package com.roombrowser.agent.ui

import com.roombrowser.data.db.AiTaskEntity
import com.roombrowser.data.repo.schedule
import com.roombrowser.domain.task.AiTaskRunStatus
import com.roombrowser.domain.task.ScheduleKind
import com.roombrowser.domain.task.ScheduleMath
import com.roombrowser.domain.task.TaskSchedule
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Human-readable schedule text for the list and confirmations. An INTERVAL
 *  reads as the cadence it will actually be delivered at, not the one it was
 *  asked for — [scheduleClampNotice] says what was changed. */
fun scheduleSummary(s: TaskSchedule): String {
    val core = when (s.kind) {
        ScheduleKind.INTERVAL -> "Every ${humanInterval(ScheduleMath.effective(s).intervalMinutes)}"
        ScheduleKind.DAILY -> "Daily at ${formatMinuteOfDay(s.minuteOfDay ?: 0)}"
        ScheduleKind.WEEKLY -> {
            val days = if (s.daysOfWeek.isEmpty()) "every day"
            else s.daysOfWeek.sortedBy { it.value }
                .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
            "Weekly on $days at ${formatMinuteOfDay(s.minuteOfDay ?: 0)}"
        }
        ScheduleKind.MONTHLY ->
            "Monthly on day ${s.dayOfMonth ?: 1} at ${formatMinuteOfDay(s.minuteOfDay ?: 0)}"
    }
    val quietFrom = s.quietFromMinute
    val quietTo = s.quietToMinute
    return if (quietFrom != null && quietTo != null && quietFrom != quietTo) {
        "$core (quiet ${formatMinuteOfDay(quietFrom)}–${formatMinuteOfDay(quietTo)})"
    } else {
        core
    }
}

/**
 * The next run an observer can actually expect, or a plain statement that
 * there is none. null from [ScheduleMath.nextRunAt] is a schedule that can
 * never fire (an INTERVAL with a non-positive interval, a MONTHLY with no day
 * of month) — never silently "later".
 */
fun nextRunText(s: TaskSchedule, nowMs: Long, zone: ZoneId): String =
    ScheduleMath.nextRunAt(ScheduleMath.effective(s), nowMs, zone)
        ?.let { "Next run: ${formatInstant(it, zone)}" }
        ?: "This schedule can never fire — check its interval or day of month."

/** One line for the last delivery, honest about a run that only deferred. */
fun lastRunText(task: AiTaskEntity, zone: ZoneId): String? {
    val at = task.lastRunAtMs ?: return null
    val whenText = formatInstant(at, zone)
    return when (AiTaskRunStatus.fromStored(task.lastRunStatus)) {
        AiTaskRunStatus.COMPLETED -> "Last run $whenText: ${task.lastResultSummary ?: "done"}"
        AiTaskRunStatus.DEFERRED -> "Last attempt $whenText: ${task.lastResultSummary ?: "deferred"}"
        AiTaskRunStatus.FAILED -> "Last run $whenText failed: ${task.lastResultSummary ?: "unknown error"}"
        null -> "Last run $whenText"
    }
}

/** Why the cadence that will be delivered differs from the one requested, or
 *  null when they are the same. Android clamps an INTERVAL at both ends. */
fun scheduleClampNotice(s: TaskSchedule): String? {
    val effective = ScheduleMath.effective(s).intervalMinutes
    if (effective == s.intervalMinutes) return null
    return "Android's background scheduler clamps this interval: the task runs every " +
        "${humanInterval(effective)}, not every ${humanInterval(s.intervalMinutes)}."
}

fun formatMinuteOfDay(minuteOfDay: Int): String =
    String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

/** "HH:mm" → minutes past midnight, or null when it is not a valid time. */
fun parseMinuteOfDay(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun humanInterval(minutes: Int): String = when {
    minutes <= 0 -> "invalid interval"
    minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)} d"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "$minutes min"
}

private val INSTANT_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.getDefault())

private fun formatInstant(epochMs: Long, zone: ZoneId): String =
    INSTANT_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(zone))

/** Weekday chips, in week order. */
val WEEKDAY_ORDER: List<DayOfWeek> = DayOfWeek.entries.sortedBy { it.value }
