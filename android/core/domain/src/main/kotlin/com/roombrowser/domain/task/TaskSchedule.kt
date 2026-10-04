package com.roombrowser.domain.task

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * When an AI task should run, in local wall-clock terms.
 *
 * This is the persisted half of the schedule; the arithmetic that turns it into
 * concrete instants lives in [ScheduleMath] and is deliberately free of Android
 * types, so the whole of it is provable in a JVM unit test. Nothing in this file
 * reads a clock: every function that needs "now" is handed it as a parameter.
 *
 * @property kind which of the four recurrence shapes this schedule uses.
 * @property intervalMinutes for [ScheduleKind.INTERVAL]: run every N minutes.
 *   Ignored by the other kinds.
 * @property minuteOfDay for [ScheduleKind.DAILY] / [ScheduleKind.WEEKLY] /
 *   [ScheduleKind.MONTHLY]: local wall-clock minute of day, 0..1439. `null`
 *   means midnight (`0`).
 * @property daysOfWeek for [ScheduleKind.WEEKLY]: which days. An empty set means
 *   every day, which makes WEEKLY degenerate to DAILY.
 * @property dayOfMonth for [ScheduleKind.MONTHLY]: 1..31, clamped down to the
 *   length of a shorter month (so `31` fires on the 30th of April, the 28th of
 *   February). Required for MONTHLY; a MONTHLY schedule without it has no next
 *   run.
 * @property quietFromMinute start of quiet hours, local minute of day, inclusive.
 *   `null` (or a missing partner) disables quiet hours.
 * @property quietToMinute end of quiet hours, local minute of day, exclusive.
 *   A window may wrap past midnight (`1320` to `420` is 22:00 to 07:00); if both
 *   ends are equal the window is empty, i.e. no quiet hours.
 */
@Serializable
data class TaskSchedule(
    val kind: ScheduleKind,
    /** For INTERVAL: every N minutes. Ignored otherwise. */
    val intervalMinutes: Int = 15,
    /** For DAILY / WEEKLY: local wall-clock minute-of-day (0..1439). */
    val minuteOfDay: Int? = null,
    /** For WEEKLY: which days. Empty means every day. */
    @Serializable(with = DayOfWeekSetSerializer::class)
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    /** For MONTHLY: day of month, 1..31 (clamped to the month's length). */
    val dayOfMonth: Int? = null,
    /** Quiet hours, local, inclusive start / exclusive end. Null = none. */
    val quietFromMinute: Int? = null,
    val quietToMinute: Int? = null
)

/**
 * There is deliberately no YEARLY kind, so a yearly `02-29` schedule is out of
 * scope: it has no representation in this API. MONTHLY clamps to the real length
 * of each month, so a `dayOfMonth = 29` fires on 29 February in a leap year and
 * on the 28th otherwise -- that is month clamping, not an annual recurrence.
 */
enum class ScheduleKind { INTERVAL, DAILY, WEEKLY, MONTHLY }

/**
 * kotlinx.serialization ships no serializer for `java.time` types, so
 * [DayOfWeek] is persisted as its ISO-8601 integer (`1` = Monday .. `7` =
 * Sunday) and the set is written as a sorted list of those integers. Sorting
 * keeps the encoding stable, which matters for a value that is compared and
 * backed up.
 */
internal object DayOfWeekSetSerializer : KSerializer<Set<DayOfWeek>> {
    private val delegate = ListSerializer(Int.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Set<DayOfWeek>) {
        delegate.serialize(encoder, value.map { it.value }.sorted())
    }

    override fun deserialize(decoder: Decoder): Set<DayOfWeek> =
        delegate.deserialize(decoder).mapTo(LinkedHashSet()) { DayOfWeek.of(it) }
}

/**
 * All schedule arithmetic, as pure functions of (schedule, instant, zone).
 *
 * There is no `System.currentTimeMillis()` anywhere in this object, and no
 * Android class either. That is what makes the DST and quiet-hours behaviour
 * testable: a test pins the zone and the instant and gets the same answer no
 * matter where or when it runs, which an on-device test cannot do.
 *
 * The scheduler's job is then only "ask for the next instant, enqueue it, trust
 * WorkManager" -- with the "next instant" already proved here.
 */
object ScheduleMath {

    /** WorkManager PeriodicWorkRequest floor. */
    const val WORKMANAGER_FLOOR_MINUTES = 15

    /**
     * Ceiling for a WorkManager interval. WorkManager's own guidance is that
     * periodic work is due no earlier than its interval and may be late; an
     * interval beyond this is not something we let a schedule ask for, so it is
     * clamped rather than silently handed to the framework.
     */
    const val MAX_WORKMANAGER_INTERVAL_MINUTES = 15 * 24 * 60

