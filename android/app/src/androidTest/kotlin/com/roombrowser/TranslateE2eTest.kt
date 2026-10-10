package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.roombrowser.data.db.TabEntity
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.translate.PageTranslate
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the page-translate flow: Page Actions -> "Translate" -> the dialog ->
 * confirm, and the tab that comes out of it.
 *
 * WHY THIS TEST EXISTS. The wrapper URL used to be assembled inline inside the
 * dialog's confirm lambda, so nothing in either edition ran that line except a
 * human hand. Two defects lived there unobserved: the dialog sent pages that
 * have no address to fetch -- a `data:` document, an `oct://` circle -- and the
 * new tab was created without the current tab's privacy, so translating a
 * private page wrote its address into a persisted normal tab. The URL itself is
 * pinned exactly by the JVM `PageTranslateTest`; this asserts the wiring that
 * test cannot see.
 *
 * THE WITNESS IS THE TAB TABLE, NOT THE SCREEN. The emulator has no internet,
 * so the wrapper URL can never actually load here. What is deterministic is the
 * row the app writes when it opens the tab: the URL it was asked to open and the
 * privacy it was given. That row is engine-independent, so the same test runs in
 * both editions. It does NOT prove Google answered -- that needs a real device
 * and is stated as out of scope rather than faked.
 *
 * Run alone this test creates the profile it needs, so a filtered dispatch
 * needs only `com.roombrowser.TranslateE2eTest`.
 */
