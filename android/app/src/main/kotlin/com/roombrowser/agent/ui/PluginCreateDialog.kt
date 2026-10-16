package com.roombrowser.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.roombrowser.agent.PendingPluginCreation
import com.roombrowser.domain.plugins.PluginManifestValidator
import com.roombrowser.domain.plugins.PluginPermissions

/**
 * The Allow-and-Create dialog — the approval form `/make_plugins` raises, and
 * the review the whole feature turns on: the person sees the draft's identity
 * (id, version, declared commands — read-only, because a review that could
 * re-point `entry` or re-spell the id would be a code review this form cannot
 * honestly perform), edits its name and description, checks the permissions
 * it will actually install (pre-checked to the draft's asks, unchecking
 * always allowed, the least-privilege direction), reviews the network hosts
 * in an editable field, and reads the FULL code the chat's AI wrote before
 * anything installs.
 *
 * PERMISSIONS AND HOSTS COME ONLY FROM THIS FORM. The draft's asks are
 * displayed and pre-fill it; what installs is the REWRITTEN manifest
 * ([PendingPluginCreation.candidate]) — the model's asks are never installed.
 *
 * THE ACCEPT BUTTON IS DISABLED UNTIL THE REWRITTEN MANIFEST PASSES THE
 * INSTALL VALIDATOR — the same [PluginManifestValidator], the same rule as
 * every Allow-and-Run gate in the plugin system: one answer app-wide.
 * Recomputed on every keystroke and toggle (a keystroke IS a
 * recomposition), so a blank name, an over-long description, or network
 * checked with an empty or invalid hosts list all keep it disabled — with
 * the validator's own network sentences shown inline under the hosts field,
 * so the refusal says what to fix. [com.roombrowser.agent.PluginUiState.submitCreation]
 * re-runs the same answer as its backstop.
 *
 * DISMISSING IS NEVER ANSWERING — Cancel and the dialog's own dismiss
 * request both call [onCancel], the same convention the form dialogs hold.
 */
@Composable
fun PluginCreateDialog(
    pending: PendingPluginCreation,
    onSubmit: (name: String, description: String, permissions: List<String>, hosts: List<String>) -> Unit,
    onCancel: () -> Unit
) {
    // The drafts are keyed to the pending they were prefilled from: a NEWER
    // draft (a second /make_plugins while one was up) renders its own
    // pre-fill, never a stale local copy — the same remember-keying rule the
    // form dialogs follow.
    var name by remember(pending) { mutableStateOf(pending.displayName) }
    var description by remember(pending) { mutableStateOf(pending.declaredDescription) }
    var checked by remember(pending) { mutableStateOf(pending.declaredPermissions.toSet()) }
    var hostsText by remember(pending) { mutableStateOf(pending.declaredHosts.joinToString("\n")) }
    var codeShown by remember(pending) { mutableStateOf(false) }

    val network = PluginPermissions.NETWORK in checked
    val hosts = hostsText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val permissions = PendingPluginCreation.CANONICAL_PERMISSIONS.filter { it in checked }
    val errors = PluginManifestValidator.validate(
        pending.candidate(name, description, permissions, hosts)
    )
    // The validator's own network sentences, inline under the hosts field —
    // the one place the form can leave a field's refusal unnamed.
    val hostErrors = errors.filter { it.contains("network.allow") }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Create \"${pending.displayName}\"?") },
        text = {
            // The same cap and the same reason as the form dialogs' bodies.
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "plugin_create_name" }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "plugin_create_description" }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${pending.idText} · v${pending.versionText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                // The five-name vocabulary, one checkbox each, in the row
                // vocabulary's canonical order. Unchecking is always allowed;
                // a permission the draft never declared can still be checked —
                // the model under-asked, and the person may know better.
                PendingPluginCreation.CANONICAL_PERMISSIONS.forEach { permission ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                checked = if (permission in checked) {
                                    checked - permission
                                } else {
                                    checked + permission
                                }
                            }
                            .semantics { contentDescription = "plugin_create_perm_$permission" },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = permission in checked,
                            onCheckedChange = { value ->
                                checked = if (value) checked + permission else checked - permission
                            }
                        )
                        Text(
                            // The network label re-renders from the LIVE hosts
                            // field, so the sentence the person consents to and
                            // the field below it can never disagree.
                            PluginPermissions.labelOf(
                                permission,
                                if (permission == PluginPermissions.NETWORK) hosts else emptyList()
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (network) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = hostsText,
                        onValueChange = { hostsText = it },
                        label = { Text("Network hosts (one per line)") },
                        isError = hostErrors.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "plugin_create_hosts" }
                    )
                    hostErrors.forEach { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                // The draft's own declarations, read-only — shown so nothing
                // rides unseen; the person cannot edit what the model declared
                // here (editing is feature A's job, after install).
                if (pending.declaredCommands.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Declares: " + pending.declaredCommands.joinToString(", ") { "/$it" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                // The full code, read-only, monospace, collapsible — collapsed
                // by default with its line count shown, because a review that
                // hides the code is no review at all, and a wall of JavaScript
                // the person has not opened yet is the first thing to fold.
                Text(
                    "\"${pending.entryPath} — review the code the chat's AI wrote:\"",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${pending.entrySource.lineSequence().count()} lines",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { codeShown = !codeShown }) {
                        Text(if (codeShown) "Hide code" else "Show code")
                    }
                }
                if (codeShown) {
                    Text(
                        pending.entrySource,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .semantics { contentDescription = "plugin_create_code" }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "The code runs only in the chats you switch it on for, with exactly the " +
                        "permissions checked above. Nothing is installed until you choose " +
                        "Allow and Create.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(name, description, permissions, hosts) },
                enabled = errors.isEmpty(),
                modifier = Modifier.semantics { contentDescription = "plugin_create_accept" }
            ) {
                Text("Allow and Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}
