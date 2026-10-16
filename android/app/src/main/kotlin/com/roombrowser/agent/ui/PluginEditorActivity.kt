@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roombrowser.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.roombrowser.RoomBrowserApp
import com.roombrowser.data.plugins.PluginDirZipper
import com.roombrowser.data.plugins.PluginInstallResult
import com.roombrowser.data.plugins.PluginZipOut
import com.roombrowser.data.repo.PluginRepository
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.plugins.PluginManifestValidator
import com.roombrowser.domain.plugins.ZAgentsManifest
import com.roombrowser.ui.common.RoomBrowserTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * The plugin file editor — its OWN activity in the default process, like the
 * manager it is launched from, and reached only through that row's Edit icon
 * (never the Node runtime, never a chat). It edits the TEXT of files that
 * ALREADY exist in the installed code directory: an edit changes bytes,
 * nothing else — there is no create, no rename, no delete, because the file
 * set the person consented to at install is the file set forever, and that
 * one rule is what keeps manifest-declared entry and command routing honest.
 *
 * SAVE IS A REINSTALL (design §2.3): the whole directory is re-zipped in
 * memory with the one edited file's bytes substituted
 * ([PluginDirZipper.zipDir]) and fed through the SAME [PluginRepository.install]
 * door a sideloaded ZIP enters — full re-validation, atomic directory swap,
 * the row rebuilt from the (possibly edited) manifest, re-consent wherever
 * the declared permission set grew. An invalid manifest therefore cannot be
 * SAVED, but it can be edited: the editor opens any text file regardless of
 * current validity, and only the commit runs the whole rulebook over the
 * would-be tree — fixing a broken manifest is one full edit and one save.
 *
 * "Repair" is the same path: saving even the unchanged manifest re-derives
 * every row column from the directory's files.
 */
class PluginEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        val graph = (application as RoomBrowserApp).graph
        setContent {
            RoomBrowserTheme {
                PluginEditorRoot(repo = graph.pluginRepo, pluginId = pluginId, onClose = { finish() })
            }
        }
    }

    companion object {

        /** The installed plugin's id — the row whose code directory is edited. */
        const val EXTRA_PLUGIN_ID = "plugin_id"

        /** Opens the editor for one installed plugin; a no-row id renders the missing-files note. */
        fun launch(context: Context, pluginId: String) {
            val intent = Intent(context, PluginEditorActivity::class.java)
                .putExtra(EXTRA_PLUGIN_ID, pluginId)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}

/** One file of the installed directory, as the editor's list carries it. */
private data class EditorFile(val relPath: String, val file: File, val editable: Boolean)

@Composable
private fun PluginEditorRoot(repo: PluginRepository, pluginId: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()

    // The directory, its files and its manifest verdict — re-read on open and
    // after every save, because a save swaps the directory under the
    // installer's atomic rename and the next read must see the tree that now
    // IS the plugin.
    var dir by remember { mutableStateOf<File?>(null) }
    var files by remember { mutableStateOf<List<EditorFile>>(emptyList()) }
    var dirErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(pluginId, refresh) {
        val resolved = runCatching { repo.pluginDir(pluginId) }.getOrNull()
        dir = resolved
        val (listed, errors) = withContext(Dispatchers.IO) {
            val live = resolved?.takeIf { it.isDirectory }
            (live?.let { listFiles(it) } ?: emptyList()) to (live?.let { manifestErrors(it) } ?: emptyList())
        }
        files = listed
        dirErrors = errors
    }

    var selected by remember { mutableStateOf<String?>(null) }
    var fieldText by remember { mutableStateOf("") }
    // The loaded content of the selected file — the dirty check's baseline,
    // null while the (≤256 KB) read is in flight. NOT re-keyed on refresh:
    // a save's own success handler re-baselines the field, so the editor
    // keeps showing exactly what installed (a forked save leaves the OLD
    // plugin's editor on the text it saved — the honest state for a row
    // whose directory the fork did not touch).
    var baseline by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selected) {
        val target = files.firstOrNull { it.relPath == selected && it.editable }
        baseline = null
        fieldText = ""
        if (target != null) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { target.file.readText() }.getOrNull()
            }
            baseline = loaded
            fieldText = loaded.orEmpty()
        }
    }
    val dirty = baseline != null && fieldText != baseline

    var message by remember { mutableStateOf<String?>(null) }
    var messageFailed by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // A file the person tapped while the current one is dirty: the guard
    // below asks its question first, and the answer either switches (Discard)
    // or stays put (Keep editing) — a mis-tap must never cost the edits.
    var pendingSelection by remember { mutableStateOf<String?>(null) }

    /** Opening another file while this one is dirty asks the same question the back button does. */
    fun openFile(relPath: String) {
        if (dirty) {
            pendingSelection = relPath
            confirmDiscard = true
        } else {
            selected = relPath
        }
    }

    /** Back and the top bar's arrow ask the same question: unsaved edits are guarded, not lost. */
    fun requestClose() {
        if (dirty) confirmDiscard = true else onClose()
    }
    BackHandler { requestClose() }

    /**
     * The save path (design §2.3): the directory re-zipped with this file's
     * new bytes, then installed through the one door — every guard the
     * installer owns re-runs, the row is rebuilt from the would-be tree, and
     * a refusal leaves the disk exactly as it was (the installer's atomicity).
     */
    fun save(rel: String) {
        val target = dir ?: return
        val text = fieldText
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                PluginDirZipper.zipDir(target, mapOf(rel to text.toByteArray(Charsets.UTF_8)))
            }
            when (out) {
                is PluginZipOut.Refused -> {
                    message = "The file was not saved:\n" +
                        out.errors.joinToString("\n") { "— $it" }
                    messageFailed = true
                }
                is PluginZipOut.Ok ->
                    when (val result = repo.install(out.bytes.inputStream())) {
                        is PluginInstallResult.ManifestOk -> {
                            message = "$rel saved — the plugin was re-validated and reinstalled."
                            messageFailed = false
                            // The saved text is the field's new baseline — it keeps
                            // showing exactly what installed — and the list and the
                            // banner re-read the directory the save swapped in.
                            baseline = text
                            refresh++
                        }
                        is PluginInstallResult.Invalid -> {
                            message = "The file was not saved:\n" +
                                result.errors.joinToString("\n") { "— $it" }
                            messageFailed = true
                        }
                    }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Edit plugin files") },
                navigationIcon = {
                    IconButton(onClick = { requestClose() }) {
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
        ) {
            if (dir == null || dir?.isDirectory != true) {
                // A row whose code directory is gone cannot be repaired here:
                // there are no files to re-zip. The manager hides this row's
                // Edit icon for the same read; the editor still answers
                // honestly when it is what got launched.
                Text(
                    "Files missing — reinstall from a ZIP or remove.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                return@Column
            }
            if (dirErrors.isNotEmpty()) {
                // The directory's manifest currently fails its format's rules
                // — the banner names what is wrong, and the fix is one full
                // edit plus one save: the save re-runs the whole install
                // validation over the would-be tree.
                Text(
                    "This plugin's files do not validate:\n" +
                        dirErrors.joinToString("\n") { "— $it" } +
                        "\nFix the manifest, then save — saving re-runs the full install validation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            message?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (messageFailed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .semantics { contentDescription = "plugin_editor_list" }
            ) {
                items(files, key = { it.relPath }) { entry ->
                    EditorFileRow(
                        entry = entry,
                        selected = entry.relPath == selected,
                        onOpen = { openFile(entry.relPath) }
                    )
                }
            }
            if (selected != null && baseline != null) {
                val rel = selected!!
                val forkWarning = manifestForkWarning(pluginId, rel, fieldText)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (forkWarning != null) {
                        Text(
                            forkWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    OutlinedTextField(
                        value = fieldText,
                        onValueChange = { fieldText = it },
                        label = { Text(rel) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp)
                            .semantics { contentDescription = "plugin_editor_field" }
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { save(rel) },
                            enabled = dirty,
                            modifier = Modifier.semantics { contentDescription = "plugin_editor_save" }
                        ) { Text("Save") }
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            // Keep editing (either dismissal) clears the pending switch too:
            // a stale one would turn a later back-press into a file switch.
            onDismissRequest = {
                pendingSelection = null
                confirmDiscard = false
            },
            title = { Text("Discard your changes to ${selected ?: ""}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Discard answers BOTH guards: a switch lands on the
                        // file the person tapped, and a back lands on closing.
                        val next = pendingSelection
                        pendingSelection = null
                        confirmDiscard = false
                        if (next != null) selected = next else onClose()
                    }
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingSelection = null
                        confirmDiscard = false
                    }
                ) { Text("Keep editing") }
            }
        )
    }
}

@Composable
private fun EditorFileRow(entry: EditorFile, selected: Boolean, onOpen: () -> Unit) {
    val sanitized = entry.relPath.replace('/', '_')
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (entry.editable) Modifier.clickable { onOpen() } else Modifier)
            .semantics { contentDescription = "plugin_editor_file_$sanitized" }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                entry.relPath + if (entry.editable) "" else " (not editable)",
                style = MaterialTheme.typography.bodyMedium,
                color = if (entry.editable) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1
            )
            if (selected) {
                Text(
                    "editing",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** Every file under [dir], relative paths, sorted — the exact tree the installer extracted. */
private fun listFiles(dir: File): List<EditorFile> =
    dir.walkTopDown()
        .filter { it.isFile }
        .sortedBy { it.absolutePath }
        .map { file ->
            val rel = file.toRelativeString(dir)
            EditorFile(relPath = rel, file = file, editable = PluginDirZipper.isEditable(file))
        }
        .toList()

/**
 * Why the directory's manifest currently fails its format's rules — the
 * banner's own sentences, from the same validators the save will run. An
 * unreadable or missing manifest is itself the finding.
 */
private fun manifestErrors(dir: File): List<String> {
    val native = File(dir, "plugin.json")
    val module = File(dir, "module.json")
    return when {
        native.isFile -> validateJson(native) { PluginManifestValidator.validate(it) }
        module.isFile -> validateJson(module) { ZAgentsManifest.validate(it) }
        else -> listOf("the plugin directory carries no plugin.json or module.json")
    }
}

/** One manifest file through its format's validator, with the read's own failure as the finding. */
private fun validateJson(
    manifest: File,
    validator: (JsonObject) -> List<String>
): List<String> = runCatching {
    val root = AgentJson.parseToJsonElement(manifest.readText()) as? JsonObject
    root?.let { validator(it) } ?: listOf("the manifest is not a JSON object")
}.getOrElse { listOf(it.message ?: it.javaClass.simpleName) }

/**
 * The identity-fork warning line, or null when the edited text does not move
 * the plugin's identity: a manifest whose id (or a module's `zagents.<name>`)
 * differs from the row's installs a NEW plugin when saved — the old row and
 * its directory stay untouched, because the safe direction is a fork, never a
 * rename. Read before the tap so the person knows what the save will do.
 */
private fun manifestForkWarning(pluginId: String, relPath: String, text: String): String? {
    if (relPath != "plugin.json" && relPath != "module.json") return null
    val root = runCatching {
        AgentJson.parseToJsonElement(text) as? JsonObject
    }.getOrNull() ?: return null
    val newId = when (relPath) {
        "plugin.json" -> (root["id"] as? JsonPrimitive)?.contentOrNull
        else -> ZAgentsManifest.parse(root)?.pluginId
    }
    if (newId.isNullOrBlank() || newId == pluginId) return null
    return "Saving this manifest installs it as a NEW plugin — \"$newId\" — the old one stays " +
        "installed too."
}
