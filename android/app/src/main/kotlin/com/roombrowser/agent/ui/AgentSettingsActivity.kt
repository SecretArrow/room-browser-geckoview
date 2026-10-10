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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.agent.AgentSettingsController
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.domain.agent.RetryCodes
import com.roombrowser.domain.agent.RetryPolicy
import com.roombrowser.domain.agent.RetryStatusCode
import com.roombrowser.domain.paging.Paging
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.PagingFooter
import com.roombrowser.ui.common.RoomBottomSheetShape
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomSheetHeader
import com.roombrowser.ui.common.SectionHeader
import com.roombrowser.ui.common.SettingActionRow
import com.roombrowser.ui.common.SettingSwitchRow
import com.roombrowser.ui.common.SettingsGroup
import kotlinx.coroutines.launch

/**
 * AI Agent settings — a DEDICATED ACTIVITY (own window, own back stack entry,
 * proper IME handling) instead of an in-browser route:
 *
 *   1. Provider management — add/edit providers in [AgentProviderEditorActivity]
 *      (another activity), select the default, delete.
 *   2. Agent behavior — floating-button visibility (hidden by default),
 *      confirm-actions, page context, temperature, step budget, system prompt.
 *   3. Data & privacy — clear all agent chats.
 *
 * Runs in the DEFAULT process (no WebView here). Every write lands in the
 * shared Room database, so the live agent in the ':browser' process updates
 * instantly via multi-instance invalidation.
 */
class AgentSettingsActivity : ComponentActivity() {

