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
}
