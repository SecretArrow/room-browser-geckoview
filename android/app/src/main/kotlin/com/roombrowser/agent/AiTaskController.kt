package com.roombrowser.agent

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.roombrowser.RoomBrowserApp
import com.roombrowser.data.db.AiTaskEntity
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.task.AiTaskPermissions
import com.roombrowser.domain.task.TaskSchedule
import com.roombrowser.work.AiTaskWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * State holder for the scheduled-tasks screens (default process, no WebView).
 *
 * Every write also (re)schedules the task's single pending delivery, so the
 * screen never has to know when a task next runs — it just observes rows and
 * the worker follows the database.
 */
class AiTaskController(application: Application) {

    private val graph = (application as RoomBrowserApp).graph
    private val repo = graph.aiTaskRepo
    private val appContext = application.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var tasks by mutableStateOf<List<AiTaskEntity>>(emptyList())
        private set
    var profiles by mutableStateOf<List<Profile>>(emptyList())
        private set

    /** The task being edited, once [loadEditing] has resolved. */
    var editing by mutableStateOf<AiTaskEntity?>(null)
        private set

    /** True once [loadEditing] has finished — the editor waits on this so it
     *  never shows empty fields and then overwrites them with the row. */
    var editingLoaded by mutableStateOf(false)
        private set

    fun start() {
        scope.launch { repo.tasks.collect { tasks = it } }
        scope.launch { runCatching { graph.profileRepo.observeProfiles().collect { profiles = it } } }
    }

    fun loadEditing(id: Long) {
        if (id <= 0L) {
            editing = null
            editingLoaded = true
            return
        }
        scope.launch {
            editing = runCatching { repo.get(id) }.getOrNull()
            editingLoaded = true
        }
    }

    fun shutdown() = scope.cancel()

    suspend fun save(
        id: Long?,
        name: String,
        prompt: String,
        profileId: String,
        schedule: TaskSchedule,
        permissions: AiTaskPermissions,
        enabled: Boolean
    ): Long {
        val savedId = repo.save(id, name, prompt, profileId, schedule, permissions, enabled)
        AiTaskWorkScheduler.scheduleNext(appContext, repo.get(savedId))
        return savedId
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        scope.launch {
            repo.setEnabled(id, enabled)
            AiTaskWorkScheduler.scheduleNext(appContext, repo.get(id))
        }
    }

    fun delete(id: Long) {
        scope.launch {
            repo.delete(id)
            AiTaskWorkScheduler.cancel(appContext, id)
        }
    }
}
