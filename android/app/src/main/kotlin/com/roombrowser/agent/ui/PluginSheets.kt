@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roombrowser.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.agent.PluginForms
import com.roombrowser.data.db.ChatPluginEntity
import com.roombrowser.data.db.PluginEntity
import com.roombrowser.data.repo.decodePermissions
import com.roombrowser.domain.plugins.PluginPermissions
import com.roombrowser.ui.common.RoomBottomSheetShape
import com.roombrowser.ui.common.RoomSheetHeader

/**
 * The chat's plugin picker — what `/plugins`, the composer's extension button
 * and every fresh chat with plugins installed open.
 *
 * The sheet is a VIEW over controller state, never a holder of it: the rows
 * read the installed plugins and this chat's bindings as they were handed in,
 * the switches are bound to the binding rows (a switch that flipped itself
 * before the enable finished would be promising a grant no one gave), and
 * every tap goes out through [onEnable] — including the one that raises the
 * grant dialog, which the controller owns.
 *
 * Turning a plugin on is per-chat BY DESIGN: the sheet says so in one line,
 * because a reader who cannot see that would read the switch as an app-wide
 * setting and the isolation the whole system is built on would be invisible.
 *
 * A row whose manifest declares an input form also shows the edit pencil —
 * [onEditInputs] opens that form prefilled with this chat's stored answers,
 * because editing is the ONLY way to change them besides a reinstall. The
 * pencil shows on every row with a form, enabled binding or not: editing a
 * plugin this chat has off just saves its answers for the next enable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatPluginsSheet(
    plugins: List<PluginEntity>,
    chatPlugins: List<ChatPluginEntity>,
    onEnable: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onInstallRequest: () -> Unit,
    onManage: () -> Unit,
    onEditInputs: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .semantics { contentDescription = "plugins_sheet" }
        ) {
            RoomSheetHeader("Chat plugins")
            if (plugins.isEmpty()) {
                // The empty sheet still opens on purpose: a person who typed
                // /plugins or tapped the puzzle piece asked a question, and a
                // sheet that answers it — "nothing installed, here is the
                // door" — beats a button that silently does nothing.
                Text(
                    "No plugins installed. Install one from a ZIP.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Each plugin runs only in the chats it is switched on for.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                plugins.forEachIndexed { index, plugin ->
                    if (index > 0) Spacer(Modifier.height(4.dp))
                    PluginRow(
                        plugin = plugin,
                        chatPlugins = chatPlugins,
                        onEnable = onEnable,
                        onEditInputs = onEditInputs
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // The footer's two doors: sideload a ZIP through the system file
            // picker, or go prune what is installed. Wraps rather than clips —
            // the two labels do not share one row on a narrow phone.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OutlinedButton(
                    onClick = onInstallRequest,
                    modifier = Modifier.semantics { contentDescription = "plugin_install_button" }
                ) { Text("Install from ZIP…") }
                TextButton(
                    onClick = onManage,
                    modifier = Modifier.semantics { contentDescription = "plugin_manage_button" }
                ) { Text("Manage plugins") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One installed plugin in the picker: name and version, the permissions it
 * would run with as one honest line (the same sentences the grant dialog
 * consents with, so the sheet is where an upgrade that grew the manifest
 * becomes visible), the chat binding's switch, and — when the manifest
 * declares an input form — the edit pencil for this chat's stored answers.
 *
 * The permission line carries the network hosts inline because that is what
 * [PluginPermissions.labelOf] does for the network grant — the hosts ARE the
 * scope of what is being agreed to, and the row is the first place they
 * should be seen, not merely the second.
 */
@Composable
private fun PluginRow(
    plugin: PluginEntity,
    chatPlugins: List<ChatPluginEntity>,
    onEnable: (String, Boolean) -> Unit,
    onEditInputs: (String) -> Unit
) {
    val hosts = decodePermissions(plugin.networkAllowJson)
    val permissionLine = decodePermissions(plugin.permissionsJson)
        .joinToString(" · ") { PluginPermissions.labelOf(it, hosts) }
    val enabled = chatPlugins.any { it.pluginId == plugin.id && it.enabled }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics { contentDescription = "plugin_row_${plugin.dirName}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plugin.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    plugin.version,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                permissionLine.ifBlank { "No permissions" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        // Only a row whose manifest declares a form can have its answers
        // edited — a plugin without one has nothing to edit, so it never
        // grows a pencil that would open nothing.
        if (PluginForms.schemaOf(plugin).fields.isNotEmpty()) {
            IconButton(
                onClick = { onEditInputs(plugin.id) },
                modifier = Modifier.semantics { contentDescription = "plugin_edit_inputs_${plugin.dirName}" }
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null)
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = { checked -> onEnable(plugin.id, checked) },
            modifier = Modifier.semantics { contentDescription = "plugin_enable_${plugin.dirName}" }
        )
    }
}
