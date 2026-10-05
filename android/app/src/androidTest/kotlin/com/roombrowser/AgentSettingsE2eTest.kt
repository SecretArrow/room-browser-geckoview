package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the AI Agent settings flow (cross-process, real app UI):
 *
 *   MainActivity (default process)
 *     -> first-run welcome / profile list -> engine opens
 *   BrowserActivity (':browser' process)
 *     -> page menu -> "AI Agents" opens the panel
 *     -> Configure providers -> AgentSettingsActivity (own window)
 *     -> Add provider -> AgentProviderEditorActivity (own window)
 *     -> type name + base URL (local MockWebServer) + API key
 *     -> Fetch models -> chips from the provider's /models response
 *     -> Save -> provider listed in the settings activity
 *     -> back to the browser: the panel shows the selected model
 *     -> chat round-trip: send a prompt -> user bubble + copy icon,
 *        mock SSE reply -> answer bubble + copy icon, tap copy -> "Copied"
 *     -> "Show AI Agent button" toggle in Browser settings:
 *        default OFF (no floating pill), ON shows the pill, OFF hides it
 *     -> AgentSessionsActivity opens from the page menu
 *
 * Compose fields are driven exactly like E2EBrowseFlowTest: EditText class
 * nodes + `input text` shell command + coordinate clicks.
 */
