package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.domain.model.DnsMode
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.ScreenSizeMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the settings-persistence regression (the browser's per-profile
 * settings must actually survive an engine restart — the write path runs
 * through the UI, the read path back through a cold-started engine):
 *
 *   BrowserActivity (':browser'), one FRESH profile per run
 *     -> page menu -> Profile settings
 *     -> DNS: tap the "Cloudflare DNS (1.1.1.1)" preset row — the mode flips
 *        to DOH and the preset's DoH URL is stored
 *     -> Language: pick "English (US)" in the Translate target picker
 *     -> Screen size: switch to "Set manually", type 400x800 — the debounced
 *        auto-save writes both values
 *     -> DB ground truth: profiles.settings_json decodes to exactly those
 *        values (the same codec the app uses)
 *     -> engine RESTART (cold relaunch) -> re-open Profile settings -> all
 *        three settings re-render from storage
 */
@RunWith(AndroidJUnit4::class)
class SettingsPersistenceE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private val tag = (System.currentTimeMillis() % 100000).toString()
    private val profileName = "E2ESet$tag"

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
        // The tap is injected via the SHELL `input tap`, never
        // UiObject2.click()/device.click(): those go through
        // UiAutomator's InteractionController, which waits for an
        // accessibility-idle window around the events and TIMES OUT on
        // busy screens (CI ec43763: 'Timed out waiting 1000ms for
        // command and events' — the app received nothing; the shell tap
        // is fire-and-forget and has never lost a tap).
        var current: UiObject2? = node
        var hops = 0
        while (current != null && hops < 8) {
            val clickable = try { current.isClickable } catch (_: Exception) { false }
            if (clickable) {
                val b = runCatching { current.visibleBounds }.getOrNull()
                if (b != null && b.width() > 0) {
                    device.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}")
                    device.waitForIdle(1_000)
                    return true
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

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun dragUpQuarter() {
        // Half-screen drag (3/4 → 1/4): the CI emulator's default profile is
        // 320x640 mdpi — the Screen size / DNS / Language sections sit
        // 2000–4000px down the profile settings screen there. Slow steps (no
        // fling) keep it a controlled scroll; the settle AFTER the drag lets
        // residual momentum finish before the caller reads node bounds.
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 100
        )
        device.waitForIdle(800)
        try { Thread.sleep(300) } catch (_: InterruptedException) { }
    }

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
     * viewport has stopped moving — the drag's own settle (see
     * [dragUpQuarter]) cannot cover a node that keeps moving for its own
     * reason (a late layout, a popup shifting under the cursor), and this
     * helper returns a bare `true` with no verification, so a tap on
     * stale bounds is swallowed silently. Bounded: gives up after [tries]
     * reads so a node that is genuinely animating cannot hang the test.
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

    private fun hasTextWithScroll(text: String, attempts: Int = 24): Boolean {
        for (i in 1..attempts) {
            if (hasText(text, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

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
            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(70)
        }.getOrDefault(emptyList())
        "TEXTS: $texts"
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }

    // ---------- Bootstrap: fresh profile (default settings) ---------------

    private fun bootstrapFreshEngine(): Boolean {
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)
        assertTrue(
            "Profile list or first-run state must appear",
            hasText("Your profiles", 90_000) || hasText("Create Profile", 90_000)
        )
        assertTrue("Create Profile affordance must be reachable", clickCreateScrollAware())
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
        assertTrue(
            "Create dialog should close after confirm",
            waitUntil(10_000) { device.findObjects(By.text("Cancel")).isEmpty() }
        )
        return engineUiUp(40_000)
    }

    private fun clickCreateScrollAware(): Boolean {
        for (i in 1..12) {
            if (clickText("Create Profile", 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    /** Polls dumpsys until the IME is actually shown (focus really landed). */
    private fun waitImeShown(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (imeShown()) return true
            try { Thread.sleep(200) } catch (_: InterruptedException) { }
        }
        return imeShown()
    }

    // ---------- Navigation helpers ----------------------------------------

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

    /** Profile settings = the per-profile screen behind the page menu. */
    private fun openProfileSettings(): Boolean =
        openSheetEntry("Profile settings") {
            device.wait(Until.hasObject(By.textContains("Profile Settings —")), 8_000)
        }

    /** Cold relaunch of the engine on the persisted active profile. */
    private fun relaunchEngine() {
        targetContext.startActivity(
            Intent()
                .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    /**
     * Types [value] into the editable field identified by its [label]
     * ("Width (CSS px)" / "Height (CSS px)"). The label floats on the field's
     * top border, so the EDITABLE node itself is targeted: the EditText-class
     * a11y node directly below the label whose horizontal span contains the
     * label's center (read-only dropdown fields render as EditText too, and
     * the width/height siblings sit side by side — hence the geometric pick).
     *
     * CI 88ec8fe forensics: the old shell key-event burst (MOVE_END + DELs +
     * `input text`) was dropped ENTIRELY — the editor's InputConnection only
     * attached ~160 ms AFTER the burst (logcat "LatinIME: Starting input.
     * Cursor position = 4,4" — the field still held its original 4-char
     * value), so nothing was typed and the verification never saw the value.
     * The proven Tabs/Wallet pattern replaces it: gate on the IME being
     * SHOWN (dumpsys proof the connection is live) before touching the
     * field, then set the text through the accessibility ACTION_SET_TEXT
     * (no key events at all — cannot be dropped by focus races) and verify
     * with FRESH node lookups; only a THROWN exception falls back to the
     * shell path.
     */
    private fun typeIntoLabeledField(label: String, value: String): Boolean {
        for (round in 1..3) {
            hideImeIfNeeded()
            // Scroll-aware label lookup: the previous field's search may have
            // scrolled THIS label out of the viewport (off-screen nodes are
            // not in the a11y tree).
            var labelNode = device.wait(Until.findObject(By.text(label)), 3_000)
            for (i in 1..8) {
                if (labelNode != null) break
                dragUpQuarter()
                labelNode = device.wait(Until.findObject(By.text(label)), 1_000)
            }
            if (labelNode == null) continue
            val labelBounds = labelNode.visibleBounds
            val labelCenterX = labelBounds.centerX()
            val field = runCatching {
                device.findObjects(By.clazz("android.widget.EditText"))
                    .filter {
                        it.visibleBounds.top >= labelBounds.bottom - 20 &&
                            it.visibleBounds.left <= labelCenterX &&
                            it.visibleBounds.right >= labelCenterX
                    }
                    .minByOrNull { it.visibleBounds.top }
            }.getOrNull() ?: continue
            clickCenter(field)
            // The IME must be SHOWN before anything is typed — the click's
            // focus handoff is asynchronous and the CI runner takes ~1 s
            // (the dropped-burst root cause above).
            if (!waitImeShown(5_000)) continue
            var typed = false
            try {
                // ACTION_SET_TEXT goes through the semantics pipeline — no
                // key events, immune to InputConnection races.
                field.setText(value)
                typed = true
            } catch (_: Exception) {
                typed = false
            }
            if (!typed) {
                // Fallback: the shell key-event path (with a full re-clear).
                device.executeShellCommand("input keyevent KEYCODE_MOVE_END")
                device.clearFocusedField()
                device.waitForIdle(300)
                device.executeShellCommand("input text $value")
                device.waitForIdle(800)
            }
            // FRESH lookups re-read the nodes' current text — the cached
            // `field` reference sees stale properties. The value must be
            // verifiably IN the field before this round can succeed (the
            // debounced commit fires off the field state, not the a11y
            // action).
            if (waitUntil(5_000) {
                    device.findObjects(By.clazz("android.widget.EditText"))
                        .mapNotNull { it.text }
                        .any { it.contains(value) }
                }
            ) return true
        }
        return false
    }

    /** DB ground truth: this run's profile settings (same codec the app uses). */
    private fun readSettingsFromDb(): ProfileSettings {
        val db = Room.databaseBuilder(targetContext, AppDatabase::class.java, AppDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        try {
            return runBlocking {
                val profile = db.profileDao().all().first { it.name == profileName }
                Json { ignoreUnknownKeys = true }
                    .decodeFromString(ProfileSettings.serializer(), profile.settingsJson)
            }
        } finally {
            db.close()
        }
    }

    // ---------- The contract ------------------------------------------------

    @Test
    fun dns_language_and_screen_size_persist_across_engine_restart() {
        // Determinism: the runner's shared IP arms the organic network
        // warning on fresh-profile boots — suppress it (E2eDeterminism).
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("Engine must come up on a fresh profile", bootstrapFreshEngine())

        // ---- 1. Profile settings screen -----------------------------------
        assertTrue("Profile settings must open", openProfileSettings())

        // Sections are visited TOP-DOWN (Screen size → Profile DNS →
        // Language & Translate): the scroll-aware helpers only ever advance
        // the viewport downwards, so the order matters.

        // ---- 2. Manual screen size (debounced auto-save) --------------------
        assertTrue(
            "The reported-size dropdown must open (desc on its trailing icon)\n${uiTree()}",
            clickDescWithScroll("Reported size dropdown")
        )
        assertTrue(
            "'Set manually' must be pickable (verified: the manual fields appear)\n${uiTree()}",
            pickReportedSizeManually()
        )
        assertTrue(
            "The width/height fields must appear",
            hasTextWithScroll("Width (CSS px)") && hasTextWithScroll("Height (CSS px)")
        )
        assertTrue("Width must be typeable", typeIntoLabeledField("Width (CSS px)", "400"))
        assertTrue("Height must be typeable", typeIntoLabeledField("Height (CSS px)", "800"))
        // Debounce: the commit fires ~600 ms after the last keystroke.
        device.waitForIdle(2_000)
        // The typing left the IME up — and with the keyboard covering the
        // bottom ~40% of the 320x640 screen, clickTextWithScroll's drags
        // (start at 3/4 height) land ON the IME and never scroll the app
        // (CI 75822ed: 24 futile attempts at the Cloudflare row). Dismiss
        // it before descending to the DNS section.
        hideImeIfNeeded()

        // ---- 3. DNS preset: one tap = DOH + the preset's DoH URL ----------
        assertTrue(
            "The Cloudflare preset row must be tappable",
            clickTextWithScroll("Cloudflare DNS (1.1.1.1)")
        )
        assertTrue(
            "The DNS mode field must flip to DNS-over-HTTPS",
            hasTextWithScroll("DNS-over-HTTPS")
        )
        assertTrue(
            "The preset's DoH URL must be stored and shown",
            hasTextWithScroll("DoH URL (https://…)") &&
                hasTextWithScroll("https://cloudflare-dns.com/dns-query")
        )

        // ---- 4. Language preset --------------------------------------------
        assertTrue(
            "The translate-target anchor (default id) must be tappable",
            clickTextWithScroll("Bahasa Indonesia (id)")
        )
        assertTrue("'English (US)' must be pickable", clickText("English (US)", 6_000))
        assertTrue(
            "The anchor must re-render with the new preset",
            hasText("English (US) (en-US)", 6_000)
        )

        // ---- 5. DB ground truth — the writes really landed ------------------
        // Each UI edit commits through its own coroutine; poll until all
        // three have reached storage, then assert each value precisely.
        assertTrue(
            "All three settings must reach the database",
            waitUntil(10_000) {
                val s = readSettingsFromDb()
                s.dnsMode == DnsMode.DOH &&
                    s.dohUrl == "https://cloudflare-dns.com/dns-query" &&
                    s.translateTargetLanguage == "en-US" &&
                    s.screenSizeMode == ScreenSizeMode.MANUAL &&
                    s.screenWidthPx == 400 &&
                    s.screenHeightPx == 800
            }
        )
        val settings = readSettingsFromDb()
        assertEquals(DnsMode.DOH, settings.dnsMode)
        assertEquals("https://cloudflare-dns.com/dns-query", settings.dohUrl)
        assertEquals("en-US", settings.translateTargetLanguage)
        assertEquals(ScreenSizeMode.MANUAL, settings.screenSizeMode)
        assertEquals(400, settings.screenWidthPx)
        assertEquals(800, settings.screenHeightPx)

        // ---- 6. Engine restart: the settings must re-render from storage ---
        relaunchEngine()
        assertTrue("The engine must come back up", engineUiUp(30_000))
        assertTrue("Profile settings must re-open", openProfileSettings())
        // Top-down again: Screen size → Profile DNS → Language & Translate.
        assertTrue(
            "The manual screen size must survive the restart",
            hasTextWithScroll("Set manually") && hasTextWithScroll("Width (CSS px)") &&
                hasTextWithScroll("400") && hasTextWithScroll("800")
        )
        assertTrue(
            "The DNS mode must still be DNS-over-HTTPS after restart",
            hasTextWithScroll("DNS-over-HTTPS") &&
                hasTextWithScroll("https://cloudflare-dns.com/dns-query")
        )
        assertTrue(
            "The language preset must survive the restart",
            hasTextWithScroll("English (US) (en-US)")
        )
    }

    private fun clickDescWithScroll(desc: String, attempts: Int = 24): Boolean {
        for (i in 1..attempts) {
            if (clickDesc(desc, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    /**
     * Picks 'Set manually' in the reported-size dropdown, VERIFIED on the
     * manual width/height fields appearing. CI run 798d73c proved the pick
     * itself WORKS — the fields then render BELOW THE FOLD on the 320x640
     * screen, and off-screen nodes are not in the a11y tree, so a flat
     * presence check misread success as failure and re-picked forever. The
     * verification is therefore SCROLL-AWARE. The menu item tap and the
     * dropdown-opening tap are both re-attempted with FRESH node resolves —
     * stale-bounds taps after scroll flings or popup layout shifts land on
     * nothing.
     */
    private fun pickReportedSizeManually(): Boolean {
        // The caller VERIFIED the dropdown is open — consume that state
        // FIRST: any drag dismisses the ExposedDropdownMenu popup AND
        // scrolls away from the section. (CI 033cb23: the old field-first
        // search dragged 24x for fields that could not exist yet, closed
        // the menu, and the NON-scroll-aware reopen never found the
        // off-screen desc node — ten minutes of futile drags.)
        for (attempt in 1..6) {
            val item = device.wait(Until.findObject(By.text("Set manually")), 2_000)
            if (item != null) {
                clickSmart(item)
                device.waitForIdle(1_000)
                // The expanded fields land below the fold when the section
                // sits low on the small CI screen — their absence from the
                // CURRENT viewport is not evidence the pick failed.
                if (hasTextWithScroll("Width (CSS px)", attempts = 10)) return true
            }
            // Menu closed (or the tap missed): reopen it. Walk the viewport
            // back UP to the Screen size section first — the one-directional
            // scroll-aware helpers can only descend, and the failed pick
            // attempts may have dragged far down.
            scrollBackToScreenSizeSection()
            if (!clickDescWithScroll("Reported size dropdown", attempts = 10)) continue
            device.waitForIdle(800)
        }
        return hasTextWithScroll("Width (CSS px)", attempts = 6)
    }

    /**
     * Drags the settings screen back toward its TOP until the "Reported
     * size" row re-enters the viewport (finger 1/4 -> 3/4 = viewport moves
     * UP). The pick/search helpers only ever scroll DOWN; after a long
     * descend they must be reset before anything above can be found again.
     */
    private fun scrollBackToScreenSizeSection(): Boolean {
        for (i in 1..18) {
            if (device.findObjects(By.text("Reported size")).isNotEmpty()) return true
            device.swipe(
                device.displayWidth / 2, device.displayHeight / 4,
                device.displayWidth / 2, device.displayHeight * 3 / 4, 100
            )
            device.waitForIdle(600)
            try { Thread.sleep(200) } catch (_: InterruptedException) { }
        }
        return device.findObjects(By.text("Reported size")).isNotEmpty()
    }
}
