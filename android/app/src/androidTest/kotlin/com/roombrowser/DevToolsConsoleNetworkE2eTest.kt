package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
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
 * WHY THE PAGE KEEPS RE-REQUESTING. The engine holds a request that arrives
 * before the Network panel composes and replays it when the panel opens, so the
 * document itself is reachable either way. The fixture still beats its fetch on
 * an interval, because a replayed request proves the buffer and only a request
 * made WHILE the panel is open proves the live path -- and the panel's own "Ask
 * the page what it loaded" control is driven as well, so the page-timing pull is
 * exercised (and must settle) on the same run.
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

    /** The first thing in the panel body, and so the marker for "the body is here". */
    private val bodyHeader = "Console"

    /**
     * How long a cleared feed is given to leave the tree. Recomposing off the
     * revision is synchronous, so this is scheduling headroom, not a race the
     * app is allowed to lose slowly.
     */
    private val clearSettleMs = 10_000L

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

        // The docked slot on this window is ~405dp of body and the CONSOLE feed
        // renders above this one, so the network rows land at the fold -- and
        // Compose prunes a clipped-out node from the accessibility tree, which
        // makes "never rendered" and "rendered one row too low" the same
        // observation from here. The panel has a real full-screen control for
        // exactly this, so the test asks it for the room rather than hoping a
        // synthetic drag scrolls a Compose list.
        assertTrue(
            "The panel's own full-screen control must give the network feed room to be read\n${uiTree()}",
            expandToFullScreen()
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

    /**
     * The clear control is a revision bump, not a ring mutation.
     *
     * The panel caches `remember(consoleRevision) { console.snapshot() }`, so a
     * clear that only emptied the ring would leave every row it removed on
     * screen until the next entry arrived — a button that looks broken, and one
     * that a unit test on the ring cannot catch, because the ring really is
     * empty. Absence on the DEVICE is the only assertion that pins the fix.
     */
    @Test
    fun clearing_the_console_feed_actually_empties_it() {
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
        // A clear only means something once there is something to clear, so the
        // feed is proven populated BEFORE the control is pressed.
        assertTrue(
            "The feed must be populated before a clear can remove anything\n${uiTree()}",
            scrollPanelToText(logMarker)
        )

        val clear = scrollPanelBackToDesc("Clear the console feed")
        assertTrue(
            "The console panel's clear control must be reachable\n${uiTree()}",
            clear != null
        )
        assertTrue(
            "The clear control must be pressable\n${uiTree()}",
            clickSmart(clear!!)
        )
        assertTrue(
            "Clearing the console feed must remove the rows it cleared\n${uiTree()}",
            waitUntilAbsent(By.textContains(logMarker), clearSettleMs)
        )
        assertTrue(
            "Clearing must empty the whole feed, not just the first row\n${uiTree()}",
            waitUntilAbsent(By.textContains(errorMarker), clearSettleMs)
        )
    }

    /**
     * Answers three questions the feed tests cannot, without failing early.
     *
     * Every feed assertion in this class looks for something BELOW THE FOLD of
     * a 320x640 window. Compose prunes a clipped-out node from the
     * accessibility tree, so from the test's side "the panel never rendered
     * this" and "the panel rendered it below the fold and the drag did not move
     * the body" are the SAME observation -- both are count=0. Those are two
     * different defects in two different files, so this reports the three facts
     * separately instead of guessing which one is in play:
     *
     *   1. Does the panel take touch? The header controls are above the fold in
     *      every dock and no suite has ever pressed one.
     *   2. Do the injected drags every feed assertion relies on move the body?
     *   3. Does the accessibility scroll ACTION move it, which asks the
     *      scrollable node to scroll instead of injecting a gesture at a
     *      coordinate?
     *
     * The report is printed as well as asserted, so a passing run still records
     * what it measured.
     */
    @Test
    fun the_devtools_panel_takes_touch_and_its_body_scrolls() {
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

        val report = StringBuilder()
        report.append("display=").append(device.displayWidth).append('x')
            .append(device.displayHeight).append('\n')

        val minimize = findDescNow("Minimize")
        report.append("minimize control present=").append(minimize != null).append('\n')
        report.append("close control bounds=")
            .append(findDescNow("Close developer tools")?.visibleBounds).append('\n')
        val collapsed = minimize != null && clickSmart(minimize) &&
            waitUntilAbsent(By.text(bodyHeader), 8_000)
        report.append("minimize collapsed the body=").append(collapsed).append('\n')
        val restored = collapsed && findDescNow("Restore")?.let { clickSmart(it) } == true &&
            hasText(bodyHeader, 8_000)
        report.append("restore brought it back=").append(restored).append('\n')

        val before = visibleTexts()
        repeat(4) { dragUpHalf() }
        val afterDrags = visibleTexts()
        val dragMoved = before != null && afterDrags != null && before != afterDrags
        report.append("injected drags moved the body=").append(dragMoved).append('\n')

        val steps = a11yScrollReport(report)
        report.append("a11y scroll steps total=").append(steps).append('\n')
        val afterA11y = visibleTexts()
        val a11yMoved = before != null && afterA11y != null && before != afterA11y
        report.append("a11y scroll moved the body=").append(a11yMoved).append('\n')
        val feedsOnScreen = hasText("Console", 1_000) && hasText("Network", 1_000)
        report.append("both feed headers visible=").append(feedsOnScreen).append('\n')
        report.append("before=").append(before).append('\n')
        report.append("afterDrags=").append(afterDrags).append('\n')
        report.append("afterA11y=").append(afterA11y).append('\n')
        println(report)

        assertTrue(
            "The panel must take touch -- minimizing and restoring it is the cheapest proof, " +
                "and the header is the only part of this panel any suite has ever pressed.\n" +
                report,
            collapsed && restored
        )
        assertTrue(
            "The panel's own content has to be reachable: either something below the fold can " +
                "be reached (an injected drag or the accessibility scroll action moving the " +
                "body), or both feed sections are on screen without scrolling at all. A failed " +
                "text probe proves neither, so it counts for neither.\n" + report,
            dragMoved || a11yMoved || steps > 0 || feedsOnScreen
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

    /**
     * Docks the panel to the whole window through its own control.
     *
     * Confirmed by the control flipping to "Dock" rather than by the press
     * alone: a tap that missed would otherwise read as a layout that simply did
     * not change.
     */
    private fun expandToFullScreen(): Boolean {
        val node = findDescNow("Full screen") ?: return false
        if (!clickSmart(node)) return false
        return hasDesc("Dock", 5_000)
    }

    /** A presence check with no wait at all, for the report's own probes. */
    private fun findDescNow(desc: String): UiObject2? =
        runCatching { device.findObjects(By.desc(desc)).firstOrNull() }.getOrNull()

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
     * Every text string currently in the active window, deduped and SORTED, or
     * NULL when the probe itself failed.
     *
     * The empty `textContains` matches any node that has text at all, which is
     * the only selector this UiAutomator version offers for "everything" — the
     * sort is what makes two captures comparable as SETS, so a mere reorder is
     * not mistaken for the body having moved.
     *
     * A failure returns null rather than an empty list on purpose. `findObjects`
     * does throw here (seen after a drag), and an empty list read as "the window
     * has no text" made `before != afterDrags` TRUE for exactly the wrong
     * reason: two failed probes compared as a body that moved. Absence of a
     * reading is not a reading.
     */
    private fun visibleTexts(): List<String>? = try {
        device.findObjects(By.textContains(""))
            .mapNotNull { it.text }
            .distinct()
            .sorted()
    } catch (_: Exception) {
        null
    }

    /**
     * The accessibility scroll ACTION, not an injected gesture.
     *
     * [dragUpHalf] hands a coordinate to the input system and hopes the panel's
     * scrollable claims it; this asks a scrollable NODE itself to scroll, which
     * is how UiAutomator is meant to move a Compose list.
     *
     * Every scrollable instance is probed in turn and reported separately,
     * because the live WebView is a scrollable node too: a single "the scroll
     * worked" would not say WHICH node moved, and scrolling the page instead of
     * the panel would look identical from here.
     */
    private fun a11yScrollReport(report: StringBuilder): Int {
        var total = 0
        for (index in 0 until 4) {
            val scroller = UiScrollable(UiSelector().scrollable(true).instance(index))
            val first = runCatching { scroller.scrollForward() }
            if (first.isFailure) {
                report.append("scrollable[").append(index).append("]: none (")
                    .append(first.exceptionOrNull()?.javaClass?.simpleName).append(")\n")
                return total
            }
            var taken = if (first.getOrDefault(false)) 1 else 0
            while (taken in 1 until 8 &&
                runCatching { scroller.scrollForward() }.getOrDefault(false)
            ) {
                taken++
                device.waitForIdle(400)
            }
            report.append("scrollable[").append(index).append("]: took ")
                .append(taken).append(" steps\n")
            total += taken
        }
        return total
    }

    /**
     * The reverse of [dragUpHalf], used to come back up to the panel's own
     * header after reaching a row below the fold.
     *
     * Both endpoints stay INSIDE the panel body on purpose: the panel is a
     * bottom-anchored surface, so a drag starting above its top edge would land
     * on the sheet's drag handle or its scrim and could dismiss the panel
     * instead of scrolling it.
     */
    private fun dragDownHalf() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight / 2,
            device.displayWidth / 2, device.displayHeight * 9 / 10, 100
        )
        device.waitForIdle(600)
        try { Thread.sleep(200) } catch (_: InterruptedException) { }
    }

    /** Scrolls back up the panel body until [desc] is worth tapping, checking before each drag. */
    private fun scrollPanelBackToDesc(desc: String, maxDrags: Int = 24): UiObject2? {
        repeat(maxDrags) {
            val node = device.findObjects(By.desc(desc)).firstOrNull()
            if (node != null && isTappable(node)) return node
            dragDownHalf()
        }
        return device.findObjects(By.desc(desc)).firstOrNull()
    }

    /**
     * Waits for [selector] to leave the tree.
     *
     * Written as an explicit poll rather than `Until.gone` so the assertion uses
     * only the UiAutomator surface this suite already proves on device:
     * androidTest compiles in no job but `e2e`, so a call that does not exist in
     * the pinned version costs a full cycle to discover.
     */
    private fun waitUntilAbsent(selector: BySelector, timeoutMs: Long): Boolean {
        fun present() = device.findObjects(selector).isNotEmpty()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!present()) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return !present()
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
            if (row != null && clickSmart(row) && hasText(bodyHeader, 8_000)) {
                return true
            }
            dragUpHalf()
        }
        return hasText(bodyHeader, 3_000)
    }

    // ---------- Failure diagnostics -----------------------------------------

    /** Readable failure diagnostics, built at the moment of failure. */
    private fun uiTree(): String = try {
        val sb = StringBuilder()
        val probes = listOf(
            "'Page Actions' sheet" to By.text("Page Actions"),
            "'Developer tools' row" to By.desc("Developer tools"),
            "'What this edition can inspect' header" to By.text("What this edition can inspect"),
            "console section header" to By.text(bodyHeader),
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