    private lateinit var controller: AgentSettingsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: insets are consumed by the Compose UI below — nothing
        // ever overlaps the system Back / Home / Recents buttons.
        enableEdgeToEdge()
        controller = AgentSettingsController(application, intent.getStringExtra(EXTRA_PROFILE_ID))
        controller.start()
        setContent {
            RoomBrowserTheme {
                AgentSettingsRoot(
                    controller = controller,
                    onAddProvider = { launchEditor(null) },
                    onEditProvider = { provider -> launchEditor(provider.id) },
                    onClose = { finish() }
                )
            }
        }
    }

    private fun launchEditor(id: Long?) {
        val intent = Intent(this, AgentProviderEditorActivity::class.java)
        if (id != null) intent.putExtra(AgentProviderEditorActivity.EXTRA_PROVIDER_ID, id)
        startActivity(intent)
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"

        fun launch(from: Activity, profileId: String?) {
            from.startActivity(Intent(from, AgentSettingsActivity::class.java).apply {
                putExtra(EXTRA_PROFILE_ID, profileId)
            })
        }

        /** Convenience for callers holding only a context (e.g. Compose). */
        fun launch(context: Context, profileId: String?) {
            val intent = Intent(context, AgentSettingsActivity::class.java).apply {
                putExtra(EXTRA_PROFILE_ID, profileId)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}

@Composable
private fun AgentSettingsRoot(
    controller: AgentSettingsController,
    onAddProvider: () -> Unit,
    onEditProvider: (AgentProviderEntity) -> Unit,
    onClose: () -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmClearSessions by remember { mutableStateOf(false) }
    // The provider queued for deletion. Removing one used to happen on the
    // SINGLE tap of its trash icon, next to the edit icon in the same row:
    // one mis-tap and the endpoint, its default model and its API key were
    // gone. The key is the part that makes this unrecoverable — it is sealed
    // with AndroidKeyStore and never shown again, so "undo" means fetching it
    // from the provider's dashboard a second time. Destructive and
    // irreversible earns the same confirmation "Delete all agent chats"
    // already asks for below.
    var confirmDeleteProvider by remember { mutableStateOf<AgentProviderEntity?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            notice = null
        }
    }

    Scaffold(
        // Keyboard rides under the whole screen (adjustResize semantics).
        modifier = Modifier.imePadding(),
        snackbarHost = {
            // Padded above the system navigation bar. contentWindowInsets is
            // zeroed on this Scaffold, so a bare SnackbarHost would draw UNDER
            // the Back/Home/Recents bar.
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom)
                )
            )
        },
        // Insets are applied EXPLICITLY below (TopAppBar handles the status
        // bar itself) — deterministic, nothing overlaps the nav buttons.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("AI Agent Settings") },
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
                // Above the navigation bar (3-button Back/Home/Recents or
                // gesture hint) and beside display cutouts in landscape.
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .verticalScroll(rememberScrollState())
        ) {
            // ================= Provider management =================
            SectionHeader("AI Provider (OpenAI-compatible)")
            if (controller.providers.isEmpty()) {
                EmptyState(
                    title = "No provider configured",
                    subtitle = "Add Z.ai, OpenAI, OpenRouter, Groq, DeepSeek, Ollama, LM Studio or any custom endpoint."
                )
            }
            // Provider list lives in ONE card (SettingsGroup) — the rows are
            // not bare list items floating on the background anymore.
            SettingsGroup {
                controller.providers.forEach { provider ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onEditProvider(provider) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = controller.settings.defaultProviderId == provider.id,
                            onClick = {
                                controller.setDefault(provider, provider.defaultModel)
                                notice = "Default: ${provider.name}"
                            }
                        )
                        Spacer(Modifier.width(6.dp))
                        // 3-LINE provider card — Name → Model → Base URL, so long
                        // URLs and model ids never cramp into one overlapping
                        // "url · model" line (same fix as the model picker sheet).
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    provider.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (provider.protocol == AgentProviderEntity.PROTOCOL_OPENCODE) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "OpenCode",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                provider.defaultModel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                provider.baseUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { onEditProvider(provider) },
                            modifier = Modifier.semantics { contentDescription = "edit_provider_${provider.id}" }
                        ) { Icon(Icons.Filled.Edit, contentDescription = null) }
                        IconButton(
                            onClick = { confirmDeleteProvider = provider },
                            modifier = Modifier.semantics { contentDescription = "delete_provider_${provider.id}" }
                        ) { Icon(Icons.Filled.Delete, contentDescription = null) }
                    }
                }
            }
            Button(
                onClick = onAddProvider,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .semantics { contentDescription = "add_provider" }
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add provider")
            }

            // ================= Default model =================
            SectionHeader("Default model")
            val defaultProvider = controller.activeProvider
            // Read-only summary row — NOT clickable, no chevron (it used to
            // look tappable while the onClick did nothing). Picking a model
            // happens in the agent panel's provider · model line (see tip).
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Provider · model", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    if (defaultProvider == null) "Not set"
                    else "${defaultProvider.name} · ${controller.activeModel ?: defaultProvider.defaultModel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "Tip: open the AI Agent panel and tap the provider · model line to pick a model fetched from the provider's /models list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // ================= Local AI =================
            // Local AI is app-global (not per-profile): the Ollama server and
            // its models are shared by every profile's agent chats.
            SectionHeader("Local AI")
            val localAiContext = LocalContext.current
            SettingActionRow(
                title = "Local AI (Ollama)",
                subtitle = "Install, import and export on-device models — pause/resume downloads, GPU tuning",
                leadingIcon = Icons.Filled.Memory,
                onClick = { LocalAiActivity.launch(localAiContext, null) }
            )

            // ================= AI tasks =================
            // Scheduled prompts are app-visible (each is tied to a profile,
            // but the list is not per-profile), so the entry sits here next
            // to the other agent settings rather than under a profile.
            SectionHeader("AI tasks")
            SettingActionRow(
                title = "Scheduled tasks",
                subtitle = "Run a prompt against a profile on a schedule — pick the profile, " +
                    "the schedule and what the task may do",
                leadingIcon = Icons.Filled.DateRange,
                onClick = { AiTasksActivity.launch(localAiContext) }
            )

            // ================= Behavior =================
            SectionHeader("Agent behavior")
            SettingSwitchRow(
                title = "Show AI Agent button",
                subtitle = "The floating agent button on the browser screen — hidden by default; the agent stays reachable from the page menu",
                checked = controller.settings.showAgentButton,
                onCheckedChange = { checked ->
                    controller.updateSettings { s -> s.copy(showAgentButton = checked) }
                }
            )
            // Below the button switch because both answer "where is the agent",
            // this one being which window it is drawn in rather than whether it
            // is offered. No parentheses in the title: UiAutomator's By.desc
            // reads its argument as a regular expression.
            SettingSwitchRow(
                title = "Open the agent as its own screen",
                subtitle = "The chat opens full-screen instead of sliding over the page. " +
                    "Off, the page stays visible while the agent works on it",
                checked = controller.settings.chatInOwnScreen,
                onCheckedChange = { checked ->
                    controller.updateSettings { s -> s.copy(chatInOwnScreen = checked) }
                }
            )
            SettingSwitchRow(
                title = "Confirm actions",
                subtitle = "Ask for Allow/Deny before the agent clicks, types or submits",
                checked = controller.settings.confirmActions,
                onCheckedChange = { checked -> controller.updateSettings { s -> s.copy(confirmActions = checked) } }
            )
            // The two 2FA/Notes switches sit together below the confirm switch
            // because they are the same kind of thing — what the agent may
            // reach of the profile's own data — and because both default OFF,
            // so nothing changes for an existing chat or task until the user
            // reads the cost here and opts in.
            SettingSwitchRow(
                title = "Let the agent read the code itself",
                subtitle = "The authenticator codes are sent to your AI provider and saved in the " +
                    "chat's history. Off, the agent can still copy or type a code without seeing it",
                checked = controller.settings.otpDigitsToAgent,
                onCheckedChange = { checked ->
                    controller.updateSettings { s -> s.copy(otpDigitsToAgent = checked) }
                }
            )
            SettingSwitchRow(
                title = "Allow scheduled AI tasks to use 2FA and Notes",
                subtitle = "A task that runs with nobody watching may use the authenticator codes " +
                    "and the notes. A page that talks it into it could then pull a live code",
                checked = controller.settings.taskProfileTools,
                onCheckedChange = { checked ->
                    controller.updateSettings { s -> s.copy(taskProfileTools = checked) }
                }
            )
            SettingSwitchRow(
                title = "Local decision gate",
                subtitle = "Let a local Ollama decision model judge each action first, so routine " +
                    "ones do not interrupt you — configure it below",
                checked = controller.settings.decisionGate,
                onCheckedChange = { checked ->
                    controller.updateSettings { s -> s.copy(decisionGate = checked) }
                }
            )
            // YOLO sits below both rules because it overrides both. The
            // paragraph under the switch is not optional decoration: a switch
            // in a list is easy to leave on, and this is the one setting in
            // the app whose "on" state is indistinguishable from the app
            // working normally.
            // The title carries no parentheses on purpose: UiAutomator's
            // By.desc treats its argument as a regular expression, so a ")"
            // in a switch title would make the E2E selector miss the row.
            SettingSwitchRow(
                title = "YOLO: always allow",
                subtitle = "Never ask. The decision gate is not consulted and no Allow/Deny " +
                    "prompt appears — every click, keystroke and post runs immediately",
                checked = controller.settings.yolo,
                onCheckedChange = { checked -> controller.updateSettings { s -> s.copy(yolo = checked) } }
            )
            if (controller.settings.yolo) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        "YOLO is ON. The agent will click, type, submit and post on any site " +
                            "with no confirmation and no policy check, including sites where you " +
                            "are signed in. Nothing else on this screen limits it — the decision " +
                            "gate and Confirm actions are both bypassed. Turn this off to get " +
                            "them back.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            // Included page = GREEN (user request: "jika include page
            // di-ikutkan maka warna hijau") — local twin of SettingSwitchRow
            // with a green checked switch, matching the panel chip.
            ContextSwitchRow(
                label = "Include current page by default",
                description = "Attach a page snapshot to the first message of each turn",
                semanticsLabel = "Include current page by default switch",
                checked = controller.settings.includePageContext,
                onCheckedChange = { checked -> controller.updateSettings { s -> s.copy(includePageContext = checked) } }
            )
            DefaultContextSection(
                controller = controller,
                onNotice = { notice = it }
            )

            SliderRow(
                label = "Temperature",
                value = controller.settings.temperature.toFloat(),
                valueLabel = controller.settings.temperature.toString(),
                rangeStart = 0f,
                rangeEnd = 1f,
                steps = 9,
                onCommit = { controller.updateSettings { s -> s.copy(temperature = (it * 10).toInt() / 10.0) } }
            )
            SliderRow(
                label = "Max steps per turn",
                value = controller.settings.maxSteps.toFloat(),
                valueLabel = controller.settings.maxSteps.toString(),
                rangeStart = 5f,
                rangeEnd = 50f,
                steps = 8,
                onCommit = { controller.updateSettings { s -> s.copy(maxSteps = it.toInt()) } }
            )

            SystemPromptSection(controller, onApplied = { notice = "System prompt applied" })

            // ================= Retry on error =================
            SectionHeader("Retry on error")
            RetrySection(controller)

            // ================= Local decision gate =================
            SectionHeader("Local decision gate")
            DecisionGateSection(controller, onNotice = { notice = it })

            // ================= Data & privacy =================
            SectionHeader("Data & privacy")
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    "Agent requests go straight from this device to the provider you configured, " +
                        "and Room Browser adds no telemetry of its own. If the profile's proxy " +
                        "setting has \"Route the AI agent\" turned on — it is off by default — " +
                        "those requests go through that proxy instead, and its operator sees " +
                        "them. API keys are encrypted with AndroidKeyStore and never leave the " +
                        "device except as the provider's own Authorization header. Chat sessions " +
                        "are stored per profile and stay local.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SettingActionRow(
                title = "Delete all agent chats",
                subtitle = "Removes the chat history of every profile (providers are kept)",
                onClick = { confirmClearSessions = true }
            )
            Spacer(Modifier.height(48.dp))
        }
    }

    if (confirmClearSessions) {
        AlertDialog(
            onDismissRequest = { confirmClearSessions = false },
            title = { Text("Delete agent chats?") },
            text = { Text("This permanently deletes all agent chats on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    controller.clearAllSessions()
                    confirmClearSessions = false
                    notice = "Agent chats deleted"
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClearSessions = false }) { Text("Cancel") } }
        )
    }

    confirmDeleteProvider?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDeleteProvider = null },
            title = { Text("Remove ${target.name}?") },
            text = {
                Text(
                    "This deletes the endpoint, its default model and its stored API key. " +
                        "The key cannot be recovered — it is encrypted on this device and never " +
                        "shown again, so adding the provider back means pasting the key in afresh."
                )
            },
            // "Remove provider", NOT the plain "Delete" the chats dialog
            // above answers to: two destructive confirmations live on this one
            // screen, and a selector matching "Delete" must never be able to
            // hit whichever of them happens to be open.
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteProvider = null
                    scope.launch {
                        controller.deleteProvider(target.id)
                        notice = "Removed ${target.name}"
                    }
                }) { Text("Remove provider") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteProvider = null }) { Text("Cancel") } }
        )
    }
}

