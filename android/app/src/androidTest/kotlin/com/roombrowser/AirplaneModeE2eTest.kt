package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the chat's Airplane Mode command, against the real app on an
 * UNROOTED emulator:
 *
 *   agent panel -> "/airplane sideways" answers with the usage notice and
 *   no turn starts (the interceptor consumed the text — the DB walk proves
 *   no user row was ever persisted);
 *   -> with "Offer Airplane Mode shortcut" switched off, "/airplane on"
 *   answers with the switched-off notice and raises nothing;
 *   -> with the setting on, "/airplane on" opens the system settings screen
 *   and raises the guided dialog (airplane_accept / airplane_stop); No stops
 *   with the stopped notice and the dialog is gone;
 *   -> "/airplane on" again — mid-chat, the same conversation — Yes is
 *   answered HONESTLY: the emulator's airplane mode is still off and the
 *   instrumentation cannot flip it (it runs as the app uid, not root), so
 *   the notice says "still off" as an error rather than pretending the
 *   switch happened.
 *
 * The rooted ATTEMPT is real on this image: the CI emulator is the AOSP
 * `default` target, which SHIPS /system/bin/su — a binary that refuses the
 * app's uid. The command's root branch therefore RUNS, is denied, says so
 * ("did not take effect, so it is offered manually instead"), and falls
 * back to the guided hand-off — which is exactly the dialog this test then
 * drives. A genuinely su-less device reaches the same dialog without the
 * notice, and this test asserts the notice only when it finds the su
 * binary (shellHasSu below), so it stays honest on either image.
 *
 * The SUCCESSFUL root switch and the airplane_mode agent-tool path are NOT
 * drivable here, matching this suite's standing convention: no root grant
 * exists for the app's uid, and the suite's provider is a dead port, so no
 * model ever emits a tool call to drive. Both halves are held by the JVM
 * tests instead (PluginUiStateTest's fake bridge, AgentAppActionsTest's
 * write/destructive classification).
 *
 * The guided hand-off opens the REAL system settings screen over the app,
 * so every dialog step first returns to the app's own task — Back is
 * pressed only while a foreign package is in front, because a Back on our
 * own dialog is its No, not this test's business.
 */
