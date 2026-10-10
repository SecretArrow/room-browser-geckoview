@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.roombrowser.agent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.roombrowser.RoomBrowserApp
import com.roombrowser.agent.AgentSettingsController
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.domain.agent.ToolCapableModels
import com.roombrowser.domain.agent.ToolMode
import com.roombrowser.domain.paging.Paging
import com.roombrowser.localai.store.OnDeviceModelStore
import com.roombrowser.ui.common.PagingFooter
import com.roombrowser.ui.common.RoomBrowserTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A model a preset ships as a ready-to-pick chip. [reasoning] marks models
 * that expose a thinking/reasoning channel (surfaced as a " · reasoning" hint);
 * it is display-only — any model id can still be typed or fetched.
 */
data class PresetModel(val id: String, val label: String, val reasoning: Boolean = false)

/**
 * Well-known provider presets (editable after selection). Some presets also
 * ship a known model pick-list ([models]); the list is only a convenience —
 * the user can still fetch the provider's `/models` or type any id by hand.
 */
data class ProviderPreset(
    val label: String,
    val url: String,
    val protocol: String,
    val models: List<PresetModel> = emptyList()
)

val PROVIDER_PRESETS: List<ProviderPreset> = listOf(
    ProviderPreset("Z.ai", "https://api.z.ai/api/paas/v4", "OPENAI"),
    ProviderPreset("OpenAI", "https://api.openai.com/v1", "OPENAI"),
    ProviderPreset("OpenRouter", "https://openrouter.ai/api/v1", "OPENAI"),
    ProviderPreset("Groq", "https://api.groq.com/openai/v1", "OPENAI"),
    ProviderPreset("DeepSeek", "https://api.deepseek.com/v1", "OPENAI"),
    ProviderPreset("Mistral", "https://api.mistral.ai/v1", "OPENAI"),
    ProviderPreset("Together", "https://api.together.xyz/v1", "OPENAI"),
    // AgentRouter speaks the @ai-sdk/anthropic shape (POST /messages). The
    // gateway probes once and falls back to the OpenAI shape if the endpoint
    // is really OpenAI-compatible, so this one preset covers both. It ships a
    // known model; more can be fetched or typed.
    ProviderPreset(
        "AgentRouter",
        "https://agentrouter.org/v1",
        AgentProviderEntity.PROTOCOL_ANTHROPIC,
        models = listOf(
            PresetModel("deepseek-v4-flash", "DeepSeek V4 Flash", reasoning = true)
        )
    ),
    ProviderPreset("Ollama (OpenAI /v1)", "http://localhost:11434/v1", "OPENAI"),
    ProviderPreset("Ollama native", "http://localhost:11434", "OLLAMA"),
    ProviderPreset("LM Studio (this device)", "http://localhost:1234/v1", "OPENAI"),
    ProviderPreset("OpenCode (opencode serve)", "http://localhost:4096", "OPENCODE"),
    ProviderPreset("Custom", "", "OPENAI")
)

/**
 * Add / edit an AI provider — its OWN activity (own window, own IME
 * handling, own back-stack entry): manual input (name, base URL, API key),
 * presets, and live model discovery from the provider's /models endpoint.
 *
 * EXTRA_PROVIDER_ID > 0 → edit mode (loads the stored provider);
 * absent / 0 → add mode.
 */
class AgentProviderEditorActivity : ComponentActivity() {

