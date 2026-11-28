package net.astrolab.interpreter

/** A transcript is grouped by capture time, never by translation arrival time. */
internal fun startsNewGroup(previousEnd: Long?, currentStart: Long): Boolean =
    previousEnd == null || (currentStart >= previousEnd && currentStart - previousEnd >= 60_000L)

/** Only pass terms relevant to the text at hand, not the entire personal dictionary. */
internal fun relevantTerms(terms: List<Term>, text: String, context: List<Segment>): List<Term> {
    val recent = (context.takeLast(3).map { it.raw + " " + it.corrected } + text).joinToString(" ")
    return terms.asSequence().filter { it.approved }.filter {
        (it.ru.isNotBlank() && recent.contains(it.ru, ignoreCase = true)) ||
        (it.zh.isNotBlank() && recent.contains(it.zh))
    }.sortedByDescending { it.count }.take(24).toList()
}

internal class RequestGate(private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private var blockedUntil = 0L
    private var blockedConfig: ApiConfig? = null
    private var config: ApiConfig? = null
    @Synchronized fun check(candidate: ApiConfig) {
        if (config != candidate) { config = candidate; blockedUntil = 0L; blockedConfig = null }
        if (blockedConfig == candidate) error("API authentication failed; check settings")
        if (now() < blockedUntil) error("API cooling down")
    }
    @Synchronized fun failed(candidate: ApiConfig, status: Int?, retryMs: Long? = null) {
        if (candidate != config) return
        if (status in listOf(401, 402, 403, 404)) blockedConfig = candidate
        else blockedUntil = now() + (retryMs ?: if(status == 429) 30_000L else 15_000L).coerceIn(1_000L, 300_000L)
    }
    @Synchronized fun reset() { blockedUntil=0;blockedConfig=null;config=null }
}

/** A segment may contain more than one writing system, irrespective of Whisper's LID. */
internal fun scriptLanguages(text: String): Set<String> = buildSet {
    if (text.any { it in '\u4e00'..'\u9fff' }) add("zh")
    if (text.any { it in '\u0400'..'\u052f' }) add("ru")
    if (text.count { it in 'a'..'z' || it in 'A'..'Z' } >= 2) add("en")
}
internal fun mixedCode(text: String, fallback: String): String {
    val scripts = scriptLanguages(text)
    return if(scripts.size > 1) scripts.sorted().joinToString("+") else fallback
}
// SenseVoice emits uppercase English by design. Normalize casing only, not words.
private val latinWords=Regex("[A-Za-z]+(?:'[A-Za-z]+)?")
private val technicalAcronyms=setOf("AI","API","ASR","ADB","APK","CPU","GPU","RAM","ROM","USB","RTC","GPS","ETCS","ATP","ATO","CBTC","VAD","TTS","NLP","HTTP","HTTPS","IP","TCP","UDP","USB","OS","UI","MR","AR","VR","LED","LCD","OLED","VFD","PLC","CAD","ISO","PDF","PC","SDK","ONNX","CTC","DSP")
internal fun normalizeSenseVoiceCase(text:String,reference:String):String{
    val tokens=latinWords.findAll(text).map{it.value}.toList()
    if(tokens.none{it.length>=2}||tokens.any{it.any(Char::isLowerCase)})return text
    val preferred=latinWords.findAll(reference).map{it.value}.filter{it.any(Char::isLowerCase)}
        .associateBy{it.lowercase(java.util.Locale.ROOT)}
    val names=mapOf("ENGLISH" to "English","RUSSIAN" to "Russian","CHINESE" to "Chinese","I" to "I")
    return latinWords.replace(text){m->
        val word=m.value
        preferred[word.lowercase(java.util.Locale.ROOT)]?:names[word]?:if(word in technicalAcronyms)word else word.lowercase(java.util.Locale.ROOT)
    }
}
internal fun selectBilingual(whisper: Recognition, candidate: String): Recognition {
    if (whisper.language !in setOf("zh","en") || "ru" in scriptLanguages(whisper.text)) return whisper
    val scripts=scriptLanguages(candidate)
    // A separate acoustic model must actually hear both scripts. Never infer English
    // from a Chinese translation, and never replace a Russian transcription.
    if (scripts != setOf("zh","en")) return whisper
    val normalized=candidate.replace(Regex("(?<=[\\p{IsHan}])(?=[A-Za-z])|(?<=[A-Za-z])(?=[\\p{IsHan}])")," ")
    return Recognition(normalizeSenseVoiceCase(normalized,whisper.text),"en+zh")
}
internal fun correctionKeepsScripts(raw: String, corrected: String): Boolean {
    val original=scriptLanguages(raw)
    return original.isEmpty() || scriptLanguages(corrected).containsAll(original)
}
internal fun commonPrefixLength(a:String,b:String):Int {
    var n=0
    while(n<minOf(a.length,b.length)&&a[n]==b[n])n++
    if(n>0&&Character.isHighSurrogate(a[n-1]))n--
    return n
}

