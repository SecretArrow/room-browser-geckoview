package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * E2E for the bottom-bar NAVIGATION contract (the "supaya works" fix):
 *
 *   MainActivity (default process)
 *     -> first-run welcome / profile list -> engine opens
 *   BrowserActivity (':browser' process), cold-started with EXTRA_INITIAL_URL
 *     -> http://localhost:PORT/page1 (local MockWebServer, cleartext allowed)
 *     -> click the in-page link -> /page2 (real WebView navigation)
 *     -> bottom-bar "Go back" (desc) -> page ONE is visible again
 *     -> bottom-bar "Go forward" (desc) -> page TWO is visible again
 *     -> omnibox "Reload" (desc) -> server hit counter for /page2 rises
 *     -> SYSTEM Back on a page with no back history left:
 *        "Exit Room Browser?" confirmation appears (app does NOT leave)
 *        -> "Cancel" -> still on page TWO, dialog gone
 *        -> Back again -> "Back to start page" -> homepage omnibox visible
 *        -> Back on the HOMEPAGE -> app backgrounds (engine stays alive)
 *
 * History state is live-tracked via doUpdateVisitedHistory; before that fix
 * canGoBack/canGoForward were never reported and the nav buttons stayed
 * grey forever, and system Back backgrounded the app from ANY page.
 */