// Green for the INCLUDED state — same user request behind the panel chip
// ("jika include page di-ikutkan maka warna hijau"); mirrors the constants
// in AgentPanel.kt so the chip and this switch always agree.
private val IncludeGreenDarkContainer = Color(0xFF2F6B33)
private val IncludeGreenDarkContent = Color(0xFFD7F5DC)
private val IncludeGreenLightContainer = Color(0xFFB9F6CA)
private val IncludeGreenLightContent = Color(0xFF0A3818)

/**
 * One "does this text go out with my message?" switch.
 *
 * The page snapshot and the saved Default Context are the same control with
 * the same meaning — this text is part of the request — so they are one
 * composable with two labels rather than two rows free to drift apart.
 * Layout, padding and semantics match the shared
 * [com.roombrowser.ui.common.SettingSwitchRow] one-for-one; only the checked
 * switch colors differ.
 *
 * Checked is GREEN because the user asked for an included context to read
 * green ("jika include page di-ikutkan maka warna hijau"). On a screen with
 * several switches, colour is what answers "what am I actually sending?" at
 * a glance.
 */
/** Shared with [DefaultContextActivity]: one green "on" look for context switches. */
@Composable
internal fun ContextSwitchRow(
    label: String,
    description: String,
    semanticsLabel: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val extras = LocalRoomExtras.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { contentDescription = "$semanticsLabel, ${if (checked) "on" else "off"}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = extras.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = if (extras.dark) IncludeGreenDarkContainer else IncludeGreenLightContainer,
                checkedThumbColor = if (extras.dark) IncludeGreenDarkContent else IncludeGreenLightContent,
                uncheckedTrackColor = extras.surfaceAlt,
                uncheckedThumbColor = extras.icon
            )
        )
    }
}

