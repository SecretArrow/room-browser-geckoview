package com.roombrowser.domain.task

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Test

class ScheduleMathTest {

    private val ny = ZoneId.of("America/New_York")
    private val utc = ZoneId.of("UTC")

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun `intervals below the WorkManager floor are clamped and flagged high-frequency`() {
        assertThat(ScheduleMath.effectiveWorkManagerInterval(5)).isEqualTo(15L)
        assertThat(ScheduleMath.effectiveWorkManagerInterval(14)).isEqualTo(15L)
        assertThat(ScheduleMath.effectiveWorkManagerInterval(15)).isEqualTo(15L)
        assertThat(ScheduleMath.effectiveWorkManagerInterval(16)).isEqualTo(16L)

        assertThat(ScheduleMath.requiresHighFrequency(5)).isTrue()
        assertThat(ScheduleMath.requiresHighFrequency(14)).isTrue()
        assertThat(ScheduleMath.requiresHighFrequency(15)).isFalse()
        assertThat(ScheduleMath.requiresHighFrequency(16)).isFalse()
    }

    @Test
    fun `an interval above the WorkManager ceiling is clamped to the ceiling`() {
        val ceiling = ScheduleMath.MAX_WORKMANAGER_INTERVAL_MINUTES
        assertThat(ScheduleMath.effectiveWorkManagerInterval(ceiling + 1))
            .isEqualTo(ceiling.toLong())
        assertThat(ScheduleMath.effectiveWorkManagerInterval(ceiling))
            .isEqualTo(ceiling.toLong())
    }

    @Test
    fun `an interval schedule advances by exactly its interval`() {
        val schedule = TaskSchedule(kind = ScheduleKind.INTERVAL, intervalMinutes = 15)
        val from = ms("2026-01-05T00:00:00Z")
        val next = ScheduleMath.nextRunAt(schedule, from, utc)!!
        assertThat(next - from).isEqualTo(15L * 60_000L)
        assertThat(ScheduleMath.nextRunAt(schedule, next, utc))
            .isEqualTo(ms("2026-01-05T00:30:00Z"))
    }

    // America/New_York 2026: 03-08 02:00 EST jumps to 03:00 EDT; 11-01 02:00 EDT falls back to 01:00 EST.
    @Test
    fun `a daily run in a DST gap resolves to the first instant after the gap, once`() {
        val schedule = TaskSchedule(kind = ScheduleKind.DAILY, minuteOfDay = 2 * 60 + 30)
        val gapFirstInstant = ms("2026-03-08T07:00:00Z")

        assertThat(ScheduleMath.nextRunAt(schedule, ms("2026-03-08T00:00:00Z"), ny))
            .isEqualTo(gapFirstInstant)

        val thatDay = ScheduleMath.occurrencesBetween(
            schedule,
            ms("2026-03-08T00:00:00Z"),
            ms("2026-03-08T23:59:59Z"),
            ny
        )
        assertThat(thatDay).containsExactly(gapFirstInstant).inOrder()
    }

    @Test
    fun `a daily run in a DST overlap fires once, not twice`() {
        val schedule = TaskSchedule(kind = ScheduleKind.DAILY, minuteOfDay = 1 * 60 + 30)
        val earlier = ms("2026-11-01T05:30:00Z")

        assertThat(ScheduleMath.nextRunAt(schedule, ms("2026-11-01T00:00:00Z"), ny))
            .isEqualTo(earlier)
        assertThat(
            ScheduleMath.occurrencesBetween(
                schedule,
                ms("2026-11-01T00:00:00Z"),
                ms("2026-11-01T23:59:59Z"),
                ny
            )
        ).containsExactly(earlier).inOrder()

        assertThat(ScheduleMath.nextRunAt(schedule, earlier, ny))
            .isEqualTo(ms("2026-11-02T06:30:00Z"))
    }

    @Test
    fun `monthly day 31 falls back to the last day of a shorter month`() {
        val schedule = TaskSchedule(kind = ScheduleKind.MONTHLY, dayOfMonth = 31)
        assertThat(ScheduleMath.nextRunAt(schedule, ms("2026-04-15T00:00:00Z"), utc))
            .isEqualTo(ms("2026-04-30T00:00:00Z"))
        assertThat(ScheduleMath.nextRunAt(schedule, ms("2026-04-30T00:00:00Z"), utc))
            .isEqualTo(ms("2026-05-31T00:00:00Z"))
        assertThat(ScheduleMath.nextRunAt(schedule, ms("2026-05-31T00:00:00Z"), utc))
            .isEqualTo(ms("2026-06-30T00:00:00Z"))
        assertThat(ScheduleMath.nextRunAt(schedule, ms("2026-02-01T00:00:00Z"), utc))
            .isEqualTo(ms("2026-02-28T00:00:00Z"))
        assertThat(ScheduleMath.nextRunAt(schedule, ms("2028-02-01T00:00:00Z"), utc))
            .isEqualTo(ms("2028-02-29T00:00:00Z"))
    }

