package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `readyState === 'complete'` alone is what the old fixed delay stood in for,
 * and it is not enough — this pins that outstanding requests hold the gate shut
 * and that the ceiling is a real, honest exit rather than an indefinite wait.
 */
class PageReadinessTest {

    @Test
    fun a_complete_and_quiet_page_is_ready_after_the_settle_window() {
        val readiness = PageReadiness(settleMs = 600, ceilingMs = 5_000)
        readiness.start(0)

        assertThat(readiness.observe(ReadinessSample("complete", 0, 100)))
            .isEqualTo(ReadinessVerdict.Busy)
        assertThat(readiness.observe(ReadinessSample("complete", 0, 800)))
            .isEqualTo(ReadinessVerdict.Ready)
    }

    @Test
    fun an_in_flight_request_keeps_the_gate_shut_even_when_complete() {
        val readiness = PageReadiness(settleMs = 600, ceilingMs = 5_000)
        readiness.start(0)

        assertThat(readiness.observe(ReadinessSample("complete", 2, 100)))
            .isEqualTo(ReadinessVerdict.Busy)
        // Past the settle window, and still busy purely because of the request.
        assertThat(readiness.observe(ReadinessSample("complete", 2, 1_000)))
            .isEqualTo(ReadinessVerdict.Busy)
    }

    @Test
    fun a_page_that_never_goes_quiet_reports_a_timeout_rather_than_ready() {
        val readiness = PageReadiness(settleMs = 600, ceilingMs = 1_000)
        readiness.start(0)

        assertThat(readiness.observe(ReadinessSample("loading", 3, 500)))
            .isEqualTo(ReadinessVerdict.Busy)
        assertThat(readiness.observe(ReadinessSample("loading", 3, 1_200)))
            .isEqualTo(ReadinessVerdict.TimedOut)
    }

    @Test
    fun the_timeout_says_what_the_page_was_still_doing() {
        val readiness = PageReadiness(settleMs = 600, ceilingMs = 1_000)

        val message = readiness.busyMessage(ReadinessSample("loading", 4, 0))

        assertThat(message).contains("readyState=loading")
        assertThat(message).contains("4 request(s) still in flight")
        assertThat(message).contains("still busy")
    }
}
