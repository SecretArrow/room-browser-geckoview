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
 * E2E for the Developer Tools APPLICATION panel against a page that really
 * stores something.
 *
 * WHAT THIS PROVES THAT THE FEED SUITE CANNOT. DevToolsConsoleNetworkE2eTest
 * opens the panel and reads a console/network feed; it never makes the page put
 * anything on the device. This suite serves a fixture that seeds a manifest, a
 * service worker, localStorage/sessionStorage keys, an IndexedDB database and a
 * Cache Storage entry, then opens the Application panel through the real UI and
 * asserts each seeded value is on screen -- so a panel that renders nothing but
 * "(not reported)", or that never ran its probe, fails here.
 *
 * It also pins the OTHER half of the panel's contract: the sections this build
 * deliberately does not read -- Extension Storage, WebMCP, Private State Tokens
 * and Storage Buckets -- are absent, not drawn empty.
 *
 * WHY THE PAGE CARRIES A READY MARKER. The seeding is asynchronous: a service
 * worker registration, an IndexedDB upgrade and a cache write all have to
 * settle before the panel can see them. A fixed sleep would be a race the
 * emulator is allowed to lose slowly, so the fixture flips a visible marker
 * when the last of them finishes and the test waits for THAT -- the same
 * "await a fact, not a duration" rule the sibling suites use.
 *
 * The panels render in the SAME activity window, so they do not hide the
 * WebView from UiAutomator -- but the Page Actions sheet that opens them IS
 * modal, so it is asserted on screen before any row in it is touched.
 */
