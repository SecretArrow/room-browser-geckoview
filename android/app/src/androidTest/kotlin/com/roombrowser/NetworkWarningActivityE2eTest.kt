package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.roombrowser.data.repo.PendingNetDecision
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the full-screen Profile Network Warning contract
 * (NetworkWarningActivity replaces the old IpWarningDialog):
 *
 *   - The warning is STATE, not a dialog: a pending decision persisted in
 *     app_state (`net_decision_pending`) gates the profile. Arming it and
 *     cold-starting the engine brings the warning up — no dialog, no bypass.
 *   - Exactly three decisions: Continue / Switch Profile / Don't Warn Again
 *     for This IP — asserted by their on-screen labels, plus the payload
 *     (ip, previous profile name) rendered from the Intent extras.
 *   - "Continue" (RESULT_CONTINUE): the engine is released — the browser
 *     surface returns and the persisted pending decision is GONE.
 *   - SYSTEM BACK (RESULT_CANCELED — NOT a decision): the gate still stands,
 *     so BrowserActivity immediately re-launches the warning. It can never
 *     be dismissed with Back.
 *   - "Don't Warn Again for This IP" (RESULT_SUPPRESS): releases + clears.
 *   - "Switch Profile" (RESULT_SWITCH): releases and re-opens the quick
 *     switcher on the engine screen.
 *   - "Refresh IP" re-reads the address and reports whether it still matches
 *     the warned one; it is never a decision, so the gate stands after it.
 *
 * The test arms the gate directly through the app's own AppStateRepository
 * (same payload armNetworkWarning writes), then relaunches the real engine
 * activity — everything from there is the production state machine.
 */
