@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roombrowser.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.agent.AiTaskController
import com.roombrowser.data.db.AiTaskEntity
import com.roombrowser.data.repo.schedule
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.SectionHeader
import com.roombrowser.ui.common.SettingsGroup
import java.time.ZoneId

/**
 * The scheduled-AI-task list — its OWN activity in the default process, like
 * the other agent screens. Create/edit lives in [AiTaskEditorActivity].
 */
class AiTasksActivity : ComponentActivity() {

    private lateinit var controller: AiTaskController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = AiTaskController(application)
        controller.start()
        setContent {
            RoomBrowserTheme {
                AiTasksRoot(
                    controller = controller,
                    onAdd = { launchEditor(null) },
                    onEdit = { launchEditor(it.id) },
                    onClose = { finish() }
                )
            }
        }
    }

    private fun launchEditor(id: Long?) {
        val intent = Intent(this, AiTaskEditorActivity::class.java)
        if (id != null) intent.putExtra(AiTaskEditorActivity.EXTRA_TASK_ID, id)
        startActivity(intent)
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, AiTasksActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}

@Composable
private fun AiTasksRoot(
    controller: AiTaskController,
    onAdd: () -> Unit,
    onEdit: (AiTaskEntity) -> Unit,
    onClose: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf<AiTaskEntity?>(null) }
    val zone = ZoneId.systemDefault()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("AI tasks") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("Scheduled tasks")
            if (controller.tasks.isEmpty()) {
                EmptyState(
                    title = "No AI tasks yet",
                    subtitle = "Create a task to have the agent run a prompt on a schedule " +
                        "against one of your profiles."
                )
            }
            SettingsGroup {
                controller.tasks.forEach { task ->
                    TaskRow(
                        task = task,
                        profiles = controller.profiles,
                        zone = zone,
                        onEdit = { onEdit(task) },
                        onDelete = { confirmDelete = task },
                        onEnabled = { controller.setEnabled(task.id, it) }
                    )
                }
            }
            Button(
                onClick = onAdd,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .semantics { contentDescription = "add_ai_task" }
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add task")
            }
            Spacer(Modifier.height(48.dp))
        }
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${target.name}?") },
            text = { Text("This removes the task and cancels its pending run.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    controller.delete(target.id)
                }) { Text("Delete task") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TaskRow(
    task: AiTaskEntity,
    profiles: List<com.roombrowser.domain.model.Profile>,
    zone: ZoneId,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onEnabled: (Boolean) -> Unit
) {
    val schedule = task.schedule
    val now = System.currentTimeMillis()
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { contentDescription = "ai_task_${task.id}" }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    task.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    scheduleSummary(schedule),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    profileLabel(profiles, task.profileId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = task.enabled,
                onCheckedChange = onEnabled,
                modifier = Modifier.semantics {
                    contentDescription = "ai_task_enabled_${task.id}"
                }
            )
            IconButton(
                onClick = onEdit,
                modifier = Modifier.semantics { contentDescription = "edit_ai_task_${task.id}" }
            ) { Icon(Icons.Filled.Edit, contentDescription = null) }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.semantics { contentDescription = "delete_ai_task_${task.id}" }
            ) { Icon(Icons.Filled.Delete, contentDescription = null) }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            nextRunText(schedule, now, zone),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (scheduleIsClamped(schedule)) {
            Text(
                "Android runs background work at most every 15 minutes, so this task runs " +
                    "every 15 minutes rather than the interval requested.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        lastRunText(task, zone)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The profile name for a task's id, or the id itself when the profile is gone
 * (a task row can outlive a profile only in a database edited outside the UI).
 */
private fun profileLabel(profiles: List<com.roombrowser.domain.model.Profile>, profileId: String): String {
    val name = profiles.firstOrNull { it.id.value == profileId }?.name
    return "Profile: ${name ?: profileId}"
}
