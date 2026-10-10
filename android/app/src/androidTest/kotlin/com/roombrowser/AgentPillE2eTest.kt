package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.roombrowser.data.repo.AgentSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * E2E for the two agent preferences that live OUTSIDE the agent settings
 * screen, and that no other instrumented suite pins end to end:
 *
 *  (A) The floating "AI Agent" pill's position. It is stored as a FRACTION
 *      (0..1) of the pill's travel range, never in pixels, so a position set
 *      in portrait survives a restart, a resize and a different device (see
 *      DraggableAgentPill / AgentPillPosition, and agentButtonXFrac /
 *      agentButtonYFrac in AppStateRepository).
 *
 *      The drag itself is `detectDragGesturesAfterLongPress`, which cannot be
 *      driven reliably from UiAutomator: a synthesized swipe with slightly
 *      wrong timing either taps the pill (opening the panel) or does nothing.
 *      So the CONTRACT is asserted instead — a fraction seeded through the
 *      repository must place the pill where that fraction says it goes, do it
 *      again after a COLD RESTART, scale proportionally between the corners,
 *      and a fraction written outside 0..1 must still land inside the
 *      viewport (the clamping in AgentPillPosition.offsetPx).
 *
 *  (B) The "Default context" standing instruction. Its editor is its OWN
 *      ACTIVITY (DefaultContextActivity) — reached from the panel's chip and
 *      from AI Agent settings — so the text has a whole screen to be written
 *      on. Turning the switch on while the text is blank must not arm
 *      anything (the panel's chip opens that editor instead), a non-blank
 *      context must still be on after a restart, and the presets saved
 *      beside it must re-apply, rename in place and delete.
 *
 * Harness: the same shape as AgentSettingsE2eTest — the page-menu route into
 * the agent panel, the shell-tap click helpers, the stability-gated engine
 * wait, and a repository seed for the state a UiAutomator gesture cannot
 * produce. Nothing is mocked; everything runs against the real app.
 *
 * GEOMETRY NOTE: the box the pill floats in is the browser's content area,
 * whose horizontal origin is the display's left edge (portrait, no cutout, no
 * horizontal insets), so the X expectation below is computed from
 * `displayWidth`. Its vertical origin/size are net of the status bar and of
 * the browser's own bottom bar — neither of which UiAutomator exposes — so
 * the Y axis is asserted as an unambiguous DIRECTION (start half vs end half,
 * near-edge thresholds), which is what the fraction means and never flakes.
 */
