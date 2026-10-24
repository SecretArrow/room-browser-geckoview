package com.roombrowser.agent

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.roombrowser.RoomBrowserApp
import com.roombrowser.agent.skills.AgentSkillsTools
import com.roombrowser.agent.skills.SkillInstaller
import com.roombrowser.agent.skills.SkillPrompts
import com.roombrowser.agent.skills.SkillRegistry
import com.roombrowser.browser.BrowserControlApiImpl
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.data.db.AgentMessageEntity
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.data.db.AgentSessionEntity
import com.roombrowser.data.repo.AgentMode
import com.roombrowser.data.repo.AgentSettings
import com.roombrowser.domain.agent.ActionGate
import com.roombrowser.domain.agent.ActionVerdict
import com.roombrowser.domain.agent.AgentEvent
import com.roombrowser.domain.agent.AgentGateway
import com.roombrowser.domain.agent.AgentHttpException
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AgentLoop
import com.roombrowser.domain.agent.AgentPause
import com.roombrowser.domain.agent.AgentPauseLabels
import com.roombrowser.domain.agent.AgentPlan
import com.roombrowser.domain.agent.AgentPrompts
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.AgentVisionSupport
import com.roombrowser.domain.agent.AutoModelPicker
import com.roombrowser.domain.agent.ChatMessage
import com.roombrowser.domain.agent.ChatRequest
import com.roombrowser.domain.agent.LocalAiTuning
import com.roombrowser.domain.agent.SessionApprovals
import com.roombrowser.domain.agent.SiteMemory
import com.roombrowser.domain.agent.TokenUsage
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.UntrustedContent
import com.roombrowser.domain.agent.formatDurationMs
import com.roombrowser.domain.download.DownloadFormat
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.SearchEngines
import com.roombrowser.domain.plugins.PluginCard
import com.roombrowser.domain.plugins.PluginComposePrompt
import com.roombrowser.domain.plugins.PluginGeneratePrompt
import com.roombrowser.domain.plugins.toJson
import com.roombrowser.domain.task.AiTaskPermissions
import com.roombrowser.engine.EngineSession
import com.roombrowser.node.PluginCallRequest
import com.roombrowser.node.PluginChatEntry
import com.roombrowser.node.PluginGenerateRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.time.ZoneId
import java.time.LocalDate
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

    /**
     * One card a plugin asked the chat to show — the plugin's only output
     * that is for the USER rather than the model. [card] carries the clamped
     * domain model and [pluginName] says whose card it is, so the row that
     * renders it and the transcript row that persists it never have to guess
     * what produced them.
     */
    data class Plugin(val card: PluginCard, val pluginName: String, val at: Long) : AgentEntry
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
 * `internal`: this is a prompt-assembly detail of the controller, and it was
 * public only by omission — nothing outside this module ever called it, so the
 * wider visibility published a signature no caller wanted and no one could
 * change freely. Not `private`, because the unit test (AgentAttachmentsTest)
 * exercises it directly, and the test source set is a friend of this module.
 */