    @Test
    fun `a run inside quiet hours is deferred to the window end, not dropped`() {
        val schedule = TaskSchedule(
            kind = ScheduleKind.DAILY,
            minuteOfDay = 2 * 60 + 30,
            quietFromMinute = 22 * 60,
            quietToMinute = 7 * 60
        )
        assertThat(ScheduleMath.nextRunAt(schedule, ms("2026-01-05T17:00:00Z"), ny))
            .isEqualTo(ms("2026-01-06T12:00:00Z"))

        val occurrences = ScheduleMath.occurrencesBetween(
            schedule,
            ms("2026-01-04T00:00:00Z"),
            ms("2026-01-07T00:00:00Z"),
            ny
        )
        assertThat(occurrences).containsExactly(
            ms("2026-01-04T12:00:00Z"),
            ms("2026-01-05T12:00:00Z"),
            ms("2026-01-06T12:00:00Z")
        ).inOrder()
    }

    @Test
    fun `a run exactly at the end of quiet hours is allowed`() {
        val schedule = TaskSchedule(
            kind = ScheduleKind.DAILY,
            minuteOfDay = 7 * 60,
            quietFromMinute = 22 * 60,
            quietToMinute = 7 * 60
        )
        assertThat(ScheduleMath.nextRunAt(schedule, ms("2026-01-05T00:00:00Z"), utc))
            .isEqualTo(ms("2026-01-05T07:00:00Z"))
    }

    @Test
    fun `quiet hours start is inclusive and end is exclusive, at the exact minute`() {
        val schedule = TaskSchedule(
            kind = ScheduleKind.DAILY,
            minuteOfDay = 0,
            quietFromMinute = 60,
            quietToMinute = 180
        )
        assertThat(ScheduleMath.suppressForQuietHours(schedule, ms("2026-01-05T00:59:00Z"), utc))
            .isFalse()
        assertThat(ScheduleMath.suppressForQuietHours(schedule, ms("2026-01-05T01:00:00Z"), utc))
            .isTrue()
        assertThat(ScheduleMath.suppressForQuietHours(schedule, ms("2026-01-05T02:59:00Z"), utc))
            .isTrue()
        assertThat(ScheduleMath.suppressForQuietHours(schedule, ms("2026-01-05T03:00:00Z"), utc))
            .isFalse()
    }

    @Test
    fun `a quiet window that wraps midnight suppresses on both sides of it`() {
        val schedule = TaskSchedule(
            kind = ScheduleKind.DAILY,
            minuteOfDay = 0,
            quietFromMinute = 22 * 60,
            quietToMinute = 7 * 60
        )
        assertThat(ScheduleMath.suppressForQuietHours(schedule, ms("2026-01-05T21:59:00Z"), utc))
            .isFalse()
        assertThat(ScheduleMath.suppressForQuietHours(schedule, ms("2026-01-05T22:00:00Z"), utc))
            .isTrue()
        assertThat(ScheduleMath.suppressForQuietHours(schedule, ms("2026-01-06T00:00:00Z"), utc))
            .isTrue()
        assertThat(ScheduleMath.suppressForQuietHours(schedule, ms("2026-01-06T06:59:00Z"), utc))
            .isTrue()
        assertThat(ScheduleMath.suppressForQuietHours(schedule, ms("2026-01-06T07:00:00Z"), utc))
            .isFalse()
    }

    @Test
    fun `a zero-length quiet window suppresses nothing all day`() {
        val schedule = TaskSchedule(
            kind = ScheduleKind.DAILY,
            minuteOfDay = 12 * 60,
            quietFromMinute = 9 * 60,
            quietToMinute = 9 * 60
        )
        assertThat(ScheduleMath.suppressForQuietHours(schedule, ms("2026-01-05T09:00:00Z"), utc))
            .isFalse()
        assertThat(ScheduleMath.nextRunAt(schedule, ms("2026-01-05T00:00:00Z"), utc))
            .isEqualTo(ms("2026-01-05T12:00:00Z"))
    }

    @Test
    fun `isDue catches up a run WorkManager delivered late`() {
        val schedule = TaskSchedule(kind = ScheduleKind.DAILY, minuteOfDay = 9 * 60)
        assertThat(
            ScheduleMath.isDue(
                schedule,
                ms("2026-01-08T10:00:00Z"),
                ms("2026-01-05T09:00:00Z"),
                utc
            )
        ).isTrue()
    }

    @Test
    fun `isDue excludes the last run and includes now, at the exact instant`() {
        val schedule = TaskSchedule(kind = ScheduleKind.DAILY, minuteOfDay = 9 * 60)
        val lastRun = ms("2026-01-05T09:00:00Z")

        assertThat(ScheduleMath.isDue(schedule, lastRun, lastRun, utc)).isFalse()
        assertThat(ScheduleMath.isDue(schedule, ms("2026-01-06T09:00:00Z"), lastRun, utc))
            .isTrue()
        assertThat(ScheduleMath.isDue(schedule, ms("2026-01-06T08:59:59Z"), lastRun, utc))
            .isFalse()
    }

