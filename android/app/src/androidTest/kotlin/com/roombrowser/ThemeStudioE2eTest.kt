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
 * E2E for the per-profile Theme Studio (2026-09):
 *
 *   BrowserActivity (':browser' process)
 *     -> page menu -> "Theme studio" (own activity, default process)
 *     -> title + presets are visible (18 built-ins, e.g. Obsidian / Ocean)
 *     -> tap the Ocean preset card (preview only — nothing persisted yet)
 *     -> "Apply to profile" persists the snapshot to profiles.theme_json
 *     -> close: back in the engine, which re-themed itself live
 *
 * Click strategy: the proven desc-click pattern from AgentSettingsE2eTest
 * (content-description on the clickable node, ACTION_CLICK with a walk-up
 * to the nearest clickable ancestor, coordinate fallback).
 */
@RunWith(AndroidJUnit4::class)
class ThemeStudioE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

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

    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickCenter(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        device.click(b.centerX(), b.centerY())
        device.waitForIdle(1_000)
        true
    } catch (_: Exception) {
        false
    }

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
        // The address pill ALWAYS carries the desc "Address bar: …" — the
        // engine is detectable regardless of omnibox placeholder exposure.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.wait(Until.hasObject(By.descContains("Address bar")), 400)) return true
            if (hasText("Search or type URL", 400)) return true
            if (hasText("Privacy Dashboard", 400)) return true
            if (hasText("trackers blocked", 400)) return true
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                }
        }
        return device.wait(Until.hasObject(By.descContains("Address bar")), 500)
    }

    private fun dragUpQuarter() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 5 / 8,
            device.displayWidth / 2, device.displayHeight * 3 / 8, 100
        )
        device.waitForIdle(600)
    }

    /** Scroll-aware text wait: small deterministic drags between polls — on
     *  the CI emulator's small screen (320x640) the preset cards live below
     *  the fold and off-screen rows are NOT exposed to the a11y tree. */
    private fun hasTextWithScroll(text: String, attempts: Int = 12): Boolean {
        for (i in 1..attempts) {
            if (hasText(text, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    /** Scroll-aware desc click with verification. */
    private fun clickDescWithScroll(desc: String, attempts: Int = 12): Boolean {
        for (i in 1..attempts) {
            val node = device.wait(Until.findObject(By.desc(desc)), 1_500)
            if (node != null) {
                if (clickSmart(node)) return true
                runCatching { clickCenter(node) }
                if (hasDesc(desc, 2_000)) {
                    // still present → the click did not consume it; try again
                } else return true
            }
            dragUpQuarter()
        }
        return false
    }

    /** Bootstrap: launcher → profile (create on first run) → engine UI up. */
    private fun openEngineFromLauncher(): Boolean {
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)
        assertTrue(
            "First-run welcome (or profile list) should appear",
            hasText("Create Profile", 90_000) || hasText("OPEN", 10_000)
        )
        if (device.findObjects(By.text("OPEN")).isNotEmpty()) {
            assertTrue("OPEN must be clickable", clickText("OPEN", 8_000))
        } else {
            var dialogOpen = false
            for (attempt in 1..2) {
                assertTrue("Create Profile button must be visible", clickText("Create Profile", 5_000))
                dialogOpen = hasText("Cancel", 6_000)
                if (dialogOpen) break
            }
            assertTrue("Create-profile dialog should open", dialogOpen)
            val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
            assertTrue("Name field must be visible", field != null)
            clickCenter(field!!)
            device.executeShellCommand("input text E2E_Theme")
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
        return engineUiUp(30_000)
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

    @Test
    fun theme_studio_opens_previews_and_applies_per_profile() {
        // Determinism: the runner's shared IP arms the organic network
        // warning on fresh-profile boots — suppress it (E2eDeterminism).
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("Engine must be reachable", openEngineFromLauncher())

        // ---- 1. Open the studio from the page menu ------------------------
        assertTrue(
            "Theme studio must open from the page menu",
            openSheetEntry("Theme studio") { hasText("Theme Studio", 8_000) }
        )

        // ---- 2. The preset gallery is visible ------------------------------
        // The CI emulator screen is small (320x640) — the cards sit below the
        // fold, so the finds are scroll-aware.
        assertTrue("Preset section header must show", hasTextWithScroll("Presets"))
        assertTrue(
            "At least two presets must be visible",
            hasTextWithScroll("Obsidian") && hasTextWithScroll("Ocean")
        )

        // ---- 3. Preview a preset (tap = preview only, nothing persisted) ---
        assertTrue(
            "Ocean preset card must be clickable",
            clickDescWithScroll("theme_card_ocean")
        )
        device.waitForIdle(800)

        // ---- 4. Apply it to this profile ------------------------------------
        // The floating Apply stays on-screen; scroll back up if needed.
        assertTrue(
            "Apply button must be clickable",
            clickDescWithScroll("theme_apply")
        )
        device.waitForIdle(1_500)

        // ---- 5. Close and land back in the re-themed engine -----------------
        for (attempt in 1..3) {
            if (hasDesc("Close", 1_000) && clickDesc("Close", 4_000)) break
            device.pressBack()
            device.waitForIdle(1_000)
        }
        assertTrue(
            "Engine UI must be back after theming",
            engineUiUp(20_000)
        )
    }
}