/**
 * Default Context — the standing text the agent carries into EVERY request.
 *
 * WHAT IT IS: a USER message put in front of each turn (see
 * `BrowserAgentController.runTurn`), not part of the system prompt. Two
 * consequences follow, and the row states both rather than leaving the user
 * to guess: the text is visible in the conversation the provider receives,
 * and it can never outrank the app's own instructions — a page cannot
 * promote its own text into this slot.
 *
 * THE SWITCH AND THE TEXT ARE SEPARATE ON PURPOSE. Muting a standing context
 * for one session must not mean deleting it and retyping it afterwards, so
 * "off" keeps the text and only
 * [com.roombrowser.data.repo.AgentSettings.useDefaultContext] changes.
 * Blank text makes the switch inert rather than an error: turning it ON with
 * nothing saved opens the editor instead, so the switch can never claim to be
 * sending something it is not.
 *
 * THE TEXT IS WRITTEN IN [DefaultContextActivity], not on this screen. An
 * inline box in a scrolling settings list was the wrong place to write a
 * paragraph, and two editors meant two places the same standing instruction
 * could be changed from. This row states what is saved and whether it is sent.
 */
@Composable
private fun DefaultContextSection(
    controller: AgentSettingsController,
    onNotice: (String) -> Unit
) {
    val saved = controller.settings.defaultContext
    val active = controller.settings.useDefaultContext && saved.isNotBlank()
    val extras = LocalRoomExtras.current
    val context = LocalContext.current

    ContextSwitchRow(
        label = "Default context",
        description = if (saved.isBlank()) {
            "Save a standing message the agent applies to every request"
        } else {
            "Send your saved standing context with every request"
        },
        semanticsLabel = "Default context switch",
        checked = controller.settings.useDefaultContext,
        onCheckedChange = { on ->
            if (on && saved.isBlank()) {
                // Nothing saved to send: open the editor instead of leaving a
                // switch on over an empty message.
                DefaultContextActivity.launch(context)
            } else {
                controller.updateSettings { s -> s.copy(useDefaultContext = on) }
                onNotice(if (on) "Default context on" else "Default context off")
            }
        }
    )

    Text(
        text = when {
            saved.isBlank() -> "No default context saved"
            active -> "Active: $saved"
            else -> "Saved, not sent: $saved"
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (active) {
            if (extras.dark) IncludeGreenDarkContent else IncludeGreenLightContent
        } else {
            extras.textSecondary
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { contentDescription = "agent_default_context_state" }
    )
    SettingActionRow(
        title = "Edit default context",
        subtitle = when (controller.settings.contextPresets.size) {
            0 -> "Write the text here; save presets to re-use one"
            1 -> "1 preset saved"
            else -> "${controller.settings.contextPresets.size} presets saved"
        },
        leadingIcon = Icons.Filled.Edit,
        onClick = { DefaultContextActivity.launch(context) }
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueLabel: String,
    rangeStart: Float,
    rangeEnd: Float,
    steps: Int,
    onCommit: (Float) -> Unit
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = rangeStart..rangeEnd,
            steps = steps
        )
    }
}

/**
 * The local decision gate — model picker plus the user's policy text.
 *
 * The honest paragraph at the top is not decoration: this screen is the only
 * place that can tell the user what the gate is and is not, and the failure
 * it can hand them (Confirm actions off + gate unreachable = ungated actions)
 * is invisible from the switch alone.
 */
@Composable
private fun DecisionGateSection(
    controller: AgentSettingsController,
    onNotice: (String) -> Unit
) {
    val provider = controller.decisionProvider
    val model = controller.decisionModel
    var pickerOpen by remember { mutableStateOf(false) }
    var policy by remember(controller.settings.decisionPolicy) {
        mutableStateOf(controller.settings.decisionPolicy)
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            "A local Ollama decision model (Ollama 0.35 or later) can judge each action before " +
                "the agent takes it, so Confirm actions only interrupts you when an action is not " +
                "routine. It runs on your own machine, and the action plus the page it is on never " +
                "leave it. It is not a security boundary — a 9B model reading your policy can be " +
                "wrong, and with Confirm actions switched OFF an unreachable gate means actions run " +
                "ungated, exactly as they did before this existed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
    }
    SettingActionRow(
        title = "Decision model",
        subtitle = when {
            provider == null -> "Add an Ollama provider first — only Ollama can serve a decision model"
            model.isNullOrBlank() -> "${provider.name} · no model chosen yet"
            else -> "${provider.name} · $model"
        },
        onClick = { if (provider != null) pickerOpen = true }
    )

    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("Policy (optional)", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Your own rules for the gate, in plain language. Empty uses the built-in policy: " +
                "routine reading and typing allowed, anything that spends, signs in, posts, " +
                "uploads or deletes confirmed, and anything outside your request refused.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedTextField(
            value = policy,
            onValueChange = { policy = it },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "decision_gate_policy" },
            placeholder = { Text("e.g. never sign in anywhere; posting is fine, but ask first") },
            minLines = 3
        )
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                controller.updateSettings { it.copy(decisionPolicy = policy.trim()) }
                onNotice("Local gate policy applied")
            }) { Text("Apply") }
            TextButton(onClick = {
                policy = ""
                controller.updateSettings { it.copy(decisionPolicy = "") }
            }) { Text("Reset to built-in") }
        }
    }

    if (pickerOpen && provider != null) {
        DecisionModelDialog(
            provider = provider,
            controller = controller,
            selected = model,
            onDismiss = { pickerOpen = false },
            onPick = { tag ->
                controller.setDecisionModel(tag)
                pickerOpen = false
            }
        )
    }
}

