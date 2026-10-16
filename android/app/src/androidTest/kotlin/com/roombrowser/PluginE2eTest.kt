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
import com.roombrowser.agent.ui.PluginManagerActivity
import com.roombrowser.data.db.AgentProviderEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the chat-plugin system, against the real app and the real embedded
 * Node runtime — nothing is mocked:
 *
 *   browser -> agent panel
 *     -> PluginManagerActivity: "Install the bundled example" (assets ZIP)
 *     -> "/plugins" opens the picker sheet
 *     -> the hello-world switch raises the grant dialog (it declares storage)
 *     -> "Grant & enable": the sheet closes, the ACTIVATION CARD lands
 *     -> "/greet Room": a second card, from the plugin's own command
 *     -> new chat: the plugin is NOT carried over — "/greet" falls through to
 *        the agent turn, which fails against a dead endpoint (the seeded
 *        provider is the only thing the chat needs to EXIST) and no plugin
 *        card appears; "/plugins" shows hello-world's switch off, and
 *        re-enabling asks for consent again
 *     -> uninstall in the manager: the installed list is empty again
 *
 * A provider ROW is seeded through the real repository before the app boots:
 * a chat with no session yet creates its row on the first plugin enable, and
 * that path refuses when no provider is configured — the one honest way to
 * exercise the picker on a brand-new chat. The endpoint is a dead port; no
 * turn ever needs to succeed here.
 *
 * Node-dependent assertions carry the generous timeouts this runner needs for
 * a first runtime boot (NodeRuntimeE2eTest's whole purpose); everything else
 * uses the shared suite's short, verified-tap idioms.
 *
 * The plugin state is cleared through the REAL repository before and after
 * the test: an installed plugin auto-opens the picker on every new chat, so a
 * leftover row would leak into every later suite's agent-panel flow.
 */
@RunWith(AndroidJUnit4::class)
class PluginE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private val helloRow = "plugin_manager_row_com_example_hello"
    private val helloSwitch = "plugin_enable_com_example_hello"
    private val helloCard = "plugin_card_com_example_hello"

    @Before
    fun setUp() {
        // Determinism: the runner's shared IP arms the organic network warning
        // on every fresh-profile boot — suppress it (see E2eDeterminism).
        E2eDeterminism.suppressOrganicNetworkWarnings()
        clearPluginState()
        seedProviderIfAbsent()
    }

    @After
    fun tearDown() {
        runCatching { clearPluginState() }
        device.pressHome()
    }

    /** Removes every installed plugin through the real repository path. */
    private fun clearPluginState() {
        runBlocking {
            val graph = (targetContext.applicationContext as RoomBrowserApp).graph
            graph.pluginRepo.installedOnce().forEach { graph.pluginRepo.uninstall(it.id) }
        }
    }

    /**
     * One inert provider row, only when none exists: enabling a plugin in a
     * chat that has no session yet creates that session through the
     * controller, and that path refuses when no provider is configured — so
     * the picker on a brand-new chat needs this to be exercisable at all. The
     * endpoint is deliberately dead (nothing listens on the discard port); a
     * suite that left a provider behind is just as good.
     */
    private fun seedProviderIfAbsent() {
        runBlocking {
            val graph = (targetContext.applicationContext as RoomBrowserApp).graph
            if (graph.agentRepo.providers().isEmpty()) {
                graph.database.agentDao().upsertProvider(
                    AgentProviderEntity(
                        name = "E2E plugins",
                        baseUrl = "http://127.0.0.1:9",
                        apiKeyEnc = "",
                        defaultModel = "e2e-plugin-model",
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    @Test
    fun bundled_plugin_installs_grants_answers_and_stays_per_chat() {
        // ---- 1. Cold start → engine → agent panel --------------------------
        bootstrapEngine()
        if (!openAgentPanelFromMenu()) {
            throw AssertionError("Agent panel must open from the page menu; UI:\n" + uiTree())
        }

        // ---- 2. Install the bundled example through the manager -----------
        // Launched directly: the manager is the install surface regardless of
        // which screen reached it, and driving SAF here would test Android's
        // file picker rather than the app.
        PluginManagerActivity.launch(targetContext)
        device.waitForIdle(1_500)
        assertTrue(
            "The manager's bundled-install action must be reachable",
            clickDesc("plugin_manager_install_bundled", 15_000)
        )
        assertTrue(
            "The bundled example must install and list itself",
            hasDesc(helloRow, 30_000)
        )
        // Leave the manager for the browser task the panel is part of.
        device.pressBack()
        device.waitForIdle(1_000)
        assertTrue("The agent panel must still be up", panelUp(6_000))

        // ---- 3. "/plugins" opens the picker sheet ---------------------------
        assertTrue("The /plugins command must reach the composer and send", sendComposer("/plugins"))
        assertTrue("The chat-plugins sheet must open", hasDesc("plugins_sheet", 10_000))

        // ---- 4. Enable hello-world with the grant dialog -------------------
        assertTrue(
            "The hello-world switch must be in the sheet",
            clickDesc(helloSwitch, 10_000)
        )
        assertTrue(
            "The permission dialog must appear (hello-world declares storage)",
            hasDesc("plugin_permission_accept", 10_000)
        )
        assertTrue(
            "Granting must be clickable",
            clickDesc("plugin_permission_accept", 5_000)
        )
        assertTrue(
            "The sheet must step out of the way once the grant is given",
            waitGoneDesc("plugins_sheet", 10_000)
        )
        // The enable runs probe + activate in the embedded Node runtime; the
        // first boot on this runner is the slow path.
        assertTrue(
            "The activation card must land in the conversation",
            hasDesc(helloCard, 60_000)
        )

        // ---- 5. "/greet Room" answers with the plugin's own card -----------
        assertTrue("The /greet command must reach the composer and send", sendComposer("/greet Room"))
        assertTrue(
            "The greeting card must answer the command",
            hasTextContains("Hello, Room!", 60_000)
        )

        // ---- 6. Isolation: a fresh chat starts without the plugin ----------
        assertTrue(
            "The new-chat button must be clickable",
            clickDesc("New agent chat", 5_000)
        )
        // A fresh chat opens the picker by itself while anything is installed.
        assertTrue(
            "A fresh chat must open the plugin picker",
            hasDesc("plugins_sheet", 10_000)
        )
        assertTrue("The sheet must close on back", dismissPluginsSheet())

        // "/greet" is nobody's command here: it falls through to a real agent
        // turn, which fails against the dead endpoint — so the transcript may
        // gain the failed turn, but NOTHING from a plugin. The window is long
        // enough that a wrongly-enabled plugin (a warm runtime by now) would
        // have had time to answer.
        assertTrue("The /greet command must reach the composer and send", sendComposer("/greet Room"))
        assertTrue(
            "No plugin card may appear in the fresh chat",
            waitUntil(8_000) {
                device.findObject(By.desc(helloCard)) == null &&
                    device.findObjects(By.textContains("Hello, Room!")).isEmpty()
            }
        )

        // "/plugins" lists hello-world for this chat too — switched OFF. The
        // proof is behavioral: tapping the OFF switch raises consent again,
        // while an ON switch would have switched OFF with no dialog at all.
        assertTrue("The /plugins command must reach the composer and send", sendComposer("/plugins"))
        assertTrue("The chat-plugins sheet must open again", hasDesc("plugins_sheet", 10_000))
        assertTrue(
            "hello-world's switch must be in the fresh chat's sheet",
            clickDesc(helloSwitch, 10_000)
        )
        assertTrue(
            "An OFF switch must raise the consent dialog again",
            hasDesc("plugin_permission_accept", 10_000)
        )
        assertTrue(
            "Cancelling must be clickable",
            clickText("Cancel", 5_000)
        )
        assertTrue(
            "The cancelled dialog must close",
            waitGoneDesc("plugin_permission_accept", 10_000)
        )
        assertTrue("The sheet must still be up after the cancel", dismissPluginsSheet())

        // ---- 7. Uninstall in the manager ------------------------------------
        PluginManagerActivity.launch(targetContext)
        device.waitForIdle(1_500)
        assertTrue(
            "The hello-world row must still be listed for uninstall",
            clickDesc("plugin_manager_uninstall_$helloDirName", 15_000)
        )
        assertTrue(
            "The remove confirmation must name the plugin",
            hasText("Remove plugin?", 5_000)
        )
        assertTrue(
            "Confirming the removal must be clickable",
            clickDesc("plugin_manager_confirm_remove", 5_000)
        )
        assertTrue(
            "The manager's installed list must be empty again",
            waitGoneDesc(helloRow, 10_000) && hasText("No plugins installed", 5_000)
        )
    }

    /** The directory name the whole plugin system addresses hello-world by. */
    private val helloDirName = "com_example_hello"

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

    private fun hasText(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs)

    private fun hasTextContains(part: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.textContains(part)), timeoutMs)

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

    private fun hasDescContains(part: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.descContains(part)), timeoutMs)

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

    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
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

    /** The address pill always carries this desc, so it detects the engine. */
    private fun engineUiUp(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasDescContains("Address bar", 400)) return true
            if (hasText("Search or type URL", 400)) return true
            if (hasText("Privacy Dashboard", 400)) return true
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                }
        }
        return hasDescContains("Address bar", 500)
    }

    /**
     * The engine surface must stay up CONTINUOUSLY for [stableMs]: the
     * ':browser' process can self-restart while rebinding to a fresh profile
     * (the warm-up suite deletes its profile), and interacting with the
     * doomed surface races that restart.
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
            device.executeShellCommand("input text E2e_Plugin")
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

    /**
     * Dismisses the plugin picker with Back and verifies BOTH halves of the
     * outcome: the sheet is gone AND the panel it belonged to is still the
     * surface on screen. A back that collapsed the panel instead is recovered
     * from, so a Compose back-handling change cannot cascade into every later
     * step failing for the wrong reason.
     */
    private fun dismissPluginsSheet(): Boolean {
        for (attempt in 1..3) {
            if (waitGoneDesc("plugins_sheet", 500)) break
            device.pressBack()
            device.waitForIdle(800)
        }
        if (!waitGoneDesc("plugins_sheet", 2_000)) return false
        if (panelUp(3_000)) return true
        return openAgentPanelFromMenu()
    }

    /** Every visible text and the tags this suite targets, for the failure
     *  message (readable from the e2e-reports artifact). */
    private fun uiTree(): String = try {
        val probes: List<Pair<String, BySelector>> = listOf(
            "pill(AI Agent desc)" to By.desc("AI Agent"),
            "plugins_sheet desc" to By.desc("plugins_sheet"),
            "plugin row desc" to By.descContains("plugin_"),
            "composer desc" to By.desc("agent_composer_field")
        )
        val sb = StringBuilder()
        probes.forEach { (name, selector) ->
            val nodes = device.findObjects(selector)
            sb.append(name).append(": ").append(nodes.size).append('\n')
        }
        device.findObjects(By.textContains(""))
            .take(60)
            .forEach { node ->
                runCatching { sb.append("text: ").append(node.text).append('\n') }
            }
        sb.toString()
    } catch (_: Exception) {
        "(ui tree unreadable)"
    }
}
