package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
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
 * E2E for the Developer Tools panels that had NO instrumented coverage.
 *
 * Elements, Security and Audit ship in this build, but until now only
 * `DevToolsConsoleNetworkE2eTest` was instrumented, and it opens Console and
 * Network. The three panels selected here had JVM tests of their text
 * formatting and nothing that proved the registry actually composes them on
 * device. Each test selects its own panel from the strip and waits for a
 * control only that panel draws.
 *
 * The strip wraps onto as many rows as it needs (it used to scroll
 * horizontally), so every chip is on screen at phone width and these three --
 * which used to sit past the right edge -- are reachable by a plain tap.
 *
 * UiAutomator sees only the active window: the Page Actions sheet that opens
 * the surface is modal, so it is asserted on screen before anything behind it
 * is probed, exactly as this suite's sibling does.
 */
@RunWith(AndroidJUnit4::class)
class DevToolsPanelsE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer

    // A per-run marker, so a dirty device cannot answer for this run.
    private val tag = (System.currentTimeMillis() % 100000).toString()
    private val pageHeading = "ROOM-DEVTOOLS-PANELS-$tag"

    private val elementsChip = "Elements panel"
    private val securityChip = "Security panel"
    private val auditChip = "Audit panel"

    // Each string is a control only its own panel draws (its SectionHeader copy
    // control), so its presence proves the panel COMPOSED rather than that the
    // strip merely drew a chip for it. A blank body -- the NeedsEngine sentence
    // for a tab with no session -- draws none of these.
    private val elementsBody = "Copy the visible element tree"
    private val securityBody = "Copy the whole Security report"
    private val auditBody = "Copy the whole Audit report"

    /**
     * The one chip every engine has: the Overview panel requires no capability,
     * so this is the marker for "the surface opened", independent of which
     * panels the engine under test serves.
     */
    private val overviewChip = "Overview panel"

    /** Where tearDown leaves the browser, so the next suite starts on it too. */
    private val homePage = "about:home"

    /**
     * Which step of the sheet walk failed, for the assertion that follows it. A
     * probe dump taken afterwards cannot answer this: the walk dismisses the
     * sheet on its way through.
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
                    path.startsWith("/page-") -> MockResponse()
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
        // Return the browser to about:home BEFORE the server goes away: the page
        // this suite opened lives on in the profile as a persisted tab, and the
        // next class cold-starts on the one viewed last.
        runCatching { launchEngine(homePage) }
        runCatching { awaitHomeTab() }
        runCatching { closeFixtureTabs() }
        runCatching { server.shutdown() }
    }

    // ---------- The contract ------------------------------------------------

    @Test
    fun the_elements_panel_composes_from_the_strip() {
        assertTrue(
            "A profile must be active before the engine can be cold-started\n${uiTree()}",
            ensureActiveProfile()
        )
        assertTrue(
            "The fixture page must render before Developer Tools can inspect it\n${uiTree()}",
            openPage(server.url("/page-$tag").toString())
        )
        assertDevToolsOpened(elementsChip, elementsBody)
    }

    @Test
    fun the_security_panel_composes_from_the_strip() {
        assertTrue(
            "A profile must be active before the engine can be cold-started\n${uiTree()}",
            ensureActiveProfile()
        )
        assertTrue(
            "The fixture page must render before Developer Tools can inspect it\n${uiTree()}",
            openPage(server.url("/page-$tag").toString())
        )
        assertDevToolsOpened(securityChip, securityBody)
    }

    @Test
    fun the_audit_panel_composes_from_the_strip() {
        assertTrue(
            "A profile must be active before the engine can be cold-started\n${uiTree()}",
            ensureActiveProfile()
        )
        assertTrue(
            "The fixture page must render before Developer Tools can inspect it\n${uiTree()}",
            openPage(server.url("/page-$tag").toString())
        )
        assertDevToolsOpened(auditChip, auditBody)
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
                .create("E2E DevTools Panels ${System.currentTimeMillis()}", "👤", 0xFF7C4DFFL)
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

    /** The fixture page: a heading and one element, so the page has a document to read. */
    private fun fixturePage(): String = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        </head><body style="font-size:24px; margin:24px;">
        <h1>$pageHeading</h1>
        <p>A paragraph the element tree can hold.</p>
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
        try { Thread.sleep(150) } catch (_: InterruptedException) { }
    }

    /**
     * Opens the Page Actions sheet, clicks the Developer tools row, and selects
     * the panel named by [chip], waiting for the control only that panel draws.
     *
     * The sheet is asserted first: it is a modal window, and UiAutomator only
     * returns nodes from the active window.
     */
    private fun assertDevToolsOpened(chip: String, body: String) {
        // The helper runs BEFORE the message is built. Kotlin evaluates the
        // arguments left to right, so a probe dump written into the message of
        // the call it is reporting on is taken before the walk has moved.
        val opened = openDeveloperToolsPanel(chip, body)
        assertTrue(
            "Developer Tools must open from the Page Actions sheet — ${openTrace}\n${uiTree()}",
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
        val node = stripChip(chip) ?: return false
        if (!clickSmart(node)) return false
        return hasDesc(body, 10_000)
    }

    /**
     * The chip for [desc], if any part of it is on screen.
     *
     * The strip wraps now, so every chip is on screen at phone width -- but a
     * chip in a wrapped row is still checked for a tappable centre rather than
     * trusted, so a layout that pushed it under an edge would fail here instead
     * of tapping whatever is beside it.
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
    private fun uiTree(): String = try {
        val sb = StringBuilder()
        val probes = listOf(
            "'Page Actions' sheet" to By.text("Page Actions"),
            "'Developer tools' row" to By.desc("Developer tools"),
            "'What this edition can inspect' header" to By.text("What this edition can inspect"),
            "overview panel chip" to By.desc(overviewChip),
            "elements panel chip" to By.desc(elementsChip),
            "security panel chip" to By.desc(securityChip),
            "audit panel chip" to By.desc(auditChip),
            "elements panel body" to By.desc(elementsBody),
            "security panel body" to By.desc(securityBody),
            "audit panel body" to By.desc(auditBody)
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
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }
}