/**
 * Picks the decision model for [provider] from what is actually installed.
 *
 * The list comes from the server (`GET /api/tags`), so it shows `nimble`,
 * `tev1` and `tev1:0.8b` once they are pulled, and nothing when they are
 * not — which is the useful answer, since a decision model that is not
 * installed cannot be asked anything. A typed tag is accepted as well: the
 * fetch can fail for reasons that have nothing to do with the model, and
 * being unable to type one would make the screen a dead end.
 */
@Composable
private fun DecisionModelDialog(
    provider: AgentProviderEntity,
    controller: AgentSettingsController,
    selected: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    var models by remember(provider.id) { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember(provider.id) { mutableStateOf(true) }
    var typed by remember(provider.id) { mutableStateOf("") }
    var page by remember(provider.id) { mutableStateOf(0) }

    LaunchedEffect(provider.id) {
        loading = true
        models = controller.installedModels(provider)
        loading = false
    }

    // A SHEET, not an AlertDialog: the body is a list of the models actually
    // installed on the server plus a field to type one, which is the app's
    // model-picker shape (the agent panel's ModelPickerSheet is the same
    // control). An AlertDialog sized to its content turns a long tag list
    // into a cramped scroll box a few rows tall.
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                // The body scrolls: with many installed models the typed-tag
                // field and its button would otherwise sit below the fold.
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Decision model")
            Text(
                if (loading) "Reading ${provider.name}…"
                else if (models.isEmpty()) {
                    "No models listed by ${provider.name}. Pull one first (for example " +
                        "`ollama pull nimble`), or type its tag below."
                } else "Installed on ${provider.name}:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Paging.slice(models, page).forEach { tag ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(tag) }
                        .padding(vertical = 10.dp)
                        .semantics { contentDescription = "decision_model_$tag" },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = tag == selected, onClick = { onPick(tag) })
                    Spacer(Modifier.width(8.dp))
                    Text(tag, style = MaterialTheme.typography.bodyLarge)
                }
            }
            PagingFooter(
                page = page,
                total = models.size,
                onPage = { page = it },
                semanticsPrefix = "decision_model_page"
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "decision_model_input" },
                singleLine = true,
                placeholder = { Text("nimble") }
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onPick(typed.trim()) },
                    enabled = typed.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Use tag") }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Retry-on-error: the switch, the attempt budget, and the status codes.
 *
 * The two groups are separated by a heading rather than merged into one
 * alphabetical list because they mean different things. A transient status
 * describes a condition that can be gone a second later, so a retry is the
 * obvious move; a permanent one describes a request the server has already
 * judged, so a retry buys the same verdict for the user's money. The second
 * group is offered because the user asked for the whole table — it is not
 * offered as a recommendation, and the heading says so.
 *
 * Nothing here is hidden behind a dialog: a retry spends the user's quota at
 * a third party, and the codes it will spend it on are the setting, so they
 * are on the screen where the switch that enables them is.
 */
