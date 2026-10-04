package com.roombrowser

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import com.roombrowser.browser.wallet.DappDecision
import com.roombrowser.browser.wallet.DappOutcome
import com.roombrowser.browser.wallet.DappRequest
import com.roombrowser.browser.wallet.WalletLockState
import com.roombrowser.data.db.ProfileEntity
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.wallet.model.BalanceResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.domain.wallet.model.WalletException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * E2E regression for the integrated multi-chain wallet (Room v8 tables +
 * WalletRepository + WalletEngine + WalletBridge/RoomWalletScript + the
 * WalletActivity UI). One test, three legs, one fresh profile per leg:
 *
 *   Leg A (UI, no-credential device): the settings "Wallet" row opens
 *     WalletActivity on the onboarding (NO_WALLET); a repository-level
 *     wallet with the FIXED test vector then locks it — WalletActivity
 *     re-opens on its LOCKED pane, a failed "Unlock" stays graceful, wallet
 *     contents never render, no crash. DB ground truth (test-process
 *     AppGraph): the wallet row's mnemonic is CIPHERTEXT (decrypts under
 *     this profile's key to the exact phrase; no phrase word's bytes occur
 *     in the decoded payload) and derived accounts store NO key material.
 *
 *   Leg B (the dApp pipeline through the REAL bridge): a second profile is
 *     created through the REAL quick switcher (process-restart path); its
 *     wallet + EVM account are created at the REPOSITORY level with the
 *     fixed vector, and the ':browser' engine sees the rows via Room
 *     multi-instance invalidation. A MockWebServer page then drives
 *     window.ethereum (falling back to the raw RoomWallet wire protocol on
 *     WebView builds without document-start script support — the CI
 *     emulator's WebView 83 lacks DOCUMENT_START_SCRIPT, so the provider
 *     script is never injected there; the page-side
 *     window.__roomWalletResponse hook is the frozen bridge contract):
 *       1. eth_requestAccounts on host 127.0.0.1 -> Connect sheet appears
 *          over the page (BrowserScreen hosts it) -> Approve -> the page
 *          renders the account address.
 *       2. the SAME host again -> already permitted: the bridge answers
 *          SILENTLY — the address renders with NO sheet ever appearing.
 *       3. a DIFFERENT host (localhost: hostOf() strips ports, so the
 *          hostname is the only host distinction) -> sheet -> Reject ->
 *          the page renders EIP-1193 error 4001.
 *     DB ground truth: exactly the 127.0.0.1 permission row exists.
 *
 *   Leg C (device-independent, always runs): the test-process engine is
 *     bound to a third profile created directly in the DB. importWallet
 *     with the fixed phrase (golden index-0 address), unlock,
 *     addDerivedAccount (golden index-1), importAccount with the fixed
 *     private key (cross-validated address), nextDerivationIndex, both
 *     reveal round-trips, an instantly-refusing local RPC endpoint makes
 *     the offline contract deterministic (refreshBalances -> every entry
 *     a BalanceResult.Error, estimateSendFee -> null, sendNative -> a
 *     typed WalletException — never a hang), the engine-level dApp
 *     pipeline (submitDappRequest -> decideDappRequest approved -> the
 *     settle callback receives the address array; isDappPermitted flips),
 *     and cross-profile isolation across all three legs' profiles.
 *
 * FIXED VECTORS (public test vectors copied from the app's own test
 * corpus — android/app/src/test/.../WalletEngineTest.kt, which pins them
 * from android/core/wallet/src/test/.../ChainAdaptersCrossValidationTest.kt
 * where they are cross-validated against the official SDKs): the standard
 * "abandon … about" BIP39 phrase, its golden EVM index-0/1 addresses, and
 * the raw key "c5338c…". Never any real key material.
 */
