package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * E2E for PAGE-SCRIPT DELIVERY: the app's page-world scripts must actually
 * reach the page in this edition.
 *
 * GEKOVIEW-ONLY. The WebView edition delivers the same three scripts through
 * `WebViewCompat.addDocumentStartJavaScript`, which requires
 * `WebViewFeature.DOCUMENT_START_SCRIPT` -- absent on the CI emulator's WebView
 * 83 -- so the same probe would fail there for a property of the test device
 * rather than of the app. Here the scripts travel as a WebExtension content
 * script and are delivered unconditionally, so what this asserts is the app.
 *
 * WHY THIS TEST EXISTS. Page scripts were posted across the isolated/page world
 * boundary as a structured object, and a structured object does not survive
 * that boundary: the page half read `scripts.vault` as `undefined` and
 * evaluated nothing. Nothing logged a failure, because the relay, the manifest
 * and the receiver were all present and correct; and nothing failed visibly,
 * because `main.js` defines `window.RoomWallet` and `window.RoomVault` itself.
 * The symptom was the one the owner reported -- a successful login on a real
 * page never offered to save the password. The wallet provider and the device
 * shim rode the same payload and went missing with it.
 *
 * THE PROBE CANNOT PASS VACUOUSLY. Every marker it reads is defined ONLY by an
 * injected script: `__roomVaultInstalled`, `__roomVaultFill` and the wrapped
 * `HTMLFormElement.prototype.submit` come from the vault capture script, and
 * `window.__roomWalletInstalled` / `window.ethereum` come from the wallet
 * provider. `window.RoomVault` and `window.RoomWallet` are deliberately NOT
 * probed -- `main.js` writes both unconditionally, so a probe on them passes on
 * a bridge that delivers nothing at all, which is precisely how this regression
 * stayed invisible. A page whose scripts never ran is left reporting
 * `BRIDGE-PROBE-WAITING`, and any other failure names the missing markers.
 *
 * The submit hook is asserted through `HTMLFormElement.prototype.submit`, which
 * every engine has, rather than `requestSubmit`, which an older engine may lack
 * -- a missing native method would otherwise read as a missing injection.
 *
 * Run alone this test creates the profile it needs, so a filtered dispatch
 * needs only `com.roombrowser.VaultBridgeDeliveryE2eTest` (A00WarmupTest
 * deletes its own profile and leaves no active one behind).
 */
@RunWith(AndroidJUnit4::class)
class VaultBridgeDeliveryE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer
    private val pageHits = AtomicInteger(0)

    @Before
    fun setUp() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                pageHits.incrementAndGet()
                return html(PROBE_PAGE)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun the_injected_scripts_reach_the_page_world() {
        assertTrue(
            "A profile must be active before the engine activity can be cold-started",
            ensureActiveProfile()
        )
        val url = server.url("/login").toString()
        var delivered = false
        for (attempt in 1..2) {
            launchEngineAt(url)
            delivered = hasText("BRIDGE-PROBE-DELIVERED", 30_000)
            if (delivered) break
            // The first engine activity after a profile switch SELF-RESTARTS
            // (bind fail -> kill + alarm); the second launch lands after it.
            device.waitForIdle(2_000)
        }
        if (!delivered) {
            snap("vault-bridge-delivery")
            fail(
                "The page-world scripts must reach the page in this edition: without " +
                    "them a submitted login form is never reported, so the app never " +
                    "offers to save the password, no wallet provider is installed and " +
                    "the device shim is absent -- all silently.\n${diagnostics()}"
            )
        }
    }

    // ---------- The probe page --------------------------------------------

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

    // ---------- UiAutomator helpers (the suite's proven patterns) --------

    private fun hasText(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs)

    /**
     * Cold-starts the engine activity at [url] through the same extra the app's
     * own launcher uses, so the page reaches the engine the way a real link
     * does (a fresh tab, no back history).
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
                .create(
                    // Unique: a leftover profile from an earlier run must not
                    // turn into a duplicate-name failure.
                    "E2E Bridge ${System.currentTimeMillis()}",
                    "👤",
                    0xFF7C4DFFL
                )
                .id.value
            graph.appState.setActiveProfile(id)
            true
        }
    }

    /** A real screenshot at the moment of failure; CI pulls /sdcard/e2e-shots. */
    private fun snap(name: String) {
        runCatching {
            device.executeShellCommand("mkdir -p /sdcard/e2e-shots")
            device.executeShellCommand("screencap -p /sdcard/e2e-shots/$name.png")
        }
    }

    /**
     * Built only when an assertion has already failed, and reporting both the
     * tokens on screen and whether the engine ever asked for the page: without
     * the hit count, a delivery failure and a page that never loaded look the
     * same from the device side.
     */
    private fun diagnostics(): String = try {
        val texts = runCatching {
            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(40)
        }.getOrDefault(emptyList())
        "VISIBLE TEXTS: $texts\npageHits=${pageHits.get()}\n" +
            "tokens: ${PROBE_TOKENS.joinToString(" ")}"
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }

    private companion object {
        /**
         * Reports its verdict as one of [PROBE_TOKENS] in a div the a11y tree
         * exposes as text. The markers are polled rather than read once because
         * the delivery point is the engine's, not the page's.
         */
        const val PROBE_PAGE = """
            <h1>ROOM-E2E-BRIDGE</h1>
            <form id="f" onsubmit="return false">
              <input type="text" name="username" id="u" autocomplete="username">
              <input type="password" name="password" id="p" autocomplete="current-password">
              <button type="submit">Sign in</button>
            </form>
            <div id="out">BRIDGE-PROBE-WAITING</div>
            <script>
              var out = document.getElementById('out');
              var MARKERS = [
                ['vault-installed', function () {
                  return window.__roomVaultInstalled === true;
                }],
                ['vault-fill-entry', function () {
                  return typeof window.__roomVaultFill === 'function';
                }],
                ['vault-submit-hook', function () {
                  var proto = window.HTMLFormElement && window.HTMLFormElement.prototype;
                  var fn = proto && proto.submit;
                  return typeof fn === 'function' && String(fn).indexOf('report') >= 0;
                }],
                ['vault-fetch-hook', function () {
                  return String(window.fetch).indexOf('maybeReportOnSubmit') >= 0;
                }],
                ['vault-xhr-hook', function () {
                  var proto = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
                  var fn = proto && proto.send;
                  return typeof fn === 'function' &&
                    String(fn).indexOf('maybeReportOnSubmit') >= 0;
                }],
                ['wallet-installed', function () {
                  return window.__roomWalletInstalled === true;
                }],
                ['wallet-provider', function () {
                  return !!window.ethereum && typeof window.ethereum.request === 'function';
                }]
              ];
              var deadline = Date.now() + 5000;
              function check() {
                var missing = [];
                for (var i = 0; i < MARKERS.length; i++) {
                  if (!MARKERS[i][1]()) missing.push(MARKERS[i][0]);
                }
                if (!missing.length) {
                  out.textContent = 'BRIDGE-PROBE-DELIVERED';
                } else if (Date.now() > deadline) {
                  out.textContent = 'BRIDGE-PROBE-MISSING:' + missing.join(',');
                } else {
                  setTimeout(check, 150);
                }
              }
              check();
            </script>
            """

        val PROBE_TOKENS = listOf(
            "BRIDGE-PROBE-WAITING",
            "BRIDGE-PROBE-DELIVERED",
            "BRIDGE-PROBE-MISSING:"
        )
    }
}
