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
 * E2E for the Developer Tools panel's LIFECYCLE.
 *
 * WHAT THIS PROVES THAT THE FEED SUITE CANNOT. DevToolsConsoleNetworkE2eTest
 * opens the surface and reads what one panel draws; it never takes the panel
 * away. The manager's whole reason for living on the view model rather than in
 * a composable is the three teardown paths it owns -- a closed tab, a tab
 * switch, a private tab -- and each is a fact about what is on screen AFTER
 * the event, not about the rows in a feed. A manager that leaked an inspector
 * on any of them passes every feed test and still holds a subscription on a
 * dead session.
 *
 * WHY THE PANEL IS NOT THE TAB GRID'S BUSINESS. DeveloperToolsHost is composed
 * only on the Browser route, so while the tab grid is up the panel is absent
 * from the tree whether or not the manager still reports it open. Every
 * "the panel is gone" assertion therefore leaves the grid first: sampling the
 * tree on the grid would be an absence that proves nothing.
 *
 * WHY THE TAB HAS A <title>. The switch test has to say WHICH tab the panel is
 * describing, and the one string only the active tab's panel draws is that
 * tab's own document title. A marker unique per run keeps a dirty profile from
 * answering for this one.
 *
 * The panels render in the SAME activity window, so unlike a permission sheet
 * they do not hide the WebView from UiAutomator -- but the Page Actions sheet
 * that opens them IS modal, so it is asserted before anything else, exactly as
 * this suite's other sheet tests do.
 */