    private const val MINUTE_MS = 60_000L
    private const val DAYS_GUARD = 3_700
    private const val MONTHS_GUARD = 1_200

    /**
     * `true` when the requested interval is below WorkManager's floor, so the
     * periodic path cannot honour it and the high-frequency foreground-service
     * path must be used instead.
     */
    fun requiresHighFrequency(intervalMinutes: Int): Boolean =
        intervalMinutes < WORKMANAGER_FLOOR_MINUTES

    /**
     * The interval WorkManager will actually be given: never below the 15-minute
     * floor, never above [MAX_WORKMANAGER_INTERVAL_MINUTES]. `5` becomes `15`.
     */
    fun effectiveWorkManagerInterval(intervalMinutes: Int): Long =
        intervalMinutes
            .coerceIn(WORKMANAGER_FLOOR_MINUTES, MAX_WORKMANAGER_INTERVAL_MINUTES)
            .toLong()

    /**
     * The first instant strictly after [fromEpochMs] at which the schedule is
     * allowed to run, or `null` when the schedule is incomplete (a MONTHLY
     * without a day, a non-positive interval).
     *
     * The result is always strictly greater than [fromEpochMs]; calling this
     * with its own result advances to the following occurrence. If the natural
     * occurrence falls inside quiet hours it is deferred to the end of the
     * quiet window, never dropped.
     */
    fun nextRunAt(s: TaskSchedule, fromEpochMs: Long, zone: ZoneId): Long? {
        val base = baseNextRunAt(s, fromEpochMs, zone) ?: return null
        return deferPastQuietHours(s, base, zone)
    }

    /**
     * Every run instant in the half-open window `(fromMs, toMs]`, in order.
     *
     * The window is exclusive at the start and inclusive at the end to match
     * [isDue] and the run ledger: a run recorded at `lastRunAtMs` is not a
     * candidate again, and a run due exactly at `nowMs` is.
     */
    fun occurrencesBetween(s: TaskSchedule, fromMs: Long, toMs: Long, zone: ZoneId): List<Long> {
        if (toMs <= fromMs) return emptyList()
        val occurrences = mutableListOf<Long>()
        var cursor = fromMs
        while (true) {
            val next = nextRunAt(s, cursor, zone) ?: break
            // nextRunAt is strictly monotonic, so this always advances; the
            // break conditions are the only exits.
            if (next > toMs) break
            occurrences += next
            cursor = next
        }
        return occurrences
    }

    /**
     * Whether the task should run now.
     *
     * This is the Doze-tolerant catch-up test: it is `true` when at least one
     * occurrence lies in `(lastRunAtMs, nowMs]`, so a periodic delivery that
     * WorkManager postponed past several intervals still fires once when it
     * finally arrives. `lastRunAtMs == null` (never run) is not due: there is no
     * anchor to measure a missed window from, and treating it as due would run
     * every freshly created task immediately. The scheduler passes the task's
     * creation time as the anchor if it wants a first check against a real
     * window.
     *
     * There is no catch-up *policy* field on [TaskSchedule]; the permissive
     * policy above is the only one encoded. A "skip missed runs, run only the
     * latest" policy would need a field on the schedule and is not part of this
     * API.
     */
    fun isDue(s: TaskSchedule, nowMs: Long, lastRunAtMs: Long?, zone: ZoneId): Boolean {
        if (lastRunAtMs == null) return false
        if (lastRunAtMs >= nowMs) return false
        return occurrencesBetween(s, lastRunAtMs, nowMs, zone).isNotEmpty()
    }

    /**
     * Whether [atMs] falls inside the schedule's quiet hours. Start is
     * inclusive, end is exclusive; a window whose ends are equal is empty, so it
     * never suppresses. A window may wrap past midnight.
     */
    fun suppressForQuietHours(s: TaskSchedule, atMs: Long, zone: ZoneId): Boolean {
        val from = s.quietFromMinute ?: return false
        val to = s.quietToMinute ?: return false
        if (from == to) return false
        val minute = localMinuteOfDay(atMs, zone)
        return if (from < to) {
            minute >= from && minute < to
        } else {
            minute >= from || minute < to
        }
    }

    // --- internals -------------------------------------------------------

    /** The natural occurrence, before quiet-hours deferral. */
    private fun baseNextRunAt(s: TaskSchedule, fromMs: Long, zone: ZoneId): Long? {
        return when (s.kind) {
            ScheduleKind.INTERVAL ->
                if (s.intervalMinutes <= 0) {
                    null
                } else {
                    fromMs + s.intervalMinutes.toLong() * MINUTE_MS
                }
            ScheduleKind.DAILY ->
                nextMatchingDay(fromMs, zone, s.minuteOfDay ?: 0) { true }
            ScheduleKind.WEEKLY -> {
                val days = s.daysOfWeek
                nextMatchingDay(fromMs, zone, s.minuteOfDay ?: 0) { date ->
                    days.isEmpty() || date.dayOfWeek in days
                }
            }
            ScheduleKind.MONTHLY -> {
                val day = s.dayOfMonth ?: return null
                nextMonthly(fromMs, zone, day.coerceIn(1, 31), s.minuteOfDay ?: 0)
            }
        }
    }

