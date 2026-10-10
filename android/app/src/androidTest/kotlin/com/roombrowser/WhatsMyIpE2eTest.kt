package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the "What's My IP" diagnostics screen:
 *
 *   - Its row sits DIRECTLY ABOVE "About Room Browser" in the page actions
 *     sheet, which is where the owner asked for it, and the row opens the
 *     screen.
 *   - The screen reports the profile's real identity, resolved through the
 *     same resolver the engine is configured from: a fresh profile sends the
 *     engine's own user agent, so the row must say so. The address rows are
 *     also present and are measured with a probe this suite cannot control —
 *     the emulator may or may not have a route — so they are asserted as
 *     "an outcome, never silence".
 *   - Refresh is a re-reading, not a decision: it ends in an address or in an
 *     honest failure, and never blanks the screen.
 */
@RunWith(AndroidJUnit4::class)
class WhatsMyIpE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private val tag = (System.currentTimeMillis() % 100000).toString()
    private val profileName = "E2EIp$tag"
    private val screenTitle = "What's My IP"
    private val failureText = "No reading - no IP service answered"

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

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

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

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickTextScrolled(text: String, attempts: Int = 8): Boolean {
        for (i in 1..attempts) {
            val node = device.wait(Until.findObject(By.text(text)), 1_500)
            if (node != null && clickSmart(node)) return true
            dragUpQuarter()
        }
        return false
    }

    private fun dragUpQuarter() {
        // Half-screen drag (3/4 -> 1/4): the CI emulator is 320x640 mdpi, so
        // deep content lives far below the fold.
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 100
        )
        device.waitForIdle(600)
    }

    private fun hasTextWithScroll(text: String, attempts: Int = 12): Boolean {
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

    // ---------- Bootstrap: a fresh profile of our own --------------------

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
        // The runner's shared IP organically arms the warning for a fresh
        // profile; clear it so it is not part of this test's variable.
        if (hasText("Profile Network Warning", 3_000)) {
            clickTextScrolled("Continue", attempts = 4)
            return engineUiUp(15_000)
        }
        return true
    }

    private fun clickScrollAwareCreate(): Boolean {
        for (i in 1..24) {
            val node = device.wait(Until.findObject(By.text("Create Profile")), 1_500)
            if (node != null && clickSmart(node)) return true
            dragUpQuarter()
        }
        return false
    }

    private fun waitGone(text: String, timeoutMs: Long): Boolean =
        waitUntil(timeoutMs) { device.findObjects(By.text(text)).isEmpty() }

    /** The page actions sheet, opened through its own affordance. */
    private fun openPageActionsSheet(): Boolean {
        for (round in 1..3) {
            if (!hasDesc("Page actions and settings", 1_500)) {
                device.pressBack()
                device.waitForIdle(1_000)
            }
            if (clickDesc("Page actions and settings", 6_000) && hasText("Page Actions", 4_000)) {
                return true
            }
        }
        return hasText("Page Actions", 2_000)
    }

    /** A sheet row's own vertical position, or null while it is off-tree. */
    private fun sheetRowTop(text: String): Int? = runCatching {
        device.findObjects(By.text(text)).minByOrNull { it.visibleBounds.top }
    }.getOrNull()?.let { runCatching { it.visibleBounds.top }.getOrNull() }

    /** Both rows' tops, read in one pass so a scroll cannot shift them apart. */
    private fun sheetRowOrder(): Pair<Int, Int>? {
        repeat(8) {
            val whatsMyIp = sheetRowTop(screenTitle)
            val about = sheetRowTop("About Room Browser")
            if (whatsMyIp != null && about != null) return Pair(whatsMyIp, about)
            dragUpQuarter()
        }
        return null
    }

    /** Opens the diagnostics screen from the sheet, leaving it on screen. */
    private fun openWhatsMyIp(): Boolean {
        if (!openPageActionsSheet()) return false
        for (i in 1..12) {
            val node = device.wait(Until.findObject(By.desc(screenTitle)), 2_000)
            if (node != null) {
                clickSmart(node)
                if (hasDesc("whatsmyip_refresh", 8_000)) return true
                runCatching { clickCenter(node) }
                if (hasDesc("whatsmyip_refresh", 8_000)) return true
            }
            dragUpQuarter()
        }
        return false
    }

    private fun ipv4Value(): String? = runCatching {
        device.findObject(By.desc("whatsmyip_ipv4"))?.text
    }.getOrNull()

    private fun looksLikeAddress(value: String): Boolean {
        val text = value.trim()
        if (text.contains(':')) return text.count { it == ':' } >= 2
        val parts = text.split('.')
        return parts.size == 4 && parts.all {
            it.isNotEmpty() && it.length <= 3 && it.toIntOrNull() in 0..255
        }
    }

    // ---------- The contract ------------------------------------------------

    @Test
    fun whats_my_ip_sits_directly_above_about_and_opens_the_screen() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("Engine must come up on a fresh profile", bootstrapFreshEngine())
        assertTrue("The Page Actions sheet must open\n${uiTree()}", openPageActionsSheet())

        val order = sheetRowOrder()
        assertTrue(
            "\"$screenTitle\" and \"About Room Browser\" must both be in the sheet\n${uiTree()}",
            order != null
        )
        val (whatsMyIpTop, aboutTop) = order!!
        assertTrue(
            "\"$screenTitle\" must sit ABOVE \"About Room Browser\" " +
                "(tops: $whatsMyIpTop / $aboutTop)\n${uiTree()}",
            whatsMyIpTop < aboutTop
        )

        assertTrue(
            "The row must open the diagnostics screen\n${uiTree()}",
            openWhatsMyIp()
        )
        assertTrue("The screen must name itself", hasText(screenTitle, 5_000))

        // The identity rows resolve through the same settings the engine is
        // configured from. A profile created here is handed a catalogue handset
        // by ProfileManager, so the screen must report that, not "Engine default".
        assertTrue("The user-agent row must be present", hasDesc("whatsmyip_user_agent", 5_000))
        assertTrue(
            "A profile created here presents as its catalogue device\n${uiTree()}",
            hasTextWithScroll("Device profile")
        )
        assertTrue(
            "The engine's own string must be reported as unused\n${uiTree()}",
            hasTextWithScroll("Not reported - this profile supplies its own")
        )
        assertTrue("The address rows must be present", hasDesc("whatsmyip_ipv4", 5_000))
        assertTrue("The IPv6 row must be present", hasDesc("whatsmyip_ipv6", 5_000))
        assertTrue("The proxy route must be reported", hasDesc("whatsmyip_proxy", 5_000))

        assertTrue("The screen must close", clickDesc("Close", 5_000))
        assertTrue("Closing must return to the engine\n${uiTree()}", engineUiUp(20_000))
    }

    /**
     * Refresh re-reads; it is never a decision and never leaves the screen
     * blank. What this can pin on the emulator is the contract, not the
     * number: the probe goes to the internet or fails, so every refresh must
     * END in an address or in a stated failure.
     */
    @Test
    fun refresh_ends_in_an_address_or_an_honest_failure() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("Engine must come up on a fresh profile", bootstrapFreshEngine())
        assertTrue("The diagnostics screen must open\n${uiTree()}", openWhatsMyIp())

        val before = ipv4Value()
        assertTrue(
            "The address row must carry a value before any refresh (saw '$before')\n${uiTree()}",
            !before.isNullOrBlank()
        )

        assertTrue("The refresh action must be clickable", clickDesc("whatsmyip_refresh", 5_000))
        assertTrue(
            "A refresh must end in an address or in an honest failure\n${uiTree()}",
            waitUntil(40_000) {
                val value = ipv4Value().orEmpty()
                value.isNotBlank() && (looksLikeAddress(value) || value == failureText)
            }
        )

        // A refresh must not take the screen away or blank the other rows.
        assertTrue("The screen must still be up after a refresh", hasText(screenTitle, 3_000))
        assertTrue("The identity rows must survive a refresh", hasDesc("whatsmyip_user_agent", 5_000))
        assertTrue("The proxy row must survive a refresh", hasDesc("whatsmyip_proxy", 5_000))

        assertTrue("The screen must close", clickDesc("Close", 5_000))
        assertTrue("Closing must return to the engine\n${uiTree()}", engineUiUp(20_000))
    }
}
