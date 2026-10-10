package com.roombrowser.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import kotlinx.coroutines.launch

private const val RENDER_MAX = 200

@Composable
internal fun NetworkPanel(session: InspectorSession) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()

    val revision = session.networkRevision
    val entries = remember(revision) { session.network.snapshot() }
    val options = remember(entries) { kindOptions(entries) }
    var filter by remember(session) { mutableStateOf(KIND_ALL) }
    var pullState by remember(session) { mutableStateOf<PullState>(PullState.Idle) }

    DisposableEffect(session) {
        session.startNetwork()
        onDispose { session.stopNetwork() }
    }

    val shown = remember(entries, filter, options) {
        val active = if (filter in options) filter else KIND_ALL
        val matching = when (active) {
            KIND_ALL -> entries
            KIND_PAGE -> entries.filter { it.kind == RESOURCE_KIND }
            else -> entries.filter { it.kind == active }
        }
        matching.takeLast(RENDER_MAX)
    }

    SectionHeader("Network") {
        RoomCopyButton(networkText(shown), "Network", "Copy these network rows")
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            feedCaption(
                shown = shown.size,
                kept = entries.size,
                cap = session.network.capacity,
                dropped = session.network.dropped
            ),
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { session.network.clear() }) {
            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear the network feed", tint = extras.icon)
        }
        IconButton(
            onClick = {
                pullState = PullState.Running
                scope.launch {
                    val timings = session.resourceTimings()
                    if (timings == null) {
                        pullState = PullState.Failed
                    } else {
                        session.addResourceTimings(timings)
                        pullState = PullState.Done(timings.size)
                    }
                }
            }
        ) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Ask the page what it loaded",
                tint = extras.icon
            )
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        KindChooser(options, if (filter in options) filter else KIND_ALL) { filter = it }
    }

    Text(
        "Request lines come from the engine, which sees them even when they fail. " +
            "Sizes and durations come from the page's own timing data, so they cover only " +
            "what the page loaded and only after you ask for them." +
            if (entries.any { it.documentUrl != null }) {
                " Each row here names the document it belongs to, because this engine's " +
                    "capture is not limited to the tab in front."
            } else {
                ""
            },
        style = MaterialTheme.typography.labelSmall,
        color = extras.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    PullStateLine(pullState)

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (entries.isEmpty() && pullState is PullState.Idle) {
            Text(
                "Nothing recorded yet. Load something, or tap the refresh icon to ask the " +
                    "page for the timing of what it has already loaded.",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary
            )
        }
        shown.forEach { entry -> NetworkRow(entry) }
    }
}

/** What the last page-timing pull did, so a failed one never reads as "the page loaded nothing". */
private sealed interface PullState {
    data object Idle : PullState
    data object Running : PullState
    data object Failed : PullState
    data class Done(val count: Int) : PullState
}

@Composable
private fun PullStateLine(state: PullState) {
    val extras = LocalRoomExtras.current
    val text = when (state) {
        PullState.Idle -> return
        PullState.Running -> "Asking the page what it loaded…"
        PullState.Failed -> "${noReadingLine()} The page's own timing data was not read, so " +
            "any rows below are what the engine saw only."
        is PullState.Done -> if (state.count == 0) {
            "The page reported no resource timings yet."
        } else {
            "The page reported ${state.count} resource timing${if (state.count == 1) "" else "s"}."
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = extras.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

@Composable
private fun KindChooser(options: List<String>, selected: String, onKind: (String) -> Unit) {
    val extras = LocalRoomExtras.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            Text(
                option,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) extras.onButton else extras.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) extras.primary else extras.surfaceAlt)
                    .clickable { onKind(option) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun NetworkRow(entry: NetworkEntry) {
    val extras = LocalRoomExtras.current
    val size = when {
        entry.sizesHidden -> "size hidden (no Timing-Allow-Origin)"
        entry.transferSize != null -> formatBytes(entry.transferSize)
        else -> null
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.kind,
                style = MaterialTheme.typography.labelSmall,
                color = kindColor(entry.kind),
                modifier = Modifier.width(64.dp)
            )
            Text(
                entry.status?.toString() ?: "no status",
                style = MaterialTheme.typography.labelSmall,
                color = extras.textSecondary,
                modifier = Modifier.width(56.dp)
            )
            Text(
                entry.url,
                style = MaterialTheme.typography.bodySmall,
                color = extras.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            RoomCopyButton(entry.url, "URL", "Copy this request URL")
        }
        val facts = listOfNotNull(
            entry.method,
            entry.resourceType,
            size,
            entry.durationMs?.let { "${compactNumber(kotlin.math.round(it))} ms" },
            entry.note,
            entry.isForMainFrame?.let { if (it) "main frame" else null },
            entry.documentUrl?.let(::urlHost)?.let { "in $it" }
        )
        if (facts.isNotEmpty()) {
            Text(
                facts.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = extras.textSecondary,
                modifier = Modifier.padding(start = 120.dp)
            )
        }
        (entry.requestHeaders + entry.responseHeaders).forEach { header ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${header.name}: ${header.value}",
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 120.dp)
                )
                RoomCopyButton("${header.name}: ${header.value}", header.name, "Copy this header")
            }
        }
    }
}

@Composable
private fun kindColor(kind: String) = when (kind) {
    "FAILED" -> MaterialTheme.colorScheme.error
    "RESPONSE" -> LocalRoomExtras.current.primary
    else -> LocalRoomExtras.current.textSecondary
}
