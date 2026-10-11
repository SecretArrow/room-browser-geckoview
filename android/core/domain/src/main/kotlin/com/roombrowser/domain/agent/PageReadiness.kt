package com.roombrowser.domain.agent

import kotlinx.serialization.Serializable

/** One sample of the page's own idea of whether it is still working. */
data class ReadinessSample(val readyState: String, val inFlight: Int, val atMs: Long)

/** What the page-side probe returns. */
@Serializable
data class PageReadinessDto(val readyState: String = "", val inFlight: Int = 0)

sealed interface ReadinessVerdict {
    /** Finished loading, and nothing has been in flight for the settle window. */
    data object Ready : ReadinessVerdict

    /** Still loading, or requests are in flight. */
    data object Busy : ReadinessVerdict

    /** The ceiling ran out while the page was still busy. */
    data object TimedOut : ReadinessVerdict
}

/**
 * Decides when a page has actually settled.
 *
 * `readyState === 'complete'` alone is not enough: it flips while `fetch` and
 * XHR calls started by the page are still outstanding, which is exactly the
 * window in which a snapshot shows half a page and a click lands on a control
 * that was about to be replaced.
 *
 * The in-flight count comes from a counter the page probe installs, and is
 * INJECTED rather than read here, so the rule is testable on the JVM against a
 * scripted sequence of samples.
 *
 * The ceiling matters as much as the verdict: a page that keeps a request open
 * forever must not hold a turn open forever, and the honest answer then is
 * "still busy", not "ready".
 */
class PageReadiness(
    private val settleMs: Long = 600L,
    private val ceilingMs: Long = 8_000L
) {

    private var startedAtMs = 0L
    private var quietSinceMs = -1L
    private var timedOut = false

    /** Begins a wait. [nowMs] is the injected clock. */
    fun start(nowMs: Long) {
        startedAtMs = nowMs
        quietSinceMs = -1L
        timedOut = false
    }

    fun observe(sample: ReadinessSample): ReadinessVerdict {
        if (timedOut) return ReadinessVerdict.TimedOut
        val settled = sample.readyState.equals("complete", ignoreCase = true) && sample.inFlight <= 0
        if (settled) {
            if (quietSinceMs < 0) quietSinceMs = sample.atMs
            if (sample.atMs - quietSinceMs >= settleMs) return ReadinessVerdict.Ready
        } else {
            quietSinceMs = -1L
        }
        if (sample.atMs - startedAtMs >= ceilingMs) {
            timedOut = true
            return ReadinessVerdict.TimedOut
        }
        return ReadinessVerdict.Busy
    }

    /** What the ceiling running out means, said plainly. */
    fun busyMessage(sample: ReadinessSample?): String {
        val state = sample?.readyState?.ifBlank { "unknown" } ?: "unknown"
        val inFlight = sample?.inFlight ?: 0
        return "The page never went quiet within ${ceilingMs}ms (readyState=$state, " +
            "$inFlight request(s) still in flight). It is still busy, so anything read now may be " +
            "incomplete."
    }
}