internal data class ScriptRun(val language:String,val text:String)
internal fun scriptRuns(text:String):List<ScriptRun> {
    val result=mutableListOf<ScriptRun>()
    val buffer=StringBuilder()
    var language=""
    for(c in text){
        val next=when(c){
            in '\u4e00'..'\u9fff'->"zh"
            in '\u0400'..'\u052f'->"ru"
            in 'a'..'z',in 'A'..'Z'->"en"
            else->""
        }
        if(next.isNotEmpty()&&language.isNotEmpty()&&next!=language){
            result.add(ScriptRun(language,buffer.toString()))
            buffer.setLength(0)
        }
        if(next.isNotEmpty())language=next
        buffer.append(c)
    }
    if(buffer.isNotEmpty())result.add(ScriptRun(language,buffer.toString()))
    return result
}

/** Return contiguous completed indices, regardless of network completion order. */
internal class OrderedCompletion {
    private var next=0
    private val completed=mutableSetOf<Int>()
    fun mark(index:Int):List<Int>{
        if(index<next)return emptyList()
        completed.add(index)
        val ready=mutableListOf<Int>()
        while(completed.remove(next))ready.add(next++)
        return ready
    }
    fun clear(){next=0;completed.clear()}
}

private val spokenWords=Regex("[\\p{L}\\p{N}]+")
/** Detect decoder loops, not ordinary emphasis or a single repeated word. */
internal fun hasDecodeLoop(text:String):Boolean{
    // Decoder loops also occur in punctuation and symbols, not just words.
    val compact=text.filterNot{it.isWhitespace()}.take(512)
    for(i in compact.indices){
        if(compact[i].isLetterOrDigit())continue
        for(period in 1..minOf(4,(compact.length-i)/5)){
            if((0 until period).all{!compact[i+it].isLetterOrDigit()}&&
                (period until period*5).all{compact[i+it]==compact[i+it%period]})return true
        }
    }
    val words=spokenWords.findAll(text.lowercase(java.util.Locale.ROOT)).map{it.value}.take(512).toList()
    for(i in words.indices){
        for(period in 1..minOf(8,(words.size-i)/4)){
            if((period until period*4).all{words[i+it]==words[i+it%period]})return true
        }
    }
    val han=text.filter{it in '\u4e00'..'\u9fff'}.take(512)
    for(i in han.indices){
        for(period in 1..minOf(12,(han.length-i)/5)){
            if((period until period*5).all{han[i+it]==han[i+it%period]})return true
        }
    }
    return false
}
internal fun plausibleCaption(text:String,samples:Int):Boolean {
    if(text.isBlank()||text.none{it.isLetterOrDigit()}||hasDecodeLoop(text))return false
    val seconds=samples.coerceAtLeast(1)/16000.0
    val words=spokenWords.findAll(text).count()
    val han=text.count{it in '\u4e00'..'\u9fff'}
    return words<=maxOf(8.0,seconds*6.0+3)&&han<=maxOf(12.0,seconds*12.0+6)&&text.length<=maxOf(100.0,seconds*60.0+40)
}
/** Keep a good provisional hypothesis visible until a conflicting one is corroborated. */
internal class PreviewStabilizer {
    private var visible:Recognition?=null
    private var candidate:Recognition?=null
    fun offer(next:Recognition,samples:Int):Recognition?{
        if(!plausibleCaption(next.text,samples))return visible
        val old=visible
        if(old==null){visible=next;candidate=null;return visible}
        val prefix=commonPrefixLength(old.text,next.text)
        val compatible=old.language==next.language&&(prefix>=minOf(old.text.length,8)||prefix>=old.text.length*.65)
        val repeatedCandidate=candidate?.let{it.language==next.language&&commonPrefixLength(it.text,next.text)>=minOf(it.text.length,next.text.length,10)}==true
        if(compatible||repeatedCandidate){visible=next;candidate=null}
        else candidate=next
        return visible
    }
    fun reset(){visible=null;candidate=null}
}
internal fun asrLanguage(recognition:Recognition):String =
    if(recognition.language=="ru"&&"ru" in scriptLanguages(recognition.text)&&"zh" !in scriptLanguages(recognition.text))"ru"
    else mixedCode(recognition.text,recognition.language)

internal fun validCloudTranslation(raw:String,translated:String):Boolean =
    translated.any{it.isLetterOrDigit()}&&!hasDecodeLoop(translated)&&translated.length<=maxOf(180,raw.length*8)