@RunWith(AndroidJUnit4::class)
class NetworkWarningActivityE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private val tag = (System.currentTimeMillis() % 100000).toString()
    private val profileName = "E2ENet$tag"
    private val warningTitle = "Profile Network Warning"
    private val ip = "203.0.113.7"
    private val previousProfileName = "Work"

    private val appGraph: com.roombrowser.di.AppGraph
        get() = (targetContext.applicationContext as com.roombrowser.RoomBrowserApp).graph

    @After
    fun tearDown() {
        // Never leave a gated profile behind for the next test class.
        runBlocking { appGraph.appState.clearPendingNetDecision() }
    }

    // ---------- UiAutomator helpers (proven patterns) --------------------

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

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return condition()
    }

    private fun clickCenter(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        device.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}")
        device.waitForIdle(1_000)
        true
    } catch (_: Exception) {
        false
    }

    private fun clickSmart(node: UiObject2): Boolean {
        var current: UiObject2? = node
        var hops = 0
        while (current != null && hops < 8) {
            val clickable = try { current.isClickable } catch (_: Exception) { false }
            if (clickable) {
                try {
                    current.click()
                    device.waitForIdle(1_000)
                    return true
                } catch (_: Exception) {
                }
            }
            current = try { current.parent } catch (_: Exception) { null }
            hops++
        }
        return clickCenter(node)
    }

    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    /** A decision can sit below the fold on the CI emulator's short screen. */
    private fun clickTextScrolled(text: String, attempts: Int = 8): Boolean {
        for (i in 1..attempts) {
            if (clickText(text, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    private fun dragUpQuarter() {
        // Half-screen drag (3/4 → 1/4): the CI emulator's default profile is
        // 320x640 mdpi — deep content lives far below the fold there; the
        // old quarter-screen drag (160 px) could not reach it.
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 100
        )
        device.waitForIdle(600)
    }

    /** Scroll-aware presence check (off-screen sheet rows are not in the
     *  a11y tree — same lesson as the other e2e suites). */
    private fun hasTextWithScroll(text: String, attempts: Int = 24): Boolean {
        for (i in 1..attempts) {
            if (hasText(text, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    private fun engineUiUp(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasDesc("Address bar", 400)) return true
            if (hasText("Search or type URL", 400)) return true
            if (hasText("Privacy Dashboard", 400)) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return hasDesc("Address bar", 500)
    }

    private fun uiTree(): String = try {
        val texts = runCatching {
            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(60)
        }.getOrDefault(emptyList())
        "TEXTS: $texts"
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }

    // ---------- Bootstrap: fresh profile so the gate state is ours --------

    private fun bootstrapFreshEngine(): Boolean {
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)
        assertTrue(
            "Profile list or first-run state must appear",
            hasText("Your profiles", 90_000) || hasText("Create Profile", 90_000)
        )
        assertTrue("Create Profile affordance must be reachable", clickScrollAwareCreate())
        assertTrue("Create-profile dialog should open", hasText("Cancel", 8_000))
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8_000)
        assertTrue("Name text field must be visible", field != null)
        clickCenter(field!!)
        device.executeShellCommand("input text $profileName")
        device.waitForIdle(1_000)
        val cancel = device.findObjects(By.text("Cancel")).minByOrNull { it.visibleBounds.top }
        val confirm = device.findObjects(By.text("Create Profile"))
            .filter { c ->
                cancel != null && kotlin.math.abs(
                    c.visibleBounds.centerY() - cancel.visibleBounds.centerY()
                ) < 200
            }
            .maxByOrNull { it.visibleBounds.centerX() }
        assertTrue("Dialog confirm button must be found", confirm != null)
        clickCenter(confirm!!)
        assertTrue("Create dialog should close after confirm", waitGone("Cancel", 10_000))
        if (!engineUiUp(40_000)) return false
        // Defensive heal: if the runner's network organically armed the
        // warning for this fresh profile, clear it so the test's OWN armed
        // state is the only variable (idempotent when no warning shows).
        if (hasText(warningTitle, 3_000)) {
            clickTextScrolled("Continue", attempts = 4)
            return engineUiUp(15_000)
        }
        return true
    }

    private fun waitGone(text: String, timeoutMs: Long): Boolean =
        waitUntil(timeoutMs) { device.findObjects(By.text(text)).isEmpty() }

    /** The create affordance sits below existing profile cards — scroll to it. */
    private fun clickScrollAwareCreate(): Boolean {
        for (i in 1..24) {
            val node = device.wait(Until.findObject(By.text("Create Profile")), 1_500)
            if (node != null && clickSmart(node)) return true
            device.swipe(
                device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 4, 100
            )
            device.waitForIdle(600)
        }
        return false
    }

    // ---------- The gate plumbing ------------------------------------------

    private fun activeProfileId(): String = runBlocking {
        appGraph.appState.activeProfileIdSnapshot()
            ?: error("engine must have persisted the active profile id")
    }

    /** The exact payload shape BrowserViewModel.armNetworkWarning writes. */
    private fun armPendingDecision(profileId: String) = runBlocking {
        val global = appGraph.appState.globalSettingsSnapshot()
        // The warning body renders the previous profile's NAME only when the
        // user allows it — pin the default so the assertion is deterministic
        // regardless of what earlier tests left in global settings.
        if (!global.showPreviousProfileName) {
            appGraph.appState.saveGlobalSettings(global.copy(showPreviousProfileName = true))
        }
        appGraph.appState.setPendingNetDecision(
            PendingNetDecision(
                profileId = profileId,
                ip = ip,
                previousProfileName = previousProfileName,
                lastSeenAt = 0L
            )
        )
    }

    private fun pendingDecisionOrNull(): PendingNetDecision? = runBlocking {
        appGraph.appState.pendingNetDecision()
    }

    /** Cold-start the engine on the persisted active profile (no extras). */
    private fun relaunchEngine() {
        targetContext.startActivity(
            Intent()
                .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    // ---------- The contract ------------------------------------------------

    @Test
    fun network_warning_decisions_continue_back_and_switch() {
        // Determinism: the runner's shared IP arms the organic network
        // warning on fresh-profile boots — suppress it (E2eDeterminism).
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("Engine must come up on a fresh profile", bootstrapFreshEngine())
        val profileId = activeProfileId()

        // ================= Cycle 1: Continue ==============================
        armPendingDecision(profileId)
        relaunchEngine()
        assertTrue(
            "The warning activity must come up over the gated engine\n${uiTree()}",
            hasText(warningTitle, 20_000)
        )
        assertTrue("The warning must show the current IP from the extras", hasText("Current IP: $ip", 5_000))
        assertTrue(
            "The warning must name the previous profile from the extras",
            hasTextContains("previously associated", 5_000) &&
                hasTextContains(previousProfileName, 2_000)
        )
        assertTrue("All three decisions must be offered", hasText("Continue", 2_000))
        assertTrue("'Switch Profile' must be offered", hasText("Switch Profile", 2_000))
        assertTrue("'Don't Warn Again for This IP' must be offered", hasText("Don't Warn Again for This IP", 2_000))

        assertTrue("'Continue' must be clickable", clickTextScrolled("Continue"))
        assertTrue(
            "Continue must release the engine back to the browser surface",
            engineUiUp(15_000)
        )
        assertTrue(
            "Continue must clear the persisted pending decision",
            waitUntil(6_000) { pendingDecisionOrNull() == null }
        )

        // ================= Cycle 2: system Back is NOT a decision =========
        armPendingDecision(profileId)
        relaunchEngine()
        assertTrue("The warning must come up again", hasText(warningTitle, 20_000))
        device.waitForIdle(1_000)
        device.pressBack()
        device.waitForIdle(1_000)
        assertTrue(
            "Back must NOT release the gate — the warning must come straight back\n${uiTree()}",
            hasText(warningTitle, 12_000)
        )
        assertTrue("The pending decision must still be persisted after Back", pendingDecisionOrNull() != null)

        // Resolve cycle 2 through "Don't Warn Again for This IP".
        assertTrue("'Don't Warn Again for This IP' must be clickable", clickTextScrolled("Don't Warn Again for This IP"))
        assertTrue("Suppress must release the engine", engineUiUp(15_000))
        assertTrue(
            "Suppress must clear the persisted pending decision",
            waitUntil(6_000) { pendingDecisionOrNull() == null }
        )

        // ================= Cycle 3: Switch Profile ========================
        armPendingDecision(profileId)
        relaunchEngine()
        assertTrue("The warning must come up (third cycle)", hasText(warningTitle, 20_000))
        assertTrue("'Switch Profile' must be clickable", clickTextScrolled("Switch Profile"))
        // The sheet's copy line sits at its top — check it BEFORE scrolling.
        // Polled rather than sampled once: this handoff is an engine release, an
        // activity launch and a sheet animation on top of two cold starts, so a
        // single 8s sample reports "the sheet is not open" when the honest
        // question is "has it opened yet". The tree dump is for the other case.
        assertTrue(
            "The quick switcher sheet must be open\n${uiTree()}",
            waitUntil(30_000) { hasTextContains("Switching closes", 400) }
        )
        assertTrue(
            "Switch Profile must release the engine and re-open the quick switcher\n${uiTree()}",
            hasTextWithScroll("Create New Profile")
        )
        assertTrue(
            "Switch must clear the persisted pending decision",
            waitUntil(6_000) { pendingDecisionOrNull() == null }
        )

        // Leave the engine ungated for whichever test runs next.
        device.pressBack()
        device.waitForIdle(1_000)
        runBlocking { appGraph.appState.clearPendingNetDecision() }
    }

    /**
     * "Refresh IP" re-reads the address and says whether it still matches the
     * warned one; it is NOT a fourth decision.
     *
     * What this can pin on the emulator is the contract, not the number: the
     * probe goes to the internet (or fails), so the assertion is that a
     * refresh always ENDS in a stated outcome — a reading, or an honest
     * failure — and that the gate is untouched either way. A green address is
     * the reading branch, and the emulator's own address can never be the
     * payload's TEST-NET-3 address, so that branch is always "changed".
     */
    @Test
    fun network_warning_refresh_reports_the_address_without_deciding() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("Engine must come up on a fresh profile", bootstrapFreshEngine())
        val profileId = activeProfileId()

        armPendingDecision(profileId)
        relaunchEngine()
        assertTrue(
            "The warning must come up over the gated engine\n${uiTree()}",
            hasText(warningTitle, 20_000)
        )
        assertTrue("The warned address must be shown", hasText("Current IP: $ip", 5_000))
        assertTrue("The refresh action must be offered", hasDesc("net_warning_refresh", 5_000))

        assertTrue("'Refresh IP' must be clickable", clickDesc("net_warning_refresh", 5_000))
        // A refresh must END in a reading or in an honest failure — never in
        // silence, and never in a "changed" claim no probe produced.
        assertTrue(
            "A refresh must report an outcome\n${uiTree()}",
            waitUntil(40_000) {
                hasDesc("net_warning_ip_changed", 300) ||
                    hasDesc("net_warning_ip_unchanged", 300) ||
                    hasDesc("net_warning_refresh_failed", 300)
            }
        )

        // A refresh is NOT a decision: the gate still stands, and every way
        // out is still offered.
        assertTrue("The warning must still stand after a refresh", hasText(warningTitle, 3_000))
        assertTrue("A refresh must not release the gate", pendingDecisionOrNull() != null)
        assertTrue("'Continue' must still be offered", hasText("Continue", 2_000))

        assertTrue("'Continue' must be clickable", clickTextScrolled("Continue"))
        assertTrue("Continue must release the engine", engineUiUp(15_000))
        assertTrue(
            "Continue must clear the persisted pending decision",
            waitUntil(6_000) { pendingDecisionOrNull() == null }
        )
    }
}