    /** Next date matching [matches] at [minuteOfDay], strictly after [fromMs]. */
    private inline fun nextMatchingDay(
        fromMs: Long,
        zone: ZoneId,
        minuteOfDay: Int,
        matches: (LocalDate) -> Boolean
    ): Long {
        val minute = minuteOfDay.coerceIn(0, 1439)
        var date = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
        var guard = 0
        while (guard++ <= DAYS_GUARD) {
            if (matches(date)) {
                val candidate = resolveLocal(zone, LocalDateTime.of(date, minuteToLocalTime(minute)))
                if (candidate > fromMs) return candidate
            }
            date = date.plusDays(1)
        }
        error("ScheduleMath: no matching day found within $DAYS_GUARD days")
    }

    /** Next month whose clamped [dayOfMonth] at [minuteOfDay] is after [fromMs]. */
    private fun nextMonthly(fromMs: Long, zone: ZoneId, dayOfMonth: Int, minuteOfDay: Int): Long {
        val minute = minuteOfDay.coerceIn(0, 1439)
        var yearMonth = YearMonth.from(Instant.ofEpochMilli(fromMs).atZone(zone))
        var guard = 0
        while (guard++ <= MONTHS_GUARD) {
            // A 31 in a 30-day month falls back to the last day, on purpose: the
            // schedule is "the end of the month", not "skip this month".
            val day = minOf(dayOfMonth, yearMonth.lengthOfMonth())
            val candidate = resolveLocal(
                zone,
                LocalDateTime.of(yearMonth.atDay(day), minuteToLocalTime(minute))
            )
            if (candidate > fromMs) return candidate
            yearMonth = yearMonth.plusMonths(1)
        }
        error("ScheduleMath: no matching month found within $MONTHS_GUARD months")
    }

    /**
     * Resolves a local wall-clock date-time to an instant, deciding the two DST
     * cases the JVM otherwise leaves to the caller:
     *
     *  * a time that does not exist (spring-forward gap) resolves to the first
     *    instant after the gap -- the transition instant itself -- so the run
     *    happens once, not zero times and not an hour late;
     *  * a time that happens twice (fall-back overlap) resolves to the *earlier*
     *    of the two instants, so it also happens once.
     */
    private fun resolveLocal(zone: ZoneId, dateTime: LocalDateTime): Long {
        val rules = zone.rules
        val offsets = rules.getValidOffsets(dateTime)
        if (offsets.isEmpty()) {
            val transition = rules.getTransition(dateTime)
                ?: return dateTime.atZone(zone).toInstant().toEpochMilli()
            return transition.instant.toEpochMilli()
        }
        // One offset in the normal case; two in an overlap, where the earliest
        // instant is the one that fires exactly once.
        return offsets.minOf { dateTime.toInstant(it).toEpochMilli() }
    }

    /** Advances [instantMs] past any quiet window it sits in. */
    private fun deferPastQuietHours(s: TaskSchedule, instantMs: Long, zone: ZoneId): Long {
        var result = instantMs
        var guard = 0
        while (suppressForQuietHours(s, result, zone)) {
            // A quiet window is shorter than a day and its (exclusive) end is
            // always allowed, so this converges in one step; the bound only
            // catches a malformed window rather than looping forever.
            check(guard++ < 8) { "ScheduleMath: quiet-hours deferral did not converge" }
            result = quietEndAfter(s, result, zone)
        }
        return result
    }

    /**
     * The first instant strictly after [instantMs] whose local time is the
     * (exclusive) end of quiet hours. Works across midnight and across a DST
     * transition.
     */
    private fun quietEndAfter(s: TaskSchedule, instantMs: Long, zone: ZoneId): Long {
        val end = (s.quietToMinute ?: return instantMs).coerceIn(0, 1439)
        var date = Instant.ofEpochMilli(instantMs).atZone(zone).toLocalDate()
        var guard = 0
        while (guard++ < 3) {
            val candidate = resolveLocal(zone, LocalDateTime.of(date, minuteToLocalTime(end)))
            if (candidate > instantMs) return candidate
            date = date.plusDays(1)
        }
        return instantMs
    }

    private fun localMinuteOfDay(atMs: Long, zone: ZoneId): Int {
        val time = Instant.ofEpochMilli(atMs).atZone(zone).toLocalTime()
        return time.hour * 60 + time.minute
    }

    private fun minuteToLocalTime(minuteOfDay: Int): LocalTime =
        LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
}