/** Token-level LCS highlights only inserted/replaced words, including Han characters. */
private val editTokens=Regex("[\\p{IsHan}]|[\\p{L}\\p{M}\\p{N}&&[^\\p{IsHan}]]+|[^\\s]")
internal fun changedRanges(before:String?,after:String):List<IntRange>{
    if(before==null||before==after)return emptyList()
    val a=editTokens.findAll(before).toList();val b=editTokens.findAll(after).toList()
    if(a.size>256||b.size>256){
        val start=commonPrefixLength(before,after)
        var end=after.length;var oldEnd=before.length
        while(end>start&&oldEnd>start&&after[end-1]==before[oldEnd-1]){end--;oldEnd--}
        return if(start<end)listOf(start until end) else emptyList()
    }
    val dp=Array(a.size+1){IntArray(b.size+1)}
    for(i in a.indices.reversed())for(j in b.indices.reversed()){
        dp[i][j]=if(a[i].value==b[j].value)1+dp[i+1][j+1] else maxOf(dp[i+1][j],dp[i][j+1])
    }
    val keep=BooleanArray(b.size)
    var i=0;var j=0
    while(i<a.size&&j<b.size){
        if(a[i].value==b[j].value){keep[j]=true;i++;j++}
        else if(dp[i+1][j]>=dp[i][j+1])i++ else j++
    }
    return b.indices.filter{!keep[it]}.map{b[it].range}
}
internal fun canReplaceRevision(existing:Segment,expected:Int)=existing.manualRevision==expected

internal data class LanguageProbe(val center:Int,val language:String)
internal data class LanguageSpan(val start:Int,val end:Int,val language:String)
/** Ignore isolated language predictions; locate a switch between stable acoustic runs. */
internal fun languageSpans(samples:FloatArray,probes:List<LanguageProbe>):List<LanguageSpan>{
    val valid=probes.filter{it.language in setOf("zh","en","ru")}
    if(valid.size<2)return emptyList()
    data class Run(val first:Int,val last:Int,val language:String)
    val runs=mutableListOf<Run>()
    var i=0
    while(i<valid.size){
        var j=i+1
        while(j<valid.size&&valid[j].language==valid[i].language)j++
        if(j-i>=2){
            val next=Run(valid[i].center,valid[j-1].center,valid[i].language)
            if(runs.lastOrNull()?.language==next.language)runs[runs.lastIndex]=runs.last().copy(last=next.last)
            else runs.add(next)
        }
        i=j
    }
    if(runs.size !in 2..6)return emptyList()
    val result=mutableListOf<LanguageSpan>()
    var start=0
    for(k in 1 until runs.size){
        val center=(runs[k-1].last+runs[k].first)/2
        val lo=maxOf(start+4800,center-4800)
        val hi=minOf(samples.size-4800,center+4800)
        if(lo>hi)return emptyList()
        val cut=(lo..hi step 160).minByOrNull{at->
            var power=0.0
            val left=maxOf(0,at-160);val right=minOf(samples.size,at+160)
            for(j in left until right)power+=samples[j]*samples[j]
            kotlin.math.ln1p(100*kotlin.math.sqrt(power/maxOf(1,right-left)))+
                kotlin.math.abs(at-center)/4800.0*1.6
        }?:return emptyList()
        result.add(LanguageSpan(start,cut,runs[k-1].language));start=cut
    }
    result.add(LanguageSpan(start,samples.size,runs.last().language))
    return result
}
internal data class SoundScore(val name:String,val probability:Float)
internal fun soundLabel(scores:List<SoundScore>):String?{
    // Select only supported events with meaningful evidence; do not label general noise.
    val top=scores.firstOrNull()?:return null
    fun score(vararg names:String)=scores.filter{it.name in names}.maxOfOrNull{it.probability}?:0f
    if(score("Cough","Throat clearing")>=.25f)return "咳嗽"
    if(score("Sneeze")>=.20f&&top.name=="Sneeze")return "打喷嚏"
    if(score("Laughter","Snicker","Chuckle, chortle","Giggle","Baby laughter")>=.28f)return "笑声"
    if(score("Applause","Clapping")>=.35f)return "掌声"
    if(score("Music","Vocal music","Musical instrument","Singing","A capella")>=.40f&&
        (top.name in setOf("Music","Vocal music","Musical instrument","Singing","A capella","Choir")||top.name.endsWith(" music")))return "音乐"
    return null
}
internal fun soundEventText(label:String)="[$label]"
internal fun preserveSource(raw:String)=raw
internal fun joinAcousticParts(parts:List<Recognition>):Recognition{
    val text=parts.joinToString(" "){it.text.trim().trimEnd('。','.','！','!','？','?')}
    return Recognition(text,scriptLanguages(text).sorted().joinToString("+"))
}

