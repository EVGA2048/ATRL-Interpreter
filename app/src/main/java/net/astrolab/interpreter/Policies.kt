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
internal fun selectBilingual(whisper: Recognition, candidate: String): Recognition {
    if (whisper.language !in setOf("zh","en") || "ru" in scriptLanguages(whisper.text)) return whisper
    val scripts=scriptLanguages(candidate)
    // A separate acoustic model must actually hear both scripts. Never infer English
    // from a Chinese translation, and never replace a Russian transcription.
    if (scripts != setOf("zh","en")) return whisper
    val normalized=candidate.replace(Regex("(?<=[\\p{IsHan}])(?=[A-Za-z])|(?<=[A-Za-z])(?=[\\p{IsHan}])")," ")
    return Recognition(normalized,"en+zh")
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
