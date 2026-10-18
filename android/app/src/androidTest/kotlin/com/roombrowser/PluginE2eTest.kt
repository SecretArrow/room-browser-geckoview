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
import java.io.File

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
 * The second flow (word_count_asks_this_chats_own_ai_to_compose) drives the
 * agent.generate extension end-to-end: word-count installs from the APK
 * through the repository, its consent dialog carries the new permission's
 * sentence, and /compose's ask crosses the bridge as a reverse request and
 * comes back as the dead-endpoint refusal — an ANSWER, proven by the absence
 * of the reverse timeout's wording.
 *
 * The third flow (zagents_counter_stages_on_a_fresh_chat_and_counts_per_chat)
 * drives the ZAgents module path: the counter module stages on a fresh chat's
 * consent, runs with the chat's FIRST message — its message lands before the
 * assistant reply — counts 2 on re-activation, and starts from 1 in a new
 * chat. The fourth (input_greeter_…) drives the input-form gates: an invalid
 * field keeps the accept unreachable, a valid set runs with the secret
 * masked, edit-inputs changes the stored answers, and a cancelled form
 * leaves the plugin not run.
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

    /**
     * The agent.generate surface, on a real device with the real bridge: the
     * word-count example (shipped beside hello-world in the APK, installed
     * here through the same repository the SAF button feeds) declares the
     * fourth permission, so its consent dialog carries the new sentence; and
     * its /compose command asks THIS chat's own AI for one sentence.
     *
     * The chat's provider is the seeded dead port, so the generation is
     * REFUSED within milliseconds — which is exactly what makes this
     * deterministic without a live model: the ask leaves the plugin, crosses
     * the bridge as a reverse request, is answered by the app's read loop
     * (the gateway's own refusal, not the runtime's 20s reverse timeout), and
     * comes back to the plugin as the failure card. A compose card title plus
     * the absence of the timeout wording is the round trip's whole proof.
     */
    @Test
    fun word_count_asks_this_chats_own_ai_to_compose() {
        // ---- 1. Cold start → engine → agent panel --------------------------
        bootstrapEngine()
        if (!openAgentPanelFromMenu()) {
            throw AssertionError("Agent panel must open from the page menu; UI:\n" + uiTree())
        }

        // ---- 2. Install word-count through the real repository -------------
        // The manager's bundled button installs hello-world specifically;
        // word-count ships beside it in the APK and installs through the same
        // stream path that button feeds.
        runBlocking {
            val graph = (targetContext.applicationContext as RoomBrowserApp).graph
            targetContext.assets.open("node/plugins/word-count.zip").use { stream ->
                graph.pluginRepo.install(stream)
            }
        }

        // ---- 3. Enable through the sheet, with the new consent sentence ----
        assertTrue("The /plugins command must reach the composer and send", sendComposer("/plugins"))
        assertTrue("The chat-plugins sheet must open", hasDesc("plugins_sheet", 10_000))
        assertTrue(
            "The word-count switch must be in the sheet",
            clickDesc(wordCountSwitch, 10_000)
        )
        assertTrue(
            "The permission dialog must appear (word-count declares four permissions)",
            hasDesc("plugin_permission_accept", 10_000)
        )
        assertTrue(
            "The agent.generate consent sentence must be in the dialog",
            // Contains, not equals: the dialog renders each label with its
            // own "• " bullet prefix.
            hasTextContains("Have this chat's AI write text for it", 5_000)
        )
        assertTrue(
            "Granting must be clickable",
            clickDesc("plugin_permission_accept", 5_000)
        )
        assertTrue(
            "The sheet must step out of the way once the grant is given",
            waitGoneDesc("plugins_sheet", 10_000)
        )
        // Probe + activate in the embedded Node runtime; the first boot on
        // this runner is the slow path.
        assertTrue(
            "The activation card must land in the conversation",
            hasDesc(wordCountCard, 60_000)
        )

        // ---- 4. "/compose" rides the reverse channel to this chat's own AI -
        assertTrue(
            "The /compose command must reach the composer and send",
            sendComposer("/compose hello room browser world")
        )
        assertTrue(
            "The composition card must answer the command",
            hasTextContains("The chat's AI could not compose", 60_000)
        )
        assertTrue(
            "The refusal must be an ANSWER from the app's read loop, not the 20s reverse timeout",
            !hasTextContains("did not answer the generation request", 2_000)
        )
    }

    /**
     * The ZAgents module path, on a real device with the real runtime: the
     * bundled counter module installs from the APK through the repository
     * (the manager's bundled action installs hello-world specifically; this
     * rides the same stream path that button feeds, the word-count
     * precedent), a FRESH chat's picker stages it on consent ("Allow and
     * Run" — grants are per chat, so every chat re-consents), and the chat's
     * FIRST message runs the entry with that message as its prompt — the
     * module's message lands BEFORE the assistant reply (the dead-endpoint
     * provider answers the turn after it, so the order is asserted by
     * bounds). Re-activation counts 2 in the same chat; a new chat starts
     * from 1 again — per-chat storage on device, the isolation the whole
     * system is built on.
     */
    @Test
    fun zagents_counter_stages_on_a_fresh_chat_and_counts_per_chat() {
        // ---- 1. Cold start → engine → agent panel --------------------------
        bootstrapEngine()
        if (!openAgentPanelFromMenu()) {
            throw AssertionError("Agent panel must open from the page menu; UI:\n" + uiTree())
        }

        // ---- 2. Install the bundled ZAgents counter through the repo -----
        // The manager's bundled button installs hello-world specifically; the
        // counter ships beside it in the APK and installs through the same
        // stream path that button feeds (the word-count flow's precedent).
        runBlocking {
            val graph = (targetContext.applicationContext as RoomBrowserApp).graph
            targetContext.assets.open("node/plugins/zagents-counter.zip").use { stream ->
                graph.pluginRepo.install(stream)
            }
        }

        // ---- 3. A fresh chat opens the picker by itself -------------------
        assertTrue("The new-chat button must be clickable", clickDesc("New agent chat", 5_000))
        assertTrue("A fresh chat must open the plugin picker", hasDesc("plugins_sheet", 10_000))

        // ---- 4. The module's consent: Allow and Run -----------------------
        assertTrue("The counter's switch must be in the sheet", clickDesc(counterSwitch, 10_000))
        assertTrue(
            "The module-format consent must appear",
            hasDesc("plugin_permission_accept", 10_000)
        )
        assertTrue(
            "The module-format title must spell the Allow wording",
            hasText("Allow \"Hello Counter\" to:", 5_000)
        )
        assertTrue(
            "The storage bullet must be in the dialog",
            hasTextContains("Store data for this chat", 5_000)
        )
        assertTrue(
            "The module-format accept button is Allow and Run",
            hasText("Allow and Run", 5_000)
        )
        assertTrue("Granting must be clickable", clickDesc("plugin_permission_accept", 5_000))
        assertTrue(
            "The sheet must step out of the way once the grant is given",
            waitGoneDesc("plugins_sheet", 10_000)
        )
        assertTrue(
            "The enable must STAGE on this fresh chat — the notice says so",
            hasTextContains("runs with this chat's first message", 10_000)
        )

        // ---- 5. The first message runs the module BEFORE the model --------
        assertTrue("The hello message must reach the composer and send", sendComposer("hello"))
        if (!moduleMessageAppears("Activations in THIS chat: 1", 120_000)) {
            // Which of the states the first message left behind — a failed
            // start-up names the exact first line it printed (the node run's
            // own words), and the staging/turn notices are checked one by one
            // so the next round fixes the actual path, not a disjunction.
            // The kv.json beside them is the module's own ground truth: it
            // separates "ran and the transcript missed it" from "never ran".
            val startup = device.findObjects(By.textContains("start-up failed"))
                .map { it.text }.firstOrNull()
            val notRun = device.findObjects(By.textContains("was not run"))
                .map { it.text }.firstOrNull()
            val agentError = device.findObjects(By.textContains("Agent error"))
                .map { it.text }.firstOrNull()
            throw AssertionError(
                "The module message must land with the chat's first message (count 1). " +
                    "startup=" + startup + " | notRun=" + notRun +
                    " | agentError=" + agentError + " | " + counterStateDiagnostics() +
                    " | cards=" + cardSubtreeTexts() + " | tree=" + uiTree()
            )
        }
        // The entry's layout position, captured ATOMICALLY with the find —
        // while it is the newest row, because appended entries never move it
        // and the captured top is the ordering witness. The dead endpoint's
        // reply can land a recomposition after the wait and re-pin the
        // transcript onto the newer rows, culling the card's clipped body out
        // of the semantics tree before a separate re-find gets its turn (the
        // gecko emulator's race); the number survives the cull, so the
        // ordering witness still has it.
        val moduleTop = rockingTop("Activations in THIS chat: 1", 30_000)
            ?: throw AssertionError(
                "The module message must hold still for its bounds capture (count 1). " +
                    counterStateDiagnostics() + " | cards=" + cardSubtreeTexts() +
                    " | tree=" + uiTree()
            )
        // The reply itself is polled END-ANCHORED, not card-anchored: the
        // rows a turn appends last are pinned at the transcript's end by the
        // panel's own auto-scroll the moment they land, so a plain poll sees
        // them — but the module capture's rock can leave the list scrolled
        // away from that end, and with nothing new landing after the turn
        // dies the auto-scroll never re-fires. The card-anchored rock hunts a
        // clipped body AROUND the card and never reaches a row further below
        // (the WebView round's one failure: the wait oscillated ±120px while
        // the error row sat culled past it), so this wait's only scroll is
        // toward the END — one swipe walks the list back until the newest
        // rows compose again, and the list clamps there, idempotent.
        val assistantTop = endAnchoredTop("Agent error", 90_000)
            ?: throw AssertionError(
                "The assistant reply must fail against the dead endpoint. " +
                    counterStateDiagnostics() + " | tree=" + uiTree()
            )
        assertTrue(
            "The module message must sit above the assistant reply (user → module → assistant). " +
                "moduleTop=" + moduleTop + " assistantTop=" + assistantTop +
                " | " + counterStateDiagnostics(),
            assistantTop > moduleTop
        )

        // ---- 6. Re-activation counts again in the same chat ---------------
        assertTrue("The /plugins command must reach the composer and send", sendComposer("/plugins"))
        assertTrue("The chat-plugins sheet must open", hasDesc("plugins_sheet", 10_000))
        assertTrue("The counter's switch must toggle off", clickDesc(counterSwitch, 10_000))
        assertTrue("The sheet must close for the off notice", dismissPluginsSheet())
        assertTrue(
            "The off state must be visible before the re-enable",
            hasTextContains("Hello Counter is off for this chat", 10_000)
        )
        assertTrue("The /plugins command must reach the composer and send again", sendComposer("/plugins"))
        assertTrue("The chat-plugins sheet must open again", hasDesc("plugins_sheet", 10_000))
        assertTrue(
            "The counter's switch must toggle back on (no consent — the grants travel with the disable)",
            clickDesc(counterSwitch, 10_000)
        )
        assertTrue("The sheet must close for the re-run's card", dismissPluginsSheet())
        // Which path the re-enable took, before the count is asked about: the
        // immediate run says "is on", a failed start-up says so, and a wrong
        // staging says "first message". The failure message carries the tree
        // so the next round knows exactly which of the three it was.
        assertTrue(
            "The re-enable must speak: on-for-this-chat, start-up failed, or staged. " +
                "UI:\n" + uiTree(),
            hasTextContains("Hello Counter is on for this chat", 30_000) ||
                hasTextContains("Hello Counter is on, but its start-up failed", 30_000) ||
                hasTextContains("Hello Counter is on for this chat. It runs with this chat's first message", 30_000)
        )
        assertTrue(
            "The re-activation must count 2 in the same chat. " + counterStateDiagnostics() +
                " | cards=" + cardSubtreeTexts() + " | tree=" + uiTree(),
            moduleMessageAppears("Activations in THIS chat: 2", 120_000)
        )

        // ---- 7. A new chat starts from 1 again ----------------------------
        assertTrue("The new-chat button must be clickable", clickDesc("New agent chat", 5_000))
        assertTrue("The fresh chat must open the picker again", hasDesc("plugins_sheet", 10_000))
        assertTrue(
            "The fresh chat's transcript must not carry the old chat's count",
            waitUntil(5_000) {
                device.findObjects(By.textContains("Activations in THIS chat: 2")).isEmpty()
            }
        )
        assertTrue(
            "The counter's switch must be in the fresh chat's sheet",
            clickDesc(counterSwitch, 10_000)
        )
        assertTrue(
            "The fresh chat must raise consent again (grants are per chat)",
            hasDesc("plugin_permission_accept", 10_000)
        )
        assertTrue("Granting must be clickable", clickDesc("plugin_permission_accept", 5_000))
        assertTrue(
            "The sheet must step out of the way once the grant is given",
            waitGoneDesc("plugins_sheet", 10_000)
        )
        assertTrue("The hello again message must reach the composer and send", sendComposer("hello again"))
        assertTrue(
            "A new chat's module message must start from 1 (per-chat storage)",
            moduleMessageAppears("Activations in THIS chat: 1", 60_000)
        )
    }

    /**
     * The input-form flow, on a real device with the real runtime: the bundled
     * input-greeter example (a plugin.json manifest declaring three REQUIRED
     * inputs — a pattern-validated email, a cadence choice, a write-only
     * token) installs from the APK through the repository, and its enable
     * raises the form gate: an INVALID email keeps the accept unreachable
     * (the tap lands and changes nothing — invalid input never continues
     * execution), a VALID set unlocks "Allow and Run", and the activation
     * card echoes exactly what was validated — with the token masked, never
     * rendered. The sheet's edit-inputs pencil then changes the stored
     * answers (the blank secret keeps the saved token), and the re-run
     * reflects the edit. A cancelled form leaves the plugin enabled but
     * dormant — the reachable missing-inputs refusal.
     *
     * The agent TOOL path's own missing-inputs refusal ("the user did not
     * complete the form for this plugin — ask them to fill it via
     * /plugins") needs a live model to issue the tool call; this suite's
     * dead-endpoint provider never issues one, so that exact sentence is
     * JVM-pinned by PluginToolExecutorTest ("a cancelled form refuses the
     * tool call in the instructive words") — the suite's standing honesty
     * convention for model-dependent paths.
     */
    @Test
    fun input_greeter_validates_before_running_and_masks_the_secret() {
        // ---- 1. Cold start → engine → agent panel --------------------------
        bootstrapEngine()
        if (!openAgentPanelFromMenu()) {
            throw AssertionError("Agent panel must open from the page menu; UI:\n" + uiTree())
        }

        // ---- 2. Install the input-form example through the repository -----
        runBlocking {
            val graph = (targetContext.applicationContext as RoomBrowserApp).graph
            targetContext.assets.open("node/plugins/input-greeter.zip").use { stream ->
                graph.pluginRepo.install(stream)
            }
        }

        // ---- 3. A fresh chat's picker; the switch raises the form ---------
        assertTrue("The new-chat button must be clickable", clickDesc("New agent chat", 5_000))
        assertTrue("A fresh chat must open the plugin picker", hasDesc("plugins_sheet", 10_000))
        assertTrue("The greeter's switch must be in the sheet", clickDesc(inputGreeterSwitch, 10_000))
        assertTrue(
            "The activation gate must raise the form (the manifest declares required inputs)",
            hasDesc("plugin_form_accept", 15_000)
        )
        assertTrue(
            "The form's title names the plugin that is waiting",
            hasText("\"Input Greeter\" is waiting for its inputs", 5_000)
        )

        // ---- 4. An invalid email keeps the accept unreachable -------------
        assertTrue(
            "The invalid email must reach the field",
            typeIntoDesc("plugin_form_field_email", "not-an-email")
        )
        assertTrue(
            "The invalid email must show its inline error",
            hasTextContains("Enter a valid email", 5_000)
        )
        assertTrue(
            "The accept's tap must land (a disabled button ignores it)",
            clickDesc("plugin_form_accept", 5_000)
        )
        try {
            Thread.sleep(800)
        } catch (_: InterruptedException) {
        }
        assertTrue(
            "An invalid form must never retire — the disabled tap changed nothing",
            hasDesc("plugin_form_accept", 2_000)
        )

        // ---- 5. A valid set unlocks Allow and Run -------------------------
        assertTrue(
            "The valid email must reach the field",
            typeIntoDesc("plugin_form_field_email", "room@example.com")
        )
        assertTrue(
            "A valid email must clear the inline error",
            waitUntil(5_000) { device.findObjects(By.textContains("Enter a valid email")).isEmpty() }
        )
        assertTrue(
            "The cadence field must open its options",
            clickDesc("plugin_form_field_choice", 5_000)
        )
        assertTrue("The Weekly option must be clickable", clickText("Weekly", 5_000))
        // A secret field never echoes what it holds, so the typing is not
        // verified by text — the mask on the card below is the proof it ran,
        // and a missing value would have kept the accept disabled.
        assertTrue(
            "The token must reach the secret field",
            typeIntoDesc("plugin_form_field_secret", "tok-1234", verify = false)
        )
        assertTrue("The accept must be clickable", clickDesc("plugin_form_accept", 5_000))
        assertTrue("The accepted form must retire", waitGoneDesc("plugin_form_accept", 10_000))
        // The sheet the switch was flipped in is still up; the conversation
        // it covered holds the card — Back reveals it (the consent dialog's
        // own accept dismisses the sheet; the form gate's does not).
        assertTrue("The sheet must close for the activation card", dismissPluginsSheet())
        assertTrue(
            "The activation card must land in the conversation",
            hasDesc(inputGreeterCard, 60_000)
        )
        assertTrue("The card must echo the validated email", hasTextContains("room@example.com", 10_000))
        assertTrue("The card must echo the chosen cadence", hasTextContains("weekly", 10_000))
        assertTrue("The card must mask the secret", hasTextContains("••••", 10_000))
        assertTrue(
            "The raw secret must never render",
            device.findObjects(By.textContains("tok-1234")).isEmpty()
        )

        // ---- 6. Edit-inputs changes the stored answers --------------------
        assertTrue("The /plugins command must reach the composer and send", sendComposer("/plugins"))
        assertTrue("The chat-plugins sheet must open", hasDesc("plugins_sheet", 10_000))
        assertTrue(
            "The greeter's row must carry the edit pencil (its manifest declares a form)",
            clickDesc(inputGreeterEdit, 10_000)
        )
        assertTrue(
            "The edit form must open with its own title",
            hasDesc("plugin_form_accept", 10_000) && hasTextContains("Edit inputs for", 5_000)
        )
        assertTrue("The edit's accept is Save and run", hasText("Save and run", 5_000))
        assertTrue(
            "A stored secret prefills blank with the keep hint",
            hasTextContains("leave blank to keep the saved value", 5_000)
        )
        assertTrue(
            "The stored email must prefill the editor",
            hasTextContains("room@example.com", 5_000)
        )
        // Change only the cadence; the blank secret keeps the stored token.
        assertTrue(
            "The cadence field must open its options",
            clickDesc("plugin_form_field_choice", 5_000)
        )
        assertTrue("The Monthly option must be clickable", clickText("Monthly", 5_000))
        assertTrue("The edit must be submittable", clickDesc("plugin_form_accept", 5_000))
        assertTrue("The edit dialog must retire", waitGoneDesc("plugin_form_accept", 10_000))
        assertTrue("The sheet must close for the save notice", dismissPluginsSheet())
        assertTrue(
            "A native edit saves its answers",
            hasTextContains("Input Greeter's inputs were saved", 10_000)
        )

        // ---- 7. The re-run reflects the edited answers --------------------
        // A native plugin's edit only saves; its re-run is the sheet's own
        // toggle — off and on, with no consent between (the grants travel
        // with the disable), the hello-world flow's own re-enable shape.
        assertTrue("The /plugins command must reach the composer and send", sendComposer("/plugins"))
        assertTrue("The chat-plugins sheet must open", hasDesc("plugins_sheet", 10_000))
        assertTrue("The greeter's switch must toggle off", clickDesc(inputGreeterSwitch, 10_000))
        assertTrue("The sheet must close for the off notice", dismissPluginsSheet())
        assertTrue(
            "The off state must be visible before the re-enable",
            hasTextContains("Input Greeter is off for this chat", 10_000)
        )
        assertTrue("The /plugins command must reach the composer and send again", sendComposer("/plugins"))
        assertTrue("The chat-plugins sheet must open again", hasDesc("plugins_sheet", 10_000))
        assertTrue("The greeter's switch must toggle back on", clickDesc(inputGreeterSwitch, 10_000))
        assertTrue("The sheet must close for the re-run's card", dismissPluginsSheet())
        assertTrue(
            "The re-run must echo the edited cadence",
            hasTextContains("monthly", 60_000)
        )
        assertTrue(
            "The re-run must keep the validated email",
            hasTextContains("room@example.com", 10_000)
        )
        assertTrue(
            "The kept secret must still be masked (blank kept the saved token)",
            hasTextContains("••••", 10_000)
        )

        // ---- 8. A cancelled form activates nothing ------------------------
        // The reachable missing-inputs refusal: a fresh chat's enable raises
        // the form, Cancel answers it, and the binding stays enabled with
        // its inputs unanswered — exactly the state the agent tool's gate
        // checks (see the KDoc for where that path's wording is pinned).
        assertTrue("The new-chat button must be clickable", clickDesc("New agent chat", 5_000))
        assertTrue("The fresh chat must open the picker", hasDesc("plugins_sheet", 10_000))
        assertTrue(
            "The greeter's switch must be in the fresh chat's sheet",
            clickDesc(inputGreeterSwitch, 10_000)
        )
        assertTrue(
            "The activation gate must raise the form again",
            hasDesc("plugin_form_accept", 15_000)
        )
        assertTrue("Cancelling must be clickable", clickText("Cancel", 5_000))
        assertTrue("The cancelled form must close", waitGoneDesc("plugin_form_accept", 10_000))
        assertTrue("The sheet must close for the refusal notice", dismissPluginsSheet())
        assertTrue(
            "A form the person did not complete must say the plugin was not run",
            hasTextContains("was not run — its input form was not completed", 10_000)
        )
    }

    /** The directory name the whole plugin system addresses hello-world by. */
    private val helloDirName = "com_example_hello"

    /** The same, for the word-count plugin the compose flow installs. */
    private val wordCountSwitch = "plugin_enable_com_example_wordcount"
    private val wordCountCard = "plugin_card_com_example_wordcount"

    /** The same, for the ZAgents counter module the staged flow installs. */
    private val counterSwitch = "plugin_enable_zagents_hello-counter"

    /** The same, for the input-form example the form flow installs. */
    private val inputGreeterSwitch = "plugin_enable_com_example_inputgreeter"
    private val inputGreeterCard = "plugin_card_com_example_inputgreeter"
    private val inputGreeterEdit = "plugin_edit_inputs_com_example_inputgreeter"

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

    /**
     * Types [text] into the field tagged [desc] — the composer helper's own
     * idiom (focus, clear, type, verify-by-text) for the form dialogs' field
     * rows. A secret field never echoes what it holds, so its typing is not
     * verifiable by text: pass [verify] false there and prove the value by
     * what its run produced instead.
     */
    private fun typeIntoDesc(desc: String, text: String, verify: Boolean = true): Boolean {
        for (round in 1..3) {
            hideImeIfNeeded()
            val field = device.wait(Until.findObject(By.desc(desc)), 4_000) ?: continue
            clickSmart(field)
            device.executeShellCommand("input keyevent KEYCODE_MOVE_END")
            device.clearFocusedField()
            device.waitForIdle(400)
            device.executeShellCommand("input text " + text.replace(" ", "%s"))
            device.waitForIdle(800)
            if (!verify) return true
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

    /**
     * Waits for a module message row the way a person finds one: the row IS
     * in the transcript, but the panel's transcript window is narrow — the
     * newest row pins at the bottom and the row before it can sit with its
     * body clipped above the window, and Compose culls clipped text out of
     * the semantics tree entirely, so no text poll will ever see it.
     *
     * The newest rows are where a module message LANDS, so the first stretch
     * is poll-only. After that the poll ROCKS the transcript a card-height
     * at a time, anchored on a plugin card's OWN bounds — the one node
     * guaranteed to sit inside the list, wherever the panel's chrome placed
     * it — and back again: one drag is enough to clear the window's whole
     * blind band (a taller drag would carry the card past the bottom edge
     * and lose the anchor with it), and the rock returns so the next entry a
     * later wait lands re-aligns onto a view it recognizes.
     */
    private fun moduleMessageAppears(needle: String, timeoutMs: Long): Boolean =
        rockingFind(needle, timeoutMs) != null

    /**
     * [moduleMessageAppears]'s wait, returning the row it found instead of a
     * Boolean. The caller reads the row's bounds off the returned object in
     * the same breath as the find, because a separate re-find races the
     * panel's auto-scroll: whatever row lands next re-pins the transcript,
     * and a clipped body is culled out of the semantics tree a recomposition
     * later. The gecko emulator lost exactly that race — the wait found the
     * row, the re-find a moment later did not. The returned object's bounds
     * snapshot survives the cull; the number is all the callers need.
     */
    private fun rockingFind(needle: String, timeoutMs: Long): UiObject2? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val settledAt = System.currentTimeMillis() + 10_000
        var towardBeginning = true
        while (true) {
            device.findObjects(By.textContains(needle)).firstOrNull()?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            if (System.currentTimeMillis() < settledAt) {
                device.waitForIdle(400)
                continue
            }
            val card = device.findObject(By.descContains("plugin_card"))
            if (card != null) {
                val bounds = card.visibleBounds
                val x = bounds.centerX()
                val fromY = bounds.centerY().coerceIn(16, device.displayHeight - 16)
                val toY = if (towardBeginning) {
                    (fromY + 120).coerceAtMost(device.displayHeight - 8)
                } else {
                    (fromY - 120).coerceAtLeast(8)
                }
                if (toY != fromY) device.swipe(x, fromY, x, toY, 60)
                towardBeginning = !towardBeginning
            } else {
                // No card composed: the list is scrolled somewhere a card is
                // not. The end is where a module message lives, so the
                // recovery rocks toward the END — the finger up, the content
                // with it — until the anchor card comes back into the window.
                device.swipe(
                    device.displayWidth / 2, device.displayHeight * 11 / 16,
                    device.displayWidth / 2, device.displayHeight * 7 / 16, 60
                )
            }
            device.waitForIdle(600)
        }
    }

    /**
     * A row's top edge, found and measured atomically: [rockingFind] hands
     * back the row and the top is read off it immediately, retrying the whole
     * find if the row decomposed between the two. A vanish between a wait and
     * a later capture costs one rock here, not the test.
     */
    private fun rockingTop(needle: String, timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val row = rockingFind(needle, deadline - System.currentTimeMillis())
            if (row == null) return null
            try {
                return row.visibleBounds.top
            } catch (_: IllegalStateException) {
                // The row decomposed between the find and the read — rock again.
            }
        }
        return null
    }

    /**
     * A NEWEST-row's top edge: polled plain, measured atomically, and when a
     * scroll is needed at all it only ever goes toward the END. The panel's
     * auto-scroll pins whatever row lands last at the transcript's end, so a
     * plain poll is the whole story — the end-ward swipe is only the recovery
     * for a list an earlier wait's rock left scrolled away from that end
     * (with nothing new landing, the auto-scroll keyed on entries.size never
     * re-fires). The list clamps at its end, so the swipe is idempotent and
     * can never overshoot. Returns null only on timeout.
     */
    private fun endAnchoredTop(needle: String, timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            device.findObjects(By.textContains(needle)).firstOrNull()?.let { row ->
                try {
                    return row.visibleBounds.top
                } catch (_: IllegalStateException) {
                    // Decomposed between the find and the read — poll again.
                }
            }
            if (System.currentTimeMillis() >= deadline) return null
            device.swipe(
                device.displayWidth / 2, device.displayHeight * 11 / 16,
                device.displayWidth / 2, device.displayHeight * 7 / 16, 60
            )
            device.waitForIdle(600)
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

    /**
     * Every text node in every plugin card's subtree, walked from the card
     * row's own desc — the body the card actually rendered, whatever the
     * composition path meant it to be. The via-footer alone proves the card
     * is on screen; only the walk can say what its body says.
     */
    private fun cardSubtreeTexts(): String = try {
        device.findObjects(By.descContains("plugin_card")).joinToString(" || ") { card ->
            fun walk(node: UiObject2, depth: Int): List<String> {
                val own = node.text?.let { listOf("\"" + it + "\"") } ?: emptyList()
                val below = if (depth < 5) node.children.flatMap { walk(it, depth + 1) } else emptyList()
                return own + below
            }
            "[" + (card.contentDescription ?: "?") + ": " + walk(card, 0).joinToString(" ~ ") + "]"
        }.ifEmpty { "(no plugin card node on screen)" }
    } catch (_: Exception) {
        "(card walk failed)"
    }

    /**
     * The counter's GROUND TRUTH, read off the disk the module itself wrote:
     * every session data root the app carries for this module, and the count
     * its kv.json reached, whatever the UI did with it. Paired with the
     * on-screen Hello Counter texts, this separates "the module ran and the
     * UI missed it" from "the module never ran".
     */
    private fun counterStateDiagnostics(): String = try {
        val dataRoot = File(targetContext.filesDir, "plugin-data")
        val sb = StringBuilder()
        val sessionDirs = dataRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        sessionDirs.forEach { sessionDir ->
            val kv = File(sessionDir, "zagents_hello-counter/kv.json")
            if (kv.isFile) {
                sb.append("session ").append(sessionDir.name)
                    .append(" count=").append(kv.readText().trim()).append("; ")
            }
        }
        if (sb.isEmpty()) sb.append("(no counter kv found)")
        sb.append("texts=").append(
            device.findObjects(By.textContains("Hello Counter")).mapNotNull { it.text }.take(6)
        )
        sb.toString()
    } catch (err: Exception) {
        "(diagnostics failed: ${err.message})"
    }
}
