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
 * E2E for the multi-tab contract (the "tabs" regression):
 *
 *   BrowserActivity (':browser' process), one FRESH profile per run
 *     -> Tab A: the initial homepage tab loads page A via the omnibox
 *     -> "New tab" (page actions sheet) -> Tab B (homepage) -> omnibox loads
 *        page B into B. THE regression this pins: a New Tab must NEVER
 *        overwrite the current tab's page (the old loadUrl("about:home",
 *        newTab = true) early-return bug).
 *     -> tab grid: exactly "Tabs (2)", A's card shows A's title + URL
 *     -> switch to A -> A's page content is intact (own WebView, own history)
 *     -> switch to B -> B's content intact
 *     -> grid "+" creates Tab C -> "Tabs (3)", A and B cards unchanged
 *     -> close B from its card -> "Tabs (2)"
 *     -> A still shows page A; C is the (fresh) start page
 *     -> DB ground truth: the profile's open tabs are exactly A and C
 *
 * Every page carries a per-run marker (title + h1), so the assertions are
 * immune to leftovers from earlier runs on a dirty device. Navigation uses
 * the same UiAutomator patterns proven by BrowserNavigationE2eTest
 * (coordinate/shell taps, verification-driven retries, scroll-aware finds).
 */
@RunWith(AndroidJUnit4::class)
class TabsE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer

    /** Per-run marker suffix — unique tab titles/URLs across runs. */
    private val tag = (System.currentTimeMillis() % 100000).toString()
    private val profileName = "E2ETabs$tag"
    private val titleA = "TAB-A-$tag"
    private val titleB = "TAB-B-$tag"
    private val contentA = "PAGE-A-$tag"
    private val contentB = "PAGE-B-$tag"
    private lateinit var urlA: String
    private lateinit var urlB: String

    @Before
    fun setUp() {
        // Determinism: the runner's shared IP makes every fresh-profile boot
        // arm the organic network warning — suppress it (see E2eDeterminism).
        E2eDeterminism.suppressOrganicNetworkWarnings()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                return when {
                    path.startsWith("/a-$tag") -> html(titleA, contentA)
                    path.startsWith("/b-$tag") -> html(titleB, contentB)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        urlA = "$base/a-$tag"
        urlB = "$base/b-$tag"
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun html(title: String, body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(
            """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$title</title>
            </head><body style="font-size:24px; margin:24px;">
            <h1>$body</h1>
            </body></html>
            """.trimIndent()
        )

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

    private fun waitGone(text: String, timeoutMs: Long): Boolean =
        waitUntil(timeoutMs) { device.findObjects(By.text(text)).isEmpty() }

    private fun clickCenter(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        // SHELL TAP (input tap) — deterministic on the busy CI a11y pipeline
        // (Task 12 lesson; injected gestures can be silently swallowed).
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
        // 320x640 mdpi — deep settings screens run ~4000px there. Slow steps
        // (no fling) keep it a controlled scroll; the settle AFTER the drag
        // lets any residual momentum finish before the caller reads node
        // bounds (a tap on bounds captured mid-fling hits the void — CI
        // proven: Passwords/Wallet row taps landed on nothing).
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

    /** Scroll-aware click (off-screen grid rows are not in the a11y tree). */
    private fun clickTextWithScroll(text: String, attempts: Int = 24): Boolean {
        for (i in 1..attempts) {
            if (clickText(text, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    /** Scroll-aware presence check. */
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

    /**
     * TEMPORARY DIAGNOSTIC: a real screenshot of the device at a named moment.
     * The CI failure fallback pulls /sdcard/e2e-shots into the e2e-reports
     * artifact, so "the page loaded but the screen still shows the start
     * page" can be settled by looking rather than by inference.
     */
    private fun snap(name: String) {
        runCatching {
            device.executeShellCommand("mkdir -p /sdcard/e2e-shots")
            device.executeShellCommand("screencap -p /sdcard/e2e-shots/$name.png")
        }
    }

    /** Polls dumpsys until the IME is actually shown (focus really landed). */
    private fun waitImeShown(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (imeShown()) return true
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                }
        }
        return imeShown()
    }

    private fun hideImeIfNeeded() {
        if (imeShown()) {
            device.pressBack()
            device.waitForIdle(600)
        }
    }

    /**
     * A leftover modal sheet (site controls, page actions, profile switcher)
     * is its own window, and while it is up the WebView stops serving its
     * accessibility subtree: `By.text` cannot see loaded page content even
     * though the page is plainly rendered on screen (CI 01d5a06 — the
     * screenshot shows the page heading while every text probe returns
     * nothing). Back dismisses Compose modal sheets; two passes cover the
     * IME-then-sheet stack.
     */
    private fun dismissSheetIfAny() {
        val markers = listOf(
            "Clear site data", // site controls (shields)
            "Toggle JavaScript for this site",
            "Page Actions", // page-actions sheet header
            "Switch Profile" // profile switcher
        )
        repeat(2) {
            val up = markers.any { device.wait(Until.hasObject(By.text(it)), 250) }
            if (!up) return
            device.pressBack()
            device.waitForIdle(800)
        }
    }

    private fun engineUiUp(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (device.wait(Until.hasObject(By.descContains("Address bar")), 400)) return true
            if (hasText("Search or type URL", 400)) return true
            if (hasText("Privacy Dashboard", 400)) return true
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                }
        }
        return device.wait(Until.hasObject(By.descContains("Address bar")), 500)
    }

    /** The start page is up exactly when the address pill says so. */
    private fun homepageUp(timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc("Address bar: search or type URL")), timeoutMs) ||
            hasText("Privacy Dashboard", 2_000)

    private fun uiTree(): String = try {
        val texts = runCatching {
            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(60)
        }.getOrDefault(emptyList())
        val descs = runCatching {
            device.findObjects(By.descContains("")).mapNotNull { it.contentDescription }
                .distinct().take(30)
        }.getOrDefault(emptyList())
        // Forensics: whether the engine is foreground at all, and whether a
        // rendered WebView exists in the hierarchy (a page that loaded but
        // never attached shows no such node).
        val pkg = runCatching { device.currentPackageName }.getOrDefault("?")
        val webViews = runCatching { device.findObjects(By.clazz("android.webkit.WebView")).size }
            .getOrDefault(-1)
        val pills = runCatching { device.findObjects(By.descContains("Address bar")).size }
            .getOrDefault(-1)
        "PKG=$pkg WEBVIEWS=$webViews PILLS=$pills\nTEXTS: $texts\nDESCS: $descs"
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }

    // ---------- Bootstrap: ALWAYS a fresh, single-tab profile ----------------

    /**
     * Creates THIS test's own profile through the real dialog (the create
     * callback opens the engine bound to it), so the tab state starts from
     * exactly one start-page tab — deterministic counts on every run, dirty
     * device or not.
     */
    private fun bootstrapFreshEngine(): Boolean {
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)
        // First run: the empty-state CTA; later runs: the outlined
        // "add another" button (below the cards — scroll-aware).
        assertTrue(
            "Profile list or first-run state must appear",
            hasText("Your profiles", 90_000) || hasText("Create Profile", 90_000)
        )
        assertTrue(
            "Create Profile affordance must be reachable",
            clickTextWithScroll("Create Profile")
        )
        assertTrue("Create-profile dialog should open", hasText("Cancel", 8_000))
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8_000)
        assertTrue("Name text field must be visible", field != null)
        clickCenter(field!!)
        device.executeShellCommand("input text $profileName")
        device.waitForIdle(1_000)
        // Confirm = the 'Create Profile' button in the dialog row (Cancel is
        // the same row's open-signal — proven proximity pattern).
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
        assertTrue("Create dialog should close after confirm", waitGone("Cancel", 10_000))
        // The create callback opens the engine (a first profile directly; a
        // later one through the process-restart switch path). CI run 227ebc3
        // caught the restart racing the omnibox typing: the ':browser'
        // process was still bound to the PREVIOUS suite's profile, so the
        // first engine activity self-restarts (bind fail → kill + alarm) —
        // and on the CI emulator the restart alarm was deferred ~5 s, so the
        // doomed surface lived long enough for the typing to begin. The
        // STABILITY gate below rides that out: the surface must stay up
        // CONTINUOUSLY for 8 s — the doomed one never does.
        if (!engineUiStable(120_000)) return false
        // First-composition settle (Homepage JIT) before anyone types.
        device.waitForIdle(2_000)
        // RESTORE-COMPLETE gate (CI 88ec8fe forensics): the chrome AND the
        // default-homepage content compose BEFORE the ViewModel's tab
        // restore finishes on the 2-core runner — and the loadUrl coroutine
        // serializes behind `restored.first { it }`, so a URL committed
        // during the restore window can be lost to the restore's own
        // pageState writes. The tab-count badge ("1") only composes once
        // `viewModel.tabs` is non-empty — the restore's observable
        // completion signal. Best-effort: engineUiStable already proved the
        // surface itself is healthy.
        waitUntil(15_000) { device.findObjects(By.text("1")).isNotEmpty() }
        return true
    }

    /**
     * The engine surface must be up CONTINUOUSLY for [stableMs] before the
     * bootstrap returns — a surface that dies (process self-restart) resets
     * the window. 150 ms polls catch sub-half-second gaps between the doomed
     * and final surfaces.
     */
    private fun engineUiStable(totalMs: Long, stableMs: Long = 8_000): Boolean {
        val deadline = System.currentTimeMillis() + totalMs
        var firstSeen = 0L
        while (System.currentTimeMillis() < deadline) {
            val up = device.findObjects(By.descContains("Address bar")).isNotEmpty() ||
                device.findObjects(By.text("Privacy Dashboard")).isNotEmpty()
            val now = System.currentTimeMillis()
            if (up) {
                if (firstSeen == 0L) firstSeen = now
                if (now - firstSeen >= stableMs) return true
            } else {
                firstSeen = 0L
            }
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                }
        }
        return false
    }

    // ---------- The tab helpers -------------------------------------------

    /**
     * Loads [url] in the CURRENT tab through the real omnibox (omni_field is
     * the stable a11y hook) and waits for [contentMarker] in the page.
     *
     * CI forensics (runs 227ebc3 / 798d73c): typing raced the engine's first
     * frames — key events were dropped ("no window focus") and the
     * InputConnection died mid-typing while the Homepage composable was
     * still being JIT-compiled. Hardening layers:
     *  1. the IME must be SHOWN after focusing (dumpsys proof the
     *     connection is live) before anything is typed;
     *  2. the URL goes in via the accessibility ACTION_SET_TEXT (no key
     *     events at all — cannot be dropped by focus races; CI log-proven
     *     "UiObject2: Setting text to ..."). Only a THROWN exception falls
     *     back to the shell `input text` path — a read-back is deliberately
     *     NOT used (the cached a11y node cannot see the fresh text, and the
     *     destructive fallback mangled the URL: cursor 53 on 30 chars);
     *  3. the post-Go marker check is the authoritative verification.
     */
    private fun loadInOmnibox(url: String, contentMarker: String): Boolean {
        for (round in 1..4) {
            if (hasText(contentMarker, 500)) return true
            // Settle: the first seconds after engine boot churn the tree.
            device.waitForIdle(1_500)
            hideImeIfNeeded()
            dismissSheetIfAny()
            snap("tabs-r$round-pre")
            // CI 75822ed: after a failed round the IME can keep the a11y
            // ACTIVE window even while hidden-looking — findObject (active
            // window) went blind for 12 s while findObjects (all windows)
            // still saw the omnibox. Fall through to the all-windows sweep.
            val field = device.wait(Until.findObject(By.desc("omni_field")), 4_000)
                ?: device.findObjects(By.desc("omni_field")).firstOrNull()
                ?: continue
            var imeUp = false
            for (focus in 1..3) {
                clickSmart(field)
                imeUp = waitImeShown(5_000)
                if (imeUp) break
            }
            if (!imeUp) continue

            var typed = false
            // PRIMARY: the shell key-event path, IME-gated. CI 033cb23
            // DISPROVED the a11y ACTION_SET_TEXT on this field: the
            // contentDescription modifier creates an OUTER semantics node
            // (omni_field) whose INNER child holds the editable semantics —
            // performAction(ACTION_SET_TEXT) on the outer node returns false
            // SILENTLY, and 3 rounds x 5 s of fresh-lookup polls never saw
            // the URL land. The shell path is the one every CI-green field
            // uses (profile-name dialogs, agent composer — the same
            // BasicTextField + semantics structure), with the dumpsys IME
            // gate above ruling out dropped keystrokes.
            device.executeShellCommand("input keyevent KEYCODE_MOVE_END")
            device.clearFocusedField()
            device.waitForIdle(300)
            device.executeShellCommand("input text $url")
            device.waitForIdle(800)
            // Verify with a GLOBAL text search: the inner editable node
            // renders its content as text, so By.textContains finds it
            // wherever it lives in the tree (reading .text off the cached
            // omni_field node sees stale/empty properties).
            typed = device.wait(Until.hasObject(By.textContains(url)), 5_000)
            if (!typed) {
                // Fallback: the a11y set-text action (occasionally a
                // truncated keyboard is the only failure mode left).
                try {
                    field.setText(url)
                    device.waitForIdle(600)
                    typed = device.wait(Until.hasObject(By.textContains(url)), 5_000)
                } catch (_: Exception) {
                    typed = false
                }
            }
            if (!typed) continue

            // IME Go action -> onOmniBoxInput -> loadUrl (same tab).
            device.executeShellCommand("input keyevent 66")
            if (hasText(contentMarker, 15_000)) return true
            snap("tabs-r$round-postgo")
            // A second Enter only makes sense while the IME still owns the
            // field — a bare Enter with no IME went to the APP and on the
            // homepage it backgrounds the engine (CI 227ebc3: that stray key
            // left the app backgrounded and every later round found no
            // omni_field in the ACTIVE window).
            if (imeShown()) {
                device.pressEnter()
                if (hasText(contentMarker, 15_000)) return true
            }
        }
        return false
    }

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

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

    private fun openTabGrid(): Boolean {
        if (!clickDesc("Open tab grid", 6_000)) return false
        return waitUntil(6_000) { device.findObjects(By.textStartsWith("Tabs (")).isNotEmpty() }
    }

    /** True while the tab grid route is on screen. */
    private fun gridUp(): Boolean =
        device.findObjects(By.textStartsWith("Tabs (")).isNotEmpty()

    private fun tabCountTitle(): String? =
        device.findObjects(By.textStartsWith("Tabs ("))
            .firstOrNull()?.text

    /** A tab card is rendered with BOTH its title and its URL text nodes. */
    private fun cardShows(title: String, url: String): Boolean =
        device.findObjects(By.text(title)).isNotEmpty() &&
            device.findObjects(By.text(url)).isNotEmpty()

    /**
     * Taps the tab card whose title is [title] (walks up to the clickable
     * card) and verifies the grid closed — selecting a card returns to the
     * browsing surface with that tab active.
     */
    private fun selectCardByTitle(title: String): Boolean {
        for (i in 1..12) {
            val node = device.wait(Until.findObject(By.text(title)), 1_500)
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
     * Closes the tab card whose title is [title] via that card's own
     * "Close tab" button. The close button is located geometrically: the
     * card's bounds come from the first CLICKABLE ancestor of the title node
     * (the whole card is the touch target), and the close button is the
     * "Close tab" node whose center lies inside those bounds.
     */
    private fun closeCardByTitle(title: String): Boolean {
        for (i in 1..12) {
            val titleNode = device.wait(Until.findObject(By.text(title)), 1_500)
            if (titleNode == null) {
                dragUpQuarter()
                continue
            }
            val card = clickableAncestorOf(titleNode)
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

    /** DB ground truth: the active profile's open tabs (test-process graph). */
    private fun openTabsGroundTruth(): List<Pair<String, String>> = runBlocking {
        val appGraph = (targetContext.applicationContext as com.roombrowser.RoomBrowserApp).graph
        val profileId = appGraph.appState.activeProfileIdSnapshot()
            ?: return@runBlocking emptyList()
        appGraph.database.tabDao().openTabs(profileId)
            .map { it.title to it.url }
    }

    // ---------- The contract ------------------------------------------------

    @Test
    fun new_tabs_never_overwrite_and_switching_preserves_each_tab() {
        assertTrue("Engine must come up on a fresh profile", bootstrapFreshEngine())

        // ---- 1. Tab A = the initial start-page tab, loaded with page A ----
        // The action runs BEFORE the assertion, in its own statement: Kotlin
        // evaluates assertTrue's MESSAGE argument before its CONDITION, so an
        // inline "${uiTree()}" photographs the tree before the load has even
        // started. That is what made CI 01d5a06's failure message report a
        // pristine start page (clock 4:20) while the screenshots at the same
        // moment showed the page loaded and rendered (clock 4:21).
        val pageALoaded = loadInOmnibox(urlA, contentA)
        assertTrue(
            "Page A must load through the omnibox\n${uiTree()}\n" +
                "DB TABS (persisted rows): ${openTabsGroundTruth()}\n" +
                "(urlA=$urlA — a row still saying about:home means the load " +
                "never finished; a row with urlA means the finish landed and " +
                "the UI state was clobbered afterwards)",
            pageALoaded
        )

        // ---- 2. New tab B: the start page must NOT clobber tab A ---------
        assertTrue(
            "Page actions 'New tab' must open a fresh start page",
            openSheetEntry("New tab") { homepageUp(8_000) }
        )
        assertTrue(
            "Page B must load into the NEW tab\n${uiTree()}",
            loadInOmnibox(urlB, contentB)
        )

        // ---- 3. Grid: exactly two tabs; A's card kept its page ------------
        assertTrue("Tab grid must open", openTabGrid())
        assertTrue(
            "Grid must show exactly two tabs (found ${tabCountTitle()})",
            waitUntil(6_000) { tabCountTitle() == "Tabs (2)" }
        )
        assertTrue(
            "Tab A's card must still show its page after the new tab",
            hasTextWithScroll(titleA) && cardShows(titleA, urlA)
        )
        assertTrue("Tab B's card must show its page", cardShows(titleB, urlB))

        // ---- 4. Switch to A: content intact (per-tab WebView) -------------
        assertTrue("Tab A's card must be selectable", selectCardByTitle(titleA))
        assertTrue(
            "Switching back to A must show A's page, not B's\n${uiTree()}",
            hasText(contentA, 15_000)
        )

        // ---- 5. Switch to B: intact ---------------------------------------
        assertTrue("Tab grid must re-open", openTabGrid())
        assertTrue("Tab B's card must be selectable", selectCardByTitle(titleB))
        assertTrue(
            "Switching back to B must show B's page\n${uiTree()}",
            hasText(contentB, 15_000)
        )

        // ---- 6. Create C from the grid "+": A and B unchanged -------------
        // Step 5's selectCardByTitle() CLOSES the grid, and the "+" lives in
        // the grid: without re-opening it first the desc poll runs against
        // the browsing surface and never matches (CI 36893513963 — "New tab"
        // polled for 6 s, "Node not found", while the surface was up).
        assertTrue("Tab grid must re-open (for C)", openTabGrid())
        assertTrue("Grid '+' (New tab) must be clickable", clickDesc("New tab", 6_000))
        assertTrue("Tab C must land on the start page", homepageUp(10_000))
        assertTrue("Tab grid must re-open after C", openTabGrid())
        assertTrue(
            "Grid must show exactly three tabs (found ${tabCountTitle()})",
            waitUntil(6_000) { tabCountTitle() == "Tabs (3)" }
        )
        assertTrue(
            "A and B must be unchanged after creating C",
            hasTextWithScroll(titleA) && cardShows(titleA, urlA) && cardShows(titleB, urlB)
        )

        // ---- 7. Close B from its card --------------------------------------
        assertTrue("Tab B's close button must be clickable", closeCardByTitle(titleB))
        assertTrue(
            "Grid must drop to two tabs after closing B (found ${tabCountTitle()})",
            waitUntil(6_000) { tabCountTitle() == "Tabs (2)" }
        )

        // ---- 8. A and C are still correct ----------------------------------
        assertTrue("Tab A's card must still be selectable", selectCardByTitle(titleA))
        assertTrue(
            "Tab A must still show its page after B was closed\n${uiTree()}",
            hasText(contentA, 15_000)
        )
        assertTrue("Tab grid must re-open (for C)", openTabGrid())
        // The fresh start-page tab's card falls back to its URL as the title.
        assertTrue("Tab C's card must be selectable", selectCardByTitle("about:home"))
        assertTrue("Tab C must be the start page", homepageUp(10_000))

        // ---- 9. DB ground truth: exactly A and C remain open ---------------
        val openTabs = openTabsGroundTruth()
        assertTrue(
            "The profile must have exactly A and C open in the DB (found $openTabs)",
            openTabs.size == 2 &&
                openTabs.any { it.first == titleA && it.second == urlA } &&
                openTabs.any { it.second == "about:home" }
        )
    }
}
