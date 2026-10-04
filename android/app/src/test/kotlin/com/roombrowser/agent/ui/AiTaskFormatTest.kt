package com.roombrowser.agent.ui

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.task.ScheduleKind
import com.roombrowser.domain.task.TaskSchedule
import com.roombrowser.work.AiTaskSchedulePlanner
import java.time.Instant
import java.time.ZoneId
import org.junit.Test

/** What the screens promise is what the worker delivers — the two must read the
 *  same schedule, clamped the same way. */
class AiTaskFormatTest {

    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-04T10:00:00Z").toEpochMilli()

    private fun interval(minutes: Int) =
        TaskSchedule(ScheduleKind.INTERVAL, intervalMinutes = minutes)

    @Test
    fun `a sub-floor interval reads as the cadence it will be delivered at`() {
        val requested = interval(5)
        assertThat(scheduleSummary(requested)).isEqualTo("Every 15 min")
        assertThat(scheduleClampNotice(requested))
            .isEqualTo(
                "Android's background scheduler clamps this interval: the task runs " +
                    "every 15 min, not every 5 min."
            )
    }

    @Test
    fun `an interval above the ceiling reads as the clamped cadence too`() {
        val requested = interval(365 * 24 * 60)
        assertThat(scheduleSummary(requested)).isEqualTo("Every 15 d")
        assertThat(scheduleClampNotice(requested)).contains("not every 365 d")
    }

    @Test
    fun `an interval the scheduler can keep is summarised unchanged and not flagged`() {
        assertThat(scheduleSummary(interval(45))).isEqualTo("Every 45 min")
        assertThat(scheduleClampNotice(interval(45))).isNull()
        assertThat(scheduleClampNotice(TaskSchedule(ScheduleKind.DAILY, minuteOfDay = 9 * 60)))
            .isNull()
    }

    @Test
    fun `the next run shown is the instant the planner actually enqueues`() {
        val requested = interval(365 * 24 * 60)
        val enqueued = AiTaskSchedulePlanner.nextRunAtMs(requested, now, utc)
        assertThat(enqueued).isEqualTo(now + 15L * 24 * 60 * 60_000L)
        assertThat(nextRunText(requested, now, utc))
            .isEqualTo(nextRunText(AiTaskSchedulePlanner.effectiveSchedule(requested), now, utc))
    }
}
