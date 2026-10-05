package com.roombrowser.agent

import android.os.SystemClock
import com.roombrowser.engine.EngineSession
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.PageEvent
import com.roombrowser.domain.agent.ActionVerdict
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.formatDurationMs
import com.roombrowser.domain.agent.KeyChord
import com.roombrowser.domain.agent.PageSnapshotDto
import com.roombrowser.domain.agent.PageSnapshotFormatter
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.engine.UrlIntelligence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.coroutines.resume

/**
 * Executes the agent's browser tools against the live engine state held by
 * [BrowserViewModel]: navigation (with page-finished waiting), page
 * snapshots via [PageInjector], element interaction, tab management and
 * web search.
 *
 * EVERY tool acts on ONE tab — [tabId], the tab the turn was started from —
 * and never on "the current tab". The user browses while a turn runs, and a
 * tool that followed the screen would edit a page the user never asked the
 * agent to touch: switch tabs mid-turn and the rest of the turn lands in the
 * wrong one, silently. [BrowserViewModel.tabSession] resolves the bound
 * tab's engine, which stays alive in the background, so reading and
 * scrolling keep working while the user is elsewhere. Only an action that
 * would START A NAVIGATION needs the tab on screen — a background engine is
 * detached from the view tree, and a load started on a detached view wedges
 * (see [MOVES_THE_PAGE]) — and those wait for the tab rather than acting on
 * the wrong page.
 *
 * All engine access happens on the main dispatcher; navigation results are
 * awaited by polling the ViewModel's page events (race-free by design).
 */