internal fun renderAttachments(attachments: List<AgentAttachment>): String {
    if (attachments.isEmpty()) return ""
    val sections = mutableListOf<String>()
    var inlineChars = 0
    attachments.forEach { attachment ->
        val header = "[Attached file: ${attachment.name} — ${attachment.mime}, " +
            "${DownloadFormat.bytes(attachment.sizeBytes)}]"
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

/**
 * Assembles the message list for ONE turn.
 *
 * Pure function (no Android deps) so the ORDER is unit-testable on the JVM.
 * The order is the contract, and every part of it is load-bearing:
 *
 *   system | prior turns | current plan | page snapshot | standing context | attachments | request
 *
 * The plan sits with the prior turns rather than down with the request: it is
 * this conversation's own work-state, so it belongs beside the rows that are
 * the conversation, and the pinned tail — snapshot, standing context, request —
 * is left exactly as it was.
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
    request: String,
    /** The step list this chat last wrote, or empty when it has none. */
    plan: List<AgentPlan.Item> = emptyList()
): MutableList<ChatMessage> {
    val history = mutableListOf(ChatMessage(role = "system", content = prompt))
    history.addAll(priorTurns)
    // Without this a follow-up turn starts from nothing: tool rows are not
    // replayed, so the list is the only record of what the last turn did.
    if (plan.isNotEmpty()) {
        history.add(
            ChatMessage(
                role = "user",
                content = "Your step list for this task so far — keep it current with `plan`, " +
                    "sending the whole list every time:\n" + AgentPlan.render(plan)
            )
        )
    }
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
 * Coalesces the model's streamed deltas into a few transcript writes.
 *
 * WHY: a streamed turn used to pay, for EVERY delta, a full copy of the
 * entries list plus `current.text + delta` — O(tokens × transcript) work
 * that also invalidated the whole chat list in Compose each time, at the
 * 30–80 deltas a second a chatty provider sends. The user cannot read that
 * fast; the list only needs to be right at reading speed. Deltas therefore
 * accumulate in two builders here, and ONE flush hands the accumulated pair
 * to [onFlush] — one entry update per window instead of one per token.
 *
 * THE CONTRACT (the e2e suite reads the transcript, so it is stated here):
 *  - The final text and thinking are EXACTLY the concatenation of all
 *    deltas, in arrival order. A flush drains both builders fully and the
 *    next starts from empty, so nothing is reordered, edited or dropped.
 *    Interleaving text and thinking is safe — they are different fields of
 *    the same entry — and only the per-field order is load-bearing.
 *  - [flush] is called at every boundary that replaces or closes the open
 *    bubble (a tool starting, the final answer, an error, the turn's end),
 *    so the last window's deltas are never stranded in the buffers when
 *    something reads the transcript. A Stop cancels between events and
 *    still lands in the turn's `finally`, where the flush runs first.
 *
 * WHY THE CADENCE IS EVENT-DRIVEN RATHER THAN A TICKER COROUTINE: the
 * listener that feeds this runs inside the gateway's
 * `withContext(Dispatchers.IO)`, so deltas arrive sequentially but on IO
 * threads. A ticker coroutine on the controller's main scope would touch
 * the same builders, and the same captured streaming index, from another
 * thread — racing the very deltas it exists to drain. Keeping every append
 * and flush on the listener's own sequence needs no locks and cannot
 * interleave; the only cost is that a stream gone quiet shows its last
 * sub-window of text when the next delta or boundary arrives, which at
 * these cadences nobody can see.
 *
 * Not thread-safe by design (see above): one instance per turn, created in
 * [BrowserAgentController.runTurn] and never shared. Pure JVM — no Android
 * dependency — so the contract is unit-testable on the JVM.
 */
internal class AgentStreamCoalescer(
    /** Minimum spacing between time-based flushes. */
    private val intervalMs: Long = FLUSH_INTERVAL_MS,
    /**
     * Monotonic-ish milliseconds, injectable so the cadence is testable.
     * nanoTime rather than currentTimeMillis because the wall clock can
     * step backwards under NTP, and a window that runs backwards freezes
     * flushing until the clock catches up.
     */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onFlush: (text: String, thinking: String) -> Unit
) {
    private val text = StringBuilder()
    private val thinking = StringBuilder()

    /** When the last flush happened, in [nowMs] units; null before the first. */
    private var lastFlushMs: Long? = null

    fun appendText(delta: String) {
        text.append(delta)
        flushIfDue()
    }

    fun appendThinking(delta: String) {
        thinking.append(delta)
        flushIfDue()
    }

    /**
     * Drains everything pending into [onFlush] NOW, clock be damned — this
     * is the boundary call. A no-op when nothing is pending, so every
     * boundary can call it unconditionally without paying for the quiet
     * ones.
     */
    fun flush() {
        if (text.isEmpty() && thinking.isEmpty()) return
        val drainedText = text.toString()
        val drainedThinking = thinking.toString()
        text.setLength(0)
        thinking.setLength(0)
        onFlush(drainedText, drainedThinking)
    }

    /**
     * The first delta flushes at once — the bubble should appear with the
     * first token exactly as it did before coalescing — and after that at
     * most one flush per [intervalMs] of delta arrivals.
     */
    private fun flushIfDue() {
        val now = nowMs()
        val last = lastFlushMs
        if (last != null && now - last < intervalMs) return
        lastFlushMs = now
        flush()
    }

    companion object {
        /**
         * ~12 transcript writes a second: fast enough that streamed text
         * still reads as streaming, slow enough that the per-token copy of
         * the whole entries list is gone. The e2e suite waits whole seconds
         * for rows, so a sub-100 ms window is invisible to it — and the
         * FINAL text is not windowed at all (see [flush]), which is the
         * part the suite actually reads.
         */
        const val FLUSH_INTERVAL_MS = 80L
    }
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
 *
 * [AllowForSite] is the narrow answer between the two: this tool, on this
 * origin, for the rest of this chat. It is what a person actually means when
 * they are being asked the same question for the third time, and it is offered
 * only where it can honestly be kept — never for an action that cannot be
 * undone, never for a wallet request, and never for a page with no origin.
 */
enum class ApprovalAnswer { Allow, AllowForSite, AlwaysAllow, Deny }

/** A pending action that waits for the user's Allow/Deny/Always-allow answer. */
data class AgentApproval(
    val name: String,
    val label: String,
    val at: Long,
    /**
     * True for an action that cannot be undone. The prompt then offers Allow
     * and Deny only: there is no answer here that switches the asking off.
     */
    val destructive: Boolean = false,
    /**
     * The origin this action is on, when it can be named. It is what the
     * site-scoped answer would be remembered by, so the prompt offers that
     * answer only when this is known — and says which site it means.
     */
    val origin: String? = null,
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
 * The controller runs in the ':browser' process (it needs the engine).
 */
/**
 * A turn that did not finish — the provider errored, the turn threw, or the
 * user stopped it — kept exactly as it was asked so it can be sent again.
 * Retyping it is the only other way, and the failure is usually the provider's
 * rather than the request's.
 */
data class RetryableTurn(
    val text: String,
    val includePage: Boolean,
    val attachments: List<AgentAttachment>
)

class BrowserAgentController(
    application: Application,
    private val profileId: ProfileId,
    private val vm: BrowserViewModel,
    httpClient: OkHttpClient
) {

    private val graph = (application as RoomBrowserApp).graph
    private val appContext: android.content.Context = application.applicationContext
    private val repo = graph.agentRepo
    private val pluginRepo = graph.pluginRepo
    private val appState = graph.appState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var callFactory: OkHttpClient = tuned(httpClient)

    // ------------------------------------------------------------- UI state
    //
    // EVERY chat-scoped surface lives in a per-session [ChatSessionState], in
    // [sessionStates] below. The controller keeps the map, the accessors the
    // panel renders, and the ONE running turn's bookkeeping. See the class's
    // KDoc for why the old controller-level fields — one shared entries list,
    // one running flag, one plan — could not survive a chat switch mid-turn.

    /** The chat the panel is showing, or null while none is resolved yet. */
    var activeSessionId by mutableStateOf<Long?>(null)
        private set

    /**
     * One state per chat, access-ordered so the eldest loaded window is the
     * first eviction candidate. Guarded by [statesLock]: every touch is
     * main-confined today, but the map is the one structure a future caller
     * could reach from a stream thread, and the lock costs a compare.
     */
    private val sessionStates = LinkedHashMap<Long?, ChatSessionState>(16, 0.75f, true)
    private val statesLock = Any()

    /**
     * Whether ANY turn is running — the controller's single turn slot. The
     * per-chat mirror is [ChatSessionState.running]. The composer, the pill
     * and the [send] guard read this one, because at most one turn exists.
     */
    var running by mutableStateOf(false)
        private set

    /**
     * The chat the RUNNING turn was minted against, or null between turns.
     * The turn's status lines, approvals, plan and rows all route through the
     * state this names — never through [activeSessionId], which belongs to
     * the user and may point somewhere else for the whole turn.
     */
    private var turnOriginSessionId: Long? = null

    private val turnState: ChatSessionState?
        get() = turnOriginSessionId?.let { peekState(it) }

    /** The state of the chat the panel shows, created on demand. */
    private val panelState: ChatSessionState
        get() = stateFor(activeSessionId)

    /**
     * The state of [sessionId]'s chat, created on first ask. Sessions whose
     * state is evicted (see [trimStatesLocked]) simply reload their window
     * from the database on the next ask — the cache is an optimization, the
     * row is the truth.
     */
    fun stateFor(sessionId: Long?): ChatSessionState = synchronized(statesLock) {
        sessionStates.getOrPut(sessionId) {
            ChatSessionState(sessionId).also { trimStatesLocked() }
        }
    }

    /**
     * [sessionId]'s state WITHOUT creating one — for readers that must not
     * allocate (the panel's accessors when a turn is not running, the
     * eviction checks) and for callers probing whether a chat is loaded.
     */
    fun peekState(id: Long?): ChatSessionState? = synchronized(statesLock) { sessionStates[id] }

    /**
     * The LRU cap on held windows. Twelve loaded transcripts cover any
     * browsing session a person actually does; beyond that the eldest
     * non-running, non-visible state falls out and costs a reload when it is
     * wanted again. Never larger than the live-engine budget, for the same
     * reason: a cache bigger than what a session can touch is only memory.
     */
    private fun trimStatesLocked() {
        while (sessionStates.size > SESSION_STATE_CAP) {
            val victim = sessionStates.entries.firstOrNull { (id, state) ->
                id != activeSessionId && !state.running
            } ?: return
            sessionStates.remove(victim.key)
        }
    }

    private fun evictState(id: Long?) {
        synchronized(statesLock) { sessionStates.remove(id) }
    }

    private fun evictAllStates() {
        synchronized(statesLock) { sessionStates.clear() }
    }

    // ------- accessors the panel renders (the chat it is showing, and the
    // running turn's prompt/status wherever the panel has drifted to) -------

    val entries: List<AgentEntry> get() = panelState.entries
    val plan: List<AgentPlan.Item> get() = panelState.plan
    val hasOlderEntries: Boolean get() = panelState.hasOlderEntries
    val retryable: RetryableTurn? get() = panelState.retryable

    /**
     * The prompt waiting on the user: a RUNNING turn's own — surfaced from
     * any chat, because there is at most one turn and therefore at most one
     * prompt to answer — else the visible chat's own.
     */
    val approval: AgentApproval?
        get() = turnState?.approval ?: panelState.approval

    /** Same routing as [approval]: the turn's line while a turn runs. */
    val statusLine: String?
        get() = turnState?.statusLine ?: panelState.statusLine

    /**
     * Whether the plan panel is open. Controller-owned ON PURPOSE: the panel
     * survives chat switches, tab switches, recompositions and whole turns,
     * none of which a remember{} inside the composable would. NOTHING writes
     * this except the user's toggle (and the sheet's own user-dismiss) — not
     * a chat switch, not a task update, not a turn starting or ending.
     */
    var planPanelOpen by mutableStateOf(false)

    /**
     * The step list of the chat the panel is showing is [panelState].plan;
     * this is the running total of what TODAY's turns cost across the
     * profile's chats, for the token dialog's second line. In-memory on
     * purpose — per-turn rows would cost a write per turn for a number
     * nobody reconciles against a bill — and it resets when the day rolls
     * over or the process restarts; the dialog says "today", not "invoice".
     */
    var todayUsage by mutableStateOf(TokenUsage.ZERO)
        private set
    private var todayStamp: LocalDate? = null

    private fun recordToday(usage: TokenUsage) {
        val today = LocalDate.now()
        if (today != todayStamp) {
            todayStamp = today
            todayUsage = TokenUsage.ZERO
        }
        todayUsage += usage
    }

    /** What the token dialog shows: one chat's totals and today's across chats. */
    data class TokenStats(val session: TokenUsage, val today: TokenUsage)

    /**
     * [sessionId]'s chat totals as they will be SHOWN: the persisted row
     * first (it is authoritative and outlives this process), falling back to
     * the in-memory state for a chat whose row was not written yet.
     */
    suspend fun tokenStatsFor(sessionId: Long?): TokenStats {
        val row = sessionId?.let { id -> runCatching { repo.session(id) }.getOrNull() }
        val session = if (row != null) {
            TokenUsage(row.inputTokens, row.outputTokens)
        } else {
            stateFor(sessionId).tokenUsage
        }
        return TokenStats(session = session, today = todayUsage)
    }

    /**
     * The "allow on this site for this session" answers, kept per controller —
     * so they last as long as the chat the user is in and no longer. See
     * [SessionApprovals] for what can never be remembered.
     */
    private val sessionApprovals = SessionApprovals()

    /**
     * The chat [sessionApprovals] belongs to. The memory is dropped when the
     * chat changes and kept when a tab is merely switched away from and back,
     * which is what "for the rest of this chat" has to mean.
     */
    private var approvalsSessionId: Long? = null

    private fun pointApprovalsAt(session: Long?) {
        if (approvalsSessionId == session) return
        approvalsSessionId = session
        sessionApprovals.clear()
    }

    /** Where the running turn's page is, or null when that cannot be said. */
    private var originOfTurn: () -> String? = { null }

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

    /**
     * The chat is set to AUTO: each turn asks the provider which of its models
     * answers rather than sending [activeModel]. Surfaced so the header says
     * AUTO instead of a model that this turn may not use.
     */
    var chatModelAuto by mutableStateOf(false)
        private set

    /**
     * The chat is set to HEADLESS: each turn drives a hidden page of this
     * profile's own rather than the tab on screen. Surfaced so the panel can
     * say so — a hidden turn cannot see a tab, and the user has to be able to
     * tell that from the conversation.
     */
    var chatHeadless by mutableStateOf(false)
        private set
    var modelsLoading by mutableStateOf(false)
        private set
    var modelsError by mutableStateOf<String?>(null)
        private set

    /**
     * Why the turn is not moving right now: the user's own Pause, or a
     * challenge that has been handed to them. [AgentPause.None] while it runs.
     *
     * Read by the panel and mirrored into the ongoing notification, so the two
     * cannot say different things.
     */
    var pauseState by mutableStateOf<AgentPause>(AgentPause.None)
        private set

    /** A user Pause the loop is waiting out; completed by [resume]. */
    private var userResume: CompletableDeferred<Unit>? = null

    /** A challenge hand-over the executor is waiting out; completed by [resume]. */
    private var challengeResume: CompletableDeferred<Unit>? = null

    /**
     * Cached /models results per provider id — BOUNDED (see the cap): a
     * profile with a long provider history used to grow these HashMaps
     * without limit, and every entry is held for the controller's whole life.
     * Eight covers every provider a person actually rotates through; the
     * eldest falls out instead of the memory.
     */
    private val modelCache = object : LinkedHashMap<Long, List<String>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<String>>): Boolean =
            size > BOUNDED_CACHE_CAP
    }

    /** Decrypted API keys — memory only, never persisted, bounded as above. */
    private val apiKeyCache = object : LinkedHashMap<Long, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>): Boolean =
            size > BOUNDED_CACHE_CAP
    }

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
     * When [refreshProviders] last actually read, in elapsedRealtime — the
     * freshness guard that stops the panel's every resume-and-expand (and
     * BrowserActivity.onResume on top) from stampeding the same Room read.
     */
    private var lastProvidersRefreshMs = 0L

    /**
     * Whether this turn has already told the user that the local decision
     * gate could not answer. Reset per turn; see [gateFailure].
     */
    private var gateFailureNoted = false

    val messages = MutableStateFlow<String?>(null)

    // ------------------------------------------------------------- chat plugins

    /**
     * Runs the chat's plugin events in the embedded Node runtime. The
     * runtime's one-call gate is a cancellable wait now, and a call that
     * queues behind the one in flight says so on the status line — the
     * person holding two plugin-running chats sees "waiting…" instead of a
     * turn that looks wedged, and Stop stays honest while it waits.
     */
    private val pluginRuntime = AgentPluginRuntime(
        appContext,
        vm.profile,
        onContended = { setStatus(RUNTIME_CONTENDED_NOTICE) }
    )

    /** The per-chat catalogue of plugin tools, commands and hooks. */
    private val pluginCatalog = AgentPluginCatalog(pluginRepo, pluginRuntime)

    // ------------------------------------------------------------- skills

    /**
     * The skills store and its `skills` tool group, ONE pair per controller:
     * the chat turn's executor chain and the system-prompt assembly below both
     * read the same [SkillRegistry], so a listing and the prompt block can
     * never disagree about what is installed. Lazy on purpose — building the
     * pair touches only a File handle, but the registry's first read walks the
     * skills directory, and a controller that never runs a turn should never
     * pay for it. See [AgentSkillsTools] for what the group gates (install,
     * update and remove ride the same confirm gate every other write does).
     */
    private val skillsDir: java.io.File by lazy { java.io.File(appContext.filesDir, "skills") }
    private val skillRegistry by lazy { SkillRegistry(skillsDir) }
    private val skillsTools by lazy {
        AgentSkillsTools(
            registry = skillRegistry,
            installer = SkillInstaller(skillsDir, pluginRepo),
            confirmGate = { name, label -> gate(name, label) }
        ).also { tools ->
            // A plugin-format skill's code lives in the PLUGIN store, so its
            // removal has to uninstall that row too — the seam the registry
            // leaves for exactly this app to fill.
            skillRegistry.pluginUninstaller = { pluginId ->
                pluginRepo.uninstall(pluginId)
            }
        }
    }

    /**
     * The airplane surface the /airplane command and the airplane_mode tool
     * share — one bridge so the two are one promise. The "Offer Airplane
     * Mode shortcut" setting is read LIVE inside the lambda (the controller's
     * own convention for every setting a gate consults), because the user can
     * flip it in the browser settings while a chat or a turn is open.
     */
    private val airplaneBridge: AirplaneModeBridge = AirplaneModeController(appContext) {
        vm.globalSettings.offerAirplaneModeShortcut
    }

    /**
     * The plugin surface the panel and its sheets bind to: installed plugins,
     * this chat's bindings, the picker sheet and the enable-time grant
     * dialog. Controller-owned on the same Compose-state convention as every
     * other surface above — see [PluginUiState] for why the sheet, not the
     * controller, is the one that closes it.
     */
    val pluginUi = PluginUiState(
        scope = scope,
        repo = pluginRepo,
        runtime = pluginRuntime,
        catalog = pluginCatalog,
        sessionId = { activeSessionId },
        createSession = { title -> createSessionForPlugins(title) },
        onCard = { session, card, name -> appendPluginCard(session, card, name) },
        onNotice = { text, error -> note(text, error) },
        history = { session -> pluginHistoryOf(session) },
        // Staged activation's one question: a chat that has sent no user
        // message yet is fresh, and a module enabled into it stages rather
        // than activates — the first message becomes its prompt. The EXISTS
        // probe keeps the answer exact at any chat length, where a capped
        // window read would call a long chat fresh and re-run a module its
        // own chat already ran.
        freshChat = { session ->
            runCatching { !repo.hasUserMessage(session) }.getOrDefault(true)
        },
        generate = { session -> pluginGeneratorFor(session) },
        // /make_plugins composes with the SAME session resolution rule — the
        // chat's own AI, read per command — so the maker's promise and
        // agent.generate's are one promise.
        compose = { session -> pluginComposerFor(session) },
        // The /airplane command rides the SAME bridge the airplane_mode tool
        // gets below, so the command and the tool cannot drift apart.
        airplane = airplaneBridge
    )

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
        // The plugin surface collects on the same scope: the installed list
        // flows from the database, and the bindings re-subscribe whenever
        // the panel moves to another chat.
        pluginUi.start()
    }

    /**
     * Defensive cross-process re-sync: re-reads providers + agent settings
     * DIRECTLY from Room. Room's multi-instance invalidation normally
     * delivers writes from the default process (settings activities) to this
     * ':browser' process within moments, but on slow/emulator filesystems the
     * ping can occasionally be lost — the panel would then keep showing a
     * stale "No provider configured" state. Called whenever the agent panel
     * is opened/expanded AND from BrowserActivity.onResume.
     *
     * The freshness guard is what makes all those callers coexist with the
     * continuous collectors that already keep both fresh: the read exists for
     * the LOST-ping case, an event of once-per-resume magnitude, so re-reading
     * more often than once every few seconds pays Room + a Log.d for a list
     * the collectors have not had time to change. A provider saved through
     * this process rides the collector Flow and never waits out the window.
     */
    fun refreshProviders() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProvidersRefreshMs < PROVIDERS_REFRESH_MIN_INTERVAL_MS) return
        lastProvidersRefreshMs = now
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
        AgentForeground.pauseCurrentTurn = null
        AgentForeground.currentTurnSessionId = null
        AgentForeground.finish()
        // Everything cached dies with the controller: held windows, decrypted
        // keys and model lists must not outlive the profile they were read
        // for — the next controller belongs to the next profile.
        evictAllStates()
        modelCache.clear()
        apiKeyCache.clear()
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
        chatModelAuto = settings.defaultModelAuto && provider != null
        chatHeadless = settings.chatHeadless
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
                it.copy(defaultProviderId = null, defaultModel = null, defaultModelAuto = false)
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
                it.copy(defaultProviderId = provider.id, defaultModel = model, defaultModelAuto = false)
            }
            activeProvider = provider
            activeModel = model
            chatModelAuto = false
            messages.value = "Agent set to ${provider.name} · $model"
        }
    }

    /**
     * Sets the chat to AUTO on [provider]: every turn tries the provider's own
     * default and then the first of its models that answers (see
     * [AutoModelPicker]), so a name the account cannot actually call does not
     * fail the whole conversation.
     */
    fun setAutoModel(provider: AgentProviderEntity) {
        scope.launch {
            // defaultModel is deliberately kept: it is the last explicit pick,
            // and turning AUTO off should give it back rather than forget it.
            settings = appState.updateAgentSettings {
                it.copy(defaultProviderId = provider.id, defaultModelAuto = true)
            }
            activeProvider = provider
            chatModelAuto = true
            messages.value = "Agent set to ${provider.name} · Auto"
        }
    }

    /**
     * Chooses what a chat turn runs ON: the tab the user is looking at, or a
     * hidden page of the running profile's own. Persisted in the app-wide
     * agent settings, so it is a standing choice about how the agent works
     * rather than a per-turn switch — and the same in every profile.
     */
    fun setChatSurface(headless: Boolean) {
        scope.launch {
            settings = appState.updateAgentSettings { it.copy(chatHeadless = headless) }
            chatHeadless = headless
        }
    }

    /**
     * Chooses what every turn from now on may do. Persisted like the surface,
     * because it is a standing choice about how the agent works rather than a
     * per-turn switch — and, like the surface, the same in every profile.
     *
     * A mode is stored as the flags it means rather than beside them, so the
     * switches in AI Agent settings keep editing the same state and cannot end
     * up describing a mode other than the one the header shows. ASK turns the
     * confirmation prompt ON: the prompt is the mode's whole point, so it
     * should not be left off by whatever the switch happened to be.
     */
    fun setAgentMode(mode: AgentMode) {
        scope.launch {
            settings = appState.updateAgentSettings { s ->
                when (mode) {
                    AgentMode.PLAN -> s.copy(chatOnly = true, yolo = false)
                    AgentMode.ASK -> s.copy(chatOnly = false, yolo = false, confirmActions = true)
                    AgentMode.YOLO -> s.copy(chatOnly = false, yolo = true)
                }
            }
        }
    }

    /**
     * The model an AUTO turn runs on: the one that was picked by hand first,
     * then whatever of the provider's own models answers. Null means none of
     * them did, which the caller reports instead of sending a turn the provider
     * is going to refuse.
     */
    private suspend fun resolveAutoModel(
        provider: AgentProviderEntity,
        gateway: AgentGateway
    ): String? {
        setStatus("Looking for a model that answers…")
        val configured = activeModel?.takeIf { it.isNotBlank() }
            ?: provider.defaultModel.takeIf { it.isNotBlank() }
        val chosen = AutoModelPicker.firstWorkingOn(gateway, configured)
        setStatus(null)
        return chosen
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
     *
     * NO running gate. Switching away from a chat whose turn is running is
     * safe now that every write of a turn lands in the turn's own captured
     * state: the panel simply starts rendering the empty null-keyed state,
     * and the running chat keeps moving underneath it.
     */
    fun newSession() {
        // Takes the panel: any tab resolve still reading is now stale.
        conversationRequest++
        val previous = activeSessionId
        activeSessionId = null
        // A site-scoped approval belongs to the chat it was given in.
        pointApprovalsAt(null)
        // The panel now renders stateFor(null) — empty by construction, so
        // nothing else needs clearing here. The chat this tab WAS showing
        // keeps its own state (rows, plan, retry offer), so returning to it
        // — from the history list or by switching the tab back — restores
        // everything without a single read.
        if (previous != null) {
            scope.launch { repo.bindSessionToTab(previous, "") }
        }
        // A fresh chat is exactly the moment the plugin choice matters: the
        // sheet opens by itself when there is anything to choose, because a
        // person who has never installed one should not have to find the
        // puzzle button to learn that they could.
        if (pluginUi.installed.isNotEmpty()) pluginUi.openPicker()
    }

    /**
     * Opens a chat from the history list IN THE CURRENT TAB.
     *
     * One tab shows one conversation, so adopting a chat moves it here: the
     * chat this tab was showing is detached (still in the list, no longer
     * this tab's) and [id] takes its place. A chat picked while no tab is
     * resolved is left as it was rather than detached from the tab that owns
     * it — moving a conversation is only ever the user's doing.
     *
     * No running gate — see [newSession].
     */
    fun openSession(id: Long) {
        scope.launch {
            val session = repo.session(id) ?: return@launch
            // Takes the panel: any tab resolve still reading is now stale.
            conversationRequest++
            val tabId = vm.activeTabId
            // A task-owned tab is the task's, not the user's: binding a chat
            // onto it would leave the chat attached to a page a run is
            // driving (and detached from the tab the user reads).
            if (tabId != null && !vm.isTaskTab(tabId)) {
                val previous = activeSessionId
                if (previous != null && previous != id) repo.bindSessionToTab(previous, "")
                repo.bindSessionToTab(id, tabId)
                conversationTabId = tabId
            }
            activeSessionId = session.id
            pointApprovalsAt(session.id)
            ensureWindowLoaded(session, stateFor(session.id))
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
     * user just sent, because the entries list is what the panel draws).
     *
     * THE CACHE: a chat whose state already holds its newest window re-renders
     * from memory with no query at all — see [ensureWindowLoaded]. This is
     * what makes the tab switch away-and-back free, and it is why the old
     * "task tab steals the panel" round-trip cost two full reloads.
     */
    private suspend fun showTabConversation(tabId: String) {
        val token = ++conversationRequest
        val session = repo.sessionForTab(profileId.value, tabId)
        if (token != conversationRequest || vm.activeTabId != tabId) return
        conversationTabId = tabId
        activeSessionId = session?.id
        // Another chat is another site; coming back to this one keeps what it
        // was told "for the rest of this chat".
        pointApprovalsAt(session?.id)
        // A tab with no chat shows the null-keyed state — empty by
        // construction. Nothing global is wiped: the chat we came FROM keeps
        // its own state, so going back re-renders it from memory.
        if (session == null) return
        ensureWindowLoaded(session, stateFor(session.id))
    }

    /**
     * Makes sure [st] holds its chat's plan and newest transcript window,
     * reusing what it already holds when that is still the truth.
     *
     * The freshness test is the chat's persisted ROW COUNT (one indexed COUNT
     * query) against the count captured at load: equal means nothing landed
     * since the last look — no query beyond the count, no re-map, the panel
     * re-renders the held rows as-is. Different means a turn ran here while
     * the chat was away (or rows were cleared), and the newest window is
     * reloaded — the one case where memory is behind the row.
     */
    private suspend fun ensureWindowLoaded(session: AgentSessionEntity, st: ChatSessionState) {
        if (!st.planLoaded) {
            st.plan = AgentPlan.decode(session.planJson)
            st.planLoaded = true
        }
        val persisted = runCatching { repo.messageCount(session.id) }.getOrDefault(st.persistedRowCount)
        if (!st.hasLoadedWindow || persisted != st.persistedRowCount) {
            loadTranscriptWindow(session.id, st, persisted)
        }
    }

    /**
     * Loads the chat's newest transcript window into ITS OWN state, off the
     * main thread for the row→entry mapping: tool rows carry multi-KB payloads
     * and plugin rows are JSON, and a tab switch used to re-parse a whole
     * day's worth of them on Main. The Room query is bounded by
     * [TRANSCRIPT_WINDOW_ROWS] on the same move — the read and the map were
     * the two halves of the same stall.
     */
    private suspend fun loadTranscriptWindow(sessionId: Long, st: ChatSessionState, persisted: Int) {
        val page = repo.messagePage(sessionId, Long.MAX_VALUE, TRANSCRIPT_WINDOW_ROWS)
        val mapped = withContext(Dispatchers.Default) { page.rows.mapNotNull(::entryFromRow) }
        // Written together, and after the mapping, so a load overtaken by a
        // faster switch lands all of its own state or none of it. And because
        // it writes the SESSION's own state, an overtake can no longer splice
        // another chat's rows anywhere — the worst case is a stale window in
        // the chat it belongs to, corrected by the next [ensureWindowLoaded].
        st.hasOlderEntries = page.hasOlder
        st.oldestLoadedRowId = page.rows.firstOrNull()?.id
        st.persistedRowCount = persisted
        st.hasLoadedWindow = true
        st.replaceAll(mapped)
    }

    /**
     * Pages the next older window of THIS chat in front of its entries and
     * answers how many entries landed (0 when the chat's own turn is running,
     * when nothing is loaded, or when the chat has nothing older).
     *
     * The return count is what the panel keeps its viewport put with: the
     * rows land BEFORE the entry the user was reading, and the panel offsets
     * its scroll by exactly the count so the frame that grows the list is
     * also the frame that stays put. A turn running in THIS chat is refused
     * rather than reconciled — the turn holds an index into its streaming
     * row, and shifting it would splice the live stream. Another chat's turn
     * is no obstacle: the lists are separate states now, which is the whole
     * point of [ChatSessionState].
     */
    suspend fun loadOlderEntries(): Int {
        val session = activeSessionId ?: return 0
        val st = stateFor(session)
        if (st.running) return 0
        val before = st.oldestLoadedRowId ?: return 0
        val page = repo.messagePage(session, before, TRANSCRIPT_WINDOW_ROWS)
        // Stale-page guard, read AFTER the query: a chat that took the panel
        // while this page was in flight owns the window bookkeeping now, and
        // a page answered for its predecessor must not write over it.
        if (activeSessionId != session) return 0
        if (page.rows.isEmpty()) {
            // Rows were promised and are gone (the chat was cleared under
            // us): the honest answer is the end of history, not a button
            // that keeps promising it.
            st.hasOlderEntries = false
            return 0
        }
        val older = withContext(Dispatchers.Default) { page.rows.mapNotNull(::entryFromRow) }
        // Re-read after the mapping, for the same reason plus one more: a
        // turn that started meanwhile holds an index into the streaming row,
        // and prepending now would splice the live stream — the refusal
        // above cannot cover a turn that began while the page was reading.
        if (st.running || activeSessionId != session) return 0
        // The cursor moves even when every entry was dropped (unparseable
        // rows exist): paging past them is the only way the NEXT window gets
        // a chance to hold something renderable. The persisted count is NOT
        // touched: it counts the chat's total rows, and loading older changes
        // the window, not the chat.
        st.hasOlderEntries = page.hasOlder
        st.oldestLoadedRowId = page.rows.first().id
        return st.prependOlder(older)
    }

    /**
     * Follows the user to the tab they just opened.
     *
     * No running gate, and that is the point of the per-session rewrite: the
     * running turn writes into its OWN chat's state, so re-pointing the PANEL
     * cannot erase its progress from the screen — the user sees whichever
     * chat they switch to, and the turn's chat keeps streaming underneath.
     * The one exception is a TASK's tab (a scheduled run's page): the run
     * needed that tab active so its engine would attach, and the panel must
     * not pay for that with the conversation the user was reading — see
     * [com.roombrowser.browser.BrowserTaskPageHost].
     */
    private fun onActiveTabChanged(tabId: String?) {
        if (tabId != null && vm.isTaskTab(tabId)) return
        if (tabId == null || tabId == conversationTabId) return
        scope.launch { showTabConversation(tabId) }
    }

    fun deleteSession(id: Long) {
        scope.launch {
            // The plugins this chat enabled get their goodbye before the row
            // goes — deactivate, best-effort, then the sweep that reclaims
            // the data directories the cascade leaves behind.
            runCatching { deactivateChatPlugins(pluginRepo, pluginRuntime, id, "chat-deleted") }
            repo.deleteSession(id)
            // The deleted chat's held window goes with it: keeping it would
            // be memory for a chat nothing can ever reopen.
            evictState(id)
            if (activeSessionId == id) newSession()
        }
    }

    fun clearAllSessions() {
        scope.launch {
            // Every chat that had plugins enabled gets the same goodbye a
            // single deletion gives; each call sweeps as it finishes.
            sessions.forEach { session ->
                runCatching { deactivateChatPlugins(pluginRepo, pluginRuntime, session.id, "chat-deleted") }
            }
            repo.deleteSessionsForProfile(profileId.value)
            // Every held window goes with every row.
            evictAllStates()
            newSession()
            messages.value = "Agent sessions cleared"
        }
    }

    private fun entryFromRow(row: AgentMessageEntity): AgentEntry? = when (row.role) {
        "user" -> AgentEntry.User(row.content, row.createdAt)
        "assistant" -> AgentEntry.Assistant(
            text = UntrustedContent.stripMarkers(row.content),
            thinking = "",
            streaming = false,
            at = row.createdAt
        )
        "tool" -> AgentEntry.Tool(
            callId = "row_${row.id}",
            name = row.toolName ?: "tool",
            label = AgentTools.describeTool(row.toolName ?: "", row.toolArgs),
            running = false,
            ok = !row.content.startsWith("ERROR:"),
            // Rows written before the fence stopped leaking still carry it,
            // cut off mid-sentence.
            summary = UntrustedContent.payload(row.content).take(200),
            at = row.createdAt
        )
        "plugin" -> pluginCardFromRow(row)
        else -> null
    }

    /**
     * The `plugin`-role row back as the card it was appended from, or null
     * when its JSON no longer parses as one — a card that cannot be read is
     * dropped rather than rendered as a wall of JSON. The row's tool-name
     * column carries the plugin's name, which is what it is: the name of
     * the thing that produced the content.
     */
    private fun pluginCardFromRow(row: AgentMessageEntity): AgentEntry? {
        val card = runCatching {
            AgentJson.parseToJsonElement(row.content) as? JsonObject
        }.getOrNull()?.let { PluginCard.parse(it) } ?: return null
        return AgentEntry.Plugin(card, row.toolName ?: "plugin", row.createdAt)
    }

    // ------------------------------------------------------------- turn execution

    fun send(text: String, includePage: Boolean, attachments: List<AgentAttachment> = emptyList()) {
        val message = text.trim()
        if (message.isEmpty() || running) return
        // The tab and the chat are read HERE, on the user's own tap, and not
        // inside the coroutine: runTurn suspends several times before it
        // would get to them, a tab switched in that window would bind the
        // turn — and its new chat row — to the wrong tab, and a plugin
        // `/`-command must run against the conversation the message was sent
        // into, not one a resolve lands on mid-flight.
        val tabId = vm.activeTabId
        val sessionAtTap = activeSessionId
        // Plugin commands are chat surface, not agent turns: they are parsed
        // FIRST, and only a text no command claims reaches the provider. The
        // provider checks live below them on purpose — `/plugins` and a
        // plugin's own commands are the plugin system's features, and they
        // must work before any provider is configured.
        turnJob = scope.launch {
            if (pluginUi.interceptChatCommand(message, sessionAtTap)) return@launch
            val provider = activeProvider ?: run {
                messages.value = "Configure an AI provider first (Agent → settings)"
                return@launch
            }
            // Null means AUTO: the model is not known until a provider answers, and
            // asking which one does is work the turn itself has to do.
            val model: String? = if (chatModelAuto) null else activeModel ?: provider.defaultModel
            if (model != null && model.isBlank()) {
                messages.value = "Pick a model for ${provider.name} first"
                return@launch
            }
            // The turn takes the panel from here on: a tab resolve still in
            // flight must not land on top of it and wipe the message below.
            conversationRequest++
            runTurn(message, includePage, attachments, provider, model, tabId)
        }
    }

    /**
     * Stops the running turn, routed BY SESSION: the rows, status and
     * approval the stop writes land in the turn's OWN chat state — the one
     * [turnOriginSessionId] names — never in whichever chat the panel has
     * drifted to. [sessionId] is who is asking (the notification's Stop
     * action carries the controller's own answer for the turn it was minted
     * by); only the turn that is actually running accepts one, and a call
     * from any other chat is refused rather than answered.
     */
    fun stop(sessionId: Long? = turnOriginSessionId) {
        if (sessionId != turnOriginSessionId) return
        val job = turnJob ?: return
        val st = stateFor(turnOriginSessionId)
        turnJob = null
        job.cancel()
        running = false
        st.running = false
        st.statusLine = null
        st.approval = null
        clearPause()
        st.appendEntry(AgentEntry.Notice("Stopped by user", error = true, at = System.currentTimeMillis()))
    }

    /**
     * Pauses the turn. Honoured BETWEEN steps — nothing can cut a tool in half —
     * and refused while a challenge is on screen, where the turn is already
     * stopped and "Task Paused" would replace the line that says what the user
     * is being asked to do. Routed by session like [stop].
     */
    fun pause(sessionId: Long? = turnOriginSessionId) {
        if (sessionId != turnOriginSessionId) return
        if (!running || pauseState != AgentPause.None) return
        applyPauseState(AgentPause.Paused)
    }

    /**
     * Resumes whatever the turn is waiting on: the user's own pause, or a
     * challenge they say they have finished.
     *
     * The challenge path does NOT take that on trust — the executor re-reads the
     * page and hands over again if the check is still there.
     */
    fun resume() {
        if (pauseState == AgentPause.Paused) {
            pauseState = AgentPause.None
            AgentForeground.setPaused(false)
            setStatus(null)
        }
        userResume?.let { if (!it.isCompleted) it.complete(Unit) }
        challengeResume?.let { if (!it.isCompleted) it.complete(Unit) }
    }

    /** What the notification's single Pause/Resume action does. */
    fun togglePause(sessionId: Long? = turnOriginSessionId) {
        if (sessionId != turnOriginSessionId) return
        if (!running) return
        if (pauseState == AgentPause.None) pause(sessionId) else resume()
    }

    /** Waits out a user Pause between steps; see [AgentLoop.beforeStep]. */
    private suspend fun awaitUserResume() {
        while (running && pauseState == AgentPause.Paused) {
            val waiter = CompletableDeferred<Unit>()
            userResume = waiter
            waiter.await()
        }
        userResume = null
    }

    /** Waits for the user to say a challenge is done. */
    private suspend fun awaitChallengeDone() {
        val waiter = CompletableDeferred<Unit>()
        challengeResume = waiter
        waiter.await()
        challengeResume = null
    }

    /**
     * Mirrors the executor's pause state into the panel and the notification,
     * and says in the chat what is being asked of the user the moment a
     * challenge is first seen — a notification the user never sees and a panel
     * they are not looking at is not a hand-over.
     */
    private fun applyPauseState(state: AgentPause) {
        pauseState = state
        (state as? AgentPause.ChallengeDetected)?.let {
            note(AgentPauseLabels.handOverText(it.kind), error = false)
        }
        // The same sentence in the status line and in the notification, so the
        // panel and a user who is looking at their lock screen agree.
        AgentPauseLabels.labelOf(state)?.let { setStatus(it) }
        AgentForeground.setPaused(state != AgentPause.None)
    }

    /** Ends whatever the turn was waiting on, without pretending it finished. */
    private fun clearPause() {
        userResume?.let { if (!it.isCompleted) it.complete(Unit) }
        challengeResume?.let { if (!it.isCompleted) it.complete(Unit) }
        userResume = null
        challengeResume = null
        pauseState = AgentPause.None
        AgentForeground.setPaused(false)
    }

    /** Sends the turn that did not finish again, word for word. */
    fun retry() {
        val last = retryable ?: return
        send(last.text, last.includePage, last.attachments)
    }

    fun respondApproval(answer: ApprovalAnswer) {
        approval?.let { it.respond(answer) }
        // Cleared where it was found — the running turn's prompt when one is
        // surfaced (even though the panel has drifted to another chat), else
        // the visible chat's own. Writing both would also be safe, but only
        // one of them can be showing a prompt right now.
        val turn = turnState
        if (turn?.approval != null) turn.approval = null else panelState.approval = null
    }

    private suspend fun runTurn(
        text: String,
        includePage: Boolean,
        attachments: List<AgentAttachment>,
        provider: AgentProviderEntity,
        // Null means AUTO — see [send] and [resolveAutoModel].
        requestedModel: String?,
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
        // THE TURN'S STATE, captured here and never re-read from
        // [activeSessionId]: every row, status line, approval and plan this
        // turn writes lands in this chat's own ChatSessionState, so a user
        // switching chats (or tabs) mid-turn cannot drag a single write into
        // the chat they moved to. The var (not val) is deliberate: a turn
        // started on a tab with no chat yet writes into the null-keyed scratch
        // state until its session row exists, then adopts the real one — see
        // the adoption below.
        var st = stateFor(activeSessionId)
        turnOriginSessionId = st.sessionId
        AgentForeground.currentTurnSessionId = st.sessionId
        running = true
        st.running = true
        gateFailureNoted = false
        // Where "this site" is for the approvals this turn asks about. The URL
        // is the engine's, never the page's, and it is only trusted while the
        // turn's tab is the one on screen — pageState describes that tab, so a
        // background one would file the answer under a different site.
        originOfTurn = {
            val id = tabId
            if (id != null && vm.isActiveTab(id)) SiteMemory.originOf(vm.pageState.url) else null
        }
        // Background mode: foreground service + wake lock so the turn keeps
        // running when the user leaves the app or the screen turns off. The
        // notification's actions are routed through the controller BY SESSION:
        // the lambdas read the turn's own origin id at invocation, so a Stop
        // or Pause can only ever reach the turn whose notification carries it
        // — never another chat's state.
        AgentForeground.stopCurrentTurn = { stop(turnOriginSessionId) }
        AgentForeground.pauseCurrentTurn = { togglePause(turnOriginSessionId) }
        AgentForeground.begin(appContext)
        AgentForeground.status("Working: " + text.take(60))
        // The pre-turn steps (plugin command handling) may have left a line on
        // THIS chat's state before any turn existed; from here the turn's
        // lines route to the same state, so clear it directly — setStatus's
        // dedupe reads the accessor, which cannot tell a stale pre-turn line
        // from the turn's own, and would keep both.
        st.statusLine = null
        setStatus(null)
        // The chat bubble (and the persisted row) mention the attached file
        // names; the LLM still receives the raw text plus an attachment block.
        val display = if (attachments.isEmpty()) text
        else text + "\n📎 " + attachments.joinToString(", ") { it.name }
        st.appendEntry(AgentEntry.User(display, System.currentTimeMillis()))
        // Offered for retry until this turn finishes cleanly. Set here, before
        // anything can fail, so every failure path below — an error event, a
        // throw, the user's Stop — leaves the button up without each of them
        // having to remember to raise it. It lives in the TURN's chat state:
        // a failed turn belongs to the chat it was asked in.
        st.retryable = RetryableTurn(text, includePage, attachments)
        // YOLO has no prompt to remind anyone it is on, which is exactly why
        // it needs saying: the user turned it on at a prompt, possibly days
        // ago, and every turn since has looked identical to one where the
        // checks were still in place. One line per turn is cheap.
        if (settings.yolo) {
            note(
                "YOLO is on — this turn's actions run without asking. " +
                    "Switch the mode in the agent panel.",
                error = true
            )
        }
        // The turn's stream coalescer (see [AgentStreamCoalescer]): every
        // streamed delta lands here and reaches THIS chat's transcript one
        // window at a time, through this one seam. `streamingIndex` lives in
        // the state, not a local — the flush callback below and the boundary
        // handlers in the event loop are its only writers, and all of them run
        // on the turn's own event sequence (see the class's threading note for
        // why that confinement is the design, not an accident).
        val stream = AgentStreamCoalescer { textDelta, thinkingDelta ->
            st.streamingIndex = st.upsertStreaming(st.streamingIndex ?: -1, textDelta, thinkingDelta)
        }
        // An error reported as an EVENT rather than thrown still means the turn
        // did not do what was asked, so it must not clear the retry offer.
        var turnFailed = false
        // A headless turn's page. Declared out here because the finally below
        // is where it is destroyed, and that runs whatever path left the try.
        var ownedPage: EngineSession? = null
        // The `compute_js` scratchpad, for the same reason: it belongs to the
        // turn and goes with it, so it is declared here and closed in the finally.
        var scratchpad: AgentScratchpad? = null
        try {
            // The turn belongs to the tab it was started from. Every tool
            // resolves its engine from this id rather than from whatever is on
            // screen, so a tab switch mid-turn can no longer retarget the rest
            // of it, and the engine is pinned so the live-engine budget cannot
            // evict the page out from under the turn either. Pinned inside the
            // try so the finally below lifts it even if a step from here on
            // throws. The pin carries the CHAT owner: a task host's pin (the
            // "task" owner) is a separate slot, so neither side can lift the
            // other's tab — see BrowserViewModel.pinTabForAgent.
            tabId?.let { id -> vm.pinTabForAgent(BrowserViewModel.PIN_OWNER_CHAT, id) }
            val apiKey = apiKeyFor(provider).orEmpty()
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
            // AUTO is settled HERE, before the session row exists: a
            // conversation recorded against a model that never answered would
            // claim a run that did not happen, and the user's own message would
            // be left in it with no reply under it.
            val model = requestedModel ?: resolveAutoModel(provider, gateway)
            if (model == null) {
                note("None of ${provider.name}'s models answered. Pick one by hand.", error = true)
                return
            }
            // The turn's own generator: a plugin that asks for text while its
            // command, tool or message hook runs gets THIS turn's gateway and
            // THIS turn's model — the same conversation it is already part
            // of, riding the turn's own retry policy. Resolved once here so
            // every plugin surface the turn touches (the executor chain, the
            // message hooks) hands the plugin the same AI, and the model is
            // settled before any of them can hold it.
            val turnGenerate = PluginGenerateCall { request ->
                generateWith(gateway, model, request)
            }
            // WHERE the turn runs is settled here too, and for the same reason
            // as the model: a conversation that recorded a request no surface
            // could carry out would claim a run that did not happen.
            val control = BrowserControlApiImpl(vm, appContext)
            // One scratchpad per turn, shared by whichever page executor the
            // turn uses. The holder is cheap; its engine session starts only if
            // compute_js is actually called.
            val pad = AgentScratchpad(appContext, vm.profile)
            scratchpad = pad
            val visibleExecutor = AgentToolExecutor(
                vm = vm,
                control = control,
                tabId = tabId,
                onStatus = { setStatus(it) },
                confirmGate = { name, label -> gate(name, label) },
                // Wallet approvals must not ride the bypassable generic gate.
                walletConfirm = { label -> requestWalletApproval(label) },
                destructiveGate = { name, label -> destructiveVerdict(name, label) },
                profileData = GraphAgentProfileData(graph, profileId, appContext),
                // Read live rather than from the turn's snapshot: the user can
                // flip it while the turn is running.
                otpDigitsAllowed = { appState.agentSettingsSnapshot().otpDigitsToAgent },
                runJsApprovalRequired = { vm.profileSettings().agentConsoleApprovalRequired },
                siteMemory = GraphSiteMemoryStore(graph, profileId),
                siteMemoryEnabled = { vm.profileSettings().agentSiteMemoryEnabled },
                scratchpad = pad,
                nodeRuntime = AgentNodeRuntime(appContext, vm.profile),
                nodePackages = AgentNodePackages(appContext),
                onPause = { state -> applyPauseState(state) },
                awaitChallengeResume = { awaitChallengeDone() },
                visionUnsupportedReason = {
                    activeProvider?.protocol?.let { AgentVisionSupport.unsupportedReason(it) }
                },
                skillsTools = skillsTools,
                // The turn's tool chain gets the same airplane bridge the
                // /airplane command rides — one promise, two callers.
                airplane = airplaneBridge
            )
            // HEADLESS: the turn drives a hidden page of this profile's own
            // instead of the tab on screen. The reference is kept so the
            // context snapshot below reads the page the turn actually worked
            // on, and so the page is torn down when the turn ends.
            val headless = if (settings.chatHeadless) {
                val page = createHeadlessPage()
                if (page == null) {
                    note(
                        "Could not start a hidden page for this turn. Switch this chat " +
                            "to the browser tab and try again.",
                        error = true
                    )
                    return
                }
                ownedPage = page
                HeadlessToolExecutor(
                    session = page,
                    searchEngineId = vm.profileSettings().searchEngineId,
                    permissions = HEADLESS_CHAT_PERMISSIONS,
                    confirmActions = settings.confirmActions,
                    // A headless CHAT still has the person who sent it, so an
                    // action that has to be asked about is asked about rather
                    // than refused the way a scheduled run refuses it.
                    askInteractive = { name, label -> gate(name, label) },
                    runJsApprovalRequired = { vm.profileSettings().agentConsoleApprovalRequired },
                    visionEnabled = { vm.profileSettings().agentVisionEnabled },
                    visionUnsupportedReason = {
                        activeProvider?.protocol?.let { AgentVisionSupport.unsupportedReason(it) }
                    },
                    siteMemory = GraphSiteMemoryStore(graph, profileId),
                    siteMemoryEnabled = { vm.profileSettings().agentSiteMemoryEnabled },
                    scratchpad = pad,
                    nodeRuntime = AgentNodeRuntime(appContext, vm.profile),
                    nodePackages = AgentNodePackages(appContext),
                    // The browser is up behind this hidden page, so a download
                    // goes to the user's own Downloads and outlives the turn.
                    download = { url, name -> visibleExecutor.startDownload(url, name) }
                )
            } else {
                null
            }
            val pageExecutor: ToolExecutor = headless?.let {
                HeadlessChatToolGate(
                    page = it,
                    appTools = { name, args -> visibleExecutor.executeAppTool(name, args) }
                )
            } ?: visibleExecutor
            // "Delete all agent chats" can run in the settings ACTIVITY while
            // a session is active here — verify it still exists, else start a
            // fresh one instead of writing to a dead row (FK safety).
            //
            // And when nothing is resolved yet, ask the TAB before creating
            // one: the panel can be used before its tab resolve finishes, and
            // a second message must continue the conversation it belongs to
            // rather than silently start a second one.
            val previousId = activeSessionId
            val known = previousId?.let { id ->
                runCatching { repo.session(id) }.getOrNull()
            } ?: tabId?.let { repo.sessionForTab(profileId.value, it) }
            val sessionId = known?.id ?: repo.createSession(
                profileId = profileId.value,
                title = text.take(64),
                providerId = provider.id,
                model = model,
                // The new chat belongs to this tab, so coming back to the
                // tab brings the conversation back with it.
                tabId = tabId.orEmpty()
            )
            activeSessionId = sessionId
            // The turn's writes route BY SESSION (stop(), the notification's
            // actions, the approval prompts all read this id), so resolving
            // the scratch id must reach them too — a Stop arriving between
            // here and the end of the turn has to land on THIS turn's chat.
            turnOriginSessionId = sessionId
            AgentForeground.currentTurnSessionId = sessionId
            // Adopting the tab's chat is continuing it, so what it was already
            // allowed to do on a site still stands.
            pointApprovalsAt(sessionId)
            tabId?.let { id -> conversationTabId = id }
            if (sessionId != st.sessionId) {
                // The turn began on the null-keyed scratch state (the tab had
                // no chat yet) and its row exists now: everything written so
                // far moves onto the real chat's own state — UNDER the prior
                // rows that chat's window holds, or the user's bubble would
                // float above a transcript it belongs beneath.
                val resolved = stateFor(sessionId)
                known?.let { ensureWindowLoaded(it, resolved) }
                resolved.adoptFrom(st)
                st = resolved
            } else if (known != null && !st.hasLoadedWindow) {
                // Continuing the panel's chat, but the send outran its resolve
                // (the panel pointed here before the window read came back):
                // load the row's window now, or the turn's rows would append
                // to an empty transcript the next reload would not match.
                ensureWindowLoaded(known, st)
            }
            // The catalogue read every later step of this turn needs — the
            // message hooks below, the plugin prompt blocks, the tool
            // definitions — is STARTED here, beside the turn, instead of being
            // left to whichever step reads it first. That first read lands
            // AFTER the input gate on a plain message, so with a cold cache its
            // per-binding Node probes (one runtime start-up each, serialized
            // under the process-wide runner lock) would all land between the
            // person's "Allow and Run" and the first visible progress — the
            // exact window the eye is on, and the source of the stall the
            // submit used to carry. Started here, the same probes overlap the
            // minutes the gate's form may hold the turn (or, with no form up,
            // the turn's own start-up), and the catalogue's probe mutex makes
            // the later readers JOIN this one rather than pay their own.
            //
            // A fire-and-forget job on the controller's scope, not the turn's
            // lifetime: a stopped turn still leaves the cache warm for the
            // next one. The invalidation discipline is unchanged — an install,
            // uninstall or edit that lands while the warm probes still wins,
            // because the catalogue's generation guard refuses to store a
            // probe an invalidation has overtaken.
            scope.launch {
                try {
                    pluginCatalog.sessionPlugins(sessionId)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // A warm that fails is only the cache miss the readers
                    // would have paid anyway — every real reader keeps its own
                    // runCatching and its own failure words, and a job whose
                    // whole point is to be early must not take the app down
                    // when the thing it warms is unavailable.
                }
            }
            // The chat's own input gate: an enabled binding whose required
            // inputs this chat never answered asks for them HERE — before the
            // row is written and before the model is called — and a form the
            // person does not complete means the message is NOT worked (the
            // notice the gate fires says so in the plugin's name). Every
            // session path runs through this one spot, tab adoption included,
            // so no chat with an unanswered module ever reaches the model. The
            // turn's own finally lifts what a turn holds, exactly the way the
            // headless-page refusal above does.
            if (!pluginUi.awaitChatInputs(sessionId)) return
            // Staged activation's one moment: the flag only ever means "this
            // chat's FIRST message runs the module", so the question is asked
            // BEFORE the row is written — a chat that already spoke has had its
            // moment, and letting it spend another chat's staging would run a
            // module mid-conversation that its own chat never got (the flag
            // lives on the plugin row, shared by every chat that enabled it).
            val chatIsFresh = runCatching { !repo.hasUserMessage(sessionId) }
                .getOrDefault(false)
            repo.addMessage(sessionId, "user", display)

            // A module staged by this chat's fresh enable runs with the
            // message that unstaged it — BEFORE the hooks and the model, so
            // the transcript's order is the one the module was written
            // against: user → module message → assistant reply. Later
            // messages never re-run it (the gate above, then the flag the
            // run clears), and a plugin.json row was never staged to begin
            // with.
            if (chatIsFresh) pluginUi.runStagedActivations(sessionId, text)

            // The chat's hooked plugins see the message BEFORE the model
            // does — sequential, budgeted, and never fatal to the turn: the
            // message was addressed to the agent, not to the plugin, and a
            // plugin that fails on it is said about and skipped.
            dispatchPluginMessageHooks(
                sessionId,
                text,
                turnGenerate,
                if (settings.chatHeadless) null else pluginUi.formHost
            )

            // The plugin executor wraps the page tools INNERMOST, so every
            // gate above it applies to a plugin tool exactly as it does to a
            // click: Plan mode wraps the WHOLE turn, the headless gate
            // included (that gate hands the app's own tools straight through,
            // and one of them reaches the page by that route); the action
            // budget wraps the whole turn too, the app's own tools included
            // since those reach the page. Plan mode stays outermost: an
            // action it refuses never ran, so it must not spend the budget.
            val pluginExecutor = PluginToolExecutor(
                inner = pageExecutor,
                runtime = pluginRuntime,
                catalog = pluginCatalog,
                sessionId = sessionId,
                // Read at call time: a tool call can arrive several steps
                // into the turn, and the conversation it should see is the
                // one that exists then.
                history = { pluginHistoryOf(sessionId) },
                onCards = { cards, name ->
                    cards.forEach { card -> appendPluginCard(sessionId, card, name) }
                },
                generate = { turnGenerate },
                // The interactive halves of the plugin tool path: the chat's
                // form host for the missing-inputs gate and a mid-run form —
                // wired only when the turn is an attended one, because a
                // headless turn has nobody to show a dialog to and its gate
                // answers the instructive refusal instead.
                form = if (settings.chatHeadless) null else pluginUi.formHost,
                repo = pluginRepo
            )
            val budget = ActionBudgetGate(pluginExecutor)
            // The profile's withheld agent permissions sit outside the budget
            // for the same reason Plan mode does: a tool refused for want of a
            // permission never ran, so it must not cost the turn a step.
            val permitted = AgentPermissionGate(budget) { vm.profileSettings().agentWithheldPermissions }
            val executor: ToolExecutor =
                if (settings.chatMode == AgentMode.PLAN) PlanModeToolGate(permitted) else permitted

            val engine = SearchEngines.byId(vm.profileSettings().searchEngineId).label
            val basePrompt = settings.systemPromptOverride?.takeIf { it.isNotBlank() }
                ?: AgentPrompts.render(System.currentTimeMillis(), ZoneId.systemDefault(), engine)
            val mode = settings.chatMode
            // A Plan turn says so up front: otherwise the model plans a
            // click, is refused, and spends its steps probing what is left.
            val modePrompt = when (mode) {
                AgentMode.PLAN -> basePrompt + AgentPrompts.PLAN_CLAUSE + AgentPrompts.TODO_CLAUSE
                else -> basePrompt + AgentPrompts.TODO_CLAUSE
            }
            // The chat's enabled plugins introduce themselves after the
            // prompt's own text — one block each, the order the chat enabled
            // them — so the model knows what else runs in the conversation
            // and treats its messages as data; a chat with none adds nothing,
            // and its prompt is byte-identical to the pre-plugin one. The
            // installed skills follow on the same terms: one fenced block per
            // enabled instruction skill, or nothing — null means no skill is
            // installed or enabled, and the prompt stays byte-identical to
            // the one before skills existed. One registry, the same pair the
            // executor chain's `skills` tool reads, so the prompt and a
            // listing can never disagree about what is installed.
            val prompt = modePrompt + pluginContextAddition(sessionId) +
                (SkillPrompts.systemPromptBlock(skillRegistry) ?: "")
            val config = com.roombrowser.domain.agent.AgentConfig(
                model = model,
                maxSteps = settings.maxSteps,
                temperature = settings.temperature,
                systemPrompt = prompt,
                // Work is not carried out before the list that says what the
                // work is; in Plan mode the list is the whole deliverable.
                planBeforeActions = mode != AgentMode.PLAN,
                planIsTheAnswer = mode == AgentMode.PLAN
            )

            // Rebuild the conversation: system + prior user/assistant rows +
            // this turn. The assembly itself lives in buildTurnHistory, where
            // the order — snapshot, then standing context, then attachments,
            // then the request — is pinned by unit tests.
            //
            // The prior rows are the chat's last MODEL_HISTORY_ROW_CAP rows,
            // read through the paged query and filtered/mapped off the main
            // thread — a chat shorter than the cap re-sends exactly what it
            // always did, in the same order, byte for byte; a longer one
            // re-sends its newest window and NOT the turns beyond the cap
            // (documented in android/docs/AI_AGENTS.md).
            val priorTurns = withContext(Dispatchers.Default) {
                repo.messagePage(sessionId, Long.MAX_VALUE, MODEL_HISTORY_ROW_CAP).rows
                    .filter { it.role == "user" || it.role == "assistant" }
                    .map { ChatMessage(role = it.role, content = it.content) }
            }
            val history = buildTurnHistory(
                prompt = prompt,
                priorTurns = priorTurns,
                pageSnapshot = when {
                    !includePage -> null
                    headless != null -> headless.snapshotContext()
                    else -> visibleExecutor.snapshotContext()
                },
                settings = settings,
                attachments = attachments,
                request = text,
                plan = st.plan
            )

            // The chat's plugin tools, resolved once here (each probe starts
            // the Node runtime; doing it per definition would restart the
            // runtime per step) and handed to the loop as the settled list
            // its per-request seam reads.
            val pluginTools = runCatching { pluginCatalog.pluginToolDefs(sessionId) }
                .getOrDefault(emptyList())
            val loop = AgentLoop(
                gateway = gateway,
                tools = executor,
                config = config,
                extraTools = { pluginTools },
                // A Pause is honoured here, BETWEEN steps: a tool that has
                // already started is never cut in half.
                beforeStep = { awaitUserResume() },
                // The budget's hard stop ends the turn with its own sentence
                // rather than leaving the model to hammer a refused action.
                stopRequested = { budget.stopped },
                stopReason = { budget.stopReason }
            )
            // The loop answers what the turn cost — real provider usage where
            // it reported any, the documented chars/4 estimate where it did
            // not. Booked onto [st], the chat that ran it, never onto
            // whichever chat the panel shows now; a turn cancelled out of the
            // loop loses its usage, which is the honest reading of an answer
            // nobody received.
            val turnUsage = loop.runTurn(history) { event ->
                when (event) {
                    is AgentEvent.AssistantText -> {
                        // The delta is buffered, not applied: one window of
                        // deltas becomes ONE entries update when the next
                        // flush fires (see AgentStreamCoalescer). setStatus
                        // below is per-token on purpose — it dedupes
                        // identical text at its own seam, so the repeated
                        // "Writing…" costs a string compare and nothing else.
                        stream.appendText(event.text)
                        setStatus("Writing…")
                    }
                    is AgentEvent.AssistantThinking -> {
                        stream.appendThinking(event.text)
                        setStatus("Thinking…")
                    }
                    is AgentEvent.ToolStarted -> {
                        // The bubble closes here, so its last window lands
                        // first: the finalize reads the entry's text, and the
                        // text it must read is every delta that arrived, not
                        // just the ones a flush already carried.
                        stream.flush()
                        st.streamingIndex = st.finalizeStreaming(st.streamingIndex ?: -1)
                        st.appendEntry(
                            AgentEntry.Tool(
                                callId = event.id,
                                name = event.name,
                                label = AgentTools.describeTool(event.name, event.argsJson),
                                running = true,
                                ok = true,
                                summary = "",
                                at = System.currentTimeMillis()
                            )
                        )
                        setStatus(AgentTools.describeTool(event.name, event.argsJson))
                    }
                    is AgentEvent.ToolFinished -> {
                        st.updateTool(event.id) { it.copy(running = false, ok = event.ok, summary = event.summary) }
                        setStatus("Step done")
                        repo.addMessage(
                            sessionId, "tool",
                            (if (event.ok) "" else "ERROR: ") + event.summary,
                            toolName = event.name,
                            toolResult = event.summary
                        )
                    }
                    is AgentEvent.PlanUpdated -> {
                        st.plan = event.items
                        // The row is the copy that outlives the process, so the
                        // card is still there when the chat is reopened and the
                        // model is shown its own list on the next turn.
                        repo.setSessionPlan(sessionId, AgentPlan.encode(event.items))
                    }
                    is AgentEvent.FinalAnswer -> {
                        // Same duty as a tool boundary, for a subtler reason:
                        // the finished answer REPLACES the open bubble's text
                        // but KEEPS its thinking, so the thinking buffer must
                        // be drained here or the final entry would silently
                        // lose whatever the last window was still holding.
                        stream.flush()
                        // The model sometimes copies the fence's frame back out
                        // of the tool result it just read.
                        val answer = UntrustedContent.stripMarkers(event.text)
                        // The answer is the bubble the user watched appear, so
                        // it lands in it rather than beside it — the state's
                        // own apply finds the open bubble wherever the panel
                        // has drifted to, because it reads THIS chat's list.
                        st.applyAnswer(st.streamingIndex ?: -1, answer, System.currentTimeMillis())
                        st.streamingIndex = -1
                        repo.addMessage(sessionId, "assistant", answer)
                        repo.touchSession(sessionId)
                        setStatus(null)
                    }
                    is AgentEvent.AgentError -> {
                        // The finally below reads the entry's text — drain
                        // first, exactly as a tool boundary does.
                        stream.flush()
                        st.streamingIndex = st.finalizeStreaming(st.streamingIndex ?: -1)
                        turnFailed = true
                        st.appendEntry(
                            AgentEntry.Notice(
                                text = "Agent error: ${event.message}",
                                error = true,
                                at = System.currentTimeMillis()
                            )
                        )
                        repo.addMessage(sessionId, "assistant", "⚠ error: ${event.message}")
                        setStatus(null)
                    }
                    is AgentEvent.Notice -> {
                        st.appendEntry(AgentEntry.Notice(event.text, error = false, at = System.currentTimeMillis()))
                    }
                }
            }
            st.addTokens(turnUsage)
            recordToday(turnUsage)
            repo.touchSession(sessionId)
            // The row is the authoritative total — it outlives this process
            // and is what the token dialog reads first — so the turn's cost
            // lands in the same single-UPDATE increment the in-memory state
            // just mirrored. Skipping this write would leave every chat's
            // persisted total at zero until restart, and the dialog would
            // then show the memory fallback for a chat whose row says 0.
            repo.addSessionTokens(sessionId, turnUsage.inputTokens, turnUsage.outputTokens)
            if (!turnFailed) st.retryable = null
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
            // the worst possible moment; the executor's tab is where the
            // turn's work ended, so an active tab that is neither it nor this
            // chat's own tab is the user's doing. A headless turn never moves
            // its own tab — the tab tools are refused — so the executor's tab
            // is still the one this chat started on.
            val nowActive = vm.activeTabId
            if (nowActive != null && nowActive != conversationTabId &&
                nowActive != visibleExecutor.currentTabId
            ) {
                showTabConversation(nowActive)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            st.appendEntry(
                AgentEntry.Notice(
                    text = "Agent error: ${t.friendlyMessage()}",
                    error = true,
                    at = System.currentTimeMillis()
                )
            )
        } finally {
            // Drain before anything else. This is the boundary every ending
            // shares — finish, failure, Stop — and the one the e2e suite's
            // transcript reads depend on: the persisted row is written from
            // events, but the on-screen bubble is written ONLY from these
            // flushes, so a turn that ended with text still in the buffers
            // would show (and Stop would freeze) a bubble missing its tail.
            // Plain calls, no suspension: safe to run while the cancellation
            // itself is unwinding, and after this the listener is gone for
            // good, so nothing can append behind the drain.
            //
            // The drain is guarded by the bubble still being open IN THE
            // TURN'S OWN STATE — the one structural win of the per-session
            // rewrite: a Stop or a chat switch can re-point the panel, but it
            // cannot reach into this chat's list, so the tail this finally
            // writes can never land in a conversation it never belonged to.
            // The guard still matters: stop() drops `running` the instant it
            // cancels and appends its own notice, and the bubble's last
            // window must land before that notice, not after it.
            val open = st.streamingIndex
            if (open != null && open >= 0 && open < st.entries.size &&
                (st.entries[open] as? AgentEntry.Assistant)?.streaming == true
            ) {
                stream.flush()
            }
            running = false
            st.running = false
            setStatus(null)
            st.approval = null
            // The hidden page belongs to the turn, so it goes with the turn —
            // it is never in the tab list, and a session left alive keeps a
            // renderer and its cookies' page state around for nothing.
            ownedPage?.let { runCatching { it.close() } }
            // The scratchpad belongs to the turn too, and a session left alive
            // keeps a renderer around for nothing.
            scratchpad?.close()
            // A turn that ends — finished, stopped or thrown — leaves nothing
            // waiting and nothing on screen claiming it is still paused.
            clearPause()
            AgentForeground.stopCurrentTurn = null
            AgentForeground.pauseCurrentTurn = null
            AgentForeground.finish()
            // The pin belongs to the turn, so it lifts with the turn —
            // including when the turn threw. Cleared by OWNER and not by tab
            // id on purpose: the turn may have rebound itself to a tab it
            // opened, and a task host's pin (the "task" owner) is a separate
            // slot this turn must never touch.
            vm.unpinTab(BrowserViewModel.PIN_OWNER_CHAT)
        }
    }

    /**
     * A hidden page for a headless turn: this profile's own engine, started at
     * the tab the user was on so the first read — and the "include the current
     * page" context — begin where they were.
     *
     * IT IS NEVER ATTACHED to a view, so nothing here can appear on the user's
     * screen or disturb the tab they are looking at, and because the process is
     * already bound to this profile it uses that profile's own engine context —
     * a signed-in page stays signed-in.
     *
     * HONEST LIMIT: a detached session still loads and runs JavaScript, but no
     * frame is ever drawn from it, so a page that only fills itself in from
     * `requestAnimationFrame` or an IntersectionObserver may stay empty.
     */
    private fun createHeadlessPage(): EngineSession? = runCatching {
        val session = ProfileEngine.createSession(
            context = appContext,
            profile = vm.profile,
            sessionId = "agent-chat-${System.currentTimeMillis()}",
            isPrivate = false
        )
        vm.pageState.url
            ?.takeIf { it.isNotBlank() && !it.startsWith("about:") }
            ?.let { session.loadUri(it) }
        session
    }.getOrNull()

    // ------------------------------------------------------------- chat plugins

    /**
     * The system-prompt addition for this chat's enabled plugins — one block
     * per binding, joined and worded by the catalogue's pure
     * [AgentPluginCatalog.systemPromptAddition]. Empty for a chat with no
     * enabled plugins, so the turn's prompt is unchanged when none are on;
     * reading it also settles the catalogue's cache for the tool definitions
     * the turn resolves further down (the same probe, paid once).
     */
    private suspend fun pluginContextAddition(sessionId: Long): String =
        AgentPluginCatalog.systemPromptAddition(
            runCatching { pluginCatalog.sessionPlugins(sessionId) }.getOrDefault(emptyList())
        )

    /**
     * The transcript as a `chat.context` grant reads it: the chat's last
     * MODEL_HISTORY_ROW_CAP rows through the paged query, shaped by the
     * runtime's own entry/char caps, with the read and the shaping off the
     * main thread. The same cap a turn re-sends (see the priorTurns read),
     * because both surfaces answer "what has this chat said" — a chat under
     * the cap hands a plugin byte-identically to what it always did; a longer
     * one hands it the newest window and not the turns beyond the cap.
     */
    private suspend fun pluginHistoryOf(sessionId: Long): List<PluginChatEntry> =
        withContext(Dispatchers.Default) {
            AgentPluginRuntime.historyOf(
                repo.messagePage(sessionId, Long.MAX_VALUE, MODEL_HISTORY_ROW_CAP).rows
            )
        }

    /**
     * The chat's message hooks: every plugin enabled with the `message` hook
     * sees the user's text BEFORE the turn starts — its cards join the
     * conversation, its failure is one honest notice line, and the turn runs
     * either way, because the message was addressed to the agent, not to the
     * plugin. Sequential on purpose: the plugins see the chat one after the
     * other under one budget each, never side by side under the runtime's
     * one-call-at-a-time lock.
     *
     * [generate] is the turn's own generator — the hook's 10 s budget will
     * usually not fit a 20 s generation, and the plugin then reads the
     * timeout words as the hook's failure rather than the turn never
     * starting, which is the honest order of those two disappointments.
     */
    private suspend fun dispatchPluginMessageHooks(
        sessionId: Long,
        text: String,
        generate: PluginGenerateCall?,
        form: PluginFormCall? = null
    ) {
        val hooked = runCatching {
            pluginCatalog.sessionPlugins(sessionId).filter { "message" in it.hooks }
        }.getOrDefault(emptyList())
        if (hooked.isEmpty()) return
        setStatus("Chat plugins are reading the message…")
        hooked.forEach { plugin ->
            runCatching {
                val result = pluginRuntime.run(
                    sessionId = sessionId,
                    plugin = plugin.plugin,
                    binding = plugin.binding,
                    event = PluginCallRequest.EVENT_MESSAGE,
                    payload = PluginPayloads.message(text),
                    history = pluginHistoryOf(sessionId),
                    budgetMs = AgentPluginRuntime.MESSAGE_HOOK_BUDGET_MS,
                    generate = generate,
                    form = form
                )
                if (result.ok) {
                    PluginCard.parseList(JsonArray(result.cards)).forEach { card ->
                        appendPluginCard(sessionId, card, plugin.plugin.name)
                    }
                } else {
                    note(
                        "${plugin.plugin.name} failed on the message: " +
                            result.output.lineSequence().firstOrNull().orEmpty(),
                        error = true
                    )
                }
            }
        }
        setStatus(null)
    }

    /**
     * The chat's own generator for plugin COMMANDS and activations — the
     * SESSION's provider and model, not the panel's current selection,
     * because a command can run in a chat whose turn is long gone and the
     * promise of `agent.generate` is "this chat's AI", not "whatever the
     * header happens to show".
     *
     * A fresh gateway per resolution, with NO retry and NO tuning: a
     * generation is one attempt by design, and the command path must not
     * inherit a turn's retry policy it was never part of. A missing session,
     * provider or model answers null — no handler is wired, and the
     * runtime's own ask is answered with the honest "agent generation is not
     * available" words rather than a guess this chat never made.
     */
    private suspend fun pluginGeneratorFor(sessionId: Long): PluginGenerateCall? {
        val session = runCatching { repo.session(sessionId) }.getOrNull() ?: return null
        val provider = runCatching { repo.provider(session.providerId) }.getOrNull() ?: return null
        val model = session.model.ifBlank { provider.defaultModel }
        if (model.isBlank()) return null
        val gateway = AgentGateways.forProvider(
            callFactory,
            provider,
            apiKeyFor(provider).orEmpty(),
            appContext = appContext
        )
        return PluginGenerateCall { request -> generateWith(gateway, model, request) }
    }

    /**
     * The chat's own composer for /make_plugins — the SESSION's provider and
     * model, not the panel's current selection, same rule as
     * [pluginGeneratorFor]: the promise is "this chat's AI". Fresh gateway,
     * no retry, no tuning; the maker's own one-retry rule lives above this
     * seam, in [PluginUiState.makePlugins].
     */
    private suspend fun pluginComposerFor(sessionId: Long): PluginComposeCall? {
        val session = runCatching { repo.session(sessionId) }.getOrNull() ?: return null
        val provider = runCatching { repo.provider(session.providerId) }.getOrNull() ?: return null
        val model = session.model.ifBlank { provider.defaultModel }
        if (model.isBlank()) return null
        val gateway = AgentGateways.forProvider(
            callFactory,
            provider,
            apiKeyFor(provider).orEmpty(),
            appContext = appContext
        )
        return PluginComposeCall { description, retryNote ->
            composeWith(gateway, model, description, retryNote)
        }
    }

    /**
     * One composition, design §5.3: the chat's own model asked ONCE,
     * non-streaming, no tools, temperature 0.2 — NOT [generateWith]'s 0.7:
     * composition is code, and the divergence is deliberate. The budget is
     * its own ([COMPOSE_BUDGET_MS] — code is slower than a 24-word sentence,
     * and no turn is running), the reply clamped to [COMPOSE_REPLY_CHARS]
     * above the parser's own code cap, and an empty answer refused rather
     * than returned, exactly as a generation is. Throws on failure; the
     * thrown words are what the maker's retry and definitive refusal read.
     */
    private suspend fun composeWith(
        gateway: AgentGateway,
        model: String,
        description: String,
        retryNote: String?
    ): String {
        val reply = withTimeout(COMPOSE_BUDGET_MS) {
            gateway.chat(
                ChatRequest(
                    model = model,
                    messages = PluginComposePrompt.messagesOf(description, retryNote),
                    stream = false,
                    tools = null,
                    temperature = 0.2
                ),
                events = {}
            )
        }
        val text = reply.content?.trim().orEmpty()
        require(text.isNotEmpty()) { "the chat's model wrote no text" }
        return text.take(COMPOSE_REPLY_CHARS)
    }

    /**
     * One generation, per design §2: the chat's own model asked ONCE,
     * non-streaming, no tools, temperature 0.7, the prompt rendered by
     * [PluginGeneratePrompt] from the clamped request — and an empty answer
     * refused rather than returned, because "the chat's model wrote no text"
     * is a failure the plugin can act on while an empty string would read
     * as success. Throws on failure; the thrown words are what the reverse
     * line's error envelope carries.
     */
    private suspend fun generateWith(
        gateway: AgentGateway,
        model: String,
        request: PluginGenerateRequest
    ): String {
        val reply = gateway.chat(
            ChatRequest(
                model = model,
                messages = PluginGeneratePrompt.messagesOf(request.toJson()),
                stream = false,
                tools = null,
                temperature = 0.7
            ),
            events = {}
        )
        val text = reply.content?.trim().orEmpty()
        require(text.isNotEmpty()) { "the chat's model wrote no text" }
        return text.take(2000)
    }

    /**
     * Appends one plugin card to the chat AND to the transcript, as a
     * `plugin`-role row whose content is the card's own JSON — the same
     * spelling [PluginCard.parse] reads — so reopening the chat renders it
     * again. The card's JSON is the persistable shape by design: the row and
     * the wire agree on one spelling, so nothing has to be re-derived later.
     */
    private suspend fun appendPluginCard(sessionId: Long, card: PluginCard, pluginName: String) {
        // The card belongs to the chat it was raised in — which is the session
        // id the caller resolved — so it lands in THAT chat's state, whether
        // or not its turn is the one running and whether or not the panel is
        // showing this chat right now.
        stateFor(sessionId)
            .appendEntry(AgentEntry.Plugin(card, pluginName, System.currentTimeMillis()))
        repo.addMessage(sessionId, "plugin", card.toJson().toString(), toolName = pluginName)
    }

    /**
     * The session row a plugin is being enabled into when the chat has none
     * yet — the picker opens on a brand-new chat and a plugin command never
     * sends a turn, so the binding needs its row before any message exists.
     * The row is bound to the tab the panel is on, so the next message
     * CONTINUES this chat instead of quietly starting a second one, and the
     * panel is taken here for the same reason a turn takes it: a tab resolve
     * still reading must not land on top of the chat this row just became.
     */
    private suspend fun createSessionForPlugins(title: String): Long? {
        val provider = activeProvider ?: run {
            messages.value = "Configure an AI provider first (Agent → settings)"
            return null
        }
        conversationRequest++
        val tabId = vm.activeTabId
        val session = repo.createSession(
            profileId = profileId.value,
            title = title.take(64),
            providerId = provider.id,
            model = (activeModel ?: provider.defaultModel).orEmpty(),
            tabId = tabId.orEmpty()
        )
        activeSessionId = session
        // The row the panel shows is the chat this is; adopting it is
        // continuing it, exactly as a turn would.
        pointApprovalsAt(session)
        tabId?.let { conversationTabId = it }
        return session
    }

    // ------------------------------------------------------------- entry helpers
    //
    // The old controller-level upsertStreamingAssistant / finalizeAssistant /
    // updateToolEntry are GONE: every one of them indexed and copy()-replaced
    // the ONE shared entries list, which is exactly the write a chat switch
    // mid-turn used to splice into the wrong chat. Their per-session
    // counterparts live on [ChatSessionState] itself — upsertStreaming,
    // finalizeStreaming, updateTool, applyAnswer — and the turn calls them on
    // the state it captured at its start.

    // ------------------------------------------------------------- approvals

    /**
     * Sets the in-app status line and mirrors it to the background
     * notification.
     *
     * Identical text is dropped on purpose. This one seam sits in front of a
     * path that rebuilds the whole notification — three fresh
     * PendingIntents and a NotificationManager.notify() binder round-trip —
     * and the streamed path used to call it per TOKEN with two constants
     * ("Writing…"/"Thinking…"), a rebuild storm at 30–80 calls a second for
     * a notification whose visible text never changed. The memory is
     * [statusLine] itself — the one thing the panel reads — so it cannot
     * drift behind a write that bypassed this seam: the only other writer
     * is stop()'s reset to null, and skipping the matching null the
     * turn's `finally` would have sent saves exactly the rebuild that
     * ending the turn was about to make pointless.
     */
    private fun setStatus(text: String?) {
        if (text == statusLine) return
        // The line belongs to the turn while one runs — wherever the panel
        // has drifted to — and to the visible chat between turns (the resolve
        // and plugin-command paths set interim lines before any turn exists).
        // The pre-turn writes land on the state the turn later captures, so
        // runTurn's own reset clears them there; nothing routes through two
        // states at once.
        (turnState ?: panelState).statusLine = text
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
                    showApproval(
                        AgentApproval(
                            name = name,
                            label = label,
                            at = SystemClock.elapsedRealtime(),
                            origin = originOfTurn()
                        ) { answer ->
                            if (continuation.isActive) continuation.resume(answer)
                        }
                    )
                }
            }
        } catch (ce: CancellationException) {
            ApprovalAnswer.Deny // timeout or turn cancelled → deny
        } finally {
            dismissApproval()
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
                    showApproval(
                        AgentApproval(WALLET_APPROVAL_NAME, label, SystemClock.elapsedRealtime()) { answer ->
                            if (continuation.isActive) {
                                when (answer) {
                                    ApprovalAnswer.Allow, ApprovalAnswer.AllowForSite ->
                                        continuation.resume(true)
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
                    )
                }
            }
        } catch (ce: CancellationException) {
            false
        } finally {
            dismissApproval()
        }
    }

    /**
     * Asks the user about an action that cannot be undone — closing the tabs
     * they opened, clearing history, removing a history entry, cancelling a
     * download, wiping the profile's site data.
     *
     * Deliberately NOT [requestApproval] with a flag, because the two answer
     * to different things. That one runs under the Confirm actions switch and
     * honours YOLO, and both of those are statements about not wanting to be
     * INTERRUPTED. This one asks a person every time, whatever either says:
     * the whole point of the destructive set is that no switch exists which
     * lets the agent delete the user's data unasked. The prompt offers Allow
     * and Deny only, and a timeout or a cancelled turn denies.
     */
    private suspend fun requestDestructiveApproval(name: String, label: String): ApprovalAnswer {
        setStatus("Approve? $label")
        return try {
            withTimeout(APPROVAL_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    showApproval(
                        AgentApproval(
                            name = name,
                            label = label,
                            at = SystemClock.elapsedRealtime(),
                            destructive = true
                        ) { answer ->
                            if (continuation.isActive) continuation.resume(answer)
                        }
                    )
                }
            }
        } catch (ce: CancellationException) {
            ApprovalAnswer.Deny // timeout or turn cancelled → deny
        } finally {
            dismissApproval()
        }
    }

    /**
     * The prompt lives in the chat whose turn raised it — [turnState], not
     * the visible chat — which is what lets the panel surface a RUNNING
     * turn's question above a transcript it does not belong to without ever
     * writing one into the wrong chat. Null turn state (the turn was stopped
     * between the gate's ask and this write) shows nothing; the wait times
     * out and denies, which is the same answer an unanswered prompt gets.
     */
    private fun showApproval(approval: AgentApproval) {
        turnState?.approval = approval
    }

    /** Retires whatever prompt this turn had up; a no-op when none is. */
    private fun dismissApproval() {
        turnState?.approval = null
    }

    private suspend fun destructiveVerdict(name: String, label: String): ActionVerdict =
        when (requestDestructiveApproval(name, label)) {
            // AlwaysAllow and AllowForSite are unreachable from the prompt this
            // raises; mapping them to a plain Allow is the safe reading — it can
            // never switch the asking off.
            ApprovalAnswer.Allow, ApprovalAnswer.AlwaysAllow, ApprovalAnswer.AllowForSite ->
                ActionVerdict.Allow
            ApprovalAnswer.Deny -> ActionVerdict.Deny("the user denied this action, which cannot be undone")
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
     *     exactly what rule 3 is for.
     *  2. The **session memory** ([SessionApprovals]): an answer the user
     *     already gave for this tool on this site, in this chat. It sits below
     *     the local gate so a policy denial still denies — a deny never
     *     interrupts anyone, so remembering an answer cannot take it away.
     *  3. **Confirm actions**, the blanket Allow/Deny prompt that has always
     *     been there. Its third answer, "Always allow", turns rule 0 on, and
     *     the narrower "Allow on this site" is written into rule 2.
     *
     * So the gate can only ever *add* a decision. Turning rule 1 on never
     * takes away a prompt the user asked for, with one deliberate exception:
     * an action the blanket rule would have asked about can now run unasked,
     * because the local model read the user's own policy and said it was
     * routine. That is the entire point of the feature, and it is why the
     * policy text is the user's to write.
     *
     * A gate that is switched on and cannot answer is NOT an error the user
     * has to clear — the turn continues under rule 3, and the chat says so.
     * The consequence is worth stating plainly, and `SECURITY.md` does: with
     * **Confirm actions off and the gate unreachable, actions run ungated**,
     * exactly as they did before this feature existed. Confirm actions is the
     * switch that gives a hard guarantee; this one buys fewer interruptions.
     */
    private suspend fun gate(name: String, label: String): ActionVerdict {
        if (settings.yolo) return ActionVerdict.Allow
        localVerdict(name, label)?.let { return it }
        val origin = originOfTurn()
        if (sessionApprovals.allows(origin, name)) return ActionVerdict.Allow
        return when (requestApproval(name, label)) {
            ApprovalAnswer.Allow -> ActionVerdict.Allow
            ApprovalAnswer.AllowForSite -> {
                sessionApprovals.remember(origin, name)
                note(
                    if (sessionApprovals.allows(origin, name)) {
                        "Allowed \"$name\" on $origin for this chat — it will not ask again here."
                    } else {
                        "Allowed once. There is no site to remember it by here, so the next " +
                            "action asks again."
                    },
                    error = false
                )
                ActionVerdict.Allow
            }
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
        // Same routing as the status line: the turn's chat while a turn runs
        // (a gate's answer belongs beside the action it answered about), the
        // visible chat between turns (plugin activations, command notices).
        (turnState ?: panelState)
            .appendEntry(AgentEntry.Notice(text, error = error, at = System.currentTimeMillis()))
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

        /**
         * How many transcript rows a chat opens with, and how many each
         * "load older" pages in — the transcript's RAM window. 200 rows is a
         * long afternoon's reading and an instant single query, where the
         * whole history was a re-read of megabytes on every tab switch; the
         * rest stays in the DB and comes back one tap at a time. A chat
         * shorter than this renders exactly the item list it always did.
         */
        internal const val TRANSCRIPT_WINDOW_ROWS = 200

        /**
         * The row cap on what a turn re-sends as prior turns, and on what a
         * `chat.context` grant reads — one number because both answer "what
         * has this chat said" to something off-device. A chat shorter than
         * this re-sends byte-identically to the uncapped reads; a longer one
         * re-sends its newest 500 rows, and the turns beyond the cap are NOT
         * re-sent (nor handed to plugins) — stated honestly in
         * android/docs/AI_AGENTS.md rather than left for someone to discover
         * from a token bill.
         */
        internal const val MODEL_HISTORY_ROW_CAP = 500

        /**
         * Name on the wallet approval prompt, distinct from a normal tool gate.
         * Internal because the prompt's own wording depends on it: "Always
         * allow" means "once" for this name and "stop asking" for the rest.
         */
        internal const val WALLET_APPROVAL_NAME = "wallet"

        /**
         * How long one local gate decision may take. Generous enough for the
         * first call of a turn, which may have to load a 9B model off disk
         * (later calls hit `keep_alive` and answer in ~100 ms), and short
         * enough that a server which never answers cannot stall a turn for
         * long — the action then falls back to the Confirm actions rule.
         */
        private const val DECISION_TIMEOUT_MS = 30_000L

        /**
         * How long one /make_plugins composition may run — its own budget,
         * because no turn is running while it does, and code is slower than
         * the sentence a plugin generation asks for. The maker gets TWO of
         * these at most (the attempt and its one corrective retry).
         */
        private const val COMPOSE_BUDGET_MS = 120_000L

        /**
         * The transport clamp on a composed reply — far above the parser's
         * own 128 KB code cap, which is the real line; this only keeps a
         * rambling model from billing the user for its preamble.
         */
        private const val COMPOSE_REPLY_CHARS = 200_000

        /**
         * What a headless chat may reach on its hidden page.
         *
         * A chat is attended, so it gets the page tools the visible chat has,
         * posting included; the person who sent the turn is there to be asked.
         * The wallet and the app tools are not decided here: the app's own
         * tools run through the visible executor's gates, and
         * [HeadlessChatToolGate] refuses the wallet and tab groups with the
         * reason that fits a hidden page.
         */
        private val HEADLESS_CHAT_PERMISSIONS = AiTaskPermissions(allowPost = true)
    }
}
