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
        assertEquals(Recognition("中文测试 ENGLISH TEST","en+zh"),
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
}
