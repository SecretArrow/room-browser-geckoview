package com.roombrowser.data.repo

import com.roombrowser.data.db.AiTaskDao
import com.roombrowser.data.db.AiTaskEntity
import com.roombrowser.domain.task.AiTaskPermissions
import com.roombrowser.domain.task.AiTaskRunStatus
import com.roombrowser.domain.task.TaskSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * Schedule and permissions travel as JSON in TEXT columns, exactly like
 * profiles' `settings_json`: the domain type owns its own shape, so a new
 * schedule field is a Kotlin change and never a migration.
 *
 * Decoding is deliberately total. A corrupt or forward-written blob must not
 * be able to take down a background worker (where a throw would be an
 * invisible crashloop), so an unreadable value degrades to a safe default and
 * the task keeps running on it.
 */
object AiTaskCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** A daily 09:00 task — a valid schedule for a row whose JSON is unreadable. */
    val DEFAULT_SCHEDULE = TaskSchedule(
        kind = com.roombrowser.domain.task.ScheduleKind.DAILY,
        minuteOfDay = 9 * 60
    )

    fun encodeSchedule(schedule: TaskSchedule): String =
        json.encodeToString(TaskSchedule.serializer(), schedule)

    fun decodeSchedule(raw: String): TaskSchedule =
        runCatching { json.decodeFromString(TaskSchedule.serializer(), raw) }
            .getOrDefault(DEFAULT_SCHEDULE)

    fun encodePermissions(permissions: AiTaskPermissions): String =
        json.encodeToString(AiTaskPermissions.serializer(), permissions)

    fun decodePermissions(raw: String): AiTaskPermissions =
        runCatching { json.decodeFromString(AiTaskPermissions.serializer(), raw) }
            .getOrDefault(AiTaskPermissions.DEFAULT)
}

/** Typed views of an [AiTaskEntity]'s JSON columns. */
val AiTaskEntity.schedule: TaskSchedule get() = AiTaskCodec.decodeSchedule(scheduleJson)

val AiTaskEntity.permissions: AiTaskPermissions get() = AiTaskCodec.decodePermissions(permissionsJson)

/**
 * Scheduled AI tasks. Rows are app-global; the profile a task runs against is
 * a column, not the scope of the list, so one screen manages every task.
 */
class AiTaskRepository(private val dao: AiTaskDao) {

    val tasks: Flow<List<AiTaskEntity>> = dao.observeAll()

    suspend fun all(): List<AiTaskEntity> = dao.all()

    suspend fun get(id: Long): AiTaskEntity? = dao.get(id)

    /** Insert-or-replace. A null [id] creates; otherwise the existing row's
     *  createdAt and last-run bookkeeping are preserved. */
    suspend fun save(
        id: Long?,
        name: String,
        prompt: String,
        profileId: String,
        schedule: TaskSchedule,
        permissions: AiTaskPermissions,
        enabled: Boolean
    ): Long {
        val existing = id?.let { dao.get(it) }
        return dao.upsert(
            AiTaskEntity(
                id = existing?.id ?: 0,
                name = name.trim(),
                prompt = prompt.trim(),
                profileId = profileId,
                scheduleJson = AiTaskCodec.encodeSchedule(schedule),
                permissionsJson = AiTaskCodec.encodePermissions(permissions),
                enabled = enabled,
                lastRunAtMs = existing?.lastRunAtMs,
                lastRunStatus = existing?.lastRunStatus ?: "",
                lastResultSummary = existing?.lastResultSummary,
                createdAt = existing?.createdAt ?: System.currentTimeMillis()
            )
        )
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun recordRun(id: Long, atMs: Long, status: String, summary: String?) =
        dao.recordRun(id, atMs, status, summary)

    /**
     * The occurrences waiting for a process that can run them. DEFERRED IS the
     * queue: the worker records what it could not run there, and ':browser'
     * runs it. A separate queue column could disagree with the run history the
     * list already shows; this cannot.
     */
    fun observeDeferred(): Flow<List<AiTaskEntity>> =
        dao.observeByStatus(AiTaskRunStatus.DEFERRED.name)

    /** The same rows, read once — what the sweep looks at before it waits. */
    suspend fun deferredNow(): List<AiTaskEntity> = dao.byStatus(AiTaskRunStatus.DEFERRED.name)

    suspend fun delete(id: Long) = dao.delete(id)

    /** Profile-deletion cascade. */
    suspend fun deleteAllForProfile(profileId: String) = dao.deleteAllForProfile(profileId)
}
