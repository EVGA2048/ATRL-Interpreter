package net.astrolab.interpreter

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig

private val cobalt = Color(0xFF2859CC)
private val graphite = Color(0xFF252A32)
private val corner = RoundedCornerShape(12.dp)

class MainActivity : ComponentActivity() {
    private lateinit var engine: Engine
    private lateinit var archive: Archive
    private lateinit var settings: ApiSettings
    private var api by mutableStateOf(ApiConfig())
    private var cloud by mutableStateOf(true)
    private var page by mutableStateOf("text")
    private var message by mutableStateOf("")
    private var draft by mutableStateOf("")
    private var direction by mutableStateOf("Auto")
    private var textResult by mutableStateOf<Segment?>(null)
    private var textBusy by mutableStateOf(false)
    private var terms by mutableStateOf(listOf<Term>())
    private var sessions by mutableStateOf(listOf<Session>())
    private var current by mutableStateOf<Session?>(null)
    private var viewed by mutableStateOf<Long?>(null)
    private var level by mutableFloatStateOf(0f)
    private var recording by mutableStateOf(false)
    private var finishing by mutableStateOf(false)
    private var pending by mutableIntStateOf(0)
    private var partial by mutableStateOf<Recognition?>(null)
    private var partialGeneration=0
    private var captureWarning by mutableStateOf("")
    private val localTranslation=Executors.newSingleThreadExecutor()
    private var captureStatus by mutableStateOf("就绪")
    private var modelStatus by mutableStateOf("")
    private var downloading by mutableStateOf(false)
    private var autoSpeak by mutableStateOf(false)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var summaryBusy by mutableStateOf<Long?>(null)
    private val active = AtomicBoolean(false)
    private val asrJobs = AtomicInteger(0)
    private val recognition = Executors.newSingleThreadExecutor()
    private val network = Executors.newSingleThreadExecutor()
    private val modelDownload = Executors.newSingleThreadExecutor()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCapture() else captureStatus = "请允许麦克风权限"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = Engine(applicationContext)
        archive = Archive(this); settings = ApiSettings(this)
        runCatching { settings.load() }.onSuccess { api = it }.onFailure { message = "API 配置读取失败，请重新保存" }
        terms = archive.terms(); sessions = archive.sessions(); cloud = archive.cloudEnabled()
        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if(dark) darkColorScheme(primary=Color(0xFF9BB5FF),background=Color(0xFF101113),surface=Color(0xFF1D1E21),surfaceVariant=Color(0xFF2C2D31),onSurface=Color(0xFFF4F4F5),onSurfaceVariant=Color(0xFFADB0B7),outline=Color(0xFF414349))
                else lightColorScheme(primary=cobalt,background=Color(0xFFF5F5F7),surface=Color.White,surfaceVariant=Color(0xFFE9E9ED),onSurface=Color(0xFF17171A),onSurfaceVariant=Color(0xFF74747B),outline=Color(0xFFD7D7DB),secondary=cobalt)
            MaterialTheme(colorScheme=scheme,shapes=Shapes(small=corner,medium=corner,large=corner)){
                Surface(Modifier.fillMaxSize(),color=scheme.background){
                    BackHandler(page !in listOf("text","live","notes","terms")){navigate(if(page=="detail")"notes" else previousPage)}
                    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()){
                        val wide=maxWidth>=650.dp
                        Column(Modifier.align(Alignment.TopCenter).widthIn(max=920.dp).fillMaxSize()){
                            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp).heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically){
                                if(page=="settings"||page=="detail") TextButton(onClick={navigate(if(page=="detail")"notes" else previousPage)},contentPadding=PaddingValues(end=12.dp)){Text("‹ 返回",fontSize=16.sp)}
                                Text(when(page){"text"->"翻译";"live"->"传译";"notes"->"记录";"terms"->"词库";"settings"->"设置";else->"会话"},fontSize=if(page=="detail"||page=="settings")22.sp else 30.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                                if(page!="settings"&&page!="detail")IconButton(onClick={previousPage=page;navigate("settings")}){NavGlyph("settings",scheme.onSurface,Modifier.size(24.dp))}
                            }
                            key(page){
                                if(page=="live") {
                                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal=if(wide)32.dp else 16.dp)){LivePage()}
                                } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=if(wide)32.dp else 16.dp).padding(top=8.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                                    if(message.isNotBlank())Text(message,color=scheme.primary,fontSize=13.sp)
                                    when(page){"text"->TextPage();"notes"->NotesPage();"terms"->TermsPage();"detail"->DetailPage();"settings"->SettingsPage()}
                                }
                            }
                            if(page!="settings"&&page!="detail"){
                                HorizontalDivider(color=scheme.outline.copy(alpha=.6f))
                                Row(Modifier.fillMaxWidth().background(scheme.surface).padding(vertical=6.dp)){
                                    for((id,label) in listOf("text" to "翻译","live" to "传译","notes" to "记录","terms" to "词库")){
                                        val selected=page==id
                                        val color=if(selected)scheme.primary else scheme.onSurfaceVariant
                                        Column(Modifier.weight(1f).heightIn(min=54.dp).clickable{navigate(id)}.padding(vertical=4.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                                            NavGlyph(id,color,Modifier.size(23.dp))
                                            Spacer(Modifier.height(4.dp));Text(label,color=color,fontSize=11.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private var previousPage="text"
    private fun navigate(to:String){page=to;message=""}
    @Composable private fun NavGlyph(id:String,color:Color,modifier:Modifier=Modifier){
        Canvas(modifier.semantics{contentDescription=if(id=="settings")"设置" else ""}){
            val w=size.width;val h=size.height;val st=1.8.dp.toPx()
            fun line(x1:Float,y1:Float,x2:Float,y2:Float)=drawLine(color,Offset(w*x1,h*y1),Offset(w*x2,h*y2),st,StrokeCap.Round)
            when(id){
                "text"->{drawRoundRect(color,Offset(w*.06f,h*.11f),Size(w*.72f,h*.61f),androidx.compose.ui.geometry.CornerRadius(w*.12f),style=Stroke(st));line(.25f,.72f,.17f,.90f);line(.22f,.31f,.61f,.31f);line(.22f,.48f,.49f,.48f);line(.84f,.40f,.93f,.40f);line(.93f,.40f,.93f,.91f)}
                "live"->{for(i in 0..4){val x=.12f+i*.19f;val a=listOf(.18f,.35f,.45f,.30f,.14f)[i];line(x,.5f-a,x,.5f+a)}}
                "notes"->{drawRoundRect(color,Offset(w*.18f,h*.06f),Size(w*.65f,h*.87f),androidx.compose.ui.geometry.CornerRadius(w*.08f),style=Stroke(st));for(i in 0..2)line(.33f,.30f+i*.18f,.69f,.30f+i*.18f)}
                "terms"->{line(.50f,.20f,.50f,.91f);line(.08f,.13f,.50f,.20f);line(.50f,.20f,.92f,.13f);line(.08f,.13f,.08f,.83f);line(.92f,.13f,.92f,.83f);line(.08f,.83f,.50f,.91f);line(.92f,.83f,.50f,.91f)}
                else->{drawCircle(color,w*.20f,style=Stroke(st));drawCircle(color,w*.39f,style=Stroke(st));for(i in 0..7){val a=i*Math.PI/4;line((.5+.39*kotlin.math.cos(a)).toFloat(),(.5+.39*kotlin.math.sin(a)).toFloat(),(.5+.49*kotlin.math.cos(a)).toFloat(),(.5+.49*kotlin.math.sin(a)).toFloat())}}
            }
        }
    }
    @Composable private fun Panel(content:@Composable ColumnScope.()->Unit){
        Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()){
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
        }
    }
    private fun languageName(code:String)=when(code){"zh"->"简体中文";"ru"->"俄语";"en"->"英语";else->"未识别"}
    @Composable private fun LanguageMenu(){
        var expanded by remember{mutableStateOf(false)}
        val detected=engine.detectText(draft)
        val labels=mapOf("Auto" to "自动选择目标语言","Chinese" to "简体中文","Russian" to "俄语","English" to "英语")
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
            Text(if(detected.isBlank())"检测语言" else languageName(detected),color=MaterialTheme.colorScheme.primary,fontSize=16.sp,fontWeight=FontWeight.Medium)
            Text("→",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=20.sp)
            Box {
                TextButton(onClick={expanded=true}){Text((if(direction=="Auto" && detected.isBlank()) "目标语言" else languageName(engine.targetFor(detected,direction)))+" ▾",fontSize=16.sp)}
                DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){
                    labels.forEach{(id,label)->DropdownMenuItem(text={Text(label)},onClick={direction=id;textResult=null;expanded=false})}
                }
            }
        }
    }
    @Composable private fun TextPage(){
        Panel{
            LanguageMenu()
            HorizontalDivider(color=MaterialTheme.colorScheme.outline.copy(alpha=.5f))
            BasicTextField(value=draft,onValueChange={draft=it;textResult=null},modifier=Modifier.fillMaxWidth().heightIn(min=128.dp),textStyle=TextStyle(color=MaterialTheme.colorScheme.onSurface,fontSize=23.sp,lineHeight=31.sp),cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),decorationBox={inner->
                Box{if(draft.isEmpty())Text("输入文字",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=23.sp);inner()}
            })
            Row(verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick={draft="";textResult=null},enabled=draft.isNotEmpty()&&!textBusy){Text("清除")}
                Spacer(Modifier.weight(1f))
                Button(onClick={translateTyped()},enabled=draft.isNotBlank()&&!textBusy,shape=RoundedCornerShape(22.dp)){Text(if(textBusy)"翻译中…" else "翻译")}
            }
        }
        textResult?.let{s->Panel{
            Text(languageName(s.target),color=MaterialTheme.colorScheme.primary,fontSize=14.sp,fontWeight=FontWeight.Medium)
            Text(s.translated.ifBlank{if(s.error.isBlank())"正在翻译…" else "未获得译文"},fontSize=24.sp,lineHeight=33.sp)
            if(s.error.isNotBlank())Text(s.error,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton(onClick={speak(s.translated,s.target)},enabled=s.translated.isNotBlank()){Text("朗读")}
                TextButton(onClick={copyText(s.translated)},enabled=s.translated.isNotBlank()){Text("复制")}
            }
        }}
        if(textResult==null&&!textBusy&&draft.isBlank())TextButton(onClick={navigate("live")},modifier=Modifier.alignForCenter()){Text("使用麦克风")}
    }
    private fun Modifier.alignForCenter()=this.fillMaxWidth()
    private fun copyText(value:String){
        val cm=getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Interpreter",value))
    }
    private fun translateTyped() {
        if(textBusy || draft.isBlank()) return
        val raw=draft.trim(); val config=api; val online=cloud; val dir=direction; val glossary=terms
        textBusy=true; textResult=null
        network.execute {
            val result=process(raw,config,online,dir,false,emptyList(),glossary)
            runOnUiThread { if(draft.trim()==raw&&direction==dir)textResult=result.first; mergeTerms(result.second); textBusy=false }
        }
    }
    private fun process(raw:String,config:ApiConfig,online:Boolean,dir:String,speech:Boolean,context:List<Segment>,glossary:List<Term>,source:String=engine.detectText(raw),onLocal:((Segment)->Unit)?=null):Pair<Segment,List<Term>> {
        val lang=source.ifBlank{engine.detectText(raw)}
        val target=engine.targetFor(lang,dir)
        val base=Segment(raw,language=lang,target=target)
        val local=runCatching{engine.offline(raw,lang,target)}
        if(local.isSuccess)onLocal?.invoke(base.copy(translated=local.getOrThrow()))
        if(online&&config.key.isNotBlank()){
            try{
                val a=engine.analyze(config,raw,context,glossary,target,speech,lang)
                val corrected=if(speech&&engine.detectText(a.corrected)==lang)a.corrected else raw
                return base.copy(corrected=corrected,translated=a.translated,scene=a.scene) to a.terms
            }catch(e:Exception){
                return base.copy(translated=local.getOrDefault(""),error="云端翻译失败"+if(local.isFailure)"，请检查网络或下载离线模型" else "，已使用离线译文") to emptyList()
            }
        }
        return base.copy(translated=local.getOrDefault(""),error=if(local.isFailure)"离线翻译不可用，请在设置中下载语言包" else "") to emptyList()
    }
    private fun mergeTerms(found:List<Term>) {
        if(found.isEmpty())return
        val list=terms.toMutableList()
        for(t in found) {
            val i=list.indexOfFirst { it.ru.equals(t.ru,true)&&it.zh==t.zh }
            if(i>=0){val old=list[i];list[i]=old.copy(count=old.count+1,category=if(old.category=="未分类")t.category else old.category,example=old.example.ifBlank{t.example})} else list.add(t)
        }
        terms=list; archive.saveTerms(terms)
    }
    private fun timeLabel(time:Long)=java.text.SimpleDateFormat("HH:mm",Locale.getDefault()).format(java.util.Date(time))
    private fun newGroup(items:List<Segment>,index:Int)=startsNewGroup(items.getOrNull(index-1)?.time,items[index].startTime)
    @Composable private fun CaptionPair(s:Segment){
        val scheme=MaterialTheme.colorScheme
        val done=s.translated.isNotBlank()
        Column(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
            SelectionContainer{
                Text(s.corrected.ifBlank{s.raw},fontSize=16.sp,lineHeight=23.sp,color=if(done)scheme.onSurface else scheme.onSurfaceVariant)
            }
            if(done)SelectionContainer{
                Text(s.translated,fontSize=19.sp,lineHeight=27.sp,color=scheme.primary,fontWeight=FontWeight.Medium)
            }
            if(s.error.isNotBlank())Text(s.error,fontSize=11.sp,color=scheme.onSurfaceVariant)
            if(s.corrected.isNotBlank()&&s.corrected!=s.raw){
                var showRaw by remember(s.raw){mutableStateOf(false)}
                Text(if(showRaw)s.raw else "查看原始识别",fontSize=11.sp,color=scheme.onSurfaceVariant,modifier=Modifier.clickable{showRaw=!showRaw})
            }
        }
    }
    @Composable private fun GroupTime(s:Segment){
        Row(Modifier.fillMaxWidth().padding(top=14.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically){
            Text(timeLabel(s.startTime),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            HorizontalDivider(Modifier.weight(1f),color=MaterialTheme.colorScheme.outline.copy(alpha=.5f))
        }
    }
    @Composable private fun LivePage(){
        val items=current?.items.orEmpty()
        val draft=partial
        val list=rememberLazyListState()
        var following by remember{mutableStateOf(true)}
        val total=items.size+1
        val dragging by list.interactionSource.collectIsDraggedAsState()
        LaunchedEffect(dragging,list.canScrollForward){
            if(dragging)following=!list.canScrollForward
            else if(!list.canScrollForward)following=true
        }
        LaunchedEffect(items.size,draft?.text,pending){
            if(following)list.scrollToItem(total-1)
        }
        Column(Modifier.fillMaxSize()){
            Row(Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
                Text(captureStatus,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f))
                if(pending>0)Text("翻译中 $pending",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(!following)TextButton(onClick={following=true;}){Text("回到最新")}
            }
            LaunchedEffect(following){if(following)list.scrollToItem(total-1)}
            if(captureWarning.isNotBlank())Text(captureWarning,fontSize=12.sp,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(vertical=4.dp))
            Surface(Modifier.weight(1f).fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface){
                LazyColumn(state=list,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp,12.dp)){
                    itemsIndexed(items,key={i,_->i}){i,s->
                        if(newGroup(items,i))GroupTime(s)
                        if(i==0||newGroup(items,i)||items[i-1].language!=s.language)Text(languageName(s.language.ifBlank{engine.detectText(s.raw)}),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=3.dp))
                        CaptionPair(s)
                    }
                    item(key="tail"){
                        if(draft!=null&&draft.text.isNotBlank()){
                            Text(draft.text,fontSize=16.sp,lineHeight=23.sp,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.78f),modifier=Modifier.padding(vertical=5.dp))
                        } else if(items.isEmpty()){
                            Text(if(recording)"正在聆听…" else "字幕将在这里显示",fontSize=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=12.dp))
                        }
                    }
                }
            }
            current?.summary?.takeIf{it.isNotBlank()}?.let{
                TextButton(onClick={viewed=current?.time;navigate("detail")}){Text("查看会话总结")}
            }
            Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically){
                Canvas(Modifier.width(95.dp).height(30.dp)){
                    for(i in 0..17){val x=size.width*(i+.5f)/18;val h=1.5f+level*size.height*.44f*(.3f+(i%9)/12f);drawLine(cobalt,Offset(x,size.height/2-h),Offset(x,size.height/2+h),strokeWidth=2.dp.toPx(),cap=StrokeCap.Round)}
                }
                Spacer(Modifier.weight(1f));Text("朗读",fontSize=13.sp)
                Switch(checked=autoSpeak,onCheckedChange={autoSpeak=it},modifier=Modifier.padding(start=6.dp))
            }
            Button(onClick={
                if(recording)stopCapture()
                else if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startCapture()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            },enabled=!finishing,modifier=Modifier.fillMaxWidth().height(50.dp).padding(bottom=2.dp),shape=RoundedCornerShape(25.dp),colors=ButtonDefaults.buttonColors(containerColor=if(recording)Color(0xFFC83C42) else MaterialTheme.colorScheme.primary)){
                Text(if(recording)"结束会话" else if(finishing)"正在整理…" else "开始聆听",fontSize=16.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    private fun startCapture() {
        if(finishing || !active.compareAndSet(false,true))return
        val started=System.currentTimeMillis()
        current=Session("会话 "+java.text.SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(java.util.Date(started)),emptyList(),time=started)
        CaptureService.stopRequested = { runOnUiThread { stopCapture() } }
        try { startForegroundService(android.content.Intent(this,CaptureService::class.java)) }
        catch(e:Exception) { active.set(false); CaptureService.stopRequested=null; captureStatus="无法启动录音服务：${e.message}"; return }
        recording=true; captureStatus="正在聆听"; pending=0;partial=null;partialGeneration=0;captureWarning=""
        val config=api; val online=cloud
        Thread {
            var mic:AudioRecord?=null
            var vad:Vad?=null
            var error:String?=null
            try {
                val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                require(min>0)
                val input=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(min,6400))
                mic=input;check(input.state==AudioRecord.STATE_INITIALIZED);input.startRecording()
                val detector=Vad(assets,VadModelConfig(sileroVadModelConfig=SileroVadModelConfig(
                    model="asr/silero_vad.onnx",threshold=0.5f,minSilenceDuration=0.38f,minSpeechDuration=0.25f,
                    windowSize=512,maxSpeechDuration=5.0f),sampleRate=16000,numThreads=1,provider="cpu"))
                vad=detector
                val block=ShortArray(512)
                val origin=System.currentTimeMillis()
                val generation=AtomicInteger(0)
                val preroll=ArrayDeque<FloatArray>()
                val previewBlocks=ArrayList<FloatArray>()
                var previewSamples=0
                var lastPreview=0
                var lastMeter=0L
                fun drain():Boolean {
                    var drained=false
                    while(!detector.empty()) {
                        val speech=detector.front()
                        val samples=speech.samples
                        val from=origin+speech.start.toLong()*1000L/16000L
                        val until=from+samples.size.toLong()*1000L/16000L
                        detector.pop();drained=true
                        val epoch=generation.getAndIncrement()
                        if(asrJobs.get()<16) {
                            asrJobs.incrementAndGet()
                            recognition.execute {
                                try {
                                    val result=engine.transcribe(samples)
                                    runOnUiThread {
                                        if(current?.time==started&&partialGeneration<=epoch)partial=null
                                        if(result.text.isNotBlank())enqueue(result.text,result.language,started,config,online,from,until)
                                    }
                                } catch(e:Throwable) {runOnUiThread{captureWarning="识别失败，请检查语音模型"}}
                                finally {asrJobs.decrementAndGet()}
                            }
                        } else runOnUiThread {captureWarning="识别积压，部分音频未处理";partial=null}
                    }
                    return drained
                }
                while(active.get()) {
                    val n=input.read(block,0,block.size)
                    if(n<0)error("AudioRecord $n")
                    if(n==0)continue
                    val samples=FloatArray(n){block[it]/32768f}
                    val rms=sqrt(samples.sumOf{(it*it).toDouble()}/n)
                    val meterNow=android.os.SystemClock.elapsedRealtime()
                    if(meterNow-lastMeter>=80L){lastMeter=meterNow;runOnUiThread {level=(rms*5).toFloat().coerceIn(0f,1f)}}
                    detector.acceptWaveform(samples)
                    if(drain()){
                        previewBlocks.clear();previewSamples=0;lastPreview=0;preroll.clear()
                    }
                    if(detector.isSpeechDetected()){
                        if(previewBlocks.isEmpty()){
                            previewBlocks.addAll(preroll);previewSamples=preroll.sumOf{it.size}
                        }
                        if(previewSamples<16000*8){previewBlocks.add(samples);previewSamples+=n}
                        if(previewSamples>=20000 && previewSamples-lastPreview>=24000 && asrJobs.compareAndSet(0,1)){
                            val snapshot=FloatArray(previewSamples);var pos=0
                            for(chunk in previewBlocks){chunk.copyInto(snapshot,pos);pos+=chunk.size}
                            val epoch=generation.get();lastPreview=previewSamples
                            recognition.execute{
                                try{
                                    val result=engine.transcribe(snapshot)
                                    if(result.text.isNotBlank()&&active.get()&&generation.get()==epoch)runOnUiThread{
                                        if(current?.time==started&&active.get()&&generation.get()==epoch){partialGeneration=epoch;partial=result}
                                    }
                                }catch(_:Throwable){}finally{asrJobs.decrementAndGet()}
                            }
                        }
                    }else if(previewBlocks.isEmpty()){
                        preroll.addLast(samples);while(preroll.size>12)preroll.removeFirst()
                    }
                }
                detector.flush()
                drain()
            } catch(e:Exception){error=e.message?: "录音失败"}
            finally {
                runCatching{mic?.stop()};mic?.release();vad?.release();active.set(false)
                val err=error
                runOnUiThread {recording=false;finishing=true;level=0f;partial=null;captureStatus=err?:"正在完成识别…"}
                recognition.execute {runOnUiThread {
                    network.execute { runOnUiThread {finishSession(started,config,online,err)} }
                }}
            }
        }.start()
    }
    private fun stopCapture() { if(active.getAndSet(false)){finishing=true;captureStatus="正在结束…"} }
    private fun enqueue(raw:String,language:String,id:Long,config:ApiConfig,online:Boolean,from:Long,until:Long) {
        val s=current?.takeIf{it.time==id}?:return
        val pos=s.items.size
        val lang=language.ifBlank{engine.detectText(raw)}
        val target=engine.targetFor(lang,"Auto")
        val base=Segment(raw,language=lang,target=target,time=until,startTime=from)
        current=s.copy(items=s.items+base)
        saveCurrent();pending++
        val local=localTranslation.submit<String?>{
            val translated=runCatching{engine.offline(raw,lang,target)}.getOrNull()
            if(!translated.isNullOrBlank())runOnUiThread{
                val existing=current?.takeIf{it.time==id}?.items?.getOrNull(pos)
                if(existing!=null&&existing.translated.isBlank()&&existing.error.isBlank())
                    updateSegment(id,pos,base.copy(translated=translated))
            }
            translated
        }
        network.execute {
            val snapshot=currentContextForWorker(id,pos)
            val analysis=if(online&&config.key.isNotBlank())runCatching{engine.analyze(config,raw,snapshot.first,snapshot.second,target,true,lang)} else null
            val a=analysis?.getOrNull()
            val final=if(a!=null)base.copy(corrected=if(engine.detectText(a.corrected)==lang)a.corrected else raw,translated=a.translated,scene=a.scene)
            else {
                val translated=runCatching{local.get(15,java.util.concurrent.TimeUnit.SECONDS)}.getOrNull().orEmpty()
                base.copy(translated=translated,error=if(translated.isBlank())"翻译不可用，请检查网络或离线语言包" else "")
            }
            runOnUiThread {
                updateSegment(id,pos,final);mergeTerms(a?.terms.orEmpty())
                pending=(pending-1).coerceAtLeast(0)
                if(autoSpeak&&final.translated.isNotBlank())speak(final.translated,final.target)
            }
        }
    }
    private fun currentContextForWorker(id:Long,pos:Int):Pair<List<Segment>,List<Term>> {
        val task=java.util.concurrent.FutureTask { (current?.takeIf{it.time==id}?.items?.take(pos)?:emptyList()) to terms }
        runOnUiThread(task)
        return task.get()
    }
    private fun updateSegment(id:Long,pos:Int,segment:Segment) {
        val s=current?.takeIf{it.time==id}?:return
        if(pos !in s.items.indices)return
        val list=s.items.toMutableList();list[pos]=segment;current=s.copy(items=list);saveCurrent()
    }
    private fun saveCurrent(){current?.takeIf{it.items.isNotEmpty()}?.let{persist(it)}}
    private fun persist(s:Session) {
        sessions=(sessions.filterNot{it.time==s.time}+s).sortedByDescending{it.time}
        archive.saveSessions(sessions)
    }
    private fun finishSession(id:Long,config:ApiConfig,online:Boolean,err:String?) {
        finishing=false;recording=false;captureStatus=err ?: "会话已结束"
        CaptureService.stopRequested=null; stopService(android.content.Intent(this,CaptureService::class.java))
        val s=current?.takeIf{it.time==id}?:return
        saveCurrent()
        if(s.items.isNotEmpty()&&online&&config.key.isNotBlank()) summarize(s,config)
    }
    private fun summarize(s:Session,config:ApiConfig=api) {
        if(summaryBusy!=null)return
        if(!cloud||config.key.isBlank()){message="总结需要启用云端并配置 API";return}
        summaryBusy=s.time
        network.execute {
            val result=runCatching{engine.summarize(config,s.items)}
            runOnUiThread {
                result.onSuccess{v->val updated=s.copy(summary=v);persist(updated);if(current?.time==s.time)current=updated}
                    .onFailure{message="总结失败：${it.message}"}
                summaryBusy=null
            }
        }
    }
    @Composable private fun NotesPage() {

        if(sessions.isEmpty())Panel {Text("暂无会话");Text("结束实时传译后，会话将保存在本机。",fontSize=12.sp)}
        sessions.forEach { s -> Panel {
            Text(s.title,fontSize=18.sp);Text("${s.items.size} 段语音",fontSize=12.sp)
            if(s.summary.isNotBlank())Text(s.summary.take(100))
            Button(onClick={viewed=s.time;navigate("detail")},shape=corner){Text("打开记录")}
        }}
    }
    @Composable private fun DetailPage() {
        val s=sessions.firstOrNull{it.time==viewed}
        if(s==null){Text("记录不存在");return}
        Text(s.title,fontSize=22.sp,fontWeight=FontWeight.SemiBold)
        Panel {
            Text(if(s.summary.isBlank())"尚未生成总结" else s.summary)
            Button(onClick={summarize(s)},enabled=summaryBusy==null&&current?.takeIf{recording||finishing}?.time!=s.time,shape=corner){Text(if(summaryBusy==s.time)"总结中…" else "生成总结")}
            var confirm by remember {mutableStateOf(false)}
            TextButton(onClick={confirm=true},enabled=current?.takeIf{recording||finishing}?.time!=s.time&&summaryBusy!=s.time){Text("删除记录")}
            if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("删除此会话？")},confirmButton={TextButton(onClick={sessions=sessions.filterNot{it.time==s.time};archive.saveSessions(sessions);if(current?.time==s.time)current=null;navigate("notes")}){Text("删除")}},dismissButton={TextButton(onClick={confirm=false}){Text("取消")}})
        }
        Panel{s.items.forEachIndexed{i,item->if(newGroup(s.items,i))GroupTime(item);CaptionPair(item)}}
    }
    private val defaultCategories=listOf("铁路与交通","工程与技术","计算机与电子","学术与教育","行政与手续","医疗与健康","生活与购物","其他","未分类")
    @Composable private fun TermsPage(){
        var search by remember{mutableStateOf("")}
        var tab by remember{mutableStateOf("已确认")}
        var category by remember{mutableStateOf("全部分类")}
        var language by remember{mutableStateOf("全部语言")}
        var categoryMenu by remember{mutableStateOf(false)}
        var languageMenu by remember{mutableStateOf(false)}
        var editing by remember{mutableStateOf<Term?>(null)}
        var adding by remember{mutableStateOf(false)}
        val categories=(defaultCategories+terms.map{it.category}).distinct()
        val iconColor=MaterialTheme.colorScheme.onSurfaceVariant
        val waiting=terms.count{!it.approved}
        val filtered=terms.filter{(if(tab=="待确认")!it.approved else it.approved)&&
            (category=="全部分类"||it.category==category)&&(language=="全部语言"||it.language==language)&&
            (search.isBlank()||it.ru.contains(search,true)||it.zh.contains(search,true)||it.example.contains(search,true)||it.category.contains(search,true))}
        Row(verticalAlignment=Alignment.CenterVertically){
            Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(12.dp),modifier=Modifier.weight(1f)){
                Row(Modifier.padding(horizontal=12.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){
                    Canvas(Modifier.size(17.dp)){drawCircle(iconColor,size.width*.32f,center=Offset(size.width*.42f,size.height*.42f),style=Stroke(1.7.dp.toPx()));drawLine(iconColor,Offset(size.width*.65f,size.height*.65f),Offset(size.width*.95f,size.height*.95f),1.7.dp.toPx(),StrokeCap.Round)}
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(search,{search=it},singleLine=true,modifier=Modifier.weight(1f),textStyle=TextStyle(color=MaterialTheme.colorScheme.onSurface,fontSize=15.sp),cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),decorationBox={inner->Box{if(search.isEmpty())Text("搜索术语",fontSize=15.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);inner()}})
                }
            }
            IconButton(onClick={adding=true;editing=null}){Text("+",fontSize=29.sp,color=MaterialTheme.colorScheme.primary)}
        }
        Surface(shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surfaceVariant){
            Row(Modifier.fillMaxWidth().padding(3.dp)){
                for(t in listOf("已确认","待确认")){
                    val selected=tab==t
                    Surface(modifier=Modifier.weight(1f).height(36.dp).clickable{tab=t},shape=RoundedCornerShape(8.dp),color=if(selected)MaterialTheme.colorScheme.surface else Color.Transparent){
                        Box(contentAlignment=Alignment.Center){Text(if(t=="待确认"&&waiting>0)"待确认 ($waiting)" else t,fontSize=14.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal)}
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Box{
                TextButton(onClick={categoryMenu=true}){Text("$category ▾")}
                DropdownMenu(expanded=categoryMenu,onDismissRequest={categoryMenu=false}){(listOf("全部分类")+categories).forEach{c->DropdownMenuItem(text={Text(c)},onClick={category=c;categoryMenu=false})}}
            }
            Box{
                TextButton(onClick={languageMenu=true}){Text((if(language=="全部语言")language else languageName(language))+" ▾")}
                DropdownMenu(expanded=languageMenu,onDismissRequest={languageMenu=false}){listOf("全部语言","ru","en").forEach{l->DropdownMenuItem(text={Text(if(l=="全部语言")l else languageName(l))},onClick={language=l;languageMenu=false})}}
            }
            Spacer(Modifier.weight(1f));Text("${filtered.size} 词",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(filtered.isEmpty()){
            Box(Modifier.fillMaxWidth().padding(vertical=32.dp),contentAlignment=Alignment.Center){Text(if(search.isNotBlank())"没有匹配的术语" else if(tab=="待确认")"没有待确认的术语" else "暂无术语",fontSize=15.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        filtered.groupBy{it.category}.toSortedMap().forEach{(group,entries)->
            Text(group,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(start=6.dp,top=6.dp))
            Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surface){
                Column(Modifier.fillMaxWidth()){
                    entries.sortedWith(compareByDescending<Term>{it.count}.thenBy{it.ru.lowercase()}).forEachIndexed{i,t->
                        if(i>0)HorizontalDivider(Modifier.padding(start=16.dp),color=MaterialTheme.colorScheme.outline.copy(alpha=.45f))
                        Row(Modifier.fillMaxWidth().clickable{editing=t}.padding(horizontal=16.dp,vertical=13.dp),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                                Text(t.ru,fontSize=17.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(t.zh,fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                            }
                            Text(if(t.count>1)"${t.count} 次" else "",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(!t.approved)TextButton(onClick={terms=terms.map{if(it==t)it.copy(approved=true)else it};archive.saveTerms(terms)}){Text("确认")}
                            else Text(" ›",fontSize=22.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if(adding||editing!=null){
            val old=editing
            var foreign by remember(old,adding){mutableStateOf(old?.ru.orEmpty())}
            var chinese by remember(old,adding){mutableStateOf(old?.zh.orEmpty())}
            var cat by remember(old,adding){mutableStateOf(old?.category?:"未分类")}
            var example by remember(old,adding){mutableStateOf(old?.example.orEmpty())}
            var menu by remember{mutableStateOf(false)}
            var confirmDelete by remember{mutableStateOf(false)}
            val close={adding=false;editing=null}
            AlertDialog(onDismissRequest=close,title={Text(if(adding)"添加术语" else "术语详情")},text={
                Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedTextField(foreign,{foreign=it},label={Text("外语术语")},singleLine=true)
                    OutlinedTextField(chinese,{chinese=it},label={Text("中文释义")})
                    Box{
                        TextButton(onClick={menu=true}){Text("$cat ▾")}
                        DropdownMenu(expanded=menu,onDismissRequest={menu=false}){categories.forEach{c->DropdownMenuItem(text={Text(c)},onClick={cat=c;menu=false})}}
                    }
                    OutlinedTextField(example,{example=it},label={Text("原始例句")},minLines=2)
                    if(old!=null){
                        Text("出现 ${old.count} 次",fontSize=12.sp)
                        TextButton(onClick={speak(foreign,engine.detectText(foreign))}){Text("朗读")}
                        TextButton(onClick={confirmDelete=true},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("删除术语")}
                    }
                }
            },confirmButton={TextButton(onClick={
                val r=foreign.trim();val z=chinese.trim()
                val updated=Term(r,z,old?.count?:1,true,cat,example.trim(),if(engine.detectText(r)=="en")"en" else "ru")
                terms=terms.filterNot{it==old||it.ru.equals(r,true)&&it.zh==z}+updated
                archive.saveTerms(terms);close()
            },enabled=foreign.isNotBlank()&&chinese.isNotBlank()){Text(if(old?.approved==false)"保存并确认" else "保存")}},dismissButton={TextButton(onClick=close){Text("取消")}})
            if(confirmDelete&&old!=null)AlertDialog(onDismissRequest={confirmDelete=false},title={Text("删除此术语？")},confirmButton={TextButton(onClick={terms=terms.filterNot{it==old};archive.saveTerms(terms);close()}){Text("删除")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("取消")}})
        }
    }
    @Composable private fun SettingsPage() {

        var edit by remember{mutableStateOf(api)}
        var show by remember{mutableStateOf(false)}
        Panel {
            Text("DeepSeek Flash",fontSize=21.sp)
            Row(verticalAlignment=Alignment.CenterVertically){
                Text("启用云端语义增强",modifier=Modifier.weight(1f))
                Switch(checked=cloud,onCheckedChange={cloud=it;archive.setCloud(it)})
            }
            Text("云端增强将发送输入文字、近期会话上下文及确认术语。关闭后仅本地处理。",fontSize=12.sp)
            OutlinedTextField(edit.key,{edit=edit.copy(key=it)},label={Text("API Key")},modifier=Modifier.fillMaxWidth(),singleLine=true,
                visualTransformation=if(show)VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon={TextButton(onClick={show=!show}){Text(if(show)"隐藏" else "显示")}})
            OutlinedTextField(edit.endpoint,{edit=edit.copy(endpoint=it)},label={Text("Base URL")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(edit.model,{edit=edit.copy(model=it)},label={Text("Model")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            Button(onClick={
                val next=ApiConfig(edit.key.trim(),edit.endpoint.trim().trimEnd('/'),edit.model.trim())
                if(!next.endpoint.startsWith("https://")||next.model.isBlank()){message="需要有效 HTTPS 地址和模型名";return@Button}
                runCatching{settings.save(next)}.onSuccess{api=next;engine.resetCloud();message="已加密保存"}.onFailure{message="保存失败"}
            },modifier=Modifier.fillMaxWidth(),shape=corner){Text("保存配置")}
        }
        Panel {
            Text("离线模型",fontSize=21.sp)
            Text("中文、英语和俄语识别已内置。离线翻译需先下载语言包。",fontSize=12.sp)
            if(modelStatus.isNotBlank())Text(modelStatus,fontSize=12.sp)
            Button(onClick={
                downloading=true;modelStatus="正在准备离线模型…"
                modelDownload.execute {
                    val r=runCatching{engine.prepareOffline()}
                    runOnUiThread{downloading=false;modelStatus=if(r.isSuccess)"离线翻译模型就绪" else "下载失败：${r.exceptionOrNull()?.message}"}
                }
            },enabled=!downloading,shape=corner,modifier=Modifier.fillMaxWidth()){Text(if(downloading)"下载中…" else "下载 / 检查离线翻译模型")}
        }
        Panel {Text("版本 0.4.1");Text("录音不保存；会话文字与术语在应用私有存储中。API Key 使用 Android Keystore 加密。",fontSize=12.sp)}
    }
    private fun speak(text:String,language:String="") {
        if(text.isBlank())return
        if(!ttsReady){message="系统语音合成不可用";return}
        val t=tts?:return
        val r=t.setLanguage(when(language.ifBlank{engine.detectText(text)}){"zh"->Locale.SIMPLIFIED_CHINESE;"en"->Locale.US;else->Locale("ru","RU")})
        if(r==TextToSpeech.LANG_MISSING_DATA||r==TextToSpeech.LANG_NOT_SUPPORTED){message="系统缺少对应语音包";return}
        t.speak(text,TextToSpeech.QUEUE_ADD,null,System.nanoTime().toString())
    }
    override fun onDestroy(){if(isFinishing)stopCapture();super.onDestroy()}
}
