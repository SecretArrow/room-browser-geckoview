package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.roombrowser.di.AppGraph
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
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

@RunWith(AndroidJUnit4::class)
class TextScaleE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext
    private val graph: AppGraph get() = (targetContext.applicationContext as RoomBrowserApp).graph

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                return if (path.startsWith("/scale")) {
                    page()
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun page(): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(
            """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html, body { margin: 0; padding: 0; }
              #probe { display: inline-block; white-space: nowrap; font-family: monospace; font-size: 16px; }
            </style>
            </head><body>
            <div id="probe">MMMMMMMMMM</div>
            <div id="out">ROOMTEXT w=0 h=0 a=0 z=1 t=none</div>
            <script>
            (function () {
              var probe = document.getElementById('probe');
              var out = document.getElementById('out');
              function state() {
                var root = document.documentElement;
                var inlineZoom = root.style.zoom || '';
                var inlineAdjust = root.style.getPropertyValue('-webkit-text-size-adjust') || '';
                var cs = getComputedStyle(root);
                var computedZoom = cs.zoom || '';
                var computedAdjust = cs.getPropertyValue('-webkit-text-size-adjust') ||
                  cs.getPropertyValue('text-size-adjust') || '';
                var zoomed =
                  (computedZoom !== '' && computedZoom !== 'normal' && computedZoom !== 'auto' &&
                    computedZoom !== '1' && computedZoom !== '1.0') ||
                  (inlineZoom !== '' && inlineZoom !== 'normal' && inlineZoom !== '1' && inlineZoom !== '1.0');
                var adjusted =
                  computedAdjust.indexOf('%') !== -1 || inlineAdjust.indexOf('%') !== -1;
                return {
                  on: zoomed || adjusted,
                  z: inlineZoom || computedZoom || 'none',
                  t: inlineAdjust || computedAdjust || 'none'
                };
              }
              function tick() {
                var r = probe.getBoundingClientRect();
                var s = state();
                out.textContent = 'ROOMTEXT w=' + Math.round(r.width) +
                  ' h=' + Math.round(r.height) +
                  ' a=' + (s.on ? 1 : 0) + ' z=' + s.z + ' t=' + s.t;
              }
              var started = Date.now();
              (function loop() {
                tick();
                if (Date.now() - started < 120000) setTimeout(loop, 150);
              })();
            })();
            </script>
            </body></html>
            """.trimIndent()
        )

    private data class Probe(val width: Int, val height: Int, val applied: Boolean, val zoom: String, val adjust: String)

    private fun probe(): Probe? {
        val node = device.findObjects(By.textContains("ROOMTEXT")).firstOrNull() ?: return null
        val text = node.text ?: return null
        val width = Regex("w=(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val height = Regex("h=(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val applied = Regex("a=(\\d)").find(text)?.groupValues?.get(1) == "1"
        val zoom = Regex("z=(\\S+)").find(text)?.groupValues?.get(1) ?: "?"
        val adjust = Regex("t=(\\S+)").find(text)?.groupValues?.get(1) ?: "?"
        return Probe(width, height, applied, zoom, adjust)
    }

    // Two consecutive samples agreeing: a single `width > 0` can be a partial
    // layout, which at 1.0 would make the 2.0 reading look like growth.
    private fun waitForMeasured(timeoutMs: Long): Probe? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var previous: Probe? = null
        var last: Probe? = null
        while (System.currentTimeMillis() < deadline) {
            val p = probe()
            if (p != null && p.width > 0) {
                last = p
                if (previous != null && previous.width == p.width && previous.height == p.height) {
                    return p
                }
                previous = p
            }
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
            }
        }
        return last
    }

    // The gate is the rendered size, not the mechanism behind it: a WebView too
    // old for DOCUMENT_START_SCRIPT cannot receive the shared shim, so the
    // engine's own text scaler carries the setting there instead. `applied`,
    // `zoom` and `adjust` stay in the failure message as diagnostics.
    private fun waitForWidthAbove(floor: Int, timeoutMs: Long): Probe? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Probe? = null
        while (System.currentTimeMillis() < deadline) {
            val p = probe()
            if (p != null) {
                last = p
                if (p.width > floor) return p
            }
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
            }
        }
        return last
    }

    private fun activeProfileId(): ProfileId? = runBlocking {
        graph.appState.activeProfileIdSnapshot()?.let { ProfileId(it) }
    }

    private fun ensureActiveProfile(): ProfileId? = runBlocking {
        val active = graph.appState.activeProfileIdSnapshot()
        val id = active ?: graph.profileManager
            .create("E2EText${System.nanoTime()}", "A", 0xFF7C4DFFL)
            .id.value
        graph.appState.setActiveProfile(id)
        ProfileId(id)
    }

    private fun setFontScale(scale: Float) {
        runBlocking<Unit> {
            val id = activeProfileId() ?: return@runBlocking
            val current = graph.profileRepo.getProfile(id)?.settings ?: ProfileSettings()
            graph.profileManager.updateSettings(id, current.copy(fontScale = scale))
        }
    }

    private fun launchEngineAt(url: String) {
        targetContext.startActivity(
            Intent()
                .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("com.roombrowser.extra.INITIAL_URL", url)
        )
    }

    private fun uiTree(): String = try {
        val texts = runCatching {
            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(70)
        }.getOrDefault(emptyList())
        "TEXTS: $texts"
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }

    @Test
    fun rendered_page_text_grows_with_font_scale() {
        assertTrue("A profile must be active before the engine can boot", ensureActiveProfile() != null)

        val url = server.url("/scale").toString()

        setFontScale(1.0f)
        launchEngineAt(url)
        val base = waitForMeasured(90_000)
        assertTrue("The page must load and measure at fontScale=1.0\n${uiTree()}", base != null)
        val widthAtOne = base!!.width
        val heightAtOne = base.height
        assertTrue("The measured text width must be positive at fontScale=1.0", widthAtOne > 0)

        setFontScale(2.0f)
        launchEngineAt(url)
        val scaled = waitForWidthAbove(widthAtOne + 8, 90_000)
        assertTrue(
            "The page must come back up at fontScale=2.0\n${uiTree()}",
            scaled != null
        )
        val widthAtTwo = scaled!!.width
        val heightAtTwo = scaled.height

        assertTrue(
            "The rendered layout size of a fixed string must grow with fontScale: " +
                "probe getBoundingClientRect() was ${widthAtOne}x$heightAtOne px at fontScale=1.0 and " +
                "${widthAtTwo}x$heightAtTwo px at fontScale=2.0 (scale applied=${scaled.applied}, " +
                "zoom=${scaled.zoom}, text-size-adjust=${scaled.adjust})",
            widthAtTwo > widthAtOne + 8
        )
        device.waitForIdle(200)
    }
}