@RunWith(AndroidJUnit4::class)
class DevToolsApplicationPanelE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer

    // Per-run markers, so a dirty device cannot answer for this run.
    private val tag = (System.currentTimeMillis() % 100000).toString()
    private val pageHeading = "ROOM-DEVTOOLS-APP-$tag"
    private val seededMarker = "SEEDED-$tag"

    private val fixturePath = "/app-$tag"
    private val manifestPath = "/manifest-$tag.webmanifest"
    private val serviceWorkerPath = "/sw-$tag.js"

    // The strings the seeded data puts on screen, each unique to this run.
    private val manifestName = "ROOM-APP-MANIFEST-$tag"
    private val localKey = "room-ls-$tag"
    private val localKeyAlt = "room-ls-alt-$tag"
    private val sessionKey = "room-ss-$tag"
    private val databaseName = "room-idb-$tag"
    private val databaseStore = "room-store-$tag"
    private val cacheName = "room-cache-$tag"

    /**
     * The strip chip's announcement, and so the marker for "the surface is open
     * and its body is drawn".
     */
    private val applicationChip = "Application panel"

    /**
     * Only the Application panel draws this, so it is the marker for "the
     * Application panel COMPOSED" as opposed to "the strip is on screen". A bare
     * `By.text("Application")` would also match the strip chip and the section
     * header, and so would say nothing about which panel is showing.
     */
    private val applicationBody = "Read the page's storage again"

    /**
     * The Application panel's own words for "the probe answered and finished".
     * Waiting for this is the honest readiness gate: it proves the page's reads
     * settled, rather than trusting a fixed time after the panel opened.
     */
    private val readDoneLine = "Read from the live page."

    /** The always-present chip, used as the marker that the surface opened at all. */
    private val overviewChip = "Overview panel"

    /** The panel header text. The Page Actions row shares this label, so it is only ever asserted with the sheet closed. */
    private val panelHeader = "Developer tools"

    /** The sections this build deliberately does not compose; their absence is the other half of the panel's contract. */
    private val hiddenSectionLabels = listOf(
        "Extension Storage",
        "WebMCP",
        "Private State Tokens",
        "Storage Buckets"
    )

    /** Where tearDown leaves the browser, so the next suite starts on it too. */
    private val homePage = "about:home"

    /** Which step of the sheet walk failed, for the assertion that follows it. */
    private var openTrace: String = ""

    /**
     * What the last panel walk did: which scrollable nodes it found, which
     * mechanism moved the body, and where the body's top ended up. A failure
     * that says only "the key is not on screen" cannot separate "the panel never
     * rendered it" from "the walk never reached it".
     */
    private var scrollTrace: String = ""

    @Before
    fun setUp() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                return when {
                    path.startsWith("/app-") -> MockResponse()
                        .setHeader("Content-Type", "text/html; charset=utf-8")
                        .setHeader("Cache-Control", "no-store")
                        .setBody(fixturePage())
                    path.startsWith("/manifest-") -> MockResponse()
                        .setHeader("Content-Type", "application/manifest+json")
                        .setHeader("Cache-Control", "no-store")
                        .setBody(manifestJson())
                    path.startsWith("/sw-") -> MockResponse()
                        .setHeader("Content-Type", "text/javascript; charset=utf-8")
                        .setHeader("Cache-Control", "no-store")
                        .setBody(serviceWorkerScript())
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
     * They point at the MockWebServer this method's caller is about to stop, and a
     * tab left open on a dead server is what the next class would then restore.
     * Retried, because a page that finishes loading while the close is in flight
     * rewrites its own row.
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

    /**
     * The fixture page.
     *
     * Every seed the Application panel can read, run in-page so the panel reads
     * it from the live session exactly as it would from a real site: a manifest
     * link, two localStorage keys, a sessionStorage key, an IndexedDB database
     * with one store and one record, and a Cache Storage entry. The ready marker
     * is flipped only once the asynchronous seeds have settled.
     */
    private fun fixturePage(): String = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <link rel="manifest" href="$manifestPath">
        </head><body style="font-size:24px; margin:24px;">
        <h1>$pageHeading</h1>
        <div id="ready" style="font-size:24px">WAITING-$tag</div>
        <script>
          (function () {
            var tag = '$tag';
            function ready() {
              try { document.getElementById('ready').textContent = '$seededMarker'; } catch (e) {}
            }
            try {
              localStorage.setItem('$localKey', 'local-value-' + tag);
              localStorage.setItem('$localKeyAlt', 'second-value');
              sessionStorage.setItem('$sessionKey', 'session-value-' + tag);
            } catch (e) {}

            var tasks = [];

            tasks.push(new Promise(function (resolve) {
              try {
                var req = indexedDB.open('$databaseName', 1);
                req.onupgradeneeded = function () {
                  var store = req.result.createObjectStore('$databaseStore', { keyPath: 'id' });
                  store.put({ id: 1, value: 'row-' + tag });
                };
                req.onsuccess = function () { try { req.result.close(); } catch (e) {} resolve(); };
                req.onerror = function () { resolve(); };
              } catch (e) { resolve(); }
            }));

            tasks.push(new Promise(function (resolve) {
              try {
                if (!('caches' in window)) { resolve(); return; }
                caches.open('$cacheName').then(function (cache) {
                  return cache.put(location.origin + '/cache-entry-' + tag, new Response('cached-' + tag));
                }).then(resolve, resolve);
              } catch (e) { resolve(); }
            }));

            tasks.push(new Promise(function (resolve) {
              try {
                if (!('serviceWorker' in navigator)) { resolve(); return; }
                navigator.serviceWorker.register('$serviceWorkerPath')
                  .then(function () { return navigator.serviceWorker.ready; })
                  .then(resolve, resolve);
              } catch (e) { resolve(); }
            }));

            Promise.all(tasks).then(ready, ready);
            setTimeout(ready, 6000);
          })();
        </script>
        </body></html>
    """.trimIndent()

    /** The manifest the page declares, with a name unique to this run. */
    private fun manifestJson(): String = """
        {
          "name": "$manifestName",
          "short_name": "ROOM-APP-SHORT-$tag",
          "start_url": "$fixturePath",
          "display": "standalone",
          "theme_color": "#123456",
          "background_color": "#ffffff",
          "icons": [{ "src": "/icon-$tag.png", "sizes": "192x192", "type": "image/png" }]
        }
    """.trimIndent()

    /** A minimal service worker: it claims the page and serves nothing, which is all the panel's registration read needs. */
    private fun serviceWorkerScript(): String = """
        self.addEventListener('install', function () { self.skipWaiting(); });
        self.addEventListener('activate', function (event) { event.waitUntil(self.clients.claim()); });
    """.trimIndent()

    // ---------- The contract ------------------------------------------------

    /**
     * The page declares a manifest and registers a service worker, so the panel
     * must show the manifest's own name and URL and the worker's script URL --
     * not "none declared" and not "none registered".
     */
    @Test
    fun the_application_panel_reads_the_pages_manifest_and_service_worker() {
        assertTrue(
            "A profile must be active before the engine can be cold-started\n${uiTree()}",
            ensureActiveProfile()
        )
        assertTrue(
            "The fixture page must render and seed before Developer Tools can read it\n${uiTree()}",
            openSeededPage()
        )
        assertDevToolsOpened()

        assertReached("The manifest's name must reach the panel", manifestName)
        assertReached("The manifest's own URL must reach the panel too", "manifest-$tag.webmanifest")
        assertReached("The registered service worker's script URL must reach the panel", "sw-$tag.js")
    }

    /**
     * The page stores keys in both web-storage areas, opens an IndexedDB
     * database and writes a cache entry, so the panel must show each name.
     */
    @Test
    fun the_application_panel_reads_the_storage_areas_and_caches() {
        assertTrue(
            "A profile must be active before the engine can be cold-started\n${uiTree()}",
            ensureActiveProfile()
        )
        assertTrue(
            "The fixture page must render and seed before Developer Tools can read it\n${uiTree()}",
            openSeededPage()
        )
        assertDevToolsOpened()

        assertReached("A localStorage key must reach the panel", localKey)
        assertReached("Every localStorage key must be listed, not just the first", localKeyAlt)
        assertReached("A sessionStorage key must reach the panel", sessionKey)
        assertReached("The IndexedDB database name must reach the panel", databaseName)
        assertReached("The IndexedDB object store must be listed under its database", databaseStore)
        assertReached("The Cache Storage cache name must reach the panel", cacheName)
    }

    /**
     * The panel must not draw the sections this build deliberately does not
     * read. They are asserted absent over the SAME panel body the content tests
     * scroll, so the absence is a fact about the composed panel rather than a
     * node Compose happened never to build.
     */
    @Test
    fun the_application_panel_hides_the_sections_this_build_does_not_read() {
        assertTrue(
            "A profile must be active before the engine can be cold-started\n${uiTree()}",
            ensureActiveProfile()
        )
        assertTrue(
            "The fixture page must render and seed before Developer Tools can read it\n${uiTree()}",
            openSeededPage()
        )
        assertDevToolsOpened()

        // A positive control first: the panel really composed, and really read
        // the page. Without it, "the hidden labels are gone" would pass on a
        // panel that drew nothing at all.
        assertReached(
            "The Application panel must be showing real content while the absent sections are checked",
            manifestName
        )
        val offender = firstHiddenSectionOnScreen()
        assertTrue(
            "A section this build does not read must be absent, but '$offender' was on screen\n${uiTree()}",
            offender == null
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
                .create("E2E DevTools App ${System.currentTimeMillis()}", "👤", 0xFF7C4DFFL)
                .id.value
            graph.appState.setActiveProfile(id)
            true
        }
    }

    /**
     * Opens the fixture page and waits for the seeds to settle.
     *
     * The heading proves the document rendered; the marker proves the manifest,
     * the storage areas, the database and the cache write all finished, so the
     * panel is only ever asked to read a page that is ready to be read.
     */
    private fun openSeededPage(): Boolean {
        if (!openPage(server.url(fixturePath).toString())) return false
        return hasText(seededMarker, 30_000)
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
     * A slow drag up the panel body. The panel body is a vertically scrollable
     * Column, so this is how a section below the fold is reached; no text field
     * is ever tapped, so the soft IME is never raised to swallow the drag.
     *
     * Both endpoints come from the body's own MEASURED bounds, never from the
     * window: the docked panel is only the bottom half of the window, so a drag
     * computed from the window's mid-line finishes ABOVE the body and moves
     * nothing. Measured on the CI emulator (320x640), the docked body stood at
     * `Rect(0, 333 - 320, 568)` while such a drag ended at y=320 -- the whole
     * gesture was spent outside the surface, which reads exactly like a panel
     * that cannot scroll.
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
        try { Thread.sleep(200) } catch (_: InterruptedException) { }
    }

    /** The same drag the other way, for walking back to the top of the body. */
    private fun dragDownHalf() {
        val body = bodyRect() ?: return
        if (body.height() <= 0) return
        val from = body.top + body.height() / 5
        device.swipe(body.centerX(), from, body.centerX(), from + body.height() * 2 / 5, 60)
        device.waitForIdle(400)
        try { Thread.sleep(120) } catch (_: InterruptedException) { }
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

    /**
     * The top line of the panel body, or the fact that it is no longer on
     * screen. The body's own first row is the only node whose position says
     * whether the body moved at all: an absent tail section is equally
     * consistent with "not rendered" and "not scrolled to".
     */
    private fun bodyTop(): String {
        val node = device.findObjects(By.textContains(readDoneLine)).firstOrNull()
        return runCatching { node?.visibleBounds?.toString() }.getOrNull() ?: "(off)"
    }

    /** Every scrollable node UiAutomator can see, with its geometry. */
    private fun scrollables(): String = (0 until 5).joinToString(",") { i ->
        val obj = runCatching { device.findObject(UiSelector().scrollable(true).instance(i)) }.getOrNull()
        if (obj == null || !runCatching { obj.exists() }.getOrDefault(false)) {
            "$i=absent"
        } else {
            val cls = runCatching { obj.className }.getOrDefault("?")
            "$i=$cls@${runCatching { obj.visibleBounds }.getOrNull()}"
        }
    }

    /**
     * The same drag, injected by the shell's own `input` command instead.
     *
     * UiAutomator's swipe pushes every motion event through the accessibility
     * connection synchronously, and a long drag against a live WebView can lose
     * that stream to the round trip -- which looks exactly like a panel that
     * cannot scroll. `input swipe` runs in its own process with the shell's
     * INJECT_EVENTS permission and does not wait on the a11y connection between
     * events, so a body that answers this one and not the other localises the
     * fault to the injection layer rather than to the panel.
     */
    private fun shellDragUp() {
        val x = device.displayWidth / 2
        val from = device.displayHeight * 3 / 4
        val to = device.displayHeight / 2
        runCatching { device.executeShellCommand("input swipe $x $from $x $to 300") }
        device.waitForIdle(400)
        try { Thread.sleep(150) } catch (_: InterruptedException) { }
    }

    /**
     * Walks the panel body until [text] appears, checking BEFORE each move so
     * evidence already on screen is never scrolled past.
     *
     * A docked body is only the bottom half of the window, so a section near
     * the bottom costs many short movings; taking the panel full screen and
     * walking again is the fallback. Every attempt is recorded in [scrollTrace],
     * so a failure says which mechanism the body answered to -- and whether it
     * moved at all.
     */
    private fun scrollPanelToText(text: String, maxDrags: Int = 24): Boolean {
        fun found() = device.findObjects(By.textContains(text)).isNotEmpty()
        scrollTrace = "body=${bodyRect()} top=${bodyTop()}"
        if (found()) {
            scrollTrace += "|onScreen"
            return true
        }
        repeat(maxDrags) {
            if (found()) return true
            dragUpHalf()
        }
        if (found()) {
            scrollTrace += "|drag=hit top=${bodyTop()}"
            return true
        }
        scrollTrace += "|drag=miss top=${bodyTop()}"
        if (!expandToFullScreen()) {
            scrollTrace += "|fullscreen=no"
            return found()
        }
        scrollTrace += "|fullscreen=yes"
        if (found()) {
            scrollTrace += "|fullscreen=onScreen"
            return true
        }
        // Growing the panel does not move its scroll offset, so a target that
        // was below the docked fold can now sit ABOVE the taller viewport.
        // Walk back to the top, then down again.
        repeat(maxDrags) { if (!found()) dragDownHalf() }
        repeat(maxDrags + 12) {
            if (found()) return true
            dragUpHalf()
        }
        repeat(8) { if (!found()) shellDragUp() }
        scrollTrace += "|end found=${found()} top=${bodyTop()} scrollables=[${scrollables()}]"
        return found()
    }

    /**
     * Walks the body to [text] and then reports the walk.
     *
     * The message is built HERE, after the walk. Kotlin evaluates a call's
     * arguments left to right, so a dump written into the message expression of
     * the call it reports on is taken before that call has moved anything.
     */
    private fun assertReached(label: String, text: String) {
        val reached = scrollPanelToText(text)
        assertTrue("$label [$scrollTrace]\n${uiTree()}", reached)
    }

    /**
     * Walks the whole panel body looking for any of the deliberately-absent
     * section labels, and names the first one it finds.
     *
     * The walk matters: Compose prunes a clipped node from the accessibility
     * tree, so a single check at the top would pass for a section that merely
     * sat below the fold. Only scrolling the body gives "absent" a meaning.
     */
    private fun firstHiddenSectionOnScreen(): String? {
        fun found(): String? = hiddenSectionLabels.firstOrNull { label ->
            device.findObjects(By.textContains(label)).isNotEmpty()
        }
        repeat(16) {
            found()?.let { return it }
            dragUpHalf()
        }
        found()?.let { return it }
        // The walk has to reach the bottom, and a docked body is only ~72% of
        // the window; without this the sections below it are never looked at,
        // and "absent" would mean "not scrolled to".
        if (!expandToFullScreen()) return found()
        repeat(16) {
            found()?.let { return it }
            dragUpHalf()
        }
        return found()
    }

    // ---------- Developer Tools: opening it (proven pattern) ----------------

    private fun assertDevToolsOpened() {
        // The helper runs BEFORE the message is built. Kotlin evaluates the
        // arguments left to right, so a probe dump written into the message of
        // the call it is reporting on is taken before the walk has moved.
        val opened = openDeveloperToolsPanel(applicationChip, applicationBody)
        assertTrue(
            "Developer Tools must open on the Application panel from the Page Actions sheet — ${openTrace}\n${uiTree()}",
            opened
        )
        // The chip and its body prove the panel COMPOSED; this proves it then
        // finished reading the page, which is what every content assertion needs.
        assertTrue(
            "The Application panel must finish reading the page before its content can be asserted — ${openTrace}\n${uiTree()}",
            hasTextContains(readDoneLine, 20_000)
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
            val row = device.findObjects(By.desc(panelHeader)).firstOrNull()
            if (row != null && clickSmart(row)) {
                openTrace += "row=$step;tap;"
                if (hasDesc(overviewChip, 15_000)) {
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
     * The strip scrolls horizontally and has to: the chips do not all fit in a
     * 320dp window. A chip pushed off the right edge is clipped, and a clipped
     * node is one this suite must not tap by its stale bounds.
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
     * The strip scrolls horizontally, so a chip that answers a lookup can still
     * be half off an edge -- and a tap at the centre of a partly-clipped node
     * lands on whatever is beside it, while the node's own bounds stay
     * non-empty. Containment in the window is what is checked, never the centre
     * against a fraction of the screen.
     */
    private fun isFullyVisible(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        b.width() > 0 && b.left >= 0 && b.right <= device.displayWidth &&
            b.centerY() > device.displayHeight / 4 &&
            b.centerY() < device.displayHeight - 20
    } catch (_: Exception) { false }

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
        try { Thread.sleep(150) } catch (_: InterruptedException) { }
    }

    // ---------- Failure diagnostics -----------------------------------------

    /** Readable failure diagnostics, built at the moment of failure. */
    private fun uiTree(): String = try {
        val sb = StringBuilder()
        val probes = listOf(
            "'Page Actions' sheet" to By.text("Page Actions"),
            "'Developer tools' row" to By.desc(panelHeader),
            "overview panel chip" to By.desc(overviewChip),
            "application panel chip" to By.desc(applicationChip),
            "application panel body" to By.desc(applicationBody),
            "read-done line" to By.textContains(readDoneLine),
            "page heading" to By.text(pageHeading),
            "seeded marker" to By.text(seededMarker),
            "manifest name" to By.textContains(manifestName),
            "service worker" to By.textContains("sw-$tag.js"),
            "localStorage key" to By.textContains(localKey),
            "sessionStorage key" to By.textContains(sessionKey),
            "IndexedDB name" to By.textContains(databaseName),
            "cache name" to By.textContains(cacheName)
        )
        for ((label, selector) in probes) {
            val nodes = runCatching { device.findObjects(selector) }.getOrDefault(emptyList())
            sb.append(label).append(": count=").append(nodes.size).append('\n')
        }
        for (label in hiddenSectionLabels) {
            val nodes = runCatching { device.findObjects(By.textContains(label)) }.getOrDefault(emptyList())
            sb.append("hidden '$label': count=").append(nodes.size).append('\n')
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