@RunWith(AndroidJUnit4::class)
class WalletE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer

    /** Per-run marker suffix — unique names/URLs across runs. */
    private val tag = (System.currentTimeMillis() % 100000).toString()
    private val profileName1 = "E2EWa$tag"
    private val profileName2 = "E2EWb$tag"

    private lateinit var urlConnect: String
    private lateinit var urlSilent: String
    private lateinit var urlReject: String

    // -- fixed vectors (see class KDoc for provenance) --------------------
    private companion object {
        const val ABANDON =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        const val EVM0 = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
        const val EVM1 = "0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0"
        const val SOL0 = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
        const val SEED = "c5338cd251c22daa8c9c9cc94f498cc8a5c7e1d2e75287a5dda91096fe64efa5"
        const val EVM_IMPORT = "0x417AA4b5a8bf239d05C03C7C0C0231ECF7620c26"
        const val EVM_PATH0 = "m/44'/60'/0'/0/0"
        const val EVM_PATH1 = "m/44'/60'/0'/0/1"
        const val SOL_PATH0 = "m/44'/501'/0'/0'"
        const val BURN_ADDRESS = "0x000000000000000000000000000000000000dEaD"

        /** id of the custom EVM network whose RPC refuses instantly. */
        const val REFUSED_NETWORK_ID = "EVM:42141337"

        /**
         * The engine's bridge log tag, duplicated here rather than imported.
         *
         * `:engine` is an `implementation` dependency of `:app`, so its
         * `internal` constant is not on this module's compile classpath, and
         * androidTest could not read it even if it were public. A rename on
         * the engine side would therefore not break this file -- it would only
         * make `bridgeLogTail()` print nothing, and an empty bridge dump reads
         * as "the bridge never ran", which answers the question wrongly rather
         * than failing to answer it. `BridgeDiagnosticsTest` in `:app`'s unit
         * tests reads the constant out of the engine's source and fails in the
         * fast job when the two drift apart.
         */
        const val BRIDGE_LOG_TAG = "RoomBridge"
    }

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
                    path.startsWith("/connect-$tag") ->
                        dappPage("WC1-$tag", "connect-$tag", "RESULT:", 750)
                    path.startsWith("/silent-$tag") ->
                        dappPage("WS2-$tag", "silent-$tag", "SILENT:", 600)
                    path.startsWith("/reject-$tag") ->
                        dappPage("WR3-$tag", "reject-$tag", "NEVER:", 600)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val port = server.port
        // 127.0.0.1 and localhost are DIFFERENT hosts to the bridge
        // (UrlIntelligence.hostOf() strips ports) but the same loopback
        // interface — one server, two host identities.
        //
        // The host is spelled out, never taken from server.url(): that URL
        // carries the listening socket's canonicalHostName, which is the
        // machine's name for the wildcard address and not the literal this
        // test is about. Taking it from there made urlConnect and urlReject
        // the SAME host — so there was no "different host" to re-prompt for,
        // and the sheet (which reports the host the bridge derived) could
        // never match the literals asserted below and in the DB rows.
        urlConnect = "http://127.0.0.1:$port/connect-$tag"
        urlSilent = "http://127.0.0.1:$port/silent-$tag"
        urlReject = "http://localhost:$port/reject-$tag"
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /**
     * A page that connects through the REAL wallet bridge and renders the
     * outcome into the DOM. Prefers the injected EIP-1193 provider
     * (window.ethereum); falls back to the raw RoomWallet wire protocol
     * (the frozen bridge contract: window.RoomWallet.request in,
     * window.__roomWalletResponse out) when the provider script was not
     * injected — the CI emulator's WebView 83 has no document-start
     * script support, so the raw interface is what its pages see.
     */
    private fun dappPage(title: String, pageId: String, marker: String, delayMs: Int): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/html; charset=utf-8")
            .setBody(
                """
                <!DOCTYPE html><html><head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>$title</title>
                </head><body style="font-size:24px; margin:24px;">
                <h1>$title</h1>
                <div id="out">WAITING-$pageId</div>
                <script>
                  function roomConnect(id) {
                    if (window.ethereum && typeof window.ethereum.request === 'function') {
                      return window.ethereum.request({ method: 'eth_requestAccounts' });
                    }
                    return new Promise(function (resolve, reject) {
                      window.__roomWalletResponse = function (rid, resultJson, errorCode, errorMessage) {
                        if (errorCode === 0) {
                          var value = null;
                          try { value = JSON.parse(resultJson); } catch (e) { value = resultJson; }
                          resolve(value);
                        } else {
                          var error = new Error(errorMessage || 'bridge error');
                          error.code = errorCode;
                          reject(error);
                        }
                      };
                      window.RoomWallet.request(JSON.stringify(
                        { id: id, kind: 'request', chain: 'EVM', method: 'eth_requestAccounts', params: [] }
                      ));
                    });
                  }
                  setTimeout(function () {
                    // Real dApps retry transient provider failures; the
                    // wallet engine binds ~2.5s after the engine boots
                    // (deferred off the startup path), so a too-early call
                    // settles DISCONNECTED (4900) once. Retry those.
                    var attempt = 0;
                    function tryConnect() {
                      attempt++;
                      roomConnect('$pageId').then(
                        function (accounts) {
                          var text = (accounts && accounts.join) ? accounts.join(',') : String(accounts);
                          document.getElementById('out').innerText = '$marker' + text;
                        },
                        function (err) {
                          var code = (err && err.code) ? err.code : 0;
                          if (code === 4900 && attempt < 12) {
                            setTimeout(tryConnect, 1000);
                          } else {
                            document.getElementById('out').innerText = 'ERR:' + ((err && err.code) ? err.code : 'none');
                          }
                        }
                      );
                    }
                    tryConnect();
                  }, $delayMs);
                </script>
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

    private fun hasTextContains(part: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.textContains(part)), timeoutMs)

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

    private fun waitGone(text: String, timeoutMs: Long): Boolean =
        waitUntil(timeoutMs) { device.findObjects(By.text(text)).isEmpty() }

    /** True when [text] is ABSENT for the whole [windowMs] (no late appearance). */
    private fun staysAbsent(text: String, windowMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            if (device.findObjects(By.text(text)).isNotEmpty()) return false
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return device.findObjects(By.text(text)).isEmpty()
    }

    /** Polls the WebView's DOM-rendered result marker (page a11y text). */
    private fun pageResultText(prefix: String, timeoutMs: Long): String? {
        fun probe(): List<String> =
            device.findObjects(By.textContains(prefix)).mapNotNull { it.text }
        val deadline = System.currentTimeMillis() + timeoutMs
        var result = probe()
        while (result.isEmpty() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
            result = probe()
        }
        return result.firstOrNull()
    }

    private fun clickCenter(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        // SHELL TAP (input tap) — deterministic on the busy CI a11y pipeline.
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

    private fun dragUpQuarter() {
        // Half-screen drag (3/4 → 1/4): the CI emulator's default profile is
        // 320x640 mdpi — the wallet row sits ~3500px down the profile
        // settings screen there. Slow steps (no fling) keep it a controlled
        // scroll; the settle AFTER the drag lets residual momentum finish
        // before the caller reads node bounds (a tap on bounds captured
        // mid-fling hits the void — CI proven: the wallet row tap landed on
        // nothing and no activity started).
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 100
        )
        device.waitForIdle(800)
        try { Thread.sleep(300) } catch (_: InterruptedException) { }
    }

    /** Scroll-aware click (off-screen rows are not in the a11y tree). */
    private fun clickTextWithScroll(text: String, attempts: Int = 24): Boolean {
        for (i in 1..attempts) {
            if (clickText(text, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    /** Scroll-aware presence check, the read-only twin of [clickTextWithScroll]. */
    private fun hasTextWithScroll(text: String, attempts: Int = 12): Boolean {
        for (i in 1..attempts) {
            if (hasText(text, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    /**
     * Clicks the node showing [text] (scrolling to it when needed) and
     * VERIFIES the effect — a tap on bounds captured mid-fling or across a
     * layout shift lands on nothing (CI 227ebc3: the wallet row tap
     * "succeeded" yet WalletActivity never started). Every attempt re-resolves
     * the node FRESH; the loop keeps going until the effect shows or the
     * budget runs out.
     */
    private fun clickTextVerifiedScrollable(
        text: String,
        timeoutMs: Long,
        verify: () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (verify()) return true
            val node = device.wait(Until.findObject(By.text(text)), 1_500)
            if (node != null) {
                clickSmart(node)
                device.waitForIdle(1_000)
                if (verify()) return true
            }
            dragUpQuarter()
        }
        return verify()
    }

    private fun imeShown(): Boolean = try {
        device.executeShellCommand("dumpsys input_method | grep mInputShown")
            .contains("mInputShown=true")
    } catch (_: Exception) {
        false
    }

    /** Polls dumpsys until the IME is actually shown (focus really landed). */
    private fun waitImeShown(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (imeShown()) return true
            try { Thread.sleep(200) } catch (_: InterruptedException) { }
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
            "Clear site data",              // site controls (shields)
            "Toggle JavaScript for this site",
            "Page Actions",                 // page-actions sheet header
            "Switch Profile"                // profile switcher
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
            if (hasDesc("Address bar", 400)) return true
            if (hasText("Search or type URL", 400)) return true
            if (hasText("Privacy Dashboard", 400)) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return hasDesc("Address bar", 500)
    }

    /** The start page is up exactly when the address pill says so. */
    private fun homepageUp(timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc("Address bar: search or type URL")), timeoutMs) ||
            hasText("Privacy Dashboard", 2_000)

    /**
     * Probe dump for failure messages. Word-cell texts ("<n>. <word>" —
     * the reveal screen's recovery-phrase grid) are REDACTED: phrase
     * material never reaches a log or a failure message.
     */
    private fun uiTree(): String = try {
        val texts = runCatching {
            device.findObjects(By.textContains(""))
                .mapNotNull { it.text }
                .map { if (wordCell.matches(it)) "<phrase cell redacted>" else it }
                .distinct().take(60)
        }.getOrDefault(emptyList())
        val descs = runCatching {
            device.findObjects(By.descContains("")).mapNotNull { it.contentDescription }
                .distinct().take(30)
        }.getOrDefault(emptyList())
        "TEXTS: $texts\nDESCS: $descs"
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }

    /**
     * Failure-path diagnostic sink. The per-test logcat is uploaded inside
     * the `e2e-reports` artifact, so a probe written here survives even when
     * a test-report renderer truncates or drops a multi-line assertion
     * message. Chunked to stay well under logcat's per-message limit.
     */
    private fun logProbe(tag: String, message: String) {
        message.chunked(1_000).forEach {
            android.util.Log.w("WalletE2eTest", "RB-PROBE $tag: $it")
        }
    }

    /**
     * How long a no-op takes to run on the app's MAIN looper, plus the main
     * thread's stack when it did not run at all.
     *
     * WHY THIS MEASURES WHAT IT MEASURES. `onClick = { revealed = !revealed }`
     * is a Compose state write and nothing else -- no engine call, no I/O, no
     * crypto -- so the label it renders is repainted on the next frame the
     * main thread produces. A run where the label stayed "Reveal" for twenty
     * seconds while 72 fresh accessibility queries were answered in the
     * meantime was therefore not a slow reveal and not a slow query: it was a
     * main thread that was answering messages and not drawing frames.
     *
     * This is the instrument that tells those apart, from inside the process
     * and without root. A posted no-op is the cheapest possible message, so
     * its latency is a floor on how long any pending work -- a Choreographer
     * frame callback, or the tap itself -- would have waited behind the same
     * queue. And because the instrumentation runs in the app's OWN process,
     * `Looper.getMainLooper().thread.stackTrace` can be read while the main
     * thread is still stuck, which names the culprit instead of inferring it.
     *
     * Returns `-1` and that stack when the no-op has not run within
     * [budgetMs]; otherwise the latency in milliseconds and an empty stack.
     */
    @Suppress("DEPRECATION")
    private fun mainThreadPing(budgetMs: Long): Pair<Long, String> {
        val ran = CountDownLatch(1)
        val start = System.currentTimeMillis()
        Handler(Looper.getMainLooper()).post { ran.countDown() }
        if (ran.await(budgetMs, TimeUnit.MILLISECONDS)) {
            return (System.currentTimeMillis() - start) to ""
        }
        val stack = Looper.getMainLooper().thread.stackTrace
            .take(30)
            .joinToString("\n") { "    at $it" }
        return -1L to stack
    }

    /**
     * The tail of the engine's bridge log, for a failure path only.
     *
     * `executeShellCommand` starts no shell, so the `-s` here is an argument
     * to logcat rather than a pipe -- which is the point: the filter is the
     * engine's own tag, so the dump is only ever the bridge's own lines and
     * not a slice of GeckoView's chatter.
     */
    private fun bridgeLogTail(): String = runCatching {
        device.executeShellCommand("logcat -d -s $BRIDGE_LOG_TAG -t 300")
    }.getOrDefault("<logcat unavailable>")

    // ---------- Bootstrap: fresh per-run profiles -------------------------

    /** TabsE2eTest bootstrap: one fresh profile, engine up on it. */
    private fun bootstrapFreshEngine(): Boolean {
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)
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
        device.executeShellCommand("input text $profileName1")
        device.waitForIdle(1_000)
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
        // STABILITY-gated engine wait: the ':browser' process may still be
        // bound to the PREVIOUS suite's profile — the first engine activity
        // then self-restarts (bind fail → kill + alarm; the CI emulator
        // deferred the restart alarm ~5 s). Typing/clicking on the doomed
        // surface races that restart; 8 s of CONTINUOUS surface rides it out.
        if (!engineUiStable(120_000)) return false
        device.waitForIdle(2_000)
        return true
    }

    /**
     * The engine surface must be up CONTINUOUSLY for [stableMs] before the
     * bootstrap returns — a surface that dies (process self-restart) resets
     * the window (same CI lesson as TabsE2eTest, run 227ebc3).
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
            try { Thread.sleep(150) } catch (_: InterruptedException) { }
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

    /**
     * Waits for proof the page loaded: the content marker renders, or an
     * [acceptInstead] surface that ONLY a running page can raise is up.
     *
     * The second half is not a shortcut — it is the only signal that exists
     * once a wallet sheet is up. A Compose ModalBottomSheet is its own
     * window, and while it stands the WebView stops serving its
     * accessibility subtree; CI 36893513963 shows the page finishing
     * (`RoomNav onPageFinished title=WC1-56494`) while every `By.text` probe
     * over the page — and even the all-windows sweep for the omnibox —
     * returned nothing for the remaining 33 s of the test. Polling page text
     * there is not just useless, it is what burns the rounds.
     */
    private fun waitLoaded(
        marker: String,
        acceptInstead: List<String>,
        timeoutMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasText(marker, 400)) return true
            if (acceptInstead.any { hasText(it, 400) }) return true
        }
        return hasText(marker, 500) || acceptInstead.any { hasText(it, 500) }
    }

    /**
     * True when some node's text is EXACTLY [url].
     *
     * Deliberately a global text search rather than the node carrying the
     * "omni_field" description: that one is the container Box, and its text
     * is NOT the field's content (empty on the homepage it reads the
     * "Search or type URL" placeholder), so it cannot distinguish a full
     * field from an empty one. A "contains" probe on the field text cannot
     * either — that is how the browser came to be sent a URL nobody typed
     * (CI 2c9f63d), since the concatenation holds the new URL as a suffix.
     */
    private fun omniboxHoldsExactly(url: String): Boolean =
        waitUntil(3_000) {
            device.findObjects(By.textContains(url)).any { it.text?.trim() == url }
        }

    /**
     * Loads [url] in the CURRENT tab through the real omnibox and waits for
     * [contentMarker] in the page. Hardened like TabsE2eTest.loadInOmnibox
     * (CI run 9399640: key events dropped while the engine's first frames
     * were still composing, so typing waits for the IME to be shown). The
     * accessibility ACTION_SET_TEXT this once fell back to is gone: 033cb23
     * showed it returns false silently on this field.
     *
     * [acceptInstead] names surfaces that count as "loaded" without the page
     * text being readable — see [waitLoaded].
     */
    private fun loadInOmnibox(
        url: String,
        contentMarker: String,
        acceptInstead: List<String> = emptyList()
    ): Boolean {
        for (round in 1..4) {
            if (hasText(contentMarker, 500)) return true
            // Settle: the first seconds after engine boot churn the tree.
            device.waitForIdle(1_500)
            hideImeIfNeeded()
            dismissSheetIfAny()
            // CI 75822ed: findObject (active window) can go blind while the
            // IME holds a11y focus — fall through to the all-windows sweep
            // (findObjects), which still sees the omnibox.
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

            // The shell key-event path, IME-gated. (CI 033cb23 DISPROVED the
            // a11y ACTION_SET_TEXT on this field — same structure as the
            // Tabs omnibox: the contentDescription modifier's OUTER
            // semantics node does not support the action, performAction
            // returns false SILENTLY and the URL never lands. There is no
            // fallback to it because there is nothing it could do.)
            //
            // Clearing is UNCONDITIONAL and generous. It cannot be gated on
            // reading the field back: the node carrying the "omni_field"
            // description is the container Box and its text is not the
            // field's, so on the homepage — where the omnibox is empty — a
            // read-back is non-empty (the placeholder) and a
            // "clear, verify, retry" loop never terminates (CI 36932653641,
            // 15s a round, then "The connect page must load"). Backspaces on
            // an already-empty field are inert, so the only cost of not
            // asking is a few keys. 80 > the longest URL this suite types.
            device.executeShellCommand("input keyevent KEYCODE_MOVE_END")
            device.clearFocusedField(80)
            device.waitForIdle(400)
            device.executeShellCommand("input text $url")
            device.waitForIdle(800)
            // Best effort, and deliberately NOT a gate: a URL left over from
            // the previous navigation turns `input text` into an append
            // (CI 2c9f63d sent the browser "…/connect-45508http://…/silent-45508"),
            // and a `contains` probe cannot see that — the concatenation
            // holds the new URL as a suffix — so this asks for EXACT.
            val typed = omniboxHoldsExactly(url)
            if (!typed) {
                device.executeShellCommand("input keyevent KEYCODE_MOVE_END")
                device.clearFocusedField(80)
                device.waitForIdle(400)
                device.executeShellCommand("input text $url")
                device.waitForIdle(800)
            }
            // Enter + the page marker below is the real check, so an
            // unconfirmed field is retyped once and then tried anyway:
            // aborting the round here is what turned a read-back this node
            // cannot answer into a failed navigation.
            device.executeShellCommand("input keyevent 66")
            if (waitLoaded(contentMarker, acceptInstead, 15_000)) return true
            // A bare Enter with no IME went to the APP and could background
            // the engine on the homepage — only retry while the IME is up.
            if (imeShown()) {
                device.pressEnter()
                if (waitLoaded(contentMarker, acceptInstead, 15_000)) return true
            }
        }
        return false
    }

    /** Backs out of WalletActivity and the settings routes to the surface. */
    private fun backToBrowsingSurface(): Boolean {
        for (i in 1..6) {
            if (homepageUp(1_500)) return true
            device.pressBack()
            device.waitForIdle(1_000)
        }
        return homepageUp(3_000)
    }

    /**
     * Creates a SECOND profile through the REAL quick switcher: page menu ->
     * "Switch profile" -> "Create New Profile" -> the dialog -> the switch
     * runs the process-restart protocol and the engine relaunches bound to
     * the new profile (TabsE2eTest's "later profile" path).
     */
    private fun createSecondProfileThroughSwitcher(): Boolean {
        if (!openSheetEntry("Switch profile") { hasText("Switch Profile", 6_000) }) return false
        if (!clickTextWithScroll("Create New Profile")) return false
        assertTrue("Create-profile dialog should open", hasText("New Profile", 8_000))
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8_000)
            ?: return false
        clickCenter(field)
        device.executeShellCommand("input keyevent KEYCODE_MOVE_END")
        device.clearFocusedField()
        device.waitForIdle(300)
        device.executeShellCommand("input text $profileName2")
        device.waitForIdle(1_000)
        if (!clickText("Create", 5_000)) return false
        // The switch kills the ':browser' process and restarts it bound to
        // the new profile — the same engine-up the bootstrap waits for, and
        // the same restart race: STABILITY-gated (8 s continuous surface)
        // so the caller never types on a doomed engine.
        if (!engineUiStable(120_000)) return false
        device.waitForIdle(2_000)
        return true
    }

    // ---------- Onboarding helpers (create-flow spec test) ----------------

    /** Shape of a reveal-screen word cell: "<1-based index>. <word>". */
    private val wordCell = Regex("^\\d{1,2}\\. .+$")

    /**
     * Reads the revealed word cells (index -> word). The words stay in
     * memory ONLY — they are never logged, asserted into a message or
     * written anywhere.
     *
     * CI 88ec8fe: the 24-cell grid does NOT fit the 320x640 runner screen
     * and off-screen nodes are not exposed to the a11y tree — a single
     * viewport read sees only the last ~6 cells (the viewport rests at the
     * Reveal button, below the grid). The reader therefore COLLECTS while
     * scrolling: read the visible band, drag toward the TOP of the column
     * (finger 1/4 → 3/4), read the next band, merge — until all 24 indices
     * are collected or the attempt budget runs out.
     */
    private fun readWordCells(expected: Int = 24): Map<Int, String> {
        val collected = HashMap<Int, String>()
        fun collectVisible() {
            device.findObjects(By.textContains(""))
                .mapNotNull { it.text }
                .mapNotNull { text ->
                    val dot = text.indexOf(". ")
                    val index = text.substringBefore(".").toIntOrNull()
                    if (dot > 0 && index != null && text.length > dot + 2) {
                        index to text.substring(dot + 2)
                    } else {
                        null
                    }
                }
                .filter { it.first in 1..expected && it.second.none { c -> c == '•' } }
                .forEach { collected.putIfAbsent(it.first, it.second) }
        }
        collectVisible()
        for (attempt in 1..16) {
            if (collected.size >= expected) break
            // Drag DOWN (finger 1/4 → 3/4) = walk the viewport toward the
            // TOP of the column: the grid sits ABOVE the Reveal button the
            // test just tapped. Slow steps, no fling — same discipline as
            // dragUpQuarter.
            device.swipe(
                device.displayWidth / 2, device.displayHeight / 4,
                device.displayWidth / 2, device.displayHeight * 3 / 4, 100
            )
            device.waitForIdle(800)
            try { Thread.sleep(300) } catch (_: InterruptedException) { }
            collectVisible()
        }
        return collected
    }

    /**
     * Finds the current "Tap word #N" quiz prompt wherever the column was
     * left parked: fast path with no drag (a fresh quiz screen starts at
     * the top), then walks the viewport UP (finger 1/4 -> 3/4 — the
     * readWordCells direction, for a bottom-parked offset), then DOWN.
     */
    private fun findPromptWithScroll(): UiObject2? {
        // Fast path, no drag: a fresh quiz screen starts at the top.
        val initial = device.wait(Until.findObject(By.textStartsWith("Tap word #")), 1_500)
        if (initial != null) return initial
        // Walk the viewport UP (finger 1/4 -> 3/4 — the readWordCells
        // direction) in case the reveal screen left the column parked LOW.
        for (i in 1..8) {
            device.swipe(
                device.displayWidth / 2, device.displayHeight / 4,
                device.displayWidth / 2, device.displayHeight * 3 / 4, 100
            )
            device.waitForIdle(600)
            try { Thread.sleep(200) } catch (_: InterruptedException) { }
            val up = device.wait(Until.findObject(By.textStartsWith("Tap word #")), 800)
            if (up != null) return up
        }
        // Then DOWN, in case the prompt sits below the fold.
        for (i in 1..8) {
            dragUpQuarter()
            val down = device.wait(Until.findObject(By.textStartsWith("Tap word #")), 800)
            if (down != null) return down
        }
        return null
    }

    /**
     * Answers the 3-word confirmation quiz by READING each "Tap word #N"
     * prompt and tapping the remembered word for that index. Each round is
     * verification-driven: the answered prompt must disappear before the
     * next one is read. Word material never reaches a message.
     */
    private fun answerQuiz(words: Map<Int, String>): Boolean {
        for (attempt in 1..12) {
            if (hasTextContains("All three words correct.", 1_000)) return true
            // Scroll-AWARE: CI 033cb23 — readWordCells parks the viewport at
            // the TOP of the reveal column, "I wrote it down" lives at the
            // BOTTOM, and the quiz screen inherits that scroll offset. The
            // prompt (and its word chips) can sit ABOVE the fold on the
            // 320x640 CI screen; drag toward the TOP until it exposes.
            val prompt = findPromptWithScroll() ?: continue
            val promptText = prompt.text ?: continue
            val number = promptText.removePrefix("Tap word #").trim().toIntOrNull() ?: continue
            val wanted = words[number] ?: return false
            if (!clickTextWithScroll(wanted, attempts = 8)) continue
            // Verification-driven: the answered prompt disappears when the
            // tap registers (the next prompt asks a different index).
            waitGone(promptText, 6_000)
        }
        return hasTextContains("All three words correct.", 2_000)
    }

    // ---------- DB ground-truth helpers (test-process AppGraph) -----------

    /** Returns the index of [needle] in [haystack], or -1. */
    private fun bytesIndexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /**
     * The stored mnemonic is ciphertext: the blob decrypts under the
     * profile's own wallet key to [expected], and no dictionary word of the
     * phrase occurs in the DECODED payload (the actual ciphertext; the
     * base64 TEXT could coincidentally contain a short word as a
     * substring — the decoded bytes are what is stored).
     */
    private fun assertMnemonicCiphertext(profileId: ProfileId, expected: String) {
        val row = runBlocking {
            (targetContext.applicationContext as com.roombrowser.RoomBrowserApp).graph
                .database.walletDao().byProfile(profileId.value)
        } ?: error("profile must have a wallet row")
        val enc = row.mnemonicEnc ?: error("the wallet row must store a mnemonic blob")
        assertThat(enc).isNotEmpty()
        assertThat(enc).doesNotContain(expected)
        val payload = Base64.getDecoder().decode(enc)
        expected.split(' ').distinct().forEach { word ->
            assertThat(bytesIndexOf(payload, word.toByteArray(Charsets.US_ASCII)))
                .isEqualTo(-1)
        }
        assertThat(com.roombrowser.security.WalletKeyCrypto.decrypt(profileId, enc))
            .isEqualTo(expected)
    }

    // ---------- The contract ------------------------------------------------

    @Test
    fun wallet_row_locked_pane_dapp_bridge_pipeline_and_repository_flow() {
        assertTrue("Engine must come up on a fresh profile", bootstrapFreshEngine())
        val appGraph = (targetContext.applicationContext as com.roombrowser.RoomBrowserApp).graph

        // ================= Leg A: settings row + onboarding + locked pane ==
        val profileId1 = ProfileId(
            runBlocking {
                appGraph.appState.activeProfileIdSnapshot()
                    ?: error("engine must have persisted the active profile id")
            }
        )

        assertTrue(
            "Profile settings must open",
            openSheetEntry("Profile settings") {
                device.wait(Until.hasObject(By.textContains("Profile Settings —")), 8_000)
            }
        )
        // The row is targeted by its SUBTITLE: the section header and the
        // row title are both the bare text "Wallet" on this screen. The
        // click is VERIFIED on WalletActivity actually opening — the row sits
        // ~3500px down a scrollable list and unverified taps have landed on
        // nothing (CI 227ebc3).
        assertTrue(
            "The Wallet settings row must be tappable (verified: activity opens)\n${uiTree()}",
            clickTextVerifiedScrollable("Multi-chain wallet, accounts and dApp connections", 60_000) {
                device.findObjects(By.text("Set up your wallet")).isNotEmpty() ||
                    device.findObjects(By.textContains("Vault locked")).isNotEmpty() ||
                    device.findObjects(By.textContains("Total balance")).isNotEmpty()
            }
        )
        assertTrue(
            "WalletActivity must open on the onboarding choice screen\n${uiTree()}",
            hasText("Set up your wallet", 15_000)
        )
        assertTrue("The create option must be offered", hasText("Create a new wallet", 3_000))
        assertTrue("The import option must be offered", hasText("Import with recovery phrase", 3_000))

        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuthenticate =
            BiometricManager.from(targetContext).canAuthenticate(authenticators) ==
                BiometricManager.BIOMETRIC_SUCCESS

        // Leave the wallet surface, then give the profile a wallet at the
        // repository level with the FIXED vector — exactly the state the
        // create flow would produce for the default chains (EVM + Solana).
        device.pressBack()
        device.waitForIdle(1_000)
        runBlocking {
            val repo = appGraph.walletRepo
            repo.createWallet(profileId1, "Wallet", ABANDON)
            repo.addDerivedAccount(profileId1, ChainType.EVM, EVM0, EVM_PATH0, "EVM 1")
            repo.addDerivedAccount(profileId1, ChainType.SOLANA, SOL0, SOL_PATH0, "Solana 1")
        }

        // Reopen: the wallet exists -> the LOCKED pane (WalletActivity's
        // entry gate fails with no device credentials — the CI state).
        assertTrue(
            "The Wallet settings row must re-open the wallet surface (verified)\n${uiTree()}",
            clickTextVerifiedScrollable("Multi-chain wallet, accounts and dApp connections", 60_000) {
                device.findObjects(By.textContains("Wallet locked")).isNotEmpty() ||
                    device.findObjects(By.text("Set up your wallet")).isNotEmpty()
            }
        )
        if (!canAuthenticate) {
            assertTrue(
                "The LOCKED pane must render — no crash\n${uiTree()}",
                hasText("Wallet locked", 15_000)
            )
            assertTrue(
                "The no-credential copy must show",
                // contains, not equals: the pane renders the FULL two-sentence
                // body ("This device has no screen lock. Set a PIN, pattern or
                // password in system settings to use the wallet.").
                hasTextContains("This device has no screen lock", 3_000)
            )
            assertTrue("The retry button must be present", hasText("Unlock", 5_000))
            // Wallet contents are NEVER composed while locked.
            assertTrue(
                "Wallet contents must never render while locked",
                device.findObjects(By.text("Networks")).isEmpty() &&
                    device.findObjects(By.text("Accounts")).isEmpty()
            )
            assertTrue(
                "A failed Unlock must keep the pane (graceful)\n${uiTree()}",
                clickText("Unlock", 5_000) && hasText("Wallet locked", 5_000)
            )
            assertTrue("The activity must still be alive (no crash)", hasDesc("Close wallet", 3_000))
        }
        // (A device WITH credentials would raise the system prompt on entry;
        //  the locked pane below it is the same pane — the repository-level
        //  ground truth and Legs B/C are device-independent.)

        // DB ground truth: the mnemonic is ciphertext, derived accounts
        // carry no key material.
        assertMnemonicCiphertext(profileId1, ABANDON)
        val legAAccounts = runBlocking {
            appGraph.database.walletAccountDao().forProfile(profileId1.value)
        }
        assertThat(legAAccounts.map { it.address }).containsExactly(EVM0, SOL0)
        assertThat(legAAccounts.map { it.chainType }).containsExactly("EVM", "SOLANA")
        legAAccounts.forEach { assertThat(it.privateKeyEnc).isNull() }

        // ================= Leg B: dApp connect through the REAL bridge ======
        assertTrue("Settings must be left for the browsing surface", backToBrowsingSurface())
        assertTrue(
            "The second profile must come up through the quick switcher",
            createSecondProfileThroughSwitcher()
        )
        val profileId2 = ProfileId(
            runBlocking {
                appGraph.appState.activeProfileIdSnapshot()
                    ?: error("the switched engine must persist the new active profile id")
            }
        )
        assertThat(profileId2.value).isNotEqualTo(profileId1.value)

        // Repository-level wallet + ONE EVM account for the fixed vector;
        // the ':browser' engine (bound since its restart) observes the rows
        // through Room multi-instance invalidation.
        runBlocking {
            val repo = appGraph.walletRepo
            repo.createWallet(profileId2, "E2E Bridge Wallet", ABANDON)
            repo.addDerivedAccount(profileId2, ChainType.EVM, EVM0, EVM_PATH0, "EVM 1")
        }
        assertThat(
            runBlocking { appGraph.database.walletDao().byProfile(profileId2.value) }
        ).isNotNull()

        // (1) First connect: the sheet MUST appear and Approve hands the
        //     page the account address (checksummed or not — dApps may get
        //     either form, so the compare is case-insensitive).
        assertTrue(
            "The connect page must load",
            loadInOmnibox(urlConnect, "WC1-$tag", acceptInstead = listOf("Connect site"))
        )
        // The bridge lines this test is about are written at page load, and
        // logcat is a ring that GeckoView fills fast: by the time the
        // assertion below has waited out its twenty seconds, the lines that
        // explain the wait may already have been evicted. So the tail is read
        // HERE, held in memory, and printed only if something later fails --
        // a capture cannot be evicted, and it costs one shell command on a
        // test that already spends most of a minute in an emulator.
        val bridgeAtLoad = bridgeLogTail()
        val connectSheet = hasText("Connect site", 20_000)
        assertTrue(
            buildString {
                append("The Connect sheet must appear over the page")
                if (!connectSheet) {
                    // The engine's three lifecycle lines are the whole answer
                    // here, and they only answer it IN ORDER: "extension
                    // installed" with no "port connected" is an injection that
                    // never reached a port; "port connected" with no "port
                    // message" is a page that never called; no lines at all is
                    // an extension that never installed. Taken in full rather
                    // than grepped for the line whose absence is the question,
                    // because silence at one boundary is not evidence about
                    // the next one.
                    val tail = bridgeLogTail()
                    logProbe("bridge-logcat-at-load", bridgeAtLoad)
                    logProbe("bridge-logcat-now", tail)
                    append("\nprobe: bridge logcat at page load, engine tag ")
                    append(BRIDGE_LOG_TAG).append(":\n").append(bridgeAtLoad.take(3_000))
                    append("\nprobe: bridge logcat now:\n").append(tail.take(2_000))
                }
            },
            connectSheet
        )
        // CONTAINS, not equals: HostBadge renders a "Connected site" label and
        // the host as two Texts in one Column, and Compose exposes that pair
        // as a single merged accessibility node, so the node's text is the
        // two joined — By.text (exact) never matches the host alone. The
        // assertion's point survives: the string it looks for comes from
        // request.host, which the bridge derives from the WebView's own URL,
        // never from the page's claimed origin.
        assertTrue("The sheet must name the WebView-verified host", hasTextContains("127.0.0.1", 10_000))
        // Scroll-aware: on the 320x640 CI emulator the sheet's answer pair can
        // sit below the fold, and an off-screen node is not in the a11y tree.
        // Every other sheet button in this suite is clicked this way.
        assertTrue("Approve must be clickable", clickTextWithScroll("Approve", attempts = 6))
        assertTrue("The sheet must leave after Approve", waitGone("Connect site", 8_000))
        val connectResult = pageResultText("RESULT:", 15_000)
        assertTrue(
            "The page must render the connect result (found ${connectResult ?: "nothing"})\n${uiTree()}",
            connectResult != null && connectResult.substringAfter("RESULT:")
                .equals(EVM0, ignoreCase = true)
        )

        // (2) The SAME host again: with the permission granted the bridge
        //     answers SILENTLY — the address renders with NO sheet.
        assertTrue(
            "The silent re-connect page must load\n${uiTree()}",
            loadInOmnibox(urlSilent, "WS2-$tag")
        )
        val silentResult = pageResultText("SILENT:", 15_000)
        assertTrue(
            "The permitted re-connect must resolve with the address (found ${silentResult ?: "nothing"})\n${uiTree()}",
            silentResult != null && silentResult.substringAfter("SILENT:")
                .equals(EVM0, ignoreCase = true)
        )
        assertTrue(
            "No Connect sheet may appear for an already-permitted host",
            staysAbsent("Connect site", 2_000)
        )

        // (3) A DIFFERENT host: the sheet appears again and Reject settles
        //     EIP-1193 4001 (the JS rejects with the bridge's error code).
        assertTrue(
            "The reject page must load",
            loadInOmnibox(urlReject, "WR3-$tag", acceptInstead = listOf("Connect site"))
        )
        assertTrue("The Connect sheet must appear for the new host", hasText("Connect site", 20_000))
        assertTrue("The sheet must name the localhost host", hasTextContains("localhost", 10_000))
        assertTrue("Reject must be clickable", clickTextWithScroll("Reject", attempts = 6))
        val rejectResult = pageResultText("ERR:", 15_000)
        assertTrue(
            "The rejected connect must surface error 4001 (found ${rejectResult ?: "nothing"})\n${uiTree()}",
            rejectResult != null && rejectResult.substringAfter("ERR:") == "4001"
        )

        // DB ground truth: exactly the 127.0.0.1 permission exists; the
        // rejected host was never granted.
        val legBPerms = runBlocking {
            appGraph.database.dappPermissionDao().allForProfile(profileId2.value)
        }
        assertThat(legBPerms.map { it.host }).containsExactly("127.0.0.1")
        assertThat(legBPerms.single().chainType).isEqualTo("EVM")
        assertThat(legBPerms.single().accountAddress).isEqualTo(EVM0)
        assertThat(legBPerms.single().methodsJson).contains("eth_requestAccounts")

        // ================= Leg C: repository/engine full flow ===============
        // A third profile keeps this leg isolated from the UI legs.
        val profileId3 = ProfileId(UUID.randomUUID().toString())
        runBlocking {
            appGraph.database.profileDao().upsert(
                ProfileEntity(
                    id = profileId3.value,
                    name = "E2E Wallet C $tag",
                    icon = "x",
                    colorArgb = 0,
                    isLocked = false,
                    isDefault = false,
                    createdAt = System.currentTimeMillis(),
                    lastActiveAt = System.currentTimeMillis(),
                    settingsJson = "{}"
                )
            )
        }
        val engine = appGraph.walletEngine
        engine.bind(profileId3)
        assertThat(engine.lockState.value).isEqualTo(WalletLockState.NO_WALLET)

        // import the fixed phrase -> the golden index-0 EVM account.
        runBlocking { engine.importWallet(ABANDON, "Leg C Wallet", listOf(ChainType.EVM)) }
        assertTrue(
            "The engine must observe the wallet (NO_WALLET -> LOCKED)",
            waitUntil(10_000) { engine.lockState.value == WalletLockState.LOCKED }
        )
        assertTrue(
            "The index-0 account must be observed",
            waitUntil(10_000) { engine.accounts.value.size == 1 }
        )
        assertThat(engine.accounts.value.single().address).isEqualTo(EVM0)
        assertThat(engine.accounts.value.single().path).isEqualTo(EVM_PATH0)

        engine.unlock()
        assertThat(engine.lockState.value).isEqualTo(WalletLockState.UNLOCKED)

        // addDerivedAccount walks to the next BIP44 index (golden index-1).
        val derived = runBlocking { engine.addDerivedAccount(ChainType.EVM) }
            ?: error("addDerivedAccount must return the new account")
        assertThat(derived.address).isEqualTo(EVM1)
        assertThat(derived.path).isEqualTo(EVM_PATH1)

        // importAccount with the fixed raw key -> the cross-validated address.
        val imported = runBlocking { engine.importAccount(ChainType.EVM, SEED, "Imported key") }
            ?: error("importAccount must return the new account")
        assertThat(imported.address).isEqualTo(EVM_IMPORT)
        assertTrue(
            "All three accounts must be observed",
            waitUntil(10_000) { engine.accounts.value.size == 3 }
        )
        assertThat(engine.accounts.value.map { it.address })
            .containsExactly(EVM0, EVM1, EVM_IMPORT)

        // The next free index is 2 (imports never count toward it).
        assertThat(
            runBlocking { appGraph.walletRepo.nextDerivationIndex(profileId3, ChainType.EVM) }
        ).isEqualTo(2)

        // Both reveal round-trips (unlocked session).
        assertThat(runBlocking { engine.revealMnemonic() }).isEqualTo(ABANDON)
        assertThat(runBlocking { appGraph.walletRepo.revealPrivateKey(imported.id) })
            .isEqualTo(SEED)

        // An instantly-refusing local RPC endpoint (nothing listens on the
        // discard port) makes the offline contract deterministic: no hang,
        // no external dependency, on ANY runner network.
        val refusedNetwork = NetworkConfig.evm(
            chainId = 42141337,
            name = "E2E Refused RPC",
            rpcUrls = listOf("http://127.0.0.1:9/"),
            symbol = "TST",
            explorer = null
        )
        assertThat(runBlocking { engine.addCustomNetwork(refusedNetwork) }).isTrue()
        runBlocking { engine.setActiveNetwork(ChainType.EVM, REFUSED_NETWORK_ID) }
        assertThat(engine.activeNetworks.value[ChainType.EVM]?.id).isEqualTo(REFUSED_NETWORK_ID)

        // refreshBalances offline: every account settles as an Error entry —
        // the withTimeout wrapper is the "does not hang" assertion itself.
        runBlocking {
            withTimeout(20_000) { engine.refreshBalances() }
        }
        val balances = engine.balances.value
        assertThat(balances.keys).containsExactlyElementsIn(
            engine.accounts.value.map { it.id }
        )
        balances.values.forEach { balance ->
            assertThat(balance).isInstanceOf(BalanceResult.Error::class.java)
        }

        // estimateSendFee offline: null, never a crash and never a hang
        // (withTimeout = the completion assertion).
        val evm0 = engine.accounts.value.first { it.address == EVM0 }
        assertThat(
            runBlocking {
                withTimeout(20_000) {
                    engine.estimateSendFee(ChainType.EVM, REFUSED_NETWORK_ID, evm0.id, BURN_ADDRESS, "0.001")
                }
            }
        ).isNull()

        // sendNative to a garbage-but-valid-format address: the engine
        // surfaces a typed WalletException immediately (adapter failures
        // map to a loud error — never a hang, never a silent success).
        val sendFailure = runBlocking {
            withTimeout(20_000) {
                try {
                    engine.sendNative(evm0.id, REFUSED_NETWORK_ID, BURN_ADDRESS, "0.001")
                    null
                } catch (e: WalletException) {
                    e
                }
            }
        }
        assertThat(sendFailure).isNotNull()

        // The engine-level dApp pipeline: submit a Connect, approve it, the
        // settle callback receives the EVM address array and isDappPermitted
        // flips false -> true (the permission the bridge auto-checks).
        val connectId = "e2e-leg-c-$tag"
        val legCHost = "dapp.example.com"
        assertThat(engine.isDappPermitted(legCHost, ChainType.EVM, EVM0, "eth_requestAccounts"))
            .isFalse()
        val settled = CountDownLatch(1)
        val outcomeRef = AtomicReference<DappOutcome?>()
        engine.submitDappRequest(
            DappRequest.Connect(connectId, legCHost, ChainType.EVM, "https://$legCHost/")
        ) { outcome ->
            outcomeRef.set(outcome)
            settled.countDown()
        }
        assertThat(engine.pendingRequests.value.map { it.id }).containsExactly(connectId)
        engine.decideDappRequest(DappDecision(requestId = connectId, approved = true))
        assertTrue("The approved Connect must settle", settled.await(15, TimeUnit.SECONDS))
        val outcome = outcomeRef.get() ?: error("the settle callback must deliver an outcome")
        assertThat(outcome.error).isNull()
        assertThat(outcome.resultJson).isEqualTo("""["$EVM0"]""")
        assertThat(engine.isDappPermitted(legCHost, ChainType.EVM, EVM0, "eth_requestAccounts"))
            .isTrue()
        assertThat(engine.pendingRequests.value).isEmpty()

        // Cross-profile isolation: each leg's profile sees ONLY its own
        // wallet rows, accounts and permissions.
        val wallet1 = runBlocking { appGraph.walletRepo.wallet(profileId1) }
        val wallet2 = runBlocking { appGraph.walletRepo.wallet(profileId2) }
        val wallet3 = runBlocking { appGraph.walletRepo.wallet(profileId3) }
        assertThat(wallet1).isNotNull()
        assertThat(wallet2).isNotNull()
        assertThat(wallet3).isNotNull()
        assertThat(wallet1?.id).isNotEqualTo(wallet3?.id)
        assertThat(wallet2?.id).isNotEqualTo(wallet3?.id)
        assertThat(runBlocking { appGraph.walletRepo.accounts(profileId3) }.map { it.address })
            .containsExactly(EVM0, EVM1, EVM_IMPORT)
        assertThat(
            runBlocking { appGraph.walletRepo.allDappPermissions(profileId3) }.map { it.host }
        ).containsExactly(legCHost)
        assertThat(
            runBlocking { appGraph.walletRepo.allDappPermissions(profileId2) }.map { it.host }
        ).containsExactly("127.0.0.1")
        assertMnemonicCiphertext(profileId3, ABANDON)
    }

    /**
     * The create-wallet onboarding contract: the reveal screen and the
     * confirmation quiz MUST complete before the lockState flip can take
     * the screen. The original bug (onboarding composed only while
     * lockState == NO_WALLET, so engine.createWallet's immediate row
     * persist swapped it out mid-flow) was fixed with the WalletRoot
     * onboarding PIN — see the worklog entry for Task 5-fix.
     */
    @Test
    fun create_flow_reveals_phrase_and_quiz_before_locking() {
        assertTrue("Engine must come up on a fresh profile", bootstrapFreshEngine())
        val appGraph = (targetContext.applicationContext as com.roombrowser.RoomBrowserApp).graph
        val profileId1 = ProfileId(
            runBlocking {
                appGraph.appState.activeProfileIdSnapshot()
                    ?: error("engine must have persisted the active profile id")
            }
        )

        assertTrue(
            "Profile settings must open",
            openSheetEntry("Profile settings") {
                device.wait(Until.hasObject(By.textContains("Profile Settings —")), 8_000)
            }
        )
        assertTrue(
            "The Wallet settings row must be tappable (verified: activity opens)\n${uiTree()}",
            clickTextVerifiedScrollable("Multi-chain wallet, accounts and dApp connections", 60_000) {
                device.findObjects(By.text("Set up your wallet")).isNotEmpty() ||
                    device.findObjects(By.textContains("Wallet locked")).isNotEmpty() ||
                    device.findObjects(By.textContains("Total balance")).isNotEmpty()
            }
        )
        assertTrue(
            "WalletActivity must open on the onboarding choice screen",
            hasText("Set up your wallet", 15_000)
        )

        // CHOICE -> CREATE_INTRO.
        assertTrue("Create must be tappable", clickText("Create a new wallet", 5_000))
        assertTrue("The create intro must render", hasText("Chains to enable", 8_000))

        // CREATE_INTRO -> the wallet is created and the reveal screen shows
        // the ONE-TIME phrase.
        assertTrue("Create Wallet must be tappable", clickText("Create Wallet", 5_000))
        assertTrue("The reveal screen must render", hasText("Your recovery phrase", 20_000))

        // Reveal, then READ the 24 numbered cells (kept in memory only).
        // Scroll-aware: on the 320x640 CI screen the Reveal button can sit
        // below the fold under the one-time-phrase copy.
        assertTrue("Reveal must be tappable", clickTextWithScroll("Reveal"))
        // Wait for the label to flip, sampling BOTH query shapes, and sample
        // the main thread alongside them.
        //
        // WHAT THE SAMPLES ALREADY SETTLED. The first version of this wait was
        // a single `hasText`, and the first repair assumed the accessibility
        // pipeline was at fault -- that one query had sampled a tree seconds
        // stale. The 20 s / 72-sample run that followed killed that reading.
        // Every fresh sample, over the exact shape and the substring shape
        // alike, returned false, and a `uiTree()` issued immediately after the
        // last one found "Hide phrase". The pipeline was not stale and the
        // query shape was never the variable: the label genuinely flipped
        // about twenty seconds after the tap.
        //
        // That is not a slow reveal. `WalletOnboarding` reveals by flipping a
        // `remember { mutableStateOf(false) }` from the Button's own onClick,
        // so the only things between the tap and the repaint are the main
        // thread's message queue and the frame it produces. `mainThreadPing`
        // measures that queue: a posted no-op is the cheapest message there
        // is, so its latency is a floor on how long the tap and the frame
        // callback behind it had to wait, and when the no-op does not run at
        // all the main thread's own stack is captured and names the reason.
        // A run that reports `main=0ms` at every sample while the label stays
        // stale rules the main thread out and says so.
        //
        // This still fails when the label never flips -- `revealed` is set only
        // from a live query and nothing here taps a second time, so a screen
        // that stayed on "Reveal" still ends the test.
        //
        // The samples accumulate in memory and are emitted only on the failure
        // path, so a green run pays one ArrayList, one cheap post per sample
        // and no logcat.
        val timeline = mutableListOf<String>()
        var blockedStack: String? = null
        val revealStartedAt = System.currentTimeMillis()
        val revealed = waitUntil(20_000) {
            val exact = device.findObjects(By.text("Hide phrase")).isNotEmpty()
            val sweep = device.findObjects(By.textContains("Hide phrase")).isNotEmpty()
            val (pingMs, pingStack) = mainThreadPing(budgetMs = 200)
            if (pingMs < 0 && blockedStack == null) blockedStack = pingStack
            timeline += "${System.currentTimeMillis() - revealStartedAt}ms " +
                "exact=$exact sweep=$sweep " +
                (if (pingMs < 0) "main=BLOCKED" else "main=${pingMs}ms")
            exact || sweep
        }
        val failTree = if (revealed) "" else uiTree()
        if (!revealed) {
            // How long the flip actually took, past the window. "It was there
            // when the next query ran" is not a number; this is, and only a
            // number can say whether the label was one frame late or twenty
            // seconds late. Observes only -- no tap, so it cannot create the
            // state it is measuring.
            val flipWindowClosed = System.currentTimeMillis()
            val flippedLate = waitUntil(10_000) {
                device.findObjects(By.textContains("Hide phrase")).isNotEmpty()
            }
            logProbe(
                "reveal-late-flip",
                if (flippedLate) {
                    "label appeared ${System.currentTimeMillis() - flipWindowClosed}ms " +
                        "after the 20s window closed " +
                        "(${System.currentTimeMillis() - revealStartedAt}ms after the tap)"
                } else {
                    "label did not appear within a further 10s"
                }
            )
            logProbe("reveal-not-shown", failTree)
            logProbe("reveal-timeline", timeline.joinToString(", "))
            blockedStack?.let { logProbe("reveal-main-thread-stack", it) }
        }
        assertTrue(
            buildString {
                append("Hide phrase must show once revealed")
                if (!revealed) {
                    append("\nprobe: display=").append(device.displayWidth)
                        .append('x').append(device.displayHeight)
                    // The bounds matter as much as the booleans: a node that is
                    // only half-clipped at the fold is in the tree with a rect
                    // whose centre can sit OUTSIDE the window, and the tap then
                    // lands on the navigation bar instead of the Button.
                    val revealNode = device.findObjects(By.text("Reveal")).firstOrNull()
                    append("\nprobe: reveal_node=").append(
                        revealNode?.let {
                            runCatching { it.visibleBounds.toString() }.getOrDefault("bounds?")
                        } ?: "absent"
                    )
                    append("\nprobe: still_on_reveal_screen=")
                    append(device.findObjects(By.text("Your recovery phrase")).isNotEmpty())
                    append(" hide_node_present=")
                    append(device.findObjects(By.text("Hide phrase")).isNotEmpty())
                    // Which window actually owns the screen at failure time.
                    // A tap is delivered to the WINDOW at that point, not to
                    // the app's node graph -- an IME (imePadding is on this
                    // Scaffold) or any other window over the Button would
                    // swallow it while the app's own a11y tree still reads
                    // normally. `executeShellCommand` runs no shell, so the
                    // filter is done here rather than with `| grep`.
                    val imeUp = runCatching {
                        device.executeShellCommand("dumpsys input_method")
                            .contains("mInputShown=true")
                    }.getOrDefault(false)
                    val focus = runCatching {
                        sequenceOf("dumpsys window", "dumpsys activity activities")
                            .flatMap { cmd ->
                                device.executeShellCommand(cmd).lineSequence().filter {
                                    it.contains("mCurrentFocus") ||
                                        it.contains("mFocusedApp") ||
                                        it.contains("ResumedActivity")
                                }
                            }
                            .joinToString(" | ")
                            .take(400)
                    }.getOrDefault("?")
                    append("\nprobe: ime_up=").append(imeUp)
                    append("\nprobe: focus=").append(focus)
                    // There used to be `retap` and `edge_tap` probes here, and
                    // they were actively harmful: "Reveal" is the SAME button
                    // that toggles the label, so on a screen that had already
                    // revealed, re-tapping it flips the phrase back to hidden
                    // -- destroying the very state the probe was called in to
                    // describe. A probe may not change what it measures.
                    append("\nprobe: reveal_timeline=").append(timeline.joinToString(", "))
                    // Only ever present when a posted no-op did not run, i.e.
                    // when the main thread was provably not draining its
                    // queue. Its absence is a finding too, and the timeline
                    // above is where that reads.
                    blockedStack?.let {
                        append("\nprobe: main_thread_stack_while_stale:\n").append(it)
                    }
                    append("\nprobe tree at failure:\n").append(failTree)
                }
            },
            revealed
        )
        // The export button is the last row of this screen, under the grid.
        // Scroll-aware for the same reason as Reveal above: off-screen nodes
        // are not in the a11y tree, so a plain By.text probe would fail on a
        // button that is present and correct. Placed before readWordCells
        // because that reader walks the grid back toward the top by itself.
        assertTrue(
            "The reveal screen must offer the encrypted-keys export",
            hasTextWithScroll("Export keys to an encrypted file")
        )
        // The scroll-collecting reader walks the grid toward the top in one
        // bounded pass; a second pass covers reveal-recomposition lag on the
        // 2-core runner. (No outer waitUntil — each pass already carries its
        // own attempt budget; nesting both would be unbounded.)
        val words = HashMap<Int, String>()
        assertTrue(
            "The 24 word cells must be readable after Reveal",
            run {
                var cells = readWordCells()
                if (cells.size < 24) cells = readWordCells()
                if (cells.size == 24 && cells.keys.sorted() == (1..24).toList()) {
                    words.putAll(cells)
                    true
                } else {
                    false
                }
            }
        )
        val phrase = (1..24).joinToString(" ") { words.getValue(it) }

        // REVEAL -> the confirmation quiz: read each "Tap word #N" prompt
        // and tap the remembered word for that index. CI 033cb23: the word
        // reader parks the viewport at the TOP of the reveal column, so the
        // button below the grid is OFF-SCREEN — the click must be
        // scroll-aware (dragUpQuarter walks the viewport back DOWN).
        assertTrue("I wrote it down must be tappable", clickTextWithScroll("I wrote it down"))
        assertTrue("The quiz must render", hasText("Confirm your phrase", 10_000))
        assertTrue("The quiz must be answerable", answerQuiz(words))
        assertTrue("Done must be tappable", clickTextWithScroll("Done"))

        // The gate fires after the quiz; with no device credentials it
        // fails and the LOCKED pane takes over — gracefully.
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(targetContext).canAuthenticate(authenticators) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            assertTrue("The LOCKED pane must render after the gate fails", hasText("Wallet locked", 15_000))
            assertTrue("The retry button must be present", hasText("Unlock", 5_000))
            assertTrue(
                "A failed Unlock must keep the pane (graceful)",
                clickText("Unlock", 5_000) && hasText("Wallet locked", 5_000)
            )
        }

        // DB ground truth: the just-shown phrase is stored as ciphertext.
        assertMnemonicCiphertext(profileId1, phrase)
    }
}
