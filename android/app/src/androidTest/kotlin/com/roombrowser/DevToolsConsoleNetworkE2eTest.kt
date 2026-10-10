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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E for the Developer Tools LIVE FEEDS.
 *
 * WHAT THIS PROVES THAT UNIT TESTS CANNOT. The feed objects and the caption
 * arithmetic are pinned by JVM tests; what only a real emulator can show is the
 * seam in between -- that a page's own `console.*` output and a request the page
 * makes actually ARRIVE at the panel's feed from a live engine, through the
 * console bridge and the network sink, and are drawn on screen once the panel
 * scrolls to them. A feed that never receives anything passes every unit test
 * and is still a broken panel.
 *
 * WHY THE PAGE KEEPS RE-REQUESTING. The engine's network sink is armed only
 * once the Network panel composes, so a single fetch at document load can be
 * over before anything is listening. The fixture therefore beats the fetch on an
 * interval, which guarantees a request happens while the panel is open; the
 * panel's own "Ask the page what it loaded" control is driven as well, so the
 * page-timing pull is exercised (and must settle) on the same run.
 *
 * The panels render in the SAME activity window (not a modal window), so unlike
 * a permission sheet they do not hide the WebView from UiAutomator -- but the
 * Page Actions sheet that opens them IS modal, so it is asserted before
 * anything else, exactly as this suite's other sheet tests do.
 */
