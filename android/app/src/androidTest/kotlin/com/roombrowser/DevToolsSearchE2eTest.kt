package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.roombrowser.domain.model.ProfileId
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
 * E2E for the Developer Tools SEARCH panel.
 *
 * WHAT THIS PROVES THAT UNIT TESTS CANNOT. The probe's shape, its escaping and
 * its decode are pinned by `DeveloperToolsSearchTest` as text; what only a real
 * engine can show is the seam in between -- that the script the panel builds
 * actually ROUND-TRIPS through `evaluateJs`, that the JSON it stringifies in the
 * page decodes into the hits the panel was written against, and that those hits
 * are drawn on screen once the panel scrolls to them. A probe that never
 * answers passes every text assertion and is still a broken panel.
 *
 * THE BUFFER GROUPS ARE THE SURFACE'S OWN STATE. The Search panel deliberately
 * starts no capture of its own: it searches the console and network buffers
 * this surface ALREADY holds, so the buffer test opens Console and Network
 * first -- through the same strip the reader would -- and only then searches.
 * That ordering is the behavior under test, not scaffolding: a search that
 * silently started its own captures would be a second, competing capture.
 *
 * The typing goes through the shell's `input text` and the taps through
 * coordinate injection, exactly as this suite's other field-driven tests do;
 * the IME is settled away before any control is pressed, because the controls
 * move when the keyboard goes.
 */
