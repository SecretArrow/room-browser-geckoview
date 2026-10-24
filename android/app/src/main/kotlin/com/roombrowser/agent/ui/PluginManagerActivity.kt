@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roombrowser.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.RoomBrowserApp
import com.roombrowser.data.db.PluginEntity
import com.roombrowser.data.plugins.PluginDirZipper
import com.roombrowser.data.plugins.PluginInstallResult
import com.roombrowser.data.plugins.PluginZipOut
import com.roombrowser.data.repo.PluginRepository
import com.roombrowser.data.repo.decodePermissions
import com.roombrowser.domain.plugins.PluginFormat
import com.roombrowser.domain.plugins.PluginPermissions
import com.roombrowser.node.NodeRuntime
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.RoomBrowserTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The installed chat plugins — its OWN activity in the default process, like
 * the other agent screens, and the one place a plugin's presence in the app
 * is managed: install from a ZIP through the system file picker, install the
 * bundled example, edit an installed plugin's files (the editor activity),
 * export one as a shareable `.zip`, and uninstall (always confirmed, because
 * it deletes every chat's data for the plugin — the code comes back with a
 * reinstall, the chats' kv does not).
 *
 * Like the npm-packages screen it is patterned on, it never starts the Node
 * runtime to draw the list — the rows are the Room table the installer
 * validated into, and Room's multi-instance invalidation keeps this default
 * process's view in step with the ':browser' process that runs the plugins.
 */
class PluginManagerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as RoomBrowserApp).graph
        setContent {
            RoomBrowserTheme {
                PluginManagerRoot(repo = graph.pluginRepo, onClose = { finish() })
            }
        }
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, PluginManagerActivity::class.java)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginManagerRoot(repo: PluginRepository, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plugins by repo.observeInstalled().collectAsState(initial = emptyList())
    val runtimeAvailable = remember { NodeRuntime.available }
    var installMessage by remember { mutableStateOf<String?>(null) }
    var installFailed by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<PluginEntity?>(null) }

    // Which rows still hold a code directory — one cheap File check per row
    // (design §2.4): a row whose dir was lost under the app renders the
    // files-missing note and keeps only the remove action, because there are
    // no files to edit or export. Re-read whenever the list changes, the same
    // invalidation the rows themselves ride.
    var filesPresent by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LaunchedEffect(plugins) {
        filesPresent = plugins.associate { row ->
            row.id to (runCatching { repo.pluginDir(row.id) }.getOrNull()?.isDirectory == true)
        }
    }

    // The row whose ZIP the person chose a destination for — read in the
    // launcher's callback, cleared the moment the callback answers (a
    // cancelled picker clears it too: nothing exports twice).
    var exportTarget by remember { mutableStateOf<PluginEntity?>(null) }

    /** What an install produced, said inline — every reason, not the first. */
    fun report(result: PluginInstallResult) {
        when (result) {
            is PluginInstallResult.ManifestOk -> {
                installMessage =
                    "${result.manifest.name} ${result.manifest.version} installed. Switch it " +
                        "on per chat with /plugins."
                installFailed = false
            }
            is PluginInstallResult.Invalid -> {
                installMessage = "The plugin was not installed:\n" +
                    result.errors.joinToString("\n") { "— $it" }
                installFailed = true
            }
        }
    }

    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream -> repo.install(stream) }
            }.getOrNull() ?: PluginInstallResult.Invalid(listOf("the ZIP could not be opened"))
            report(result)
        }
    }

    // SAF CreateDocument: the zip is built AFTER the destination is chosen —
    // no gathering on a tap the person can cancel (the sessions screen's
    // precedent). The walk covers ONLY the plugin's code directory, so an
    // export can structurally never carry a chat's kv, files or state.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val plugin = exportTarget
        exportTarget = null
        if (uri == null || plugin == null) return@rememberLauncherForActivityResult
        scope.launch {
            val dir = runCatching { repo.pluginDir(plugin.id) }.getOrNull()
            if (dir == null || !dir.isDirectory) {
                installMessage = "The plugin was not exported:\n— the plugin directory is missing"
                installFailed = true
                return@launch
            }
            when (val zipped = PluginDirZipper.zipDir(dir)) {
                is PluginZipOut.Refused -> {
                    installMessage = "The plugin was not exported:\n" +
                        zipped.errors.joinToString("\n") { "— $it" }
                    installFailed = true
                }
                is PluginZipOut.Ok -> {
                    val failure = runCatching {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { stream ->
                                stream.write(zipped.bytes)
                            } ?: error("the chosen location could not be opened for writing")
                        }
                    }.exceptionOrNull()
                    if (failure == null) {
                        installMessage = "Exported ${plugin.name} to the chosen location."
                        installFailed = false
                    } else {
                        // The user is told WHAT failed, not just that something
                        // did — the sessions screen's sentence shape.
                        installMessage =
                            "Export failed: ${failure.message ?: failure.javaClass.simpleName}"
                        installFailed = true
                    }
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Chat plugins") },
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
        ) {
            if (!runtimeAvailable) {
                Text(
                    "This build carries no Node runtime for this device's CPU, so no plugin " +
                        "can run here. Installs are still kept, and work again on a device " +
                        "the runtime supports.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            // The two doors in: a ZIP of the person's choosing, and the example
            // this APK ships — the smallest honest demonstration of what a
            // plugin is, one tap away from anyone curious enough to open this
            // screen. FlowRow so they wrap on a narrow phone rather than clip.
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        installLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    modifier = Modifier.semantics { contentDescription = "plugin_manager_install_zip" }
                ) { Text("Install from ZIP") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val result = runCatching {
                                context.assets.open(BUNDLED_EXAMPLE).use { stream ->
                                    repo.install(stream)
                                }
                            }.getOrNull() ?: PluginInstallResult.Invalid(
                                listOf("the bundled example is missing from this build")
                            )
                            report(result)
                        }
                    },
                    modifier = Modifier
                        .semantics { contentDescription = "plugin_manager_install_bundled" }
                ) { Text("Install the bundled example") }
            }
            installMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (installFailed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (plugins.isEmpty()) {
                EmptyState(
                    title = "No plugins installed",
                    subtitle = "A plugin is a plain-JavaScript package this app installs from " +
                        "a ZIP and runs per chat, with permissions you grant at enable time. " +
                        "Nothing is installed until you say so."
                )
                return@Column
            }

            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "plugin_manager_list" }
            ) {
                items(plugins, key = { it.id }) { plugin ->
                    ManagerPluginRow(
                        plugin = plugin,
                        filesPresent = filesPresent[plugin.id] ?: false,
                        onEdit = { PluginEditorActivity.launch(context, plugin.id) },
                        onExport = {
                            exportTarget = plugin
                            exportLauncher.launch(exportFileName(plugin))
                        },
                        onUninstall = { removeTarget = plugin }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    removeTarget?.let { plugin ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove plugin?") },
            text = {
                Text(
                    "Remove ${plugin.name} and its data in every chat? The chats keep their " +
                        "conversations; the plugin's code and its stored data are gone, and " +
                        "chats that had it on fall back to the agent alone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeTarget = null
                        scope.launch { repo.uninstall(plugin.id) }
                    },
                    modifier = Modifier
                        .semantics { contentDescription = "plugin_manager_confirm_remove" }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ManagerPluginRow(
    plugin: PluginEntity,
    filesPresent: Boolean,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onUninstall: () -> Unit
) {
    val hosts = decodePermissions(plugin.networkAllowJson)
    val permissionLine = decodePermissions(plugin.permissionsJson)
        .joinToString(" · ") { PluginPermissions.labelOf(it, hosts) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .semantics { contentDescription = "plugin_manager_row_${plugin.dirName}" },
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
            if (plugin.authorName.isNotBlank()) {
                Text(
                    plugin.authorName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                permissionLine.ifBlank { "No permissions" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!filesPresent) {
                // The row whose code directory is gone (design §2.4): it
                // cannot be repaired — nothing re-zips — so it says so, and
                // the delete icon is its only action.
                Text(
                    "Files missing — reinstall from a ZIP or remove.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (filesPresent) {
            IconButton(
                onClick = onEdit,
                modifier = Modifier
                    .semantics { contentDescription = "plugin_manager_edit_${plugin.dirName}" }
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null)
            }
            IconButton(
                onClick = onExport,
                modifier = Modifier
                    .semantics { contentDescription = "plugin_manager_export_${plugin.dirName}" }
            ) {
                Icon(Icons.Filled.FileDownload, contentDescription = null)
            }
        }
        IconButton(
            onClick = onUninstall,
            modifier = Modifier
                .semantics { contentDescription = "plugin_manager_uninstall_${plugin.dirName}" }
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null)
        }
    }
}

/**
 * The export's default SAF filename: `<name>-<version>.zip`, with the row's
 * own spellings — the semver column for a plugin row, the module's own
 * verbatim version for a module row. Hostile filename characters fold to
 * '-' inside [PluginDirZipper.exportFileName]; spaces stay (SAF handles
 * them).
 */
private fun exportFileName(plugin: PluginEntity): String {
    val version = if (plugin.format == PluginFormat.ZAGENTS) {
        plugin.zagentsVersion ?: plugin.version
    } else {
        plugin.version
    }
    return PluginDirZipper.exportFileName(plugin.name, version)
}

/** The example shipped inside the APK, synced from `assets/node/plugins/`. */
private const val BUNDLED_EXAMPLE = "node/plugins/hello-world.zip"
