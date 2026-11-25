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
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.selected
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
    private var meterHistory by mutableStateOf(List(30){0f})
    private var recording by mutableStateOf(false)
    private var finishing by mutableStateOf(false)
    private var pending by mutableIntStateOf(0)
    private var partial by mutableStateOf<Recognition?>(null)
    private var russianStreaming by mutableStateOf(false)
    private var mixedRecognition by mutableStateOf(false)
    private val streamingWorker=Executors.newSingleThreadExecutor()
    private val editWorker=Executors.newFixedThreadPool(2)
    private data class SentenceEdit(val session:Long,val index:Int,val source:Boolean,val before:String,val selection:TextRange)
    private var sentenceEdit by mutableStateOf<SentenceEdit?>(null)
    private var partialGeneration=-1
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
    private val liveNetwork = Executors.newFixedThreadPool(3)
    private val russianNetwork = Executors.newSingleThreadExecutor()
    private var finalizer: (() -> Unit)? = null
    private val playbackOrder=OrderedCompletion()
    private val warming = AtomicBoolean(false)
    private val modelDownload = Executors.newSingleThreadExecutor()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCapture() else captureStatus = "请允许麦克风权限"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.auto(0xFFF5F5F7.toInt(),0xFF101113.toInt()),navigationBarStyle=SystemBarStyle.auto(0xFFFFFFFF.toInt(),0xFF1D1E21.toInt()))
        engine = Engine(applicationContext)
        archive = Archive(this); settings = ApiSettings(this)
        runCatching { settings.load() }.onSuccess { api = it }.onFailure { message = "API 配置读取失败，请重新保存" }
        terms = archive.terms(); sessions = archive.sessions(); cloud = archive.cloudEnabled(); engine.setCloudAllowed(cloud)
        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if(dark) darkColorScheme(primary=Color(0xFF9BB5FF),background=Color(0xFF101113),surface=Color(0xFF1D1E21),surfaceVariant=Color(0xFF2C2D31),onSurface=Color(0xFFF4F4F5),onSurfaceVariant=Color(0xFFADB0B7),outline=Color(0xFF414349))
                else lightColorScheme(primary=cobalt,background=Color(0xFFF5F5F7),surface=Color.White,surfaceVariant=Color(0xFFE9E9ED),onSurface=Color(0xFF17171A),onSurfaceVariant=Color(0xFF74747B),outline=Color(0xFFD7D7DB),secondary=cobalt)
            MaterialTheme(colorScheme=scheme,shapes=Shapes(small=corner,medium=corner,large=corner)){
                Surface(Modifier.fillMaxSize(),color=scheme.background){
                    sentenceEdit?.let{request->key(request){SentenceEditDialog(request)}}
                    BackHandler(page !in listOf("text","live","notes","terms")){navigate(if(page=="detail")"notes" else previousPage)}
                    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()){
                        val wide=maxWidth>=650.dp
                        Column(Modifier.align(Alignment.TopCenter).widthIn(max=920.dp).fillMaxSize()){
                            Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp,top=5.dp,bottom=8.dp).heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically){
                                if(page=="settings"||page=="detail") TextButton(onClick={navigate(if(page=="detail")"notes" else previousPage)},contentPadding=PaddingValues(end=12.dp)){Text("‹ 返回",fontSize=16.sp)}
                                Text(when(page){"text"->"翻译";"live"->"传译";"notes"->"记录";"terms"->"词库";"settings"->"设置";else->"会话"},fontSize=if(page=="detail"||page=="settings")23.sp else 32.sp,letterSpacing=(-.8).sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                                if(page=="text"||page=="live")ModeIndicator()
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
                                Row(Modifier.fillMaxWidth().background(scheme.surface).padding(horizontal=8.dp,vertical=3.dp)){
                                    for((id,label) in listOf("text" to "翻译","live" to "传译","notes" to "记录","terms" to "词库")){
                                        val activeTab=page==id
                                        val color by animateColorAsState(if(activeTab)scheme.primary else scheme.onSurfaceVariant,tween(160),label="tab")
                                        Column(Modifier.weight(1f).heightIn(min=57.dp).semantics{selected=activeTab}.clickable{navigate(id)},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                                            NavGlyph(id,color,Modifier.size(24.dp),activeTab)
                                            Spacer(Modifier.height(4.dp))
                                            Text(label,color=color,fontSize=11.sp,fontWeight=if(activeTab)FontWeight.SemiBold else FontWeight.Normal)
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
    private fun changeMode(online:Boolean){
        if(cloud==online)return
        cloud=online;archive.setCloud(online);engine.setCloudAllowed(online)
        if(!online)tts?.stop()
    }
    @Composable private fun ModeIndicator(){
        var expanded by remember{mutableStateOf(false)}
        val color=if(cloud)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        Box{
            TextButton(onClick={expanded=true},contentPadding=PaddingValues(horizontal=8.dp)){
                Canvas(Modifier.size(15.dp)){
                    val stroke=Stroke(1.6.dp.toPx(),cap=StrokeCap.Round)
                    if(cloud){
                        drawCircle(color,size.width*.44f,style=stroke)
                        drawOval(color,topLeft=Offset(size.width*.31f,size.height*.06f),size=Size(size.width*.38f,size.height*.88f),style=stroke)
                        drawLine(color,Offset(size.width*.07f,size.height*.50f),Offset(size.width*.93f,size.height*.50f),1.4.dp.toPx())
                    }else{
                        drawRoundRect(color,topLeft=Offset(size.width*.23f,size.height*.08f),size=Size(size.width*.54f,size.height*.84f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),style=stroke)
                        drawLine(color,Offset(size.width*.43f,size.height*.76f),Offset(size.width*.57f,size.height*.76f),1.4.dp.toPx(),StrokeCap.Round)
                    }
                }
                Spacer(Modifier.width(5.dp))
                Text(if(cloud)"联网" else "本地",color=color,fontSize=13.sp,fontWeight=FontWeight.Medium)
            }
            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){
                DropdownMenuItem(text={Text("纯本地模式")},trailingIcon={if(!cloud)Text("✓",color=color)},onClick={changeMode(false);expanded=false})
                DropdownMenuItem(text={Text("网络模式")},trailingIcon={if(cloud)Text("✓",color=color)},onClick={changeMode(true);expanded=false})
            }
        }
    }
    private var previousPage="text"
    private fun navigate(to:String){
        page=to;message=""
        if(to=="live"&&warming.compareAndSet(false,true)){
            recognition.execute{
                try{engine.prepareAsr()}catch(_:Exception){warming.set(false)}
            }
        }
    }
    @Composable private fun NavGlyph(id:String,color:Color,modifier:Modifier=Modifier,active:Boolean=false){
        Canvas(if(id=="settings")modifier.semantics{contentDescription="设置"} else modifier){
            val w=size.width;val h=size.height;val st=1.8.dp.toPx()
            fun line(x1:Float,y1:Float,x2:Float,y2:Float)=drawLine(color,Offset(w*x1,h*y1),Offset(w*x2,h*y2),st,StrokeCap.Round)
            fun outline(p:Path)=drawPath(p,color,style=Stroke(st,cap=StrokeCap.Round,join=androidx.compose.ui.graphics.StrokeJoin.Round))
            fun path(points:List<Pair<Float,Float>>,close:Boolean=false)=Path().apply{
                moveTo(points[0].first*w,points[0].second*h);points.drop(1).forEach{lineTo(it.first*w,it.second*h)};if(close)close()
            }
            when(id){
                "text"->{
                    val p=Path().apply{moveTo(w*.20f,h*.13f);lineTo(w*.68f,h*.13f);quadraticTo(w*.85f,h*.13f,w*.85f,h*.30f);lineTo(w*.85f,h*.57f);quadraticTo(w*.85f,h*.74f,w*.68f,h*.74f);lineTo(w*.43f,h*.74f);lineTo(w*.22f,h*.90f);lineTo(w*.25f,h*.74f);quadraticTo(w*.06f,h*.73f,w*.06f,h*.55f);lineTo(w*.06f,h*.30f);quadraticTo(w*.06f,h*.13f,w*.20f,h*.13f);close()}
                    if(active)drawPath(p,color.copy(alpha=.13f));outline(p)
                    line(.24f,.35f,.66f,.35f);line(.24f,.51f,.55f,.51f)
                }
                "live"->{for(i in 0..4){val x=.12f+i*.19f;val a=listOf(.14f,.30f,.43f,.26f,.12f)[i];drawLine(color,Offset(w*x,h*(.5f-a)),Offset(w*x,h*(.5f+a)),if(active)2.6.dp.toPx() else st,StrokeCap.Round)}}
                "notes"->{
                    val p=path(listOf(.20f to .08f,.66f to .08f,.84f to .27f,.84f to .92f,.20f to .92f),true)
                    if(active)drawPath(p,color.copy(alpha=.13f));outline(p);line(.66f,.08f,.66f,.27f);line(.66f,.27f,.84f,.27f)
                    for(i in 0..2)line(.34f,.43f+i*.15f,.69f,.43f+i*.15f)
                }
                "terms"->{
                    val p=Path().apply{moveTo(w*.50f,h*.23f);quadraticTo(w*.28f,h*.07f,w*.08f,h*.14f);lineTo(w*.08f,h*.80f);quadraticTo(w*.30f,h*.75f,w*.50f,h*.89f);quadraticTo(w*.70f,h*.75f,w*.92f,h*.80f);lineTo(w*.92f,h*.14f);quadraticTo(w*.72f,h*.07f,w*.50f,h*.23f);close()}
                    if(active)drawPath(p,color.copy(alpha=.13f));outline(p);line(.50f,.23f,.50f,.89f)
                }
                "mic"->{drawRoundRect(color,Offset(w*.35f,h*.06f),Size(w*.30f,h*.53f),androidx.compose.ui.geometry.CornerRadius(w*.15f),style=Stroke(st));drawArc(color,0f,180f,false,Offset(w*.20f,h*.25f),Size(w*.60f,h*.50f),style=Stroke(st,cap=StrokeCap.Round));line(.50f,.75f,.50f,.93f);line(.32f,.93f,.68f,.93f)}
                "copy"->{drawRoundRect(color,Offset(w*.30f,h*.26f),Size(w*.57f,h*.65f),androidx.compose.ui.geometry.CornerRadius(w*.09f),style=Stroke(st));line(.14f,.72f,.14f,.09f);line(.14f,.09f,.70f,.09f)}
                "speaker"->{outline(path(listOf(.10f to .37f,.30f to .37f,.52f to .15f,.52f to .85f,.30f to .63f,.10f to .63f),true));drawArc(color,-50f,100f,false,Offset(w*.37f,h*.24f),Size(w*.40f,h*.52f),style=Stroke(st,cap=StrokeCap.Round));drawArc(color,-49f,98f,false,Offset(w*.29f,h*.07f),Size(w*.65f,h*.86f),style=Stroke(st,cap=StrokeCap.Round))}
                "close"->{line(.27f,.27f,.73f,.73f);line(.73f,.27f,.27f,.73f)}
                "stop"->{drawRoundRect(color,Offset(w*.24f,h*.24f),Size(w*.52f,h*.52f),androidx.compose.ui.geometry.CornerRadius(w*.06f))}
                else->{drawCircle(color,w*.18f,style=Stroke(st));val p=Path().apply{for(i in 0..31){val a=i*Math.PI/16;val r=if(i%4==0||i%4==1).44 else .35;val x=w*(.5+r*kotlin.math.cos(a)).toFloat();val y=h*(.5+r*kotlin.math.sin(a)).toFloat();if(i==0)moveTo(x,y) else lineTo(x,y)};close()};outline(p)}
            }
        }
    }
    @Composable private fun Panel(content:@Composable ColumnScope.()->Unit){
        Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()){
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
        }
    }
    private fun languageName(code:String)=when(code){"zh"->"简体中文";"ru"->"俄语";"en"->"英语";"en+zh"->"中英混合";"ru+zh"->"中俄混合";"en+ru"->"俄英混合";"en+ru+zh"->"多语混合";else->"未识别"}
    @Composable private fun LanguageMenu(){
        var expanded by remember{mutableStateOf(false)}
        val detected=mixedCode(draft,engine.detectText(draft))
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
        val scheme=MaterialTheme.colorScheme
        val focus=LocalFocusManager.current
        val keyboard=LocalSoftwareKeyboardController.current
        Surface(shape=RoundedCornerShape(18.dp),color=scheme.surface,modifier=Modifier.fillMaxWidth()){
            Column(Modifier.padding(horizontal=18.dp,vertical=10.dp)){
                LanguageMenu()
                HorizontalDivider(color=scheme.outline.copy(alpha=.55f))
                BasicTextField(value=draft,onValueChange={draft=it;textResult=null},modifier=Modifier.fillMaxWidth().padding(top=17.dp,bottom=8.dp).heightIn(min=145.dp),
                    textStyle=TextStyle(color=scheme.onSurface,fontSize=23.sp,lineHeight=32.sp),cursorBrush=SolidColor(scheme.primary),decorationBox={inner->
                    Box{if(draft.isEmpty())Text("输入文字",color=scheme.onSurfaceVariant.copy(alpha=.85f),fontSize=23.sp);inner()}
                })
                Row(Modifier.fillMaxWidth().padding(bottom=5.dp),verticalAlignment=Alignment.CenterVertically){
                    IconButton(onClick={draft="";textResult=null},enabled=draft.isNotEmpty()&&!textBusy){
                        NavGlyph("close",if(draft.isNotEmpty())scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha=.35f),Modifier.size(22.dp).semantics{contentDescription="清除"})
                    }
                    IconButton(onClick={focus.clearFocus();keyboard?.hide();navigate("live")}){
                        NavGlyph("mic",scheme.primary,Modifier.size(23.dp).semantics{contentDescription="语音传译"})
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick={focus.clearFocus();keyboard?.hide();translateTyped()},enabled=draft.isNotBlank()&&!textBusy,shape=RoundedCornerShape(12.dp),modifier=Modifier.height(44.dp),contentPadding=PaddingValues(horizontal=24.dp)){
                        if(textBusy){CircularProgressIndicator(Modifier.size(15.dp),strokeWidth=2.dp,color=scheme.onPrimary);Spacer(Modifier.width(8.dp))}
                        Text(if(textBusy)"翻译中" else "翻译",fontSize=15.sp)
                    }
                }
            }
        }
        AnimatedVisibility(textResult!=null,enter=fadeIn(tween(200))+expandVertically(tween(220),expandFrom=Alignment.Top)){
            textResult?.let{s->
                Surface(shape=RoundedCornerShape(18.dp),color=scheme.surface,modifier=Modifier.fillMaxWidth()){
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Text(languageName(s.target),color=scheme.primary,fontSize=14.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f))
                            IconButton(onClick={speak(s.translated,s.target)},enabled=s.translated.isNotBlank()){NavGlyph("speaker",scheme.primary,Modifier.size(22.dp).semantics{contentDescription="朗读"})}
                            IconButton(onClick={copyText(s.translated)},enabled=s.translated.isNotBlank()){NavGlyph("copy",scheme.primary,Modifier.size(20.dp).semantics{contentDescription="复制"})}
                        }
                        SelectionContainer{Text(s.translated.ifBlank{if(s.error.isBlank())"正在翻译…" else "未获得译文"},fontSize=25.sp,lineHeight=35.sp,color=scheme.onSurface)}
                        if(s.error.isNotBlank())Text(s.error,fontSize=12.sp,color=scheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
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
            runOnUiThread { if(draft.trim()==raw&&direction==dir&&cloud==online)textResult=result.first; if(cloud==online)mergeTerms(result.second); textBusy=false }
        }
    }
    private fun process(raw:String,config:ApiConfig,online:Boolean,dir:String,speech:Boolean,context:List<Segment>,glossary:List<Term>,source:String=mixedCode(raw,engine.detectText(raw)),onLocal:((Segment)->Unit)?=null):Pair<Segment,List<Term>> {
        val lang=source.ifBlank{engine.detectText(raw)}
        val target=engine.targetFor(lang,dir)
        val base=Segment(raw,language=lang,target=target)
        val local=runCatching{engine.offline(raw,lang,target)}
        if(local.isSuccess)onLocal?.invoke(base.copy(translated=local.getOrThrow()))
        if(online&&config.key.isNotBlank()){
            try{
                val a=engine.analyze(config,raw,context,glossary,target,speech,lang)
                val corrected=if(speech&&correctionKeepsScripts(raw,a.corrected))a.corrected else raw
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
    @Composable private fun EditableCaption(value:String,before:String?,color:Color,fontSize:Int,lineHeight:Int,source:Boolean,onEdit:(TextRange)->Unit){
        val scheme=MaterialTheme.colorScheme
        var layout by remember(value){mutableStateOf<TextLayoutResult?>(null)}
        val accent=if(source)scheme.primary else if(isSystemInDarkTheme())Color(0xFFD3E0FF) else Color(0xFF123A91)
        val annotated=remember(value,before,accent,scheme.primary){
            buildAnnotatedString{
                append(value)
                changedRanges(before,value).forEach{range->
                    addStyle(SpanStyle(color=accent,background=scheme.primary.copy(alpha=.13f),fontWeight=FontWeight.SemiBold,textDecoration=TextDecoration.Underline),range.first,range.last+1)
                }
            }
        }
        Text(annotated,fontSize=fontSize.sp,lineHeight=lineHeight.sp,color=color,
            fontWeight=if(source)FontWeight.Normal else FontWeight.Medium,
            onTextLayout={layout=it},modifier=Modifier.fillMaxWidth().pointerInput(value){
                detectTapGestures(onTap={point->
                    val l=layout?:return@detectTapGestures
                    if(value.isNotEmpty()){
                        val offset=l.getOffsetForPosition(point).coerceIn(0,value.lastIndex)
                        onEdit(l.getWordBoundary(offset))
                    }
                })
            }.semantics{contentDescription=value})
    }
    @Composable private fun SentenceEditDialog(request:SentenceEdit){
        var value by remember{mutableStateOf(TextFieldValue(request.before,request.selection))}
        val focus=remember{FocusRequester()}
        val keyboard=LocalSoftwareKeyboardController.current
        AlertDialog(onDismissRequest={sentenceEdit=null},title={Text(if(request.source)"编辑原文" else "编辑译文")},
            text={
                OutlinedTextField(value,{value=it},modifier=Modifier.fillMaxWidth().focusRequester(focus),minLines=3,maxLines=7)
                LaunchedEffect(Unit){focus.requestFocus();keyboard?.show()}
            },confirmButton={TextButton(onClick={saveSentenceEdit(request,value.text.trim());sentenceEdit=null},enabled=value.text.isNotBlank()&&value.text.trim()!=request.before){Text("保存")}},
            dismissButton={TextButton(onClick={sentenceEdit=null}){Text("取消")}})
    }
    private fun sessionFor(id:Long)=current?.takeIf{it.time==id}?:sessions.firstOrNull{it.time==id}
    private fun replaceSentence(id:Long,index:Int,expected:Int,next:Segment):Boolean{
        val s=sessionFor(id)?:return false
        val old=s.items.getOrNull(index)?:return false
        if(!canReplaceRevision(old,expected))return false
        val list=s.items.toMutableList();list[index]=next
        val updated=s.copy(items=list,summary="")
        if(current?.time==id)current=updated
        persist(updated)
        return true
    }
    private fun saveSentenceEdit(request:SentenceEdit,value:String){
        val s=sessionFor(request.session)?:return
        val old=s.items.getOrNull(request.index)?:return
        val revision=old.manualRevision+1
        val next=if(request.source){
            val lang=asrLanguage(Recognition(value,engine.detectText(value)))
            val target=old.target.ifBlank{engine.targetFor(lang,"Auto")}
            old.copy(corrected=value,translated="",language=lang,target=target,error="重新翻译中",
                manualRevision=revision,sourceBeforeEdit=old.sourceBeforeEdit?:request.before,
                translationBeforeEdit=old.translationBeforeEdit?:old.translated.takeIf{it.isNotBlank()})
        }else old.copy(translated=value,error="",manualRevision=revision,translationBeforeEdit=old.translationBeforeEdit?:request.before)
        if(!replaceSentence(request.session,request.index,old.manualRevision,next)||!request.source)return
        val online=cloud;val config=api;val glossary=terms;val context=s.items.take(request.index)
        editWorker.execute{
            val a=if(online&&config.key.isNotBlank())runCatching{engine.analyze(config,value,context,glossary,next.target,false,next.language)}.getOrNull() else null
            val translated=a?.translated?:runCatching{engine.offline(value,next.language,next.target)}.getOrDefault("")
            val updated=next.copy(translated=translated,scene=a?.scene?:next.scene,error=if(translated.isBlank())"重新翻译失败，点按原文重试" else if(a==null&&online&&config.key.isNotBlank())"本地译文" else "")
            runOnUiThread{replaceSentence(request.session,request.index,revision,updated)}
        }
    }
    @Composable private fun CaptionPair(s:Segment,sessionId:Long,index:Int){
        val scheme=MaterialTheme.colorScheme
        val done=s.translated.isNotBlank()
        val rawColor by animateColorAsState(if(done)scheme.onSurface else scheme.onSurfaceVariant,tween(220),label="caption color")
        var showRaw by remember(s.raw){mutableStateOf(false)}
        val corrected=s.corrected.isNotBlank()&&s.corrected!=s.raw
        Column(Modifier.fillMaxWidth().padding(vertical=7.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
            Text(java.text.SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(java.util.Date(s.startTime)),
                fontSize=10.sp,letterSpacing=.25.sp,color=scheme.onSurfaceVariant.copy(alpha=.78f),
                fontWeight=FontWeight.Medium)
            if(showRaw)SelectionContainer{Text(s.raw,fontSize=16.sp,lineHeight=24.sp,color=rawColor)}
            else {
                val displayed=s.corrected.ifBlank{s.raw}
                EditableCaption(displayed,s.sourceBeforeEdit,rawColor,16,24,true){range->
                    sentenceEdit=SentenceEdit(sessionId,index,true,displayed,range)
                }
            }
            AnimatedVisibility(visible=done,enter=fadeIn(tween(220))+expandVertically(tween(180),expandFrom=Alignment.Top)){
                EditableCaption(s.translated,s.translationBeforeEdit,scheme.primary,20,29,false){range->
                    sentenceEdit=SentenceEdit(sessionId,index,false,s.translated,range)
                }
            }
            if(s.error.isNotBlank())Text(s.error,fontSize=11.sp,color=scheme.onSurfaceVariant)
            if(s.manualRevision>0)Row(verticalAlignment=Alignment.CenterVertically){
                Text("已修正",fontSize=11.sp,color=scheme.primary)
                if(s.translated.isNotBlank()){
                    Spacer(Modifier.width(8.dp))
                    Text("朗读",fontSize=11.sp,color=scheme.primary,modifier=Modifier.clickable{speak(s.translated,s.target)})
                }
            }
            if(corrected&&showRaw)Text("显示校正文本",fontSize=11.sp,color=scheme.onSurfaceVariant,modifier=Modifier.clickable{showRaw=false})
            else if(corrected)Text("原文",fontSize=11.sp,color=scheme.onSurfaceVariant,modifier=Modifier.clickable{showRaw=true})
        }
    }
    @Composable private fun PartialCaption(value:String){
        var previous by remember{mutableStateOf("")}
        var unchanged by remember{mutableIntStateOf(0)}
        val reveal=remember{Animatable(1f)}
        LaunchedEffect(value){
            unchanged=commonPrefixLength(previous,value)
            previous=value
            reveal.snapTo(.25f)
            reveal.animateTo(1f,tween(180))
        }
        val color=MaterialTheme.colorScheme.onSurfaceVariant
        val prefix=unchanged.coerceAtMost(value.length)
        val display=buildAnnotatedString{
            withStyle(SpanStyle(color=color)){append(value.substring(0,prefix))}
            withStyle(SpanStyle(color=color.copy(alpha=.35f+.65f*reveal.value))){append(value.substring(prefix))}
        }
        Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
            Text(display,fontSize=16.sp,lineHeight=23.sp,modifier=Modifier.weight(1f,fill=false))
            Spacer(Modifier.width(5.dp))
            ListeningDot()
        }
    }
    @Composable private fun ListeningDot(){
        val transition=rememberInfiniteTransition(label="listening")
        val opacity by transition.animateFloat(.35f,1f,infiniteRepeatable(tween(650),RepeatMode.Reverse),label="pulse")
        Box(Modifier.size(6.dp).alpha(opacity).background(MaterialTheme.colorScheme.primary,androidx.compose.foundation.shape.CircleShape))
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
                var sourceMenu by remember{mutableStateOf(false)}
                Box{
                    TextButton(onClick={sourceMenu=true},enabled=!recording&&!finishing,contentPadding=PaddingValues(horizontal=4.dp)){
                        Text(if(mixedRecognition)"中英俄混合 ▾" else if(russianStreaming)"俄语实时 ▾" else "自动识别 ▾",fontSize=13.sp)
                    }
                    DropdownMenu(expanded=sourceMenu,onDismissRequest={sourceMenu=false}){
                        DropdownMenuItem(text={Text("自动识别")},onClick={russianStreaming=false;mixedRecognition=false;sourceMenu=false})
                        DropdownMenuItem(text={Text("俄语实时")},onClick={russianStreaming=true;mixedRecognition=false;sourceMenu=false})
                        DropdownMenuItem(text={Text("中英俄混合")},onClick={russianStreaming=false;mixedRecognition=true;sourceMenu=false})
                    }
                }
                Text(captureStatus,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f).padding(start=6.dp))
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
                        CaptionPair(s,current!!.time,i)
                    }
                    item(key="tail"){
                        if(draft!=null&&draft.text.isNotBlank()){
                            PartialCaption(draft.text)
                        } else if(items.isEmpty()){
                            Row(Modifier.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
                                Text(if(recording)"正在聆听…" else "字幕将在这里显示",fontSize=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(recording){Spacer(Modifier.width(6.dp));ListeningDot()}
                            }
                        }
                    }
                }
            }
            current?.summary?.takeIf{it.isNotBlank()}?.let{
                TextButton(onClick={viewed=current?.time;navigate("detail")}){Text("查看会话总结")}
            }
            val scheme=MaterialTheme.colorScheme
            Row(Modifier.fillMaxWidth().padding(top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                val smoothLevel by animateFloatAsState(if(recording)level else 0f,tween(120),label="audio level")
                val history=meterHistory
                val meterColor=if(recording)scheme.primary else scheme.onSurfaceVariant.copy(alpha=.40f)
                Surface(Modifier.weight(1f).height(54.dp),color=scheme.surface,shape=RoundedCornerShape(14.dp)){
                    Canvas(Modifier.fillMaxSize().padding(horizontal=13.dp,vertical=12.dp)){
                        for(i in history.indices){
                            val x=size.width*(i+.5f)/history.size
                            val v=if(!recording)0f else if(i==history.lastIndex)smoothLevel else history[i]
                            val h=1.dp.toPx()+v*size.height*.44f
                            drawLine(meterColor.copy(alpha=.30f+.70f*i/(history.size-1)),Offset(x,size.height/2-h),Offset(x,size.height/2+h),strokeWidth=1.8.dp.toPx(),cap=StrokeCap.Round)
                        }
                    }
                }
                Surface(onClick={autoSpeak=!autoSpeak},modifier=Modifier.size(54.dp).semantics{contentDescription=if(autoSpeak)"关闭自动朗读" else "开启自动朗读";selected=autoSpeak},shape=RoundedCornerShape(14.dp),color=if(autoSpeak)scheme.primary.copy(alpha=.12f) else scheme.surface){
                    Box(contentAlignment=Alignment.Center){NavGlyph("speaker",if(autoSpeak)scheme.primary else scheme.onSurfaceVariant,Modifier.size(23.dp))}
                }
                val buttonColor by animateColorAsState(if(recording)Color(0xFFBE3941) else scheme.primary,tween(180),label="capture")
                Button(onClick={
                    if(recording)stopCapture()
                    else if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startCapture()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                },enabled=!finishing,modifier=Modifier.height(54.dp),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=buttonColor),contentPadding=PaddingValues(horizontal=15.dp)){
                    if(finishing)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp,color=scheme.onSurfaceVariant)
                    else NavGlyph(if(recording)"stop" else "mic",scheme.onPrimary,Modifier.size(20.dp))
                    Spacer(Modifier.width(7.dp));Text(if(recording)"结束" else if(finishing)"整理中" else "聆听",fontSize=15.sp)
                }
            }
        }
    }
    private fun startCapture() {
        if(finishing || !active.compareAndSet(false,true))return
        val started=System.currentTimeMillis()
        current=Session("会话 "+java.text.SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(java.util.Date(started)),emptyList(),time=started)
        CaptureService.stopRequested = { runOnUiThread { stopCapture() } }
        try { startForegroundService(android.content.Intent(this,CaptureService::class.java)) }
        catch(e:Exception) { active.set(false); CaptureService.stopRequested=null; captureStatus="无法启动录音服务：${e.message}"; return }
        recording=true; captureStatus="正在聆听"; pending=0;partial=null;partialGeneration=-1;captureWarning="";meterHistory=List(30){0f};finalizer=null;playbackOrder.clear()
        val useNative=russianStreaming
        val useMixed=mixedRecognition
        val nativeFailed=AtomicBoolean(false)
        val nativeJobs=AtomicInteger(0)
        val native=if(useNative)StreamingRussian(assets) else null
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
                val stabilizer=PreviewStabilizer()
                val preroll=ArrayDeque<FloatArray>()
                val previewBlocks=ArrayList<FloatArray>()
                var previewSamples=0
                var lastPreview=0
                var lastMeter=0L
                var nativeEpoch=-1
                var nativeSamples=0
                val nativeBlocks=ArrayList<FloatArray>()
                fun submitNative(epoch:Int,begin:Boolean){
                    if(native==null||nativeFailed.get())return
                    if(nativeJobs.get()>=12){
                        nativeFailed.set(true)
                        runOnUiThread{captureWarning="流式识别积压，已切换滚动识别"}
                        return
                    }
                    val audio=FloatArray(nativeSamples);var offset=0
                    for(chunk in nativeBlocks){chunk.copyInto(audio,offset);offset+=chunk.size}
                    nativeBlocks.clear();nativeSamples=0
                    nativeJobs.incrementAndGet()
                    streamingWorker.execute{
                        try{
                            if(nativeFailed.get()||generation.get()!=epoch)return@execute
                            if(begin)native.start()
                            val text=native.feed(audio)
                            if(text.isNotBlank()&&!hasDecodeLoop(text)&&generation.get()==epoch&&active.get())runOnUiThread{
                                if(current?.time==started&&active.get()&&generation.get()==epoch){
                                    partialGeneration=epoch;partial=Recognition(text,"ru")
                                }
                            }
                        }catch(e:Exception){
                            nativeFailed.set(true)
                            runOnUiThread{captureWarning="流式模型不可用，已切换滚动识别"}
                        }finally{nativeJobs.decrementAndGet()}
                    }
                }
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
                                    val result=engine.transcribe(samples,final=true,mixed=useMixed)
                                    runOnUiThread {
                                        if(current?.time==started&&partialGeneration<=epoch)partial=null
                                        if(result.text.isNotBlank()){
                                            if(plausibleCaption(result.text,samples.size))enqueue(result.text,result.language,started,api,cloud,from,until)
                                            else {
                                                captureWarning="部分语音未能可靠识别"
                                                current?.takeIf{it.time==started}?.let{session->
                                                    val pos=session.items.size
                                                    val failed=Segment(result.text,corrected="识别未完成",error="识别异常",language=result.language,time=until,startTime=from)
                                                    current=session.copy(items=session.items+failed);saveCurrent()
                                                    for(index in playbackOrder.mark(pos)){
                                                        val ready=current?.items?.getOrNull(index)
                                                        if(autoSpeak&&ready!=null&&ready.translated.isNotBlank())speak(ready.translated,ready.target)
                                                    }
                                                }
                                            }
                                        }
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
                    if(meterNow-lastMeter>=80L){lastMeter=meterNow;runOnUiThread {level=(rms*5).toFloat().coerceIn(0f,1f);meterHistory=(meterHistory.drop(1)+level)}}
                    detector.acceptWaveform(samples)
                    if(drain()){
                        previewBlocks.clear();previewSamples=0;lastPreview=0;preroll.clear()
                        nativeBlocks.clear();nativeSamples=0;nativeEpoch=-1
                    }
                    if(detector.isSpeechDetected()){
                        if(previewBlocks.isEmpty()){
                            previewBlocks.addAll(preroll);previewSamples=preroll.sumOf{it.size}
                        }
                        if(previewSamples<16000*8){previewBlocks.add(samples);previewSamples+=n}
                        if(useNative&&!nativeFailed.get()){
                            val epoch=generation.get()
                            val begin=nativeEpoch!=epoch
                            if(begin){nativeEpoch=epoch;nativeBlocks.addAll(preroll);nativeSamples+=preroll.sumOf{it.size}}
                            nativeBlocks.add(samples);nativeSamples+=n
                            if(begin||nativeSamples>=2560)submitNative(epoch,begin)
                        }
                        if((!useNative||nativeFailed.get())&&previewSamples>=25600 && previewSamples-lastPreview>=24000 && asrJobs.compareAndSet(0,1)){
                            val snapshot=FloatArray(previewSamples);var pos=0
                            for(chunk in previewBlocks){chunk.copyInto(snapshot,pos);pos+=chunk.size}
                            val epoch=generation.get();lastPreview=previewSamples
                            recognition.execute{
                                try{
                                    val result=engine.transcribe(snapshot)
                                    if(result.text.isNotBlank()&&active.get()&&generation.get()==epoch)runOnUiThread{
                                        if(current?.time==started&&active.get()&&generation.get()==epoch){
                                            if(partialGeneration!=epoch)stabilizer.reset()
                                            partialGeneration=epoch
                                            partial=stabilizer.offer(result,snapshot.size)
                                        }
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
                if(native!=null)streamingWorker.execute{native.close()}
                val err=error
                runOnUiThread {recording=false;finishing=true;level=0f;partial=null;captureStatus=err?:"正在完成识别…"}
                recognition.execute {runOnUiThread {
                    finalizer={finishSession(started,api,cloud,err)}
                    finishWhenReady()
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
                    updateSegment(id,pos,base.copy(translated=translated,error=if(online&&config.key.isNotBlank())"本地初译" else ""))
            }
            translated
        }
        (if(lang=="ru"||lang.contains("ru+"))russianNetwork else liveNetwork).execute {
            val snapshot=currentContextForWorker(id,pos)
            val analysis=if(online&&config.key.isNotBlank())runCatching{engine.analyze(config,raw,snapshot.first,snapshot.second,target,true,lang)} else null
            val a=analysis?.getOrNull()
            val final=if(a!=null)base.copy(corrected=raw,translated=a.translated,scene=a.scene)
            else {
                val translated=runCatching{local.get(15,java.util.concurrent.TimeUnit.SECONDS)}.getOrNull().orEmpty()
                base.copy(translated=translated,error=when{
                    translated.isBlank()->"翻译不可用，请检查网络或离线语言包"
                    online&&config.key.isNotBlank()&&cloud->"网络翻译不可用 · 本地译文"
                    else->""
                })
            }
            runOnUiThread {
                updateSegment(id,pos,final);mergeTerms(a?.terms.orEmpty())
                pending=(pending-1).coerceAtLeast(0)
                for(index in playbackOrder.mark(pos)){
                    val ready=current?.takeIf{it.time==id}?.items?.getOrNull(index)
                    if(autoSpeak&&ready!=null&&ready.translated.isNotBlank())speak(ready.translated,ready.target)
                }
                finishWhenReady()
            }
        }
    }
    private fun finishWhenReady(){
        if(pending!=0)return
        val finish=finalizer?:return
        finalizer=null
        finish()
    }
    private fun currentContextForWorker(id:Long,pos:Int):Pair<List<Segment>,List<Term>> {
        val task=java.util.concurrent.FutureTask { (current?.takeIf{it.time==id}?.items?.take(pos)?:emptyList()) to terms }
        runOnUiThread(task)
        return task.get()
    }
    private fun updateSegment(id:Long,pos:Int,segment:Segment) {
        val s=current?.takeIf{it.time==id}?:return
        if(pos !in s.items.indices||!canReplaceRevision(s.items[pos],segment.manualRevision))return
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
                result.onSuccess{v->sessionFor(s.time)?.takeIf{it.items==s.items}?.let{latest->val updated=latest.copy(summary=v);persist(updated);if(current?.time==s.time)current=updated}}
                    .onFailure{message="总结失败：${it.message}"}
                summaryBusy=null
            }
        }
    }
    private fun canDeleteSession(id:Long)=current?.takeIf{recording||finishing}?.time!=id&&summaryBusy!=id
    private fun deleteSession(id:Long){
        if(!canDeleteSession(id))return
        sessions=sessions.filterNot{it.time==id}
        archive.saveSessions(sessions)
        if(current?.time==id)current=null
        if(viewed==id)viewed=null
    }
    @Composable private fun NotesPage(){
        val scheme=MaterialTheme.colorScheme
        var opened by remember{mutableStateOf<Long?>(null)}
        var confirming by remember{mutableStateOf<Long?>(null)}
        fun close(){opened=null;confirming=null}
        if(sessions.isEmpty()){
            Column(Modifier.fillMaxWidth().padding(top=55.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)){
                NavGlyph("notes",scheme.onSurfaceVariant.copy(alpha=.6f),Modifier.size(42.dp))
                Text("暂无会话",fontSize=18.sp,fontWeight=FontWeight.Medium,color=scheme.onSurfaceVariant)
            }
            return
        }
        val dateFormat=java.text.SimpleDateFormat("yyyy年 M月 d日",Locale.CHINA)
        sessions.groupBy{dateFormat.format(java.util.Date(it.time))}.forEach{(day,entries)->
            Text(day,fontSize=13.sp,color=scheme.onSurfaceVariant,modifier=Modifier.padding(start=6.dp,top=5.dp))
            Surface(shape=RoundedCornerShape(16.dp),color=scheme.surface){
                Column(Modifier.fillMaxWidth()){
                    entries.forEachIndexed{i,session->
                        key(session.time){
                            if(i>0)HorizontalDivider(Modifier.padding(start=16.dp),color=scheme.outline.copy(alpha=.45f))
                            SwipeSessionRow(session,opened==session.time,confirming==session.time,canDeleteSession(session.time),
                                onOpen={if(opened!=session.time){opened=session.time;confirming=null}},
                                onClose={if(opened==session.time)close()},
                                onTap={if(opened!=null)close() else {viewed=session.time;navigate("detail")}},
                                onDelete={if(canDeleteSession(session.time)){
                                    if(confirming==session.time){deleteSession(session.time);close()}
                                    else {opened=session.time;confirming=session.time}
                                }})
                        }
                    }
                }
            }
        }
    }
    @Composable private fun SwipeSessionRow(session:Session,open:Boolean,confirm:Boolean,enabled:Boolean,
        onOpen:()->Unit,onClose:()->Unit,onTap:()->Unit,onDelete:()->Unit){
        val scheme=MaterialTheme.colorScheme
        val density=LocalDensity.current
        val actionWidth=if(confirm)124.dp else 86.dp
        val widthPx=with(density){actionWidth.toPx()}
        var dragging by remember{mutableStateOf(false)}
        var dragOffset by remember{mutableFloatStateOf(0f)}
        val animated by animateFloatAsState(
            if(dragging)dragOffset else if(open&&enabled)-widthPx else 0f,
            if(dragging)snap() else tween(220),label="session reveal")
        val offset=if(dragging)dragOffset else animated
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(0.dp))){
            Box(Modifier.matchParentSize(),contentAlignment=Alignment.CenterEnd){
                if(offset<-.5f||open){
                    Box(Modifier.width(actionWidth).fillMaxHeight().background(if(confirm)Color(0xFFB4232D) else Color(0xFFD83B42))
                        .clickable(enabled=enabled&&open,onClick=onDelete),contentAlignment=Alignment.Center){
                        Text(if(confirm)"确认删除" else "删除",color=Color.White,fontSize=14.sp,fontWeight=FontWeight.Medium)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().offset{IntOffset(offset.roundToInt(),0)}.background(scheme.surface)
                .draggable(rememberDraggableState{delta->dragOffset=(dragOffset+delta).coerceIn(-widthPx,0f)},
                    orientation=Orientation.Horizontal,enabled=enabled,
                    onDragStarted={dragOffset=animated;dragging=true},
                    onDragStopped={velocity->
                        val reveal=shouldRevealDelete(dragOffset,widthPx,velocity)
                        if(reveal)onOpen() else onClose()
                        dragging=false
                    })
                .semantics{if(enabled)customActions=listOf(CustomAccessibilityAction(if(confirm)"确认删除记录" else "删除记录"){onDelete();true})}
                .clickable(onClick=onTap).padding(horizontal=16.dp,vertical=15.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
                    Text(timeLabel(session.time),fontSize=17.sp,fontWeight=FontWeight.Medium)
                    Text(session.summary.ifBlank{session.items.firstOrNull()?.translated?.ifBlank{session.items.first().raw}.orEmpty()}.ifBlank{"${session.items.size} 段语音"},fontSize=14.sp,color=scheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                Text("›",fontSize=24.sp,color=scheme.onSurfaceVariant,modifier=Modifier.padding(start=12.dp))
            }
        }
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
            if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("删除此会话？")},confirmButton={TextButton(onClick={deleteSession(s.time);navigate("notes")}){Text("删除")}},dismissButton={TextButton(onClick={confirm=false}){Text("取消")}})
        }
        Panel{s.items.forEachIndexed{i,item->if(newGroup(s.items,i))GroupTime(item);CaptionPair(item,s.time,i)}}
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
                Switch(checked=cloud,onCheckedChange={changeMode(it)})
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
        Panel {Text("版本 0.6.3");Text("录音不保存；会话文字与术语在应用私有存储中。API Key 使用 Android Keystore 加密。",fontSize=12.sp)}
    }
    private fun speak(text:String,language:String="") {
        if(text.isBlank())return
        if(!ttsReady){message="系统语音合成不可用";return}
        val t=tts?:return
        val locale=when(language.ifBlank{engine.detectText(text)}){"zh"->Locale.SIMPLIFIED_CHINESE;"en"->Locale.US;else->Locale("ru","RU")}
        if(!cloud){
            val voice=t.voices?.filter{!it.isNetworkConnectionRequired&&it.locale.language==locale.language}
                ?.maxByOrNull{(if(it.locale==locale)100000 else 0)+it.quality}
            if(voice==null||t.setVoice(voice)!=TextToSpeech.SUCCESS){message="请安装本地语音包";return}
        }else{
            val r=t.setLanguage(locale)
            if(r==TextToSpeech.LANG_MISSING_DATA||r==TextToSpeech.LANG_NOT_SUPPORTED){message="系统缺少对应语音包";return}
        }
        t.speak(text,TextToSpeech.QUEUE_ADD,null,System.nanoTime().toString())
    }
    override fun onDestroy(){if(isFinishing)stopCapture();super.onDestroy()}
}
