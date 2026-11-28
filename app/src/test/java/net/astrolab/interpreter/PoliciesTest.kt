package net.astrolab.interpreter

import org.junit.Test
import org.junit.Assert.*

class PoliciesTest {
    @Test fun groupsAtExactlyOneMinute() {
        assertTrue(startsNewGroup(null, 0))
        assertFalse(startsNewGroup(10_000, 69_999))
        assertTrue(startsNewGroup(10_000, 70_000))
        assertFalse(startsNewGroup(10_000, 9_000))
    }
    @Test fun glossaryIncludesOnlyRelevantConfirmedTerms() {
        val terms=listOf(Term("рельсовая цепь","轨道电路",approved=true),
            Term("паспорт","护照",approved=true),
            Term("сигнал","信号",approved=false))
        assertEquals(listOf(terms[0]),relevantTerms(terms,"РЕЛЬСОВАЯ ЦЕПЬ и сигнал",emptyList()))
        assertEquals(listOf(terms[1]),relevantTerms(terms,"Документы",listOf(Segment("护照"))))
    }
    @Test fun cooldownAndCredentialChanges() {
        var now=1000L
        val gate=RequestGate{now}
        val a=ApiConfig("test-a")
        gate.check(a);gate.failed(a,429)
        assertTrue(runCatching{gate.check(a)}.isFailure)
        now+=30_000L;gate.check(a)
        gate.failed(a,401)
        now+=1_000_000L
        assertTrue(runCatching{gate.check(a)}.isFailure)
        gate.check(ApiConfig("test-b"))
        gate.reset();gate.check(a)
    }
    @Test fun retryAfterAndNetworkFailure() {
        var now=0L
        val gate=RequestGate{now}
        val a=ApiConfig("test")
        gate.check(a);gate.failed(a,503,2000)
        now=1999;assertTrue(runCatching{gate.check(a)}.isFailure)
        now=2000;gate.check(a)
        gate.failed(a,null)
        now=17000;gate.check(a)
    }
    @Test fun bilingualSelectionPreservesScriptsAndRussian() {
        assertEquals(Recognition("中文测试 English test","en+zh"),
            selectBilingual(Recognition("中文测试 英语文本","zh"),"中文测试ENGLISH TEST"))
        val russian=Recognition("Сегодня мы изучаем сигналы.","ru")
        assertEquals(russian,selectBilingual(russian,"中文 English"))
        val chinese=Recognition("中文测试","zh")
        assertEquals(chinese,selectBilingual(chinese,"普通话"))
        assertFalse(correctionKeepsScripts("中文 English","中文 英语"))
        assertTrue(correctionKeepsScripts("中文 English","中文 English test"))
        assertEquals("en+ru",mixedCode("Привет English","ru"))
    }
    @Test fun prefixAnimationPreservesExistingText() {
        assertEquals(4,commonPrefixLength("中文测试","中文测试 English"))
        assertEquals(2,commonPrefixLength("中文测试","中文识别"))
        assertEquals(0,commonPrefixLength("😀","😁"))
    }
    @Test fun mixedOfflineRunsDoNotTreatWholePhraseAsChinese(){
        assertEquals(listOf(ScriptRun("zh","中文测试 "),ScriptRun("en","English test")),scriptRuns("中文测试 English test"))
        assertEquals(listOf(ScriptRun("ru","Привет "),ScriptRun("en","English!")),scriptRuns("Привет English!"))
    }
    @Test fun playbackRemainsOrderedWhenNetworkCompletesOutOfOrder(){
        val order=OrderedCompletion()
        assertEquals(emptyList<Int>(),order.mark(2))
        assertEquals(listOf(0),order.mark(0))
        assertEquals(listOf(1,2),order.mark(1))
        assertEquals(emptyList<Int>(),order.mark(0))
        order.clear();assertEquals(listOf(0),order.mark(0))
    }
    @Test fun catchesRunawayDecoderLoops(){
        assertTrue(hasDecodeLoop("Сегодня мы мы мы мы мы"))
        assertTrue(hasDecodeLoop("рельсовая цепь рельсовая цепь рельсовая цепь рельсовая цепь"))
        assertTrue(hasDecodeLoop("测试测试测试测试测试测试"))
        assertFalse(hasDecodeLoop("Да, да, да. Это рельсовая цепь."))
        assertFalse(hasDecodeLoop("非常非常重要"))
        assertFalse(plausibleCaption("слово ".repeat(40),16000))
        assertTrue(plausibleCaption("В электрической централизации стрелки и сигналы связаны.",64000))
    }
    @Test fun unstableHypothesesDoNotFlashOnScreen(){
        val p=PreviewStabilizer()
        val first=Recognition("Сегодня мы","ru")
        assertEquals(first,p.offer(first,32000))
        assertEquals(first,p.offer(Recognition("мы мы мы мы мы мы","ru"),48000))
        assertEquals(first,p.offer(Recognition("English test","en"),48000))
        val next=Recognition("Сегодня мы изучаем сигналы","ru")
        assertEquals(next,p.offer(next,64000))
        p.reset()
        assertNull(p.offer(Recognition("да да да да да","ru"),32000))
    }
    @Test fun preservesRussianSentenceWithTechnicalLatinTerms(){
        assertEquals("ru",asrLanguage(Recognition("Система ETCS применяется на железной дороге.","ru")))
        assertEquals("en+zh",asrLanguage(Recognition("中文 English","zh")))
        assertFalse(validCloudTranslation("Проверка","测试测试测试测试测试"))
        assertFalse(validCloudTranslation("Проверка",""))
        assertTrue(validCloudTranslation("Проверка рельсовой цепи","检查轨道电路"))
    }
    @Test fun highlightsOnlyChangedWords(){
        val text="Сегодня мы изучаем сигналы"
        assertEquals(listOf("сигналы"),changedRanges("Сегодня мы изучаем стрелки",text).map{text.substring(it)})
        assertEquals(listOf("电"),changedRanges("轨道线路","轨道电路").map{"轨道电路".substring(it)})
        assertTrue(changedRanges(null,text).isEmpty())
        assertTrue(changedRanges(text,text).isEmpty())
    }
    @Test fun manualRevisionRejectsStaleTranslation(){
        val edited=Segment("raw",corrected="fixed",manualRevision=1)
        assertFalse(canReplaceRevision(edited,0))
        assertTrue(canReplaceRevision(edited,1))
    }
    @Test fun sourceIsNotTranslatedOrCorrected(){
        val original="我想要test English speaking и по русский язык"
        assertEquals(original,preserveSource(original))
        val r=joinAcousticParts(listOf(Recognition("我想要","zh"),Recognition("test English speaking","en"),Recognition("и по русский язык","ru")))
        assertEquals("我想要 test English speaking и по русский язык",r.text)
        assertEquals("en+ru+zh",r.language)
    }
    @Test fun requiresTwoLanguageProbesBeforeSwitching(){
        val audio=FloatArray(48000){.1f}
        for(i in 11000..11700)audio[i]=0f
        for(i in 30900..31500)audio[i]=0f
        val spans=languageSpans(audio,listOf(LanguageProbe(4800,"zh"),LanguageProbe(8800,"zh"),LanguageProbe(12800,"en"),LanguageProbe(16800,"en"),LanguageProbe(20800,"ru"),LanguageProbe(24800,"en"),LanguageProbe(32800,"ru"),LanguageProbe(36800,"ru")))
        assertEquals(listOf("zh","en","ru"),spans.map{it.language})
        assertEquals(0,spans.first().start)
        assertEquals(audio.size,spans.last().end)
        assertTrue(languageSpans(audio,listOf(LanguageProbe(4800,"zh"),LanguageProbe(8800,"zh"),LanguageProbe(12800,"en"),LanguageProbe(16800,"zh"),LanguageProbe(20800,"zh"))).isEmpty())
    }
    @Test fun swipeRevealsAndClosesDeleteAction(){
        assertFalse(shouldRevealDelete(-15f,100f,0f))
        assertTrue(shouldRevealDelete(-60f,100f,0f))
        assertTrue(shouldRevealDelete(-10f,100f,-800f))
        assertFalse(shouldRevealDelete(-90f,100f,800f))
    }
    @Test fun senseVoiceNormalizesUppercaseWithoutTranslating(){
        assertEquals("我想要 test English speaking",normalizeSenseVoiceCase("我想要 TEST ENGLISH SPEAKING","我想要测试英语"))
        assertEquals("配置 ETCS 和 USB",normalizeSenseVoiceCase("配置 ETCS 和 USB","配置"))
        assertEquals("中文 OpenAI test",normalizeSenseVoiceCase("中文 OPENAI TEST","OpenAI test"))
        assertEquals("中文 English test",normalizeSenseVoiceCase("中文 English test",""))
    }
    @Test fun detectsSoundLabelsWithoutMistakingNoiseForMusic(){
        assertEquals("咳嗽",soundLabel(listOf(SoundScore("Cough",.62f))))
        assertEquals("音乐",soundLabel(listOf(SoundScore("Music",.72f))))
        assertEquals("笑声",soundLabel(listOf(SoundScore("Snicker",.78f))))
        assertNull(soundLabel(listOf(SoundScore("Siren",.8f),SoundScore("Music",.4f))))
        assertNull(soundLabel(listOf(SoundScore("Speech",.9f),SoundScore("Cough",.05f))))
        assertEquals("[咳嗽]",soundEventText("咳嗽"))
    }
    @Test fun rejectsPunctuationDecoderLoops(){
        assertTrue(hasDecodeLoop("(".repeat(29)))
        assertTrue(hasDecodeLoop("正常文字"+"()".repeat(12)))
        assertFalse(plausibleCaption("...?!",32000))
        assertFalse(plausibleCaption("你好"+"（".repeat(16),32000))
        assertFalse(validCloudTranslation("你好","(".repeat(20)))
        assertTrue(plausibleCaption("你好（测试）。",32000))
    }
    @Test fun chineseUsesNativeAcousticTranscriptWithoutOverridingRussian(){
        val zh=Recognition("語音時別","zh")
        assertEquals(Recognition("语音识别","zh"),chooseChineseCaption(zh,"语音识别",32000))
        assertEquals(zh,chooseChineseCaption(zh,"(".repeat(20),32000))
        val ru=Recognition("Проверка","ru")
        assertEquals(ru,chooseChineseCaption(ru,"语音识别",32000))
        assertEquals(Recognition("中文 test English","en+zh"),chooseChineseCaption(zh,"中文 test English",32000))
    }
    @Test fun rejectsSeverelyIncompleteChineseCandidate(){
        val complete=Recognition("今天我們學習鐵路信號","zh")
        assertEquals(complete,chooseChineseCaption(complete,"嗯",48000))
        assertEquals(Recognition("今天我们学习铁路信号","zh"),chooseChineseCaption(complete,"今天我们学习铁路信号",48000))
        assertFalse(isIncompleteChineseCandidate("(".repeat(20),"你好",48000))
    }
    @Test fun retainsChineseInRussianAcousticTranscript(){
        assertEquals("ru+zh",asrLanguage(Recognition("今天 изучаем сигналы","ru")))
        assertEquals("ru",asrLanguage(Recognition("Система ETCS","ru")))
    }
    @Test fun quickInputPreservesUserAuthoredText(){
        assertEquals("你好 test English",quickInputText(" 你好 test English "))
        assertEquals("哈哈哈哈哈哈！！！",quickInputText("哈哈哈哈哈哈！！！"))
        assertNull(quickInputText("   "))
        assertNull(quickInputText("a".repeat(2001)))
        assertEquals("en+ru+zh",quickInputLanguage("我想要 test English и русский","zh"))
        assertEquals("zh",quickInputLanguage("123",""))
    }
    @Test fun typedOriginSurvivesTranslationAndManualCorrection(){
        val typed=Segment("明天几点上课",typedInput=true,time=123L)
        assertTrue(typed.copy(translated="Когда занятия?").typedInput)
        assertTrue(typed.copy(corrected="明天几点开始上课",manualRevision=1).typedInput)
        assertFalse(Segment("语音输入").typedInput)
        assertEquals(123L,typed.copy(translated="Когда занятия?").startTime)
    }
    @Test fun localizesSoundEventsWithoutNetwork(){
        assertEquals("[Кашель]",localizedSoundEvent("咳嗽","ru"))
        assertEquals("[Music]",localizedSoundEvent("音乐","en"))
        assertEquals("[Смех]",localizedSoundEvent("笑声","ru"))
        assertEquals("[Аплодисменты]",localizedSoundEvent("掌声","ru"))
        assertEquals("[Sneezing]",localizedSoundEvent("打喷嚏","en"))
        assertEquals("ru",soundEventTarget(emptyList(),20L))
        assertEquals("en",soundEventTarget(listOf(Segment("hello",language="en",time=10L)),20L))
    }
    @Test fun summaryChunksPreserveChronologicalFactsAndExcludeSoundEvents(){
        val a=Segment("套餐 500 卢布",time=20L,typedInput=true)
        val b=Segment("Паспорт",time=10L,translated="护照")
        val c=Segment("[咳嗽]",time=15L,soundEvent="咳嗽")
        val d=Segment("",corrected="识别未完成",time=30L,error="识别异常")
        val chunks=summaryChunks(listOf(a,c,d,b),maxEntries=1)
        assertEquals(listOf(b,a,d),chunks.flatten())
        assertEquals(3,chunks.size)
        assertTrue(summaryChunks(listOf(c)).isEmpty())
        assertEquals(listOf(b,a,d),summaryChunks(listOf(d,a,b),maxChars=1).flatten())
    }
    @Test fun offlineTranscriptsRemainEligibleForAiSummary(){
        val offline=Session("机场",listOf(Segment("Где багаж?",error="原文已保存",language="ru")))
        assertTrue(needsSessionSummary(offline))
        assertTrue(needsSessionSummary(offline.copy(items=offline.items.map{it.copy(translated="行李在哪里？")})))
        assertFalse(needsSessionSummary(offline.copy(summary="询问行李位置")))
        assertFalse(needsSessionSummary(Session("无语音",listOf(Segment("",corrected="识别未完成")))))
        assertFalse(needsSessionSummary(Session("环境声",listOf(Segment("[音乐]",soundEvent="音乐")))))
        assertNotNull(summaryAvailability(true,true,false))
        assertNotNull(summaryAvailability(false,true,true))
        assertNull(summaryAvailability(true,true,true))
    }
    @Test fun networkNotificationsOnlyDescribeTransitions(){
        assertNull(networkTransitionNotice(null,true))
        assertNull(networkTransitionNotice(true,true))
        assertNull(networkTransitionNotice(false,false))
        assertEquals("网络已断开，继续本地处理",networkTransitionNotice(null,false))
        assertEquals("网络已断开，继续本地处理",networkTransitionNotice(true,false))
        assertEquals("网络已恢复",networkTransitionNotice(false,true))
    }
    @Test fun balanceOnlyUsesOfficialDeepSeekEndpoint(){
        assertTrue(supportsDeepSeekBalance("https://api.deepseek.com"))
        assertTrue(supportsDeepSeekBalance("https://api.deepseek.com/v1/"))
        assertFalse(supportsDeepSeekBalance("https://api.deepseek.com.example.com"))
        assertFalse(supportsDeepSeekBalance("https://api.deepseek.com@other.example"))
        assertFalse(supportsDeepSeekBalance("http://api.deepseek.com"))
        assertFalse(supportsDeepSeekBalance("https://api.deepseek.com:8443"))
        assertEquals("¥12.30",displayBalance("CNY","12.3"))
        assertEquals("$0.00",displayBalance("USD","0"))
        assertEquals("—",displayBalance("CNY","invalid"))
    }
}
