package com.roombrowser.agent

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.roombrowser.RoomBrowserApp
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.data.db.AgentSessionEntity
import com.roombrowser.data.net.AppHttpClients
import com.roombrowser.data.repo.AgentSettings
import com.roombrowser.data.repo.AgentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Lightweight AI-settings controller for the AGENT SETTINGS ACTIVITIES
 * (AgentSettingsActivity / AgentProviderEditorActivity /
 * AgentSessionsActivity), which run in the DEFAULT process — no WebView,
 * no agent loop, just provider/model/behavior management.
 *
 * All writes go through the shared Room database (multi-instance
 * invalidation), so the live BrowserAgentController in the ':browser'
 * process observes every change instantly and the floating panel updates
 * without any manual refresh.
 */
class AgentSettingsController(
    application: Application,
    private val profileId: String?
) {

    private val graph = (application as RoomBrowserApp).graph
    private val repo: AgentRepository = graph.agentRepo
    private val appState = graph.appState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Plain HTTP stack is fine here: this client only lists /models while
    // the user is editing a provider. The agent's real turns keep using the
    // DoH-configured client inside the ':browser' process.
    private val callFactory: OkHttpClient = AppHttpClients.agent()

    // ------------------------------------------------------------- UI state

    var providers by mutableStateOf<List<AgentProviderEntity>>(emptyList())
        private set
    var sessions by mutableStateOf<List<AgentSessionEntity>>(emptyList())
        private set
    var settings by mutableStateOf(AgentSettings())
        private set
    var activeProvider by mutableStateOf<AgentProviderEntity?>(null)
        private set
    var activeModel by mutableStateOf<String?>(null)
        private set

    /** The provider being edited (null while loading, or in add-mode). */
    var editing by mutableStateOf<AgentProviderEntity?>(null)
        private set
    /** True once the editing target has been resolved (add-mode resolves immediately). */
    var editingLoaded by mutableStateOf(false)
        private set

    // ------------------------------------------------------------- lifecycle

    fun start() {
        scope.launch {
            repo.providers.collect { list ->
                providers = list
                refreshSelection()
            }
        }
        scope.launch {
            appState.agentSettings.collect {
                settings = it
                refreshSelection()
            }
        }
        if (!profileId.isNullOrBlank()) {
            scope.launch {
                repo.observeSessions(profileId).collect { sessions = it }
            }
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    /**
     * Renders this profile's agent chats for export.
     *
     * Messages are read on demand rather than taken from [sessions]: that
     * list carries only the chat headers, and an export that quietly
     * contained the titles and none of the conversation would be worse than
     * no export at all.
     *
     * A null profile id is the "no profile selected" state the rest of this
     * screen already handles — the document still renders, and says so by
     * being empty, rather than throwing at the user who just picked a file.
     */
    suspend fun buildChatExport(now: Long = System.currentTimeMillis()): String {
        val pid = profileId.orEmpty()
        val chats = if (pid.isBlank()) emptyList() else repo.sessions(pid)
        val sessionModels = mutableListOf<AgentChatExport.Session>()
        for (session in chats) {
            val messages = repo.messages(session.id).map { m ->
                AgentChatExport.Message(
                    role = m.role,
                    at = m.createdAt,
                    content = m.content,
                    toolName = m.toolName,
                    toolArgs = m.toolArgs,
                    toolResult = m.toolResult
                )
            }
            sessionModels.add(
                AgentChatExport.Session(
                    title = session.title,
                    model = session.model,
                    updatedAt = session.updatedAt,
                    messages = messages
                )
            )
        }
        val label = runCatching {
            graph.profileRepo.profiles().firstOrNull { it.id.value == pid }?.name
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "this profile"
        return AgentChatExport.render(
            header = AgentChatExport.Header(
                profileLabel = label,
                exportedAt = now,
                providers = providers.map { "${it.name} (${it.protocol})" }
            ),
            sessions = sessionModels
        )
    }

    private fun refreshSelection() {
        val list = providers
        val preferred = settings.defaultProviderId?.let { id -> list.firstOrNull { it.id == id } }
        val provider = preferred ?: list.firstOrNull()
        activeProvider = provider
        val model = settings.defaultModel?.takeIf { it.isNotBlank() && provider != null }
            ?: provider?.defaultModel?.takeIf { it.isNotBlank() }
        activeModel = model
    }

    // ------------------------------------------------------------- providers

    /** Resolves the editing target for [id] (0 = add mode). */
    fun loadEditing(id: Long) {
        if (id <= 0L) {
            editing = null
            editingLoaded = true
            return
        }
        scope.launch {
            editing = runCatching { repo.provider(id) }.getOrNull()
            editingLoaded = true
        }
    }

    /**
     * Persists a provider (encrypting the API key); blank key keeps the old one
     * on edit. [toolMode] is a `ToolMode` name, or null to keep whatever an
     * edited provider already has — only the provider editor offers the choice.
     */
    suspend fun saveProvider(
        id: Long?,
        name: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        protocol: String = AgentProviderEntity.PROTOCOL_OPENAI,
        toolMode: String? = null
    ): Result<AgentProviderEntity> =
        AgentProviderStore.save(repo, id, name, baseUrl, apiKey, defaultModel, protocol, toolMode)

    fun deleteProvider(id: Long) {
        scope.launch {
            runCatching { repo.deleteProvider(id) }
            if (settings.defaultProviderId == id) {
                saveSettings { it.copy(defaultProviderId = null, defaultModel = null) }
            }
        }
    }

    fun setDefault(provider: AgentProviderEntity, model: String) {
        scope.launch {
            saveSettings { it.copy(defaultProviderId = provider.id, defaultModel = model) }
        }
    }

    /**
     * Suspending variant of [setDefault] for callers that must guarantee the
     * write commits even when their own scope is being cancelled (the editor
     * wraps this in withContext(NonCancellable) so finishing the activity
     * mid-save can never lose the provider or the default selection).
     */
    suspend fun setDefaultNow(provider: AgentProviderEntity, model: String) {
        saveSettings { it.copy(defaultProviderId = provider.id, defaultModel = model) }
    }

    fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        scope.launch { saveSettings(transform) }
    }

    /**
     * Read-modify-write against the STORED blob, then mirror the result.
     *
     * [transform] deliberately receives the persisted settings and NOT
     * [settings]. This controller runs in the default process while the
     * browser panel runs in ':browser', and each keeps its own copy of the
     * whole blob; a transform applied to this copy would write the other
     * process's changes back out as they were when the copy was refreshed —
     * turning the standing context off because this screen had not heard that
     * the panel had just turned it on. See
     * [com.roombrowser.data.repo.AppStateRepository.updateAgentSettings].
     */
    private suspend fun saveSettings(transform: (AgentSettings) -> AgentSettings) {
        runCatching { settings = appState.updateAgentSettings(transform) }
    }

    /**
     * Fetches the model list for a provider that is still being EDITED
     * (uses the typed base URL + API key, not stored credentials).
     */
    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String,
        protocol: String = AgentProviderEntity.PROTOCOL_OPENAI
    ): List<String> {
        val gateway = AgentGateways.forProvider(callFactory, baseUrl, apiKey, protocol)
        return gateway.listModels()
    }

    // ------------------------------------------------- local decision gate

    /**
     * Providers that could host a decision model.
     *
     * Only `OLLAMA`: `/v1/systemone` needs a GGUF runner that can score
     * answer tokens, and Ollama refuses cloud and MLX/Safetensors models for
     * it. Offering an OpenAI-compatible or Anthropic provider here would be
     * offering a choice that cannot work.
     */
    val decisionProviders: List<AgentProviderEntity>
        get() = providers.filter { it.protocol == AgentProviderEntity.PROTOCOL_OLLAMA }

    /**
     * The provider the gate will use — the one the user picked, else the only
     * Ollama provider there is. Mirrors `BrowserAgentController`'s own
     * resolution so the screen shows the same provider the agent will talk
     * to, including after a profile switch invalidates a stored id.
     */
    val decisionProvider: AgentProviderEntity?
        get() = settings.decisionProviderId
            ?.let { id -> decisionProviders.firstOrNull { it.id == id } }
            ?: decisionProviders.firstOrNull()

    /** The model tag the gate will ask, resolving the provider's default. */
    val decisionModel: String?
        get() = settings.decisionModel.takeIf { it.isNotBlank() }
            ?: decisionProvider?.defaultModel?.takeIf { it.isNotBlank() }

    /**
     * Installed models on [provider], for the decision-model picker.
     *
     * Returns an empty list on any failure — an unreachable server is a
     * normal state for this screen, and the picker still accepts a typed
     * tag, so a failed fetch must not read as "no models exist".
     */
    suspend fun installedModels(provider: AgentProviderEntity): List<String> =
        runCatching {
            fetchModels(
                baseUrl = provider.baseUrl,
                apiKey = KeyStoreCrypto.decrypt(provider.apiKeyEnc).orEmpty(),
                protocol = provider.protocol
            )
        }.getOrDefault(emptyList())

    fun setDecisionProvider(id: Long?) {
        updateSettings { it.copy(decisionProviderId = id) }
    }

    fun setDecisionModel(model: String) {
        updateSettings { it.copy(decisionModel = model.trim()) }
    }

    // ------------------------------------------------------------- sessions

    fun deleteSession(id: Long) {
        scope.launch { runCatching { repo.deleteSession(id) } }
    }

    /** Deletes every agent chat across ALL profiles (providers are kept). */
    fun clearAllSessions() {
        scope.launch { runCatching { repo.deleteAllSessions() } }
    }
}
