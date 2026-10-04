package com.roombrowser.agent

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.roombrowser.RoomBrowserApp
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.data.db.AgentMessageEntity
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.data.db.AgentSessionEntity
import com.roombrowser.data.repo.AgentSettings
import com.roombrowser.domain.agent.ActionGate
import com.roombrowser.domain.agent.ActionVerdict
import com.roombrowser.domain.agent.AgentEvent
import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.AgentLoop
import com.roombrowser.domain.agent.AgentPrompts
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ChatMessage
import com.roombrowser.domain.agent.LocalAiTuning
import com.roombrowser.domain.agent.formatDurationMs
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.SearchEngines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.time.ZoneId
import kotlin.coroutines.resume

/** Display model for one row of the agent chat. */
sealed interface AgentEntry {
    data class User(val text: String, val at: Long) : AgentEntry
    data class Assistant(val text: String, val thinking: String, val streaming: Boolean, val at: Long) : AgentEntry
    data class Tool(
        val callId: String,
        val name: String,
        val label: String,
        val running: Boolean,
        val ok: Boolean,
        val summary: String,
        val at: Long
    ) : AgentEntry

    data class Notice(val text: String, val error: Boolean, val at: Long) : AgentEntry
}

/** A user-attached file for the agent turn. [text] is the extracted text content (null = binary/metadata-only). */
data class AgentAttachment(
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val text: String?
)

/** Caps how much attachment text is inlined across ONE turn (~60k chars). */
private const val ATTACHMENT_INLINE_BUDGET = 60_000

/**
 * Renders attachments into a prompt block. Text-like files are inlined (with
 * a header), binaries contribute name/size only. Caps total inline text
 * (~60k chars) — later text attachments degrade to metadata lines.
 *
 * Pure function (no Android deps) so it is unit-testable on the JVM.
 *
 * `internal`, like [formatSize] below: this is a prompt-assembly detail of the
 * controller, and it was public only by omission — nothing outside this module
 * ever called it, so the wider visibility published a signature no caller
 * wanted and no one could change freely. Not `private`, because the unit test
 * (AgentAttachmentsTest) exercises it directly, and the test source set is a
 * friend of this module.
 */
internal fun renderAttachments(attachments: List<AgentAttachment>): String {
    if (attachments.isEmpty()) return ""
    val sections = mutableListOf<String>()
    var inlineChars = 0
    attachments.forEach { attachment ->
        val header = "[Attached file: ${attachment.name} — ${attachment.mime}, ${formatSize(attachment.sizeBytes)}]"
        val content = attachment.text
        when {
            content == null ->
                sections.add("$header — binary file, content not inlined")
            inlineChars + content.length > ATTACHMENT_INLINE_BUDGET ->
                sections.add("$header — (skipped: attachment budget exceeded)")
            else -> {
                inlineChars += content.length
                sections.add("$header\n$content")
            }
        }
    }
    return sections.joinToString("\n\n")
}

