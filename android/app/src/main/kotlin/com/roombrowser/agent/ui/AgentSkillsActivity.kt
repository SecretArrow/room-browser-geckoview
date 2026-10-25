@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roombrowser.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.agent.AgentSettingsController
import com.roombrowser.agent.AgentSkillsTools
import com.roombrowser.data.db.AgentSkillEntity
import com.roombrowser.data.skills.SkillInstallResult
import com.roombrowser.data.skills.SkillRepository
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AgentPermissions
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.RoomBrowserTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The installed skills and the place they are managed — its OWN activity,
 * mirroring [AgentPackagesActivity]: the list is a place the user can read,
 * enable, remove and install from, rather than a table only the agent sees.
 *
 * THE GATES ARE THE TOOL'S GATES. Installing fetches a git repository and
 * its instructions then reach the model's system prompt, so install runs
 * through the same [SkillRepository] the skills_install tool drives, behind
 * this profile's `agent.skills` permission — when that is withheld, the
 * screen says WHY instead of offering a button that would fail later.
 * Removal deletes files for every chat, so it asks first, every time — the
 * dialog IS the confirm gate the tool's confirmGate provides on the chat
 * route. Enable/disable is the user's own switch (the same consent a
 * /plugins toggle carries) and asks nothing.
 *
 * The skills are shared by every profile (like the Node packages), but the
 * PERMISSION is per profile, which is why the screen is opened with a
 * profile id and the banner names the profile's decision.
 */
class AgentSkillsActivity : ComponentActivity() {

    private lateinit var controller: AgentSettingsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = AgentSettingsController(application, intent.getStringExtra(EXTRA_PROFILE_ID))
        controller.start()
        setContent {
            RoomBrowserTheme {
                AgentSkillsRoot(
                    controller = controller,
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"

        fun launch(context: Context, profileId: String?) {
            val intent = Intent(context, AgentSkillsActivity::class.java).apply {
                putExtra(EXTRA_PROFILE_ID, profileId)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}

@Composable
private fun AgentSkillsRoot(controller: AgentSettingsController, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hostState = remember { SnackbarHostState() }
    val repo = remember { AgentSkillsTools.repository(context) }
    val skills by repo.observeAll().collectAsState(initial = null)

    // This profile's permission verdict: null when the agent may install,
    // otherwise the sentence the tools would answer with — shown once as a
    // banner rather than discovered twice as a failed install.
    val withheld = controller.profileSettings.agentWithheldPermissions
    val permissionRefusal = AgentPermissions.refusal(AgentTools.SKILLS_INSTALL, withheld)

    var installDialog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<AgentSkillEntity?>(null) }

    fun report(message: String) {
        scope.launch { hostState.showSnackbar(message) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState) },
        topBar = {
            TopAppBar(
                title = { Text("Agent skills") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                },
                actions = {
                    // An ICON, not a row: this is a rarely used action on a
                    // screen whose whole point is the list. The permission
                    // banner below is the explanation when the button is off.
                    IconButton(
                        onClick = { installDialog = true },
                        enabled = permissionRefusal == null
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Install a skill")
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
            if (permissionRefusal != null) {
                Text(
                    // WHY, verbatim from the permission map: the same sentence
                    // the agent's own tool call would answer with, so the user
                    // never has to guess what the profile's switch did.
                    permissionRefusal,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            Text(
                "A skill is a SKILL.md (instructions) plus reference files, fetched from a GitHub " +
                    "repository. An enabled skill's instructions join the agent's system prompt in " +
                    "every chat; nothing from a skill is ever executed. Install with " +
                    "\"npx skills add owner/repo/path/to/skill\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            val list = skills
            if (list == null) return@Column
            if (list.isEmpty()) {
                EmptyState(
                    title = "No skills installed",
                    subtitle = "Skills you or the agent install from GitHub appear here. Nothing is " +
                        "installed until someone asks, and npm packages a skill declares are " +
                        "installed only through the Node packages screen."
                )
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.skillId }) { skill ->
                    SkillRow(
                        skill = skill,
                        onToggle = { enabled ->
                            scope.launch {
                                repo.setEnabled(skill.skillId, enabled)
                                report(
                                    "Skill \"${skill.displayName}\" " +
                                        (if (enabled) "enabled" else "disabled") + "."
                                )
                            }
                        },
                        onRemove = { removeTarget = skill }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (installDialog) {
        // The dialog is the confirm gate: its Install button is the user
        // approving THIS fetch, exactly as the tool's confirmation card is
        // on the chat route.
        var spec by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { installDialog = false },
            title = { Text("Install a skill") },
            text = {
                Column {
                    Text(
                        "Fetches the repository, copies the skill's files into this app's data " +
                            "directory and adds its SKILL.md instructions to the agent's prompt. " +
                            "Nothing from a skill is executed; npm packages it declares are reported, " +
                            "not installed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = spec,
                        onValueChange = { spec = it },
                        singleLine = true,
                        label = { Text("npx skills add owner/repo/path/to/skill") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        installDialog = false
                        scope.launch {
                            // A failed install is a RESULT, never a crash: the
                            // fetch is network traffic and the commit is disk
                            // I/O, so both can throw, and an exception escaping
                            // this lambda would take the activity down without
                            // telling the person what went wrong.
                            val result = runCatching { repo.install(spec) }
                                .getOrElse { error ->
                                    report(
                                        "Install failed: " +
                                            (error.message ?: error.javaClass.simpleName)
                                    )
                                    return@launch
                                }
                            when (result) {
                                is SkillInstallResult.Refused -> report(result.reason)
                                is SkillInstallResult.Installed -> {
                                    val where = result.entity.sourceRepo +
                                        (if (result.entity.subPath.isEmpty()) "" else "/${result.entity.subPath}")
                                    report(
                                        (if (result.updated) "Updated" else "Installed") +
                                            " \"${result.entity.displayName}\" from $where" +
                                            depsNote(result.npmDeps)
                                    )
                                }
                            }
                        }
                    },
                    enabled = spec.isNotBlank()
                ) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = { installDialog = false }) { Text("Cancel") }
            }
        )
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove skill \"${target.displayName}\"?") },
            text = {
                Text(
                    "This deletes its files and its prompt entry for every chat. npm packages you " +
                        "installed for it through the Node runtime are NOT removed by this."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    scope.launch {
                        val removed = repo.remove(target.skillId)
                        report(
                            if (removed) "Removed \"${target.displayName}\"."
                            else "Could not remove \"${target.skillId}\" — no installed skill has that id."
                        )
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SkillRow(
    skill: AgentSkillEntity,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                skill.displayName + (if (skill.enabled) "" else "  (disabled)"),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                skill.sourceRepo + (if (skill.subPath.isEmpty()) "" else "/${skill.subPath}") +
                    (if (skill.version.isEmpty()) "" else " · v${skill.version}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val deps = declaredDeps(skill)
            if (deps.isNotEmpty()) {
                Text(
                    "declares npm deps: ${deps.joinToString(", ")} — install via Node packages",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = skill.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove ${skill.displayName}")
        }
    }
}

/** The declared npm packages of a row, or an empty list when none or unreadable. */
private fun declaredDeps(skill: AgentSkillEntity): List<String> = runCatching {
    (AgentJson.parseToJsonElement(skill.npmDepsJson) as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
}.getOrDefault(emptyList())

/** The one sentence a finished install adds about the skill's declared npm deps. */
private fun depsNote(deps: List<String>): String =
    if (deps.isEmpty()) {
        ""
    } else {
        " It declares npm deps (${deps.joinToString(", ")}) — install them via Node packages."
    }
