package com.roombrowser.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomCopyButton
import com.roombrowser.ui.common.SectionHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val RENDER_MAX = 200

/**
 * The Recorder: page-side listeners written into steps that carry a selector
 * and a timestamp, exported as a Room agent task.
 *
 * The capture scripts are the app's own (see [DeveloperToolsRecorderScripts]),
 * never the agent's `run_js`. Replay hands the exported task to the agent
 * through the scope's `onRunAgentTask`, so it leaves down the execution path the
 * agent already has -- there is no second way to drive a page from here, and no
 * Puppeteer or Node export, because this app has no Node runtime to run one.
 */
@Composable
internal fun RecorderPanel(scope: DevToolsPanelScope) {
    val session = scope.session ?: return
    RecorderBody(session, scope.tabUrl, scope.onRunAgentTask)
}

@Composable
private fun RecorderBody(
    session: InspectorSession,
    tabUrl: String,
    onRunAgentTask: (String) -> Unit
) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()

    var recording by remember(session) { mutableStateOf(false) }
    var steps by remember(session) { mutableStateOf<List<RecorderStep>>(emptyList()) }
    var dropped by remember(session) { mutableStateOf(0) }
    var status by remember(session) { mutableStateOf<String?>(null) }

    fun apply(snapshot: RecorderSnapshot?) {
        if (snapshot == null) {
            status = noReadingLine()
            return
        }
        recording = snapshot.active
        steps = snapshot.steps
        dropped = snapshot.dropped
    }

    LaunchedEffect(session, tabUrl) {
        // A recording has to survive a full page load: the listeners die with
        // the document, so they are reinstalled whenever the tab's URL changes
        // and the buffer the previous document wrote is read back.
        if (recording) session.rawEval(DeveloperToolsRecorderScripts.startJs())
        apply(session.rawEval(DeveloperToolsRecorderScripts.pullJs())?.let(::decodeRecorderSnapshot))
    }

    DisposableEffect(session) {
        onDispose {
            // Leaving the panel must not leave a recorder installed in someone's
            // page. The call is asynchronous, and a scope tied to this
            // composition is already cancelled here, so it goes out on its own.
            CoroutineScope(Dispatchers.Main).launch {
                session.rawEval(DeveloperToolsRecorderScripts.uninstallJs())
            }
        }
    }

    val shown = remember(steps) { steps.takeLast(RENDER_MAX) }
    val base = steps.firstOrNull()?.timestampMs ?: 0L
    val caption = feedCaption(shown.size, steps.size, DeveloperToolsRecorderScripts.CAP, dropped)
    val export = remember(steps, tabUrl) {
        recorderExport(steps, recorderTaskName(tabUrl), tabUrl.ifBlank { null })
    }

    SectionHeader("Recorder") {
        RoomCopyButton(
            recorderReport(steps, DeveloperToolsRecorderScripts.CAP, dropped),
            "Recorder",
            "Copy these recorded steps"
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary,
            modifier = Modifier.weight(1f)
        )
        RoomCopyButton(caption, "Recorder caption", "Copy the recorder counts")
        IconButton(onClick = {
            scope.launch {
                apply(session.rawEval(DeveloperToolsRecorderScripts.pullJs())?.let(::decodeRecorderSnapshot))
            }
        }) {
            Icon(Icons.Filled.Refresh, contentDescription = "Read the page buffer", tint = extras.icon)
        }
        IconButton(onClick = {
            scope.launch {
                apply(session.rawEval(DeveloperToolsRecorderScripts.clearJs())?.let(::decodeRecorderSnapshot))
            }
        }) {
            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear the recording", tint = extras.icon)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RecorderChip(if (recording) "Stop" else "Record", selected = recording) {
            scope.launch {
                val script = if (recording) {
                    DeveloperToolsRecorderScripts.stopJs()
                } else {
                    DeveloperToolsRecorderScripts.startJs()
                }
                apply(session.rawEval(script)?.let(::decodeRecorderSnapshot))
                status = if (recording) {
                    "Recording. Page events are captured until you stop."
                } else {
                    "Stopped. The steps below are kept until the panel is cleared."
                }
            }
        }
        Text(
            recorderSelectorTally(steps),
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary
        )
    }

    status?.let { line ->
        Text(
            line,
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (steps.isEmpty()) {
            Text(
                "Nothing recorded yet. Tap Record, use the page, then Stop. Steps are held " +
                    "in this site's session storage while recording and removed when you stop.",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary
            )
        }
        shown.forEach { step -> RecorderRow(step, step.timestampMs - base) }
    }

    RecorderExportBlock(export, onRunAgentTask)
    RecorderFootnotes()
}

@Composable
private fun RecorderRow(step: RecorderStep, offsetMs: Long) {
    val extras = LocalRoomExtras.current
    val line = remember(step, offsetMs) { recorderStepLine(step, offsetMs) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = extras.textPrimary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        RoomCopyButton(line, "Recorder step", "Copy this recorded step")
    }
}

@Composable
private fun RecorderExportBlock(export: RecorderExport, onRunAgentTask: (String) -> Unit) {
    val extras = LocalRoomExtras.current
    when (export) {
        is RecorderExport.Task -> {
            Text(
                "Task \"${export.name}\" — ${export.toolCalls.size} agent step" +
                    (if (export.toolCalls.size == 1) "" else "s"),
                style = MaterialTheme.typography.labelMedium,
                color = extras.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RecorderChip("Replay in the agent", selected = false) { onRunAgentTask(export.prompt) }
                RoomCopyButton(export.prompt, "Task prompt", "Copy the task instruction")
                RoomCopyButton(export.json, "Task JSON", "Copy the task as JSON")
            }
            Text(
                export.prompt,
                style = MaterialTheme.typography.labelSmall,
                color = extras.textSecondary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        is RecorderExport.Refused -> {
            Text(
                "No task can be built from this recording:",
                style = MaterialTheme.typography.labelMedium,
                color = extras.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            export.reasons.forEach { reason ->
                Text(
                    "· $reason",
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun RecorderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val extras = LocalRoomExtras.current
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) extras.onButton else extras.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) extras.primary else extras.surfaceAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/**
 * The Recorder's own honesty line, and it is last on purpose.
 *
 * What it can and cannot capture is the thing a reader needs before trusting a
 * replay, and a caption placed above the rows pushes every row off the bottom
 * of a phone-sized dock.
 */
@Composable
private fun RecorderFootnotes() {
    val extras = LocalRoomExtras.current
    Text(
        "Steps are captured by the app's own listeners in the page: a click, a value, " +
            "and navigation within this site. A recording does not follow you to another " +
            "site. Replay runs through the agent, so the agent's own confirmations apply.",
        style = MaterialTheme.typography.labelSmall,
        color = extras.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}