@RunWith(AndroidJUnit4::class)
class DevToolsSearchE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer

    // Per-run markers, so a dirty device cannot answer for this run.
    private val tag = (System.currentTimeMillis() % 100000).toString()
    private val pageHeading = "ROOM-DEVTOOLS-SEARCH-$tag"

    /** The fixture's one named global, whose VALUE carries this run's tag. */
    private val globalName = "roomSearchGlobal"

    /** The fixture's one storage key, whose value only the reveal may show. */
    private val storageKey = "room-search-key-$tag"
    private val storageValue = "$tag-value"

    /** A console marker logged once at load, and a path the page keeps fetching. */
    private val logMarker = "room-search-log-$tag"
    private val netPath = "/net-$tag"

    /** The strip chip's announcement, and the marker for "the surface is open". */
    private val searchChip = "Search panel"

    /**
     * Only the Search panel draws this, so it is the marker for "the Search
     * panel COMPOSED" as opposed to "the strip is on screen" -- the run button
     * the assertions press, tagged for exactly that.
     */
    private val searchBody = "devtools_search_run"

    /** The always-present chip, used as the marker that the surface opened at all. */
    private val overviewChip = "Overview panel"

    private val consoleChip = "Console panel"
    private val consoleBody = "Clear the console feed"

    private val networkChip = "Network panel"
    private val networkBody = "Ask the page what it loaded"

    /** Where tearDown leaves the browser, so the next suite starts on it too. */
    private val homePage = "about:home"

    /**
     * Which step of the sheet walk failed, for the assertion that follows it. A
     * probe dump taken afterwards cannot answer this: the walk dismisses the
     * sheet on its way through.
     */
    private var openTrace: String = ""

    /** What the last panel walk did, so a failure names the mechanism, not just the miss. */
    private var scrollTrace: String = ""

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
        // Return the browser to about:home BEFORE the server goes away: every page
        // this suite opened lives on in the profile as a persisted tab, and the next
        // class cold-starts on the one that was viewed last.
        runCatching { launchEngine(homePage) }
        runCatching { awaitHomeTab() }
        runCatching { closeFixtureTabs() }
        runCatching { server.shutdown() }
    }

    // ---------- The contract ------------------------------------------------

    /**
     * One query finds the page's own variable, its storage key, and its own
     * heading -- the three halves of the ask (a variable, a string, anything).
     *
     * The variable is found through its VALUE, which is the case the panel
     * exists for: the reader knows a string, not a name. The storage row is the
     * key, never the value. The element hit is asserted through its SECTION
     * HEADER, because the heading itself is also the live page's own text and a
     * bare text match would prove nothing about the panel.
     */
    @Test
    fun the_search_panel_finds_a_global_a_storage_key_and_page_text() {
        assertTrue(
            "A profile must be active before the engine can be cold-started\n${uiTree()}",
            ensureActiveProfile()
        )
        assertTrue(
            "The fixture page must render before Developer Tools can inspect it\n${uiTree()}",
            openPage(server.url("/page-$tag").toString())
        )
        assertDevToolsOpened(searchChip, searchBody)

        assertTrue(
            "The tag must land in the search field before the probe can be asked\n${uiTree()}",
            runSearch(tag)
        )

        assertTrue(
            "The page's own global must be found by the value it holds\n$scrollTrace\n${uiTree()}",
            scrollPanelToText(globalName)
        )
        assertTrue(
            "The page's storage key must be found by the key it is\n$scrollTrace\n${uiTree()}",
            scrollPanelToText(storageKey)
        )
        assertTrue(
            "The element the page shows must be found, and only the panel draws this header\n" +
                "$scrollTrace\n${uiTree()}",
            scrollPanelToText("Page text and elements (")
        )
    }

    /**
     * Tapping a storage hit reveals its value in a dialog, through the one
     * reveal path this module keeps.
     *
     * The value never travels with the probe: the row shows the key and where
     * it matched, and the value exists on screen only after this tap. The
     * assertion text is the value itself, which nothing else on this run
     * renders.
     */
    @Test
    fun tapping_a_storage_hit_reveals_its_value_in_a_dialog() {
        assertTrue(
            "A profile must be active before the engine can be cold-started\n${uiTree()}",
            ensureActiveProfile()
        )
        assertTrue(
            "The fixture page must render before Developer Tools can inspect it\n${uiTree()}",
            openPage(server.url("/page-$tag").toString())
        )
        assertDevToolsOpened(searchChip, searchBody)

        assertTrue(
            "The tag must land in the search field before the probe can be asked\n${uiTree()}",
            runSearch(tag)
        )
        assertTrue(
            "The storage row must be on screen before it can be tapped\n$scrollTrace\n${uiTree()}",
            scrollPanelToText(storageKey)
        )
        val row = device.findObjects(By.textContains(storageKey)).firstOrNull()
        assertTrue(
            "The storage row must resolve to a node worth tapping\n${uiTree()}",
            row != null
        )
        assertTrue(
            "Tapping the storage row must land\n${uiTree()}",
            clickSmart(row!!)
        )
        assertTrue(
            "The revealed value must reach the dialog, and nothing else shows it\n${uiTree()}",
            device.wait(Until.hasObject(By.textContains(storageValue)), 10_000)
        )
        // Close through the dialog's own control, so the next assertion sees the
        // panel rather than whatever a dismissed dialog left behind.
        device.findObjects(By.text("Close")).firstOrNull()?.let { clickSmart(it) }
        device.waitForIdle(1_000)
    }

    /**
     * A query with no matches says so once, in the reader's own words.
     *
     * A POSITIVE CONTROL RUNS FIRST, on the same page and the same panel: a
     * "no matches" line on a panel whose probe never answered would be the same
     * sentence, and only a hit before the miss separates them.
     */
    @Test
    fun a_query_with_no_matches_says_so_once() {
        assertTrue(
            "A profile must be active before the engine can be cold-started\n${uiTree()}",
            ensureActiveProfile()
        )
        assertTrue(
            "The fixture page must render before Developer Tools can inspect it\n${uiTree()}",
            openPage(server.url("/page-$tag").toString())
        )
        assertDevToolsOpened(searchChip, searchBody)

        assertTrue(
            "The control search must run first, so the miss is a real miss\n${uiTree()}",
            runSearch(tag)
        )
        assertTrue(
            "The control search must have found the page's global\n$scrollTrace\n${uiTree()}",
            scrollPanelToText(globalName)
        )

        val nothing = "no-such-thing-$tag"
        assertTrue(
            "The garbage query must land in the search field\n${uiTree()}",
            runSearch(nothing)
        )
        assertTrue(
            "A query with no matches must say so, in the words the panel pins\n$scrollTrace\n${uiTree()}",
            scrollPanelToText("No matches for \"$nothing\" on this page.")
        )
    }

    /**
     * The search reads the buffers this surface already holds.
     *
     * Console and Network are opened first, through the strip, so their feeds
     * fill; the Search panel then finds the marker and the request under the
     * one query. The section headers are the proof that the BUFFER groups
     * rendered -- they exist only when their category is non-empty.
     */
    @Test
    fun the_search_panel_reads_the_buffers_this_surface_already_holds() {
        assertTrue(
            "A profile must be active before the engine can be cold-started\n${uiTree()}",
            ensureActiveProfile()
        )
        assertTrue(
            "The fixture page must render before Developer Tools can inspect it\n${uiTree()}",
            openPage(server.url("/page-$tag").toString())
        )
        assertDevToolsOpened(consoleChip, consoleBody)
        assertTrue(
            "The page's console marker must reach the console feed first\n$scrollTrace\n${uiTree()}",
            awaitFeedRow(logMarker) ||
                scrollPanelToText(logMarker) ||
                recoveredFeedScroll(consoleChip, consoleBody, logMarker)
        )
        assertTrue(
            "The Network panel must compose before its feed can be read\n${uiTree()}",
            selectPanel(networkChip, networkBody)
        )
        assertTrue(
            "A request the page made must reach the network feed first\n$scrollTrace\n${uiTree()}",
            awaitFeedRow(netPath) ||
                scrollPanelToText(netPath) ||
                recoveredFeedScroll(networkChip, networkBody, netPath)
        )
        assertTrue(
            "The Search panel must compose again before it can be driven\n${uiTree()}",
            selectPanel(searchChip, searchBody)
        )

        assertTrue(
            "The tag must land in the search field before the probe can be asked\n${uiTree()}",
            runSearch(tag)
        )
        assertTrue(
            "The console buffer's marker must be found under the one query\n$scrollTrace\n${uiTree()}",
            scrollPanelToText(logMarker)
        )
        assertTrue(
            "The network buffer's request must be found under the one query\n$scrollTrace\n${uiTree()}",
            scrollPanelToText(netPath)
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
                .create("E2E DevTools Search ${System.currentTimeMillis()}", "👤", 0xFF7C4DFFL)
                .id.value
            graph.appState.setActiveProfile(id)
            true
        }
    }

    /**
     * Waits for the launch above to reach the profile. A request is not a
     * result: the next class starts its activity with CLEAR_TASK, which destroys
     * this one, so a home tab still unwritten when this returns is a launch that
     * never happened.
     */
    private fun awaitHomeTab() {
        val graph = (targetContext.applicationContext as RoomBrowserApp).graph
        repeat(30) {
            val onHome = runBlocking {
                val profileId = graph.appState.activeProfileIdSnapshot() ?: return@runBlocking false
                graph.browserRepo.openTabs(ProfileId(profileId)).any { it.url == homePage }
            }
            if (onHome) return
            device.waitForIdle(500)
        }
    }

    /**
     * Deletes the tabs this suite opened. They point at the MockWebServer this
     * method's caller is about to stop, and a tab left open on a dead server is
     * what the next class would then restore.
     */
    private fun closeFixtureTabs() {
        val graph = (targetContext.applicationContext as RoomBrowserApp).graph
        val base = server.url("/").toString()
        repeat(10) {
            val left = runBlocking {
                val profileId = graph.appState.activeProfileIdSnapshot() ?: return@runBlocking 0
                val stale = graph.browserRepo.openTabs(ProfileId(profileId)).filter { it.url.startsWith(base) }
                stale.forEach { graph.browserRepo.closeTab(it.id) }
                stale.size
            }
            if (left == 0) return
            Thread.sleep(500)
        }
    }

    /**
     * The fixture page: a named global holding the run's tag, one storage key,
     * a heading, one console marker logged at load, and a fetch that keeps
     * repeating so the network feed has something live to hold.
     */
    private fun fixturePage(): String = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        </head><body style="font-size:24px; margin:24px;">
        <h1>$pageHeading</h1>
        <script>
          window.$globalName = '$tag';
          try { localStorage.setItem('$storageKey', '$storageValue'); } catch (e) {}
          console.log('$logMarker');
          function beat() {
            try { fetch('$netPath', { cache: 'no-store' }).then(function () {}, function () {}); }
            catch (e) {}
          }
          beat();
          setInterval(beat, 700);
        </script>
        </body></html>
    """.trimIndent()

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

    // ---------- Driving the search ------------------------------------------

    /**
     * Types [text] into the query field and presses Search.
     *
     * The field is walked back to first: an earlier assertion leaves the body
     * scrolled wherever its own target sat, and a clipped-out field is a node
     * this driver cannot find at all. The run button is pressed only after the
     * IME is settled away, because the body resizes under the keyboard and a
     * tap on pre-shift bounds lands on nothing.
     */
    private fun runSearch(text: String): Boolean {
        var guard = 0
        while (device.findObjects(By.desc("devtools_search_field")).isEmpty() && guard++ < 16) {
            dragDownHalf()
        }
        for (round in 1..3) {
            hideImeIfNeeded()
            val field = device.wait(Until.findObject(By.desc("devtools_search_field")), 4_000)
                ?: continue
            clickSmart(field)
            device.executeShellCommand("input keyevent KEYCODE_MOVE_END")
            device.clearFocusedField()
            device.waitForIdle(400)
            device.executeShellCommand("input text " + text.replace(" ", "%s"))
            device.waitForIdle(800)
            if (device.wait(Until.hasObject(By.textContains(text)), 1_500)) break
            if (round == 3) return false
        }
        hideImeIfNeeded()
        device.waitForIdle(1_000)
        try {
            Thread.sleep(400)
        } catch (_: InterruptedException) {
            }
        return clickDesc("devtools_search_run", 5_000)
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

    /**
     * One small step down the Page Actions sheet.
     *
     * A half-screen swipe flings the sheet's column past the row being looked
     * for and leaves it at the bottom, where the row above the fold is gone
     * however many times the swipe is repeated. A short, slow drag moves the
     * list about as far as it travels and stops there.
     */
    private fun sheetScrollStep() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight * 5 / 8, 40
        )
        device.waitForIdle(400)
        try {
            Thread.sleep(150)
        } catch (_: InterruptedException) {
            }
    }

    /**
     * The panel body's own scrollable node.
     *
     * Compose exposes the body as an `android.widget.ScrollView`, and the live
     * page's WebView is scrollable too, so the class is what tells the two
     * apart.
     */
    private fun bodyNode(): UiObject? = (0 until 5).firstNotNullOfOrNull { i ->
        val node = runCatching { device.findObject(UiSelector().scrollable(true).instance(i)) }
            .getOrNull() ?: return@firstNotNullOfOrNull null
        val isBody = runCatching {
            node.exists() && node.className == "android.widget.ScrollView"
        }.getOrDefault(false)
        if (isBody) node else null
    }

    /** The body's measured rectangle, or null while it is not on screen. */
    private fun bodyRect(): android.graphics.Rect? =
        bodyNode()?.let { runCatching { it.visibleBounds }.getOrNull() }

    /**
     * A slow drag up the panel body, measured from the body's OWN bounds.
     *
     * The docked panel is only the bottom part of the window, so a drag computed
     * from the window's mid-line finishes ABOVE the body and moves nothing --
     * which reads exactly like a panel that cannot scroll.
     */
    private fun dragUpHalf() {
        val body = bodyRect()
        if (body == null || body.height() <= 0) {
            device.swipe(
                device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 2, 100
            )
        } else {
            val x = body.centerX()
            val from = body.top + body.height() * 4 / 5
            device.swipe(x, from, x, from - body.height() * 2 / 5, 60)
        }
        device.waitForIdle(600)
        try {
            Thread.sleep(200)
        } catch (_: InterruptedException) {
            }
    }

    /** The same drag the other way, for walking back to the top of the body. */
    private fun dragDownHalf() {
        val body = bodyRect() ?: return
        if (body.height() <= 0) return
        val from = body.top + body.height() / 5
        device.swipe(body.centerX(), from, body.centerX(), from + body.height() * 2 / 5, 60)
        device.waitForIdle(400)
        try {
            Thread.sleep(120)
        } catch (_: InterruptedException) {
            }
    }

    /**
     * Docks the panel to the whole window through its own control, confirmed by
     * the control flipping to "Dock" rather than by the press alone.
     */
    private fun expandToFullScreen(): Boolean {
        val node = findDescNow("Full screen") ?: return false
        if (!clickSmart(node)) return false
        return hasDesc("Dock", 5_000)
    }

    /** A presence check with no wait at all, for the walk's own probes. */
    private fun findDescNow(desc: String): UiObject2? =
        runCatching { device.findObjects(By.desc(desc)).firstOrNull() }.getOrNull()

    /**
     * Walks the panel body until [text] appears, checking BEFORE each move so
     * evidence already on screen is never scrolled past.
     *
     * The body is walked from the TOP every time, because an earlier assertion
     * leaves it scrolled wherever its own target sat. A docked body is only the
     * bottom part of the window, so a target near the bottom costs many short
     * movings; taking the panel full screen and walking again is the fallback,
     * and [scrollTrace] records which mechanism the body answered to.
     */
    /**
     * The one failure this suite cannot otherwise survive on the gecko
     * edition: the engine recycles its content process mid-test (the fixture
     * page's repeating fetch is the load that triggers it), and the window's
     * accessibility tree stays null-rooted for minutes after it - every node
     * query and every input injection pays a full cross-process timeout, and
     * a scroll hunt through that state is a crawl this suite once measured at
     * an hour. A fresh activity is the one heal this suite can drive: the
     * engine relaunches, the page reloads, its fetch loop keeps feeding the
     * buffers, and the panels re-open on a tree that answers. This test alone
     * earns the recovery because it alone holds its panels open across a
     * multi-minute walk while that recycle lands.
     *
     * TWO relaunches, not one: after the 2026-10 runner image change the
     * crawl outlasted a single fresh activity twice in a row (CI run
     * 40070510408, attempts 1 and 2: the network feed's row was never found,
     * and the failure's own tree probe reported "the window was
     * mid-transition"), so the first relaunch can come up while the tree is
     * still wedged. The second starts a full walk later, from a tree that
     * has had that much longer to settle, and the first attempt's heading
     * wait is doubled to 60 seconds for the same reason. Each attempt is
     * bounded by the walk budget, so the whole recovery costs minutes, not
     * the hour the un-walked crawl once did.
     */
    private fun recoveredFeedScroll(panelChip: String, panelBody: String, text: String): Boolean {
        repeat(2) { attempt ->
            restartEngineProcess()
            launchEngine(server.url("/page-$tag").toString())
            if (!hasText(pageHeading, if (attempt == 0) 60_000 else 30_000)) return@repeat
            if (!openDeveloperToolsPanel(consoleChip, consoleBody)) return@repeat
            if (panelChip != consoleChip && !selectPanel(panelChip, panelBody)) return@repeat
            // A relaunch re-arms every feed from scratch: the engine's
            // extension reconnects, the rings it held while nobody listened
            // flush, and only then does the row exist. A walk that starts
            // before that hunts an empty body, so the same bounded await runs
            // here first -- its expiry still reaches the walk below.
            awaitFeedRow(text)
            if (scrollPanelToText(text)) return true
        }
        return false
    }

    // ---------- Awaiting a feed event ---------------------------------------

    /**
     * How long a feed is given to deliver the event the next assertion hunts
     * for, counted from the moment the panel that arms it composed.
     *
     * Composing a panel only STARTS its capture. The arming message then
     * crosses to the engine's bridge extension, the extension flushes the
     * observations it held while nobody was listening, and the fixture's next
     * request lands only after that -- the page beats every 700ms, so an
     * event arrives on schedule, but ARRIVING is a property of the chain, not
     * of the panel being open. Every hop is fast; the whole chain is what a
     * panel-open is allowed to cost before the row exists at all. That is
     * exactly the ordering this suite's one CI failure lost: the scroll walk
     * began on a body the event had not reached, and the feed delivered the
     * row a moment after the assertion had already been decided. Waiting for
     * the event BEFORE walking is the fix at the root, not a delay bolted on:
     * the wait returns the moment the row is drawn, and its expiry still
     * reaches the walk and the engine-restart recovery below, so a genuinely
     * broken feed still fails the assertion.
     */
    private val feedEventTimeoutMs = 20_000L

    /**
     * The await itself: [Until.hasObject] against a deadline, this file's
     * existing wait form (the reveal dialog uses the same call). A row can
     * only be drawn by the feed, so a hit here IS the event having arrived;
     * a miss says "not yet" and hands the decision to the caller's fallbacks.
     */
    private fun awaitFeedRow(text: String): Boolean =
        device.wait(Until.hasObject(By.textContains(text)), feedEventTimeoutMs)

    /**
     * Kills the engine-bearing process so the next [launchEngine] is a cold
     * one.
     *
     * CLEAR_TASK alone was proven not to heal what this suite recovers
     * from: the activity is destroyed but the `:browser` process survives
     * it, and with the process survive both wedges the recovery exists for.
     * The network feed's extension port, dead since an earlier
     * content-process recycle, stays dead (CI 40070510408 attempts 1-2 and
     * 40083429230: one pid served every CLEAR_TASK relaunch, and the
     * Network panel's body composed empty each time while the console feed
     * — a separate path — carried on); the degraded accessibility window is
     * the same process's window. A cold process restores exactly the
     * conditions the feed demonstrably works under, which the suite's own
     * early tests prove on every run. `run-as` is available because the
     * build under test is debuggable, and a kill that cannot happen (pidof
     * misses, run-as refuses) degrades to the plain relaunch — the
     * recovery this suite has always had, only with a higher floor.
     */
    private fun restartEngineProcess() {
        runCatching {
            val pids = device.executeShellCommand(
                "pidof ${targetContext.packageName}:browser"
            ).trim()
            if (pids.isNotEmpty()) {
                device.executeShellCommand(
                    "run-as ${targetContext.packageName} sh -c 'kill $pids'"
                )
            }
        }
        device.waitForIdle(1_000)
    }

    private fun scrollPanelToText(text: String, maxDrags: Int = 24): Boolean {
        // A window mid-recreation can leave its accessibility nodes null-rooted
        // for a long moment, and in that state every node query and every
        // input injection pays a full cross-process timeout - 24 drags have
        // measured at an hour. The budget bounds the whole hunt: past it the
        // honest miss returns (the caller's assertion fails with the trace),
        // and the suite's own second invocation walks a fresh tree.
        val budgetDeadline = System.currentTimeMillis() + 90_000
        fun overBudget(): Boolean {
            if (System.currentTimeMillis() > budgetDeadline) {
                scrollTrace += "|budget"
                return true
            }
            return false
        }
        fun found() = device.findObjects(By.textContains(text)).isNotEmpty()
        scrollTrace = "body=${bodyRect()}"
        if (found()) {
            scrollTrace += "|onScreen"
            return true
        }
        repeat(maxDrags) {
            if (found()) return true
            if (overBudget()) return false
            dragDownHalf()
        }
        if (found()) {
            scrollTrace += "|upHit"
            return true
        }
        repeat(maxDrags) {
            if (found()) return true
            if (overBudget()) return false
            dragUpHalf()
        }
        if (found()) {
            scrollTrace += "|dragHit"
            return true
        }
        scrollTrace += "|dragMiss"
        if (!expandToFullScreen()) {
            scrollTrace += "|fullscreen=no"
            return found()
        }
        scrollTrace += "|fullscreen=yes"
        // Growing the panel does not move its scroll offset, so a target that was
        // below the docked fold can now sit ABOVE the taller viewport.
        repeat(maxDrags) {
            if (found()) return true
            if (overBudget()) return false
            dragDownHalf()
        }
        repeat(maxDrags + 12) {
            if (found()) return true
            if (overBudget()) return false
            dragUpHalf()
        }
        scrollTrace += "|end"
        return found()
    }

    // ---------- Developer Tools: opening it (proven pattern) ----------------

    private fun assertDevToolsOpened(chip: String, body: String) {
        // The helper runs BEFORE the message is built. Kotlin evaluates the
        // arguments left to right, so a probe dump written into the message of
        // the call it is reporting on is taken before the walk has moved.
        val opened = openDeveloperToolsPanel(chip, body)
        assertTrue(
            "Developer Tools must open from the Page Actions sheet — $openTrace\n${uiTree()}",
            opened
        )
    }

    private fun openDeveloperToolsPanel(chip: String, body: String): Boolean {
        openTrace = "start;"
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
        if (!clickDesc("Page actions and settings", 6_000)) {
            openTrace = "sheetTap=missed;"
            return false
        }
        assertTrue(
            "The Page Actions sheet must be on screen before any row in it is tapped\n${uiTree()}",
            hasText("Page Actions", 6_000)
        )
        // The row sits ~22 rows into a scrollable sheet, so the walk steps
        // through it a little at a time and checks before each step.
        repeat(14) { step ->
            val row = device.findObjects(By.desc("Developer tools")).firstOrNull()
            if (row != null && clickSmart(row)) {
                openTrace += "row=$step;tap;"
                if (hasDesc(overviewChip, 15_000)) {
                    openTrace += "chip;"
                    return selectPanel(chip, body)
                }
                // The sheet closes ITSELF when its row is taken. A sheet still on
                // screen therefore means the tap never landed and the walk should
                // scroll on; a sheet that is gone means the surface is open and
                // dragging it would only move or close the thing under test.
                if (!hasText("Page Actions", 2_000)) {
                    openTrace += "surfaceWithoutChip;"
                    return false
                }
                openTrace += "tapMissed=$step;"
            }
            sheetScrollStep()
        }
        openTrace += "row=absent;"
        return false
    }

    /**
     * Taps a strip chip and waits for that panel's own body to compose.
     *
     * Both halves are needed. The chip proves the press landed on the strip; the
     * body proves the registry composed the panel rather than the strip merely
     * drawing a chip for it.
     */
    private fun selectPanel(chip: String, body: String): Boolean {
        // Three attempts, because the panel being LEFT can be in two states a
        // fresh open never is: a deep buffer walk escalates the surface to
        // FULLSCREEN (scrollPanelToText's own escalation), where the strip
        // sits at the very top of the window — below the tappable band
        // [isTappable] promises for the docked geometry it was written
        // against — and a plain deep scroll leaves the strip above the fold.
        // Docking back, then walking the surface to its top, restores the
        // geometry every chip tap was written for.
        val node = stripChip(chip)
            ?: dockedStripChip(chip)
            ?: toppedStripChip(chip)
            ?: return false
        if (!clickSmart(node)) return false
        return hasDesc(body, 10_000)
    }

    /** The chip once the surface is back to DOCKED, or null when it already was (or the dock failed). */
    private fun dockedStripChip(chip: String): UiObject2? {
        val dock = findDescNow("Dock") ?: return null
        if (!clickSmart(dock)) return null
        return stripChip(chip)
    }

    /** The chip once the surface has been walked back to its top. */
    private fun toppedStripChip(chip: String): UiObject2? {
        repeat(6) { dragDownHalf() }
        return stripChip(chip)
    }

    /**
     * The chip for [desc], if any part of it is on screen.
     *
     * The strip wraps across as many rows as it needs, so every chip is on
     * screen at phone width -- but a wrapped chip is still checked for a
     * tappable centre rather than trusted, so a layout that pushed it under an
     * edge would fail here instead of tapping whatever is beside it.
     */
    private fun stripChip(desc: String): UiObject2? {
        val node = device.findObjects(By.desc(desc)).firstOrNull() ?: return null
        return if (isTappable(node)) node else null
    }

    private fun isTappable(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        b.width() > 0 && b.centerY() > device.displayHeight / 4 &&
            b.centerY() < device.displayHeight - 20
    } catch (_: Exception) {
        false
    }

    // ---------- Failure diagnostics -----------------------------------------

    /** Readable failure diagnostics, built at the moment of failure. */
    private fun uiTree(): String {
        // BOUNDED, and the reason is a measured hang: the walk re-reads every
        // node through the accessibility connection of the window it started
        // in, and while that window is being recreated each re-read costs the
        // full cross-process timeout — a crawl, not a crash, that once took a
        // DevTools search test 104 minutes to finish. A dedicated walker with a
        // 4-second join is the ceiling: a live window is walked whole, a dying
        // one is abandoned, and the returned message says which happened. A
        // fresh thread per walk (never a shared pool) means a window that dies
        // mid-walk parks one daemon instead of starving every later probe.
        val walked = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val walker = Thread {
            try {
                walked.set(
                    run {
                        val sb = StringBuilder()
                        val probes = listOf(
                            "'Page Actions' sheet" to By.text("Page Actions"),
                            "'Developer tools' row" to By.desc("Developer tools"),
                            "overview panel chip" to By.desc(overviewChip),
                            "search panel chip" to By.desc(searchChip),
                            "search run button" to By.desc(searchBody),
                            "search field" to By.desc("devtools_search_field"),
                            "console panel chip" to By.desc(consoleChip),
                            "network panel chip" to By.desc(networkChip),
                            "page heading" to By.text(pageHeading),
                            "the fixture global" to By.textContains(globalName),
                            "the storage key" to By.textContains(storageKey),
                            "the revealed value" to By.textContains(storageValue),
                            "the console marker" to By.textContains(logMarker),
                            "the requested path" to By.textContains(netPath)
                        )
                        for ((label, selector) in probes) {
                            val nodes = runCatching { device.findObjects(selector) }.getOrDefault(emptyList())
                            sb.append(label).append(": count=").append(nodes.size).append('\n')
                        }
                        val texts = runCatching {
                            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(80)
                        }.getOrDefault(emptyList())
                        sb.append("VISIBLE TEXTS: ").append(texts).append('\n')
                        // The strip wraps across rows, so a chip this suite cannot reach names
                        // itself by its y-range as well as its x-range; the window size is the
                        // other half of that.
                        val chips = runCatching {
                            device.findObjects(By.descContains(" panel")).joinToString(" ") { node ->
                                val b = runCatching { node.visibleBounds }.getOrNull()
                                "${node.contentDescription}[${b?.left},${b?.top},${b?.right},${b?.bottom}]"
                            }
                        }.getOrDefault("probe failed")
                        sb.append("STRIP CHIPS (window ").append(device.displayWidth).append('x')
                            .append(device.displayHeight).append("): ").append(chips)
                        sb.toString().take(8000)
                    }
                )
            } catch (t: Throwable) {
                walked.set("probe dump failed: $t")
            }
        }
        walker.name = "e2e-tree-walker"
        walker.isDaemon = true
        walker.start()
        walker.join(4_000)
        return walked.get()
            ?: "tree probe timed out — the window was mid-transition, so the "
            "walk was abandoned at 4s rather than crawled"
    }
}