@RunWith(AndroidJUnit4::class)
class AgentPillE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    /** AGENT_PILL_MARGIN (12.dp) from AgentPanel.kt, converted to pixels. */
    private val marginPx: Int
        get() = dpPx(12)

    private fun dpPx(dp: Int): Int =
        (dp * targetContext.resources.displayMetrics.density).roundToInt()

    // ------------------------------------------------------------- app state

    /**
     * Reads and rewrites the agent settings straight from the shared Room
     * repository — the same lever E2eDeterminism.suppressOrganicNetworkWarnings
     * uses, and the only way to set a value that is normally produced by a
     * gesture UiAutomator cannot perform. The ':browser' process re-reads the
     * row on its next cold start.
     */
    private fun seedAgentSettings(transform: (AgentSettings) -> AgentSettings) {
        runBlocking {
            val app = targetContext.applicationContext as RoomBrowserApp
            val repo = app.graph.appState
            repo.saveAgentSettings(transform(repo.agentSettingsSnapshot()))
        }
    }

    /** Ground truth for the persisted agent settings (read back from Room). */
    private fun readAgentSettings(): AgentSettings = runBlocking {
        val app = targetContext.applicationContext as RoomBrowserApp
        app.graph.appState.agentSettingsSnapshot()
    }

    // ----------------------------------------------------------- ui plumbing

    private fun launchMainActivity() {
        val intent = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                setClassName(targetContext.packageName, "com.roombrowser.main.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
    }

    /**
     * Cold relaunch of the engine on the persisted active profile. This is
     * the "process restart" every persistence assertion below is about: the
     * ':browser' process is left to come up again and re-read its settings.
     */
    private fun relaunchEngine() {
        targetContext.startActivity(
            Intent()
                .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        device.waitForIdle(1_500)
    }

    private fun hasText(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs)

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

    private fun hasDescContains(part: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.descContains(part)), timeoutMs)

    private fun textExists(part: String): Boolean =
        runCatching { device.findObjects(By.textContains(part)).isNotEmpty() }
            .getOrDefault(false)

    /** Polls until [condition] holds (the harness' non-sleeping wait idiom). */
    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return condition()
    }

    /** Polls until no node carries [desc] anymore (sheet/editor closed). */
    private fun waitGoneDesc(desc: String, timeoutMs: Long): Boolean = waitUntil(timeoutMs) {
        runCatching { device.findObjects(By.desc(desc)).isEmpty() }.getOrDefault(false)
    }

    private fun clickCenter(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        // SHELL TAP, not device.click(): the CI runner's busy a11y pipeline
        // silently swallows injected gestures; `input tap` is deterministic.
        device.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}")
        device.waitForIdle(1_000)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Clicks via the accessibility ACTION_CLICK (immune to overlays such as
     * the IME covering the node), walking up to the nearest clickable
     * ancestor for Compose text-inside-button nodes; coordinate-tap fallback.
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

    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    /**
     * [clickDesc] for a node that may start below the fold.
     *
     * A composed row that is scrolled out of view still carries its content
     * description, so `findObject` returns it — but its `visibleBounds` is
     * empty and `click()` on it goes nowhere, which reads as "the row is not
     * clickable" when the row was simply never on screen. The node is
     * scrolled into view before the click, and only clicked once it is.
     */
    private fun clickDescScrolled(desc: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val node = device.findObject(By.desc(desc))
            val onScreen = runCatching { (node?.visibleBounds?.height() ?: 0) > 0 }
                .getOrDefault(false)
            if (node != null && onScreen) return clickSmart(node)
            dragUpQuarter()
        }
        return false
    }

    /** SLOW drag (100 steps, no fling) that scrolls ~half the screen — the
     *  proven deterministic scroll for the CI emulator's small profile. */
    private fun dragUpQuarter() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 100
        )
        device.waitForIdle(800)
        try { Thread.sleep(300) } catch (_: InterruptedException) { }
    }

    /** The address pill always carries this desc, so it detects the engine. */
    private fun engineUiUp(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasDescContains("Address bar", 400)) return true
            if (hasText("Search or type URL", 400)) return true
            if (hasText("Privacy Dashboard", 400)) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return hasDescContains("Address bar", 500)
    }

    /**
     * The engine surface must be up CONTINUOUSLY for [stableMs] — a surface
     * that dies (':browser' self-restart while rebinding to a fresh profile)
     * resets the window, and interacting with the doomed surface races it.
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

    /** Opens a page-actions sheet entry by desc and VERIFIES the effect. */
    private fun openSheetEntry(label: String, verify: () -> Boolean): Boolean {
        for (round in 1..3) {
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
            || hasText("No AI provider configured", 1_000)
            || hasText("Room Agent", 1_000)
            || hasDesc("Agent settings", 1_000)
            || hasDesc("agent_model", 1_000)

    private fun imeShown(): Boolean = try {
        device.executeShellCommand("dumpsys input_method | grep mInputShown")
            .contains("mInputShown=true")
    } catch (_: Exception) {
        false
    }

    /** The IME is a separate accessibility window that shadows node lookups. */
    private fun hideImeIfNeeded() {
        if (imeShown()) {
            device.pressBack()
            device.waitForIdle(600)
        }
    }

    /**
     * Profile + engine, first-run or returning — the same start state
     * AgentSettingsE2eTest reaches (OPEN an existing profile, or create the
     * single first-run one), then wait for a STABLE engine surface.
     */
    private fun bootstrapEngine() {
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)
        assertTrue(
            "Profile list or first-run state must appear",
            waitUntil(90_000) {
                device.findObjects(By.text("OPEN")).isNotEmpty() ||
                    device.findObjects(By.text("Create Profile")).isNotEmpty()
            }
        )
        if (device.findObjects(By.text("OPEN")).isNotEmpty()) {
            assertTrue("OPEN must be clickable", clickText("OPEN", 8_000))
        } else {
            assertTrue("Create Profile must be visible", clickText("Create Profile", 8_000))
            assertTrue("Create-profile dialog should open", hasText("Cancel", 8_000))
            val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8_000)
            assertTrue("Name field must be visible", field != null)
            clickCenter(field!!)
            device.executeShellCommand("input text E2ePill")
            device.waitForIdle(1_000)
            val cancel = device.findObjects(By.text("Cancel")).minByOrNull { it.visibleBounds.top }
            val confirm = device.findObjects(By.text("Create Profile"))
                .filter { c ->
                    cancel != null && abs(c.visibleBounds.centerY() - cancel.visibleBounds.centerY()) < 200
                }
                .maxByOrNull { it.visibleBounds.centerX() }
            assertTrue("Confirm button must be found", confirm != null)
            clickCenter(confirm!!)
            if (!engineUiUp(30_000)) {
                assertTrue("OPEN must appear after creation", hasText("OPEN", 10_000))
                assertTrue("OPEN must be clickable", clickText("OPEN", 5_000))
            }
        }
        assertTrue("Browser engine must be up (and stable)", engineUiStable(120_000))
        device.waitForIdle(1_500)
    }

    // -------------------------------------------------------- pill geometry

    private data class PillRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    /** The pill's bounds on screen, or null when it is not up yet. */
    private fun pillBounds(): PillRect? {
        val node = device.wait(Until.findObject(By.desc("AI Agent")), 20_000) ?: return null
        val b = runCatching { node.visibleBounds }.getOrNull() ?: return null
        return PillRect(b.left, b.top, b.right, b.bottom)
    }

    /**
     * The pill's offset from the start of its box for [fraction] — the same
     * arithmetic as AgentPillPosition.offsetPx (margin + clampedFraction *
     * range, range = box - pill - 2 * margin), recomputed here so the test
     * does not depend on the app's own function to say what it expects.
     */
    private fun expectedOffsetPx(fraction: Float, boxSize: Int, pillSize: Int, margin: Int): Int {
        val range = (boxSize - pillSize - margin * 2).coerceAtLeast(0)
        return margin + (range * fraction.coerceIn(0f, 1f)).roundToInt()
    }

    // ------------------------------------------------------- default context

    private fun contextEditorOpen(): Boolean =
        runCatching { device.findObjects(By.desc("agent_default_context_field")).isNotEmpty() }
            .getOrDefault(false)

    /** Opens the editor the way AI Agent settings does, without the panel. */
    private fun launchContextEditor() {
        targetContext.startActivity(
            Intent()
                .setClassName(
                    targetContext.packageName,
                    "com.roombrowser.agent.ui.DefaultContextActivity"
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        device.waitForIdle(1_500)
    }

    /**
     * Leaves the editor. Back is pressed once per window it has to close — the
     * IME first when a field is focused, then the activity — and the exit is
     * VERIFIED rather than assumed, because a Back that only dismissed the
     * keyboard would leave the next step looking at the wrong screen.
     */
    private fun closeContextEditor(): Boolean {
        for (attempt in 1..3) {
            if (!contextEditorOpen()) return true
            device.pressBack()
            device.waitForIdle(800)
        }
        return waitGoneDesc("agent_default_context_field", 2_000)
    }

    /** True while the preset row for [name] is actually ON SCREEN. */
    private fun presetRowVisible(name: String): Boolean = runCatching {
        val height = device.findObject(By.desc("agent_context_preset_use_$name"))
            ?.visibleBounds?.height() ?: 0
        height > 0
    }.getOrDefault(false)

    /**
     * Scrolls the preset list in until the row for [name] is on screen.
     *
     * The list sits below the two fields and the buttons the editor opens on,
     * so the row starts out of view. It is composed there — reaching it by
     * scrolling is what makes this an assertion about the editor rather than
     * about where the editor happens to be scrolled to.
     */
    private fun scrollToPresetRow(name: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (presetRowVisible(name)) return true
            dragUpQuarter()
        }
        return presetRowVisible(name)
    }

    /**
     * Scrolls [desc] back into the editor's semantics tree.
     *
     * Compose stops reporting a node that is scrolled out of the viewport, so
     * `findObject` returning null there means "out of view", not "not on the
     * screen" — and a click or a tap into empty bounds silently goes nowhere.
     * The screen is therefore paged in BOTH directions until the node is back.
     */
    private fun scrollToField(desc: String, timeoutMs: Long): Boolean {
        repeat(4) {
            if (device.hasObject(By.desc(desc))) return true
            dragDownQuarter()
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.hasObject(By.desc(desc))) return true
            dragUpQuarter()
        }
        return device.hasObject(By.desc(desc))
    }

    /** Every text on screen, for a failure message that shows what was there. */
    private fun uiDump(): String = runCatching {
        device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(60)
            .joinToString(" | ")
    }.getOrDefault("<no dump>")

    /** SLOW drag the other way, back to the top of a long editor screen. */
    private fun dragDownQuarter() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight / 4,
            device.displayWidth / 2, device.displayHeight * 3 / 4, 100
        )
        device.waitForIdle(800)
        try { Thread.sleep(300) } catch (_: InterruptedException) { }
    }

    /**
     * Types [text] into the field carrying [desc]. Both fields are Compose
     * OutlinedTextFields, so this is the proven typeIntoField shape: tap to
     * focus, clear, type through the shell, VERIFY with a global text search
     * (the field renders its content on an inner text node).
     *
     * A field below the fold is scrolled in first: its `visibleBounds` is
     * empty there, and the shell tap would land on whatever is on screen
     * instead of the field.
     */
    private fun typeIntoField(desc: String, text: String): Boolean {
        for (round in 1..3) {
            hideImeIfNeeded()
            var field = device.wait(Until.findObject(By.desc(desc)), 3_000) ?: return false
            for (scroll in 1..4) {
                val onScreen = runCatching { field.visibleBounds.height() > 0 }.getOrDefault(false)
                if (onScreen) break
                dragUpQuarter()
                field = device.wait(Until.findObject(By.desc(desc)), 2_000) ?: return false
            }
            clickSmart(field)
            device.executeShellCommand("input keyevent KEYCODE_MOVE_END")
            device.clearFocusedField()
            device.waitForIdle(400)
            device.executeShellCommand("input text $text")
            device.waitForIdle(1_000)
            if (device.wait(Until.hasObject(By.textContains(text)), 2_000)) return true
        }
        return false
    }

    // ------------------------------------------------------------------ (A)

    /**
     * The stored fraction is honoured, it survives a cold restart, and it
     * SCALES: 0 is the start corner, 1 the end corner, and 0.5 lands midway
     * between the two on both axes. Midway is the part a "left half vs right
     * half" check would miss — a pill pinned to an edge would still pass that.
     */
    @Test
    fun agent_pill_position_fraction_survives_a_process_restart() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        bootstrapEngine()

        val boxW = device.displayWidth
        val boxH = device.displayHeight
        val tol = dpPx(16)

        // ---- fraction 0: the start corner -----------------------------------
        seedAgentSettings {
            it.copy(showAgentButton = true, agentButtonXFrac = 0f, agentButtonYFrac = 0f)
        }
        relaunchEngine()
        assertTrue("Engine must come back up", engineUiUp(30_000))
        val start = pillBounds()
            ?: throw AssertionError("The pill must appear for a stored fraction of 0")
        assertTrue(
            "A fraction of 0 must sit near the start edges (was ${start.left},${start.top})",
            start.left <= boxW / 4 && start.top <= boxH / 4
        )
        assertTrue(
            "A fraction of 0 must sit in the top-start corner",
            start.centerX < boxW / 2 && start.centerY < boxH / 2
        )
        assertTrue(
            "The left edge of a fraction-0 pill must be the 12dp margin (was ${start.left})",
            abs(start.left - expectedOffsetPx(0f, boxW, start.width, marginPx)) <= tol
        )
        assertTrue(
            "The pill must never be outside its viewport (was $start)",
            start.left >= 0 && start.top >= 0 && start.right <= boxW && start.bottom <= boxH
        )

        // ---- fraction 1: the end corner -------------------------------------
        seedAgentSettings {
            it.copy(agentButtonXFrac = 1f, agentButtonYFrac = 1f)
        }
        relaunchEngine()
        assertTrue("Engine must come back up (2nd)", engineUiUp(30_000))
        val end = pillBounds()
            ?: throw AssertionError("The pill must appear for a stored fraction of 1")
        assertTrue(
            "A fraction of 1 must sit near the end edges (was ${end.right},${end.bottom})",
            end.right >= boxW * 3 / 4 && end.bottom >= boxH * 3 / 5
        )
        assertTrue(
            "A fraction of 1 must sit in the bottom-end corner",
            end.centerX > boxW / 2 && end.centerY > boxH / 2
        )
        assertTrue(
            "The left edge of a fraction-1 pill must be margin + full range (was ${end.left})",
            abs(end.left - expectedOffsetPx(1f, boxW, end.width, marginPx)) <= tol
        )
        assertTrue(
            "The fraction-1 pill must move towards the end on BOTH axes",
            end.left > start.left && end.top > start.top
        )
        assertTrue(
            "The pill must never be outside its viewport (was $end)",
            end.left >= 0 && end.top >= 0 && end.right <= boxW && end.bottom <= boxH
        )

        // ---- fraction 0.5: midway, not pinned to either edge -----------------
        seedAgentSettings {
            it.copy(agentButtonXFrac = 0.5f, agentButtonYFrac = 0.5f)
        }
        relaunchEngine()
        assertTrue("Engine must come back up (3rd)", engineUiUp(30_000))
        val mid = pillBounds()
            ?: throw AssertionError("The pill must appear for a stored fraction of 0.5")
        val midTol = dpPx(8)
        assertTrue(
            "A fraction of 0.5 must place the pill midway along X " +
                "(start=${start.centerX}, mid=${mid.centerX}, end=${end.centerX})",
            abs(mid.centerX - (start.centerX + end.centerX) / 2) <= midTol
        )
        assertTrue(
            "A fraction of 0.5 must place the pill midway along Y " +
                "(start=${start.centerY}, mid=${mid.centerY}, end=${end.centerY})",
            abs(mid.centerY - (start.centerY + end.centerY) / 2) <= midTol
        )
        assertTrue(
            "The midpoint must be strictly between the corners",
            mid.centerX > start.centerX && mid.centerX < end.centerX
        )
    }

    /**
     * A fraction written OUTSIDE 0..1 (an older build, or a hand-edited
     * backup) must still leave the pill inside the viewport — clamped to the
     * edge, not stranded off-screen where it cannot be grabbed again.
     */
    @Test
    fun agent_pill_position_clamps_an_out_of_range_fraction() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        bootstrapEngine()

        val boxW = device.displayWidth
        val boxH = device.displayHeight

        seedAgentSettings {
            it.copy(showAgentButton = true, agentButtonXFrac = 5f, agentButtonYFrac = -5f)
        }
        relaunchEngine()
        assertTrue("Engine must come back up", engineUiUp(30_000))
        val pill = pillBounds()
            ?: throw AssertionError("The pill must still appear for an out-of-range fraction")

        assertTrue(
            "An out-of-range fraction must not push the pill off-screen (was $pill)",
            pill.left >= 0 && pill.top >= 0 && pill.right <= boxW && pill.bottom <= boxH
        )
        // 5 clamps to 1: the end edge.
        assertTrue(
            "A fraction of 5 must clamp to the end edge (was ${pill.right})",
            pill.right >= boxW * 3 / 4 && pill.centerX > boxW / 2
        )
        assertTrue(
            "The clamped X must be exactly the fraction-1 position (was ${pill.left})",
            abs(pill.left - expectedOffsetPx(1f, boxW, pill.width, marginPx)) <= dpPx(16)
        )
        // -5 clamps to 0: the start edge.
        assertTrue(
            "A fraction of -5 must clamp to the start edge (was ${pill.top})",
            pill.top <= boxH / 4 && pill.centerY < boxH / 2
        )
    }

    // ------------------------------------------------------------------ (B)

    /**
     * The standing context: blank text must not arm the toggle (the chip
     * opens the editor, and the editor's Save is disabled while the text is
     * blank), while a non-blank context saves, switches the toggle on, and is
     * still on after a cold restart — asserted from the persisted value and
     * from the composer's own "Context: …" line.
     */
    @Test
    fun default_context_refuses_blank_and_survives_a_process_restart() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        bootstrapEngine()

        // No floating pill in this test: it would only sit on top of the
        // browser chrome, and the page-menu route is the proven way in.
        seedAgentSettings {
            it.copy(
                showAgentButton = false,
                agentButtonXFrac = null,
                agentButtonYFrac = null,
                defaultContext = "",
                useDefaultContext = false,
                contextPresets = emptyList()
            )
        }
        relaunchEngine()
        assertTrue("Engine must come back up", engineUiUp(30_000))

        // ---- 1. Blank text: the chip opens the editor, it does not arm ------
        assertTrue("Agent panel must open from the page menu", openAgentPanelFromMenu())
        assertTrue(
            "The Default context chip must be tappable",
            clickDesc("agent_default_context", 8_000)
        )
        assertTrue(
            "Tapping the chip with blank text must open the editor, not arm the toggle",
            hasDesc("agent_default_context_field", 8_000)
        )
        // The editor is its own window and hides everything behind it, so only
        // its own contents can be asserted while it is open.
        val save = device.findObjects(By.desc("agent_default_context_save")).firstOrNull()
        assertTrue("The editor must offer a Save action", save != null)
        // The refusal is asserted as BEHAVIOUR, not as `save.isEnabled`. The
        // button really is disabled while blank — `enabled = draft.isNotBlank()`
        // in DefaultContextActivity — but reading that flag back through
        // UiAutomator is not reliable: Compose surfaces `enabled = false` as a
        // separate semantics node from the one carrying this content
        // description, so `isEnabled` came back true on a button that cannot be
        // pressed. Asserting the flag tested the accessibility plumbing rather
        // than the contract. Pressing Save while blank must leave the toggle
        // off; that is what the name of this test promises, and it holds
        // whether the press is refused by the disabled button or ignored by the
        // editor.
        clickSmart(save!!)
        assertTrue(
            "A blank context must not arm the toggle",
            !readAgentSettings().useDefaultContext
        )
        assertTrue("The editor must close on Back", closeContextEditor())

        // ---- 2. Write a context and save it ---------------------------------
        val context = "e2e_context_keep"
        var saved = false
        for (attempt in 1..3) {
            if (!contextEditorOpen()) {
                if (!clickDesc("agent_default_context", 5_000)) {
                    // Back may have collapsed the panel: reopen it and retry.
                    openAgentPanelFromMenu()
                    if (!clickDesc("agent_default_context", 5_000)) continue
                }
                if (!hasDesc("agent_default_context_field", 8_000)) continue
            }
            if (!typeIntoField("agent_default_context_field", context)) {
                hideImeIfNeeded()
                continue
            }
            hideImeIfNeeded()
            // Back may have taken the whole editor if the IME was not up.
            if (!contextEditorOpen()) continue
            val button = device.wait(Until.findObject(By.desc("agent_default_context_save")), 3_000)
                ?: continue
            clickSmart(button)
            if (waitUntil(6_000) {
                    val s = readAgentSettings()
                    s.useDefaultContext && s.defaultContext == context
                }
            ) {
                saved = true
                break
            }
        }
        assertTrue("Saving a non-blank context must persist it and switch it on", saved)
        if (!closeContextEditor()) {
            assertTrue("The editor must close before the panel is used again", false)
        }

        // ---- 3. It survives a process restart, and the composer shows it ----
        relaunchEngine()
        assertTrue("Engine must come back up (2nd)", engineUiUp(30_000))
        val restored = readAgentSettings()
        assertTrue("The toggle must still be on after the restart", restored.useDefaultContext)
        assertEquals("The context text must survive the restart", context, restored.defaultContext)

        assertTrue("Agent panel must open from the page menu (2nd)", openAgentPanelFromMenu())
        assertTrue(
            "The composer must show the standing context as active",
            hasDesc("agent_active_contexts", 8_000) && textExists(context)
        )
    }

    /**
     * (C) The presets saved beside the standing context — the CRUD the editor
     * exposes. Each step is asserted from the STORED list, because the write
     * and the read happen in different windows and a screen that merely looks
     * right proves nothing about what was saved.
     *
     * The whole flow runs in the editor activity itself (reached by the same
     * intent AI Agent settings uses) rather than through the panel: this test
     * is about what the editor does, and the panel route is already exercised
     * by (B).
     */
    @Test
    fun a_context_preset_saves_applies_renames_and_deletes() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        bootstrapEngine()

        val tag = System.currentTimeMillis() % 100000
        val text = "e2e_preset_text_$tag"
        val name = "e2e_preset_$tag"
        val renamed = "e2e_preset_renamed_$tag"
        seedAgentSettings {
            it.copy(
                showAgentButton = false,
                agentButtonXFrac = null,
                agentButtonYFrac = null,
                defaultContext = "",
                useDefaultContext = false,
                contextPresets = emptyList()
            )
        }

        // ---- 1. Save a preset out of the text field -------------------------
        launchContextEditor()
        assertTrue("The context editor must open", hasDesc("agent_default_context_field", 15_000))
        assertTrue(
            "The editor's text field must accept the context",
            typeIntoField("agent_default_context_field", text)
        )
        assertTrue(
            "The editor's preset-name field must accept a name",
            typeIntoField("agent_context_preset_name", name)
        )
        assertTrue(
            "The editor must offer Save as preset",
            clickDesc("agent_context_preset_save", 5_000)
        )
        assertTrue(
            "Saving a preset must store it under the typed name",
            waitUntil(8_000) {
                readAgentSettings().contextPresets.any { it.name == name && it.text == text }
            }
        )

        // ---- 2. A fresh editor instance still lists it ----------------------
        assertTrue("The editor must close on Back", closeContextEditor())
        launchContextEditor()
        assertTrue("The context editor must reopen", hasDesc("agent_default_context_field", 15_000))
        if (!scrollToPresetRow(name, 10_000)) {
            // The message carries what the screen DID show: a stored list that
            // is empty, an editor that says so, and an editor that shows
            // neither are three different defects.
            val stored = readAgentSettings().contextPresets.joinToString(",") { it.name }
            assertTrue(
                "A saved preset must be listed when the editor is opened again; " +
                    "stored=[$stored]; empty state shown=${textExists("No presets saved yet")}\n" +
                    uiDump(),
                false
            )
        }

        // ---- 3. One tap applies it -----------------------------------------
        assertTrue(
            "The preset row must be applicable",
            clickDescScrolled("agent_context_preset_use_$name", 8_000)
        )
        assertTrue(
            "Applying a preset must make it the active context",
            waitUntil(8_000) {
                val s = readAgentSettings()
                s.useDefaultContext && s.defaultContext == text
            }
        )

        // ---- 4. Editing RENAMES in place; it does not add a second copy -----
        assertTrue(
            "The preset must offer an Edit action",
            clickDescScrolled("agent_context_preset_edit_$name", 8_000)
        )
        // Edit mode is asserted before anything is typed: two blind drags used to
        // be the only thing between the tap and the rename, so a tap that landed
        // nowhere and a field that would not take text read identically.
        assertTrue(
            "Edit must put the editor in edit mode (the save button becomes Update preset); " +
                "cancel button shown=${device.hasObject(By.desc("agent_context_preset_cancel"))}\n" +
                uiDump(),
            scrollToField("agent_context_preset_save", 8_000) &&
                waitUntil(5_000) {
                    device.hasObject(By.text("Update preset")) ||
                        device.hasObject(By.desc("agent_context_preset_cancel"))
                }
        )
        // The name field is composed at the top, above the list the previous steps
        // left in view — and Compose stops reporting a node once it leaves the
        // viewport, so it has to be scrolled BACK INTO the tree, not clicked blind.
        assertTrue(
            "The name field must still be reachable after an edit was started\n${uiDump()}",
            scrollToField("agent_context_preset_name", 10_000)
        )
        assertTrue(
            "The preset-name field must accept the new name\n${uiDump()}",
            typeIntoField("agent_context_preset_name", renamed)
        )
        assertTrue(
            "The editor must offer Update preset",
            clickDescScrolled("agent_context_preset_save", 8_000)
        )
        assertTrue(
            "A renamed preset must replace the old one, not add a copy",
            waitUntil(8_000) {
                val list = readAgentSettings().contextPresets
                list.size == 1 && list.first().name == renamed && list.first().text == text
            }
        )

        // ---- 5. Delete asks first, then removes it --------------------------
        assertTrue(
            "The preset must offer a Delete action",
            clickDescScrolled("agent_context_preset_delete_$renamed", 8_000)
        )
        assertTrue("Deleting must ask first", hasText("Delete preset?", 5_000))
        assertTrue("The confirmation must be pressable", clickText("Delete", 5_000))
        assertTrue(
            "A confirmed delete must remove the preset",
            waitUntil(8_000) { readAgentSettings().contextPresets.isEmpty() }
        )
        assertEquals(
            "Deleting a preset must not touch the standing context",
            text,
            readAgentSettings().defaultContext
        )
        assertTrue("The editor must close on Back", closeContextEditor())
    }
}
