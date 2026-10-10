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
import com.roombrowser.engine.devtools.DevToolsCapability
import com.roombrowser.engine.devtools.EngineSecurityInfo
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomCopyButton
import com.roombrowser.ui.common.SectionHeader

/**
 * The Security panel: the connection, and what the page can say about itself.
 *
 * THE TWO HALVES COME FROM DIFFERENT PLACES AND ARE KEPT APART. The engine owns
 * the transport state -- and on an edition that cannot read a certificate the
 * section says so rather than showing nothing, because the reason differs by
 * engine and only the capability set knows which engine this is. The page owns
 * what it can see from inside, which is a different and sometimes contradictory
 * view: a page served over https whose form posts to http:// is the case this
 * panel exists to surface.
 *
 * One read each, on compose and on demand. Nothing polls.
 */
@Composable
internal fun SecurityPanel(scope: DevToolsPanelScope) {
    val extras = LocalRoomExtras.current
    val session = scope.session
    // Asked of the capability set rather than of the engine's name: "this
    // edition cannot read a certificate" is a claim about what was declared, and
    // reading it from anywhere else would let the two drift.
    val certificatesReadable = scope.capabilities.has(DevToolsCapability.SECURITY_CERTIFICATE)

    var info by remember(session) { mutableStateOf<EngineSecurityInfo?>(null) }
    var probe by remember(session) { mutableStateOf<SecurityProbe?>(null) }
    var reading by remember(session) { mutableStateOf(session != null) }
    var refreshKey by remember(session) { mutableIntStateOf(0) }

    LaunchedEffect(session, refreshKey) {
        val attached = session
        if (attached == null) {
            info = null
            probe = null
            reading = false
            return@LaunchedEffect
        }
        reading = true
        info = attached.inspector.securityInfo()
        probe = attached.securityProbe()
        reading = false
    }

    SectionHeader("Security") {
        RoomCopyButton(
            DeveloperToolsSecurityText.securityReport(info, probe, scope.engineName, certificatesReadable),
            "Security",
            "Copy the whole Security report"
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            readingState(session != null, reading, info, probe),
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { refreshKey++ }, enabled = session != null) {
            Icon(Icons.Filled.Refresh, contentDescription = "Read the connection's state again", tint = extras.icon)
        }
    }

    Block(DeveloperToolsSecurityText.transportText(info), "Transport")
    Block(DeveloperToolsSecurityText.certificateText(info, certificatesReadable), "Certificate")
    Block(DeveloperToolsSecurityText.pageObservableText(probe), "What the page can see")
}

/** What the panel is doing right now, in one line, so an empty panel is never unexplained. */
private fun readingState(
    hasSession: Boolean,
    reading: Boolean,
    info: EngineSecurityInfo?,
    probe: SecurityProbe?
): String = when {
    !hasSession -> "No engine session for this tab yet."
    reading -> "Reading the connection..."
    info == null && probe == null -> "Neither the engine nor the page answered."
    info == null -> "The engine did not report the connection; what the page can see is below."
    probe == null -> "The page did not answer; the engine's view is below."
    else -> "Read from the live connection."
}
