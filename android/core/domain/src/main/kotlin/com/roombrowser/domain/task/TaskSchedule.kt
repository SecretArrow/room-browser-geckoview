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
 * All minute fields are minutes past local midnight (0..1439); [daysOfWeek] and [dayOfMonth] are
 * ignored unless [kind] uses them. Quiet hours are local, start-inclusive and end-exclusive, and
 * `quietFromMinute == quietToMinute` means "no quiet hours" rather than "all day".
 */
@Serializable
data class TaskSchedule(
    val kind: ScheduleKind,
    val intervalMinutes: Int = 15,
    val minuteOfDay: Int? = null,
    @Serializable(with = DayOfWeekSetSerializer::class)
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val dayOfMonth: Int? = null,
    val quietFromMinute: Int? = null,
    val quietToMinute: Int? = null
)

enum class ScheduleKind { INTERVAL, DAILY, WEEKLY, MONTHLY }

// kotlinx.serialization has no java.time serializer, so store DayOfWeek as its ISO number.
internal object DayOfWeekSetSerializer : KSerializer<Set<DayOfWeek>> {
    private val delegate = ListSerializer(Int.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Set<DayOfWeek>) {
        delegate.serialize(encoder, value.map { it.value }.sorted())
    }

    override fun deserialize(decoder: Decoder): Set<DayOfWeek> =
        delegate.deserialize(decoder).mapTo(LinkedHashSet()) { DayOfWeek.of(it) }
}

object ScheduleMath {

    const val WORKMANAGER_FLOOR_MINUTES = 15
    const val MAX_WORKMANAGER_INTERVAL_MINUTES = 15 * 24 * 60

    private const val MINUTE_MS = 60_000L
    private const val DAYS_GUARD = 3_700
    private const val MONTHS_GUARD = 1_200

    fun requiresHighFrequency(intervalMinutes: Int): Boolean =
        intervalMinutes < WORKMANAGER_FLOOR_MINUTES

    fun effectiveWorkManagerInterval(intervalMinutes: Int): Long =
        intervalMinutes
            .coerceIn(WORKMANAGER_FLOOR_MINUTES, MAX_WORKMANAGER_INTERVAL_MINUTES)
            .toLong()

    /**
     * First instant strictly after [fromEpochMs]; a run inside quiet hours is deferred, not dropped.
     * null means this schedule can never fire (an INTERVAL with a non-positive interval, or a
     * MONTHLY with no day of month) — that is an invalid schedule to surface, not "later".
     */
    fun nextRunAt(s: TaskSchedule, fromEpochMs: Long, zone: ZoneId): Long? {
        val base = baseNextRunAt(s, fromEpochMs, zone) ?: return null
        return deferPastQuietHours(s, base, zone)
    }

    /** Run instants in the half-open window `(fromMs, toMs]`, in order. */
    fun occurrencesBetween(s: TaskSchedule, fromMs: Long, toMs: Long, zone: ZoneId): List<Long> {
        if (toMs <= fromMs) return emptyList()
        val occurrences = mutableListOf<Long>()
        var cursor = fromMs
        while (true) {
            val next = nextRunAt(s, cursor, zone) ?: break
            if (next > toMs) break
            occurrences += next
            cursor = next
        }
        return occurrences
    }

    /**
     * Catch-up for a late (Doze) delivery: true when an occurrence lies in `(lastRunAtMs, nowMs]`.
     * A task that has never run is not due, so creating a schedule whose time already passed today
     * waits for the next occurrence instead of firing at once. Any number of missed occurrences
     * still owes exactly one run, which the caller runs once and then stamps as done.
     */
    fun isDue(s: TaskSchedule, nowMs: Long, lastRunAtMs: Long?, zone: ZoneId): Boolean {
        if (lastRunAtMs == null) return false
        if (lastRunAtMs >= nowMs) return false
        return occurrencesBetween(s, lastRunAtMs, nowMs, zone).isNotEmpty()
    }

    /** Quiet hours: start inclusive, end exclusive; a wrapped window may cross midnight. */
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

    private fun nextMonthly(fromMs: Long, zone: ZoneId, dayOfMonth: Int, minuteOfDay: Int): Long {
        val minute = minuteOfDay.coerceIn(0, 1439)
        var yearMonth = YearMonth.from(Instant.ofEpochMilli(fromMs).atZone(zone))
        var guard = 0
        while (guard++ <= MONTHS_GUARD) {
            // A 31 in a shorter month clamps to that month's last day, never skips it.
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

    // A gap resolves to the transition instant and an overlap to the earlier instant, so each fires once.
    private fun resolveLocal(zone: ZoneId, dateTime: LocalDateTime): Long {
        val rules = zone.rules
        val offsets = rules.getValidOffsets(dateTime)
        if (offsets.isEmpty()) {
            val transition = rules.getTransition(dateTime)
                ?: return dateTime.atZone(zone).toInstant().toEpochMilli()
            return transition.instant.toEpochMilli()
        }
        return offsets.minOf { dateTime.toInstant(it).toEpochMilli() }
    }

    private fun deferPastQuietHours(s: TaskSchedule, instantMs: Long, zone: ZoneId): Long {
        var result = instantMs
        var guard = 0
        while (suppressForQuietHours(s, result, zone)) {
            check(guard++ < 8) { "ScheduleMath: quiet-hours deferral did not converge" }
            result = quietEndAfter(s, result, zone)
        }
        return result
    }

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
