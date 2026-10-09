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
 * E2E for this edition's WebAuthn policy: a page must NOT be able to see the
 * passkey API.
 *
 * GEKOVIEW-ONLY, AND THE POLICY IS DELIBERATELY ASYMMETRIC. An assertion cannot
 * be serviced in a third-party GeckoView app: the request falls through to GMS
 * FIDO2, whose failure GeckoView never reports, so `navigator.credentials.get()`
 * never settles and a sign-in that offers a passkey spins forever. The engine
 * therefore hides `security.webauth.webauthn` so a site falls back to its
 * password. The WebView edition keeps passkeys because its engine CAN service
 * them -- so this test must never be copied to the sibling repo, and a future
 * GeckoView that can service an assertion means deleting this test and the pref
 * together (see `GeckoEngineHost.hideWebAuthn`).
 *
 * THE PROBE CANNOT PASS VACUOUSLY. `PublicKeyCredential` is `[SecureContext]`,
 * so on an untrustworthy origin it is undefined no matter what the policy says;
 * `isSecureContext` is checked FIRST and reported as its own token, so
 * `PASSKEY-API-ABSENT` is only reachable on a trustworthy origin where the
 * interface is genuinely gone. The probe also cannot pass on a broken load: a
 * page whose script never ran leaves `PASSKEY-PROBE-WAITING`, and every other
 * outcome -- present, present-and-offering-conditional-mediation, insecure
 * context -- prints a token that names itself in the failure dump.
 *
 * Run alone this test creates the profile it needs, so a filtered dispatch needs
 * only `com.roombrowser.PasskeyPolicyE2eTest` (A00WarmupTest deletes its own
 * profile and leaves no active one behind).
 */
@RunWith(AndroidJUnit4::class)
class PasskeyPolicyE2eTest {

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
    fun passkey_api_is_hidden_from_pages() {
        assertTrue(
            "A profile must be active before the engine activity can be cold-started",
            ensureActiveProfile()
        )
        val url = server.url("/passkey").toString()
        var hidden = false
        for (attempt in 1..2) {
            launchEngineAt(url)
            hidden = hasText("PASSKEY-API-ABSENT", 30_000)
            if (hidden) break
            // The first engine activity after a profile switch SELF-RESTARTS
            // (bind fail -> kill + alarm); the second launch lands after it.
            device.waitForIdle(2_000)
        }
        if (!hidden) {
            snap("passkey-policy")
            fail(
                "A page on a trustworthy origin must not see the passkey API: " +
                    "this build cannot service an assertion, and a site that still " +
                    "feature-detects `PublicKeyCredential` will hang on it instead " +
                    "of offering its password.\n${diagnostics()}"
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
                    "E2E Passkey ${System.currentTimeMillis()}",
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
     * the hit count, a policy failure and a page that never loaded look the
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
         * exposes as text.
         */
        const val PROBE_PAGE = """
            <h1>ROOM-E2E-PASSKEY</h1>
            <div id="out">PASSKEY-PROBE-WAITING</div>
            <script>
              var out = document.getElementById('out');
              if (!window.isSecureContext) {
                // PublicKeyCredential is [SecureContext]: its absence here
                // would say nothing about the engine's policy.
                out.textContent = 'PASSKEY-PROBE-INSECURE-CONTEXT';
              } else if (typeof window.PublicKeyCredential === 'undefined') {
                out.textContent = 'PASSKEY-API-ABSENT';
              } else if (typeof window.PublicKeyCredential.isConditionalMediationAvailable === 'function') {
                // The lever a signing page pulls when it decides to offer a
                // passkey before the user has typed anything.
                window.PublicKeyCredential.isConditionalMediationAvailable().then(
                  function (v) { out.textContent = 'PASSKEY-API-PRESENT-CM-' + v; },
                  function () { out.textContent = 'PASSKEY-API-PRESENT-CM-REJECTED'; }
                );
              } else {
                out.textContent = 'PASSKEY-API-PRESENT';
              }
            </script>
            """

        val PROBE_TOKENS = listOf(
            "PASSKEY-PROBE-WAITING",
            "PASSKEY-PROBE-INSECURE-CONTEXT",
            "PASSKEY-API-ABSENT",
            "PASSKEY-API-PRESENT",
            "PASSKEY-API-PRESENT-CM-REJECTED"
        )
    }
}