@Composable
private fun RetrySection(controller: AgentSettingsController) {
    SettingSwitchRow(
        title = "Retry failed requests",
        subtitle = "Send the request again when a provider call fails, up to the " +
            "attempt limit below — off by default, because a retry spends your " +
            "quota at the provider",
        checked = controller.settings.retryOnError,
        onCheckedChange = { checked ->
            controller.updateSettings { s -> s.copy(retryOnError = checked) }
        }
    )
    if (!controller.settings.retryOnError) return

    RetryAttemptsField(controller)

    RetryDelayField(controller)

    SettingSwitchRow(
        title = "Retry connection failures",
        subtitle = "Also retry when no status came back at all — refused connection, " +
            "DNS, TLS, or a stream that died before the first token",
        checked = controller.settings.retryConnectionFailures,
        onCheckedChange = { checked ->
            controller.updateSettings { s -> s.copy(retryConnectionFailures = checked) }
        }
    )

    RetryCodeGroup(
        controller = controller,
        heading = "Transient — retrying usually helps",
        codes = RetryCodes.TRANSIENT
    )
    RetryCodeGroup(
        controller = controller,
        heading = "Permanent — the server already decided; a retry usually " +
            "just costs another call",
        codes = RetryCodes.PERMANENT
    )
}

/**
 * The attempt budget as a typed field, not a slider: the useful values are
 * 1-10 and a slider on a ten-stop range cannot express "3" any better than
 * the number three can.
 *
 * The text is committed only while it parses to a value in range, so a
 * half-typed "1" on the way to "10" writes a setting the loop can honour
 * rather than a zero or a 100. Out of range leaves it visibly in error and
 * the stored value untouched.
 */