/** Formats a byte count as B / KB / MB (1 decimal, dot separator). */
internal fun formatSize(bytes: Long): String = when {
    bytes < 0L -> "?"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/**
 * Assembles the message list for ONE turn.
 *
 * Pure function (no Android deps) so the ORDER is unit-testable on the JVM.
 * The order is the contract, and every part of it is load-bearing:
 *
 *   system | prior turns | page snapshot | standing context | attachments | request
 *
 * The standing context is re-sent on EVERY turn rather than once when it was
 * written, because a provider keeps no memory between turns: a context that
 * only rides the first request is gone by the third.
 *
 * It sits BELOW the page snapshot so "this is my standing instruction" is the
 * most recent thing the model read before the actual request, and ABOVE the
 * request itself so the request remains the last word.
 *
 * It is a USER message, never merged into the system prompt: the system prompt
 * is the app's own instruction set, and text the user can retype at any time
 * does not belong in the slot that defines what the agent fundamentally is.
 *
 * The switch and the text are checked separately on purpose. [AgentSettings]
 * keeps "off" and "empty" as distinct states so muting a context does not mean
 * deleting it, which means a switch left ON over blank text is reachable — and
 * must send nothing rather than an empty "Standing context:" block.
 *
 * [pageSnapshot] is null when the user did not include the page.
 *
 * Returns a MUTABLE list, and that is part of the contract rather than a
 * convenience: [AgentLoop.runTurn] appends the tool calls and tool results it
 * makes to this very list as the turn proceeds, so the caller's history and
 * the model's history stay the same object. Handing it an immutable list does
 * not compile — which is how this signature was caught.
 */
internal fun buildTurnHistory(
    prompt: String,
    priorTurns: List<ChatMessage>,
    pageSnapshot: String?,
    settings: AgentSettings,
    attachments: List<AgentAttachment>,
    request: String
): MutableList<ChatMessage> {
    val history = mutableListOf(ChatMessage(role = "system", content = prompt))
    history.addAll(priorTurns)
    if (pageSnapshot != null) {
        history.add(ChatMessage(role = "user", content = "$pageSnapshot\n\n(The user's request follows.)"))
    }
    val standing = settings.defaultContext.trim()
    if (settings.useDefaultContext && standing.isNotEmpty()) {
        history.add(
            ChatMessage(
                role = "user",
                content = "Standing context — apply it to this and every " +
                    "following request until I say otherwise:\n$standing"
            )
        )
    }
    renderAttachments(attachments).takeIf { it.isNotEmpty() }?.let {
        history.add(ChatMessage(role = "user", content = it))
    }
    history.add(ChatMessage(role = "user", content = request))
    return history
}

/**
 * What the user answered at the approval prompt.
 *
 * [AlwaysAllow] is the third button and the only answer that outlives the
 * action it was given for: it turns on [AgentSettings.yolo], so nothing asks
 * again until the user turns it back off. It is deliberately the answer that
 * is hardest to reach — last in the row, labelled with what it actually does
 * rather than with a joke — because it is the one that removes every check
 * there is.
 */
enum class ApprovalAnswer { Allow, AlwaysAllow, Deny }

/** A pending action that waits for the user's Allow/Deny/Always-allow answer. */
data class AgentApproval(
    val name: String,
    val label: String,
    val at: Long,
    val respond: (ApprovalAnswer) -> Unit
)

/**
 * BrowserAgentController — ties everything together for ONE profile:
 *  - provider/model selection (settings UI state)
 *  - chat session lifecycle (create / continue / history)
 *  - the agent loop with streaming UI updates
 *  - action approvals when "confirm actions" is enabled
 *  - persistence of messages
 *
 * The controller runs in the ':browser' process (it needs the WebView).
 */
class BrowserAgentController(
    application: Application,
    private val profileId: ProfileId,
    private val vm: BrowserViewModel,
    httpClient: OkHttpClient
) {

    private val graph = (application as RoomBrowserApp).graph
    private val appContext: android.content.Context = application.applicationContext
    private val repo = graph.agentRepo
    private val appState = graph.appState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var callFactory: OkHttpClient = tuned(httpClient)

    // ------------------------------------------------------------- UI state

    var entries by mutableStateOf<List<AgentEntry>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set
    var statusLine by mutableStateOf<String?>(null)
        private set
    var approval by mutableStateOf<AgentApproval?>(null)
        private set
    var activeSessionId by mutableStateOf<Long?>(null)
        private set
    /**
     * The tab whose conversation is on screen, or null while none is
     * resolved yet. Read by the panel to notice that the chat it is showing
     * is not the one belonging to the tab behind it (which happens while a
     * turn runs — see [onActiveTabChanged]).
     */
    var conversationTabId by mutableStateOf<String?>(null)
        private set
    var providers by mutableStateOf<List<AgentProviderEntity>>(emptyList())
        private set
    var sessions by mutableStateOf<List<AgentSessionEntity>>(emptyList())
        private set
    var settings by mutableStateOf(AgentSettings())
        private set
    /**
     * Local AI (Ollama) tuning, live-collected from app_state so /api/chat
     * options (num_ctx/num_gpu/num_thread/keep_alive) track the Local AI
     * screen in real time while an agent turn is being built.
     */
    private var localAiTuning by mutableStateOf(LocalAiTuning())
    var activeProvider by mutableStateOf<AgentProviderEntity?>(null)
        private set
    var activeModel by mutableStateOf<String?>(null)
        private set
    var modelsLoading by mutableStateOf(false)
        private set
    var modelsError by mutableStateOf<String?>(null)
        private set

    /** Cached /models results per provider id. */
    private val modelCache = HashMap<Long, List<String>>()
    /** Decrypted API keys — memory only, never persisted. */
    private val apiKeyCache = HashMap<Long, String>()

    private var turnJob: Job? = null

    /**
     * Which request owns the panel, as a counter rather than a flag.
     *
     * Bumped by everything that takes the panel over — a tab resolve, a turn
     * being sent — so a resolve that was still reading the database when the
     * panel changed hands can tell that its answer is stale and drop it. See
     * [showTabConversation].
     */
    private var conversationRequest = 0L

    /**
     * Whether this turn has already told the user that the local decision
     * gate could not answer. Reset per turn; see [gateFailure].
     */
    private var gateFailureNoted = false

    val messages = MutableStateFlow<String?>(null)

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
        scope.launch {
            appState.localAiTuning.collect { localAiTuning = it }
        }
        scope.launch {
            repo.observeSessions(profileId.value).collect { sessions = it }
        }
        // The panel follows the browser to whichever tab is on screen, so
        // each tab keeps its own conversation. snapshotFlow keys off the
        // Compose state itself, so this fires on a user tab switch, on the
        // tab closing, and on the first restore alike.
        scope.launch {
            snapshotFlow { vm.activeTabId }.collect { onActiveTabChanged(it) }
        }
    }

    /**
     * Defensive cross-process re-sync: re-reads providers + agent settings
     * DIRECTLY from Room. Room's multi-instance invalidation normally
     * delivers writes from the default process (settings activities) to this
     * ':browser' process within moments, but on slow/emulator filesystems the
     * ping can occasionally be lost — the panel would then keep showing a
     * stale "No provider configured" state. Called whenever the agent panel
     * is opened/expanded AND from BrowserActivity.onResume so the freshly
     * configured provider is always picked up immediately.
     */
    fun refreshProviders() {
        scope.launch {
            runCatching {
                val list = repo.providers()
                val snap = appState.agentSettingsSnapshot()
                android.util.Log.d(
                    "RoomAgent",
                    "refreshProviders: ${list.size} providers=${list.map { it.name }} " +
                        "default=${snap.defaultProviderId}/${snap.defaultModel}"
                )
                providers = list
                settings = snap
                refreshSelection()
            }.onFailure {
                android.util.Log.e("RoomAgent", "refreshProviders failed", it)
            }
        }
    }

    fun updateClient(client: OkHttpClient) {
        callFactory = tuned(client)
    }

    fun shutdown() {
        turnJob?.cancel()
        AgentForeground.stopCurrentTurn = null
        AgentForeground.finish()
        scope.cancel()
    }

    private fun tuned(client: OkHttpClient): OkHttpClient = client.newBuilder().build()

    private fun refreshSelection() {
        val list = providers
        val preferred = settings.defaultProviderId?.let { id -> list.firstOrNull { it.id == id } }
        val provider = preferred ?: list.firstOrNull()
        activeProvider = provider
        val model = settings.defaultModel?.takeIf { it.isNotBlank() && provider != null }
            ?: provider?.defaultModel?.takeIf { it.isNotBlank() }
        activeModel = model
    }

    // ------------------------------------------------------------- provider & model

    fun apiKeyFor(provider: AgentProviderEntity): String? {
        if (provider.apiKeyEnc.isBlank()) return ""
        apiKeyCache[provider.id]?.let { return it }
        val key = KeyStoreCrypto.decrypt(provider.apiKeyEnc)
        if (key != null) apiKeyCache[provider.id] = key
        return key
    }

    /**
     * Persists a provider (encrypting the API key); blank key keeps the old one
     * on edit. [toolMode] is a `ToolMode` name, or null to keep whatever an
     * edited provider already has (see [AgentProviderStore.save]).
     */
    suspend fun saveProvider(
        id: Long?,
        name: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        protocol: String = AgentProviderEntity.PROTOCOL_OPENAI,
        toolMode: String? = null
    ): Result<AgentProviderEntity> {
        val result = AgentProviderStore.save(repo, id, name, baseUrl, apiKey, defaultModel, protocol, toolMode)
        if (result.isSuccess && id != null) apiKeyCache.remove(id)
        return result
    }

    suspend fun deleteProvider(id: Long) {
        repo.deleteProvider(id)
        apiKeyCache.remove(id)
        modelCache.remove(id)
        if (settings.defaultProviderId == id) {
            // Against the STORED blob, never this mirror: clearing the default
            // must not drag every setting changed since the mirror was filled
            // back to its old value (see AppStateRepository.updateAgentSettings).
            settings = appState.updateAgentSettings {
                it.copy(defaultProviderId = null, defaultModel = null)
            }
        }
    }

    /** Fetches the provider's /models list (cached unless [force]). */
    suspend fun modelsFor(provider: AgentProviderEntity, force: Boolean = false): List<String> {
        if (!force) modelCache[provider.id]?.let { return it }
        modelsLoading = true
        modelsError = null
        try {
            val key = apiKeyFor(provider).orEmpty()
            val gateway = AgentGateways.forProvider(
                callFactory, provider, key,
                appContext = appContext,
                retry = settings.retryPolicy()
            )
            val models = gateway.listModels()
            modelCache[provider.id] = models
            return models
        } catch (t: Throwable) {
            modelsError = t.friendlyMessage()
            throw t
        } finally {
            modelsLoading = false
        }
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
        modelsLoading = true
        modelsError = null
        try {
            val gateway = AgentGateways.forProvider(
                callFactory, baseUrl, apiKey, protocol,
                appContext = appContext,
                retry = settings.retryPolicy()
            )
            return gateway.listModels()
        } catch (t: Throwable) {
            modelsError = t.friendlyMessage()
            throw t
        } finally {
            modelsLoading = false
        }
    }

    fun setDefault(provider: AgentProviderEntity, model: String) {
        scope.launch {
            settings = appState.updateAgentSettings {
                it.copy(defaultProviderId = provider.id, defaultModel = model)
            }
            activeProvider = provider
            activeModel = model
            messages.value = "Agent set to ${provider.name} · $model"
        }
    }

    /**
     * Applies [transform] to the STORED settings and mirrors the result.
     *
     * The transform runs against the persisted blob, never against [settings]:
     * see [com.roombrowser.data.repo.AppStateRepository.updateAgentSettings]
     * for why an in-memory base silently reverts other writers' fields.
     */
    fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        scope.launch { settings = appState.updateAgentSettings(transform) }
    }

    // ------------------------------------------------------------- sessions

    /**
     * Starts a NEW conversation in the current tab.
     *
     * The chat this tab was showing is detached, not deleted: it goes back to
     * the history list (reachable from the panel's history button) and the
     * tab is left with no conversation, so the next message begins a fresh
     * one. Without the detach the tab would still resolve to the old chat and
     * this button would appear to do nothing.
     */
    fun newSession() {
        if (running) return
        // Takes the panel: any tab resolve still reading is now stale.
        conversationRequest++
        val previous = activeSessionId
        activeSessionId = null
        entries = emptyList()
        if (previous != null) {
            scope.launch { repo.bindSessionToTab(previous, "") }
        }
    }

    /**
     * Opens a chat from the history list IN THE CURRENT TAB.
     *
     * One tab shows one conversation, so adopting a chat moves it here: the
     * chat this tab was showing is detached (still in the list, no longer
     * this tab's) and [id] takes its place. A chat picked while no tab is
     * resolved is left as it was rather than detached from the tab that owns
     * it — moving a conversation is only ever the user's doing.
     */
    fun openSession(id: Long) {
        if (running) return
        scope.launch {
            val session = repo.session(id) ?: return@launch
            // Takes the panel: any tab resolve still reading is now stale.
            conversationRequest++
            val tabId = vm.activeTabId
            if (tabId != null) {
                val previous = activeSessionId
                if (previous != null && previous != id) repo.bindSessionToTab(previous, "")
                repo.bindSessionToTab(id, tabId)
                conversationTabId = tabId
            }
            activeSessionId = session.id
            entries = repo.messages(id).mapNotNull(::entryFromRow)
        }
    }

    /**
     * Points the panel at [tabId]'s conversation, creating nothing yet: a tab
     * with no chat of its own shows an empty panel, and its chat row is
     * written by the first message sent from it.
     *
     * Whoever takes the panel bumps [conversationRequest], and a resolve that
     * finds the counter moved while it was reading applies nothing. Two
     * things can overtake it, and both would be destructive: a faster tab
     * switch (which would leave the wrong conversation on screen with nothing
     * to say so) and a message being sent (which would erase the message the
     * user just sent, because `entries` is the one list the panel draws).
     */
    private suspend fun showTabConversation(tabId: String) {
        val token = ++conversationRequest
        val session = repo.sessionForTab(profileId.value, tabId)
        if (token != conversationRequest || vm.activeTabId != tabId) return
        conversationTabId = tabId
        activeSessionId = session?.id
        entries = session?.let { repo.messages(it.id).mapNotNull(::entryFromRow) } ?: emptyList()
    }

    /**
     * Follows the user to the tab they just opened.
     *
     * While a turn is RUNNING this does nothing, deliberately. `entries` is
     * one list for the whole controller, so re-pointing it mid-turn would
     * erase the running turn's progress from the screen — and the turn is
     * usually the reason the panel is open at all. The panel therefore stays
     * with the turn and re-binds when it ends (see [runTurn]); the header
     * says so meanwhile.
     */
    private fun onActiveTabChanged(tabId: String?) {
        if (running || tabId == null || tabId == conversationTabId) return
        scope.launch { showTabConversation(tabId) }
    }

    fun deleteSession(id: Long) {
        scope.launch {
            repo.deleteSession(id)
            if (activeSessionId == id) newSession()
        }
    }

    fun clearAllSessions() {
        scope.launch {
            repo.deleteSessionsForProfile(profileId.value)
            newSession()
            messages.value = "Agent sessions cleared"
        }
    }

    private fun entryFromRow(row: AgentMessageEntity): AgentEntry? = when (row.role) {
        "user" -> AgentEntry.User(row.content, row.createdAt)
        "assistant" -> AgentEntry.Assistant(row.content, "", streaming = false, row.createdAt)
        "tool" -> AgentEntry.Tool(
            callId = "row_${row.id}",
            name = row.toolName ?: "tool",
            label = AgentTools.describeTool(row.toolName ?: "", row.toolArgs),
            running = false,
            ok = !row.content.startsWith("ERROR:"),
            summary = row.content.take(200),
            at = row.createdAt
        )
        else -> null
    }

    // ------------------------------------------------------------- turn execution

    fun send(text: String, includePage: Boolean, attachments: List<AgentAttachment> = emptyList()) {
        val message = text.trim()
        if (message.isEmpty() || running) return
        val provider = activeProvider ?: run {
            messages.value = "Configure an AI provider first (Agent → settings)"
            return
        }
        val model = activeModel ?: provider.defaultModel
        if (model.isBlank()) {
            messages.value = "Pick a model for ${provider.name} first"
            return
        }
        // The tab is read HERE, on the user's own tap, and not inside the
        // coroutine: runTurn suspends several times before it would get to
        // it, and a tab switched in that window would bind the turn — and
        // its new chat row — to the wrong tab.
        val tabId = vm.activeTabId
        // The turn takes the panel from here on: a tab resolve still in
        // flight must not land on top of it and wipe the message below.
        conversationRequest++
        turnJob = scope.launch { runTurn(message, includePage, attachments, provider, model, tabId) }
    }

    fun stop() {
        val job = turnJob ?: return
        turnJob = null
        job.cancel()
        running = false
        statusLine = null
        approval = null
        entries = entries + AgentEntry.Notice("Stopped by user", error = true, at = System.currentTimeMillis())
    }

    fun respondApproval(answer: ApprovalAnswer) {
        approval?.let { it.respond(answer) }
        approval = null
    }

    private suspend fun runTurn(
        text: String,
        includePage: Boolean,
        attachments: List<AgentAttachment>,
        provider: AgentProviderEntity,
        model: String,
        // The tab the user was on when they sent; see [send].
        tabId: String?
    ) {
        // The turn runs on the STORED settings, not on the [settings] mirror.
        // The mirror starts at defaults and is filled asynchronously, and this
        // class documents that Room's cross-process invalidation "can
        // occasionally be lost" — either way it can be behind at exactly the
        // moment the user sends, which silently drops the standing context
        // (and would equally use a stale system prompt, step budget and
        // temperature). Refreshing here means every later read in this turn —
        // the gate's yolo/confirm checks included — works from one
        // authoritative value.
        runCatching { settings = appState.agentSettingsSnapshot() }
        running = true
        gateFailureNoted = false
        // Background mode: foreground service + wake lock so the turn keeps
        // running when the user leaves the app or the screen turns off.
        AgentForeground.stopCurrentTurn = { stop() }
        AgentForeground.begin(appContext)
        AgentForeground.status("Working: " + text.take(60))
        setStatus(null)
        // The chat bubble (and the persisted row) mention the attached file
        // names; the LLM still receives the raw text plus an attachment block.
        val display = if (attachments.isEmpty()) text
        else text + "\n📎 " + attachments.joinToString(", ") { it.name }
        entries = entries + AgentEntry.User(display, System.currentTimeMillis())
        // YOLO has no prompt to remind anyone it is on, which is exactly why
        // it needs saying: the user turned it on at a prompt, possibly days
        // ago, and every turn since has looked identical to one where the
        // checks were still in place. One line per turn is cheap.
        if (settings.yolo) {
            note(
                "YOLO is on — this turn's actions run without asking. " +
                    "Turn it off in AI Agent settings.",
                error = true
            )
        }
        var streamingIndex = -1
        try {
            // The turn belongs to the tab it was started from. Every tool
            // resolves its engine from this id rather than from whatever is on
            // screen, so a tab switch mid-turn can no longer retarget the rest
            // of it, and the engine is pinned so the live-engine budget cannot
            // evict the page out from under the turn either. Pinned inside the
            // try so the finally below lifts it even if a step from here on
            // throws.
            vm.pinTabForAgent(tabId)
            // "Delete all agent chats" can run in the settings ACTIVITY while
            // a session is active here — verify it still exists, else start a
            // fresh one instead of writing to a dead row (FK safety).
            val sessionId = activeSessionId
                ?.takeIf { runCatching { repo.session(it) != null }.getOrDefault(false) }
                ?: repo.createSession(
                    profileId = profileId.value,
                    title = text.take(64),
                    providerId = provider.id,
                    model = model,
                    // The new chat belongs to this tab, so coming back to the
                    // tab brings the conversation back with it.
                    tabId = tabId.orEmpty()
                ).also {
                    activeSessionId = it
                    tabId?.let { id -> conversationTabId = id }
                }
            repo.addMessage(sessionId, "user", display)

            val apiKey = apiKeyFor(provider).orEmpty()
            val executor = AgentToolExecutor(
                vm = vm,
                tabId = tabId,
                onStatus = { setStatus(it) },
                confirmGate = { name, label -> gate(name, label) },
                // Wallet approvals must not ride the bypassable generic gate.
                walletConfirm = { label -> requestWalletApproval(label) }
            )
            // Only the native Ollama protocol consumes the tuning; the other
            // gateways ignore it (default null keeps their wire format intact).
            val retry = settings.retryPolicy()
            val gateway = AgentGateways.forProvider(
                callFactory,
                provider,
                apiKey,
                tuning = localAiTuning.takeIf { provider.protocol == AgentProviderEntity.PROTOCOL_OLLAMA },
                appContext = appContext,
                retry = retry,
                // The pause is the user's number and can be up to a minute, so
                // it has to be visible: a silent retry that long reads as the
                // app hanging, and the user's answer to a hang is to kill it.
                onRetry = { attempt, reason ->
                    setStatus(
                        "Attempt $attempt failed ($reason) — retrying in " +
                            formatDurationMs(retry.delay.toInt())
                    )
                }
            )
            val engine = SearchEngines.byId(vm.profileSettings().searchEngineId).label
            val prompt = settings.systemPromptOverride?.takeIf { it.isNotBlank() }
                ?: AgentPrompts.render(System.currentTimeMillis(), ZoneId.systemDefault(), engine)
            val config = com.roombrowser.domain.agent.AgentConfig(
                model = model,
                maxSteps = settings.maxSteps,
                temperature = settings.temperature,
                systemPrompt = prompt
            )

            // Rebuild the conversation: system + prior user/assistant rows +
            // this turn. The assembly itself lives in buildTurnHistory, where
            // the order — snapshot, then standing context, then attachments,
            // then the request — is pinned by unit tests.
            val history = buildTurnHistory(
                prompt = prompt,
                priorTurns = repo.messages(sessionId)
                    .filter { it.role == "user" || it.role == "assistant" }
                    .map { ChatMessage(role = it.role, content = it.content) },
                pageSnapshot = if (includePage) executor.snapshotContext() else null,
                settings = settings,
                attachments = attachments,
                request = text
            )

            val loop = AgentLoop(gateway, executor, config)
            loop.runTurn(history) { event ->
                when (event) {
                    is AgentEvent.AssistantText -> {
                        streamingIndex = upsertStreamingAssistant(streamingIndex, event.text, null)
                        setStatus("Writing…")
                    }
                    is AgentEvent.AssistantThinking -> {
                        streamingIndex = upsertStreamingAssistant(streamingIndex, "", event.text)
                        setStatus("Thinking…")
                    }
                    is AgentEvent.ToolStarted -> {
                        streamingIndex = finalizeAssistant(streamingIndex)
                        entries = entries + AgentEntry.Tool(
                            callId = event.id,
                            name = event.name,
                            label = AgentTools.describeTool(event.name, event.argsJson),
                            running = true,
                            ok = true,
                            summary = "",
                            at = System.currentTimeMillis()
                        )
                        setStatus(AgentTools.describeTool(event.name, event.argsJson))
                    }
                    is AgentEvent.ToolFinished -> {
                        updateToolEntry(event.id) { it.copy(running = false, ok = event.ok, summary = event.summary) }
                        setStatus("Step done")
                        repo.addMessage(
                            sessionId, "tool",
                            (if (event.ok) "" else "ERROR: ") + event.summary,
                            toolName = event.name,
                            toolResult = event.summary
                        )
                    }
                    is AgentEvent.FinalAnswer -> {
                        streamingIndex = finalizeAssistant(streamingIndex)
                        entries = entries + AgentEntry.Assistant(
                            text = event.text,
                            thinking = "",
                            streaming = false,
                            at = System.currentTimeMillis()
                        )
                        repo.addMessage(sessionId, "assistant", event.text)
                        repo.touchSession(sessionId)
                        setStatus(null)
                    }
                    is AgentEvent.AgentError -> {
                        streamingIndex = finalizeAssistant(streamingIndex)
                        entries = entries + AgentEntry.Notice(
                            text = "Agent error: ${event.message}",
                            error = true,
                            at = System.currentTimeMillis()
                        )
                        repo.addMessage(sessionId, "assistant", "⚠ error: ${event.message}")
                        setStatus(null)
                    }
                    is AgentEvent.Notice -> {
                        entries = entries + AgentEntry.Notice(event.text, error = false, at = System.currentTimeMillis())
                    }
                }
            }
            repo.touchSession(sessionId)
            // The turn finished, so the panel belongs to the user again: if
            // they moved to another tab while it ran, that tab's conversation
            // takes the screen now.
            //
            // On the SUCCESS path only. The catch below appends an error
            // notice and stop() appends "stopped by user", and both of those
            // live in `entries` alone — re-binding there would wipe the very
            // line that explains what happened, and would do it fastest
            // exactly when something went wrong.
            //
            // Only when the user moved, too. A turn that opened its own tab
            // leaves that tab on screen (Tahap 1), and re-binding to it would
            // swap this chat's answer for that tab's empty conversation at
            // the worst possible moment; `executor.currentTabId` is where the
            // turn's work ended, so an active tab that is neither it nor this
            // chat's own tab is the user's doing.
            val nowActive = vm.activeTabId
            if (nowActive != null && nowActive != conversationTabId &&
                nowActive != executor.currentTabId
            ) {
                showTabConversation(nowActive)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            entries = entries + AgentEntry.Notice(
                text = "Agent error: ${t.friendlyMessage()}",
                error = true,
                at = System.currentTimeMillis()
            )
        } finally {
            running = false
            setStatus(null)
            approval = null
            AgentForeground.stopCurrentTurn = null
            AgentForeground.finish()
            // The pin belongs to the turn, so it lifts with the turn —
            // including when the turn threw. Cleared by value and not by id on
            // purpose: the turn may have rebound itself to a tab it opened.
            vm.pinTabForAgent(null)
        }
    }

    // ------------------------------------------------------------- entry helpers

    /** Finds-or-appends the streaming assistant entry; appends text/thinking. */
    private fun upsertStreamingAssistant(index: Int, text: String, thinking: String?): Int {
        val i = if (index >= 0 && index < entries.size &&
            entries[index] is AgentEntry.Assistant && (entries[index] as AgentEntry.Assistant).streaming
        ) index else {
            entries = entries + AgentEntry.Assistant("", "", streaming = true, at = System.currentTimeMillis())
            entries.size - 1
        }
        val current = entries[i] as AgentEntry.Assistant
        entries = entries.toMutableList().apply {
            set(i, current.copy(text = current.text + text, thinking = current.thinking + (thinking ?: "")))
        }
        return i
    }

    /** Marks the streaming assistant entry as finished (if one exists). */
    private fun finalizeAssistant(index: Int): Int {
        if (index >= 0 && index < entries.size) {
            val entry = entries[index]
            if (entry is AgentEntry.Assistant && entry.streaming) {
                entries = entries.toMutableList().apply { set(index, entry.copy(streaming = false)) }
            }
        }
        return -1
    }

    private fun updateToolEntry(callId: String, transform: (AgentEntry.Tool) -> AgentEntry.Tool) {
        val index = entries.indexOfFirst { it is AgentEntry.Tool && it.callId == callId }
        if (index >= 0) {
            entries = entries.toMutableList().apply {
                set(index, transform(get(index) as AgentEntry.Tool))
            }
        }
    }

    // ------------------------------------------------------------- approvals

    /** Sets the in-app status line and mirrors it to the background notification. */
    private fun setStatus(text: String?) {
        statusLine = text
        AgentForeground.status(text ?: "Working…")
    }

    /**
     * Asks the user about one action under the Confirm actions rule.
     *
     * Reached only when the local gate declined to settle the action itself.
     * A timeout and a cancelled turn both deny: the failure mode of an
     * unanswered prompt must never be that the agent proceeds.
     */
    private suspend fun requestApproval(name: String, label: String): ApprovalAnswer {
        if (!settings.confirmActions) return ApprovalAnswer.Allow
        setStatus("Approve? $label")
        return try {
            withTimeout(APPROVAL_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    approval = AgentApproval(name, label, SystemClock.elapsedRealtime()) { answer ->
                        if (continuation.isActive) continuation.resume(answer)
                    }
                }
            }
        } catch (ce: CancellationException) {
            ApprovalAnswer.Deny // timeout or turn cancelled → deny
        } finally {
            approval = null
        }
    }

    /**
     * Always shows the prompt: unlike [gate]/[requestApproval] it can never
     * return allow without the user seeing it, and every failure denies.
     * "Always allow" is one-time here — there is no wallet-wide trust store.
     */
    private suspend fun requestWalletApproval(label: String): Boolean {
        setStatus("Approve wallet request? ${label.lineSequence().firstOrNull().orEmpty()}")
        return try {
            withTimeout(APPROVAL_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    approval = AgentApproval(WALLET_APPROVAL_NAME, label, SystemClock.elapsedRealtime()) { answer ->
                        if (continuation.isActive) {
                            when (answer) {
                                ApprovalAnswer.Allow -> continuation.resume(true)
                                ApprovalAnswer.AlwaysAllow -> {
                                    note(
                                        "Wallet requests always ask — this one was allowed once, " +
                                            "and the next will ask again.",
                                        error = false
                                    )
                                    continuation.resume(true)
                                }
                                ApprovalAnswer.Deny -> continuation.resume(false)
                            }
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            false
        } finally {
            approval = null
        }
    }

    /**
     * The gate every state-changing tool call passes through.
     *
     * Three rules apply, in this order:
     *
     *  0. **YOLO** ([AgentSettings.yolo]) — every action runs. Nothing below
     *     is consulted, not the model and not the user. It is first because
     *     it is the only rule that is not a decision: it is the user having
     *     said they do not want to be asked. It is reachable from the
     *     approval prompt's third answer as well as from settings, since the
     *     moment a person wants it is the moment they are being interrupted.
     *  1. The **local decision gate** (see [AgentSettings.decisionGate]), when
     *     the user has pointed it at an Ollama decision model. It answers
     *     allow / confirm / deny, and only the first and last are settled
     *     here — "confirm" means the model declined to decide alone, which is
     *     exactly what rule 2 is for.
     *  2. **Confirm actions**, the blanket Allow/Deny prompt that has always
     *     been there. Its third answer, "Always allow", turns rule 0 on.
     *
     * So the gate can only ever *add* a decision. Turning rule 1 on never
     * takes away a prompt the user asked for, with one deliberate exception:
     * an action the blanket rule would have asked about can now run unasked,
     * because the local model read the user's own policy and said it was
     * routine. That is the entire point of the feature, and it is why the
     * policy text is the user's to write.
     *
     * A gate that is switched on and cannot answer is NOT an error the user
     * has to clear — the turn continues under rule 2, and the chat says so.
     * The consequence is worth stating plainly, and `SECURITY.md` does: with
     * **Confirm actions off and the gate unreachable, actions run ungated**,
     * exactly as they did before this feature existed. Confirm actions is the
     * switch that gives a hard guarantee; this one buys fewer interruptions.
     */
    private suspend fun gate(name: String, label: String): ActionVerdict {
        if (settings.yolo) return ActionVerdict.Allow
        localVerdict(name, label)?.let { return it }
        return when (requestApproval(name, label)) {
            ApprovalAnswer.Allow -> ActionVerdict.Allow
            ApprovalAnswer.AlwaysAllow -> {
                updateSettings { it.copy(yolo = true) }
                note(
                    "YOLO on — every action from now on runs without asking, " +
                        "including clicks, typing and posts. Turn it off in AI Agent settings.",
                    error = true
                )
                ActionVerdict.Allow
            }
            ApprovalAnswer.Deny -> ActionVerdict.Deny("the user denied this action")
        }
    }

    /**
     * The local model's verdict, or null when the decision belongs to the
     * user — the gate is off, the model asked for confirmation, or it could
     * not be reached at all.
     */
    private suspend fun localVerdict(name: String, label: String): ActionVerdict? {
        if (!settings.decisionGate) return null
        val provider = decisionProvider()
        val model = (settings.decisionModel.takeIf { it.isNotBlank() }
            ?: provider?.defaultModel?.takeIf { it.isNotBlank() })
        if (provider == null || model.isNullOrBlank()) {
            gateFailure("no Ollama provider and decision model are set")
            return null
        }
        setStatus("Asking the local gate…")
        val client = SystemOneClient(callFactory, provider.baseUrl, apiKeyFor(provider).orEmpty())
        val response = try {
            withTimeoutOrNull(DECISION_TIMEOUT_MS) {
                client.decideAction(
                    model = model,
                    action = label,
                    pageUrl = vm.pageState.url,
                    pageTitle = vm.pageState.title,
                    policy = settings.decisionPolicy
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            gateFailure(t.gateMessage())
            return null
        }
        if (response == null) {
            // Either a cold model load that outran the deadline, or a server
            // that never answered. Both are survivable; see the KDoc above.
            gateFailure("the local gate did not answer within ${DECISION_TIMEOUT_MS / 1000}s")
            return null
        }
        return when (val verdict = ActionGate.verdict(response)) {
            is ActionVerdict.Allow -> verdict
            is ActionVerdict.Deny -> {
                note("Local gate denied \"$label\" — ${verdict.reason}", error = true)
                verdict
            }
            is ActionVerdict.Ask -> {
                // The user is about to be asked; say why, so the prompt does
                // not look like the gate silently failed.
                note("Local gate passed \"$label\" to you — ${verdict.reason}", error = false)
                null
            }
        }
    }

    /**
     * The provider hosting the decision model: the one the user picked, or —
     * because every profile shares these settings and a profile switch can
     * leave the id pointing at a deleted row — the first Ollama provider
     * there is. A decision model is only ever served by a local Ollama 0.35+
     * (the endpoint refuses cloud and MLX weights), so restricting the search
     * to the OLLAMA protocol is what keeps the gate from being "configured"
     * against a provider that could never serve it.
     */
    private fun decisionProvider(): AgentProviderEntity? {
        val ollama = providers.filter { it.protocol == AgentProviderEntity.PROTOCOL_OLLAMA }
        val chosen = settings.decisionProviderId?.let { id -> ollama.firstOrNull { it.id == id } }
        return chosen ?: ollama.firstOrNull()
    }

    /**
     * Reports, once per turn, that the gate could not decide and the action
     * is being handled by the Confirm actions rule instead. Once per turn
     * because the same failure repeats on every action, and a notice per
     * action would bury the conversation.
     */
    private fun gateFailure(reason: String) {
        if (gateFailureNoted) return
        gateFailureNoted = true
        note("Local gate unavailable ($reason) — falling back to Confirm actions", error = true)
    }

    private fun note(text: String, error: Boolean) {
        entries = entries + AgentEntry.Notice(text, error = error, at = System.currentTimeMillis())
    }

    private fun Throwable.friendlyMessage(): String = when (this) {
        is AgentHttpException -> when (code) {
            401, 403 -> "authentication failed ($code) — check the API key"
            404 -> "endpoint not found (404) — check the base URL (…/v1)"
            429 -> "rate limited (429) — try again later"
            in 500..599 -> "provider server error ($code)"
            -1 -> message ?: "network error"
            else -> "HTTP $code ${body.take(120)}"
        }
        else -> message ?: javaClass.simpleName
    }

    /**
     * Why the gate could not decide, in the gate's own terms.
     *
     * Deliberately not [friendlyMessage]: that one is written for the chat
     * endpoint, where a 404 means the base URL is missing its `/v1`. The
     * decision endpoint lives at the server root, so the same status means
     * something else entirely — an Ollama older than 0.35, or a decision
     * model that was never pulled — and telling the user to check a suffix
     * that is not there would send them after the wrong thing.
     */
    private fun Throwable.gateMessage(): String = when (this) {
        is AgentHttpException -> when (code) {
            404 -> "no /v1/systemone on this server — needs Ollama 0.35+ and a pulled decision model"
            413 -> "the decision request was too large"
            401, 403 -> "authentication failed ($code) — check the API key"
            -1 -> message ?: "network error"
            else -> "HTTP $code ${body.take(120)}"
        }
        else -> message ?: javaClass.simpleName
    }

    companion object {
        private const val APPROVAL_TIMEOUT_MS = 120_000L

        /** Name on the wallet approval prompt, distinct from a normal tool gate. */
        private const val WALLET_APPROVAL_NAME = "wallet"

        /**
         * How long one local gate decision may take. Generous enough for the
         * first call of a turn, which may have to load a 9B model off disk
         * (later calls hit `keep_alive` and answer in ~100 ms), and short
         * enough that a server which never answers cannot stall a turn for
         * long — the action then falls back to the Confirm actions rule.
         */
        private const val DECISION_TIMEOUT_MS = 30_000L
    }
}