@RunWith(AndroidJUnit4::class)
class AirplaneModeE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private val appGraph: com.roombrowser.di.AppGraph
        get() = (targetContext.applicationContext as com.roombrowser.RoomBrowserApp).graph

    /** The su paths the app's own root check consults — mirrored for [shellHasSu]. */
    private val suPaths = setOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su"
    )

    @Before
    fun setUp() {
        // Determinism: the runner's shared IP arms the organic network warning
        // on every fresh-profile boot — suppress it (see E2eDeterminism).
        E2eDeterminism.suppressOrganicNetworkWarnings()
    }

    @After
    fun tearDown() {
        // Never leave the master switch off for the next suite class.
        runBlocking {
            val current = appGraph.appState.globalSettingsSnapshot()
            if (!current.offerAirplaneModeShortcut) {
                appGraph.appState.saveGlobalSettings(
                    current.copy(offerAirplaneModeShortcut = true)
                )
            }
        }
        runCatching { returnToApp(3_000) }
        device.pressHome()
    }

    @Test
    fun airplane_command_usage_gate_and_guided_dialog() {
        // ---- 1. "/airplane sideways": the usage notice, and no turn -------
        bootstrapEngine()
        assertTrue(
            "The agent panel must open from the page menu; UI:\n${uiTree()}",
            openAgentPanelFromMenu()
        )
        assertTrue(
            "The command must reach the composer and send",
            sendComposer("/airplane sideways")
        )
        assertTrue(
            "A directionless /airplane must answer with the usage notice",
            hasTextContains("the word after the command says which way to switch", 10_000)
        )
        assertTrue(
            "The command must raise no guided dialog",
            waitGoneDesc("airplane_stop", 3_000)
        )
        assertTrue(
            "An intercepted command persists no user row — no turn started",
            noUserRowHolds("/airplane sideways")
        )

        // ---- 2. The master switch off: the notice, and nothing raised ----
        // The flag is written through the app's own state repository and the
        // engine RELAUNCHED, because the ':browser' process must re-read it
        // before the command can be gated — the cold start is the honest
        // hand-off this suite uses for cross-process settings.
        setGlobalFlags(offerAirplaneModeShortcut = false)
        relaunchBrowser()
        assertTrue("The engine must come back up", engineUiStable(120_000))
        assertTrue(
            "The agent panel must come back up",
            openAgentPanelFromMenu()
        )
        assertTrue(
            "The command must reach the composer and send again",
            sendComposer("/airplane on")
        )
        assertTrue(
            "A gated /airplane must name the setting it is gated by",
            hasTextContains("is switched off — enable \"Offer Airplane Mode shortcut\"", 10_000)
        )
        assertTrue(
            "A gated /airplane must raise no guided dialog",
            waitGoneDesc("airplane_stop", 3_000)
        )
        // Restore the feature before the flows that need it — and relaunch
        // for the same cross-process reason as above.
        setGlobalFlags(offerAirplaneModeShortcut = true)

        // ---- 3. Setting on: the guided dialog, answered with No ----------
        relaunchBrowser()
        assertTrue("The engine must come back up", engineUiStable(120_000))
        assertTrue(
            "The agent panel must come back up",
            openAgentPanelFromMenu()
        )
        assertTrue(
            "The command must reach the composer and send",
            sendComposer("/airplane on")
        )
        // On this image the su binary exists and denies the app's uid, so the
        // root branch runs and fails BEFORE the guided hand-off — the failure
        // is said out loud, and only a su-less device skips it.
        if (shellHasSu()) {
            assertTrue(
                "A denied root attempt must say so before the guided hand-off",
                hasTextContains(
                    "the root command did not take effect, so it is offered manually instead",
                    15_000
                )
            )
        }
        // Come back to the app's own task BEFORE asserting the dialog: the
        // guided hand-off opens the REAL system settings screen over it, and
        // a node the foreign screen covers is not queryable — under it, the
        // dialog is up either way (the settings launch and the raise ride
        // the same send), so the assertion lands on the app's own surface.
        assertTrue("The app's own task must be in front", returnToApp(10_000))
        assertTrue(
            "The guided confirmation dialog must be up back in the app",
            hasDesc("airplane_stop", 15_000)
        )
        assertTrue(
            "No must be clickable",
            clickDesc("airplane_stop", 5_000)
        )
        assertTrue(
            "The dialog must close on No",
            waitGoneDesc("airplane_stop", 10_000)
        )
        assertTrue(
            "No must answer with the stopped notice",
            hasTextContains("Airplane Mode was not changed — stopped.", 10_000)
        )

        // ---- 4. Mid-chat, the same command again: an honest Yes ----------
        // The emulator's airplane mode is off and this test cannot flip it,
        // so Yes is answered with the honest error — the trust-but-verify
        // half of the flow, driven by the only device state available.
        assertTrue(
            "The command must reach the composer once more, mid-chat",
            sendComposer("/airplane on")
        )
        // Same order as before — the settings screen opens over the app again.
        assertTrue("The app's own task must be in front again", returnToApp(10_000))
        assertTrue(
            "The guided confirmation dialog must be up again",
            hasDesc("airplane_accept", 15_000)
        )
        assertTrue(
            "Yes must be clickable",
            clickDesc("airplane_accept", 5_000)
        )
        assertTrue(
            "The dialog must close on Yes",
            waitGoneDesc("airplane_accept", 10_000)
        )
        assertTrue(
            "A Yes that did not switch anything must say so honestly",
            hasTextContains("Airplane mode is still off — the chat continues.", 10_000)
        )
    }

    // ----------------------------------------------------------- state helpers

    /**
     * The "Offer Airplane Mode shortcut" master switch, written through the
     * app's own state repository — the same door the settings screen uses
     * (the NetworkWarningActivity suite's setGlobalFlags pattern).
     */
    private fun setGlobalFlags(offerAirplaneModeShortcut: Boolean? = null) = runBlocking<Unit> {
        val current = appGraph.appState.globalSettingsSnapshot()
        appGraph.appState.saveGlobalSettings(
            current.copy(
                offerAirplaneModeShortcut =
                    offerAirplaneModeShortcut ?: current.offerAirplaneModeShortcut
            )
        )
    }

    /**
     * Whether ANY of this profile's chats persisted a user row containing
     * [text] — the ground-truth walk proving an intercepted command never
     * started a turn (the panel renders notices, the DB holds the truth).
     */
    private fun noUserRowHolds(text: String): Boolean = runBlocking {
        val profileId = appGraph.appState.activeProfileIdSnapshot() ?: return@runBlocking true
        appGraph.database.agentDao().sessions(profileId).none { session ->
            appGraph.database.agentDao().messages(session.id).any { row ->
                row.role == "user" && row.content.contains(text)
            }
        }
    }

    /**
     * Leaves whatever foreign screen the guided hand-off opened (the system
     * Airplane Mode settings) and waits for this app to be the foreground
     * package again. Back is pressed ONLY while a foreign package is in
     * front: a Back on our own dialog is the dialog's No, which is the
     * flow's business, never this helper's.
     */
    private fun returnToApp(timeoutMs: Long): Boolean {
        // Settle first: the settings launch rides the same send as the dialog
        // raise, and a too-early check would read our own package before the
        // foreign screen ever came up.
        device.waitForIdle(1_500)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.currentPackageName == targetContext.packageName) return true
            device.pressBack()
            device.waitForIdle(1_000)
        }
        return device.currentPackageName == targetContext.packageName
    }

    /**
     * Cold-restarts the ENGINE on the persisted active profile — the suite's
     * own relaunch convention (SettingsPersistenceE2eTest.relaunchEngine, the
     * DevTools search e2e's launchEngine): BrowserActivity directly, with
     * NEW_TASK|CLEAR_TASK and no profile picker in the way, because a
     * relaunch mid-test needs the browser surface the next assertion reads,
     * not the first-run flow again. CI 0d6730a forensics: relaunching through
     * the package's LAUNCH intent lands on the profile picker, which shows
     * neither node engineUiStable accepts, so the wait timed out and the
     * suite reported "The engine must come back up".
     */
    private fun relaunchBrowser() {
        targetContext.startActivity(
            Intent()
                .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    /** Cold-starts the app on its launcher entry — the profile-picker / first-run surface only [bootstrapEngine] walks. */
    private fun launchFromHome() {
        val intent = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                setClassName(targetContext.packageName, "com.roombrowser.main.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
    }

    /**
     * Whether this device carries a su binary the app's own root check would
     * find — read through the shell the instrumentation holds, because the
     * app-side check answers the same question but is not reachable from here.
     * Decides whether the root-attempt failure notice is asserted: the CI
     * image (AOSP `default`) ships su and denies the app's uid, so the branch
     * runs; a su-less image goes straight to the guided hand-off.
     */
    private fun shellHasSu(): Boolean = try {
        instrumentation.uiAutomation
            .executeShellCommand("ls /system/bin/su /system/xbin/su /sbin/su /vendor/bin/su 2>/dev/null")
            .use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                    input.readBytes().decodeToString()
                }
            }
            .lineSequence()
            .any { it.trim() in suPaths }
    } catch (_: Exception) {
        // An unreadable answer is not "no su" — it is no answer, and the
        // notice assertion is skipped rather than guessed at.
        false
    }

    // ----------------------------------------------------------- ui plumbing

    private fun hasText(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs)

    private fun hasTextContains(part: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.textContains(part)), timeoutMs)

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

    /** Polls until [condition] holds (the harness' non-sleeping wait idiom). */
    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                }
        }
        return condition()
    }

    /** Polls until no node carries [desc] anymore (sheet/dialog closed). */
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

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    /**
     * Types [text] into the composer, settles the IME away, then taps Send.
     * The send button MOVES when the keyboard hides (the composer resizes),
     * so the tap always targets a freshly resolved node — the
     * AgentSettingsE2eTest lesson (CI 227ebc3: a tap on pre-shift bounds
     * landed on nothing and the "send" never happened).
     */
    private fun sendComposer(text: String): Boolean {
        if (!typeIntoComposer(text)) return false
        hideImeIfNeeded()
        device.waitForIdle(1_000)
        try {
            Thread.sleep(400)
        } catch (_: InterruptedException) {
            }
        return clickDesc("agent_send", 5_000)
    }

    /**
     * Types [text] into the agent composer (tag `agent_composer_field`) and
     * verifies the field actually holds it: Compose renders the value on a
     * text node distinct from the one carrying the desc, so the probe is a
     * bare TEXT match (the AgentSettingsE2eTest lesson). Spaces are spelled
     * `%s`, the input command's own escape.
     */
    private fun typeIntoComposer(text: String): Boolean {
        for (round in 1..3) {
            hideImeIfNeeded()
            val field = device.wait(Until.findObject(By.desc("agent_composer_field")), 4_000)
                ?: continue
            clickSmart(field)
            device.executeShellCommand("input keyevent KEYCODE_MOVE_END")
            device.clearFocusedField()
            device.waitForIdle(400)
            device.executeShellCommand("input text " + text.replace(" ", "%s"))
            device.waitForIdle(800)
            if (device.wait(Until.hasObject(By.textContains(text)), 1_500)) return true
        }
        return false
    }

    /** The IME is a separate accessibility window that shadows node lookups. */
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

    /** The address pill always carries this desc, so it detects the engine. */
    private fun engineUiUp(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.findObjects(By.descContains("Address bar")).isNotEmpty()) return true
            if (hasText("Search or type URL", 400)) return true
            if (hasText("Privacy Dashboard", 400)) return true
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                }
        }
        return device.findObjects(By.descContains("Address bar")).isNotEmpty()
    }

    /**
     * The engine surface must stay up CONTINUOUSLY for [stableMs]: the
     * ':browser' process can self-restart while rebinding to a fresh profile,
     * and interacting with the doomed surface races that restart.
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
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                }
        }
        return false
    }

    /**
     * Profile + engine, first-run or returning — the same start state the
     * other agent suites reach (OPEN an existing profile, or create the
     * single first-run one), then a STABLE engine surface.
     */
    private fun bootstrapEngine() {
        device.pressHome()
        launchFromHome()
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
            device.executeShellCommand("input text E2e_Airplane")
            device.waitForIdle(1_000)
            val cancel = device.findObjects(By.text("Cancel")).minByOrNull { it.visibleBounds.top }
            val confirm = device.findObjects(By.text("Create Profile"))
                .filter { c ->
                    cancel != null && kotlin.math.abs(
                        c.visibleBounds.centerY() - cancel!!.visibleBounds.centerY()
                    ) < 200
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

    private fun panelUp(timeout: Long): Boolean =
        hasText("Configure providers", 2_000)
            || hasText("No AI provider configured", 1_000)
            || hasText("Room Agent", 1_000)
            || hasDesc("Agent settings", 1_000)
            || hasDesc("agent_model", 1_000)

    /** SLOW drag (100 steps, no fling) that scrolls ~half the screen. */
    private fun dragUpQuarter() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 100
        )
        device.waitForIdle(800)
        try {
            Thread.sleep(300)
        } catch (_: InterruptedException) {
            }
    }

    /** Every visible text, for the failure message (readable from the
     *  e2e-reports artifact). */
    private fun uiTree(): String = try {
        val sb = StringBuilder()
        device.findObjects(By.textContains(""))
            .take(60)
            .forEach { node ->
                runCatching { sb.append("text: ").append(node.text).append('\n') }
            }
        sb.append("package: ").append(device.currentPackageName).append('\n')
        sb.toString()
    } catch (_: Exception) {
        "(ui tree unreadable)"
    }
}
