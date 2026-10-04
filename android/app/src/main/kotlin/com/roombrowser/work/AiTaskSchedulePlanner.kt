package com.roombrowser.work

import com.roombrowser.domain.task.ScheduleKind
import com.roombrowser.domain.task.ScheduleMath
import com.roombrowser.domain.task.TaskSchedule
import java.time.ZoneId

/**
 * The worker's arithmetic, kept pure and separate so it fails in the fast
 * `quality` job instead of on a device.
 *
 * WorkManager will not deliver work more often than
 * [ScheduleMath.WORKMANAGER_FLOOR_MINUTES] and caps an interval at
 * [ScheduleMath.MAX_WORKMANAGER_INTERVAL_MINUTES]. An INTERVAL schedule is
 * therefore judged and scheduled through [effectiveSchedule], so a 5-minute
 * task is honestly a 15-minute one rather than a task whose every delivery
 * finds itself not yet due. Sub-floor tasks are also flagged
 * ([requiresForeground]) so the worker can take the foreground path and the
 * UI can say what the real cadence is.
 */
object AiTaskSchedulePlanner {

    /**
     * The schedule as WorkManager can actually honour. Only INTERVAL is
     * clamped: DAILY/WEEKLY/MONTHLY name an instant, and a one-time delayed
     * delivery has no minimum.
     */
    fun effectiveSchedule(schedule: TaskSchedule): TaskSchedule =
        if (schedule.kind == ScheduleKind.INTERVAL) {
            schedule.copy(
                intervalMinutes =
                    ScheduleMath.effectiveWorkManagerInterval(schedule.intervalMinutes).toInt()
            )
        } else {
            schedule
        }

    /** The next instant WorkManager should deliver this task; null when the
     *  schedule can never fire (a non-positive interval, a missing day of
     *  month) — an invalid schedule to surface, not a "later". */
    fun nextRunAtMs(schedule: TaskSchedule, nowMs: Long, zone: ZoneId): Long? =
        ScheduleMath.nextRunAt(effectiveSchedule(schedule), nowMs, zone)

    /** Milliseconds until [nextRunAtMs], or null when it can never fire. */
    fun delayMs(schedule: TaskSchedule, nowMs: Long, zone: ZoneId): Long? =
        nextRunAtMs(schedule, nowMs, zone)?.let { it - nowMs }

    /**
     * Due when an occurrence lies in `(lastRunAtMs, now]` (catch-up after a
     * late Doze delivery). A task that has never run has no such window — its
     * first delivery IS the occurrence it was scheduled for, so it is due.
     */
    fun isDue(schedule: TaskSchedule, lastRunAtMs: Long?, nowMs: Long, zone: ZoneId): Boolean =
        lastRunAtMs == null || ScheduleMath.isDue(effectiveSchedule(schedule), nowMs, lastRunAtMs, zone)

    /**
     * Whether this task asks for a cadence WorkManager cannot deliver, so the
     * worker promotes itself to a foreground service for its delivery and the
     * UI warns that the real interval is the floor.
     */
    fun requiresForeground(schedule: TaskSchedule): Boolean =
        schedule.kind == ScheduleKind.INTERVAL &&
            ScheduleMath.requiresHighFrequency(schedule.intervalMinutes)

    /** Unique work name per task: editing reuses it (REPLACE), so pending work
     *  is replaced rather than stacked. */
    fun uniqueWorkName(taskId: Long): String = "ai-task-$taskId"
}
