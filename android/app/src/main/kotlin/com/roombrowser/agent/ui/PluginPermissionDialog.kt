package com.roombrowser.agent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roombrowser.agent.PendingPluginGrant
import com.roombrowser.domain.plugins.PluginPermissions

/**
 * The enable-time consent for one plugin: the permissions it asks for, in
 * words, before any of them is granted.
 *
 * THE ANSWER IS WHOLE, not itemized. There are no per-permission checkboxes
 * on purpose — the manifest's set is what the plugin's code was written
 * against, and a "half grant" the author never tested is a state nobody
 * chose. Both buttons say exactly what they do: "Grant & enable" grants the
 * declared set, and Cancel enables nothing, as does closing the dialog
 * (dismissing a consent must never read as consent).
 *
 * [onAccept] is handed the granted permission NAMES — [PluginUiState] stores
 * names, while [PendingPluginGrant] carries only the human sentences. The
 * sentences are recovered through [PluginPermissions.labelOf] itself, which
 * is deterministic in (permission, hosts) and the exact function that
 * produced the sentences; the controller filters whatever arrives against
 * the manifest anyway, so a list that somehow misses is inert rather than
 * widening.
 */
@Composable
fun PluginPermissionDialog(
    pending: PendingPluginGrant,
    onAccept: (List<String>) -> Unit,
    onCancel: () -> Unit
) {
    // Labels back to the permission names they were consented with.
    val granted = pending.permissionLabels.mapNotNull { label ->
        PluginPermissions.PERMISSIONS.firstOrNull { permission ->
            PluginPermissions.labelOf(permission, pending.networkHosts) == label
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Enable ${pending.pluginName}?") },
        text = {
            // Material3 gives this slot no scrolling of its own and clips an
            // overlong body — the same reason and the same cap as the
            // approval dialog in AgentPanel.
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                pending.permissionLabels.forEach { label ->
                    Text("• $label", style = MaterialTheme.typography.bodyMedium)
                }
                // The hosts are repeated deliberately: the network label
                // already carries them, but they are the actual scope of what
                // is being agreed to, so they also get their own line where a
                // long allowlist wraps instead of being ellipsized away.
                if (pending.networkHosts.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Network access is limited to: " + pending.networkHosts.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "The plugin runs only in this chat, never in other chats, and it can be " +
                        "switched off here at any time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Closing this dialog or choosing Cancel enables nothing — no permission " +
                        "is granted.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAccept(granted) },
                modifier = Modifier.semantics { contentDescription = "plugin_permission_accept" }
            ) { Text("Grant & enable") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}
