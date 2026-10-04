package com.roombrowser.data.repo

import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AiTaskDao
import com.roombrowser.data.db.AiTaskEntity
import com.roombrowser.domain.task.AiTaskPermissions
import com.roombrowser.domain.task.ScheduleKind
import com.roombrowser.domain.task.TaskSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The repository's mapping and bookkeeping, against a map-backed DAO — no Room,
 * no Android.
 *
 * The property that matters is that editing a task does NOT reset what the
 * scheduler depends on: `createdAt` and the last-run stamp must survive a save
 * of an unrelated field, or editing a prompt would re-owe every missed
 * occurrence and re-run the task on the spot.
 */
class AiTaskRepositoryTest {

    private class FakeDao : AiTaskDao {
        val rows = linkedMapOf<Long, AiTaskEntity>()
        private var nextId = 1L

        override fun observeAll(): Flow<List<AiTaskEntity>> =
            flowOf(rows.values.sortedByDescending { it.createdAt })

        override suspend fun all(): List<AiTaskEntity> = rows.values.sortedByDescending { it.createdAt }

        override suspend fun get(id: Long): AiTaskEntity? = rows[id]

        override suspend fun upsert(entity: AiTaskEntity): Long {
            val id = if (entity.id == 0L) nextId++ else entity.id
            rows[id] = entity.copy(id = id)
            return id
        }

        override suspend fun setEnabled(id: Long, enabled: Boolean) {
            rows[id]?.let { rows[id] = it.copy(enabled = enabled) }
        }

        override suspend fun recordRun(id: Long, atMs: Long, status: String, summary: String?) {
            rows[id]?.let {
                rows[id] = it.copy(lastRunAtMs = atMs, lastRunStatus = status, lastResultSummary = summary)
            }
        }

        override suspend fun delete(id: Long) {
            rows.remove(id)
        }

        override suspend fun deleteAllForProfile(profileId: String) {
            rows.entries.removeAll { it.value.profileId == profileId }
        }
    }

    private fun schedule(kind: ScheduleKind = ScheduleKind.DAILY) =
        TaskSchedule(kind = kind, minuteOfDay = 9 * 60)

    @Test
    fun creating_a_task_assigns_an_id_and_trims_its_text() = runBlocking<Unit> {
        val repo = AiTaskRepository(FakeDao())
        val id = repo.save(
            id = null,
            name = "  Morning news  ",
            prompt = "  summarize  ",
            profileId = "p1",
            schedule = schedule(),
            permissions = AiTaskPermissions.DEFAULT,
            enabled = true
        )
        val saved = repo.get(id)!!
        assertThat(saved.name).isEqualTo("Morning news")
        assertThat(saved.prompt).isEqualTo("summarize")
        assertThat(saved.createdAt).isGreaterThan(0L)
        assertThat(saved.lastRunAtMs).isNull()
    }

    @Test
    fun editing_preserves_the_created_at_and_the_last_run_bookkeeping() = runBlocking<Unit> {
        val repo = AiTaskRepository(FakeDao())
        val id = repo.save(
            null, "old", "old prompt", "p1", schedule(), AiTaskPermissions.DEFAULT, true
        )
        repo.recordRun(id, atMs = 1_000L, status = "DEFERRED", summary = "not now")
        val originalCreatedAt = repo.get(id)!!.createdAt

        repo.save(
            id = id,
            name = "new",
            prompt = "new prompt",
            profileId = "p2",
            schedule = schedule(ScheduleKind.INTERVAL),
            permissions = AiTaskPermissions.DEFAULT.copy(allowPost = true),
            enabled = false
        )

        val edited = repo.get(id)!!
        assertThat(edited.name).isEqualTo("new")
        assertThat(edited.profileId).isEqualTo("p2")
        assertThat(edited.schedule.kind).isEqualTo(ScheduleKind.INTERVAL)
        assertThat(edited.permissions.allowPost).isTrue()
        assertThat(edited.enabled).isFalse()
        // The scheduler's inputs are untouched by an unrelated edit.
        assertThat(edited.createdAt).isEqualTo(originalCreatedAt)
        assertThat(edited.lastRunAtMs).isEqualTo(1_000L)
        assertThat(edited.lastRunStatus).isEqualTo("DEFERRED")
        assertThat(edited.lastResultSummary).isEqualTo("not now")
    }

    @Test
    fun record_run_writes_the_stamp_status_and_summary_together() = runBlocking<Unit> {
        val repo = AiTaskRepository(FakeDao())
        val id = repo.save(
            null, "t", "p", "p1", schedule(), AiTaskPermissions.DEFAULT, true
        )
        repo.recordRun(id, atMs = 42L, status = "COMPLETED", summary = "done")

        val row = repo.get(id)!!
        assertThat(row.lastRunAtMs).isEqualTo(42L)
        assertThat(row.lastRunStatus).isEqualTo("COMPLETED")
        assertThat(row.lastResultSummary).isEqualTo("done")
    }

    @Test
    fun toggling_enabled_does_not_disturb_the_schedule_or_the_last_run() = runBlocking<Unit> {
        val repo = AiTaskRepository(FakeDao())
        val id = repo.save(
            null, "t", "p", "p1", schedule(), AiTaskPermissions.DEFAULT, true
        )
        repo.recordRun(id, 7L, "FAILED", "boom")
        repo.setEnabled(id, false)

        val row = repo.get(id)!!
        assertThat(row.enabled).isFalse()
        assertThat(row.lastRunAtMs).isEqualTo(7L)
        assertThat(row.schedule).isEqualTo(schedule())
    }

    @Test
    fun permissions_persist_as_json_that_decodes_back_to_the_same_grants() = runBlocking<Unit> {
        val repo = AiTaskRepository(FakeDao())
        val permissions = AiTaskPermissions(
            allowReadPage = true,
            allowNavigate = false,
            allowInteract = true,
            allowPost = false
        )
        val id = repo.save(null, "t", "p", "p1", schedule(), permissions, true)
        assertThat(repo.get(id)!!.permissions).isEqualTo(permissions)
    }

    @Test
    fun deleting_a_profile_removes_only_that_profiles_tasks() = runBlocking<Unit> {
        val repo = AiTaskRepository(FakeDao())
        repo.save(null, "a", "p", "p1", schedule(), AiTaskPermissions.DEFAULT, true)
        repo.save(null, "b", "p", "p2", schedule(), AiTaskPermissions.DEFAULT, true)

        repo.deleteAllForProfile("p1")

        assertThat(repo.all().map { it.name }).containsExactly("b")
    }

    @Test
    fun deleting_a_task_removes_it() = runBlocking<Unit> {
        val repo = AiTaskRepository(FakeDao())
        val id = repo.save(null, "a", "p", "p1", schedule(), AiTaskPermissions.DEFAULT, true)
        repo.delete(id)
        assertThat(repo.all()).isEmpty()
    }
}
