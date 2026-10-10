package com.roombrowser.devtools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomCopyButton
import com.roombrowser.ui.common.SectionHeader
import kotlinx.coroutines.launch

/**
 * The Application panel: what this page has put on the device.
 *
 * WHAT IS READ AND WHEN. Everything here comes from one page-side probe that
 * has to be started and then collected -- see [DeveloperToolsStorageScripts]
 * for why the engine cannot await it -- plus one synchronous frame-tree read.
 * The probe runs when the panel is composed and again on demand; nothing polls,
 * so a closed panel costs the page nothing.
 *
 * WHAT IS NOT SENT. The probe carries key names and lengths, never values. A
 * value is fetched one row at a time by tapping that row, and the reveal is
 * local state that no copy control and no report reads. That is what keeps a
 * site's stored tokens out of a paste.
 *
 * A section the page did not answer and a section that is empty are rendered
 * differently on purpose: `(not reported)` and "empty" are different facts and
 * a report has to be able to state which one it found.
 */
@Composable
internal fun ApplicationPanel(scope: DevToolsPanelScope) {
    val extras = LocalRoomExtras.current
    val session = scope.session

    var probe by remember(session) { mutableStateOf<ApplicationProbe?>(null) }
    var frames by remember(session) { mutableStateOf<FrameNode?>(null) }
    var reading by remember(session) { mutableStateOf(session != null) }
    var refreshKey by remember(session) { mutableIntStateOf(0) }

    LaunchedEffect(session, refreshKey) {
        val attached = session
        if (attached == null) {
            probe = null
            frames = null
            reading = false
            return@LaunchedEffect
        }
        reading = true
        probe = attached.applicationProbe()
        frames = attached.frames()
        reading = false
    }

    SectionHeader("Application") {
        // The whole panel, not the sections on screen: the report is the thing
        // a bug report carries, and it states each section's own state.
        RoomCopyButton(
            DeveloperToolsStorageText.applicationReport(probe, frames, scope.engineName),
            "Application",
            "Copy the whole Application report"
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            readingState(session != null, reading, probe),
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { refreshKey++ }, enabled = session != null) {
            Icon(Icons.Filled.Refresh, contentDescription = "Read the page's storage again", tint = extras.icon)
        }
    }

    val answered = probe ?: return
    val losses = answered.failures.orEmpty()
    if (losses.isNotEmpty()) {
        // Not a Block: a section whose whole content is an admission has no
        // useful "copy this section" -- the whole-panel report carries it.
        SectionHeader("Some reads did not finish")
        losses.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 1.dp)
            )
        }
    }

    Block(DeveloperToolsStorageText.estimateText(answered.estimate), "Usage")
    Block(DeveloperToolsStorageText.manifestText(answered.hasManifestLink, answered.manifest), "Manifest")
    Block(DeveloperToolsStorageText.serviceWorkersText(answered.serviceWorkers), "Service workers")
    StorageArea("localStorage", answered.localStorage, session)
    StorageArea("sessionStorage", answered.sessionStorage, session)
    Block(DeveloperToolsStorageText.indexedDbText(answered.databases), "IndexedDB")
    Block(DeveloperToolsStorageText.cacheText(answered.caches), "Cache storage")
    Block(DeveloperToolsStorageText.backgroundText(answered.background, answered.bfcache, answered.reports), "Background services")
    // The frame tree is a pull of its own, so it can be missing while everything
    // else answered; framesText() says which of the two happened.
    Block(DeveloperToolsStorageText.framesText(frames), "Frames")
}

/** One section: its header, a copy control, and the block text both draw. */
@Composable
private fun Block(body: String, title: String) {
    val extras = LocalRoomExtras.current
    SectionHeader(title) {
        // A section writes its own text once, so what is on screen and what a
        // copy carries cannot drift apart.
        RoomCopyButton(body, title, "Copy the $title section")
    }
    Text(
        body,
        style = MaterialTheme.typography.bodySmall,
        color = extras.textPrimary,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp)
    )
}

/**
 * A storage area, with one row per key.
 *
 * The rows are not just the section text again: a key is the unit a reader
 * wants, so each one carries its own copy control and its own reveal. The
 * reveal is the only path by which a value reaches the app at all.
 */
@Composable
private fun StorageArea(area: String, dump: StorageAreaDump?, session: InspectorSession?) {
    val extras = LocalRoomExtras.current
    val keys = dump?.keys.orEmpty()
    SectionHeader(area) {
        RoomCopyButton(
            DeveloperToolsStorageText.storageAreaText(area, dump),
            area,
            "Copy the $area key list"
        )
    }
    when {
        dump == null -> Text(
            "This build could not read $area.",
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
        )
        dump.blocked == true -> Text(
            "The page refused access to $area.",
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
        )
        keys.isEmpty() -> Text(
            if (dump.total == 0) "Empty." else "No keys were reported.",
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
        )
        else -> {
            keys.forEach { entry -> StorageKeyRow(area, entry, session) }
            if (dump.truncated == true) {
                Text(
                    "Listing stops at ${keys.size} of ${dump.total}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.textSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/**
 * One key: its name, the length of its value, a reveal, and a copy.
 *
 * Tapping the row reads the value into THIS composable's state and nowhere
 * else. [revealed] is deliberate: the panel never receives a store's values in
 * bulk, so there is no buffer for a copy control or an export to walk.
 */
@Composable
private fun StorageKeyRow(area: String, entry: StorageKeyEntry, session: InspectorSession?) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()
    var revealed by remember(area, entry.key) { mutableStateOf<String?>(null) }
    var asked by remember(area, entry.key) { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Row(
            Modifier.weight(1f).clickable(enabled = session != null && !asked) {
                asked = true
                scope.launch { revealed = session?.storageValue(area, entry.key) }
            },
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                if (asked) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = if (asked) "Hide this value" else "Reveal this value",
                tint = extras.icon,
                modifier = Modifier.width(24.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    entry.key,
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    entry.length?.let { "$it chars" } ?: "(length not reported)",
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.textSecondary
                )
                if (asked) {
                    Text(
                        revealed ?: "(the value could not be read)",
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        // The key alone, unless it was revealed -- then the pair. Revealing is
        // what makes the value copyable, and only for the row a reader opened.
        RoomCopyButton(
            if (asked && revealed != null) {
                DeveloperToolsStorageText.storageValueText(area, entry.key, revealed)
            } else {
                DeveloperToolsStorageText.storageKeyLine(area, entry)
            },
            "$area key",
            "Copy this $area key"
        )
    }
}

/** What the panel is doing right now, in one line, so an empty panel is never unexplained. */
private fun readingState(hasSession: Boolean, reading: Boolean, probe: ApplicationProbe?): String = when {
    !hasSession -> "No engine session for this tab yet."
    reading -> "Reading the page's storage..."
    probe == null -> "The page did not answer, so nothing here was read."
    !probe.done -> "The page had not finished when reading stopped; what it answered is below."
    else -> "Read from the live page."
}
