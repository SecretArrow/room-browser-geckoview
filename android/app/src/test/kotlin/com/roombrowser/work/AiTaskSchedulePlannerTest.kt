package com.roombrowser.work

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.task.ScheduleKind
import com.roombrowser.domain.task.ScheduleMath
import com.roombrowser.domain.task.TaskSchedule
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import org.junit.Test

/**
 * The worker's arithmetic, pinned in the fast `quality` job.
 *
 * Two properties matter most and both are asserted directly: an interval below
 * WorkManager's floor is CLAMPED to the floor (so a 5-minute task still becomes
 * due and still fires, just every 15), and a late delivery owes catch-up
 * exactly once (so a Doze-woken worker does not run the same missed occurrence
 * on every subsequent delivery).
 */
class AiTaskSchedulePlannerTest {

    private val utc = ZoneId.of("UTC")
    private val noon = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()

    private fun interval(minutes: Int) =
        TaskSchedule(kind = ScheduleKind.INTERVAL, intervalMinutes = minutes)

    private fun daily(minuteOfDay: Int) =
        TaskSchedule(kind = ScheduleKind.DAILY, minuteOfDay = minuteOfDay)

    @Test
    fun effective_schedule_floors_a_sub_minimum_interval() {
        val effective = AiTaskSchedulePlanner.effectiveSchedule(interval(5))
        assertThat(effective.intervalMinutes)
            .isEqualTo(ScheduleMath.WORKMANAGER_FLOOR_MINUTES)
    }

    @Test
    fun effective_schedule_caps_an_interval_above_the_workmanager_maximum() {
        val effective = AiTaskSchedulePlanner.effectiveSchedule(interval(365 * 24 * 60))
        assertThat(effective.intervalMinutes)
            .isEqualTo(ScheduleMath.MAX_WORKMANAGER_INTERVAL_MINUTES)
    }

    @Test
    fun effective_schedule_leaves_a_valid_interval_and_exact_times_alone() {
        assertThat(AiTaskSchedulePlanner.effectiveSchedule(interval(60)).intervalMinutes)
            .isEqualTo(60)
        val dailySchedule = daily(9 * 60)
        assertThat(AiTaskSchedulePlanner.effectiveSchedule(dailySchedule))
            .isEqualTo(dailySchedule)
    }

    @Test
    fun a_sub_minimum_task_is_next_due_one_floor_away_not_one_requested_interval_away() {
        val next = AiTaskSchedulePlanner.nextRunAtMs(interval(5), noon, utc)
        assertThat(next).isEqualTo(noon + 15L * 60_000L)
    }

    @Test
    fun a_weekly_schedule_uses_its_time_on_the_next_selected_day() {
        // 2026-01-01 is a Thursday; the next Monday 09:00 UTC is 2026-01-05.
        val weekly = TaskSchedule(
            kind = ScheduleKind.WEEKLY,
            minuteOfDay = 9 * 60,
            daysOfWeek = setOf(DayOfWeek.MONDAY)
        )
        val next = AiTaskSchedulePlanner.nextRunAtMs(weekly, noon, utc)
        assertThat(next).isEqualTo(Instant.parse("2026-01-05T09:00:00Z").toEpochMilli())
    }

    @Test
    fun a_schedule_that_can_never_fire_has_no_delay() {
        assertThat(AiTaskSchedulePlanner.delayMs(interval(0), noon, utc)).isNull()
        assertThat(
            AiTaskSchedulePlanner.delayMs(
                TaskSchedule(kind = ScheduleKind.MONTHLY, dayOfMonth = null),
                noon,
                utc
            )
        ).isNull()
    }

    @Test
    fun a_schedule_that_can_never_fire_is_never_due_even_before_its_first_run() {
        // The zero interval must survive effectiveSchedule: flooring it to 15
        // would make an invalid schedule look like a valid quarter-hourly one.
        assertThat(AiTaskSchedulePlanner.isDue(interval(0), null, noon, utc)).isFalse()
        assertThat(
            AiTaskSchedulePlanner.isDue(
                TaskSchedule(kind = ScheduleKind.MONTHLY, dayOfMonth = null),
                null,
                noon,
                utc
            )
        ).isFalse()
        assertThat(AiTaskSchedulePlanner.isDue(interval(60), null, noon, utc)).isTrue()
    }

    @Test
    fun a_task_that_has_never_run_is_due_because_its_first_delivery_is_the_occurrence() {
        assertThat(AiTaskSchedulePlanner.isDue(interval(60), lastRunAtMs = null, nowMs = noon, zone = utc))
            .isTrue()
    }

    @Test
    fun a_delivery_just_after_a_run_is_not_due_again() {
        val lastRun = noon - 60_000L
        assertThat(AiTaskSchedulePlanner.isDue(interval(60), lastRun, noon, utc)).isFalse()
    }

    @Test
    fun a_late_delivery_after_a_missed_occurrence_is_due_exactly_once() {
        // Ran at 08:00, woke at 12:00 — four hourly occurrences were missed,
        // but one run settles them all.
        val lastRun = Instant.parse("2026-01-01T08:00:00Z").toEpochMilli()
        assertThat(AiTaskSchedulePlanner.isDue(interval(60), lastRun, noon, utc)).isTrue()

        val afterCatchUp = noon
        assertThat(AiTaskSchedulePlanner.isDue(interval(60), afterCatchUp, noon, utc)).isFalse()
    }

    @Test
    fun requires_foreground_only_for_sub_floor_intervals() {
        assertThat(AiTaskSchedulePlanner.requiresForeground(interval(5))).isTrue()
        assertThat(AiTaskSchedulePlanner.requiresForeground(interval(15))).isFalse()
        assertThat(AiTaskSchedulePlanner.requiresForeground(daily(9 * 60))).isFalse()
    }

    @Test
    fun unique_work_name_is_stable_per_task() {
        assertThat(AiTaskSchedulePlanner.uniqueWorkName(42)).isEqualTo("ai-task-42")
        assertThat(AiTaskSchedulePlanner.uniqueWorkName(42))
            .isEqualTo(AiTaskSchedulePlanner.uniqueWorkName(42))
        assertThat(AiTaskSchedulePlanner.uniqueWorkName(43)).isNotEqualTo("ai-task-42")
    }

    @Test
    fun quiet_hours_push_the_next_run_past_the_window() {
        val schedule = daily(7 * 60).copy(quietFromMinute = 6 * 60, quietToMinute = 8 * 60)
        val next = AiTaskSchedulePlanner.nextRunAtMs(schedule, noon, utc)
        assertThat(next).isEqualTo(Instant.parse("2026-01-02T08:00:00Z").toEpochMilli())
    }
}