@RunWith(AndroidJUnit4::class)
class TranslateE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer

    /** Per-run marker, so a dirty device cannot answer for this run. */
    private val tag = (System.currentTimeMillis() % 100000).toString()
    private val marker = "TRANSLATE-E2E-$tag"

    @Before
    fun setUp() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(
                    """
                    <!DOCTYPE html><html><head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    </head><body style="font-size:24px; margin:24px;">
                    <h1>$marker</h1>
                    </body></html>
                    """.trimIndent()
                )
        }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun translating_a_page_opens_a_tab_at_the_wrapper_url() {
        assertTrue(
            "A profile must be active before the engine activity can be cold-started",
            ensureActiveProfile()
        )
        val page = server.url("/article-$tag").toString()
        assertTrue("The page must be rendered before it can be translated", openPage(page))

        // The expectation is read from the active profile's own settings, so this
        // pins the URL the app built rather than a language the test assumed.
        val expected = requireNotNull(PageTranslate.urlFor(page, activeTranslateLanguage()))

        assertTrue(
            "Page Actions must offer a Translate entry that opens the dialog",
            openTranslateDialog()
        )
        assertTrue(
            "The dialog's Translate button must be pressable for a real web page",
            clickText("Translate", 6_000)
        )

        val created = waitUntil(20_000) { openTabs().any { it.url == expected && !it.isPrivate } }
        if (!created) {
            snap("translate-tab")
            fail(
                "Confirming the translate dialog must open a new tab at the wrapper URL " +
                    "for the profile's target language.\nexpected=$expected\n${diagnostics()}"
            )
        }
    }

    @Test
    fun a_page_with_no_address_never_reaches_the_wrapper() {
        assertTrue(ensureActiveProfile())
        launchEngine()
        // The chrome must be up before anything is opened: openSheetEntry gives
        // up on a missing sheet with a Back, and a Back into a still-starting
        // activity finishes it.
        assertTrue(
            "The browser UI must be up before its sheet can be opened",
            hasDesc("Page actions and settings", 30_000)
        )
        // The restored session may have left a real page as the active tab, so
        // the start page is opened explicitly instead of assumed.
        assertTrue("A New tab must open the start page", openStartPageTab())
        val before = wrapperTabCount()

        assertTrue(
            "Page Actions must offer a Translate entry on the start page too",
            openTranslateDialog()
        )
        assertTrue(
            "The dialog must say why this page cannot be translated",
            hasText("This page has no address a translator can open. Load a web page first.", 5_000)
        )

        // The button is disabled; press it anyway, so a regression that
        // re-enables it is caught here rather than by a user on a blank tab.
        clickText("Translate", 3_000)
        device.waitForIdle(2_000)
        assertEquals(
            "A page with no fetchable address must never be sent to the wrapper",
            before,
            wrapperTabCount()
        )
    }

    // ---------- The state this test reads ---------------------------------

    private fun openTabs(): List<TabEntity> = runBlocking {
        val graph = (targetContext.applicationContext as RoomBrowserApp).graph
        val profileId = graph.appState.activeProfileIdSnapshot()
            ?: return@runBlocking emptyList()
        graph.database.tabDao().openTabs(profileId)
    }

    private fun wrapperTabCount(): Int =
        openTabs().count { it.url.startsWith(PageTranslate.WRAPPER) }

    private fun activeTranslateLanguage(): String = runBlocking {
        val graph = (targetContext.applicationContext as RoomBrowserApp).graph
        val profileId = graph.appState.activeProfileIdSnapshot()
            ?: return@runBlocking "id"
        graph.profileRepo.getProfile(ProfileId(profileId))?.settings?.translateTargetLanguage
            ?: "id"
    }

    /**
     * Makes sure SOME profile is active, through the repositories the UI itself
     * uses -- the create dialog's focus/scroll/IME races are not what this test
     * is about. A00WarmupTest deliberately leaves the app with no profile, so a
     * filtered run has to mint one; a full run reuses whatever the earlier
     * suites left active.
     */
    private fun ensureActiveProfile(): Boolean {
        val graph = (targetContext.applicationContext as RoomBrowserApp).graph
        return runBlocking {
            val active = graph.appState.activeProfileIdSnapshot()
            val id = active ?: graph.profileManager
                .create("E2E Translate ${System.currentTimeMillis()}", "👤", 0xFF7C4DFFL)
                .id.value
            graph.appState.setActiveProfile(id)
            true
        }
    }

    // ---------- UiAutomator helpers (the suite's proven patterns) ---------

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

    /**
     * Taps through the SHELL, never UiObject2.click(): the latter goes through
     * UiAutomator's InteractionController, which waits for an accessibility-idle
     * window and TIMES OUT on a busy screen -- the app receives nothing.
     */
    private fun clickSmart(node: UiObject2): Boolean {
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

    /**
     * A slow controlled scroll: the CI emulator is 320x640 mdpi and the Page
     * Actions sheet runs past the fold, so "Translate" is not in the tree until
     * the sheet has been scrolled.
     */
    private fun dragUpQuarter() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 100
        )
        device.waitForIdle(800)
        try { Thread.sleep(300) } catch (_: InterruptedException) { }
    }

    /**
     * True when a modal from THIS flow is on screen. Back is only ever sent for
     * one of these: a Back aimed at a screen that is merely slow to draw would
     * finish the activity instead.
     */
    private fun modalUp(): Boolean =
        device.findObjects(By.text("Page Actions")).isNotEmpty() ||
            device.findObjects(By.text("Translate this page?")).isNotEmpty()

    /**
     * Opens the Page Actions sheet and clicks [desc], scrolling for it, until
     * [verify] holds -- a tap can land on a row that is still settling, and the
     * check runs BEFORE each tap so an effect that already happened is not
     * re-triggered.
     */
    private fun openSheetEntry(desc: String, verify: () -> Boolean): Boolean {
        for (round in 1..2) {
            if (verify()) return true
            if (!hasDesc("Page actions and settings", 1_500) && modalUp()) {
                device.pressBack()
                device.waitForIdle(1_000)
            }
            if (!clickDesc("Page actions and settings", 6_000)) continue
            for (attempt in 1..8) {
                if (verify()) return true
                val node = device.wait(Until.findObject(By.desc(desc)), 2_000)
                if (node != null) {
                    clickSmart(node)
                    if (verify()) return true
                    runCatching { clickCenter(node) }
                    if (verify()) return true
                }
                dragUpQuarter()
            }
        }
        return verify()
    }

    private fun openTranslateDialog(): Boolean =
        openSheetEntry("Translate") { hasText("Translate this page?", 3_000) }

    /** The sheet's New tab, confirmed by the row it writes, not by the screen. */
    private fun openStartPageTab(): Boolean {
        val before = openTabs().count { it.url == START_PAGE }
        return openSheetEntry("New tab") {
            waitUntil(3_000) { openTabs().count { it.url == START_PAGE } > before }
        }
    }

    /**
     * Cold-starts the engine at [url] through the same extra the app's own
     * launcher uses, so the page reaches the engine the way a real link does.
     * The first engine activity after a profile switch SELF-RESTARTS, so the
     * second attempt is the one that lands.
     */
    private fun openPage(url: String): Boolean {
        for (attempt in 1..2) {
            launchEngine(url)
            if (hasText(marker, 30_000)) return true
            device.waitForIdle(2_000)
        }
        return hasText(marker, 5_000)
    }

    private fun launchEngine(url: String? = null) {
        val intent = Intent()
            .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        url?.let { intent.putExtra("com.roombrowser.extra.INITIAL_URL", it) }
        targetContext.startActivity(intent)
    }

    private fun snap(name: String) {
        runCatching {
            device.executeShellCommand("mkdir -p /sdcard/e2e-shots")
            device.executeShellCommand("screencap -p /sdcard/e2e-shots/$name.png")
        }
    }

    private fun diagnostics(): String = try {
        val texts = runCatching {
            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(40)
        }.getOrDefault(emptyList())
        val tabs = runCatching { openTabs().map { it.url } }.getOrDefault(emptyList())
        "VISIBLE TEXTS: $texts\nTAB ROWS: $tabs"
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }

    private companion object {
        const val START_PAGE = "about:home"
    }
}
