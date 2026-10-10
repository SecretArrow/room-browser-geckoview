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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomCopyButton
import com.roombrowser.ui.common.SectionHeader

/**
 * How many rows a panel draws at once.
 *
 * The feed keeps [InspectorSession.CONSOLE_CAP]; this is what a 320dp emulator
 * can put on screen without the whole panel going to jelly. The caption always
 * says which of the two numbers the user is looking at.
 */
private const val RENDER_MAX = 200

private val LEVELS = listOf("error", "warn", "log")

@Composable
internal fun ConsolePanel(session: InspectorSession) {
    val extras = LocalRoomExtras.current

    // Reading the revision is what subscribes this panel to the feed: the sink
    // bumps it on the main thread, so there is no timer and no polling.
    val revision = session.consoleRevision
    val entries = remember(revision) { session.console.snapshot() }
    var level by remember(session) { mutableStateOf<String?>(null) }

    DisposableEffect(session) {
        session.startConsole()
        onDispose { session.stopConsole() }
    }

    val shown = remember(entries, level) {
        val matching = if (level == null) entries else entries.filter { it.level == level }
        matching.takeLast(RENDER_MAX)
    }

    SectionHeader("Console") {
        RoomCopyButton(consoleText(shown), "Console", "Copy these console entries")
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            feedCaption(
                shown = shown.size,
                kept = entries.size,
                cap = session.console.capacity,
                dropped = session.console.dropped
            ),
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { session.console.clear() }) {
            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear the console feed", tint = extras.icon)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        LevelChooser(level) { level = it }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (entries.isEmpty()) {
            Text(
                "Nothing logged yet. This feed keeps the page's own console output and, " +
                    "where the engine offers it, the engine's own messages.",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary
            )
        }
        shown.forEach { entry -> ConsoleRow(entry) }
    }
}

@Composable
private fun LevelChooser(level: String?, onLevel: (String?) -> Unit) {
    val extras = LocalRoomExtras.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (listOf<String?>(null) + LEVELS).forEach { option ->
            val selected = option == level
            Text(
                option ?: "all",
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) extras.onButton else extras.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) extras.primary else extras.surfaceAlt)
                    .clickable { onLevel(option) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ConsoleRow(entry: ConsoleEntry) {
    val extras = LocalRoomExtras.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            entry.level,
            style = MaterialTheme.typography.labelSmall,
            color = levelColor(entry.level),
            modifier = Modifier.width(44.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                entry.text,
                style = MaterialTheme.typography.bodySmall,
                color = extras.textPrimary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            val where = listOfNotNull(
                entry.source,
                entry.line?.takeIf { it > 0 }?.toString(),
                if (entry.fromEngine) "engine" else null
            )
            if (where.isNotEmpty()) {
                Text(
                    where.joinToString(":"),
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun levelColor(level: String): Color {
    val extras = LocalRoomExtras.current
    return when (level) {
        "error", "assert" -> MaterialTheme.colorScheme.error
        "warn" -> extras.secondary
        else -> extras.textSecondary
    }
}

/** The one caption every bounded feed shows, so the cap and any loss are never a surprise. */
internal fun feedCaption(shown: Int, kept: Int, cap: Int, dropped: Int): String {
    val head = "$shown of $kept · $cap max"
    return if (dropped > 0) "$head · $dropped older dropped" else head
}
