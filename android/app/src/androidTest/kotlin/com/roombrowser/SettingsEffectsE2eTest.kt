package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.roombrowser.agent.AgentDevToolsTools
import com.roombrowser.browser.BrowserControlApi
import com.roombrowser.devtools.InspectorSession
import com.roombrowser.di.AppGraph
import com.roombrowser.domain.agent.ActionVerdict
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.DiagReport
import com.roombrowser.domain.agent.DomEvidence
import com.roombrowser.domain.download.sanitiseDownloadSubfolder
import com.roombrowser.domain.model.BrowserGlobalSettings
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.engine.EngineSession
import com.roombrowser.engine.devtools.DeveloperToolsCapabilities
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsEffectsE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private val graph: AppGraph get() = (targetContext.applicationContext as RoomBrowserApp).graph

    private val tag = (System.currentTimeMillis() % 1000000).toString()
    private val typedSubfolder = "../room-dl-$tag"
    private val sanitisedSubfolder = "room-dl-$tag"

    /** The active profile's settings as this test found them, so it can put them back. */
    private var restoreSettings: Pair<ProfileId, ProfileSettings>? = null

    private fun launchEngineHome() {
        targetContext.startActivity(
            Intent()
                .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("com.roombrowser.extra.INITIAL_URL", "about:home")
        )
    }

    private fun launchHomeAndWait(): Boolean {
        for (attempt in 1..3) {
            launchEngineHome()
            if (engineHomeUp(30_000)) return true
            device.waitForIdle(1_000)
        }
        return engineHomeUp(5_000)
    }

    private fun engineHomeUp(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (homeChromeUp(400)) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return homeChromeUp(1_000)
    }

    // The URL-bar pill merges its placeholder into one semantics node whose
    // contentDescription is "Address bar: search or type URL"; the inner TEXT
    // node is not what the a11y tree exposes.
    private fun homeChromeUp(timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.descContains("Address bar")), timeoutMs)

    private fun activeProfileId(): ProfileId? = runBlocking {
        graph.appState.activeProfileIdSnapshot()?.let { ProfileId(it) }
    }

    private fun ensureActiveProfile(): ProfileId? = runBlocking {
        val active = graph.appState.activeProfileIdSnapshot()
        val id = active ?: graph.profileManager
            .create("E2ESetFx${System.nanoTime()}", "👤", 0xFF7C4DFFL)
            .id.value
        graph.appState.setActiveProfile(id)
        val profileId = ProfileId(id)
        if (restoreSettings == null) {
            graph.profileRepo.getProfile(profileId)?.settings?.let { restoreSettings = profileId to it }
        }
        profileId
    }

    /**
     * These are persisted profile settings and the suite shares one process, so a
     * homepage left disabled or a download folder left changed is what every later
     * suite would see. Put back what was found.
     */
    @After
    fun restoreActiveProfileSettings() {
        val (id, settings) = restoreSettings ?: return
        runBlocking<Unit> {
            if (graph.profileRepo.getProfile(id) == null) return@runBlocking
            graph.profileManager.updateSettings(id, settings)
        }
    }

    private fun setActiveProfileSettings(transform: (ProfileSettings) -> ProfileSettings) {
        runBlocking {
            val id = activeProfileId() ?: return@runBlocking
            val current = graph.profileRepo.getProfile(id)?.settings ?: ProfileSettings()
            graph.profileManager.updateSettings(id, transform(current))
        }
    }

    private fun storedSubfolder(): String? = runBlocking {
        val raw = graph.appState.activeProfileIdSnapshot() ?: return@runBlocking null
        graph.profileRepo.getProfile(ProfileId(raw))?.settings?.downloadSubfolder
    }

    private fun waitForStoredSubfolder(expected: String, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: String? = null
        while (System.currentTimeMillis() < deadline) {
            last = storedSubfolder()
            if (last == expected) return last
            try { Thread.sleep(300) } catch (_: InterruptedException) { }
        }
        return last
    }

    // ---------- UiAutomator helpers --------------------------------------

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

    private fun absentFor(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.findObjects(By.text(text)).isNotEmpty()) return false
            try { Thread.sleep(200) } catch (_: InterruptedException) { }
        }
        return device.findObjects(By.text(text)).isEmpty()
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
            val clickable = try {
                current.isClickable
            } catch (_: Exception) {
                false
            }
            if (clickable) {
                val b = runCatching { current.visibleBounds }.getOrNull()
                if (b != null && b.width() > 0) {
                    device.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}")
                    device.waitForIdle(1_000)
                    return true
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

    private fun dragUpQuarter() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 100
        )
        device.waitForIdle(800)
        try { Thread.sleep(300) } catch (_: InterruptedException) { }
    }

    private fun hasTextWithScroll(text: String, attempts: Int = 20): Boolean {
        for (i in 1..attempts) {
            if (hasText(text, 1_000)) return true
            dragUpQuarter()
        }
        return hasText(text, 1_000)
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

    private fun waitImeShown(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (imeShown()) return true
            try { Thread.sleep(200) } catch (_: InterruptedException) { }
        }
        return imeShown()
    }

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

    private fun openProfileSettings(): Boolean =
        openSheetEntry("Profile settings") {
            device.wait(Until.hasObject(By.textContains("Profile Settings —")), 8_000)
        }

    private fun typeIntoDownloadFolder(value: String): Boolean {
        for (round in 1..3) {
            hideImeIfNeeded()
            var label = device.findObjects(By.text("Download folder")).firstOrNull { onScreen(it) }
            for (i in 1..8) {
                if (label != null) break
                dragUpQuarter()
                label = device.findObjects(By.text("Download folder")).firstOrNull { onScreen(it) }
            }
            if (label == null) continue
            val labelBounds = label.visibleBounds
            val centerX = labelBounds.centerX()
            val field = runCatching {
                device.findObjects(By.clazz("android.widget.EditText"))
                    .filter {
                        it.visibleBounds.left <= centerX && it.visibleBounds.right >= centerX
                    }
                    .minByOrNull { kotlin.math.abs(it.visibleBounds.top - labelBounds.top) }
            }.getOrNull() ?: continue
            var imeUp = false
            for (attempt in 1..3) {
                clickCenter(field)
                imeUp = waitImeShown(5_000)
                if (imeUp) break
                device.waitForIdle(400)
            }
            if (!imeUp) continue
            // Shell key events, IME-gated: ACTION_SET_TEXT can return false here
            // without typing anything, and a cached node's `.text` is stale, so
            // the read-back is a global text search.
            device.executeShellCommand("input keyevent KEYCODE_MOVE_END")
            device.clearFocusedField()
            device.waitForIdle(300)
            device.executeShellCommand("input text $value")
            device.waitForIdle(800)
            var typed = waitUntil(5_000) { device.hasObject(By.textContains(value)) }
            if (!typed) {
                runCatching { field.setText(value) }
                device.waitForIdle(600)
                typed = waitUntil(5_000) { device.hasObject(By.textContains(value)) }
            }
            if (typed) {
                hideImeIfNeeded()
                return true
            }
        }
        return false
    }

    private fun onScreen(node: UiObject2): Boolean {
        val b = node.visibleBounds
        return b.width() > 0 && b.height() > 0 &&
            b.centerX() in 0 until device.displayWidth &&
            b.centerY() in 0 until device.displayHeight
    }

    private fun downloadFolderRowReady(): Boolean {
        val label = device.findObjects(By.text("Download folder")).firstOrNull { onScreen(it) }
            ?: return false
        val top = label.visibleBounds.bottom
        return device.findObjects(By.text("Apply"))
            .any { onScreen(it) && it.visibleBounds.top >= top - 20 }
    }

    private fun reachDownloadFolderRow(): Boolean {
        for (i in 1..20) {
            if (downloadFolderRowReady()) return true
            dragUpQuarter()
        }
        return downloadFolderRowReady()
    }

    private fun clickApplyBelow(labelText: String): Boolean {
        for (attempt in 1..10) {
            val label = device.findObjects(By.text(labelText)).firstOrNull { onScreen(it) }
            if (label != null) {
                val top = label.visibleBounds.bottom
                val apply = device.findObjects(By.text("Apply"))
                    .filter { onScreen(it) && it.visibleBounds.top >= top - 20 }
                    .minByOrNull { it.visibleBounds.top }
                if (apply != null && clickSmart(apply)) return true
            }
            dragUpQuarter()
        }
        return false
    }

    // ---------- The contracts --------------------------------------------

    @Test
    fun homepage_enabled_gates_the_homepage_blocks() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("A profile must be active before the engine can boot", ensureActiveProfile() != null)

        setActiveProfileSettings { it.copy(homepageEnabled = false) }
        assertTrue("The home surface must come up with the homepage disabled", launchHomeAndWait())
        assertTrue(
            "With homepageEnabled=false the privacy-stats block must be gone\n${uiTree()}",
            absentFor("Privacy Dashboard", 2_000)
        )
        assertTrue(
            "With homepageEnabled=false the Quick Access block must be gone\n${uiTree()}",
            absentFor("Quick Access", 2_000)
        )

        setActiveProfileSettings {
            it.copy(
                homepageEnabled = true,
                showClock = true,
                showPrivacyStats = true,
                showRecentSites = true,
                homepageShortcuts = ProfileSettings.defaultShortcuts
            )
        }
        assertTrue("The home surface must come back up with the homepage enabled", launchHomeAndWait())
        assertTrue(
            "With homepageEnabled=true the privacy-stats block must render\n${uiTree()}",
            hasTextWithScroll("Privacy Dashboard")
        )
        assertTrue(
            "With homepageEnabled=true the Quick Access block must render\n${uiTree()}",
            hasTextWithScroll("Quick Access")
        )
    }

    @Test
    fun download_folder_row_commits_the_value_the_path_builder_sanitises() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        assertTrue("A profile must be active before the engine can boot", ensureActiveProfile() != null)

        assertTrue("The engine chrome must come up", launchHomeAndWait())
        assertTrue("Profile settings must open", openProfileSettings())
        assertTrue("The Downloads row must be reachable", reachDownloadFolderRow())
        assertTrue(
            "The download folder field must accept the typed value\n${uiTree()}",
            typeIntoDownloadFolder(typedSubfolder)
        )
        assertTrue(
            "The Downloads row's Apply button must commit the value\n${uiTree()}",
            clickApplyBelow("Download folder")
        )

        val stored = waitForStoredSubfolder(typedSubfolder, 10_000)
        assertEquals(typedSubfolder, stored)
        assertEquals(
            "The download path builder must reduce the stored value to a single safe segment",
            sanitisedSubfolder,
            sanitiseDownloadSubfolder(stored ?: "")
        )
    }

    @Test
    fun devtools_report_is_gated_by_diagnostics_enabled() {
        assertFalse(
            "diagnosticsEnabled must default to false",
            BrowserGlobalSettings().diagnosticsEnabled
        )

        val control = FakeControlApi()
        var enabled = false
        val tool = AgentDevToolsTools(
            control = control,
            boundTabId = { "e2e-tab" },
            diagnosticsEnabled = { enabled },
            destructiveGate = { _, _ -> ActionVerdict.Allow }
        )

        val refused = runBlocking { tool.execute(AgentTools.DEVTOOLS_REPORT, "{}") }
        assertTrue("The report tool must answer when diagnostics are off", refused != null)
        if (refused == null) return
        assertFalse("With diagnostics off the tool must refuse", refused.ok)
        assertTrue("The refusal must name the setting", refused.output.contains("switched off"))
        assertEquals("A refused report must not consult the engine", 0, control.captureCount)

        enabled = true
        val written = runBlocking { tool.execute(AgentTools.DEVTOOLS_REPORT, "{}") }
        assertTrue("The report tool must answer when diagnostics are on", written != null)
        if (written == null) return
        assertTrue("With diagnostics on the tool must succeed", written.ok)
        assertEquals("The enabled report must collect diagnostics once", 1, control.captureCount)
        assertTrue(
            "The report handed to the writer must be the formatted diagnostic report",
            control.savedText.contains("Room Browser - diagnostic report")
        )
        assertTrue(
            "The tool must report the path the writer returned",
            written.output.contains(control.savedPath)
        )
    }

    // ---------- Diagnostics --------------------------------------------------

    private class FakeControlApi : BrowserControlApi {

        var captureCount = 0
        var savedText = ""
        val savedPath = "/data/local/tmp/room-e2e-diagnostics.txt"

        override val engineName: String = "E2E engine"
        override val engineVersion: String = "0.0.0"

        override fun navigate(url: String) = Unit

        override suspend fun openTab(url: String?): String? = null

        override fun closeTab(tabId: String?) = Unit

        override fun switchTab(tabId: String) = Unit

        override fun reload(tabId: String?) = Unit

        override fun goForward(tabId: String?) = Unit

        override fun find(query: String, forward: Boolean) = Unit

        override fun clearFind() = Unit

        override fun setDesktopMode(enabled: Boolean) = Unit

        override fun openDevToolsPanel(tabId: String?) = Unit

        override fun activeTabId(): String? = "e2e-tab"

        override fun tabIds(): List<String> = listOf("e2e-tab")

        override fun tabTitle(tabId: String): String? = "E2E"

        override fun tabUrl(tabId: String): String? = "about:home"

        override fun tabSession(tabId: String): EngineSession? = null

        override fun inspectorFor(tabId: String): InspectorSession? = null

        override fun capabilitiesFor(tabId: String): DeveloperToolsCapabilities =
            DeveloperToolsCapabilities.NONE

        override suspend fun captureDiagnostics(
            tabId: String?,
            dom: List<DomEvidence>,
            focus: String
        ): DiagReport {
            captureCount++
            return DiagReport(
                engineName = engineName,
                engineVersion = engineVersion,
                androidRelease = "e2e",
                apiLevel = 0,
                appVersion = "e2e",
                profile = profile(),
                activeUrl = "about:home",
                generatedAtMillis = System.currentTimeMillis()
            )
        }

        override fun profile(): Profile =
            Profile(id = ProfileId("e2e-profile"), name = "E2E Profile", createdAt = 0L)

        override fun saveDiagnosticReport(text: String, fileName: String): String? {
            savedText = text
            return savedPath
        }
    }

    private fun uiTree(): String = try {
        val texts = runCatching {
            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(70)
        }.getOrDefault(emptyList())
        "TEXTS: $texts"
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }
}