@RunWith(AndroidJUnit4::class)
class BrowserNavigationE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer
    private val page1Hits = AtomicInteger(0)
    private val page2Hits = AtomicInteger(0)

    /** Every path this test's server was actually asked for, and how often. */
    private val hitsByPath = ConcurrentHashMap<String, AtomicInteger>()

    private fun countHit(path: String): AtomicInteger =
        hitsByPath.computeIfAbsent(path) { AtomicInteger(0) }

    @Before
    fun setUp() {
        // Determinism: the runner's shared IP makes every fresh-profile boot
        // arm the organic network warning — suppress it (see E2eDeterminism).
        E2eDeterminism.suppressOrganicNetworkWarnings()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                countHit(path).incrementAndGet()
                return when {
                    path.startsWith("/page1") -> {
                        page1Hits.incrementAndGet()
                        html(
                            """
                            <h1>ROOM-E2E-PAGE-ONE</h1>
                            <p><a href="/page2">Goto page 2</a></p>
                            """.trimIndent()
                        )
                    }
                    path.startsWith("/page2") -> {
                        page2Hits.incrementAndGet()
                        html(
                            """
                            <h1>ROOM-E2E-PAGE-TWO</h1>
                            <p><a href="/page1">Goto page 1</a></p>
                            """.trimIndent()
                        )
                    }
                    path.startsWith("/geo") -> html(
                        """
                        <h1>ROOM-E2E-GEO</h1>
                        <div id="out">GEO-WAITING</div>
                        <script>
                          var out = document.getElementById('out');
                          // localhost is a secure context, so the API exists;
                          // the branch keeps a missing API distinguishable
                          // from a refusal instead of looking like the same
                          // silent nothing.
                          if (!navigator.geolocation) {
                            out.textContent = 'GEO-UNAVAILABLE';
                          } else {
                            navigator.geolocation.getCurrentPosition(
                              function () { out.textContent = 'GEO-POSITION'; },
                              function (e) { out.textContent = 'GEO-DENIED-' + e.code; }
                            );
                          }
                        </script>
                        """.trimIndent()
                    )
                    path.startsWith("/media") -> html(
                        """
                        <h1>ROOM-E2E-MEDIA</h1>
                        <div id="out">MEDIA-WAITING</div>
                        <script>
                          var out = document.getElementById('out');
                          if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                            out.textContent = 'MEDIA-UNAVAILABLE';
                          } else {
                            navigator.mediaDevices.getUserMedia({ audio: true, video: true })
                              .then(function (s) {
                                out.textContent = 'MEDIA-STREAM';
                                s.getTracks().forEach(function (t) { t.stop(); });
                              })
                              .catch(function (e) { out.textContent = 'MEDIA-DENIED-' + e.name; });
                          }
                        </script>
                        """.trimIndent()
                    )
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

    private fun html(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(
            """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            </head><body style="font-size:24px; margin:24px;">
            $body
            </body></html>
            """.trimIndent()
        )

    // ---------- UiAutomator helpers (proven patterns) --------------------

    private fun hasText(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs)

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

    private fun waitGone(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.findObjects(By.text(text)).isEmpty()) return true
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                }
        }
        return device.findObjects(By.text(text)).isEmpty()
    }

    private fun clickCenter(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        // SHELL TAP (input tap), not gesture injection — the CI runner's busy
        // a11y pipeline silently swallows injected gestures (Task 12 lesson).
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

    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    /** Chromium/WebView net log captured ON-DEVICE at the moment of failure —
     *  the runner-level logcat dump only fires at the END of the whole gradle
     *  run, long after this test's window has scrolled away. */
    private fun chromiumLog(): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "800"))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.lineSequence()
            .filter {
                it.contains("chromium", true) || it.contains("ERR_", true) ||
                    it.contains("SSL", true) || it.contains("cr_")
            }
            .toList()
            .takeLast(80)
            .joinToString("\n")
    } catch (_: Exception) {
        "(logcat unavailable)"
    }

    /** Readable failure diagnostics (readable from the e2e-reports artifact). */
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
                            "'ROOM-E2E-PAGE-ONE'" to By.text("ROOM-E2E-PAGE-ONE"),
                            "'ROOM-E2E-PAGE-TWO'" to By.text("ROOM-E2E-PAGE-TWO"),
                            "'Goto page 2' link" to By.text("Goto page 2"),
                            "'Go back' button" to By.desc("Go back"),
                            "'Wallet' button" to By.desc("Wallet"),
                            "'Go forward' button" to By.desc("Go forward"),
                            "'Reload' button (omnibox row)" to By.desc("Reload"),
                            "'Open Room Agent' button" to By.desc("Open Room Agent"),
                            "'Exit Room Browser?' dialog" to By.text("Exit Room Browser?"),
                            "'Back to start page' action" to By.text("Back to start page"),
                            "omni_field" to By.desc("omni_field"),
                            // The permission sheets. Probed explicitly because "the page
                            // loaded but its <h1> is not in the tree" has two very different
                            // causes: the sheet is up and its modal window is the ACTIVE one,
                            // so UiAutomator no longer returns the activity window's nodes at
                            // all — or the sheet never came up, which is a real bug in the
                            // permission path. The message alone cannot tell them apart.
                            "'Share your location?' sheet" to By.text("Share your location?"),
                            "'Share your camera and microphone?' sheet"
                                to By.text("Share your camera and microphone?"),
                            "'Deny' button" to By.text("Deny"),
                            "'Allow' button" to By.text("Allow")
                        )
                        for ((label, selector) in probes) {
                            val nodes = runCatching { device.findObjects(selector) }.getOrDefault(emptyList())
                            sb.append(label).append(": count=").append(nodes.size)
                            nodes.take(2).forEach { n ->
                                sb.append(" bounds=").append(runCatching { n.visibleBounds }.getOrNull())
                                    .append(" clickable=").append(runCatching { n.isClickable }.getOrDefault(false))
                            }
                            sb.append('\n')
                        }
                        val texts = runCatching {
                            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(60)
                        }.getOrDefault(emptyList())
                        sb.append("VISIBLE TEXTS: ").append(texts).append('\n')
                        sb.append("page1Hits=").append(page1Hits.get())
                            .append(" page2Hits=").append(page2Hits.get()).append('\n')
                        // The server is the only witness that cannot lie about whether the
                        // engine ever ASKED for the page. Without it a blank document, a
                        // blocked request and a page that rendered offscreen all read the
                        // same from the device side: onPageFinished, right URL, no text.
                        sb.append("server hits: ").append(
                            hitsByPath.entries.sortedBy { it.key }
                                .joinToString(" ") { "${it.key}=${it.value.get()}" }
                                .ifEmpty { "(none)" }
                        ).append('\n')
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

    /**
     * A real screenshot of the device at a named moment. The CI failure
     * fallback pulls /sdcard/e2e-shots into the e2e-reports artifact, so
     * "the page loaded but the screen shows something else" is settled by
     * looking instead of by inference.
     */
    private fun snap(name: String) {
        runCatching {
            device.executeShellCommand("mkdir -p /sdcard/e2e-shots")
            device.executeShellCommand("screencap -p /sdcard/e2e-shots/$name.png")
        }
    }

    /**
     * Asserts a wait and, when it fails, dumps the tree AS IT IS AFTER the
     * wait — never as an eagerly evaluated message argument.
     *
     * `assertTrue("...${uiTree()}", hasText(x, 30_000))` builds the dump
     * BEFORE hasText ever runs, so the failure message describes the screen
     * one step earlier. That is not a cosmetic difference: it reported the
     * start page for the geolocation test and the geolocation error page for
     * the camera test, which are the states each test INHERITED from the one
     * before it, and it sent this suite's debugging after the wrong page.
     */
    private fun assertLoaded(what: String, snapshot: String, loaded: () -> Boolean) {
        if (!loaded()) {
            snap(snapshot)
            fail("$what\n${uiTree()}")
        }
    }

    private fun launchMainActivity() {
        val intent = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                setClassName(targetContext.packageName, "com.roombrowser.main.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
    }

    private fun engineUiUp(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.wait(Until.hasObject(By.descContains("Address bar")), 400)) return true
            if (device.wait(Until.hasObject(By.text("Search or type URL")), 400)) return true
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                }
        }
        return device.wait(Until.hasObject(By.descContains("Address bar")), 500)
    }

    /**
     * Slow half-screen drag (3/4 → 1/4). The CI emulator's default profile
     * is 320x640 mdpi — anything below ~570px is off-screen, and off-screen
     * nodes are NOT exposed to the a11y tree (proven in CI run 9399640).
     * Slow steps = controlled scroll, no fling.
     */
    private fun dragUpHalf() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 100
        )
        device.waitForIdle(600)
    }

    /** Scroll-aware presence check. */
    private fun hasTextScrollable(text: String, attempts: Int = 24): Boolean {
        for (i in 1..attempts) {
            if (hasText(text, 1_500)) return true
            dragUpHalf()
        }
        return false
    }

    /** Scroll-aware click. */
    private fun clickTextScrollable(text: String, attempts: Int = 24): Boolean {
        for (i in 1..attempts) {
            if (clickText(text, 1_500)) return true
            dragUpHalf()
        }
        return false
    }

    /** Same bootstrap contract as E2EBrowseFlowTest (create-or-OPEN profile). */
    private fun openEngineFromLauncher(): Boolean {
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)
        assertTrue(
            "First-run welcome (or profile list) should appear",
            hasText("Create Profile", 90_000) ||
                hasText("OPEN", 10_000) ||
                // Cards can push the create affordance below the fold on the
                // small CI screen — scroll before giving up.
                hasTextScrollable("Create Profile")
        )
        val alreadyHasProfile = device.findObjects(By.text("OPEN")).isNotEmpty()
        if (!alreadyHasProfile) {
            var dialogOpen = false
            for (attempt in 1..3) {
                assertTrue(
                    "Welcome 'Create Profile' button must be visible",
                    clickTextScrollable("Create Profile", attempts = 4)
                )
                dialogOpen = hasText("Cancel", 6_000)
                if (dialogOpen) break
            }
            assertTrue("Create-profile dialog should open (retry failed)", dialogOpen)
            val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
                ?: device.wait(Until.findObject(By.text("Name")), 5_000)
            assertTrue("Name text field must be visible", field != null)
            var typed = false
            for (attempt in 1..2) {
                clickCenter(field!!)
                device.executeShellCommand("input text E2E_Nav")
                device.waitForIdle(1_500)
                if (device.findObjects(By.textContains("E2E_Nav")).isNotEmpty()) {
                    typed = true
                    break
                }
            }
            assertTrue("Profile name must be typed into the field", typed)
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
            assertTrue("Create dialog should close after confirm", waitGone("Cancel", 8_000))
            if (!engineUiUp(30_000)) {
                assertTrue("Profile card with OPEN must appear after creation", hasText("OPEN", 10_000))
                assertTrue("OPEN button must be clickable", clickText("OPEN", 5_000))
            }
        } else {
            assertTrue("OPEN button must be clickable", clickText("OPEN", 5_000))
        }
        return engineUiUp(30_000)
    }

    /**
     * Cold-starts the engine activity with the mock URL as EXTRA_INITIAL_URL
     * (the persisted active profile is picked up automatically) — the page
     * opens in a NEW tab whose fresh engine has NO back history, exactly the
     * state where system Back used to kick the user out of the app.
     */
    private fun launchEngineAt(url: String) {
        targetContext.startActivity(
            Intent()
                .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("com.roombrowser.extra.INITIAL_URL", url)
        )
    }

    /**
     * Cold-starts the engine activity with the mock URL as EXTRA_INITIAL_URL
     * (the persisted active profile is picked up automatically) — the page
     * opens in a NEW tab whose fresh engine has NO back history, exactly the
     * state where system Back used to kick the user out of the app. Waits
     * for page ONE's marker, which is the page every caller of this helper
     * navigates to.
     *
     * The permission tests must NOT use this: their pages raise a modal
     * sheet while loading, and a modal sheet's dialog window is the ACTIVE
     * one — the only window UiAutomator returns nodes from — so their marker
     * is unreachable by construction. They use [launchEngineAt] and witness
     * the load through [serverHits] instead.
     */
    private fun openEngineAt(url: String): Boolean {
        launchEngineAt(url)
        return hasText("ROOM-E2E-PAGE-ONE", 30_000)
    }

    /** How many times this test's server was asked for [path]. The one
     *  witness that a modal dialog window cannot hide. */
    private fun serverHits(path: String): Int = hitsByPath[path]?.get() ?: 0

    // ---------- The contract ------------------------------------------------

    @Test
    fun bottom_bar_back_forward_reload_and_system_back_confirmation() {
        val base = server.url("/").toString().trimEnd('/')
        assertTrue("Engine must be reachable from the launcher", openEngineFromLauncher())

        // ---- 1. Open page ONE in a fresh tab (no back history) ------------
        assertTrue(
            "Page ONE must load via EXTRA_INITIAL_URL\n${uiTree()}",
            openEngineAt("$base/page1")
        )

        // ---- 2. Real in-page navigation: ONE -> TWO -----------------------
        assertTrue(
            "The 'Goto page 2' link must be clickable\n${uiTree()}",
            clickText("Goto page 2", 15_000)
        )
        if (!hasText("ROOM-E2E-PAGE-TWO", 12_000)) {
            // Defense-in-depth against a swallowed first tap on a busy runner
            // (Task 12 lesson: the a11y pipeline can drop injected input).
            clickText("Goto page 2", 5_000)
        }
        assertTrue(
            "Page TWO must load after the link click\n${uiTree()}\nCHROMIUM LOG:\n${chromiumLog()}",
            hasText("ROOM-E2E-PAGE-TWO", 15_000)
        )

        // ---- 3. Bottom-bar Back: TWO -> ONE (the old always-grey bug) -----
        assertTrue(
            "Bottom-bar 'Go back' must be clickable\n${uiTree()}",
            clickDesc("Go back", 10_000)
        )
        assertTrue(
            "Back must return to page ONE\n${uiTree()}",
            hasText("ROOM-E2E-PAGE-ONE", 20_000)
        )

        // ---- 4. Bottom-bar Forward: ONE -> TWO ----------------------------
        assertTrue(
            "Bottom-bar 'Go forward' must be clickable\n${uiTree()}",
            clickDesc("Go forward", 10_000)
        )
        assertTrue(
            "Forward must return to page TWO\n${uiTree()}",
            hasText("ROOM-E2E-PAGE-TWO", 20_000)
        )

        // ---- 5. Reload: the server must see a fresh hit -------------------
        // NOT the bottom-bar slot any more: that slot belongs to the agent
        // whenever "Show AI Agent button" is off, which is the default and
        // therefore this run's state (the pill has no other entry point
        // inside the page). What the step verifies is unchanged — a reload
        // re-requests the page — so it drives the control that is always
        // there, the one in the omnibox row. Same dual role as the bar's:
        // it reads "Stop loading" while a load is in flight.
        val hitsBefore = page2Hits.get()
        assertTrue(
            "Reload must be clickable\n${uiTree()}",
            clickDesc("Reload", 10_000)
        )
        assertTrue(
            "Reload must re-request /page2 (hits before=$hitsBefore, now=${page2Hits.get()})\n${uiTree()}",
            waitUntil(15_000) { page2Hits.get() > hitsBefore }
        )

        // ---- 6. SYSTEM Back with web history: walks BACK like a browser ---
        // (the BackHandler's canGoBack branch — previously dead code, Back
        // backgrounded the app from ANY page). History is [page1, page2] and
        // we are on page2, so Back must land on page ONE.
        device.pressBack()
        device.waitForIdle(1_000)
        assertTrue(
            "System Back must walk the web history back to page ONE\n${uiTree()}",
            hasText("ROOM-E2E-PAGE-ONE", 20_000)
        )

        // ---- 7. System Back with NO history left: CONFIRM, never exit -----
        // We are on page ONE — the FIRST entry of this tab's history.
        device.pressBack()
        device.waitForIdle(1_000)
        assertTrue(
            "System Back must show the exit confirmation dialog, not leave the app\n${uiTree()}",
            hasText("Exit Room Browser?", 8_000)
        )
        assertTrue("App must still be in the foreground after Back", device.currentPackageName == targetContext.packageName)

        // ---- 8. Cancel: the dialog closes, the page stays -----------------
        assertTrue("'Cancel' must be clickable", clickText("Cancel", 5_000))
        assertTrue("Dialog must close after Cancel", waitGone("Exit Room Browser?", 8_000))
        assertTrue(
            "Page ONE must still be visible after Cancel\n${uiTree()}",
            hasText("ROOM-E2E-PAGE-ONE", 10_000)
        )

        // ---- 9. Back to start page via the confirmation -------------------
        device.pressBack()
        device.waitForIdle(1_000)
        assertTrue("Exit confirmation must appear again", hasText("Exit Room Browser?", 8_000))
        assertTrue(
            "'Back to start page' must be clickable\n${uiTree()}",
            clickText("Back to start page", 5_000)
        )
        // Homepage marker — the SAME hedge the older E2EBrowseFlowTest uses
        // (engineUiUp): on the Compose a11y bridge the omnibox placeholder
        // is frequently MERGED into the address-bar row's semantics and not
        // exposed as a standalone text node, so assert by homepage-only
        // texts first (CI dump-proven: Privacy Dashboard / Quick Access /
        // Good morning were all visible while "Search or type URL" wasn't).
        assertTrue(
            "The start page must be visible after 'Back to start page'\n${uiTree()}",
            waitUntil(15_000) {
                device.findObjects(By.text("Privacy Dashboard")).isNotEmpty() ||
                    device.findObjects(By.text("Quick Access")).isNotEmpty() ||
                    device.findObjects(By.textContains("Search or type URL")).isNotEmpty() ||
                    device.findObjects(By.descContains("Address bar: search or type URL")).isNotEmpty()
            }
        )

        // ---- 10. Homepage Back: instant background (existing contract) ----
        device.pressBack()
        device.waitForIdle(1_000)
        assertTrue(
            "Back on the homepage must background the app (engine stays alive)\n${uiTree()}",
            waitUntil(6_000) { device.currentPackageName != targetContext.packageName }
        )
    }

    /**
     * A second launch of a RUNNING engine must open its URL.
     *
     * BrowserActivity is `launchMode="singleTask"`, so when the engine is
     * already up the system reuses this instance and delivers the intent to
     * onNewIntent — onCreate never runs again. Reading EXTRA_INITIAL_URL
     * only in onCreate therefore dropped the URL entirely: a deep link, a
     * share target or `am start` against a backgrounded engine did nothing
     * at all, and the browser stayed on whatever page it was showing.
     *
     * The launch here deliberately carries NO FLAG_ACTIVITY_CLEAR_TASK. That
     * flag is what the other tests use to force a cold start through
     * onCreate, and it is exactly the path that already worked.
     */
    @Test
    fun a_second_launch_opens_its_url_in_the_running_engine() {
        val base = server.url("/").toString().trimEnd('/')
        assertTrue("Engine must be reachable from the launcher", openEngineFromLauncher())

        assertLoaded("Page ONE must load first", "relaunch-1-page-one-missing") {
            openEngineAt("$base/page1")
        }

        // The engine is RUNNING and showing page ONE. Same class, no
        // CLEAR_TASK: the singleTask instance is reused.
        targetContext.startActivity(
            Intent()
                .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("com.roombrowser.extra.INITIAL_URL", "$base/page2")
        )

        assertLoaded("A second launch must open its URL", "relaunch-2-url-dropped") {
            hasText("ROOM-E2E-PAGE-TWO", 20_000)
        }
    }

    /**
     * The bottom bar's fifth slot is the WALLET, and Share did not leave the
     * app with it.
     *
     * Two separate regressions are worth pinning here. The wallet used to be
     * reachable only from Browser settings, so a swap that quietly dropped the
     * button would put it back behind three taps with nothing failing. And
     * since "Share page" left the bar, its Page Actions row is now the ONLY
     * way to share a link — deleting that row as dead code would remove the
     * feature, and nothing else asserts it.
     */
    @Test
    fun the_bottom_bar_opens_the_wallet_and_share_survived_the_move() {
        val base = server.url("/").toString().trimEnd('/')
        assertTrue("Engine must be reachable from the launcher", openEngineFromLauncher())
        assertLoaded("Page ONE must load first", "wallet-slot-page-one-missing") {
            openEngineAt("$base/page1")
        }

        assertTrue(
            "The bottom bar must offer the wallet\n${uiTree()}",
            device.wait(Until.hasObject(By.desc("Wallet")), 10_000)
        )
        assertTrue(
            "Sharing must not also still occupy a bottom-bar slot\n${uiTree()}",
            device.findObjects(By.desc("Share page")).isEmpty()
        )

        // The sheet takes the active window, so this must be asserted against
        // the sheet's own tree, after it is up.
        assertTrue(
            "Page Actions must open\n${uiTree()}",
            clickDesc("Page actions and settings", 10_000)
        )
        // Scroll-aware: Share is the 13th row of the sheet, well below the
        // fold on the 320x640 CI profile, and an off-screen node is not in
        // the a11y tree at all — a plain By.text probe fails on a row that is
        // present and correct.
        assertTrue(
            "Sharing a link must still be reachable from Page Actions\n${uiTree()}",
            hasTextScrollable("Share", attempts = 8)
        )
    }

    /**
     * A page's location request must reach the USER and be SETTLED.
     *
     * This test lives here because this class owns the local HTTP server and
     * the engine-launch/tree helpers the flow needs, and because it is the
     * same browser-chrome contract as its neighbours — the sheet had no UI at
     * all, so every request was left un-invoked and the page's promise stayed
     * pending for the life of the document.
     *
     * The Deny branch is asserted rather than Allow on purpose: allowing a
     * position would need a fix from the emulator's console, and a test that
     * waits for one is a test that flakes. A refusal is fully deterministic —
     * it must arrive as PERMISSION_DENIED (code 1), which also proves the
     * callback was really INVOKED rather than dropped.
     */
    @Test
    fun a_location_request_is_raised_as_a_sheet_and_settled_by_deny() {
        val base = server.url("/").toString().trimEnd('/')
        // The sheet names the origin that ASKED, so the expected name is the
        // one this test actually navigated to. MockWebServer hands out
        // "localhost" (not 127.0.0.1), and hardcoding the other spelling made
        // this assertion demand a string the app was right not to show —
        // invisible until the marker assertions above stopped failing first.
        val askingHost = java.net.URI(base).host
        assertTrue("Engine must be reachable from the launcher", openEngineFromLauncher())

        launchEngineAt("$base/geo")

        // The SHEET is the contract, and it is asserted first on purpose.
        // The page asks for a position while it parses, so the sheet is up
        // before its <h1> could ever be read — and while a modal sheet is
        // up, its dialog window is the ACTIVE one, which is the only window
        // UiAutomator returns nodes from. Asserting the marker first asks
        // the framework for a node it has already stopped handing out, which
        // is exactly how this test failed while the browser was correct.
        assertLoaded("The location sheet must be raised", "geo-1-sheet-never-raised") {
            hasText("Share your location?", 30_000)
        }
        assertLoaded("The sheet must name the host that asked", "geo-2-sheet-unnamed") {
            hasText(askingHost, 5_000)
        }

        // The page really loaded — witnessed by the server, which a dialog
        // window cannot hide the way it hides the WebView's accessibility
        // nodes.
        assertLoaded("The engine must have requested /geo", "geo-3-page-never-requested") {
            serverHits("/geo") > 0
        }

        assertTrue("Deny must be tappable\n${uiTree()}", clickText("Deny", 10_000))

        assertLoaded("Denying must SETTLE the page's request", "geo-4-not-settled") {
            waitUntil(20_000) {
                device.findObjects(By.text("GEO-DENIED-1")).isNotEmpty()
            }
        }
    }

    /**
     * The camera/microphone half of the same contract.
     *
     * Deny again, for the same reason the location test denies: Allow is two
     * answers (the user's and Android's) and the second one opens a system
     * dialog that only a device with a camera can settle. Deny is one answer,
     * and the page must receive it as NotAllowedError — the rejection that
     * proves the request was ANSWERED rather than left pending.
     *
     * The sheet's title is asserted as well as the denial: the title is what
     * distinguishes a camera grant from a location grant, and a sheet that
     * said the wrong one would be a consent screen that lies.
     */
    @Test
    fun a_camera_request_is_raised_as_a_sheet_and_settled_by_deny() {
        val base = server.url("/").toString().trimEnd('/')
        assertTrue("Engine must be reachable from the launcher", openEngineFromLauncher())

        launchEngineAt("$base/media")

        // Sheet first, for the same reason as the location test: getUserMedia
        // runs as the page parses, so the modal window is already the active
        // one by the time anything else could be read.
        assertLoaded("The camera/microphone sheet must be raised", "media-1-sheet-never-raised") {
            hasText("Share your camera and microphone?", 30_000)
        }

        assertLoaded("The engine must have requested /media", "media-2-page-never-requested") {
            serverHits("/media") > 0
        }

        assertTrue("Deny must be tappable\n${uiTree()}", clickText("Deny", 10_000))

        assertLoaded("Denying must REJECT the page's getUserMedia", "media-3-not-rejected") {
            waitUntil(20_000) {
                device.findObjects(By.text("MEDIA-DENIED-NotAllowedError")).isNotEmpty()
            }
        }
    }
}