@RunWith(AndroidJUnit4::class)
class DevToolsLifecycleE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer

    // Per-run markers, so a dirty device cannot answer for this run.
    private val tag = (System.currentTimeMillis() % 100000).toString()
    private val pageHeading = "ROOM-DEVTOOLS-LIFE-$tag"
    private val pageTitle = "Room Life Title $tag"
    private val fixturePath = "/life-$tag"

    /**
     * The tab card is matched by a substring of its URL, not the whole URL:
     * the card always renders the tab's URL, and a substring survives whatever
     * normalisation the engine applies to the string it loaded.
     */
    private val urlMarker = fixturePath

    /**
     * The strip chip's announcement, and so the marker for "the surface is open
     * and its body is drawn". The strip is inside the same minimized check as
     * the body, so it disappears with it.
     */
    private val overviewChip = "Overview panel"

    /**
     * Only the Overview panel's Current-page section draws this, so it is the
     * marker for "the Overview panel COMPOSED" as opposed to "the strip is on
     * screen". A bare `By.text("Overview")` would match the chip and say nothing.
     */
    private val overviewBody = "Copy everything this panel is showing"

    /** The close control's contentDescription: the panel, and only the panel, carries it. */
    private val panelClose = "Close developer tools"

    /** The panel header text. The Page Actions row shares this label, so it is only ever asserted with the sheet closed. */
    private val panelHeader = "Developer tools"

    /**
     * The panel's own words for a tab that holds no live engine. It is the
     * first thing PageFacts draws above the fold, so its presence identifies a
     * no-engine active tab and its absence is meaningful on a tab that has one.
     */
    private val noEngineLine = "No live engine session for this tab yet"

    /** Where tearDown leaves the browser, so the next suite starts on it too. */
    private val homePage = "about:home"

    /**
     * Which step of the sheet walk failed, for the assertion that follows it.
     *
     * A probe dump taken afterwards cannot answer this: the walk dismisses the
     * sheet on its way through, so the tree at that point shows a closed sheet
     * and says nothing about where the walk stopped.
     */
    private var openTrace: String = ""

    @Before
    fun setUp() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                return when {
                    path.startsWith("/life-") -> MockResponse()
                        .setHeader("Content-Type", "text/html; charset=utf-8")
                        .setHeader("Cache-Control", "no-store")
                        .setBody(fixturePage())
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
        runCatching { dropSuiteTabs() }
        runCatching { server.shutdown() }
    }

    /**
     * Waits for the launch above to reach the profile.
     *
     * A request is not a result: the next class starts its activity with CLEAR_TASK,
     * which destroys this one, so a home tab still unwritten when this returns is a
     * launch that never happened -- and what is then left for that class is a fixture
     * URL on the server this method is about to stop.
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
     * Deletes the tabs this suite opened.
     *
     * They point at the MockWebServer this method's caller is about to stop, and a private
     * tab left standing is a privacy promise owed to nobody. Retried, because a page that
     * finishes loading while the close is in flight rewrites its own row.
     */
    private fun dropSuiteTabs() {
        val graph = (targetContext.applicationContext as RoomBrowserApp).graph
        val base = server.url("/").toString()
        repeat(10) {
            val left = runBlocking {
                val profileId = graph.appState.activeProfileIdSnapshot() ?: return@runBlocking 0
                val stale = graph.browserRepo.openTabs(ProfileId(profileId))
                    .filter { it.isPrivate || it.url.startsWith(base) }
                stale.forEach { graph.browserRepo.closeTab(it.id) }
                stale.size
            }
            if (left == 0) return
            Thread.sleep(500)
        }
    }

    /** The fixture page: a unique document title (the switch marker) over a visible heading. */
    private fun fixturePage(): String = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>$pageTitle</title>
        </head><body style="font-size:24px; margin:24px;">
        <h1>$pageHeading</h1>
        </body></html>
    """.trimIndent()

    // ---------- The contract ------------------------------------------------

    /**
     * The tab the panel is describing closes, so the panel must go with it.
     *
     * This is the reason the registry lives on the view model: a panel pointed
     * at a tab that no longer exists composes nothing while still reporting
     * itself open, and the inspector it built still holds the session the tab
     * close is about to destroy.
     */
    @Test
    fun closing_the_inspected_tab_takes_the_panel_with_it() {
        assertTrue(
            "A profile must be active before the engine can be cold-started\n${uiTree()}",
            ensureActiveProfile()
        )
        assertTrue(
            "The fixture page must render before Developer Tools can inspect it\n${uiTree()}",
            openPage(server.url(fixturePath).toString())
        )
        assertDevToolsOpened()

        // The grid is the only way to close a tab, and it hides the panel while
        // it is up -- so the close is done on the grid and the panel is read
        // after leaving it, never from the grid's own tree.
        assertTrue(
            "The tab grid must open to reach the inspected tab's close control\n${uiTree()}",
            openTabGrid()
        )
        assertTrue(
            "The inspected tab's card must offer its own close control\n${uiTree()}",
            closeCardByMarker(urlMarker)
        )
        assertTrue(
            "The grid must be left before the panel's absence can mean anything\n${uiTree()}",
            leaveGrid()
        )

        assertTrue(
            "Closing the tab the panel is describing must take the panel with it\n${uiTree()}",
            panelClosed()
        )
    }

    /**
     * A tab switch RETARGETS the panel; it must not close it, and it must not
     * keep describing the tab that was left behind.
     *
     * The new tab is the start page, which holds no engine, so the panel's own
     * no-engine line is the proof it followed -- and the document title of the
     * page tab is the proof it came back when the switch was reversed.
     */
    @Test
    fun switching_tabs_retargets_the_panel_to_the_active_tab() {
        assertTrue(
            "A profile must be active before the engine can be cold-started\n${uiTree()}",
            ensureActiveProfile()
        )
        assertTrue(
            "The fixture page must render before Developer Tools can inspect it\n${uiTree()}",
            openPage(server.url(fixturePath).toString())
        )
        assertDevToolsOpened()

        // On the page tab the panel describes THIS page: its document title is
        // on screen and the no-engine line is not.
        assertTrue(
            "The panel must describe the page tab it was opened on\n${uiTree()}",
            hasText(pageTitle, 10_000)
        )
        assertTrue(
            "A page tab must not read as a tab with no engine\n${uiTree()}",
            waitUntilAbsent(By.textContains(noEngineLine), 5_000)
        )

        // A brand-new start-page tab becomes active; the panel must follow it.
        assertTrue(
            "A new start-page tab must become the active one\n${uiTree()}",
            newTabFromGrid()
        )
        assertTrue(
            "Switching to a tab with no engine must be what the panel then reports\n${uiTree()}",
            hasTextContains(noEngineLine, 15_000)
        )
        assertTrue(
            "The panel must stop describing the tab that was left behind\n${uiTree()}",
            waitUntilAbsent(By.text(pageTitle), 5_000)
        )

        // Back to the page tab: the panel must describe it again.
        assertTrue(
            "The tab grid must re-open to switch back\n${uiTree()}",
            openTabGrid()
        )
        assertTrue(
            "The page tab's card must be selectable\n${uiTree()}",
            selectCardByMarker(urlMarker)
        )
        assertTrue(
            "Switching back must retarget the panel to the page tab\n${uiTree()}",
            hasText(pageTitle, 15_000)
        )
        assertTrue(
            "The retargeted panel must describe a tab that has an engine\n${uiTree()}",
            waitUntilAbsent(By.textContains(noEngineLine), 5_000)
        )
    }

    /**
     * Developer Tools is not offered on a private tab, so a panel already open
     * when one is raised must not keep reading it.
     *
     * This is the one teardown the panel's own composable owns: the render guard
     * would hide it, but only the effect closes the manager, and a hidden panel
     * that is still "open" would reappear the moment a public tab came forward
     * with an inspector still bound to the private session.
     */
    @Test
    fun opening_a_private_tab_closes_the_panel() {
        assertTrue(
            "A profile must be active before the engine can be cold-started\n${uiTree()}",
            ensureActiveProfile()
        )
        assertTrue(
            "The fixture page must render before Developer Tools can inspect it\n${uiTree()}",
            openPage(server.url(fixturePath).toString())
        )
        assertDevToolsOpened()

        assertTrue(
            "The Page Actions sheet must offer 'New private tab'\n${uiTree()}",
            tapSheetRow("New private tab")
        )
        assertTrue(
            "A private tab must actually be the active tab before any absence is read\n${uiTree()}",
            hasTextContains("Private tab", 15_000)
        )
        assertTrue(
            "Raising a private tab must close the panel rather than hide it\n${uiTree()}",
            panelClosed()
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
                .create("E2E DevTools Life ${System.currentTimeMillis()}", "👤", 0xFF7C4DFFL)
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

    private fun hasTextContains(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.textContains(text)), timeoutMs)

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

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
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                }
        }
        return !present()
    }

    /**
     * The panel is gone: both its close control and its header have left the
     * tree. Two probes rather than one because the close control is the panel's
     * own hook and the header is the string a user would name.
     */
    private fun panelClosed(timeoutMs: Long = 10_000): Boolean =
        waitUntilAbsent(By.desc(panelClose), timeoutMs) && waitUntilAbsent(By.text(panelHeader), timeoutMs)

    // ---------- Developer Tools: opening it (proven pattern) ----------------

    /**
     * Opens the Page Actions sheet, clicks the Developer tools row, and selects
     * the Overview panel -- the strip's always-present entry, and the one whose
     * body carries the tab's own facts.
     */
    private fun assertDevToolsOpened() {
        // The helper runs BEFORE the message is built. Kotlin evaluates the
        // arguments left to right, so a probe dump written into the message of
        // the call it is reporting on is taken before the walk has moved.
        val opened = openDeveloperToolsPanel(overviewChip, overviewBody)
        assertTrue(
            "Developer Tools must open from the Page Actions sheet — ${openTrace}\n${uiTree()}",
            opened
        )
    }

    private fun openDeveloperToolsPanel(chip: String, body: String): Boolean {
        openTrace = "start;"
        // A panel left open by an earlier test method would cover the chrome it
        // is opened from, so it is closed first rather than assumed away.
        device.findObjects(By.desc(panelClose)).firstOrNull()?.let {
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
            val row = device.findObjects(By.desc(panelHeader)).firstOrNull()
            if (row != null && clickSmart(row)) {
                openTrace += "row=$step;tap;"
                if (hasDesc(chip, 15_000)) {
                    openTrace += "chip;"
                    return selectPanel(chip, body)
                }
                // The sheet closes ITSELF when its row is taken. A sheet still
                // on screen therefore means the tap never landed and the walk
                // should scroll on; a sheet that is gone means the surface is
                // open and dragging it would only move or close the thing
                // under test.
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
        val node = scrollStripToDesc(chip) ?: return false
        if (!clickSmart(node)) return false
        return hasDesc(body, 10_000)
    }

    /**
     * Brings a strip chip into view, checking before each swipe.
     *
     * The strip scrolls horizontally and has to: four chips only just fit in a
     * 320dp window, and the panel set grows from here. A chip pushed off the
     * right edge is clipped, and a clipped node is one this suite must not tap
     * by its stale bounds.
     */
    private fun scrollStripToDesc(desc: String, maxSwipes: Int = 12): UiObject2? {
        repeat(maxSwipes) {
            val node = device.findObjects(By.desc(desc)).firstOrNull()
            if (node != null && isFullyVisible(node)) return node
            stripLeft()
        }
        return device.findObjects(By.desc(desc)).firstOrNull()
    }

    /**
     * Whether a chip is wholly inside the window.
     *
     * The strip scrolls horizontally and there are more chips than fit, so a
     * chip that answers a lookup can still be half off an edge -- and a tap at
     * the centre of a clipped node lands on whatever is beside it.
     */
    private fun isFullyVisible(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        b.width() > 0 && b.left >= 0 && b.right <= device.displayWidth &&
            b.centerY() > device.displayHeight / 4 &&
            b.centerY() < device.displayHeight - 20
    } catch (_: Exception) {
        false
    }

    /**
     * Where the strip sits: just under the header row.
     *
     * Anchored on the header's own minimize control rather than on a fraction of
     * the window, because the dock height changes with the window and the header
     * is a node this suite can actually find.
     */
    private fun stripY(): Int {
        val header = findDescNow("Minimize") ?: return device.displayHeight * 39 / 100
        return runCatching { header.visibleBounds.bottom + 20 }
            .getOrDefault(device.displayHeight * 39 / 100)
    }

    /**
     * Drags the strip towards the left, which reveals the chips to its right.
     *
     * Half a window per step rather than nearly a whole one: a chip is narrower
     * than the jump, so a bigger step can carry one from off the right edge to
     * off the left edge without ever being wholly on screen.
     */
    private fun stripLeft() {
        val y = stripY()
        device.swipe(device.displayWidth - 24, y, device.displayWidth / 2, y, 40)
        device.waitForIdle(400)
        try {
            Thread.sleep(150)
        } catch (_: InterruptedException) {
            }
    }

    // ---------- The tab grid, where a tab is switched and closed ------------

    /** True while the tab grid route is on screen. */
    private fun gridUp(): Boolean =
        device.findObjects(By.textStartsWith("Tabs (")).isNotEmpty()

    private fun openTabGrid(): Boolean {
        if (!clickDesc("Open tab grid", 6_000)) return false
        return waitUntil(6_000) { gridUp() }
    }

    /**
     * Leaves the grid for the Browser route through its own back control.
     *
     * NOT `pressBack`: the system Back handler closes an OPEN Developer Tools
     * panel first on the browser route, and a stray Back on a page with no
     * history pops the exit confirmation -- either would be read as part of the
     * assertion that follows.
     */
    private fun leaveGrid(): Boolean {
        if (!clickDesc("Close", 6_000)) return false
        return waitUntil(6_000) { !gridUp() }
    }

    /**
     * Creates a start-page tab from the grid's "+" and returns to the browser.
     *
     * The grid's "+" performs the create AND the close, so the freshly made tab
     * is the visible active one when this returns.
     */
    private fun newTabFromGrid(): Boolean {
        if (!openTabGrid()) return false
        if (!clickDesc("New tab", 6_000)) return false
        if (!waitUntil(8_000) { !gridUp() }) return false
        return hasDesc("Address bar: search or type URL", 8_000) || hasText("Privacy Dashboard", 3_000)
    }

    /**
     * Taps the tab card whose URL shows [marker] (walks up to the clickable
     * card) and verifies the grid closed -- selecting a card returns to the
     * browsing surface with that tab active.
     *
     * The URL is matched rather than the title because the card always renders
     * its URL, while a title is blank until the engine reports one.
     */
    private fun selectCardByMarker(marker: String): Boolean {
        for (i in 1..12) {
            val node = device.wait(Until.findObject(By.textContains(marker)), 1_500)
            if (node != null) {
                clickSmart(node)
                if (waitUntil(5_000) { !gridUp() }) return true
                runCatching { clickCenter(node) }
                if (waitUntil(5_000) { !gridUp() }) return true
            }
            dragUpQuarter()
        }
        return false
    }

    /**
     * Closes the tab card whose URL shows [marker] via that card's own "Close
     * tab" button. The close button is located geometrically: the card's bounds
     * come from the first CLICKABLE ancestor of the URL node (the whole card is
     * the touch target), and the close button is the "Close tab" node whose
     * center lies inside those bounds -- a partly-clipped node reports a
     * non-empty rect, so containment in the card is what is checked, never the
     * centre against the screen.
     */
    private fun closeCardByMarker(marker: String): Boolean {
        for (i in 1..12) {
            val urlNode = device.wait(Until.findObject(By.textContains(marker)), 1_500)
            if (urlNode == null) {
                dragUpQuarter()
                continue
            }
            val card = clickableAncestorOf(urlNode)
            if (card == null) {
                dragUpQuarter()
                continue
            }
            val cardBounds = card.visibleBounds
            val close = device.findObjects(By.desc("Close tab"))
                .firstOrNull { b ->
                    val cb = b.visibleBounds
                    cardBounds.contains(cb.centerX(), cb.centerY())
                }
            if (close == null) {
                dragUpQuarter()
                continue
            }
            clickSmart(close)
            return true
        }
        return false
    }

    private fun clickableAncestorOf(node: UiObject2): UiObject2? {
        var current: UiObject2? = node
        var hops = 0
        while (current != null && hops < 8) {
            val clickable = try {
                current.isClickable
            } catch (_: Exception) {
                false
            }
            if (clickable) return current
            current = try {
                current.parent
            } catch (_: Exception) {
                null
            }
            hops++
        }
        return null
    }

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

    // ---------- The Page Actions sheet, for the private-tab row ------------

    /**
     * Opens the Page Actions sheet and taps the row announced as [desc].
     *
     * The sheet is asserted on screen first: it is a modal window, and
     * UiAutomator only returns nodes from the active window.
     */
    private fun tapSheetRow(desc: String, maxSteps: Int = 10): Boolean {
        assertTrue(
            "The browser chrome must be up before its sheet can be opened\n${uiTree()}",
            hasDesc("Page actions and settings", 30_000)
        )
        if (!clickDesc("Page actions and settings", 6_000)) return false
        assertTrue(
            "The Page Actions sheet must be on screen before any row in it is tapped\n${uiTree()}",
            hasText("Page Actions", 6_000)
        )
        repeat(maxSteps) {
            val row = device.findObjects(By.desc(desc)).firstOrNull()
            if (row != null && clickSmart(row)) return true
            sheetScrollStep()
        }
        return false
    }

    // ---------- Failure diagnostics -----------------------------------------

    /** Readable failure diagnostics, built at the moment of failure. */
    private fun uiTree(): String = try {
        val sb = StringBuilder()
        val probes = listOf(
            "'Page Actions' sheet" to By.text("Page Actions"),
            "'Developer tools' row" to By.desc(panelHeader),
            "overview panel chip" to By.desc(overviewChip),
            "overview panel body" to By.desc(overviewBody),
            "panel close control" to By.desc(panelClose),
            "panel header" to By.text(panelHeader),
            "no-engine line" to By.textContains(noEngineLine),
            "page title marker" to By.text(pageTitle),
            "tab grid header" to By.textStartsWith("Tabs (")
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
