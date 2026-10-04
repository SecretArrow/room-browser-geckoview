package com.roombrowser.work

import com.roombrowser.domain.task.ScheduleKind
import com.roombrowser.domain.task.ScheduleMath
import com.roombrowser.domain.task.TaskSchedule
import java.time.ZoneId

/**
 * The worker's arithmetic, kept pure and separate so it fails in the fast
 * `quality` job instead of on a device. The clamping rule itself lives in
 * [ScheduleMath.effective], so the scheduler, the due test and the UI all read
 * the same cadence.
 */
object AiTaskSchedulePlanner {

    /** The schedule as WorkManager can actually deliver it. */
    fun effectiveSchedule(schedule: TaskSchedule): TaskSchedule = ScheduleMath.effective(schedule)

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
     * first delivery IS the occurrence it was scheduled for, so it is due, as
     * long as the schedule can fire at all.
     */
    fun isDue(schedule: TaskSchedule, lastRunAtMs: Long?, nowMs: Long, zone: ZoneId): Boolean {
        val effective = effectiveSchedule(schedule)
        if (lastRunAtMs == null) return ScheduleMath.nextRunAt(effective, nowMs, zone) != null
        return ScheduleMath.isDue(effective, nowMs, lastRunAtMs, zone)
    }

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