@Composable
private fun RetryAttemptsField(controller: AgentSettingsController) {
    var text by remember { mutableStateOf(controller.settings.retryMaxAttempts.toString()) }
    val parsed = text.toIntOrNull()
    val inRange = parsed != null &&
        parsed in RetryPolicy.MIN_ATTEMPTS..RetryPolicy.MAX_ATTEMPTS
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { typed ->
                text = typed.filter { it.isDigit() }.take(2)
                text.toIntOrNull()
                    ?.takeIf { it in RetryPolicy.MIN_ATTEMPTS..RetryPolicy.MAX_ATTEMPTS }
                    ?.let { n ->
                        controller.updateSettings { s -> s.copy(retryMaxAttempts = n) }
                    }
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "retry_attempts" },
            label = { Text("Attempts") },
            supportingText = {
                Text(
                    if (inRange) {
                        "Total tries per request, counting the first. " +
                            "1 means no retry. The pause between tries is set below."
                    } else {
                        "Enter a number from ${RetryPolicy.MIN_ATTEMPTS} to " +
                            "${RetryPolicy.MAX_ATTEMPTS}."
                    }
                )
            },
            isError = !inRange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

/**
 * The pause before each retry, in seconds.
 *
 * SECONDS, not milliseconds, because seconds are the unit the decision is
 * made in: "wait six seconds" is a choice, "6000" is a number to translate
 * first. Same typed-field rules as [RetryAttemptsField] — only a value in
 * range is committed, so a half-typed number never writes a setting.
 *
 * The field matters as much as the switch it sits under: the pause is the
 * difference between a retry that clears a rate-limit window and a retry
 * that lands inside the same one.
 */
@Composable
private fun RetryDelayField(controller: AgentSettingsController) {
    var text by remember { mutableStateOf(controller.settings.retryDelaySeconds.toString()) }
    val parsed = text.toIntOrNull()
    val inRange = parsed != null &&
        parsed in RetryPolicy.MIN_DELAY_SECONDS..RetryPolicy.MAX_DELAY_SECONDS
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { typed ->
                text = typed.filter { it.isDigit() }.take(3)
                text.toIntOrNull()
                    ?.takeIf { it in RetryPolicy.MIN_DELAY_SECONDS..RetryPolicy.MAX_DELAY_SECONDS }
                    ?.let { n ->
                        controller.updateSettings { s -> s.copy(retryDelaySeconds = n) }
                    }
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "retry_delay" },
            label = { Text("Pause before retry (seconds)") },
            supportingText = {
                Text(
                    if (inRange) {
                        "How long to wait before trying again. 0 retries " +
                            "immediately; ${RetryPolicy.DEFAULT_DELAY_SECONDS} is the default."
                    } else {
                        "Enter a number from ${RetryPolicy.MIN_DELAY_SECONDS} to " +
                            "${RetryPolicy.MAX_DELAY_SECONDS}."
                    }
                )
            },
            isError = !inRange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

/** One group of status codes, each row an independently ticked checkbox. */
@Composable
private fun RetryCodeGroup(
    controller: AgentSettingsController,
    heading: String,
    codes: List<RetryStatusCode>
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            heading,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        codes.forEach { entry ->
            val checked = entry.code in controller.settings.retryStatusCodes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        controller.updateSettings { s ->
                            // Sorted on write: the stored JSON stays stable,
                            // so an unchanged set does not look like an edit.
                            val next = if (checked) {
                                s.retryStatusCodes - entry.code
                            } else {
                                (s.retryStatusCodes + entry.code).sorted()
                            }
                            s.copy(retryStatusCodes = next)
                        }
                    }
                    .padding(vertical = 8.dp)
                    .semantics { contentDescription = "retry_code_${entry.code}" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    "${entry.code}  ${entry.reason}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun SystemPromptSection(
    controller: AgentSettingsController,
    onApplied: () -> Unit
) {
    var prompt by remember(controller.settings.systemPromptOverride) {
        mutableStateOf(controller.settings.systemPromptOverride ?: "")
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("System prompt (optional override)", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Leave empty to use the built-in browsing-agent prompt.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "agent_system_prompt" },
            placeholder = { Text("Built-in prompt (browsing rules, ref usage, sources…)") },
            minLines = 3
        )
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                controller.updateSettings { it.copy(systemPromptOverride = prompt.trim().ifBlank { null }) }
                onApplied()
            }) { Text("Apply") }
            TextButton(onClick = {
                prompt = ""
                controller.updateSettings { it.copy(systemPromptOverride = null) }
            }) { Text("Reset to built-in") }
        }
    }
}