class AgentToolExecutor(
    private val vm: BrowserViewModel,
    /**
     * The tab this turn is bound to, or null when there was none to bind.
     * Reassigned only when the AGENT moves its own tab (see [bindTo]) — a
     * switch the user makes must never move the turn.
     */
    private var tabId: String? = null,
    /** Narration for waits long enough to look like a hang. */
    private val onStatus: (String) -> Unit = {},
    private val confirmGate: suspend (name: String, label: String) -> ActionVerdict,
    /**
     * Wallet approvals ask the user directly, never through [confirmGate]:
     * YOLO, the local decision model and "Confirm actions: off" all bypass
     * that gate, and none of them may approve a wallet request. Default DENY.
     */
    private val walletConfirm: suspend (label: String) -> Boolean = { false },
    /**
     * The gate for actions that cannot be undone. Deliberately a DIFFERENT
     * callback from [confirmGate]: this one always reaches a person, whatever
     * the app's Confirm actions switch and whatever YOLO mode says.
     */
    private val destructiveGate: suspend (name: String, label: String) -> ActionVerdict,
    /** This profile's own notes and authenticator accounts; see [AgentProfileData]. */
    private val profileData: AgentProfileData? = null,
    /** Whether the digits of a generated code may be returned to the model. */
    private val otpDigitsAllowed: suspend () -> Boolean = { false }
) : ToolExecutor {

    private val appTools by lazy {
        AgentAppTools(
            vm = vm,
            boundTabId = { tabId },
            onStatus = onStatus,
            confirmGate = confirmGate,
            destructiveGate = destructiveGate,
            profileData = profileData,
            otpDigitsAllowed = otpDigitsAllowed,
            fillField = { ref, text -> rawFillField(ref, text) }
        )
    }

    /**
     * The tab this turn is acting on right now: its start tab, or a tab the
     * agent moved itself to since (see [bindTo]).
     *
     * The controller reads it when a turn ends, to tell "the user walked away
     * from this chat while it ran" from "this chat's work moved to a tab of
     * its own" — the two look identical from [BrowserViewModel.activeTabId]
     * alone, and they want opposite treatment.
     */
    val currentTabId: String? get() = tabId

    /** Built on first use: `vm.walletEngine` loads the whole crypto stack. */
    private val walletTools: AgentWalletTools by lazy {
        AgentWalletTools(
            wallet = { WalletEngineAccess(vm.walletEngine) },
            confirmApproval = walletConfirm
        )
    }

    override suspend fun execute(name: String, argsJson: String): ToolResult =
        withContext(Dispatchers.Main) {
            val args: Map<String, Any?> = parseArgs(argsJson)
            try {
                // Asked once, here, rather than inside each tool: "needs the
                // tab on screen" is a property of the tool, and one place that
                // answers it cannot drift out of step with the list.
                if (name in MOVES_THE_PAGE || name in NEEDS_SCREEN_TAB) {
                    awaitBoundTabActive()?.let { return@withContext it }
                }
                appTools.execute(name, argsJson)?.let { return@withContext it }
                when (name) {
                    AgentTools.NAVIGATE -> navigate(str(args, "url"))
                    AgentTools.SEARCH_WEB -> searchWeb(str(args, "query"))
                    AgentTools.READ_PAGE -> readPage()
                    AgentTools.CLICK -> click(int(args, "ref"))
                    AgentTools.FILL_INPUT -> fillInput(int(args, "ref"), str(args, "text"))
                    AgentTools.PRESS_ENTER -> pressEnter(intOrNull(args, "ref"))
                    AgentTools.SCROLL -> scroll(str(args, "direction") ?: "down", intOrNull(args, "amount"))
                    AgentTools.GO_BACK -> goBack()
                    AgentTools.OPEN_NEW_TAB -> openNewTab(strOrNull(args, "url"))
                    AgentTools.LIST_TABS -> listTabs()
                    AgentTools.SWITCH_TAB -> switchTab(int(args, "index"))
                    AgentTools.CLOSE_TAB -> closeTab()
                    AgentTools.AUTO_LIKE -> autoLike()
                    AgentTools.AUTO_REPOST -> autoRepost()
                    AgentTools.AUTO_REPLY -> autoReply(str(args, "text"))
                    AgentTools.AUTO_POST -> autoPost(str(args, "text"))
                    AgentTools.WAIT -> waitTool(intOrNull(args, "ms"))
                    AgentTools.WALLET_STATE -> walletTools.readState()
                    AgentTools.WALLET_REQUESTS -> walletTools.listRequests()
                    AgentTools.WALLET_APPROVE -> walletTools.approve(str(args, "request_id"))
                    AgentTools.WALLET_REJECT -> walletTools.reject(str(args, "request_id"))
                    AgentTools.WALLET_SWITCH_NETWORK -> walletTools.switchNetwork(str(args, "network_id"))
                    AgentTools.RUN_JS -> runJs(str(args, "script"))
                    AgentTools.SELECT_OPTION -> selectOption(int(args, "ref"), str(args, "value"))
                    AgentTools.PRESS_KEYS -> pressKeys(str(args, "keys"), intOrNull(args, "ref"))
                    AgentTools.WAIT_FOR -> waitFor(str(args, "text"), intOrNull(args, "timeout_ms"))
                    else -> ToolResult(false, "unknown tool: $name")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                ToolResult(false, "${t.message ?: t.javaClass.simpleName}")
            }
        }

    /**
     * The app's own tools alone, or null when [name] is not one of them.
     *
     * Exposed for a HEADLESS turn, which drives a hidden page instead of the
     * tab on screen and so cannot go through [execute] — but whose app tools
     * are the same ones, on the same gates, because they act on the browser
     * rather than on any page.
     */
    suspend fun executeAppTool(name: String, argsJson: String): ToolResult? =
        appTools.execute(name, argsJson)

    /** Snapshot text used for the "include current page" context feature. */
    suspend fun snapshotContext(): String? = withContext(Dispatchers.Main) {
        formatSnapshot()?.let { "Current page:\n$it" }
    }

    // ------------------------------------------------------------- tools

    private suspend fun navigate(rawUrl: String?): ToolResult {
        val url = normalizeUrl(rawUrl)
            ?: return ToolResult(false, "missing or invalid 'url' argument")
        val triggerAt = SystemClock.elapsedRealtime()
        vm.loadUrl(url)
        val settled = awaitPageSettle(triggerAt)
        delay(SETTLE_MS)
        // Where the tab LANDED, read from this tab's own engine: pageState
        // belongs to the tab on screen, and the user may have switched away
        // while the page was loading — the report would then name the page
        // they switched TO.
        val landed = tabId?.let { vm.tabSession(it) }
        val landedUrl = landed?.url?.takeIf { it.isNotBlank() } ?: url
        return if (settled) {
            ToolResult(
                true,
                "Navigated to $landedUrl — \"${landed?.title.orEmpty()}\". Call read_page to inspect the content."
            )
        } else {
            ToolResult(
                false,
                "Navigation to $url is still loading (timeout). Call read_page to check what loaded."
            )
        }
    }

    private suspend fun searchWeb(rawQuery: String?): ToolResult {
        val query = nonBlank(rawQuery) ?: return ToolResult(false, "missing 'query' argument")
        val engineId = vm.profileSettings().searchEngineId
        val url = UrlIntelligence.classify(query, engineId).second
        if (url.isBlank()) return ToolResult(false, "could not build a search URL for the query")
        return navigate(url)
    }

    private suspend fun readPage(): ToolResult {
        val formatted = formatSnapshot()
            ?: return ToolResult(
                false,
                "No readable page in this chat's tab. It is on the start page, still loading, or JavaScript is disabled. Use navigate first and wait for it to finish."
            )
        return ToolResult(true, formatted)
    }

    private suspend fun click(ref: Int?): ToolResult {
        if (ref == null) return ToolResult(false, "missing 'ref' argument")
        refuse(AgentTools.CLICK, AgentTools.describeTool(AgentTools.CLICK, "{\"ref\":$ref}"))?.let { return it }
        val session = boundSession() ?: return ToolResult(false, "no page is loaded in this chat's tab")
        val triggerAt = SystemClock.elapsedRealtime()
        val jsResult = evaluateJs(session, PageInjector.clickJs(ref))
            ?: return ToolResult(false, "click failed (JavaScript error or page still loading)")
        val outcome = unquote(jsResult)
        awaitPageSettle(triggerAt)
        delay(SETTLE_MS)
        return ToolResult(true, outcome)
    }

    private suspend fun fillInput(ref: Int?, text: String?): ToolResult {
        if (ref == null || text == null) {
            return ToolResult(false, "missing 'ref' or 'text' argument")
        }
        refuse(AgentTools.FILL_INPUT, "type into [$ref]")?.let { return it }
        return rawFillField(ref, text)
    }

    /**
     * The fill itself, with no gate in front of it. Separate from [fillInput]
     * because `app_2fa action=fill` types a code the owner decided the agent
     * may always place — an action that is a read, so it must not raise the
     * "clicking and typing" prompt.
     */
    private suspend fun rawFillField(ref: Int, text: String): ToolResult {
        val session = boundSession() ?: return ToolResult(false, "no page is loaded in this chat's tab")
        val jsonText = AgentJson.encodeToString(String.serializer(), text)
        val jsResult = evaluateJs(session, PageInjector.fillJs(ref, jsonText))
            ?: return ToolResult(false, "fill failed (JavaScript error or page still loading)")
        return ToolResult(true, unquote(jsResult))
    }

    private suspend fun pressEnter(ref: Int?): ToolResult {
        refuse(AgentTools.PRESS_ENTER, "press Enter / submit")?.let { return it }
        val session = boundSession() ?: return ToolResult(false, "no page is loaded in this chat's tab")
        val triggerAt = SystemClock.elapsedRealtime()
        val jsResult = evaluateJs(session, PageInjector.enterJs(ref))
            ?: return ToolResult(false, "enter failed (JavaScript error)")
        awaitPageSettle(triggerAt)
        delay(SETTLE_MS)
        return ToolResult(true, unquote(jsResult))
    }

    private suspend fun scroll(direction: String?, amount: Int?): ToolResult {
        val session = boundSession() ?: return ToolResult(false, "no page is loaded in this chat's tab")
        val percent = (amount ?: 80).coerceIn(10, 300)
        val dy = (session.view.height * percent / 100) * (if (direction == "up") -1 else 1)
        val jsResult = evaluateJs(session, PageInjector.scrollJs(dy))
            ?: return ToolResult(false, "scroll failed")
        return ToolResult(true, unquote(jsResult))
    }

    private suspend fun goBack(): ToolResult {
        val session = boundSession() ?: return ToolResult(false, "no page is loaded in this chat's tab")
        if (!session.canGoBack) return ToolResult(true, "already at the first page in this tab")
        val triggerAt = SystemClock.elapsedRealtime()
        session.goBack()
        awaitPageSettle(triggerAt)
        delay(SETTLE_MS)
        // This tab's engine, not pageState — see [navigate] for why.
        return ToolResult(true, "went back to ${session.url.orEmpty()}")
    }

    private suspend fun openNewTab(url: String?): ToolResult {
        val target = url?.takeIf { it.isNotBlank() }?.let { normalizeUrl(it) } ?: "about:home"
        vm.openNewTab(target)
        // A tab opened TO BE WORKED IN is the tab this chat now means. Without
        // this the chat would sit bound to the tab it just left and gate its
        // own next navigation — the tool would open a tab nothing ever acts on.
        vm.activeTabId?.let { bindTo(it) }
        delay(SETTLE_MS)
        return ToolResult(true, "opened a new tab at ${vm.pageState.url.ifBlank { target }}")
    }

    private fun listTabs(): ToolResult {
        if (vm.tabs.isEmpty()) return ToolResult(true, "no open tabs")
        val lines = vm.tabs.mapIndexed { index, tab ->
            // Both marks, because they are no longer the same tab: the chat
            // keeps working in the tab it was started on while the user reads
            // another one, and a single "(current)" would name the wrong one.
            val marks = buildList {
                if (tab.id == tabId) add("this chat")
                if (tab.id == vm.activeTabId) add("on screen")
            }
            val suffix = if (marks.isEmpty()) "" else " (${marks.joinToString(", ")})"
            "[$index] ${tab.title.ifBlank { tab.url }} — ${tab.url}$suffix"
        }
        return ToolResult(true, "Open tabs:\n" + lines.joinToString("\n"))
    }

    private suspend fun switchTab(index: Int?): ToolResult {
        if (index == null) return ToolResult(false, "missing 'index' argument")
        val tab = vm.tabs.getOrNull(index)
            ?: return ToolResult(false, "no tab with index $index — call list_tabs for current indices")
        // Chosen by the agent, so the chat moves with it (see [bindTo]); a tab
        // the USER picks never does.
        bindTo(tab.id)
        vm.selectTab(tab.id)
        awaitPageSettle(SystemClock.elapsedRealtime() - 1500)
        return ToolResult(true, "switched to tab [$index]: ${tab.title.ifBlank { tab.url }}")
    }

    private suspend fun closeTab(): ToolResult {
        // THIS chat's tab, not the tab on screen: closing the active one would
        // shut a page the user is reading to tidy up a tab they are not.
        val id = tabId ?: vm.activeTabId ?: return ToolResult(false, "no open tab")
        if (!vm.tabExists(id)) return ToolResult(false, "this chat's tab is already closed")
        vm.closeTab(id)
        return ToolResult(true, "closed the tab this chat was working on")
    }

    // ------------------------------------------------------------- social automation

    private suspend fun autoLike(): ToolResult = socialAction(
        AgentTools.AUTO_LIKE, "like the visible posts"
    ) { session -> evaluateJs(session, PageInjector.autoLikeJs()) }

    private suspend fun autoRepost(): ToolResult = socialAction(
        AgentTools.AUTO_REPOST, "repost the visible posts"
    ) { session -> evaluateJs(session, PageInjector.autoRepostJs()) }

    private suspend fun autoReply(text: String?): ToolResult {
        if (text == null) return ToolResult(false, "missing 'text' argument")
        return socialAction(
            AgentTools.AUTO_REPLY, "reply with \"" + text.replace('\n', ' ').take(40) + "\"", 900L
        ) { session ->
            val jsonText = AgentJson.encodeToString(String.serializer(), text)
            evaluateJs(session, PageInjector.autoReplyJs(jsonText))
        }
    }

    private suspend fun autoPost(text: String?): ToolResult {
        if (text == null) return ToolResult(false, "missing 'text' argument")
        return socialAction(
            AgentTools.AUTO_POST, "post \"" + text.replace('\n', ' ').take(40) + "\"", 2400L
        ) { session ->
            val jsonText = AgentJson.encodeToString(String.serializer(), text)
            evaluateJs(session, PageInjector.autoPostJs(jsonText))
        }
    }

    /** Runs one heuristic social action: gate → JS → settle → result. */
    private suspend fun socialAction(
        name: String,
        label: String,
        settleMs: Long = 0L,
        js: suspend (EngineSession) -> String?
    ): ToolResult {
        refuse(name, label)?.let { return it }
        val session = boundSession()
            ?: return ToolResult(false, "no page is loaded in this chat's tab — navigate to the site first")
        val triggerAt = SystemClock.elapsedRealtime()
        val jsResult = js(session)
            ?: return ToolResult(false, "$name failed (JavaScript error or page still loading)")
        awaitPageSettle(triggerAt)
        if (settleMs > 0) delay(settleMs)
        return ToolResult(true, unquote(jsResult))
    }

    private suspend fun waitTool(ms: Int?): ToolResult {
        val bounded: Long = (ms ?: 1500).coerceIn(200, 20_000).toLong()
        delay(bounded)
        // Same seconds-not-milliseconds rendering as the tool card label, so
        // the request the user approved and the result they read agree.
        return ToolResult(true, "waited ${formatDurationMs(bounded.toInt())}")
    }

    // ------------------------------------------------- direct page control

    /**
     * Runs a script the model wrote. NOT settled afterwards: a script that
     * starts a navigation gets its own page events, and waiting on every
     * read-only script would cost the foreground window for nothing — the
     * tool's answer says to follow up with wait_for or read_page.
     */
    private suspend fun runJs(script: String?): ToolResult {
        val code = nonBlank(script) ?: return ToolResult(false, "missing 'script' argument")
        val session = boundSession() ?: return ToolResult(false, "no page is loaded in this chat's tab")
        val raw = evaluateJs(session, PageInjector.runJs(jsonString(code)))
            ?: return ToolResult(false, "run_js failed (JavaScript error or page still loading)")
        return ToolResult(true, clip(unquote(raw)))
    }

    private suspend fun selectOption(ref: Int?, value: String?): ToolResult {
        if (ref == null || value == null) {
            return ToolResult(false, "missing 'ref' or 'value' argument")
        }
        refuse(AgentTools.SELECT_OPTION, "choose \"${value.take(40)}\" in [$ref]")?.let { return it }
        val session = boundSession() ?: return ToolResult(false, "no page is loaded in this chat's tab")
        val triggerAt = SystemClock.elapsedRealtime()
        val raw = evaluateJs(session, PageInjector.selectOptionJs(ref, jsonString(value)))
            ?: return ToolResult(false, "select_option failed (JavaScript error or page still loading)")
        // A select's change handler is a submit by another name.
        awaitPageSettle(triggerAt)
        delay(SETTLE_MS)
        return ToolResult(true, unquote(raw))
    }

    private suspend fun pressKeys(keys: String?, ref: Int?): ToolResult {
        val chord = KeyChord.parse(keys) ?: return ToolResult(
            false,
            "unsupported key chord '${keys.orEmpty()}'. Supported keys: ${KeyChord.SUPPORTED_KEYS}"
        )
        refuse(AgentTools.PRESS_KEYS, "press ${chord.label()}")?.let { return it }
        val session = boundSession() ?: return ToolResult(false, "no page is loaded in this chat's tab")
        val triggerAt = SystemClock.elapsedRealtime()
        val script = PageInjector.pressKeysJs(
            ref, jsonString(chord.key), jsonString(chord.code), chord.keyCode,
            chord.ctrl, chord.shift, chord.alt, chord.meta, jsonString(chord.label())
        )
        val raw = evaluateJs(session, script)
            ?: return ToolResult(false, "press_keys failed (JavaScript error or page still loading)")
        awaitPageSettle(triggerAt)
        delay(SETTLE_MS)
        return ToolResult(true, unquote(raw))
    }

    private suspend fun waitFor(text: String?, timeoutMs: Int?): ToolResult {
        val needle = nonBlank(text) ?: return ToolResult(false, "missing 'text' argument")
        val timeout = (timeoutMs ?: DEFAULT_WAIT_FOR_MS).coerceIn(500, 30_000).toLong()
        val session = boundSession() ?: return ToolResult(false, "no page is loaded in this chat's tab")
        val probe = PageInjector.waitProbeJs(jsonString(needle))
        val startedAt = SystemClock.elapsedRealtime()
        val found = withTimeoutOrNull(timeout) {
            var hit = false
            while (!hit) {
                hit = unquote(evaluateJs(session, probe).orEmpty()).trim() == "1"
                if (!hit) delay(POLL_MS)
            }
            true
        } ?: false
        val waited = SystemClock.elapsedRealtime() - startedAt
        return if (found) {
            ToolResult(true, "\"$needle\" appeared after ${formatDurationMs(waited.toInt())}")
        } else {
            ToolResult(
                false,
                "\"$needle\" did not appear within ${formatDurationMs(waited.toInt())} — the page may " +
                    "load it later, show it only after a click, or never show it at all."
            )
        }
    }

    // ------------------------------------------------------------- helpers

    /**
     * Asks the gate about one action, returning the refusal to hand back to
     * the model — or null when the action may run.
     *
     * The reason travels: a refusal the model cannot read is a refusal it
     * repeats. "the user denied this action" tells a model to stop;
     * "judged outside what you asked for" tells it to try something else,
     * which is what a policy denial is usually for.
     */
    private suspend fun refuse(name: String, label: String): ToolResult? =
        when (val verdict = confirmGate(name, label)) {
            is ActionVerdict.Allow -> null
            is ActionVerdict.Deny -> ToolResult(false, verdict.reason)
            // Ask is resolved by the caller (it owns the approval UI), so a
            // verdict that reaches here is a bug — deny rather than run.
            is ActionVerdict.Ask -> ToolResult(false, "the user denied this action")
        }

    /**
     * The engine of THIS turn's tab, or null when it has no page.
     *
     * Two questions, because the turn's tab is not always the tab on screen.
     * For a BACKGROUND tab the engine's OWN url is the only answer available
     * — pageState describes the tab on screen, and asking it about another
     * tab answers about a different page. For the tab on screen pageState is
     * the sharper answer, and the inline comment below says why it is still
     * consulted for that one case.
     */
    private fun boundSession(): EngineSession? {
        val id = tabId ?: return null
        // pageState describes the tab on SCREEN, so it is only meaningful for
        // that tab — asking it about a background one answers about a
        // different page. Where it IS meaningful it is the sharper answer: a
        // tab returned to the start page keeps its engine alive holding the
        // page the user left, and reading that would report content the tab
        // is no longer showing.
        if (vm.isActiveTab(id) && vm.pageState.isHomepage) return null
        val session = vm.tabSession(id) ?: return null
        val url = session.url ?: return null
        return session.takeIf { url.isNotBlank() && !url.startsWith("about:") }
    }

    /**
     * Waits for this turn's tab to become the one on screen, returning the
     * refusal to hand the model when it never does.
     *
     * WAITING, not failing at once, because a glance at another tab is
     * ordinary and the agent should not lose its place to it. WAITING, not
     * switching the tab for the user, because pulling the screen out from
     * under someone who deliberately moved away is a worse answer than a tool
     * error they can read — and the action would otherwise have to run on a
     * tab it was not asked about. The window is long enough to cover the
     * glance and short enough that a turn is not held hostage by one.
     */
    private suspend fun awaitBoundTabActive(): ToolResult? {
        val id = tabId ?: return null
        if (vm.isActiveTab(id)) return null
        if (!vm.tabExists(id)) {
            return ToolResult(
                false,
                "The tab this chat was working on was closed, so this action was not run. Open a tab and send a new message to continue."
            )
        }
        onStatus("Waiting for this chat's tab to come to the front…")
        val cameForward = withTimeoutOrNull(TAB_FOREGROUND_WAIT_MS) {
            while (!vm.isActiveTab(id) && vm.tabExists(id)) delay(POLL_MS)
            vm.isActiveTab(id)
        } ?: false
        if (cameForward) return null
        return ToolResult(
            false,
            "This chat is bound to its own tab and the user is looking at a different one, so this action was not run — starting a page load on a tab that is not on screen is how the wrong page moves. read_page and scroll still work on this chat's tab. Ask the user to switch back to it, or answer with what you already have."
        )
    }

    /**
     * Moves this turn onto [id], pinning it — used only where the AGENT
     * chooses a tab for itself ([openNewTab], [switchTab]). Without it the
     * chat would be left bound to a tab the agent walked away from, and every
     * later navigation would be waiting for a tab it no longer means.
     */
    private fun bindTo(id: String) {
        tabId = id
        vm.pinTabForAgent(id)
    }

    private suspend fun formatSnapshot(): String? {
        val session = boundSession() ?: return null
        if (session.progress < 100) {
            // The engine's OWN progress, not pageState: a background tab has
            // no pageState of its own, so the old check would wait on the
            // active tab's load — or skip the wait for this tab's.
            withTimeoutOrNull(4000) {
                while (session.progress < 100) delay(POLL_MS)
            }
        }
        val raw = evaluateJs(session, PageInjector.snapshotJs()) ?: return null
        if (raw.isBlank() || raw == "null" || raw == "undefined") return null
        val snapshot = runCatching {
            AgentJson.decodeFromString(PageSnapshotDto.serializer(), raw)
        }.getOrNull() ?: return null
        return PageSnapshotFormatter.format(snapshot)
    }

    /** Evaluates JS on the session, suspending until the callback fires. */
    private suspend fun evaluateJs(session: EngineSession, script: String): String? =
        suspendCancellableCoroutine { continuation ->
            try {
                session.evaluateJs(script) { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    /** The engine returns string results JSON-encoded — undo that. */
    private fun unquote(jsResult: String): String = runCatching {
        if (jsResult.length >= 2 && jsResult.startsWith("\"") && jsResult.endsWith("\"")) {
            AgentJson.decodeFromString(String.serializer(), jsResult)
        } else jsResult
    }.getOrDefault(jsResult)

    /** Encodes a value for embedding in a script — quotes and newlines included. */
    private fun jsonString(value: String): String =
        AgentJson.encodeToString(String.serializer(), value)

    /** A script's own output is not a place to spend the context window. */
    private fun clip(text: String): String =
        if (text.length <= MAX_JS_RESULT_CHARS) {
            text
        } else {
            text.take(MAX_JS_RESULT_CHARS) + "\n…[truncated at $MAX_JS_RESULT_CHARS characters]"
        }

    /**
     * Waits for a navigation that started at/after [triggerAt] to finish.
     * Pure-JS actions (no navigation) resolve immediately after a short
     * window with no page start event.
     *
     * KNOWN LIMIT: these page events carry no tab id — they describe the tab
     * on screen — so a user who switches tabs DURING a load can make this
     * settle on the other tab's event. That costs an accurate wait, never a
     * wrong action: every caller has already established that this turn's tab
     * is the one on screen before a navigation starts, and the results above
     * are reported from this tab's own engine rather than from pageState.
     */
    private suspend fun awaitPageSettle(triggerAt: Long): Boolean {
        val navigationStarted = pollUntil(NAV_START_WINDOW_MS) { event ->
            event is PageEvent.Started && event.at >= triggerAt
        }
        if (!navigationStarted) return true // JS-only action, no navigation
        return pollUntil(NAV_FINISH_TIMEOUT_MS) { event ->
            event is PageEvent.Finished && event.at >= triggerAt
        }
    }

    private suspend fun pollUntil(timeoutMs: Long, predicate: (PageEvent?) -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate(vm.lastPageEvent)) return true
            if (!kotlin.coroutines.coroutineContext.isActive) return false
            delay(120)
        }
        return predicate(vm.lastPageEvent)
    }

    private fun normalizeUrl(raw: String?): String? {
        val input = nonBlank(raw)?.trim() ?: return null
        return when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.startsWith("about:") -> input
            input.contains('.') && !input.contains(' ') -> "https://$input"
            else -> null
        }
    }

    private fun nonBlank(raw: String?): String? = raw?.takeIf { it.isNotBlank() }

    private fun parseArgs(argsJson: String): Map<String, Any?> = runCatching {
        if (argsJson.isBlank()) return emptyMap()
        val element = AgentJson.parseToJsonElement(argsJson)
        if (element !is JsonObject) return emptyMap()
        element.entries.associate { (key, value) ->
            val v: Any? = when (value) {
                is JsonPrimitive -> value.intOrNull ?: value.booleanOrNull ?: value.contentOrNull
                else -> value.toString()
            }
            key to v
        }
    }.getOrDefault(emptyMap())

    private fun str(args: Map<String, Any?>, key: String): String? =
        (args[key] as? String)?.takeIf { it.isNotBlank() }

    private fun strOrNull(args: Map<String, Any?>, key: String): String? = args[key] as? String

    private fun int(args: Map<String, Any?>, key: String): Int? = intOrNull(args, key)

    private fun intOrNull(args: Map<String, Any?>, key: String): Int? =
        (args[key] as? Number)?.toInt() ?: (args[key] as? String)?.toIntOrNull()

    companion object {
        private const val SETTLE_MS = 350L
        private const val NAV_START_WINDOW_MS = 1800L
        private const val NAV_FINISH_TIMEOUT_MS = 25_000L

        /** Poll interval shared by every wait in this class. */
        private const val POLL_MS = 150L

        /** wait_for's default budget, and the ceiling a run_js result is cut to. */
        private const val DEFAULT_WAIT_FOR_MS = 10_000
        private const val MAX_JS_RESULT_CHARS = 4000

        /**
         * How long an action that moves the page waits for this chat's tab to
         * come to the front before refusing it. Long enough to cover a glance
         * at another tab, short enough that a turn is not held hostage by one.
         */
        private const val TAB_FOREGROUND_WAIT_MS = 8_000L
    }
}

/**
 * The tools that can START A NAVIGATION in the tab they act on, and so may
 * only run while that tab is the one on screen (see
 * [AgentToolExecutor.awaitBoundTabActive]).
 *
 * WHY THE RULE EXISTS: a page load started on an engine with no parent wedges
 * permanently on the WebView 83 stack once the view attaches mid-flight —
 * renderer spawned, onPageStarted fired, then no commit, no finish, no error,
 * an empty surface forever (see BrowserViewModel.runWhenAttached, CI
 * 36833914913). A background tab's engine is exactly that parentless view:
 * WebViewHost hosts the ACTIVE engine only, so every other engine is
 * detached. `click` and `fill_input` are on the list because a click on a
 * link, or a submit, IS a page load by another name, and `select_option` and
 * `press_keys` are the same thing reached by a dropdown or a chord.
 *
 * The rest — read_page, scroll, list_tabs, open_new_tab, switch_tab,
 * close_tab, wait, and the wallet tools — start no navigation of their own and
 * stay available while the user is elsewhere, which is what lets a turn keep
 * working in the background instead of stalling on a tab switch.
 *
 * Top-level and `internal` rather than private to the class so that
 * [AgentToolForegroundPolicyTest] can hold it to the full tool list: a tool
 * added later must be classified deliberately, because the wrong answer is
 * either a wedged engine or a needless wait.
 */
internal val MOVES_THE_PAGE: Set<String> = setOf(
    AgentTools.NAVIGATE,
    AgentTools.SEARCH_WEB,
    AgentTools.CLICK,
    AgentTools.FILL_INPUT,
    AgentTools.PRESS_ENTER,
    AgentTools.GO_BACK,
    AgentTools.SELECT_OPTION,
    AgentTools.PRESS_KEYS,
    AgentTools.AUTO_LIKE,
    AgentTools.AUTO_REPOST,
    AgentTools.AUTO_REPLY,
    AgentTools.AUTO_POST
)

/**
 * The tools that act on the page the user is LOOKING AT rather than on this
 * chat's own engine: the find bar, reader and desktop mode, the page's
 * bookmark state, the shields and permission records of the site in the
 * visible tab.
 *
 * That is not a shortcut — it is what these features ARE. The ViewModel keeps
 * one find bar, one reader mode and one shields view, all describing the tab
 * on screen, and the per-site records are keyed by that tab's host. Holding
 * them until this chat's tab is the visible one is what makes the tool's
 * answer about the page the model has been reading.
 *
 * Classified here rather than inside [AgentAppTools] for the same reason as
 * [MOVES_THE_PAGE]: [AgentToolForegroundPolicyTest] holds the three sets to
 * the whole catalogue, so a tool added later must be placed deliberately.
 */
internal val NEEDS_SCREEN_TAB: Set<String> = setOf(
    AgentTools.APP_PAGE,
    AgentTools.APP_SHIELDS,
    AgentTools.APP_SITE_PERMISSION
)
