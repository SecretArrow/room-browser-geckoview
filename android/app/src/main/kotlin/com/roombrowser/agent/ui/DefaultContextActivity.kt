@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roombrowser.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.agent.AgentSettingsController
import com.roombrowser.domain.agent.ContextPreset
import com.roombrowser.domain.agent.ContextPresets
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.SectionHeader
import com.roombrowser.ui.common.SettingsGroup

/**
 * The default-context editor, as its OWN ACTIVITY so a paragraph has a whole
 * screen to be written on, and so one window is the only writer of the text
 * the agent carries into every request.
 *
 * Runs in the DEFAULT process (no WebView) like the other agent screens; the
 * ':browser' process picks the write up through Room multi-instance
 * invalidation.
 */
class DefaultContextActivity : ComponentActivity() {

    private lateinit var controller: AgentSettingsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = AgentSettingsController(application, null)
        controller.start()
        setContent {
            RoomBrowserTheme {
                DefaultContextScreen(controller = controller, onClose = { finish() })
            }
        }
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    companion object {
        /** Opens the editor. [context] may be a Compose [LocalContext]. */
        fun launch(context: Context) {
            val intent = Intent(context, DefaultContextActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}

@Composable
private fun DefaultContextScreen(
    controller: AgentSettingsController,
    onClose: () -> Unit
) {
    val settings = controller.settings
    val snackbarHostState = remember { SnackbarHostState() }
    var notice by remember { mutableStateOf<String?>(null) }
    // Keyed on the SAVED text: an edit made in the panel's chip must land in
    // this field rather than being overwritten by a stale draft.
    var draft by remember(settings.defaultContext) { mutableStateOf(settings.defaultContext) }
    var presetName by remember { mutableStateOf("") }
    /** The preset being renamed/replaced; null means the next save adds one. */
    var editingPresetId by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<ContextPreset?>(null) }

    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            notice = null
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Default context") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Sent with every request until you turn it off — the same " +
                    "instruction, not a one-off. Use it for how you want answers " +
                    "(language, format, level of detail) or what you are working on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            ContextSwitchRow(
                label = "Send with every request",
                description = if (settings.defaultContext.isBlank()) {
                    "Save a standing message below first"
                } else {
                    "The agent applies the saved context to every request"
                },
                semanticsLabel = "Default context switch",
                checked = settings.useDefaultContext,
                onCheckedChange = { on ->
                    if (on && settings.defaultContext.isBlank()) {
                        notice = "Save a context before switching it on"
                    } else {
                        controller.updateSettings { s -> s.copy(useDefaultContext = on) }
                        notice = if (on) "Default context on" else "Default context off"
                    }
                }
            )

            SectionHeader("Your context")
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = {
                    Text("e.g. Answer in Indonesian, be concise, and assume I am on Android.")
                },
                minLines = 8,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .semantics { contentDescription = "agent_default_context_field" }
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Button(
                    onClick = {
                        val text = draft.trim()
                        controller.updateSettings { s ->
                            s.copy(defaultContext = text, useDefaultContext = text.isNotEmpty())
                        }
                        notice = if (text.isEmpty()) "Default context cleared" else "Default context saved"
                    },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "agent_default_context_save" }
                ) { Text("Save and use") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = {
                        draft = ""
                        controller.updateSettings { s ->
                            s.copy(defaultContext = "", useDefaultContext = false)
                        }
                        notice = "Default context cleared"
                    },
                    enabled = draft.isNotBlank() || settings.defaultContext.isNotBlank(),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "agent_default_context_clear" }
                ) { Text("Clear") }
            }

            SectionHeader("Presets")
            Text(
                "One tap re-applies a saved context. The name is a label — editing a " +
                    "preset overwrites it, it does not add a second copy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            OutlinedTextField(
                value = presetName,
                onValueChange = { presetName = it },
                label = { Text(if (editingPresetId == null) "New preset name" else "Preset name") },
                placeholder = { Text("Optional — the first line of the text is used") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { contentDescription = "agent_context_preset_name" }
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Button(
                    onClick = {
                        val preset = ContextPresets.of(editingPresetId.orEmpty(), presetName, draft)
                        if (preset == null) {
                            notice = "Write the context above before saving a preset"
                        } else {
                            controller.updateSettings { s ->
                                s.copy(contextPresets = ContextPresets.upsert(s.contextPresets, preset))
                            }
                            editingPresetId = null
                            presetName = ""
                            notice = "Preset saved"
                        }
                    },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "agent_context_preset_save" }
                ) { Text(if (editingPresetId == null) "Save as preset" else "Update preset") }
                if (editingPresetId != null) {
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = {
                            editingPresetId = null
                            presetName = ""
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "agent_context_preset_cancel" }
                    ) { Text("Cancel") }
                }
            }

            if (settings.contextPresets.isEmpty()) {
                Text(
                    "No presets saved yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            } else {
                val extras = LocalRoomExtras.current
                SettingsGroup {
                    settings.contextPresets.forEach { preset ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable {
                                        // Applying fills the field AND takes
                                        // effect, so one tap answers "what will
                                        // be sent" without a second save.
                                        draft = preset.text
                                        controller.updateSettings { s ->
                                            s.copy(defaultContext = preset.text, useDefaultContext = true)
                                        }
                                        notice = "Preset applied"
                                    }
                                    .padding(vertical = 10.dp)
                                    .semantics {
                                        contentDescription = "agent_context_preset_use_${preset.name}"
                                    }
                            ) {
                                Text(
                                    preset.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = extras.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    ContextPresets.snippet(preset.text),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = extras.textSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = {
                                    editingPresetId = preset.id
                                    presetName = preset.name
                                    draft = preset.text
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = "agent_context_preset_edit_${preset.name}"
                                }
                            ) { Icon(Icons.Filled.Edit, contentDescription = null) }
                            IconButton(
                                onClick = { confirmDelete = preset },
                                modifier = Modifier.semantics {
                                    contentDescription = "agent_context_preset_delete_${preset.name}"
                                }
                            ) { Icon(Icons.Filled.Delete, contentDescription = null) }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete preset?") },
            text = { Text("\"${preset.name}\" is removed. The text itself is not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    controller.updateSettings { s ->
                        s.copy(contextPresets = ContextPresets.remove(s.contextPresets, preset.id))
                    }
                    if (editingPresetId == preset.id) {
                        editingPresetId = null
                        presetName = ""
                    }
                    confirmDelete = null
                    notice = "Preset deleted"
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}
