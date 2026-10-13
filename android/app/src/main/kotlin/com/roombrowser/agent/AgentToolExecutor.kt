package com.roombrowser.agent

import android.os.SystemClock
import com.roombrowser.browser.BrowserControlApi
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.PageEvent
import com.roombrowser.domain.agent.ActionVerdict
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AgentPause
import com.roombrowser.domain.agent.AgentPlan
import com.roombrowser.domain.agent.AgentStepVerifier
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ChallengeDetector
import com.roombrowser.domain.agent.ChallengeHit
import com.roombrowser.domain.agent.DataTransform
import com.roombrowser.domain.agent.KeyChord
import com.roombrowser.domain.agent.PageDigestDto
import com.roombrowser.domain.agent.PageReadiness
import com.roombrowser.domain.agent.PageReadinessDto
import com.roombrowser.domain.agent.PageSnapshotDto
import com.roombrowser.domain.agent.PageSnapshotFormatter
import com.roombrowser.domain.agent.ReadinessSample
import com.roombrowser.domain.agent.ReadinessVerdict
import com.roombrowser.domain.agent.SecretRedaction
import com.roombrowser.domain.agent.SiteMemory
import com.roombrowser.domain.agent.SiteMemoryStore
import com.roombrowser.domain.agent.SiteMemoryTool
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.agent.UntrustedContent
import com.roombrowser.domain.agent.formatDurationMs
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.oct.OctUri
import com.roombrowser.engine.EngineSession
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
     * The browser operations this turn may name, as one surface. Every
     * navigation, tab move and page control goes through it rather than
     * reaching [BrowserViewModel] directly, so the agent's reach is reviewable
     * in one place.
     */
    private val control: BrowserControlApi,
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
    private val otpDigitsAllowed: suspend () -> Boolean = { false },
    /**
     * Whether `run_js` still has to be approved by the user.
     *
     * Read live rather than from the turn's snapshot, because the setting is
     * per profile and the user can change it while a turn runs. ON preserves
     * the ordinary confirmation chain; OFF is the profile's explicit decision
     * to let the agent run scripts without asking. The user's own typing in the
     * Console panel is a different surface and is never gated by this.
     */
    private val runJsApprovalRequired: suspend () -> Boolean = { true },
    /**
     * The detached page `compute_js` runs in. Null — a scheduled run's executor —
     * leaves the tool unavailable rather than running it in the wrong place;
     * the turn that owns this executor owns the session's lifecycle too.
     */
    private val scratchpad: AgentScratchpad? = null,
    /**
     * The agent's per-site memory, scoped to this turn's profile. Null leaves
     * the tool unavailable rather than writing under the wrong profile.
     */
    private val siteMemory: SiteMemoryStore? = null,
    /**
     * The per-profile "Let the agent remember this site" setting, read live.
     * OFF by default: the agent keeps no notes about a site until the user says
     * it may, so this is the gate rather than a withheld permission.
     */
    private val siteMemoryEnabled: suspend () -> Boolean = { false },
    /**
     * Told every time the pause state changes, so the panel and the ongoing
     * notification can show it. See [AgentPause].
     */
    private val onPause: suspend (AgentPause) -> Unit = {},
    /**
     * Suspends until the user says the page's check is done. The executor
     * itself NEVER touches the check — see [handOver].
     */
    private val awaitChallengeResume: suspend () -> Unit = {},
    /**
     * Why the active provider cannot be sent an image, or null when it can.
     * Checked before capture so a text-only provider is refused rather than
     * silently handed a request with the picture dropped.
     */
    private val visionUnsupportedReason: () -> String? = { null }
) : ToolExecutor {

    /** Why the last failed readiness wait gave up; null when it did not fail. */
    private var lastReadinessMessage: String? = null

    private val appTools by lazy {
        AgentAppTools(
            control = control,
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

    /** Built on first use, so a turn that never diagnoses pays for none of it. */
    private val devtoolsTools by lazy {
        AgentDevToolsTools(
            control = control,
            boundTabId = { tabId },
            onStatus = onStatus,
            diagnosticsEnabled = { vm.globalSettings.diagnosticsEnabled },
            destructiveGate = destructiveGate
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
                appTools.execute(name, argsJson)?.let { return@withContext answer(name, it) }
                devtoolsTools.execute(name, argsJson)?.let { return@withContext answer(name, it) }
                // Measured BEFORE the action so the page can be compared after
                // it — see [appendEvidence] and [AgentStepVerifier].
                val before = if (name in MUTATING_PAGE_TOOLS) measurePage() else null
                val raw = when (name) {
                    AgentTools.NAVIGATE -> navigate(str(args, "url"))
                    AgentTools.SEARCH_WEB -> searchWeb(str(args, "query"))
                    AgentTools.READ_PAGE -> readPage()
                    AgentTools.SEE_PAGE -> seePage(args)
                    AgentTools.CLICK -> click(int(args, "ref"))
                    AgentTools.FILL_INPUT -> fillInput(int(args, "ref"), str(args, "text"))
                    AgentTools.PRESS_ENTER -> pressEnter(intOrNull(args, "ref"))
                    AgentTools.SCROLL -> scroll(str(args, "direction") ?: "down", intOrNull(args, "amount"))
                    AgentTools.GO_BACK -> goBack()
                    AgentTools.RELOAD -> reloadTool()
                    AgentTools.FORWARD -> forwardTool()
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
                    AgentTools.COMPUTE_JS -> computeJs(str(args, "script"))
                    AgentTools.DATA_TRANSFORM -> dataTransform(args)
                    AgentTools.SITE_MEMORY -> siteMemoryTool(args)
                    AgentTools.SELECT_OPTION -> selectOption(int(args, "ref"), str(args, "value"))
                    AgentTools.PRESS_KEYS -> pressKeys(str(args, "keys"), intOrNull(args, "ref"))
                    AgentTools.WAIT_FOR -> waitFor(str(args, "text"), intOrNull(args, "timeout_ms"))
                    // The agent's own step list: nothing on the page, nothing
                    // stored, and no gate — it changes only what the user reads.
                    AgentTools.PLAN -> AgentPlan.execute(argsJson)
                    else -> ToolResult(false, "unknown tool: $name")
                }
                val withEvidence = before?.let { appendEvidence(raw, it) } ?: raw
                answer(name, withEvidence)
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
        control.navigate(url)
        val settled = awaitPageSettle(triggerAt)
        // Where the tab LANDED, read from this tab's own engine: pageState
        // belongs to the tab on screen, and the user may have switched away
        // while the page was loading — the report would then name the page
        // they switched TO.
        val landed = tabId?.let { control.tabSession(it) }
        // A finished load is not a settled page: `readyState === 'complete'`
        // flips while the page's own fetch/XHR calls are still outstanding, and
        // the next read would show half of it (see [awaitReadiness]).
        val busy = landed?.let { awaitReadiness(it) == ReadinessVerdict.TimedOut } ?: false
        val landedUrl = landed?.url?.takeIf { it.isNotBlank() } ?: url
        return if (settled) {
            ToolResult(
                true,
                "Navigated to $landedUrl — \"${landed?.title.orEmpty()}\". Call read_page to inspect the content." +
                    if (busy) " " + lastReadinessMessage.orEmpty() else ""
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

    /**
     * A picture of this chat's tab, for the model to look at. Refused in a
     * private tab for the same reason Developer Tools is: nothing a private
     * session renders leaves the device.
     */
    private suspend fun seePage(args: Map<String, Any?>): ToolResult {
        if (!vm.profileSettings().agentVisionEnabled) {
            return ToolResult(
                false,
                "see_page is turned off for this profile — turn on \"Agent can see the page\" in AI Agent settings."
            )
        }
        visionUnsupportedReason()?.let { why ->
            return ToolResult(
                false,
                "see_page is not available on this provider — $why. Switch to a provider that accepts images."
            )
        }
        val id = tabId
        if (id != null && vm.tabs.firstOrNull { it.id == id }?.isPrivate == true) {
            return ToolResult(
                false,
                "see_page is not available in a private tab: a screenshot of a private session is never sent to the model provider."
            )
        }
        val session = boundSession() ?: return ToolResult(false, "no page is loaded in this chat's tab")
        val shot = PageScreenshot.capture(session).getOrElse {
            return ToolResult(false, "could not capture the page: ${it.message ?: "unknown error"}")
        }
        return ToolResult(
            true,
            screenshotSummary(shot, str(args, "question"), str(args, "reason")),
            images = listOf(shot.image)
        )
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

    /**
     * Reloads this chat's tab in place.
     *
     * A reload STARTS A NAVIGATION (see [MOVES_THE_PAGE]), so [execute] has
     * already established that this chat's tab is the one on screen. The
     * console and network buffers are untouched — Developer Tools owns that
     * decision, and `devtools_reload` is the tool that says so explicitly.
     */
    private suspend fun reloadTool(): ToolResult {
        val triggerAt = SystemClock.elapsedRealtime()
        control.reload(tabId)
        awaitPageSettle(triggerAt)
        delay(SETTLE_MS)
        val session = tabId?.let { control.tabSession(it) }
        return ToolResult(true, "reloaded this chat's tab — \"${session?.title.orEmpty()}\"")
    }

    /** Forward one step, the mirror of [goBack] and classified the same way. */
    private suspend fun forwardTool(): ToolResult {
        val session = boundSession() ?: return ToolResult(false, "no page is loaded in this chat's tab")
        if (!session.canGoForward) return ToolResult(true, "already at the latest page in this tab")
        val triggerAt = SystemClock.elapsedRealtime()
        control.goForward(tabId)
        awaitPageSettle(triggerAt)
        delay(SETTLE_MS)
        return ToolResult(true, "went forward to ${session.url.orEmpty()}")
    }

    private suspend fun openNewTab(url: String?): ToolResult {
        val target = url?.takeIf { it.isNotBlank() }?.let { normalizeUrl(it) } ?: "about:home"
        val newId = control.openTab(target)
        // A tab opened TO BE WORKED IN is the tab this chat now means. Without
        // this the chat would sit bound to the tab it just left and gate its
        // own next navigation — the tool would open a tab nothing ever acts on.
        newId?.let { bindTo(it) }
        delay(SETTLE_MS)
        val landed = newId?.let { control.tabUrl(it) }?.takeIf { it.isNotBlank() }
        return ToolResult(true, "opened a new tab at ${landed ?: target}")
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
        control.switchTab(tab.id)
        awaitPageSettle(SystemClock.elapsedRealtime() - 1500)
        return ToolResult(true, "switched to tab [$index]: ${tab.title.ifBlank { tab.url }}")
    }

    private suspend fun closeTab(): ToolResult {
        // THIS chat's tab, not the tab on screen: closing the active one would
        // shut a page the user is reading to tidy up a tab they are not.
        val id = tabId ?: control.activeTabId() ?: return ToolResult(false, "no open tab")
        if (id !in control.tabIds()) return ToolResult(false, "this chat's tab is already closed")
        control.closeTab(id)
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
        // The per-profile "Agent console execution requires approval" setting.
        // ON (the default) sends the call through the ordinary confirmation
        // chain, so the person sees the script before it runs in their page;
        // OFF is the profile's own decision to let scripts run unattended. The
        // Console panel's own input is a different surface and never reaches
        // here.
        if (runJsApprovalRequired()) {
            val preview = code.lineSequence().firstOrNull().orEmpty().take(60)
            refuse(AgentTools.RUN_JS, "run this script in the page: $preview")?.let { return it }
        }
        val session = boundSession() ?: return ToolResult(false, "no page is loaded in this chat's tab")
        val raw = evaluateJs(session, PageInjector.runJs(jsonString(code)))
            ?: return ToolResult(false, "run_js failed (JavaScript error or page still loading)")
        return ToolResult(true, clip(unquote(raw)))
    }

    /**
     * Runs a script in the detached scratchpad, never the user's page. There is
     * no approval gate here as there is for [runJs]: the scratchpad has no
     * network, no filesystem and no origin, and the user's page cannot observe
     * it, so there is nothing of theirs for a script to change.
     */
    private suspend fun computeJs(script: String?): ToolResult {
        val code = nonBlank(script) ?: return ToolResult(false, "missing 'script' argument")
        val pad = scratchpad
            ?: return ToolResult(false, "compute_js is not available in this run")
        val session = pad.session()
            ?: return ToolResult(false, "compute_js could not start its scratchpad")
        val raw = evaluateJs(session, PageInjector.runJs(jsonString(code)))
            ?: return ToolResult(false, "compute_js failed (JavaScript error in the scratchpad)")
        return ToolResult(
            true,
            "Scratchpad result (a detached page — the user's page was not touched):\n" + clip(unquote(raw))
        )
    }

    /**
     * Runs one of the pure host-side transforms. No engine and no page are
     * involved: the input is the model's own data and the answer is computed in
     * this process, so there is nothing of the user's to touch and no gate.
     */
    private suspend fun dataTransform(args: Map<String, Any?>): ToolResult {
        val op = str(args, "op") ?: return ToolResult(false, "missing 'op' argument")
        val input = str(args, "input") ?: return ToolResult(false, "missing 'input' argument")
        val limit = intOrNull(args, "limit") ?: MAX_JS_RESULT_CHARS
        if (limit !in 1..MAX_JS_RESULT_CHARS) {
            return ToolResult(false, "limit must be between 1 and $MAX_JS_RESULT_CHARS")
        }
        val result = when (op) {
            "json_path" -> DataTransform.jsonPath(input, str(args, "path") ?: "", limit)
            "csv_rows" -> DataTransform.csvRows(input, limit)
            "aggregate" -> DataTransform.aggregate(input, intOrNull(args, "column"), str(args, "agg").orEmpty())
            "diff" -> {
                val compare = str(args, "compare")
                    ?: return ToolResult(false, "missing 'compare' argument for op=diff")
                DataTransform.diff(input, compare, limit)
            }
            else -> return ToolResult(
                false,
                "unsupported op '$op': use json_path, csv_rows, aggregate or diff"
            )
        }
        return when (result) {
            is DataTransform.Result.Success -> {
                val note = if (result.truncated) "\n[output capped at $limit characters]" else ""
                ToolResult(
                    true,
                    "Data transform result (computed on the device — the user's page was not touched):\n" +
                        result.text + note
                )
            }
            is DataTransform.Result.Failure -> ToolResult(false, result.reason)
        }
    }

    /**
     * The agent's per-site memory. The origin comes from the ENGINE's own
     * session URL, never from page text, so the page cannot choose the bucket
     * its notes are filed under.
     */
    private suspend fun siteMemoryTool(args: Map<String, Any?>): ToolResult {
        SiteMemory.refusalWhenDisabled(siteMemoryEnabled())?.let { return ToolResult(false, it) }
        val store = siteMemory
            ?: return ToolResult(false, "site_memory is not available in this run")
        val origin = SiteMemory.originOf(boundSession()?.url)
        return SiteMemoryTool.execute(
            store = store,
            origin = origin,
            op = str(args, "op"),
            key = str(args, "key"),
            value = str(args, "value"),
            limit = intOrNull(args, "limit")
        )
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

    // ------------------------------------------------------------- page safety

    /**
     * The last thing every page-derived result passes through: the fence, the
     * masking, and the check for a page that is asking for a person.
     *
     * Both transformations live HERE, at the one boundary every tool result
     * crosses, rather than inside each tool — a tool added later then cannot
     * forget them (see [PAGE_TEXT_TOOLS]).
     */
    private suspend fun answer(name: String, result: ToolResult): ToolResult {
        val framed =
            if (name in PAGE_TEXT_TOOLS) ToolResult(result.ok, framePageText(result.output))
            else result
        return handOverIfChallenged(framed)
    }

    /** Fences page text as DATA and masks anything credential-shaped in it. */
    private fun framePageText(text: String): String =
        UntrustedContent.wrap(SecretRedaction.redact(text), "the page")

    /**
     * Stops and hands over when the page is showing a challenge.
     *
     * The haystack is the result the model is about to read plus this tab's own
     * title and URL — all three are written by the site. The devtools tools'
     * output is part of that result, so a console line or a request row can be
     * what trips this; a challenge that leaves no trace in any of them is not
     * caught here.
     */
    private suspend fun handOverIfChallenged(result: ToolResult): ToolResult {
        val session = boundSession()
        val hit = ChallengeDetector.detect(
            pageText = result.output.take(MAX_CHALLENGE_SCAN_CHARS),
            url = session?.url,
            title = session?.title
        )
        if (!hit.detected) return result
        return handOver(hit)
    }

    /**
     * Hand the turn over to the user and wait.
     *
     * NOTHING here touches the check: no click, no tap at a coordinate, no
     * retry of whatever ran into the wall. An agent that "helps" with one of
     * these is the exact automation they exist to stop, and a coordinate tap
     * would be guessing at a page it cannot read.
     *
     * Resume is not taken on trust either. It re-runs the readiness gate and
     * takes a FRESH reading of the page, and the turn only continues when the
     * challenge is gone from that reading — a user who resumes too early is
     * told so and asked again instead of being quietly obeyed.
     */
    private suspend fun handOver(first: ChallengeHit): ToolResult {
        var hit = first
        var round = 0
        // Bounded, because a caller with nobody behind it — a scheduled run,
        // where the resume callback returns at once — would otherwise spin here
        // forever against a page that never stops asking.
        while (round < MAX_HANDOVER_ROUNDS) {
            round++
            onPause(AgentPause.ChallengeDetected(hit.kind))
            // Held briefly so the first state is actually seen before the ask
            // replaces it.
            delay(PAUSE_NOTICE_MS)
            onPause(AgentPause.WaitingForUser)
            val resumed = withTimeoutOrNull(CHALLENGE_WAIT_MS) { awaitChallengeResume() } != null
            if (!resumed) {
                onPause(AgentPause.None)
                return ToolResult(
                    false,
                    "no one answered the check within ${CHALLENGE_WAIT_MS / 60_000} minutes, so this " +
                        "turn stopped here. Tell the user the page is still waiting for them."
                )
            }
            if (!kotlin.coroutines.coroutineContext.isActive) {
                return ToolResult(false, "the turn was stopped while the page was waiting for the user")
            }
            val session = boundSession()
            if (session == null) {
                onPause(AgentPause.VerificationSuccessful)
                delay(PAUSE_NOTICE_MS)
                onPause(AgentPause.None)
                return ToolResult(true, CHALLENGE_CLEARED_NOTE + " The page is no longer open.")
            }
            awaitReadiness(session)
            val again = ChallengeDetector.detect(
                pageText = rawSnapshotText(session),
                url = session.url,
                title = session.title
            )
            if (!again.detected) {
                onPause(AgentPause.VerificationSuccessful)
                delay(PAUSE_NOTICE_MS)
                onPause(AgentPause.None)
                return ToolResult(true, CHALLENGE_CLEARED_NOTE)
            }
            onPause(AgentPause.VerificationFailed)
            delay(PAUSE_NOTICE_MS)
            hit = again
        }
        onPause(AgentPause.None)
        return ToolResult(
            false,
            "the page is still asking for a person after $MAX_HANDOVER_ROUNDS attempts, so this turn " +
                "stops here. Tell the user the page needs them."
        )
    }

    /** The page's own visible text, unframed — used only to look for a challenge. */
    private suspend fun rawSnapshotText(session: EngineSession): String? {
        val raw = evaluateJs(session, PageInjector.snapshotJs()) ?: return null
        if (raw.isBlank() || raw == "null" || raw == "undefined") return null
        return runCatching { AgentJson.decodeFromString(PageSnapshotDto.serializer(), raw) }
            .getOrNull()?.text
    }

    /** The page's canonical digest, or null when it cannot be measured at all. */
    private suspend fun measurePage(): String? {
        val session = boundSession() ?: return null
        val raw = evaluateJs(session, PageInjector.digestJs()) ?: return null
        val dto = runCatching { AgentJson.decodeFromString(PageDigestDto.serializer(), raw) }
            .getOrNull() ?: return null
        return AgentStepVerifier.digest(dto)
    }

    /**
     * Appends what the action actually did, measured rather than assumed —
     * "the page did NOT change" one step after a click is the fact that stops
     * the model repeating it (see [AgentStepVerifier]).
     */
    private suspend fun appendEvidence(result: ToolResult, before: String): ToolResult =
        result.copy(output = result.output + "\n\n" + AgentStepVerifier.evidenceFor(before, measurePage()))

    /**
     * The readiness gate: `readyState === 'complete'` AND nothing in flight for
     * a settle window.
     *
     * A page that never goes quiet is reported rather than waited on forever —
     * the caller gets [ReadinessVerdict.TimedOut] with the probe's own last
     * sample, and says so instead of pretending the page was ready.
     */
    private suspend fun awaitReadiness(session: EngineSession): ReadinessVerdict {
        val gate = PageReadiness(settleMs = READINESS_SETTLE_MS, ceilingMs = READINESS_CEILING_MS)
        gate.start(SystemClock.elapsedRealtime())
        var last: ReadinessSample? = null
        val verdict = withTimeoutOrNull(READINESS_CEILING_MS + 1_000) {
            var result: ReadinessVerdict = ReadinessVerdict.Busy
            while (result == ReadinessVerdict.Busy) {
                val raw = evaluateJs(session, PageInjector.readinessJs())
                val dto = raw?.let {
                    runCatching { AgentJson.decodeFromString(PageReadinessDto.serializer(), it) }.getOrNull()
                }
                if (dto != null) {
                    val sample = ReadinessSample(dto.readyState, dto.inFlight, SystemClock.elapsedRealtime())
                    last = sample
                    result = gate.observe(sample)
                }
                if (result == ReadinessVerdict.Busy) delay(POLL_MS)
            }
            result
        } ?: ReadinessVerdict.TimedOut
        lastReadinessMessage = if (verdict == ReadinessVerdict.TimedOut) gate.busyMessage(last) else null
        return verdict
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
            // A circle id is not a domain, so the bare-host rule below would
            // rewrite "oct://<id>" into "https://oct://<id>".
            input.startsWith(OctUri.PREFIX) -> input
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

        /**
         * How long a page must show `readyState === 'complete'` with nothing in
         * flight before it counts as settled. Shorter than the old fixed delay
         * on a quiet page because it measures the page rather than guessing.
         */
        private const val READINESS_SETTLE_MS = 500L

        /**
         * The ceiling on a readiness wait, after which the page is reported as
         * still busy. A site that keeps a request open (long-polling, analytics
         * beacon) must not hold a turn open with it.
         */
        private const val READINESS_CEILING_MS = 3_000L

        /** How long each hand-over state stays on screen before the next one. */
        private const val PAUSE_NOTICE_MS = 700L

        /**
         * How long a hand-over waits for the user. Long enough to actually
         * finish a check by hand, short enough that an unattended turn ends
         * instead of sitting on a live foreground service forever.
         */
        private const val CHALLENGE_WAIT_MS = 10 * 60_000L

        /** How many times a hand-over is offered before the turn gives up. */
        private const val MAX_HANDOVER_ROUNDS = 3

        /** A challenge this deep into a result is not what the page is about. */
        private const val MAX_CHALLENGE_SCAN_CHARS = 20_000

        private const val CHALLENGE_CLEARED_NOTE =
            "the check is gone and the page reads normally again. Call read_page before acting, " +
                "then carry on with what you were doing."

        /**
         * The tools that are supposed to CHANGE the page, and so are measured
         * before and after (see [appendEvidence]). read_page and navigate are
         * not here: one is a read, and the other reports where it landed.
         */
        private val MUTATING_PAGE_TOOLS = setOf(
            AgentTools.CLICK,
            AgentTools.FILL_INPUT,
            AgentTools.PRESS_ENTER,
            AgentTools.SELECT_OPTION,
            AgentTools.PRESS_KEYS,
            AgentTools.AUTO_LIKE,
            AgentTools.AUTO_REPOST,
            AgentTools.AUTO_REPLY,
            AgentTools.AUTO_POST
        )
    }
}

/**
 * The tools whose answer QUOTES the page back at the model, and so has to
 * arrive inside the untrusted fence (see [UntrustedContent]).
 *
 * Classified by what the script's own return value contains rather than by
 * feel: `click` reports the element's `innerText` label, `select_option`
 * reports the option names it found, `run_js` returns whatever the model's
 * script read, and `app_page` reports the page's title. The rest answer with
 * sentences this app wrote ("typed the reply and clicked submit"), which are
 * nobody's page talking. read_page is absent because [PageSnapshotFormatter]
 * already fences it, and so it must NOT appear here or it would be fenced twice.
 *
 * Top-level rather than private to one executor because BOTH page executors
 * need it — a headless turn reads the same pages — and a list kept in two
 * places is a list that drifts.
 */
internal val PAGE_TEXT_TOOLS: Set<String> = setOf(
    AgentTools.CLICK,
    AgentTools.SELECT_OPTION,
    AgentTools.RUN_JS,
    AgentTools.APP_PAGE,
    // The audit and diagnose tools answer with counts and findings, but a
    // finding's evidence quotes the console — the page's own words — so the
    // result is fenced here rather than trusted. The console, network and
    // inspect tools are absent because each fences its own body already.
    AgentTools.DEVTOOLS_AUDIT,
    AgentTools.DEVTOOLS_DIAGNOSE
)

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
    AgentTools.RELOAD,
    AgentTools.FORWARD,
    AgentTools.SELECT_OPTION,
    AgentTools.PRESS_KEYS,
    AgentTools.AUTO_LIKE,
    AgentTools.AUTO_REPOST,
    AgentTools.AUTO_REPLY,
    AgentTools.AUTO_POST,
    // A diagnostic reload IS a page load, and a page patch is a script that can
    // start one — both belong on this list for the same reason as navigate.
    AgentTools.DEVTOOLS_RELOAD,
    AgentTools.DEVTOOLS_PATCH
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