    @Test
    fun `interval catch-up hits the exact interval boundary`() {
        val schedule = TaskSchedule(kind = ScheduleKind.INTERVAL, intervalMinutes = 15)
        val lastRun = ms("2026-01-05T00:00:00Z")
        assertThat(ScheduleMath.isDue(schedule, lastRun + 14 * 60_000, lastRun, utc)).isFalse()
        assertThat(ScheduleMath.isDue(schedule, lastRun + 15 * 60_000, lastRun, utc)).isTrue()
        assertThat(ScheduleMath.isDue(schedule, lastRun + 45 * 60_000, lastRun, utc)).isTrue()
    }

    @Test
    fun `a never-run task is not due until it has an anchor`() {
        val schedule = TaskSchedule(kind = ScheduleKind.DAILY, minuteOfDay = 9 * 60)
        assertThat(ScheduleMath.isDue(schedule, ms("2026-01-08T10:00:00Z"), null, utc)).isFalse()
    }

    @Test
    fun `nextRunAt is strictly monotonic and advances from its own result`() {
        val schedules = listOf(
            TaskSchedule(kind = ScheduleKind.INTERVAL, intervalMinutes = 15),
            TaskSchedule(kind = ScheduleKind.DAILY, minuteOfDay = 2 * 60 + 30),
            TaskSchedule(
                kind = ScheduleKind.WEEKLY,
                minuteOfDay = 9 * 60,
                daysOfWeek = setOf(DayOfWeek.MONDAY)
            ),
            TaskSchedule(kind = ScheduleKind.MONTHLY, dayOfMonth = 31)
        )
        val starts = listOf(ms("2026-03-08T00:00:00Z"), ms("2026-11-01T00:00:00Z"))

        for (schedule in schedules) {
            for (from in starts) {
                val first = ScheduleMath.nextRunAt(schedule, from, ny)!!
                val second = ScheduleMath.nextRunAt(schedule, first, ny)!!
                assertThat(first).isGreaterThan(from)
                assertThat(second).isGreaterThan(first)
            }
        }
    }

    @Test
    fun `weekly selects the requested days and an empty set means every day`() {
        val mondayOnly = TaskSchedule(
            kind = ScheduleKind.WEEKLY,
            minuteOfDay = 9 * 60,
            daysOfWeek = setOf(DayOfWeek.MONDAY)
        )
        assertThat(ScheduleMath.nextRunAt(mondayOnly, ms("2026-01-07T10:00:00Z"), utc))
            .isEqualTo(ms("2026-01-12T09:00:00Z"))

        val everyDay = TaskSchedule(kind = ScheduleKind.WEEKLY, minuteOfDay = 9 * 60)
        assertThat(ScheduleMath.nextRunAt(everyDay, ms("2026-01-07T10:00:00Z"), utc))
            .isEqualTo(ms("2026-01-08T09:00:00Z"))
    }

    @Test
    fun `an incomplete schedule has no next run`() {
        val monthlyWithoutDay = TaskSchedule(kind = ScheduleKind.MONTHLY)
        assertThat(ScheduleMath.nextRunAt(monthlyWithoutDay, ms("2026-01-05T00:00:00Z"), utc))
            .isNull()

        val zeroInterval = TaskSchedule(kind = ScheduleKind.INTERVAL, intervalMinutes = 0)
        assertThat(ScheduleMath.nextRunAt(zeroInterval, ms("2026-01-05T00:00:00Z"), utc))
            .isNull()

        assertThat(ScheduleMath.occurrencesBetween(monthlyWithoutDay, 0L, 1_000_000L, utc))
            .isEmpty()
    }

    @Test
    fun `the arithmetic is a pure function of its inputs`() {
        val schedule = TaskSchedule(
            kind = ScheduleKind.DAILY,
            minuteOfDay = 2 * 60 + 30,
            quietFromMinute = 22 * 60,
            quietToMinute = 7 * 60
        )
        val from = ms("2026-01-05T17:00:00Z")
        val first = ScheduleMath.nextRunAt(schedule, from, ny)
        val second = ScheduleMath.nextRunAt(schedule, from, ny)

        assertThat(first).isEqualTo(second)
        assertThat(first).isEqualTo(ms("2026-01-06T12:00:00Z"))
    }

    @Test
    fun `a schedule survives a JSON round-trip`() {
        val schedule = TaskSchedule(
            kind = ScheduleKind.WEEKLY,
            intervalMinutes = 30,
            minuteOfDay = 150,
            daysOfWeek = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY),
            dayOfMonth = 15,
            quietFromMinute = 1320,
            quietToMinute = 420
        )
        val json = Json.encodeToString(TaskSchedule.serializer(), schedule)
        val restored = Json.decodeFromString(TaskSchedule.serializer(), json)
        assertThat(restored).isEqualTo(schedule)
    }
}
