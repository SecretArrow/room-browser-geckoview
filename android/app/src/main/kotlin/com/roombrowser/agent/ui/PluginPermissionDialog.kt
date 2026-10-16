package com.roombrowser.agent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roombrowser.agent.PendingPluginGrant
import com.roombrowser.domain.plugins.PluginFormat
import com.roombrowser.domain.plugins.PluginPermissions
import kotlinx.serialization.json.JsonObject

/**
 * The enable-time consent for one plugin: the permissions it asks for, in
 * words, before any of them is granted — and, when the plugin's manifest
 * declares an `inputs` form, the SAME dialog extended into that form (one
 * dialog, not two: the person approves the plugin and answers it in one
 * look, and "Allow and Run" cannot be pressed until the form validates).
 *
 * THE ANSWER IS WHOLE, not itemized. There are no per-permission checkboxes
 * on purpose — the manifest's set is what the plugin's code was written
 * against, and a "half grant" the author never tested is a state nobody
 * chose. Both buttons say exactly what they do: "Grant & enable" (native
 * format) and "Allow and Run" (module format) grant the declared set and
 * store the form's answers; Cancel enables nothing, as does closing the
 * dialog (dismissing a consent must never read as consent).
 *
 * [onAccept] is handed the granted permission NAMES — [PluginUiState] stores
 * names — straight off [PendingPluginGrant.permissionNames], because mapping
 * the bullet sentences back through [PluginPermissions.labelOf] cannot
 * recover the module-only `files` permission and a module declaring it would
 * silently lose its grant at consent. The label→name mapping survives below
 * only as the fallback for a grant that arrived without names, a shape the
 * state's own raiser never produces; the controller filters whatever arrives
 * against the manifest anyway, so a list that somehow misses is inert rather
 * than widening.
 *
 * The form half is the same editor, validator and live re-validation the
 * standalone form dialog renders (see [PluginFormFields]); an INVALID set
 * keeps the accept button disabled, and the state's own validator stays the
 * backstop behind it. A plugin that declares no inputs gets exactly the
 * dialog this screen has always drawn — no divider, no fields, the accept
 * description on the button itself.
 */
@Composable
fun PluginPermissionDialog(
    pending: PendingPluginGrant,
    onAccept: (granted: List<String>, inputsValues: JsonObject?) -> Unit,
    onCancel: () -> Unit
) {
    // Labels back to the permission names they were consented with — only
    // when the grant arrived without its own names (see the KDoc above for
    // why the names, not this mapping, are the real path).
    val granted = pending.permissionNames.ifEmpty {
        pending.permissionLabels.mapNotNull { label ->
            PluginPermissions.PERMISSIONS.firstOrNull { permission ->
                PluginPermissions.labelOf(permission, pending.networkHosts) == label
            }
        }
    }
    val hasForm = pending.fields.isNotEmpty()
    val drafts = rememberPluginFormDrafts(pending.fields, pending.existingValues)
    val values = pluginFormValuesOf(pending.fields, drafts)
    // Live on every keystroke: the drafts are state, reading them here is
    // the subscription, and an empty map is the only thing that enables the
    // accept button while the form half is up.
    val errors = pluginFormErrorsOf(pending.fields, drafts, pending.existingValues)
    val zagents = pending.format == PluginFormat.ZAGENTS
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(if (zagents) "Allow \"${pending.pluginName}\" to:" else "Enable ${pending.pluginName}?")
        },
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
                if (hasForm) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    PluginFormFields(
                        fields = pending.fields,
                        drafts = drafts,
                        existingValues = pending.existingValues,
                        errors = errors
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
                onClick = { onAccept(granted, if (hasForm) values else null) },
                enabled = !hasForm || errors.isEmpty(),
                modifier = Modifier.semantics {
                    // With a form half, the button's own description is the
                    // form accept and the label text node below carries the
                    // permission accept the existing e2e clicks — never both
                    // on one node, or an exact By.desc match would miss one.
                    // A plain dialog keeps today's shape: the description on
                    // the button itself.
                    contentDescription = if (hasForm) "plugin_form_accept" else "plugin_permission_accept"
                }
            ) {
                Text(
                    if (zagents) "Allow and Run" else "Grant & enable",
                    modifier = if (hasForm) {
                        Modifier.semantics { contentDescription = "plugin_permission_accept" }
                    } else {
                        Modifier
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}