    private lateinit var controller: AgentSettingsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: insets are consumed by the Compose UI below — the
        // form never runs under the system Back / Home / Recents buttons.
        enableEdgeToEdge()
        val providerId = intent.getLongExtra(EXTRA_PROVIDER_ID, 0L)
        controller = AgentSettingsController(application, profileId = null)
        controller.start()
        controller.loadEditing(providerId)
        setContent {
            RoomBrowserTheme {
                if (controller.editingLoaded) {
                    ProviderEditorRoot(
                        controller = controller,
                        editing = controller.editing,
                        onDone = { finish() }
                    )
                } else {
                    Column(
                        // The app-wide inset pattern: system bars UNION the
                        // display cutout (never systemBars alone — a cutout
                        // can reach further in than the status bar).
                        Modifier.fillMaxSize().windowInsetsPadding(
                            WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROVIDER_ID = "provider_id"
    }
}

// ===========================================================================
// Provider editor (add / edit) — manual input + presets + model discovery
// ===========================================================================

@Composable
private fun ProviderEditorRoot(
    controller: AgentSettingsController,
    editing: AgentProviderEntity?,
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // rememberSaveable for everything the user TYPED or PICKED: plain remember
    // dies with the composition, so a rotation (or a process death while the
    // user was off looking up an API key in another app) silently emptied a
    // half-filled form and dropped them back on "Add AI provider". The ONE
    // deliberate exception is [apiKey] below — read its comment.
    var name by rememberSaveable(editing) { mutableStateOf(editing?.name ?: "") }
    var baseUrl by rememberSaveable(editing) { mutableStateOf(editing?.baseUrl ?: "") }
    var protocol by rememberSaveable(editing) {
        mutableStateOf(
            // Explicit mapping — a plain else→OPENAI would silently CORRUPT
            // an edited Ollama-native provider (its protocol would flip to
            // the OpenAI transport on open). LOCAL gets the same guard.
            when (editing?.protocol) {
                AgentProviderEntity.PROTOCOL_OPENCODE -> AgentProviderEntity.PROTOCOL_OPENCODE
                AgentProviderEntity.PROTOCOL_OLLAMA -> AgentProviderEntity.PROTOCOL_OLLAMA
                AgentProviderEntity.PROTOCOL_LOCAL -> AgentProviderEntity.PROTOCOL_LOCAL
                AgentProviderEntity.PROTOCOL_ANTHROPIC -> AgentProviderEntity.PROTOCOL_ANTHROPIC
                else -> AgentProviderEntity.PROTOCOL_OPENAI
            }
        )
    }
    // On-device providers carry a PLACEHOLDER base URL ("local://engine") —
    // pre-fill it whenever the protocol is LOCAL and nothing is typed yet,
    // so the field always satisfies the (non-blank) save validation.
    LaunchedEffect(protocol) {
        if (protocol == AgentProviderEntity.PROTOCOL_LOCAL && baseUrl.isBlank()) {
            baseUrl = "local://engine"
        }
    }
    /* The API key stays on PLAIN remember on purpose: saved instance state is
     * handed to the system, which writes it to disk (and into the recents
     * snapshot), so a secret typed here must never enter it. Losing it on a
     * rotation is the right trade — the field is optional, an edited provider
     * keeps its stored ciphertext when left blank, and the label says "(kept)".
     * Same rule as the password vault's own screens. */
    var apiKey by remember(editing) { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var toolMode by rememberSaveable(editing) { mutableStateOf(ToolMode.fromStored(editing?.toolMode)) }
    /* NOT saved, and not an oversight: [models] is whatever the provider's
     * /models answered and [presetModels] is derived from the preset table, so
     * both are re-fetchable facts rather than user input (and PresetModel is
     * neither Parcelable nor Serializable — saving it would throw). [fetching],
     * [fetchError] and [saveError] describe an operation that did NOT survive
     * the restore: a restored `fetching = true` would spin a progress
     * indicator over a request that no longer exists. */
    var models by remember(editing) { mutableStateOf<List<String>>(emptyList()) }
    var modelQuery by rememberSaveable(editing) { mutableStateOf("") }
    /* Which page of the (possibly filtered) model list is on screen. Not
     * saved: it is a scroll position, and a restored page number over a
     * re-fetched list is a page of a list that no longer exists. */
    var modelPage by remember(editing) { mutableStateOf(0) }
    /* Whether the fetched list is narrowed to models that can call tools. On
     * by default, because a model that cannot act fails silently — it answers
     * in prose and the run ends without doing anything. */
    var toolsOnly by rememberSaveable(editing) { mutableStateOf(true) }
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var model by rememberSaveable(editing) { mutableStateOf(editing?.defaultModel ?: "") }
    var saveError by remember { mutableStateOf<String?>(null) }
    var hasKey by remember(editing) { mutableStateOf(editing != null && editing.apiKeyEnc.isNotBlank()) }
    // Preset-supplied model pick-list for the current provider (e.g. AgentRouter
    // ships DeepSeek V4 Flash). Seeded on open when the edited provider matches a
    // preset that ships models; refreshed when a preset chip is tapped, cleared
    // when the protocol is changed by hand.
    var presetModels by remember(editing) {
        mutableStateOf(
            PROVIDER_PRESETS.firstOrNull {
                it.models.isNotEmpty() && it.url == editing?.baseUrl && it.protocol == editing?.protocol
            }?.models ?: emptyList()
        )
    }

    Scaffold(
        // adjustResize semantics: fields, chips and the save row all ride
        // above the keyboard.
        modifier = Modifier.imePadding(),
        // Insets are applied EXPLICITLY below (TopAppBar handles the status
        // bar itself; the sticky save row pads itself above the nav bar) —
        // deterministic on every API level.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (editing == null) "Add AI provider" else "Edit provider") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                }
            )
        },
        // STICKY SAVE ROW — pinned above the navigation bar, always fully
        // visible and reachable. CI evidence (run 36313948566): when the
        // save buttons lived at the END of the scrollable form, the small
        // CI screen scrolled them under the system navigation bar — the
        // tap landed at y=619 inside the nav-bar zone and the click never
        // reached the button. A pinned bottom bar also saves users from
        // scrolling a long form just to confirm.
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                // saveError surfaces right above the sticky row (always visible).
                saveError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.systemBars
                                .union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            // The persistence write must outlive this window —
                            // the user may tap Back in the same breath as Save —
                            // so it runs on the graph's APPLICATION scope, which
                            // is owned by something (AppGraph) and documented as
                            // outliving a screen. GlobalScope used to stand here:
                            // structurally detached from onDestroy AND from
                            // controller.shutdown(), it kept this composition's
                            // state and the controller's OkHttpClient reachable
                            // for the whole write and then ran its completion
                            // handlers against a window that could already be
                            // dead. saveProvider/setDefaultNow are plain suspend
                            // functions that do NOT use the controller's own
                            // scope (and are internally NonCancellable), so a
                            // shut-down controller still commits them.
                            // The typed values are snapshotted FIRST so the
                            // long-lived job never reads Compose state.
                            val appScope =
                                (context.applicationContext as RoomBrowserApp).graph.appScope
                            val draftId = editing?.id
                            val draftName = name
                            val draftBaseUrl = baseUrl
                            val draftApiKey = apiKey
                            val draftModel = model
                            val draftProtocol = protocol
                            val draftToolMode = toolMode.name
                            appScope.launch {
                                android.util.Log.d(
                                    "RoomAgent",
                                    "save start: name=$draftName model=$draftModel url=$draftBaseUrl"
                                )
                                val result = controller.saveProvider(
                                    id = draftId,
                                    name = draftName,
                                    baseUrl = draftBaseUrl,
                                    apiKey = draftApiKey,
                                    defaultModel = draftModel,
                                    protocol = draftProtocol,
                                    toolMode = draftToolMode
                                )
                                result.fold(
                                    onSuccess = { provider ->
                                        android.util.Log.d(
                                            "RoomAgent",
                                            "save ok: provider=${provider.id} ${provider.name}"
                                        )
                                        // Make the freshly saved provider the default.
                                        controller.setDefaultNow(provider, provider.defaultModel)
                                        // The REACTION belongs to the activity:
                                        // `scope` dies with the composition, so a
                                        // gone editor simply never runs this, and
                                        // the lifecycle check keeps finish() off
                                        // an already destroyed window.
                                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                                            scope.launch { onDone() }
                                        }
                                    },
                                    onFailure = {
                                        android.util.Log.e("RoomAgent", "save failed", it)
                                        val message = it.message ?: "could not save"
                                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                                            scope.launch { saveError = message }
                                        }
                                    }
                                )
                            }
                        },
                        enabled = name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (editing == null) "Save provider" else "Save changes") }
                    OutlinedButton(onClick = onDone) { Text("Cancel") }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Beside display cutouts in landscape; the bottom is already
                // reserved by the sticky save row (Scaffold content padding).
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal)
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ---- Provider type ----
            Text("Provider type", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            // FlowRow (like the presets below): four protocol chips wrap on
            // narrow screens instead of being clipped off-screen.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = protocol == AgentProviderEntity.PROTOCOL_OPENAI,
                    onClick = { protocol = AgentProviderEntity.PROTOCOL_OPENAI; models = emptyList(); presetModels = emptyList(); fetchError = null },
                    label = { Text("OpenAI-compatible API") },
                    modifier = Modifier.semantics { contentDescription = "provider_protocol_openai" }
                )
                FilterChip(
                    selected = protocol == AgentProviderEntity.PROTOCOL_ANTHROPIC,
                    onClick = { protocol = AgentProviderEntity.PROTOCOL_ANTHROPIC; models = emptyList(); presetModels = emptyList(); fetchError = null },
                    label = { Text("Anthropic Messages API") },
                    modifier = Modifier.semantics { contentDescription = "provider_protocol_anthropic" }
                )
                FilterChip(
                    selected = protocol == AgentProviderEntity.PROTOCOL_OPENCODE,
                    onClick = { protocol = AgentProviderEntity.PROTOCOL_OPENCODE; models = emptyList(); presetModels = emptyList(); fetchError = null },
                    label = { Text("OpenCode server") },
                    modifier = Modifier.semantics { contentDescription = "provider_protocol_opencode" }
                )
                FilterChip(
                    selected = protocol == AgentProviderEntity.PROTOCOL_OLLAMA,
                    onClick = { protocol = AgentProviderEntity.PROTOCOL_OLLAMA; models = emptyList(); presetModels = emptyList(); fetchError = null },
                    label = { Text("Ollama native") },
                    modifier = Modifier.semantics { contentDescription = "provider_protocol_ollama" }
                )
                FilterChip(
                    selected = protocol == AgentProviderEntity.PROTOCOL_LOCAL,
                    onClick = {
                        protocol = AgentProviderEntity.PROTOCOL_LOCAL
                        models = emptyList()
                        presetModels = emptyList()
                        fetchError = null
                        if (baseUrl.isBlank()) baseUrl = "local://engine"
                    },
                    label = { Text("On-device") },
                    modifier = Modifier.semantics { contentDescription = "provider_protocol_local" }
                )
            }
            // Tool calling is available on EVERY provider — natively, or over
            // the text contract when the provider will not take a tools array.
            // The on-device engine has no wire channel at all, so it is always
            // text and is not offered a choice (AgentProviderStore stores TEXT
            // for it, whichever chip was last tapped).
            if (protocol != AgentProviderEntity.PROTOCOL_LOCAL) {
                Spacer(Modifier.height(10.dp))
                Text("Tool calling", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ToolMode.entries.forEach { mode ->
                        FilterChip(
                            selected = toolMode == mode,
                            onClick = { toolMode = mode },
                            label = { Text(mode.label) },
                            modifier = Modifier.semantics {
                                contentDescription = "provider_tool_mode_${mode.name.lowercase()}"
                            }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when (toolMode) {
                        ToolMode.AUTO ->
                            "Use the provider's own tool calling, and switch to the text protocol " +
                                "if it rejects a tools array. The safe default."
                        ToolMode.NATIVE ->
                            "Always send a tools array. Best quality and streams as it writes — " +
                                "but a provider that does not support it will fail."
                        ToolMode.TEXT ->
                            "Send no tools array: the catalogue travels in the prompt and the " +
                                "reply is read back as a call. Works on providers that reject or " +
                                "ignore tools; the answer appears when the turn ends."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (protocol == AgentProviderEntity.PROTOCOL_OPENCODE) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Run `opencode serve` on your PC/NAS (default port 4096), then point this to it, " +
                        "e.g. http://192.168.1.10:4096 — keep it on a private LAN or VPN (Tailscale), " +
                        "never expose the port to the internet. The model list is fetched from GET /provider.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (protocol == AgentProviderEntity.PROTOCOL_OLLAMA) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Native Ollama protocol (/api/chat): the Local AI performance settings " +
                        "(GPU layers, threads, context) are applied to every chat, and the model list " +
                        "comes from the server's installed models. For the OpenAI-compatible /v1 " +
                        "endpoint use the other Ollama preset.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Inline row (NOT SettingActionRow): the scroll column already
                // pads 16dp horizontally — SettingActionRow would add its own
                // 16dp, pushing this entry to a 32dp inset, misaligned with the
                // fields above it.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { LocalAiActivity.launch(context, null) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Manage local models →", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Open the Local AI menu — install, pause/resume, import/export",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (protocol == AgentProviderEntity.PROTOCOL_LOCAL) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "On-device engine: the model runs INSIDE the app through the embedded " +
                        "llama.cpp runtime — no server, no network, no API key (the base URL is a " +
                        "placeholder and the key field is unused). Pick the model in Local AI → " +
                        "On-device engine (import a .gguf file or download one); Fetch models " +
                        "lists the models stored on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Inline row (NOT SettingActionRow): same alignment fix as the
                // Ollama entry above — flush with the form's 16dp column padding.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { LocalAiActivity.launch(context, null) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Manage on-device models →", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Open the Local AI screen — import, download, test models",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (protocol == AgentProviderEntity.PROTOCOL_ANTHROPIC) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Anthropic Messages API (POST /messages, x-api-key + anthropic-version) — " +
                        "spoken by aggregators like AgentRouter through the @ai-sdk/anthropic SDK. " +
                        "The first turn probes this endpoint; if it actually serves the OpenAI shape " +
                        "it transparently falls back to /chat/completions, so either kind of aggregator " +
                        "works. Pick a model below, fetch the provider's list, or type any id.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))

            // ---- Presets ----
            Text("Presets", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PROVIDER_PRESETS.forEach { preset ->
                    FilterChip(
                        selected = preset.url.isNotBlank() && preset.url == baseUrl && preset.protocol == protocol,
                        onClick = {
                            if (preset.url.isNotBlank()) {
                                baseUrl = preset.url
                                protocol = preset.protocol
                                if (name.isBlank()) name = preset.label.substringBefore(" (")
                                // Adopt this preset's known models and drop any
                                // list fetched from a previous provider.
                                presetModels = preset.models
                                models = emptyList()
                                fetchError = null
                                // Prefill the model when empty so a preset like
                                // AgentRouter works without a fetch round-trip.
                                if (model.isBlank()) preset.models.firstOrNull()?.let { model = it.id }
                            }
                        },
                        label = { Text(preset.label) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---- Manual fields ----
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Provider name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "provider_name_field" }
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; models = emptyList(); fetchError = null },
                label = {
                    Text(
                        when (protocol) {
                            AgentProviderEntity.PROTOCOL_OPENCODE ->
                                "Base URL (opencode serve, e.g. http://192.168.1.10:4096)"
                            AgentProviderEntity.PROTOCOL_OLLAMA ->
                                "Base URL (Ollama server, e.g. http://localhost:11434)"
                            AgentProviderEntity.PROTOCOL_LOCAL ->
                                "Base URL (placeholder — local://engine, unused)"
                            AgentProviderEntity.PROTOCOL_ANTHROPIC ->
                                "Base URL (Anthropic Messages API, e.g. https://agentrouter.org/v1)"
                            else ->
                                "Base URL (OpenAI-compatible, e.g. https://api.z.ai/api/paas/v4)"
                        }
                    )
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "provider_url_field" }
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; hasKey = it.isNotBlank() },
                label = {
                    Text(
                        when {
                            editing != null && hasKey && apiKey.isBlank() -> "API key (kept)"
                            apiKey.isBlank() -> "API key (optional — local servers need none)"
                            else -> "API key"
                        }
                    )
                },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (keyVisible) "Hide API key" else "Show API key"
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "provider_key_field" }
            )
            Spacer(Modifier.height(12.dp))

            // ---- Preset models (pick-list shipped with the provider) ----
            if (presetModels.isNotEmpty()) {
                Text("Models for this provider", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Known models for this preset — tap to select, or fetch/type any other id below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.semantics { contentDescription = "provider_preset_models" }
                ) {
                    presetModels.forEach { pm ->
                        FilterChip(
                            selected = pm.id == model,
                            onClick = { model = pm.id },
                            label = {
                                Text(
                                    if (pm.reasoning) "${pm.label} · reasoning" else pm.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ---- Fetch models ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        // LOCAL: no HTTP at all — the "models" of the on-device
                        // engine are the .gguf files in app-private storage;
                        // list them from the store instead of the network.
                        if (protocol == AgentProviderEntity.PROTOCOL_LOCAL) {
                            scope.launch {
                                fetching = true
                                fetchError = null
                                try {
                                    val ids = withContext(Dispatchers.IO) {
                                        OnDeviceModelStore(context).list().map { it.id }
                                    }
                                    if (ids.isEmpty()) {
                                        fetchError =
                                            "No on-device models yet — import or download one in Local AI first."
                                    } else {
                                        models = ids
                                        if (model !in models) model = models.first()
                                        modelQuery = ""
                                    }
                                } catch (t: Throwable) {
                                    fetchError = t.message ?: "listing failed"
                                } finally {
                                    fetching = false
                                }
                            }
                        } else {
                            scope.launch {
                                fetching = true
                                fetchError = null
                                try {
                                    models = controller.fetchModels(baseUrl, apiKey, protocol)
                                    if (models.isNotEmpty() && model !in models) model = models.first()
                                    modelQuery = ""
                                } catch (t: Throwable) {
                                    fetchError = t.message ?: "fetch failed"
                                } finally {
                                    fetching = false
                                }
                            }
                        }
                    },
                    enabled = baseUrl.isNotBlank() && !fetching
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Fetch models")
                }
                Spacer(Modifier.width(12.dp))
                if (fetching) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (protocol == AgentProviderEntity.PROTOCOL_LOCAL) "Reading on-device models…"
                        else "Contacting provider…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            fetchError?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Could not fetch models: $it\nYou can still type the model id manually below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (models.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                /* Which of the returned ids are worth offering. On-device
                 * models are exempt: that list is the user's own library, and
                 * hiding a model they imported deliberately would be worse
                 * than offering one that turns out to be too small to act. */
                val toolCapable =
                    if (protocol == AgentProviderEntity.PROTOCOL_LOCAL) emptyList()
                    else ToolCapableModels.filter(models)
                /* An unrecognised list must not become an empty picker: the
                 * filter is a convenience, and a provider whose naming this
                 * does not know still has to be usable. */
                val filtering = toolsOnly && toolCapable.isNotEmpty()
                val pool = if (filtering) toolCapable else models
                Text(
                    "Models returned by the provider:",
                    style = MaterialTheme.typography.labelLarge
                )
                if (protocol != AgentProviderEntity.PROTOCOL_LOCAL) {
                    Spacer(Modifier.height(4.dp))
                    FilterChip(
                        selected = toolsOnly,
                        onClick = { toolsOnly = !toolsOnly },
                        label = { Text("Only models that can call tools") },
                        modifier = Modifier.semantics {
                            contentDescription = "provider_tools_only_chip"
                        }
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when {
                            filtering ->
                                "Showing ${toolCapable.size} of ${models.size} — the rest can " +
                                    "describe an action but not take one. Tap the chip to see all."
                            toolsOnly ->
                                "None of these ${models.size} ids is a known tool-calling " +
                                    "family, so all of them are listed."
                            else -> "Showing all ${models.size}."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                // Model search — providers expose long model lists; typing a
                // few characters (e.g. "glm" or "mini") filters them live.
                OutlinedTextField(
                    value = modelQuery,
                    onValueChange = { modelQuery = it },
                    placeholder = { Text("Search models…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (modelQuery.isNotEmpty()) {
                            IconButton(onClick = { modelQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear model search", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "provider_model_search_field" }
                )
                Spacer(Modifier.height(6.dp))
                val visibleModels = if (modelQuery.isBlank()) pool
                else pool.filter { it.contains(modelQuery.trim(), ignoreCase = true) }
                // A new filter starts at the first page; a list that merely
                // shrank must not leave the view past its new end.
                LaunchedEffect(modelQuery, toolsOnly) { modelPage = 0 }
                LaunchedEffect(visibleModels.size) {
                    modelPage = Paging.clampPage(modelPage, visibleModels.size)
                }
                if (visibleModels.isEmpty()) {
                    Text(
                        "No models match “${modelQuery.trim()}” — clear the search to see all ${pool.size}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "${visibleModels.size} of ${pool.size} models",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.semantics { contentDescription = "provider_models_list" }
                    ) {
                        Paging.slice(visibleModels, modelPage).forEach { candidate ->
                            FilterChip(
                                selected = candidate == model,
                                onClick = { model = candidate },
                                label = { Text(candidate, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                    PagingFooter(
                        page = modelPage,
                        total = visibleModels.size,
                        onPage = { modelPage = it },
                        semanticsPrefix = "provider_model_page"
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model id (required)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "provider_model_field" }
            )

            // (The Save / Cancel row is the Scaffold's sticky bottomBar —
            // always visible above the navigation bar. saveError shows there.)
            Spacer(Modifier.height(24.dp))
        }
    }
}