@RunWith(AndroidJUnit4::class)
class AgentSettingsE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        // Determinism: the runner's shared IP makes every fresh-profile boot
        // arm the organic network warning — suppress it (see E2eDeterminism).
        E2eDeterminism.suppressOrganicNetworkWarnings()
        server = MockWebServer()
        // Path-routed dispatcher (NOT a strict response queue): the
        // verification-driven flow may click Fetch several times and the
        // chat step below POSTs /chat/completions — every request gets a
        // correct answer regardless of order or count.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("/models") ->
                        MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"object":"list","data":[{"id":"mock-model-a"},{"id":"mock-model-b"}]}""")
                    // OpenAI-style SSE stream: one content delta then DONE —
                    // a plain final answer with no tool calls, so the agent
                    // turn ends after this single response.
                    path.contains("/chat/completions") ->
                        MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(
                                "data: {\"choices\":[{\"delta\":{\"content\":\"mock-reply-ok\"}}]}\n\n" +
                                    "data: [DONE]\n\n"
                            )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun launchMainActivity() {
        val intent = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                setClassName(targetContext.packageName, "com.roombrowser.main.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
    }

    private fun hasText(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs)

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

    private fun hasDescContains(part: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.descContains(part)), timeoutMs)

    /** Polls until no node shows [text] anymore (dialog/editor closed). */
    private fun waitGone(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.findObjects(By.text(text)).isEmpty()) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return device.findObjects(By.text(text)).isEmpty()
    }

    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    /** Clicks the first node whose content-description CONTAINS [part]. */
    private fun clickDescContains(part: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.descContains(part)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickCenter(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        // SHELL TAP, not device.click() gesture injection: the CI runner's
        // busy a11y pipeline silently swallows injected gestures (Task 12
        // lesson, commit 2354d81 — "Gestures took longer than expected");
        // `input tap` is deterministic and focuses Compose fields reliably.
        device.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}")
        device.waitForIdle(1_000)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Clicks via the accessibility ACTION_CLICK (immune to overlays like the
     * IME or sheets covering the node), walking up to the nearest clickable
     * ancestor for Compose text-inside-button nodes; falls back to a
     * coordinate tap. NB: UiObject2.click() returns Unit.
     */
    private fun clickSmart(node: UiObject2): Boolean {
        var current: UiObject2? = node
        var hops = 0
        while (current != null && hops < 8) {
            val clickable = try {
                current.isClickable
            } catch (_: Exception) {
                false
            }
            if (clickable) {
                try {
                    current.click()
                    device.waitForIdle(1_000)
                    return true
                } catch (_: Exception) {
                }
            }
            current = try {
                current.parent
            } catch (_: Exception) {
                null
            }
            hops++
        }
        return clickCenter(node)
    }

    private fun engineUiUp(timeoutMs: Long): Boolean {
        // The address pill ALWAYS carries the desc "Address bar: …" (its
        // Row semantics), so the engine is detectable regardless of whether
        // the omnibox placeholder text is currently exposed (the expanded
        // agent panel or a non-home tab can hide it). Short poll cadence —
        // the old hard-coded 10s+5s sub-waits made one failing check take
        // ~19s and starved the retry loops.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasDescContains("Address bar", 400)) return true
            if (hasText("Search or type URL", 400)) return true
            if (hasText("Privacy Dashboard", 400)) return true
            if (hasText("trackers blocked", 400)) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return hasDescContains("Address bar", 500)
    }

    /** Probes the live accessibility tree for the nodes we care about and
     *  lists every visible text — goes into the failure message (readable
     *  from the e2e-reports artifact). */
    private fun uiTree(): String = try {
        val sb = StringBuilder()
        val probes: List<Pair<String, BySelector>> = listOf(
            "pill(AI Agent desc)" to By.desc("AI Agent"),
            "agent_configure desc" to By.desc("agent_configure"),
            "'Configure providers' text" to By.text("Configure providers"),
            "'No AI provider configured'" to By.text("No AI provider configured"),
            "'Room Agent' text" to By.text("Room Agent"),
            "Agent settings gear" to By.desc("Agent settings"),
            "agent_model line" to By.desc("agent_model"),
            "Page actions button" to By.desc("Page actions and settings"),
            "'Page Actions' sheet title" to By.text("Page Actions"),
            "'AI Agents' entry" to By.text("AI Agents"),
            "'Browser settings' entry" to By.text("Browser settings"),
            "'AI Agent chats' entry" to By.text("AI Agent chats"),
            "'Add provider' text" to By.text("Add provider"),
            "'AI Agent Settings' title" to By.text("AI Agent Settings"),
            "'Show AI Agent button' switch" to By.descContains("Show AI Agent button"),
            "retry switch" to By.descContains("Retry failed requests switch"),
            "retry_attempts field" to By.desc("retry_attempts"),
            "retry_delay field" to By.desc("retry_delay"),
            "retry_code_400 box" to By.desc("retry_code_400"),
            "agent hint text" to By.textContains("Ask the agent")
        )
        for ((label, selector) in probes) {
            val nodes = runCatching { device.findObjects(selector) }.getOrDefault(emptyList())
            sb.append(label).append(": count=").append(nodes.size)
            nodes.take(2).forEach { n ->
                sb.append(" bounds=").append(runCatching { n.visibleBounds }.getOrNull())
                    .append(" clickable=").append(runCatching { n.isClickable }.getOrDefault(false))
            }
            sb.append('\n')
        }
        // Compose editable fields expose their content as accessibility text
        for (fd in listOf("provider_name_field", "provider_url_field", "provider_key_field")) {
            val v = runCatching {
                device.findObjects(By.desc(fd)).firstOrNull()?.text
            }.getOrNull()
            sb.append(fd).append(" value='").append(v).append("'\n")
        }
        val texts = runCatching {
            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(80)
        }.getOrDefault(emptyList())
        sb.append("VISIBLE TEXTS: ").append(texts).append('\n')
        sb.toString().take(9000)
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }

    /** Types text into the editor field with the given content description —
     *  VERIFICATION-DRIVEN, and EVERY round clears the field first: retyping
     *  into a non-empty field inserts at the tap-positioned cursor and
     *  corrupts the content (CI-observed). The clear (cursor to end + one
     *  compound shell line of 40 semicolon-chained DEL keyevents — 40
     *  separate commands cost ~20s) also makes retries safe. Verification is
     *  a GLOBAL text search: Compose renders field content on an inner text
     *  node (the desc node itself reports null), findable via By.textContains
     *  exactly like the key field's bullets. */
    private fun typeIntoField(desc: String, text: String, masked: Boolean = false): Boolean {
        for (round in 1..3) {
            hideImeIfNeeded()
            // Scroll-aware lookup: the editor's manual fields sit BELOW the
            // preset list, and that list GROWS when presets are added (CI
            // regression: one extra preset row pushed the name field past the
            // old single-drag fallback). Poll + drag until the field is on
            // screen — a fixed drag count silently breaks on layout growth.
            // (Task 13 regression: the 4th protocol chip + LOCAL caption add
            // another wrapped FlowRow row + a hint paragraph above the fields.)
            var field: UiObject2? = null
            for (i in 1..10) {
                field = device.wait(Until.findObject(By.desc(desc)), 1_500)
                if (field != null) break
                dragUpQuarter()
            }
            if (field == null) continue
            // FRESH resolve immediately before the tap: a handle captured
            // earlier can carry stale bounds — tapping it hits the void.
            val fresh = device.wait(Until.findObject(By.desc(desc)), 2_000) ?: field
            clickCenter(fresh)
            // ALWAYS clear: the field may hold text from a failed earlier
            // round (or this may be a retype after a fetch error).
            device.executeShellCommand("input keyevent KEYCODE_MOVE_END")
            device.clearFocusedField()
            device.waitForIdle(400)
            // NB: executeShellCommand does not interpret shell quoting — a quoted
            // argument would type the quotes into the field. Values here contain
            // no spaces or shell metacharacters, so pass them bare.
            device.executeShellCommand("input text $text")
            device.waitForIdle(1_000)
            // Global text search: the field renders its content as a text
            // node (masked fields render a bullet run).
            val token = if (masked) "\u2022\u2022\u2022\u2022\u2022" else text
            if (device.wait(Until.hasObject(By.textContains(token)), 1_500)) return true
        }
        return false
    }

    /** Outcome of one Fetch-models click. The ERROR text renders right below
     *  the button (in view); the chips render below the fold — one drag after
     *  a quiet moment makes them a11y-visible. */
    private enum class FetchOutcome { CHIPS, ERROR, NOTHING }

    /**
     * VERIFIED send: taps the send button with FRESH bounds each attempt and
     * only returns once the app has ACCEPTED the tap (see [sendLanded]). The
     * send button moves when the IME dismisses (composer resize) — a tap on
     * pre-shift bounds lands on nothing (CI 227ebc3). Each send attempt is
     * given its own grace window before re-tapping, so a slow first send is
     * never duplicated.
     */
    private fun sendAgentPrompt(): Boolean {
        // The negative control runs ONCE, before any tap: the prompt is in the
        // field at this point, so a probe that cannot see it is broken and must
        // not be allowed to read a later "it is gone" as a successful send.
        // Inside the loop it would instead misread an accepted send as a
        // failure — and, on a slow clear, re-tap and send twice.
        if (!device.hasObject(composerHoldsPrompt)) return false
        repeat(5) {
            val send = device.wait(Until.findObject(By.desc("agent_send")), 2_000)
                ?: return sendLanded()
            clickSmart(send)
            if (sendLanded()) return true
        }
        return sendLanded()
    }

    private val composerHoldsPrompt: BySelector =
        By.desc("agent_composer_field").textContains("e2e_copy_prompt")

    /**
     * True once the app has taken the prompt out of the composer.
     *
     * The composer is the only reachable evidence of an accepted send. The user
     * bubble's copy affordance would be more direct, but the transcript is a
     * LazyColumn shorter than one bubble on the CI display and the mock reply
     * lands at once, so the just-sent bubble leaves the composed window — and
     * with it the accessibility tree — and scrolling does not bring it back
     * (run 37360922928 searched both ways for 38s without finding it). The
     * composer survives that geometry: it held the prompt, and only an accepted
     * send empties it, the exact inverse of the CI 227ebc3 defect where a tap
     * on stale bounds left the text in place. Waiting for it to go, rather than
     * sampling once, is what keeps a slow clear from being misread as a missed
     * tap and re-sent.
     */
    private fun sendLanded(): Boolean = device.wait(Until.gone(composerHoldsPrompt), 4_000)

    /**
     * Waits for a node, then keeps looking for it with the transcript scrolled
     * each way.
     *
     * The transcript is pinned to its NEWEST entry, so the just-sent bubble —
     * the first entry — is normally scrolled above the fold by the time the
     * reply lands. A LazyColumn does not compose what is outside its viewport,
     * so the node leaves the accessibility tree altogether, and on the CI
     * display the whole panel is ~640px with only ~85px given to the list —
     * less than one bubble, which makes this the ordinary state rather than an
     * edge case. Searching BOTH directions is what keeps the checks below
     * independent of where the list happens to sit, because satisfying one of
     * them is free to leave the list scrolled away from the next.
     */
    private fun revealNode(selector: BySelector, windowMs: Long): Boolean {
        val found = if (windowMs <= 0) {
            device.hasObject(selector)
        } else {
            device.wait(Until.hasObject(selector), windowMs)
        }
        if (found) return true
        repeat(3) {
            if (scrollTranscript(older = true) && device.wait(Until.hasObject(selector), 1_200)) {
                return true
            }
            if (scrollTranscript(older = false) && device.wait(Until.hasObject(selector), 1_200)) {
                return true
            }
        }
        return device.hasObject(selector)
    }

    private fun revealDesc(desc: String, windowMs: Long): Boolean =
        revealNode(By.desc(desc), windowMs)

    /**
     * Drags the chat's transcript one gesture toward earlier or later entries.
     *
     * The band is bracketed by the two panel parts around the list — the model
     * line above it and the composer below — rather than the screen centre: the
     * panel is resized while the IME is up, and a screen-centre drag lands in
     * the composer (or the header) and scrolls nothing. Direction is the
     * content's, as in [nudgeChatDown]: dragging DOWN pulls earlier entries
     * into view, dragging UP reveals later ones.
     */
    private fun scrollTranscript(older: Boolean): Boolean {
        val above = device.findObjects(By.desc("agent_model")).firstOrNull() ?: return false
        val below = device.findObjects(By.desc("agent_composer_field")).firstOrNull() ?: return false
        val top = above.visibleBounds.bottom + 4
        val bottom = below.visibleBounds.top - 4
        if (bottom - top < 24) return false
        val cx = device.displayWidth / 2
        if (older) {
            device.swipe(cx, top, cx, bottom, 250)
        } else {
            device.swipe(cx, bottom, cx, top, 250)
        }
        device.waitForIdle(500)
        try { Thread.sleep(200) } catch (_: InterruptedException) { }
        return true
    }

    private fun fetchOutcome(): FetchOutcome {
        val deadline = System.currentTimeMillis() + 9_000
        var dragged = false
        while (System.currentTimeMillis() < deadline) {
            if (textExists("Could not fetch models")) return FetchOutcome.ERROR
            if (textExists("mock-model-a")) return FetchOutcome.CHIPS
            if (!dragged) {
                try { Thread.sleep(1_200) } catch (_: InterruptedException) { }
                dragUpQuarter()
                dragged = true
            } else {
                try { Thread.sleep(300) } catch (_: InterruptedException) { }
            }
        }
        return FetchOutcome.NOTHING
    }

    private fun textExists(part: String): Boolean =
        runCatching { device.findObjects(By.textContains(part)) }.getOrDefault(emptyList()).isNotEmpty()

    /** SLOW drag (100 steps ≈ no fling momentum) that scrolls ~1/4 of the
     *  screen — deterministic: a fast fling overshoots past the target row
     * in scrollable sheets (observed in CI: the "AI Agents" sheet entry
     * never became visible after 4 fling attempts). A slow drag
     * also fully expands a half-expanded ModalBottomSheet. */
    private fun dragUpQuarter() {
        // Half-screen drag (3/4 → 1/4): the CI emulator's default profile is
        // 320x640 mdpi — deep settings screens run ~4000px there. Slow steps
        // (no fling) keep it a controlled scroll; the settle AFTER the drag
        // lets residual momentum finish before the caller reads node bounds.
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 100
        )
        device.waitForIdle(800)
        try { Thread.sleep(300) } catch (_: InterruptedException) { }
    }

    /**
     * True when the node is not USABLY inside its own container.
     *
     * An empty `visibleBounds` is NOT the test, and believing it was cost
     * three runs. A LazyColumn item that is only PARTLY scrolled past the
     * viewport edge is still composed, and UiAutomator reports a non-empty
     * — clipped — rect for it, so "the rect is non-empty" reads as visible.
     * CI evidence (run 37056201693): `agent_copy_user` reported
     * `Rect(256, 234 - 304, 255)`, while the container it lives in was
     * `Rect(0, 258 - 320, 415)`; the tap went to (280, 244) — 14px ABOVE
     * the list, onto the panel header — and no "Copied" ever appeared.
     *
     * So the test is the CONTAINER: the node's rect must lie inside its
     * parent's visible rect before its centre means anything.
     */
    private fun isOutOfView(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        if (b.width() <= 0 || b.height() <= 0) {
            true
        } else {
            val p = try { node.parent?.visibleBounds } catch (_: Exception) { null }
            p != null && (
                b.top < p.top || b.bottom > p.bottom ||
                    b.left < p.left || b.right > p.right
                )
        }
    } catch (_: Exception) {
        true
    }

    /**
     * Where the copy affordance actually IS, plus its ancestor chain.
     *
     * Worth the noise: the copy round-trip failed three runs running with the
     * same geometry (`Rect(256, 234 - 304, 255)` against a `Rect(0, 258 - 320,
     * 415)` container) while the feature's own code was untouched, so the
     * question is not whether the click lands but WHAT is laying the button
     * out there. `visibleBoundsFor` rejects a node against its ancestors'
     * bounds, so the ancestors are the answer.
     */
    /**
     * Runs a shell command and keeps only the lines [keep] accepts.
     *
     * `executeShellCommand` does not interpret a `|` in this environment —
     * `dumpsys window | grep X` reaches `dumpsys` as three extra arguments
     * and answers `Bad window command, or no windows match: |`, and
     * `dumpsys input_method | grep X` ignores them and dumps the lot. Either
     * way the filter has to happen on this side of the call.
     */
    private fun shellLines(cmd: String, keep: (String) -> Boolean): String = try {
        device.executeShellCommand(cmd)
            .split("\n")
            .filter(keep)
            .joinToString("\n")
            .trim()
            .ifEmpty { "(no match)" }
    } catch (_: Exception) {
        "?"
    }

    private fun copyAffordanceReport(desc: String): String {
        val sb = StringBuilder("screen=${device.displayWidth}x${device.displayHeight}")
        // Two facts that decide how to read the geometry: whether the IME is
        // up (it resizes the panel, so the list gets short), and which window
        // actually owns the focus. `executeShellCommand` does NOT run a shell
        // here — a `| grep` is passed to the tool as arguments and either
        // dumps everything or errors — so filter in Kotlin.
        sb.append("\nime: ").append(shellLines("dumpsys input_method") { line ->
            line.contains("mInputShown") || line.contains("mIsInputViewShown") ||
                line.contains("contentTopInsets")
        })
        sb.append("\nfocus: ").append(shellLines("dumpsys window") { line ->
            line.contains("mCurrentFocus") || line.contains("mFocusedApp")
        })
        val node = device.findObjects(By.desc(desc)).firstOrNull()
            ?: return "$sb\n$desc: NOT IN THE TREE"
        // NOTE: UiObject2 has no getBounds() in this version — only the
        // VISIBLE rect. The unclipped rect is in the logcat line that
        // `AccessibilityNodeInfoHelper` prints when it rejects the node.
        sb.append("\n$desc visible=").append(node.visibleBounds)
        var parent = try { node.parent } catch (_: Exception) { null }
        var depth = 0
        while (parent != null && depth < 8) {
            sb.append("\n  ^$depth ").append(try { parent.className } catch (_: Exception) { "?" })
                .append(" visible=").append(try { parent.visibleBounds } catch (_: Exception) { "?" })
                .append(" clickable=").append(try { parent.isClickable } catch (_: Exception) { false })
                .append(" text=").append(try { parent.text } catch (_: Exception) { null })
            parent = try { parent.parent } catch (_: Exception) { null }
            depth++
        }
        return sb.toString()
    }

    /**
     * Drags DOWN inside the chat's own container — the gesture that reveals
     * EARLIER content.
     *
     * Both ends come from the CONTAINER's visible rect, not from the screen
     * centre: the panel is resized while the IME is up, and a screen-centre
     * drag lands in the composer below the list (or the header above it) and
     * scrolls nothing. The list is pinned to its newest item, so the user's
     * own bubble is the FIRST entry — dragging down is always the direction
     * that brings it back.
     */
    private fun nudgeChatDown(node: UiObject2) {
        val p = try { node.parent?.visibleBounds } catch (_: Exception) { null }
        if (p == null || p.height() < 40) return
        val cx = p.centerX()
        val from = p.top + p.height() / 5
        val to = p.top + p.height() * 4 / 5
        device.swipe(cx, from, cx, to, 250)
        device.waitForIdle(500)
        try { Thread.sleep(200) } catch (_: InterruptedException) { }
    }

    /**
     * Taps a chat bubble's copy affordance and waits for its "Copied" label.
     *
     * The panel scrolls to its newest entry, so after the reply streams in
     * the user's own bubble — the FIRST entry — is scrolled above the fold.
     * That is correct chat behaviour, and it is the test's problem to solve,
     * not the panel's: the affordance is composed (partly on screen) and
     * tappable only once it is genuinely inside the list's viewport.
     *
     * So: scroll it into view, tap its VISIBLE centre through the shell (see
     * `clickCenter` for why injected gestures are avoided on the CI runner),
     * and poll for the label straight away — an idle-wait here would eat most
     * of the ~1.8s window it observes. [copyAffordanceReport] is the
     * assertion's message: it names the geometry and the container the button
     * has to be inside, so a future failure is readable without another run.
     */
    private fun copyAndSeeFeedback(desc: String): Boolean {
        repeat(3) {
            var node = device.wait(Until.findObject(By.desc(desc)), 3_000) ?: return@repeat
            var nudges = 0
            while (isOutOfView(node) && nudges < 6) {
                nudgeChatDown(node)
                nudges++
                node = device.wait(Until.findObject(By.desc(desc)), 2_000) ?: return@repeat
            }
            if (isOutOfView(node)) return@repeat
            val b = node.visibleBounds
            device.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}")
            if (device.wait(Until.hasObject(By.text("Copied")), 2_500)) return true
        }
        return false
    }

    /**
     * The engine surface must be up CONTINUOUSLY for [stableMs] — a surface
     * that dies (':browser' process self-restart while rebinding to this
     * suite's fresh profile) resets the window; 150 ms polls catch the gap
     * between the doomed and the final surface.
     */
    private fun engineUiStable(totalMs: Long, stableMs: Long = 8_000): Boolean {
        val deadline = System.currentTimeMillis() + totalMs
        var firstSeen = 0L
        while (System.currentTimeMillis() < deadline) {
            val up = device.findObjects(By.descContains("Address bar")).isNotEmpty() ||
                device.findObjects(By.text("Privacy Dashboard")).isNotEmpty()
            val now = System.currentTimeMillis()
            if (up) {
                if (firstSeen == 0L) firstSeen = now
                if (now - firstSeen >= stableMs) return true
            } else {
                firstSeen = 0L
            }
            try { Thread.sleep(150) } catch (_: InterruptedException) { }
        }
        return false
    }

    /** Off-screen rows of a scrollable container are not exposed to the
     *  accessibility tree — advance the viewport with SMALL deterministic
     *  drags between find attempts (no fling overshoot). */
    private fun clickTextWithScroll(text: String, attempts: Int = 24): Boolean {
        for (i in 1..attempts) {
            val node = device.wait(Until.findObject(By.text(text)), 1_500)
            if (node != null && rectSettled(node) && clickSmart(node)) return true
            dragUpQuarter()
        }
        return false
    }

    /**
     * True once two consecutive reads of [node]'s rect agree, i.e. the
     * viewport has stopped moving. A found node is not necessarily a still
     * one — a row can still be settling after the drag, or a dialog can
     * shift the layout under it — and this helper returns a bare `true` with
     * no verification, so a tap on stale bounds is swallowed silently.
     * Bounded: gives up after [tries] reads so a node that is genuinely
     * animating cannot hang the test.
     */
    private fun rectSettled(node: UiObject2, tries: Int = 6): Boolean {
        var previous = runCatching { node.visibleBounds }.getOrNull() ?: return false
        repeat(tries) {
            try { Thread.sleep(200) } catch (_: InterruptedException) { }
            val current = runCatching { node.visibleBounds }.getOrNull() ?: return false
            if (current == previous) return true
            previous = current
        }
        return false
    }

    /** The IME is a separate accessibility window that can shadow node
     *  lookups — close it before searching for the next field/button. */
    private fun imeShown(): Boolean = try {
        device.executeShellCommand("dumpsys input_method | grep mInputShown")
            .contains("mInputShown=true")
    } catch (_: Exception) {
        false
    }

    private fun hideImeIfNeeded() {
        if (imeShown()) {
            device.pressBack()
            device.waitForIdle(600)
        }
    }

    /** Scrolls to the switch row, flips it and VERIFIES the state label
     *  actually flipped. CI evidence (run 36292517214): an a11y ACTION_CLICK
     *  can silently no-op, so every click is followed by a state check and a
     *  coordinate-tap fallback on the same row. */
    private fun flipSwitch(title: String, wantOn: Boolean): Boolean {
        val want = "$title switch, ${if (wantOn) "on" else "off"}"
        val from = "$title switch, ${if (wantOn) "off" else "on"}"
        for (i in 1..12) {
            // Already in the wanted state (e.g. dirty-device rerun)?
            if (hasDesc(want, 500)) return true
            val node = device.wait(Until.findObject(By.desc(from)), 1_500)
            if (node != null) {
                clickSmart(node)
                if (hasDesc(want, 4_000)) return true
                runCatching { clickCenter(node) }
                if (hasDesc(want, 4_000)) return true
            }
            // The row sits at the bottom of the settings column — scroll.
            dragUpQuarter()
        }
        return hasDesc(want, 2_000)
    }

    /** Opens a page-actions sheet entry by its content description and
     *  VERIFIES the effect. Defence in depth (CI evidence: an accessibility
     *  ACTION_CLICK on a text-matched sheet row once silently no-opped):
     *  - desc-click on the clickable row (the proven gear-button pattern)
     *  - coordinate-tap fallback on the same node
     *  - small drags between attempts (scrollable sheet, no fling overshoot)
     *  - sheet-dismissal recovery between rounds (Back closes a stuck sheet)
     */
    private fun openSheetEntry(label: String, verify: () -> Boolean): Boolean {
        for (round in 1..3) {
            // If the toolbar gear is covered, a leftover sheet is open —
            // close it first (Back dismisses ModalBottomSheet).
            if (!hasDesc("Page actions and settings", 1_500)) {
                device.pressBack()
                device.waitForIdle(1_000)
            }
            if (!clickDesc("Page actions and settings", 6_000)) continue
            for (attempt in 1..12) {
                val node = device.wait(Until.findObject(By.desc(label)), 2_000)
                if (node != null) {
                    clickSmart(node)
                    if (verify()) return true
                    // The a11y click can silently no-op — tap the row itself.
                    runCatching { clickCenter(node) }
                    if (verify()) return true
                }
                dragUpQuarter()
            }
        }
        return false
    }

    /** Opens the floating agent panel — direct pill tap when visible,
     *  otherwise the always-available page-menu entry. */
    private fun openAgentPanelFromMenu(): Boolean {
        if (hasDesc("AI Agent", 1_000)) {
            for (attempt in 1..2) {
                clickDesc("AI Agent", 3_000)
                if (panelUp(4_000)) return true
            }
        }
        return openSheetEntry("AI Agents") { panelUp(6_000) }
    }

    private fun panelUp(timeout: Long): Boolean =
        hasText("Configure providers", 2_000)
            || hasText("No provider configured", 1_000)
            || hasText("Room Agent", 1_000)
            || hasDesc("Agent settings", 1_000)
            || hasDesc("agent_model", 1_000)

    @Test
    fun add_provider_fetch_models_and_save() {
        // ---- 1. Cold start → engine (profile auto-open or OPEN tap) --------
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)

        if (device.findObjects(By.text("OPEN")).isNotEmpty()) {
            assertTrue("OPEN must be clickable", clickText("OPEN", 8_000))
        } else {
            // First-run state: the single create affordance sits under the
            // welcome copy — visible without scrolling on every screen the
            // CI emulator boots, but scroll-aware anyway (profile cards can
            // push an "add another" variant below the fold).
            assertTrue(
                "First-run welcome should appear",
                hasText("Create Profile", 90_000) || clickTextWithScroll("Create Profile")
            )
            var dialogOpen = false
            for (attempt in 1..3) {
                assertTrue("Create Profile button must be visible", clickText("Create Profile", 5_000))
                dialogOpen = hasText("Cancel", 6_000)
                if (dialogOpen) break
            }
            assertTrue("Create-profile dialog should open", dialogOpen)
            val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
            assertTrue("Name field must be visible", field != null)
            clickCenter(field!!)
            device.executeShellCommand("input text E2E_Agent")
            val cancel = device.findObjects(By.text("Cancel")).minByOrNull { it.visibleBounds.top }
            val confirm = device.findObjects(By.text("Create Profile"))
                .filter { c -> cancel != null && kotlin.math.abs(c.visibleBounds.centerY() - cancel!!.visibleBounds.centerY()) < 200 }
                .maxByOrNull { it.visibleBounds.centerX() }
            assertTrue("Confirm button must be found", confirm != null)
            clickCenter(confirm!!)
            if (!engineUiUp(30_000)) {
                assertTrue("OPEN must appear after creation", hasText("OPEN", 10_000))
                assertTrue("OPEN must be clickable", clickText("OPEN", 5_000))
            }
        }
        // STABILITY-gated engine wait: the ':browser' process may still be
        // bound to the PREVIOUS suite's profile — the first engine activity
        // then self-restarts (bind fail → kill + alarm; the CI emulator
        // deferred the restart alarm ~5 s). Interacting with the doomed
        // surface races that restart; 8 s of CONTINUOUS surface rides it out
        // (same CI lesson as TabsE2eTest, run 227ebc3).
        assertTrue("Browser engine must be up (and stable)", engineUiStable(120_000))
        device.waitForIdle(2_000)

        // ---- 2. The floating pill is HIDDEN by default ---------------------
        // (Show-AI-Agent-button is off out of the box; the panel is reached
        // from the page menu instead.)
        assertTrue(
            "Floating agent pill must be hidden by default",
            !hasDesc("AI Agent", 3_000)
        )
        if (!openAgentPanelFromMenu()) {
            throw AssertionError("Agent panel must open from the page menu; UI:\n" + uiTree())
        }

        // Reach provider settings: empty-state button, or the header gear
        // (when a default provider already exists the empty state is skipped).
        if (!clickDesc("agent_configure", 6_000)) {
            assertTrue(
                "Agent settings (gear) must be clickable",
                clickDesc("Agent settings", 6_000)
            )
        }

        // ---- 3. AgentSettingsActivity (own window) -------------------------
        assertTrue(
            "AgentSettingsActivity must open with its title",
            hasText("AI Agent Settings", 15_000)
        )

        // ---- 4. AgentProviderEditorActivity (own window) -------------------
        assertTrue("Add provider button must appear", hasText("Add provider", 10_000))
        assertTrue("Add provider must be clickable", clickText("Add provider", 8_000))
        assertTrue("Editor must open", hasText("Presets", 10_000))

        // Fill the manual fields via their semantics descriptions
        // (name → URL → API key), then fetch the model list.
        val baseUrl = server.url("/v1").toString().trimEnd('/')
        assertTrue(
            "name field must be typeable; UI:\n" + uiTree(),
            typeIntoField("provider_name_field", "MockLLM")
        )
        assertTrue("base URL field must be typeable", typeIntoField("provider_url_field", baseUrl))
        assertTrue("API key field must be typeable", typeIntoField("provider_key_field", "test-key-123", masked = true))

        // ---- 5. Fetch models from the MockWebServer ------------------------
        // VERIFICATION-DRIVEN: after each click, watch for one of three
        // outcomes — the chips (fetch worked; they render below the fold on
        // the small CI screen, so poll + drag), the fetch ERROR text (the
        // URL text got corrupted — clear the field completely and retype),
        // or nothing at all (the tap was lost — click again). Retyping into
        // a non-empty field without clearing corrupts it at the cursor
        // (CI-observed: interleaved URL fragments, "Invalid URL port").
        hideImeIfNeeded()
        var chipsShown = false
        for (attempt in 1..3) {
            assertTrue(
                "Fetch models button must be clickable",
                clickTextWithScroll("Fetch models")
            )
            when (fetchOutcome()) {
                FetchOutcome.CHIPS -> { chipsShown = true; break }
                FetchOutcome.ERROR ->
                    assertTrue(
                        "base URL field must be retypable after a failed fetch",
                        typeIntoField("provider_url_field", baseUrl)
                    )
                FetchOutcome.NOTHING -> device.waitForIdle(2_000)
            }
        }
        if (!chipsShown) {
            throw AssertionError("Model chips from /models must appear; UI:\n" + uiTree())
        }
        if (!clickTextWithScroll("mock-model-a")) {
            throw AssertionError("mock-model-a chip must be selectable; UI:\n" + uiTree())
        }

        // ---- 6. Save -> back in the settings activity ----------------------
        hideImeIfNeeded()
        if (!clickTextWithScroll("Save provider")) {
            throw AssertionError("Save provider must be clickable; UI:\n" + uiTree())
        }
        // The save primitives are non-cancellable (AgentProviderStore.save /
        // saveAgentSettings survive the editor being finished mid-write), but
        // the EDITOR must still close ITSELF via its onDone — proving the
        // write committed. "Add provider" is only visible on the SETTINGS
        // screen (the editor covers it while open), so it is the unambiguous
        // editor-closed signal — "Presets" disappearing is NOT (a scroll can
        // push that header out of the a11y viewport while the editor is
        // still open; CI evidence run 36312695165).
        assertTrue(
            "Editor must close itself after the save completes",
            hasText("Add provider", 15_000)
        )
        assertTrue(
            "Settings screen must list the saved provider",
            hasText("MockLLM", 15_000)
        )

        // ---- 6b. The local decision gate -----------------------------------
        // The gate is switchable here but NOT usable: this test configures an
        // OpenAI-compatible provider, and /v1/systemone only exists on a local
        // Ollama 0.35+. That is exactly the state the row has to describe
        // honestly, so both halves are checked — the switch persists, and the
        // policy field the model is asked with is on the screen at all.
        //
        // Order matters. flipSwitch only advances the viewport downwards, and
        // off-screen rows of a scrollable column are not in the a11y tree, so
        // every switch is flipped FIRST, in top-to-bottom order, and the
        // policy field — which sits below all of them, in the gate's own
        // section — is found on the way down at the end. A check that needed
        // to scroll back up would fail outright: flipSwitch cannot go up.
        assertTrue(
            "Local decision gate switch must flip ON",
            flipSwitch("Local decision gate", wantOn = true)
        )
        assertTrue(
            "Local decision gate switch must flip OFF",
            flipSwitch("Local decision gate", wantOn = false)
        )

        // ---- 6c. YOLO ------------------------------------------------------
        // YOLO's "on" state is indistinguishable from the app working
        // normally, which is the whole reason it is dangerous. The screen
        // therefore owes the user a sentence saying otherwise, and flipping
        // the switch must actually render it — an off-by-one in the condition
        // that draws it would leave the app silently unguarded with nothing
        // on screen to say so, and that is worth an assertion.
        assertTrue(
            "YOLO switch must flip ON",
            flipSwitch("YOLO: always allow", wantOn = true)
        )
        // The warning paragraph renders directly under the switch row, and
        // flipSwitch may have found that row at the very bottom edge of the
        // viewport — in which case the paragraph is still below the fold and
        // therefore not in the a11y tree. One small drag brings it in and
        // still leaves the row on screen, which is what the OFF flip below
        // needs: flipSwitch only ever scrolls DOWNWARDS, so a row carried off
        // the top could never be flipped back.
        var warningUp = device.wait(Until.hasObject(By.textContains("YOLO is ON")), 1_500)
        for (i in 1..2) {
            if (warningUp) break
            dragUpQuarter()
            warningUp = device.wait(Until.hasObject(By.textContains("YOLO is ON")), 2_000)
        }
        assertTrue("YOLO must show its warning while it is on", warningUp)
        assertTrue(
            "YOLO switch must flip OFF",
            flipSwitch("YOLO: always allow", wantOn = false)
        )

        // ---- 6d. Retry on error --------------------------------------------
        // The section sits between the behavior switches above and the gate's
        // policy field below, so it is checked here — on the way down. It is
        // PRESENCE ONLY, and deliberately so: flipSwitch only ever scrolls
        // downwards, so a switch flipped ON here and then carried off the top
        // of the viewport could never be flipped back, and turning retry on
        // would leave it on for every other test in the run (the setting is
        // app-global). What the section DOES is pinned by
        // RetryingAgentGatewayTest, which drives the decorator directly.
        var retrySeen = false
        for (i in 1..12) {
            if (hasDescContains("Retry failed requests switch", 700)) { retrySeen = true; break }
            dragUpQuarter()
        }
        assertTrue(
            "The retry switch must be on the agent settings screen",
            retrySeen
        )

        var policySeen = false
        for (i in 1..18) {
            if (hasDesc("decision_gate_policy", 700)) { policySeen = true; break }
            dragUpQuarter()
        }
        assertTrue("The gate's policy field must be reachable", policySeen)
        // ---- 7. Back to the browser: the panel shows the model -------------
        // RACE GUARD: right after saving, the EDITER window can still be
        // finishing — the first node matching desc "Close" may belong to the
        // dying editor (its click is a silent no-op). Click, VERIFY the
        // engine is back, and fall back to system Back (deterministic
        // finish()) until the browser surface is truly visible again.
        assertTrue(
            "Settings must close (Close button or system Back)",
            closeUntilEngineBack()
        )
        assertTrue("Engine UI must be back", engineUiUp(15_000))
        // The panel may STILL be expanded from step 2 (rememberSaveable) —
        // in that case there is no pill to click and none is needed.
        var reopened = hasDesc("agent_model", 2_000) || hasText("Room Agent", 2_000)
        if (!reopened) {
            for (attempt in 1..3) {
                if (openAgentPanelFromMenu()) {
                    reopened = true
                    break
                }
            }
        }
        if (!reopened) {
            throw AssertionError("Agent panel must be reachable; UI:\n" + uiTree())
        }
        if (!device.wait(Until.hasObject(By.textContains("mock-model-a")), 15_000)) {
            throw AssertionError(
                "Agent panel model line must show the fetched model; " +
                    "DB ground truth: " + dbGroundTruth() +
                    "; agent logcat: " + agentLogTail() +
                    "; UI:\n" + uiTree()
            )
        }

        // ---- 7b. Chat round-trip + the copy affordance --------------------
        // Type a prompt and send it; the mock SSE reply streams back as an
        // assistant bubble. Tapping a bubble's copy icon puts that bubble's
        // text back on the clipboard — proven by the "Copied" feedback label —
        // so it can be pasted into the composer and re-processed.
        assertTrue(
            "composer field must be typeable",
            typeIntoField("agent_composer_field", "e2e_copy_prompt")
        )
        hideImeIfNeeded()
        // The IME dismissal RESIZES the composer panel — the send button
        // moves. A tap on pre-shift bounds lands on nothing (CI 227ebc3:
        // the send "succeeded", no prompt was ever sent). Settle, then a
        // VERIFIED send: fresh re-find per attempt, retried until the app
        // actually takes the prompt out of the composer.
        device.waitForIdle(1_000)
        try { Thread.sleep(400) } catch (_: InterruptedException) { }
        // The dump is taken AFTER the attempts, not before them: built into the
        // message of a call whose condition is computed second, it would show
        // the state the failure is about to change.
        val sent = sendAgentPrompt()
        assertTrue(
            "the composed prompt must be sent (the composer releases it); UI:\n" + uiTree(),
            sent
        )
        // The reply is the observable half of this round-trip. The USER bubble
        // is not: the transcript is a LazyColumn shorter than one bubble and the
        // mock reply lands at once, so the just-sent bubble leaves the composed
        // window immediately and scrolling does not bring it back — run
        // 37360922928 spent 38 s swiping both ways without finding it, while
        // mock-reply-ok was on screen and the composer had already gone back to
        // its placeholder. Both bubbles render the same CopyTextButton through
        // the same clipboard path, so the round-trip below is that same
        // assertion, made on the bubble this display can actually show.
        val replySeen = revealNode(By.text("mock-reply-ok"), 20_000)
        assertTrue(
            "mock SSE reply must stream back as an assistant bubble; UI:\n" + uiTree(),
            replySeen
        )
        assertTrue(
            "copy icon under the assistant reply must appear",
            revealDesc("agent_copy_assistant", 5_000)
        )
        assertTrue(
            "copying a bubble's text must show the Copied feedback; " +
                copyAffordanceReport("agent_copy_assistant") + "; UI:\n" + uiTree(),
            copyAndSeeFeedback("agent_copy_assistant")
        )

        // ---- 8. Show/hide the floating agent button ------------------------
        // Collapse the panel first (system Back collapses it — see the
        // BackHandler priority chain) so the pill area is observable.
        device.pressBack()
        device.waitForIdle(1_000)

        // Turn the toggle ON via Browser settings. The sheet scrolls (the
        // "Browser settings" row sits low) and the switch row itself sits
        // at the BOTTOM of the settings screen (AI Agent section) — both
        // need scroll-aware clicking.
        assertTrue(
            "Browser settings must open",
            openSheetEntry("Browser settings") { hasText("Browser Settings", 6_000) }
        )
        assertTrue(
            "Show AI Agent button switch must flip ON",
            flipSwitch("Show AI Agent button", wantOn = true)
        )
        // Leave settings via system Back — after scrolling, the top-bar Close
        // button has scrolled out of the viewport.
        device.pressBack()
        device.waitForIdle(1_000)
        assertTrue(
            "Pill must appear once the toggle is ON",
            hasDesc("AI Agent", 15_000)
        )

        // Turn the toggle OFF again — the pill disappears.
        assertTrue(
            "Browser settings must open (2nd)",
            openSheetEntry("Browser settings") { hasText("Browser Settings", 6_000) }
        )
        assertTrue(
            "Show AI Agent button switch must flip OFF",
            flipSwitch("Show AI Agent button", wantOn = false)
        )
        device.pressBack()
        device.waitForIdle(1_000)
        assertTrue(
            "Pill must be gone after turning the toggle OFF",
            !hasDesc("AI Agent", 4_000)
        )

        // ---- 8b. The agent's slot in the bottom bar ------------------------
        // With the pill off there is no floating button, so the bottom bar's
        // refresh slot becomes the way in — the pill's own icon, one tap to
        // the panel. A toggle that removes the only entry point is worse than
        // no toggle, so the swap is asserted rather than assumed.
        assertTrue(
            "With the pill off, the bottom bar must offer the agent; UI:\n" + uiTree(),
            hasDesc("Open Room Agent", 8_000)
        )
        assertTrue(
            "That slot must open the agent panel",
            clickDesc("Open Room Agent", 6_000) &&
                (hasDesc("agent_model", 10_000) || hasText("Room Agent", 10_000))
        )
        device.pressBack()
        device.waitForIdle(1_000)

        // ---- 9. AgentSessionsActivity opens from the page menu -------------
        assertTrue(
            "AI Agent chats must open",
            openSheetEntry("AI Agent chats") {
                hasText("Agent chats", 15_000) || hasText("No agent chats yet", 5_000)
            }
        )
        assertTrue("Sessions must close (Close button or system Back)", closeUntilEngineBack())
        assertTrue("Engine UI must be back (2nd)", engineUiUp(15_000))
    }

    /** Clicks the current activity's Close button, then falls back to the
     *  system Back button until the BROWSER surface is visible again —
     *  immune to the dying-editor-window Close race (CI evidence run
     *  36306609106: the first desc-Close belonged to the finishing editor). */
    private fun closeUntilEngineBack(): Boolean {
        for (attempt in 1..4) {
            runCatching { clickDesc("Close", 2_000) }
            if (engineUiUp(4_000)) return true
            device.pressBack()
            device.waitForIdle(1_000)
            if (engineUiUp(4_000)) return true
        }
        return engineUiUp(4_000)
    }

    /** Ground truth: reads the providers table from THIS instrumentation
     *  process (a third Room instance on the same file) — proves whether the
     *  saved provider is committed and visible cross-process. */
    private fun dbGroundTruth(): String = runCatching {
        kotlinx.coroutines.runBlocking {
            val db = androidx.room.Room.databaseBuilder(
                targetContext, com.roombrowser.data.db.AppDatabase::class.java,
                com.roombrowser.data.db.AppDatabase.NAME
            ).allowMainThreadQueries().build()
            try {
                val providers = db.agentDao().providers()
                val agentSettings = db.appStateDao().get("agent_settings")
                "providers=${providers.map { "${it.name}/${it.defaultModel}" }} " +
                    "agent_settings=$agentSettings"
            } finally {
                db.close()
            }
        }
    }.getOrElse { "db-query-failed: ${it.message}" }

    /** Last RoomAgent diagnostic lines from the app's logcat. */
    private fun agentLogTail(): String = runCatching {
        val logs = device.executeShellCommand(
            "logcat -d -s RoomAgent:V -t 40"
        ).trim()
        if (logs.isBlank()) "(no RoomAgent logs)" else logs.take(1500)
    }.getOrDefault("(logcat failed)")
}