/** Swiping only reveals an action; it never deletes a session. */
internal fun shouldRevealDelete(offset:Float,width:Float,velocity:Float):Boolean =
    when{velocity>600f->false;velocity< -600f->true;else->offset< -width*.45f}

/** Prefer SenseVoice for acoustically Chinese input; never substitute it for Russian. */
internal fun chooseChineseCaption(primary:Recognition,candidate:String,samples:Int,forceChinese:Boolean=false):Recognition{
    val scripts=scriptLanguages(candidate)
    if(!plausibleCaption(candidate,samples)||"zh" !in scripts||"ru" in scripts)return primary
    if(!forceChinese&&(primary.language!="zh"||"ru" in scriptLanguages(primary.text)))return primary
    // A short fragment must not replace a substantially more complete, plausible decode.
    if(isIncompleteChineseCandidate(primary.text,candidate,samples))return primary
    val normalized=normalizeSenseVoiceCase(candidate,primary.text)
    return Recognition(normalized,mixedCode(normalized,"zh"))
}

internal fun isIncompleteChineseCandidate(primary:String,candidate:String,samples:Int):Boolean{
    if(!plausibleCaption(primary,samples))return false
    val a=primary.count{it in '\u4e00'..'\u9fff'}
    val b=candidate.count{it in '\u4e00'..'\u9fff'}
    return a>=6&&b*2<a
}

/** Typed text is user-authored, not an ASR hypothesis: keep punctuation and repetition. */
internal fun quickInputText(value:String):String? =
    value.trim().takeIf{it.isNotEmpty()&&it.length<=2000&&it.any{c->c.isLetterOrDigit()}}
internal fun quickInputLanguage(value:String,fallback:String):String =
    mixedCode(value,fallback).ifBlank{"zh"}

/** Non-speech captions are localized locally, with no translation request. */
internal fun localizedSoundEvent(label:String,language:String):String {
    val name=when(language){
        "ru"->when(label){"咳嗽"->"Кашель";"音乐"->"Музыка";"笑声"->"Смех";"掌声"->"Аплодисменты";"打喷嚏"->"Чихание";else->label}
        "en"->when(label){"咳嗽"->"Cough";"音乐"->"Music";"笑声"->"Laughter";"掌声"->"Applause";"打喷嚏"->"Sneezing";else->label}
        else->label
    }
    return soundEventText(name)
}
internal fun soundEventTarget(items:List<Segment>,at:Long):String =
    items.asReversed().firstNotNullOfOrNull{item->
        if(item.soundEvent.isNotBlank()||item.startTime>at)null
        else if("ru" in item.language.split('+')||item.target=="ru")"ru"
        else if("en" in item.language.split('+')||item.target=="en")"en"
        else null
    }?:"ru"

/** Keep meaningful text, including uncertain fragments, in actual capture order. */
internal fun summarySpeech(items:List<Segment>):List<Segment> =
    items.filter{it.soundEvent.isBlank()&&(it.raw.isNotBlank()||it.corrected.isNotBlank()||it.translated.isNotBlank())}
        .sortedBy{it.startTime}

internal fun summaryChunks(items:List<Segment>,maxChars:Int=18000,maxEntries:Int=120):List<List<Segment>> {
    require(maxChars>0&&maxEntries>0)
    val out=mutableListOf<List<Segment>>()
    var chunk=mutableListOf<Segment>()
    var size=0
    for(item in summarySpeech(items)){
        val n=item.raw.length+item.corrected.length+item.translated.length+item.error.length+180
        if(chunk.isNotEmpty()&&(size+n>maxChars||chunk.size>=maxEntries)){
            out.add(chunk);chunk=mutableListOf();size=0
        }
        chunk.add(item);size+=n
    }
    if(chunk.isNotEmpty())out.add(chunk)
    return out
}

/** Readiness is derived from saved transcript, not from a transient request or error. */
internal fun needsSessionSummary(session:Session):Boolean =
    session.summary.isBlank()&&summarySpeech(session.items).any{
        it.raw.isNotBlank()||(it.corrected.isNotBlank()&&it.corrected!="识别未完成")||it.translated.isNotBlank()
    }
internal fun summaryAvailability(cloud:Boolean,hasKey:Boolean,connected:Boolean):String? = when{
    !cloud->"纯本地模式 · 联网后可整理"
    !connected->"已本地保存 · 联网后可整理"
    !hasKey->"配置 API 后可整理"
    else->null
}

/** Notify once per settled transition; launching online is silent. */
internal fun networkTransitionNotice(previous:Boolean?,connected:Boolean):String? =
    if(previous==connected||(previous==null&&connected))null
    else if(connected)"网络已恢复" else "网络已断开，继续本地处理"
