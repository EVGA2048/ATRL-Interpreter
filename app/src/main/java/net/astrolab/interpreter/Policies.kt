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
