package com.roombrowser.agent.ui

import com.roombrowser.data.db.AiTaskEntity
import com.roombrowser.domain.agent.AgentPlan
import com.roombrowser.domain.agent.UntrustedContent
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

/**
 * One line for the last delivery, honest about a run that only deferred.
 *
 * [AiTaskEntity.lastResultSummary] holds the model's own answer, which it
 * sometimes writes with the page-content frame still in it. The producer takes
 * that frame off, and this takes it off again: rows written before it did are
 * already on disk, flattened to one line and cut off mid-frame, and
 * [UntrustedContent.payload] is what recognises that shape. It is a no-op on a
 * summary that never had a fence, so the second pass costs nothing.
 */
fun lastRunText(task: AiTaskEntity, zone: ZoneId): String? {
    val at = task.lastRunAtMs ?: return null
    val whenText = formatInstant(at, zone)
    // A stored row that was nothing but the frame comes back empty, and the
    // status word is the honest thing to show for it.
    val summary = task.lastResultSummary
        ?.let { UntrustedContent.payload(it) }
        ?.takeIf { it.isNotBlank() }
    return when (AiTaskRunStatus.fromStored(task.lastRunStatus)) {
        AiTaskRunStatus.COMPLETED -> "Last run $whenText: ${summary ?: "done"}"
        AiTaskRunStatus.DEFERRED -> "Last attempt $whenText: ${summary ?: "deferred"}"
        AiTaskRunStatus.FAILED -> "Last run $whenText failed: ${summary ?: "unknown error"}"
        null -> "Last run $whenText"
    }
}

/**
 * The plan a run last committed to, as one compact live line: "Plan: 2/5 done
 * · 1 running". This is how a scheduled run's progress reaches the task list
 * in real time — the runner overwrites [AiTaskEntity.planJson] on every
 * PlanUpdated event, the list re-reads the row through Room's reactive Flow,
 * and this renders whatever is current, with no polling and no reload.
 *
 * null when the row carries no plan (never run with plan mode, or a pre-plan
 * row): the line is then simply absent rather than a permanent "0/0".
 */
fun taskPlanText(task: AiTaskEntity): String? {
    val raw = task.planJson
    if (raw.isBlank()) return null
    val items = AgentPlan.decode(raw)
    if (items.isEmpty()) return null
    val done = items.count { it.status == AgentPlan.Status.DONE }
    val running = items.count { it.status == AgentPlan.Status.IN_PROGRESS }
    val failed = items.count { it.status == AgentPlan.Status.FAILED }
    return buildString {
        append("Plan: $done/${items.size} done")
        if (running > 0) append(" · $running running")
        if (failed > 0) append(" · $failed failed")
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