@RunWith(AndroidJUnit4::class)
class DevToolsConsoleNetworkE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer

    // Per-run markers, so a dirty device cannot answer for this run.
    private val tag = (System.currentTimeMillis() % 100000).toString()
    private val pageHeading = "ROOM-DEVTOOLS-PAGE-$tag"
    private val logMarker = "room-console-log-$tag"
    private val errorMarker = "room-console-error-$tag"
    private val netPath = "/net-$tag"

    @Before
    fun setUp() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                return when {
                    path.startsWith("/page-") -> MockResponse()
                        .setHeader("Content-Type", "text/html; charset=utf-8")
                        .setHeader("Cache-Control", "no-store")
                        .setBody(fixturePage())
                    path.startsWith("/net-") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/plain; charset=utf-8")
                        .setHeader("Cache-Control", "no-store")
                        .setBody("ok")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /** The fixture page: two console calls, then a fetch that keeps repeating. */
    private fun fixturePage(): String = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        </head><body style="font-size:24px; margin:24px;">
        <h1>$pageHeading</h1>
        <script>
          console.log('$logMarker');
          console.error('$errorMarker');
          function beat() {
            try { fetch('$netPath', { cache: 'no-store' }).then(function () {}, function () {}); }
            catch (e) {}
          }
          beat();
          setInterval(beat, 700);
        </script>
        </body></html>
    """.trimIndent()

    // ---------- The contract ------------------------------------------------

    @Test
    fun a_pages_console_log_reaches_the_panel_feed() {
        assertTrue(
            "A profile must be active before the engine can be cold-started\n${uiTree()}",
            ensureActiveProfile()
        )
        assertTrue(
            "The fixture page must render before Developer Tools can inspect it\n${uiTree()}",
            openPage(server.url("/page-$tag").toString())
        )
        assertTrue(
            "Developer Tools must open from the Page Actions sheet\n${uiTree()}",
            openDeveloperToolsPanel()
        )
        // The feed rows sit far below the panel header, so a marker is found by
        // scrolling the panel body, never by a bare first lookup.
        assertTrue(
            "The page's console.log marker must reach the console feed\n${uiTree()}",
            scrollPanelToText(logMarker)
        )
        assertTrue(
            "The page's console.error marker must reach the console feed too\n${uiTree()}",
            scrollPanelToText(errorMarker)
        )
    }

    @Test
    fun a_pages_request_reaches_the_network_feed() {
        assertTrue(
            "A profile must be active before the engine can be cold-started\n${uiTree()}",
            ensureActiveProfile()
        )
        assertTrue(
            "The fixture page must render before Developer Tools can inspect it\n${uiTree()}",
            openPage(server.url("/page-$tag").toString())
        )
        assertTrue(
            "Developer Tools must open from the Page Actions sheet\n${uiTree()}",
            openDeveloperToolsPanel()
        )

        // The refresh control is the app's own ask-the-page path. Pressing it
        // must land, produce a pull state and leave the panel standing; none of
        // that touches the page's own document.
        val refresh = findDescScrolling("Ask the page what it loaded")
        assertTrue(
            "The network panel's 'Ask the page what it loaded' control must be reachable\n${uiTree()}",
            refresh != null
        )
        assertTrue(
            "The refresh control must be pressable\n${uiTree()}",
            clickSmart(refresh!!)
        )
        assertTrue(
            "The page-timing pull must settle into a result rather than crash the panel\n${uiTree()}",
            scrollForAnyText(
                listOf(
                    "Asking the page what it loaded",
                    "The page reported",
                    "timing data was not read"
                ),
                maxDrags = 6
            )
        )

        // Either the engine's own request sink or the page-timing pull puts the
        // requested path into the SAME feed; both are the panel showing it.
        assertTrue(
            "A request the page made must reach the network feed\n${uiTree()}",
            scrollPanelToText(netPath, maxDrags = 24)
        )
    }

    // ---------- The state this test needs -----------------------------------

    /**
     * Makes sure SOME profile is active, through the repositories the UI itself
     * uses. A00WarmupTest deliberately leaves the app with no profile, so a
     * filtered dispatch has to mint one; a full run reuses whatever the earlier
     * suites left active.
     */
    private fun ensureActiveProfile(): Boolean {
        val graph = (targetContext.applicationContext as RoomBrowserApp).graph
        return runBlocking {
            val active = graph.appState.activeProfileIdSnapshot()
            val id = active ?: graph.profileManager
                .create("E2E DevTools ${System.currentTimeMillis()}", "👤", 0xFF7C4DFFL)
                .id.value
            graph.appState.setActiveProfile(id)
            true
        }
    }

    /**
     * Cold-starts the engine at [url] through the same extra the app's own
     * launcher uses. The first engine activity after a profile switch
     * SELF-RESTARTS, so the second attempt is the one that lands.
     */
    private fun openPage(url: String): Boolean {
        for (attempt in 1..2) {
            launchEngine(url)
            if (hasText(pageHeading, 30_000)) return true
            device.waitForIdle(2_000)
        }
        return hasText(pageHeading, 5_000)
    }

    private fun launchEngine(url: String) {
        targetContext.startActivity(
            Intent()
                .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("com.roombrowser.extra.INITIAL_URL", url)
        )
    }

    // ---------- UiAutomator helpers (the suite's proven patterns) -----------

    private fun hasText(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs)

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

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

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    /**
     * A slow half-screen drag (3/4 -> 1/4). The panel body is a vertically
     * scrollable Column, so this is how a row below the fold is reached; the
     * soft IME is never raised here, so the drag is not swallowed.
     */
    private fun dragUpHalf() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 100
        )
        device.waitForIdle(600)
        try { Thread.sleep(200) } catch (_: InterruptedException) { }
    }

    /**
     * Scrolls the panel body until [text] appears, checking BEFORE each drag so
     * evidence already on screen is never scrolled past.
     */
    private fun scrollPanelToText(text: String, maxDrags: Int = 24): Boolean {
        repeat(maxDrags) {
            if (device.findObjects(By.textContains(text)).isNotEmpty()) return true
            dragUpHalf()
        }
        return device.findObjects(By.textContains(text)).isNotEmpty()
    }

    /** Scroll-aware presence check for any one of [candidates]. */
    private fun scrollForAnyText(candidates: List<String>, maxDrags: Int): Boolean {
        repeat(maxDrags) {
            if (candidates.any { device.findObjects(By.textContains(it)).isNotEmpty() }) return true
            dragUpHalf()
        }
        return candidates.any { device.findObjects(By.textContains(it)).isNotEmpty() }
    }

    /**
     * Scrolls the panel body (and, first, the Page Actions sheet) looking for a
     * node by contentDescription. A node is only returned when it is worth
     * tapping: a partly-clipped node reports a non-empty rect, so its center is
     * checked against the panel's band rather than trusted.
     */
    private fun findDescScrolling(desc: String, maxDrags: Int = 24): UiObject2? {
        repeat(maxDrags) {
            val node = device.findObjects(By.desc(desc)).firstOrNull()
            if (node != null && isTappable(node)) return node
            dragUpHalf()
        }
        return device.findObjects(By.desc(desc)).firstOrNull()
    }

    private fun isTappable(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        b.width() > 0 && b.centerY() > device.displayHeight / 4 &&
            b.centerY() < device.displayHeight - 20
    } catch (_: Exception) {
        false
    }

    /**
     * Opens the Page Actions sheet and clicks the Developer tools row, scrolling
     * the sheet for it. The sheet is asserted first: it is a modal window, and
     * UiAutomator only returns nodes from the active window.
     */
    private fun openDeveloperToolsPanel(): Boolean {
        // A panel left open by an earlier test method would cover the chrome it
        // is opened from, so it is closed first rather than assumed away.
        device.findObjects(By.desc("Close developer tools")).firstOrNull()?.let {
            clickSmart(it)
            device.waitForIdle(1_000)
        }
        assertTrue(
            "The browser chrome must be up before its sheet can be opened\n${uiTree()}",
            hasDesc("Page actions and settings", 30_000)
        )
        if (!clickDesc("Page actions and settings", 6_000)) return false
        assertTrue(
            "The Page Actions sheet must be on screen before any row in it is tapped\n${uiTree()}",
            hasText("Page Actions", 6_000)
        )
        repeat(12) {
            val row = device.findObjects(By.desc("Developer tools")).firstOrNull()
            if (row != null && clickSmart(row) && hasText("What this edition can inspect", 8_000)) {
                return true
            }
            dragUpHalf()
        }
        return hasText("What this edition can inspect", 3_000)
    }

    // ---------- Failure diagnostics -----------------------------------------

    /** Readable failure diagnostics, built at the moment of failure. */
    private fun uiTree(): String = try {
        val sb = StringBuilder()
        val probes = listOf(
            "'Page Actions' sheet" to By.text("Page Actions"),
            "'Developer tools' row" to By.desc("Developer tools"),
            "'What this edition can inspect' header" to By.text("What this edition can inspect"),
            "refresh control" to By.desc("Ask the page what it loaded"),
            "console clear control" to By.desc("Clear the console feed"),
            "console log marker" to By.textContains(logMarker),
            "console error marker" to By.textContains(errorMarker),
            "requested path" to By.textContains(netPath)
        )
        for ((label, selector) in probes) {
            val nodes = runCatching { device.findObjects(selector) }.getOrDefault(emptyList())
            sb.append(label).append(": count=").append(nodes.size).append('\n')
        }
        val texts = runCatching {
            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(80)
        }.getOrDefault(emptyList())
        sb.append("VISIBLE TEXTS: ").append(texts)
        sb.toString().take(8000)
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }
}
