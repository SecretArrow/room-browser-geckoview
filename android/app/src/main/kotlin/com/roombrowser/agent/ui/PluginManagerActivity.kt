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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.roombrowser.agent.skills.SkillInstaller
import com.roombrowser.agent.skills.SkillRecord
import com.roombrowser.agent.skills.SkillRegistry
import com.roombrowser.agent.skills.SkillSource
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
import com.roombrowser.ui.common.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The installed chat plugins AND the installed agent skills — its OWN activity
 * in the default process, like the other agent screens, and the one place a
 * plugin's or a skill's presence in the app is managed.
 *
 * The PLUGIN half: install from a ZIP through the system file picker, install
 * the bundled example, edit an installed plugin's files (the editor activity),
 * export one as a shareable `.zip`, and uninstall (always confirmed, because
 * it deletes every chat's data for the plugin — the code comes back with a
 * reinstall, the chats' kv does not).
 *
 * The SKILLS half: the same rows the `skills` tool's registry reads, with the
 * actions a person wants at their own screen — install from a GitHub source
 * pasted in ANY of the spellings a README teaches (a bare `owner/repo/path`,
 * a full https URL, or a verbatim `npx skills add …` command, which
 * [SkillSource.stripCliSpelling] strips before parsing), enable or disable an
 * instruction skill's prompt contribution, read what it actually says, and
 * remove it (confirmed, like every delete here). The stack is constructed
 * locally from the app context and the plugin store — the skills system is
 * self-contained by design, and the screen reaches neither the agent
 * controller nor the Node runtime to draw it. Every install runs the REAL
 * [SkillInstaller]: what lands on disk is exactly what codeload served,
 * nothing is simulated, and a refusal lists every reason.
 *
 * Like the npm-packages screen it is patterned on, it never starts the Node
 * runtime to draw the list — the plugin rows are the Room table the installer
 * validated into, and Room's multi-instance invalidation keeps this default
 * process's view in step with the ':browser' process that runs the plugins.
 * The skill rows are the `<filesDir>/skills/` directories the same way: read
 * from disk, never through the agent.
 */
class PluginManagerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as RoomBrowserApp).graph
        // The skills store and its installer, built the way the tool group
        // builds them: the ONE directory [SkillRegistry.STORE_DIR_NAME] names,
        // and the plugin store a plugin-format skill lands in. The uninstall
        // seam is wired here so removing a plugin-format skill from this
        // screen also removes its plugin row and every chat's data for it.
        val skillsRoot = File(filesDir, SkillRegistry.STORE_DIR_NAME)
        val skills = SkillRegistry(skillsRoot).apply {
            pluginUninstaller = { id -> graph.pluginRepo.uninstall(id) }
        }
        val skillInstaller = SkillInstaller(skillsRoot, graph.pluginRepo).apply {
            pluginUninstaller = { id -> graph.pluginRepo.uninstall(id) }
        }
        setContent {
            RoomBrowserTheme {
                PluginManagerRoot(
                    repo = graph.pluginRepo,
                    skills = skills,
                    skillInstaller = skillInstaller,
                    onClose = { finish() }
                )
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
private fun PluginManagerRoot(
    repo: PluginRepository,
    skills: SkillRegistry,
    skillInstaller: SkillInstaller,
    onClose: () -> Unit
) {
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

    // ---- skills state ----
    // The snapshot is a plain read of the store, re-taken whenever [skillRefresh]
    // moves — the store is files, not a Flow, and every action below bumps the
    // counter when it has changed something on disk.
    var skillSnapshot by remember { mutableStateOf<SkillRegistry.Snapshot?>(null) }
    var skillRefresh by remember { mutableStateOf(0) }
    var skillSource by remember { mutableStateOf("") }
    var skillInstalling by remember { mutableStateOf(false) }
    var skillMessage by remember { mutableStateOf<String?>(null) }
    var skillFailed by remember { mutableStateOf(false) }
    var skillRemoveTarget by remember { mutableStateOf<SkillRecord?>(null) }
    var skillViewTarget by remember { mutableStateOf<SkillRecord?>(null) }
    var skillViewBody by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    LaunchedEffect(skillRefresh) {
        skillSnapshot = skills.list()
    }

    val installSkill = {
        if (!skillInstalling && skillSource.isNotBlank()) {
            scope.launch {
                skillInstalling = true
                skillMessage = null
                try {
                    // The field accepts what a user pastes verbatim — the
                    // `npx skills add …` spelling included. One strip, one
                    // parse, the SAME door the chat tool uses, so a source
                    // this screen accepts the agent accepts too.
                    val source = SkillSource.parse(SkillSource.stripCliSpelling(skillSource))
                        .getOrElse { err ->
                            skillMessage = "That source could not be used: ${err.message}"
                            skillFailed = true
                            return@launch
                        }
                    when (val outcome = skillInstaller.install(source)) {
                        is SkillInstaller.Outcome.Refused -> {
                            skillMessage = "The skill was not installed:\n" +
                                outcome.errors.joinToString("\n") { "— $it" }
                            skillFailed = true
                        }
                        is SkillInstaller.Outcome.Installed -> {
                            skillMessage = buildString {
                                append(
                                    "Installed \"${outcome.record.name}\" " +
                                        "(${outcome.record.format})."
                                )
                                if (outcome.record.format == SkillRecord.FORMAT_PLUGIN) {
                                    append(
                                        " Its tools run per chat — enable it with /plugins " +
                                            "in the agent panel."
                                    )
                                } else {
                                    append(
                                        if (outcome.record.enabled) {
                                            " Its instructions join the agent's prompts."
                                        } else {
                                            " Use the switch on its row to enable it."
                                        }
                                    )
                                }
                            }
                            skillFailed = false
                            skillSource = ""
                            skillRefresh++
                        }
                    }
                } finally {
                    skillInstalling = false
                }
            }
        }
        Unit
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Plugins and skills") },
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
            // One scroller for both halves: a skills section pinned above a
            // plugin LazyColumn would push the plugins off a small screen,
            // and neither list is long enough to need its own.
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "plugin_manager_list" }
            ) {
                if (!runtimeAvailable) {
                    item(key = "runtime-note") {
                        Text(
                            "This build carries no Node runtime for this device's CPU, so no " +
                                "plugin can run here. Installs are still kept, and work again " +
                                "on a device the runtime supports. Skills' instructions need " +
                                "no runtime to work.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }

                // The two doors in: a ZIP of the person's choosing, and the
                // example this APK ships — the smallest honest demonstration
                // of what a plugin is, one tap away from anyone curious enough
                // to open this screen. FlowRow so they wrap on a narrow phone
                // rather than clip.
                item(key = "plugin-install") {
                    FlowRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                installLauncher.launch(
                                    arrayOf("application/zip", "application/octet-stream")
                                )
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "plugin_manager_install_zip"
                            }
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
                }
                installMessage?.let { message ->
                    item(key = "plugin-message") {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (installFailed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }

                // ---- the skills half ----
                item(key = "skills-header") {
                    SectionHeader("Skills")
                }
                item(key = "skills-install") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        OutlinedTextField(
                            value = skillSource,
                            onValueChange = { skillSource = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = "plugin_manager_skill_source"
                                },
                            singleLine = true,
                            enabled = !skillInstalling,
                            label = { Text("Install a skill from GitHub") },
                            placeholder = {
                                Text("owner/repo/path — or a pasted `npx skills add …` command")
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = installSkill,
                            enabled = !skillInstalling && skillSource.isNotBlank(),
                            modifier = Modifier.semantics {
                                contentDescription = "plugin_manager_skill_install"
                            }
                        ) {
                            Text(if (skillInstalling) "Installing…" else "Install from GitHub")
                        }
                    }
                }
                skillMessage?.let { message ->
                    item(key = "skill-message") {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (skillFailed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
                val skillList = skillSnapshot?.skills.orEmpty()
                if (skillList.isEmpty()) {
                    item(key = "skills-empty") {
                        Text(
                            "No skills installed. A skill is an instruction pack (a SKILL.md) " +
                                "or a plugin installed from a GitHub source. Every install is " +
                                "validated, and nothing in it is executed at install time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
                items(skillList, key = { "skill-${it.id}" }) { record ->
                    ManagerSkillRow(
                        record = record,
                        onToggle = { target, on ->
                            scope.launch {
                                val updated = skills.setEnabled(target.id, on)
                                if (updated == null) {
                                    skillMessage = "\"${target.name}\" could not be " +
                                        (if (on) "enabled" else "disabled") +
                                        " — its metadata is missing or unreadable."
                                    skillFailed = true
                                }
                                skillRefresh++
                            }
                        },
                        onView = { target ->
                            scope.launch {
                                // The body the agent is actually told about —
                                // read from the installed copy, clipped here
                                // for a dialog, with the clip said out loud.
                                val body = skills.instructionOf(target)
                                skillViewBody = body?.let { text ->
                                    if (text.length > SKILL_VIEW_MAX_CHARS) {
                                        (text.take(SKILL_VIEW_MAX_CHARS) +
                                            "\n\n… the rest stays in the installed skill.") to
                                            true
                                    } else {
                                        text to false
                                    }
                                }
                                skillViewTarget = target
                            }
                        },
                        onRemove = { skillRemoveTarget = it }
                    )
                }
                skillSnapshot?.broken.orEmpty().takeIf { it.isNotEmpty() }?.let { broken ->
                    item(key = "skills-broken") {
                        Text(
                            "Unreadable skill directories (remove their folders to clear them): " +
                                broken.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }

                // ---- the plugins half ----
                item(key = "plugins-header") {
                    SectionHeader("Plugins")
                }
                if (plugins.isEmpty()) {
                    item(key = "plugins-empty") {
                        EmptyState(
                            title = "No plugins installed",
                            subtitle = "A plugin is a plain-JavaScript package this app installs " +
                                "from a ZIP and runs per chat, with permissions you grant at " +
                                "enable time. Nothing is installed until you say so."
                        )
                    }
                }
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
                item(key = "bottom-space") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    skillRemoveTarget?.let { record ->
        AlertDialog(
            onDismissRequest = { skillRemoveTarget = null },
            title = { Text("Remove skill?") },
            text = {
                Text(
                    if (record.format == SkillRecord.FORMAT_PLUGIN) {
                        "Remove ${record.name}? The plugin it installed, its row, and every " +
                            "chat's stored data for it are removed too; chats that had it on " +
                            "fall back to the agent alone."
                    } else {
                        "Remove ${record.name}? Its instructions will no longer join the " +
                            "agent's prompts."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        skillRemoveTarget = null
                        scope.launch {
                            val problem = skills.remove(record)
                            skillMessage = if (problem == null) {
                                "Removed \"${record.name}\"."
                            } else {
                                "The skill was not fully removed: $problem"
                            }
                            skillFailed = problem != null
                            skillRefresh++
                        }
                    },
                    modifier = Modifier
                        .semantics { contentDescription = "plugin_manager_skill_confirm_remove" }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { skillRemoveTarget = null }) { Text("Cancel") }
            }
        )
    }

    skillViewTarget?.let { record ->
        val body = skillViewBody
        AlertDialog(
            onDismissRequest = {
                skillViewTarget = null
                skillViewBody = null
            },
            title = { Text(record.name) },
            text = {
                // Read-only and scrolled, with the body framed as what it is:
                // the author's text, the same bytes the agent is fenced into
                // reading as data.
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "The skill's instructions, as its author wrote them:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    if (body == null) {
                        Text(
                            "The instruction body is missing on disk — remove the skill and " +
                                "install it again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            body.first,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (body.second) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "(shown to its length limit here; the installed copy holds " +
                                    "the rest)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        skillViewTarget = null
                        skillViewBody = null
                    }
                ) { Text("Close") }
            }
        )
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
private fun ManagerSkillRow(
    record: SkillRecord,
    onToggle: (SkillRecord, Boolean) -> Unit,
    onView: (SkillRecord) -> Unit,
    onRemove: (SkillRecord) -> Unit
) {
    val isPlugin = record.format == SkillRecord.FORMAT_PLUGIN
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .semantics { contentDescription = "plugin_manager_skill_row_${record.id}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                // The format/version badge, in the version slot the plugin
                // row uses: an instruction skill and a plugin-format one are
                // different animals, and the row says which before the
                // actions do.
                Text(
                    buildString {
                        append(record.format)
                        if (record.version.isNotBlank()) append(" · v${record.version}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
            if (record.source.isNotBlank()) {
                Text(
                    record.source,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isPlugin) {
                // The enabled flag cannot reach a plugin-format skill — its
                // enablement is per chat — so the row says where the switch
                // actually lives instead of showing one that does nothing.
                Text(
                    "Enabled per chat, with /plugins.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (record.truncated) {
                Text(
                    "Instruction body was longer than the cap and is stored truncated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!isPlugin) {
            Switch(
                checked = record.enabled,
                onCheckedChange = { onToggle(record, it) },
                modifier = Modifier.semantics {
                    contentDescription = "plugin_manager_skill_toggle_${record.id}"
                }
            )
            IconButton(
                onClick = { onView(record) },
                modifier = Modifier.semantics {
                    contentDescription = "plugin_manager_skill_view_${record.id}"
                }
            ) {
                Icon(Icons.Filled.MenuBook, contentDescription = null)
            }
        }
        IconButton(
            onClick = { onRemove(record) },
            modifier = Modifier.semantics {
                contentDescription = "plugin_manager_skill_remove_${record.id}"
            }
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null)
        }
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

/** How much of an instruction body the read dialog shows before it says it stopped. */
private const val SKILL_VIEW_MAX_CHARS = 8_000

/** The example shipped inside the APK, synced from `assets/node/plugins/`. */
private const val BUNDLED_EXAMPLE = "node/plugins/hello-world.zip"
