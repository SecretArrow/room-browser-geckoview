package com.roombrowser.devtools

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomCopyButton
import com.roombrowser.ui.common.SectionHeader

/**
 * The Audit panel: what the page can tell about itself, with no score on it.
 *
 * ONE PULL, NO POLL. Everything the audit reads is already in the document when
 * the panel opens, so a refresh is a re-read rather than a wait, and the panel
 * has no "still measuring" state to get stuck in.
 *
 * THE CAPTION IS PART OF THE PANEL, not a footnote -- it heads the report so a
 * reader who copies the whole thing carries the caveat with it. See
 * [DeveloperToolsAuditText.CAPTION] for why it is a constant.
 */
@Composable
internal fun AuditPanel(scope: DevToolsPanelScope) {
    val extras = LocalRoomExtras.current
    val session = scope.session

    var probe by remember(session) { mutableStateOf<AuditProbe?>(null) }
    var reading by remember(session) { mutableStateOf(session != null) }
    var refreshKey by remember(session) { mutableIntStateOf(0) }

    LaunchedEffect(session, refreshKey) {
        val attached = session
        if (attached == null) {
            probe = null
            reading = false
            return@LaunchedEffect
        }
        reading = true
        probe = attached.auditProbe()
        reading = false
    }

    val report = DeveloperToolsAuditText.auditReport(probe, scope.engineName)

    SectionHeader("Audit") {
        RoomCopyButton(report, "Audit", "Copy the whole Audit report")
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            auditState(session != null, reading, probe),
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { refreshKey++ }, enabled = session != null) {
            Icon(Icons.Filled.Refresh, contentDescription = "Read the page again", tint = extras.icon)
        }
    }

    Block(report, "Report")
}

/** What the panel is doing right now, so an empty report is never unexplained. */
private fun auditState(hasSession: Boolean, reading: Boolean, probe: AuditProbe?): String = when {
    !hasSession -> "No engine session for this tab yet."
    reading -> "Reading the page..."
    probe == null -> "The page did not answer."
    else -> DeveloperToolsAuditText.CAPTION
}
