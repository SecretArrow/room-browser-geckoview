package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.roombrowser.domain.agent.OllamaRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * E2E for the Local AI (Ollama) manager (real app UI, fake Ollama server):
 *
 *   AgentSettingsActivity (launched DIRECTLY, default process — no browser
 *   round-trip needed; the package name is resolved from the instrumentation
 *   target context, never hard-coded, because debug builds carry an
 *   applicationId suffix)
 *     -> "Local AI (Ollama)" entry row -> LocalAiActivity (own window)
 *     -> clear + type the MockWebServer host + Connect
 *     -> status "Connected · Ollama 0.5.7"      (GET /api/version)
 *     -> installed models listed from the server (GET /api/tags → llama3.2:1b)
 *     -> "Pull to Ollama server instead" on a preset
 *        (POST /api/pull, NDJSON stream)
 *     -> the Downloads section grows a live row for that tag
 *
 *   Catalog refresh ("Find new models", LocalAiActivity launched directly
 *   with the test-only library_url AND registry_url extras pointing at the
 *   same fake server):
 *     -> GET /library?sort=newest → structurally faithful HTML slice
 *     -> preset-covered families (llama3.2) and embedding-only families
 *        (bge-m3) stay hidden; the new phone-suitable family (qwen3.5,
 *        badges 0.8b/2b/27b) is discovered
 *     -> tapping its 0.8b Install button resolves the tag on the fake REGISTRY
 *        (GET /v2/library/qwen3.5/manifests/0.8b → the model layer) and
 *        downloads that blob (GET /v2/…/blobs/…) onto the ON-DEVICE engine,
 *        so the card flips to the Installed chip
 *
 * WHY THE FIRST TEST PULLS TO THE SERVER AND DOES NOT PRESS "Install": the
 * built-in engine's Install talks to the public model registry. That test
 * enters LocalAiActivity through the real "Local AI (Ollama)" row, which
 * carries no registry_url override — pressing Install there would resolve
 * against the REAL registry.ollama.ai and start a multi-hundred-MB download
 * inside CI. The registry path is covered by the second test, which launches
 * the activity directly and CAN redirect it.
 *
 * The fake server is a STATEFUL [Dispatcher], not a strict response queue:
 * the LocalAiController may call /api/version and /api/tags in any order and
 * any count (refresh on connect, refresh after a finished pull, manual
 * refresh) — every request gets a correct response, and /api/tags lists
 * every model that has been pulled so far, so the flow stays deterministic.
 *
 * HONEST NOTE — pause/resume labels are NOT asserted here: the MockWebServer
 * pull body completes instantly, so the DOWNLOADING phase can flash by
 * before UiAutomator polls the accessibility tree. Pause/resume semantics
 * (job cancel + re-attach, server-side layer cache) are covered by the
 * controller's unit tests (OllamaLocalTest). The library PARSER itself is
 * unit-tested against a faithful fixture in OllamaDtosTest, and the registry
 * translation (tag → manifest URL → blob URL → file id) in
 * OllamaRegistryTest — the e2e only proves the wire-up (button → fetch →
 * parsed cards → install → bytes on disk).
 *
 * Compose fields are driven exactly like AgentSettingsE2eTest: semantics
 * content-description nodes + `input text` shell command (WITHOUT quotes —
 * executeShellCommand does not parse shell quoting) + coordinate clicks.
 */
@RunWith(AndroidJUnit4::class)
class LocalAiE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer
    private lateinit var fake: OllamaFake

    /**
     * Structurally faithful slice of ollama.com/library (see OllamaDtosTest
     * for the full-fidelity parser tests). Three families on purpose:
     *  - llama3.2 — covered by the curated presets → hidden from discovery
     *  - qwen3.5 — NEW phone-suitable family (0.8b/2b fit; 27b too big)
     *  - bge-m3  — embedding-only → filtered out of discovery
     */
    private val libraryHtml = """
        <html><body><ul>
        <li  class="flex items-baseline border-b border-neutral-200 py-6">
          <a href="/library/llama3.2" class="group w-full space-y-5">
            <div  title="llama3.2" class="flex flex-col">
              <p class="max-w-lg break-words text-neutral-800 text-md">Meta&#39;s compact multilingual models.</p>
            </div>
            <div class="flex flex-col space-y-2">
              <div class="flex flex-wrap space-x-2">
                <span  class="inline-flex items-center rounded-md bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-600 sm:text-[13px]">tools</span>
                <span  class="inline-flex items-center rounded-md bg-[#ddf4ff] px-2 py-0.5 text-xs font-medium text-blue-600 sm:text-[13px]">1b</span>
                <span  class="inline-flex items-center rounded-md bg-[#ddf4ff] px-2 py-0.5 text-xs font-medium text-blue-600 sm:text-[13px]">3b</span>
              </div>
              <span><span class="hidden sm:flex">Updated&nbsp;</span><span >3 months ago</span></span>
            </div>
          </a>
        </li>
        <li  class="flex items-baseline border-b border-neutral-200 py-6">
          <a href="/library/qwen3.5" class="group w-full space-y-5">
            <div  title="qwen3.5" class="flex flex-col">
              <p class="max-w-lg break-words text-neutral-800 text-md">Qwen 3.5 is a family of open-source multimodal models.</p>
            </div>
            <div class="flex flex-col space-y-2">
              <div class="flex flex-wrap space-x-2">
                <span  class="inline-flex items-center rounded-md bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-600 sm:text-[13px]">tools</span>
                <span  class="inline-flex items-center rounded-md bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-600 sm:text-[13px]">thinking</span>
                <span  class="inline-flex items-center rounded-md bg-[#ddf4ff] px-2 py-0.5 text-xs font-medium text-blue-600 sm:text-[13px]">0.8b</span>
                <span  class="inline-flex items-center rounded-md bg-[#ddf4ff] px-2 py-0.5 text-xs font-medium text-blue-600 sm:text-[13px]">2b</span>
                <span  class="inline-flex items-center rounded-md bg-[#ddf4ff] px-2 py-0.5 text-xs font-medium text-blue-600 sm:text-[13px]">27b</span>
              </div>
              <span><span class="hidden sm:flex">Updated&nbsp;</span><span >3 weeks ago</span></span>
            </div>
          </a>
        </li>
        <li  class="flex items-baseline border-b border-neutral-200 py-6">
          <a href="/library/bge-m3" class="group w-full space-y-5">
            <div  title="bge-m3" class="flex flex-col">
              <p class="max-w-lg break-words text-neutral-800 text-md">BGE-M3 is a versatile embedding model.</p>
            </div>
            <div class="flex flex-col space-y-2">
              <div class="flex flex-wrap space-x-2">
                <span  class="inline-flex items-center rounded-md bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-600 sm:text-[13px]">embedding</span>
                <span  class="inline-flex items-center rounded-md bg-[#ddf4ff] px-2 py-0.5 text-xs font-medium text-blue-600 sm:text-[13px]">0.5b</span>
              </div>
              <span><span class="hidden sm:flex">Updated&nbsp;</span><span >2 months ago</span></span>
            </div>
          </a>
        </li>
        </ul></body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        // Determinism: the runner's shared IP makes every fresh-profile boot
        // arm the organic network warning — suppress it (see E2eDeterminism).
        E2eDeterminism.suppressOrganicNetworkWarnings()
        server = MockWebServer()
        fake = OllamaFake(libraryHtml)
        // Kotlin property syntax — OkHttp 4.x MockWebServer.dispatcher is a
        // var, so the Java-style setDispatcher() does not resolve in Kotlin.
        server.dispatcher = fake
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /**
     * Stateful fake Ollama server + fake public model registry:
     *  - GET  /api/version → {"version":"0.5.7"}
     *  - GET  /api/tags    → llama3.2:1b + every model pulled so far
     *  - POST /api/pull    → instant NDJSON success stream
     *  - GET  /library     → the faithful HTML slice above (catalog refresh)
     *  - GET  /v2/<name>/manifests/<ref> → a Docker Registry v2 manifest whose
     *    model layer is [MODEL_DIGEST] (catalog Install resolution)
     *  - GET  /v2/<name>/blobs/<digest>  → the model bytes themselves
     *
     * The registry routes are what make an Install testable at all: without
     * them the app would resolve against the real registry.ollama.ai.
     */
    private class OllamaFake(val libraryHtml: String) : Dispatcher() {
        private val pulled = mutableSetOf<String>()

        /** Ground truth for the refresh test: GET /library hit count. */
        val libraryHits = AtomicInteger()

        /** Resolve-side hits — 0 means the Install tap never reached the registry. */
        val manifestHits = AtomicInteger()

        /** Download-side hits — 0 with manifestHits > 0 means the blob URL was wrong. */
        val blobHits = AtomicInteger()

        /**
         * POST /api/pull count. Printed in the failure messages next to
         * [pullTags]: hits > 0 with an empty tag list means the request
         * arrived but its body did not carry the model name.
         */
        val pullHits = AtomicInteger()

        /**
         * Tags seen in a /api/pull BODY, in arrival order.
         *
         * The durable half of "the Pull-to-server tap worked". The UI evidence
         * for a server pull (a Downloads row) renders at the TOP of the screen
         * while the pull buttons live deep in the catalog, so a probe parked on
         * the catalog cannot see it without scrolling back — and on the instant
         * fake the row is already finished by then. What the request body says
         * is immune to both, and it also cross-checks that the tag on the
         * button is the tag that actually got pulled.
         */
        private val pullTagLog = ConcurrentLinkedQueue<String>()

        fun pullTags(): List<String> = pullTagLog.toList()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: ""
            return when {
                path.startsWith("/library") -> {
                    libraryHits.incrementAndGet()
                    MockResponse()
                        .setHeader("Content-Type", "text/html")
                        .setBody(libraryHtml)
                }

                // ---- public registry (Docker Registry v2) ------------------
                path.contains("/manifests/") -> {
                    manifestHits.incrementAndGet()
                    MockResponse()
                        .setHeader(
                            "Content-Type",
                            "application/vnd.docker.distribution.manifest.v2+json"
                        )
                        .setBody(manifestJson())
                }

                path.contains("/blobs/") -> {
                    blobHits.incrementAndGet()
                    MockResponse()
                        .setHeader("Content-Type", "application/octet-stream")
                        // Deliberately NOT a real GGUF: the store lists any
                        // *.gguf and reports meta = null for an unparseable
                        // header, which is exactly the honest behaviour under
                        // test here (the chip is keyed on the file name).
                        .setBody(Buffer().write(ByteArray(MODEL_BYTES) { 0x42 }))
                }

                path.startsWith("/api/version") ->
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"version":"0.5.7"}""")

                path.startsWith("/api/tags") -> {
                    val models = StringBuilder()
                        .append(llamaModelJson())
                    synchronized(pulled) {
                        pulled.forEach { tag ->
                            models.append(',')
                                .append(pulledModelJson(tag))
                        }
                    }
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"models":[$models]}""")
                }

                path.startsWith("/api/pull") -> {
                    pullHits.incrementAndGet()
                    val body = request.body.readUtf8()
                    Regex(""""model"\s*:\s*"([^"]+)"""").find(body)
                        ?.groupValues?.get(1)
                        ?.let { tag ->
                            pullTagLog.add(tag)
                            synchronized(pulled) { pulled.add(tag) }
                        }
                    MockResponse()
                        .setHeader("Content-Type", "application/x-ndjson")
                        .setBody(
                            "{\"status\":\"pulling manifest\"}\n" +
                                "{\"status\":\"downloading\",\"digest\":\"sha256:abc\",\"completed\":500,\"total\":1000}\n" +
                                "{\"status\":\"verifying sha256 digest\"}\n" +
                                "{\"status\":\"success\"}"
                        )
                }

                else -> MockResponse().setResponseCode(404)
            }
        }

        /**
         * A manifest shaped like the real ones: the weights sit in a layer
         * whose mediaType is the ollama model type, alongside metadata layers
         * the client must ignore. The order is deliberately metadata-first —
         * a client that grabbed `layers[0]` would fail here.
         */
        private fun manifestJson() = """
            {"schemaVersion":2,
             "mediaType":"application/vnd.docker.distribution.manifest.v2+json",
             "layers":[
               {"mediaType":"application/vnd.ollama.image.params","digest":"sha256:params","size":42},
               {"mediaType":"application/vnd.ollama.image.license","digest":"sha256:license","size":7},
               {"mediaType":"$MODEL_MEDIA_TYPE","digest":"$MODEL_DIGEST","size":$MODEL_BYTES}
             ]}
        """.trimIndent()

        private fun llamaModelJson() =
            """{"name":"llama3.2:1b","model":"llama3.2:1b","size":1328238021,""" +
                """"digest":"sha256:llama","modified_at":"2025-01-01T00:00:00Z",""" +
                """"details":{"family":"llama","parameter_size":"1.2B","quantization_level":"Q4_K_M"}}"""

        private fun pulledModelJson(tag: String) =
            """{"name":"$tag","model":"$tag","size":494337152,""" +
                """"digest":"sha256:pulled","modified_at":"2025-01-01T00:00:00Z",""" +
                """"details":{"family":"qwen2","parameter_size":"0.5B","quantization_level":"Q4_K_M"}}"""

        private companion object {
            /** The real registry's own media type for the weights layer. */
            const val MODEL_MEDIA_TYPE: String = OllamaRegistry.MODEL_MEDIA_TYPE

            /** Shaped like a real digest so the blob URL is exercised verbatim. */
            const val MODEL_DIGEST: String =
                "sha256:c5396e06af294bd101b30dce59131a76d2b773e76950acc870eda801d3ab0515"

            /** Tiny stand-in for multi-GB weights — completion is what matters. */
            const val MODEL_BYTES: Int = 4 * 1024
        }
    }

    // =====================================================================
    // Launch helpers
    // =====================================================================

    /**
     * Launches AgentSettingsActivity directly (default process) — its own
     * window, no browser round-trip. The activity is not exported, so the
     * primary path is the app's own context (same-app start is allowed);
     * the `am start` shell fallback uses the REAL application id resolved
     * from the instrumentation target context (never hard-coded: debug
     * builds append ".debug" to the application id while the class keeps
     * the fixed source namespace).
     */
    private fun launchAgentSettingsDirectly() {
        runCatching {
            val intent = Intent()
                .setClassName(targetContext, "com.roombrowser.agent.ui.AgentSettingsActivity")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            targetContext.startActivity(intent)
        }
        if (!hasText("AI Agent Settings", 4_000)) {
            device.executeShellCommand(
                "am start -n ${targetContext.packageName}/com.roombrowser.agent.ui.AgentSettingsActivity"
            )
            device.waitForIdle(2_000)
        }
    }

    /**
     * Launches LocalAiActivity directly with the TEST-ONLY library_url and
     * registry_url extras (mirrors LocalAiActivity.EXTRA_LIBRARY_URL /
     * EXTRA_REGISTRY_URL; kept as literals so the test reads like the manifest
     * contract) — the catalog refresh then talks to the fake server instead of
     * ollama.com, and an Install resolves against the fake registry instead of
     * the REAL registry.ollama.ai.
     *
     * The PRIMARY path uses FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK so
     * an instance left by the earlier test method can never absorb the launch
     * (a stale, extra-less activity would silently point BOTH the refresh and
     * every Install at the real internet). The shell fallback carries the same
     * flags + extras.
     */
    private fun launchLocalAiDirectly(libraryUrl: String, registryUrl: String) {
        runCatching {
            val intent = Intent()
                .setClassName(targetContext, "com.roombrowser.agent.ui.LocalAiActivity")
            intent.putExtra("library_url", libraryUrl)
            intent.putExtra("registry_url", registryUrl)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            targetContext.startActivity(intent)
        }
        if (!hasDesc("localai_host_field", 4_000)) {
            // 0x10008000 = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK.
            device.executeShellCommand(
                "am start -f 0x10008000 -n ${targetContext.packageName}/com.roombrowser.agent.ui.LocalAiActivity" +
                    " --es library_url $libraryUrl --es registry_url $registryUrl"
            )
            device.waitForIdle(2_000)
        }
    }

    // =====================================================================
    // UiAutomator helpers (proven patterns from AgentSettingsE2eTest)
    // =====================================================================

    private fun hasText(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs)

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

    private fun hasDescContains(part: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.descContains(part)), timeoutMs)

    /** Scroll-aware text wait: off-screen rows are NOT exposed to the a11y
     *  tree — small deterministic drags between polls (no fling overshoot). */
    private fun hasTextWithScroll(text: String, attempts: Int = 10): Boolean {
        for (i in 1..attempts) {
            if (hasText(text, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    /** Scroll-aware desc wait — same reason as [hasTextWithScroll]. */
    private fun hasDescContainsWithScroll(part: String, attempts: Int = 12): Boolean {
        for (i in 1..attempts) {
            if (hasDescContains(part, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickCenter(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        device.click(b.centerX(), b.centerY())
        device.waitForIdle(1_000)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Clicks via the accessibility ACTION_CLICK (immune to overlays like the
     * IME covering the node), walking up to the nearest clickable ancestor
     * for Compose text-inside-button nodes; falls back to a coordinate tap.
     * NB: UiObject2.click() returns Unit.
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

    /** Off-screen rows of a scrollable container are not exposed to the
     *  accessibility tree — advance the viewport with SMALL deterministic
     *  drags between click attempts (no fling overshoot). */
    private fun clickTextWithScroll(text: String, attempts: Int = 12): Boolean {
        for (i in 1..attempts) {
            val node = device.wait(Until.findObject(By.text(text)), 1_500)
            if (node != null && rectSettled(node) && clickSmart(node)) return true
            dragUpQuarter()
        }
        return false
    }

    /**
     * True once two consecutive reads of [node]'s rect agree, i.e. the
     * viewport has stopped moving — the sibling of the mid-fling miss that
     * [dragUpQuarter]'s own KDoc records (CI 798d73c: three Install taps
     * landed on nothing). A settle after the drag cannot cover a node that
     * keeps moving for its own reason, and this helper returns a bare `true`
     * with no verification, so a tap on stale bounds is swallowed silently.
     * Bounded: gives up after [tries] reads so a node that is genuinely
     * animating cannot hang the test.
     */
    private fun rectSettled(node: UiObject2, tries: Int = 6): Boolean {
        var previous = runCatching { node.visibleBounds }.getOrNull() ?: return false
        repeat(tries) {
            try { Thread.sleep(200) } catch (_: InterruptedException) { }
            val current = runCatching { node.visibleBounds }.getOrNull() ?: return false
            if (current == previous) return true
            previous = current
        }
        return false
    }

    /** Scroll-aware desc-contains click (catalog Install buttons).
     *
     *  Shell-tapped, not clicked: `UiObject2.click()` injects a gesture, and
     *  that channel is phantom-dropped on this screen — CI logged
     *  "Clicked on (109, 584)" for the discovered card's Install button, the
     *  app's handler never ran, and the fake registry saw zero manifest
     *  requests. [tapDescWithScroll] also lifts the card out of the bottom
     *  quarter first, which is where the CI emulator's 320x640 screen puts
     *  it. */
    private fun clickDescContainsWithScroll(part: String, attempts: Int = 12): Boolean =
        tapDescWithScroll(part, attempts, ::dragUpQuarter)

    /** SLOW drag (100 steps ≈ no fling momentum) that scrolls ~1/4 of the
     *  screen — deterministic. The settle AFTER the drag lets residual
     *  momentum finish before the caller reads node bounds (a tap on bounds
     * captured mid-fling lands on nothing — CI 798d73c: three Install taps
     * all "succeeded" and 0 registry requests were ever made). */
    private fun dragUpQuarter() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 5 / 8,
            device.displayWidth / 2, device.displayHeight * 3 / 8, 100
        )
        device.waitForIdle(800)
        try { Thread.sleep(300) } catch (_: InterruptedException) { }
    }

    /** Reverse of [dragUpQuarter] — scrolls the viewport toward the START of
     *  the screen, so retry rounds can get back to widgets above the fold. */
    private fun dragDownQuarter() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 8,
            device.displayWidth / 2, device.displayHeight * 5 / 8, 100
        )
        device.waitForIdle(800)
        try { Thread.sleep(200) } catch (_: InterruptedException) { }
    }

    /** Reset to the top of the scrollable screen. Generous on purpose: the
     *  drift between rounds can reach ~5 screens (failed find loops drag the
     *  viewport to the bottom), so 18 quarter-drags (~4.5 screens) climb back
     *  far enough for the next round's down-search to cross the catalog row. */
    private fun scrollToTop() {
        repeat(18) { dragDownQuarter() }
    }

    /** Scroll-aware SHELL tap: find the node by desc, lift it clear of the
     *  bottom of the screen, then tap its visible center via `input tap` —
     *  the same input channel as the proven `input text` typing.
     *
     *  Two CI facts shaped this helper. UiAutomator's gesture injection is
     *  PHANTOM-dropped on this busy screen (the node was found, the "click"
     *  returned success, the onClick never fired), so the tap goes through
     *  the shell. And a tap near the bottom edge is lost too: both catalog
     *  failures tapped at 84-91% of the height — the CI emulator is only
     *  320x640, so "the last row" sits in the system-bar/gesture zone — with
     *  the node found and the app's handler never running. One quarter-drag
     *  puts the target in the middle, where neither can happen.
     *
     *  The exact-text fallback is tried only after the desc was NEVER on
     *  screen, never per attempt. Per attempt it matches whichever card
     *  happens to show that button — a DIFFERENT card. CI proof: the
     *  qwen2.5:0.5b round posted smollm2:360m twice, because after the reset
     *  scroll the topmost visible "Pull to Ollama server instead" belonged to
     *  the first preset. Tapping the wrong card and reporting success is
     *  worse than a round that honestly finds nothing and retries. */
    private fun scrollAndShellTap(descPart: String, textNeedle: String, attempts: Int = 10): Boolean {
        if (tapDescWithScroll(descPart, attempts, ::slowDragUp)) return true
        for (i in 1..attempts / 2) {
            val node = device.wait(Until.findObject(By.text(textNeedle)), 1_000)
            val bounds = node?.let { runCatching { it.visibleBounds }.getOrNull() }
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                device.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")
                device.waitForIdle(800)
                return true
            }
            slowDragUp()
        }
        return false
    }

    /** The shared half of every scroll-aware tap: find [part]'s node, drag it
     *  out of the bottom quarter of the screen, then shell-tap it. A target
     *  that cannot be lifted (the end of the list) is tapped where it is
     *  rather than looping until the attempts run out. */
    private fun tapDescWithScroll(part: String, attempts: Int, drag: () -> Unit): Boolean {
        var repositions = 0
        for (i in 1..attempts) {
            val node = device.wait(Until.findObject(By.descContains(part)), 1_500)
            val bounds = node?.let { runCatching { it.visibleBounds }.getOrNull() }
            if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
                drag()
                continue
            }
            if (bounds.centerY() > device.displayHeight * 3 / 4 && repositions < 3) {
                repositions++
                drag()
                continue
            }
            // SHELL tap (fire-and-forget), NOT device.click():
            // UiAutomator's InteractionController click waits for an
            // accessibility-idle window and TIMED OUT on this busy screen
            // (CI ec43763: 'Timed out waiting 1000ms for command and
            // events' — refresh/pull/install taps all died; three earlier
            // runs of this shell path were green). The stale-bounds risk is
            // handled by the post-drag settles above + verified retries.
            device.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")
            device.waitForIdle(800)
            return true
        }
        return false
    }

    /** Anti-fling scroll: the SAME quarter-screen distance as dragUpQuarter
     *  but over 300 interpolation steps (~3x duration, near-zero velocity).
     *  Compose never flings a drag this slow, so the viewport can never LEAP
     *  past a node between polls (CI proof: refreshBtn=0 while the dump
     *  showed catalog tier cards far beyond the refresh row). */
    private fun slowDragUp() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 5 / 8,
            device.displayWidth / 2, device.displayHeight * 3 / 8, 300
        )
        device.waitForIdle(600)
    }

    /** Catalog-state caption read IN PLACE (no scrolling) — call while the
     *  viewport is still at the refresh button. Viewport-limited on purpose:
     *  the caption lives in the SAME row as the button. */
    private fun catalogCaptionInPlace(): String {
        val probes = listOf(
            "Idle" to "Fetch the live ollama.com library",
            "Loading" to "Fetching the newest models",
            "Ready" to "Library updated",
            "Failed" to "Couldn't read the library"
        )
        for ((label, needle) in probes) {
            val found = runCatching {
                device.findObjects(By.textContains(needle)).isNotEmpty()
            }.getOrDefault(false)
            if (found) return label
        }
        return "?"
    }

    /** Single-LINE screen state for assertion messages — multi-line dumps get
     *  truncated by the runner's console, so everything is joined with ' | '. */
    private fun screenSummary(): String {
        val refreshBtn = runCatching {
            device.findObjects(By.descContains("localai_refresh_catalog")).size
        }.getOrDefault(-1)
        val texts = runCatching {
            device.findObjects(By.textContains(""))
                .mapNotNull { it.text }
                .distinct()
                .take(30)
                .map { it.take(44) }
        }.getOrDefault(emptyList())
        return "libraryHits=${fake.libraryHits.get()} refreshBtn=$refreshBtn " +
            "manifests=${fake.manifestHits.get()} blobs=${fake.blobHits.get()} " +
            "pulls=${fake.pullHits.get()} pulledTags=${fake.pullTags()} " +
            "texts=[${texts.joinToString(" | ")}]"
    }

    /** Full a11y dump AT the catalog top — descs AND texts — for the
     *  button-not-found assertion. Runs after positioning the viewport at the
     *  "Model catalog" header + refresh row (~1.25 screens down from the top). */
    private fun dumpCatalogTop(): String {
        repeat(18) { dragDownQuarter() }
        repeat(5) { dragUpQuarter() }
        device.waitForIdle(800)
        val descs = runCatching {
            device.findObjects(By.descContains("localai"))
                .mapNotNull { it.contentDescription }
                .take(16)
        }.getOrDefault(emptyList())
        val texts = runCatching {
            device.findObjects(By.textContains(""))
                .mapNotNull { it.text }
                .distinct()
                .take(24)
                .map { it.take(40) }
        }.getOrDefault(emptyList())
        return "descs=[${descs.joinToString(", ")}] texts=[${texts.joinToString(" | ")}]"
    }

    /** Types text into the field with the given content description. */
    private fun typeIntoField(desc: String, text: String): Boolean {
        hideImeIfNeeded()
        var field = device.wait(Until.findObject(By.desc(desc)), 4_000)
        if (field == null) {
            dragUpQuarter()
            field = device.wait(Until.findObject(By.desc(desc)), 4_000) ?: return false
        }
        runCatching {
            val b = field.visibleBounds
            if (b.bottom > device.displayHeight - 80) dragUpQuarter()
        }
        clickCenter(field)
        // NB: executeShellCommand does not interpret shell quoting — a quoted
        // argument would type the quotes into the field. Values here contain
        // no spaces or shell metacharacters, so pass them bare.
        device.executeShellCommand("input text $text")
        device.waitForIdle(1_000)
        return true
    }

    /**
     * Clears a field that already holds text (the host field defaults to
     * http://localhost:11434): tap → cursor to end → repeated DEL. Verified
     * by reading the node's accessibility text — retries once more if any
     * characters survived.
     */
    private fun clearField(desc: String): Boolean {
        hideImeIfNeeded()
        val field = device.wait(Until.findObject(By.desc(desc)), 6_000) ?: return false
        for (round in 1..3) {
            clickCenter(field)
            device.executeShellCommand("input keyevent KEYCODE_MOVE_END")
            repeat(32) { device.executeShellCommand("input keyevent KEYCODE_DEL") }
            device.waitForIdle(400)
            val current = runCatching { field.text }.getOrNull()
            if (current.isNullOrBlank()) return true
        }
        return false
    }

    /** The IME is a separate accessibility window that can shadow node
     *  lookups — close it before searching for the next field/button. */
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

    /**
     * The connection-status node carries desc "localai_status"; its visible
     * label ("Connected · Ollama 0.5.7") is exposed as accessibility text.
     * Three probes (desc-node text, screen text, desc-contains) so Compose
     * semantics merging can never hide the version.
     */
    private fun statusContains(part: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val node = runCatching { device.findObject(By.desc("localai_status")) }.getOrNull()
            if (node != null && runCatching { node.text }.getOrNull()?.contains(part) == true) {
                return true
            }
            if (runCatching { device.findObjects(By.textContains(part)) }.getOrDefault(emptyList()).isNotEmpty()) {
                return true
            }
            if (hasDescContains(part, 300)) return true
            try { Thread.sleep(250) } catch (_: InterruptedException) { }
        }
        return false
    }

    /** First visible catalog Install button desc, scrolling if needed.
     *  "localai_install_" cannot collide with "localai_installed_" (the
     *  char after "localai_install" differs: "_" vs "e"). */
    private fun findFirstInstallButton(): String? {
        for (i in 1..8) {
            val nodes = runCatching {
                device.findObjects(By.descContains("localai_install_"))
            }.getOrDefault(emptyList())
            nodes.firstOrNull()?.contentDescription?.let { return it }
            dragUpQuarter()
        }
        return null
    }

    /** First visible "Pull to Ollama server instead" desc, scrolling if needed. */
    private fun findFirstPullServerButton(): String? {
        for (i in 1..8) {
            val nodes = runCatching {
                device.findObjects(By.descContains("localai_pull_server_"))
            }.getOrDefault(emptyList())
            nodes.firstOrNull()?.contentDescription?.let { return it }
            dragUpQuarter()
        }
        return null
    }

    /** Polls [condition] every 200 ms until it holds or [timeoutMs] elapses. */
    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            try { Thread.sleep(200) } catch (_: InterruptedException) { }
        }
        return condition()
    }

    /** The four DownloadRow action descs for [tag] (see [serverPullEvidence]). */
    private fun serverPullProbes(tag: String): List<String> = listOf(
        "localai_pause_$tag",
        "localai_resume_$tag",
        "localai_clear_$tag",
        "localai_cancel_$tag"
    )

    /** True when any DownloadRow action for [tag] is in the CURRENT viewport. */
    private fun serverPullProbesVisible(tag: String): Boolean =
        serverPullProbes(tag).any { probe ->
            runCatching { device.findObjects(By.descContains(probe)) }
                .getOrDefault(emptyList())
                .isNotEmpty()
        }

    /**
     * UI PROOF that a SERVER pull for [tag] produced a Downloads row: the row
     * exposes a Pause button while STARTING/DOWNLOADING/VERIFYING, a Resume
     * button when PAUSED/FAILED, a Cancel button throughout, and a Clear button
     * on the kept SUCCESS row.
     *
     * SCROLL-AWARE, and that is the point: the Downloads section sits near the
     * TOP of the screen (right after "Installed models") while every
     * "Pull to Ollama server instead" button lives deep inside the catalog
     * below it. A probe that only looked at the current viewport therefore
     * reported "the pull never started" for a pull that had started, finished
     * and been rendered several screens above — the viewport is parked on the
     * catalog when the tap happens. So: current viewport first (cheap, and
     * correct when the caller is already up there), then climb to the top and
     * sweep back down.
     *
     * The catalog's Installed chip is deliberately NOT a probe here: on this
     * screen it means "in the BUILT-IN engine's directory", which a server-side
     * pull never changes (see the class KDoc).
     */
    private fun serverPullEvidence(tag: String, timeoutMs: Long): Boolean {
        if (waitUntil(timeoutMs / 2) { serverPullProbesVisible(tag) }) return true
        scrollToTop()
        repeat(14) {
            if (serverPullProbesVisible(tag)) return true
            dragUpQuarter()
        }
        return waitUntil(timeoutMs / 2) { serverPullProbesVisible(tag) }
    }

    /** Probes the local-AI nodes for readable failure messages. */
    private fun uiTree(): String = try {
        val sb = StringBuilder()
        for (probe in listOf(
            "AI Agent Settings title" to By.text("AI Agent Settings"),
            "Local AI row" to By.text("Local AI (Ollama)"),
            "localai_host_field" to By.desc("localai_host_field"),
            "localai_connect" to By.desc("localai_connect"),
            "localai_status" to By.desc("localai_status"),
            "localai_installed_list" to By.desc("localai_installed_list"),
            // Prefix probes — the LABEL is the prefix, so count is how many
            // nodes matched it: "[localai_install_] count=3" is three Install
            // buttons on screen, NOT one card rendered three times. The row
            // that used to be labelled with a whole tag ("localai_install_…")
            // read as a duplicate card for exactly that reason.
            "[localai_install_]" to By.descContains("localai_install_"),
            "[localai_installed_]" to By.descContains("localai_installed_"),
            // The downloads section is the FIRST thing these tests need and the
            // LAST thing a viewport parked on the catalog can see, so a dump
            // without it cannot tell "no row" from "row above the fold".
            "[localai_pull_server_]" to By.descContains("localai_pull_server_"),
            "[localai_cancel_]" to By.descContains("localai_cancel_"),
            "[localai_clear_]" to By.descContains("localai_clear_")
        )) {
            val nodes = runCatching { device.findObjects(probe.second) }.getOrDefault(emptyList())
            sb.append(probe.first).append(": count=").append(nodes.size)
            nodes.take(2).forEach { n ->
                sb.append(" text='").append(runCatching { n.text }.getOrNull())
                    .append("' bounds=").append(runCatching { n.visibleBounds }.getOrNull())
            }
            sb.append('\n')
        }
        val texts = runCatching {
            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(80)
        }.getOrDefault(emptyList())
        sb.append("VISIBLE TEXTS: ").append(texts).append('\n')
        sb.toString().take(9000)
    } catch (t: Throwable) {
        "probe dump failed: $t"
    }

    // =====================================================================
    // The flow
    // =====================================================================

    @Test
    fun local_ai_connect_install_and_pause_resume_labels() {
        // ---- 1. Agent settings activity (own window) -----------------------
        device.pressHome()
        launchAgentSettingsDirectly()
        assertTrue(
            "AgentSettingsActivity must open with its title; UI:\n" + uiTree(),
            hasText("AI Agent Settings", 15_000)
        )

        // ---- 2. Local AI entry row opens LocalAiActivity -------------------
        assertTrue(
            "The Local AI (Ollama) entry row must be clickable; UI:\n" + uiTree(),
            clickTextWithScroll("Local AI (Ollama)")
        )
        // The row label and the screen title share the same text — the
        // screen-open proof is the UNIQUE host field / Connect desc.
        assertTrue(
            "LocalAiActivity must open (host field visible); UI:\n" + uiTree(),
            hasDesc("localai_host_field", 15_000)
        )

        // ---- 3. Point the host field at the fake Ollama server ------------
        assertTrue(
            "Host field must be clearable (defaults to http://localhost:11434)",
            clearField("localai_host_field")
        )
        val host = server.url("/").toString().trimEnd('/')
        assertTrue("Host field must be typeable", typeIntoField("localai_host_field", host))
        hideImeIfNeeded()
        assertTrue("Connect button must be clickable", clickDesc("localai_connect", 8_000))

        // ---- 4. Connected: the status shows the server version -------------
        assertTrue(
            "Connection status must show Ollama 0.5.7; UI:\n" + uiTree(),
            statusContains("0.5.7", 20_000)
        )

        // ---- 5. Installed models come from GET /api/tags -------------------
        assertTrue(
            "The installed model llama3.2:1b must be listed; UI:\n" + uiTree(),
            hasTextWithScroll("llama3.2:1b", attempts = 16)
        )

        // ---- 6. Pull a catalog preset to the Ollama SERVER -----------------
        // NOT the Install button: this test reaches LocalAiActivity through the
        // real "Local AI (Ollama)" row, which carries no registry_url override,
        // so Install would resolve against the REAL registry.ollama.ai and
        // start a multi-hundred-MB download inside CI. "Pull to Ollama server
        // instead" is the path this fake daemon can honestly serve, and it
        // proves the same wiring: button → POST /api/pull → a live Downloads
        // row. (The registry Install path is covered end-to-end by the second
        // test, which launches the activity directly and CAN redirect it.)
        val preferredDesc = "localai_pull_server_qwen2.5:0.5b"
        val pullDesc = if (hasDescContainsWithScroll(preferredDesc, attempts = 20)) {
            preferredDesc
        } else {
            findFirstPullServerButton()
                ?: throw AssertionError(
                    "No 'Pull to Ollama server instead' button found; UI:\n" + uiTree()
                )
        }
        val pulledTag = pullDesc.removePrefix("localai_pull_server_")

        // The tap is VERIFICATION-DRIVEN: CI emulators can drop an injected
        // tap on this busy screen (13 preset cards + chips recomposing while
        // the a11y tree is polled — observed in CI: the tap landed dead-on
        // the button and the download coroutine never started). So each round
        // clicks, then waits for PROOF that the pull started. The verdict is
        // DURABLE — the fake RECEIVING POST /api/pull with this tag — because
        // two earlier versions of this check could not see a tap that had
        // worked:
        //   * it looked for the Downloads row WITHOUT scrolling — that row
        //     renders just under "Installed models", near the top of the
        //     screen, while every pull button is deep inside the catalog, so
        //     it was off-screen by construction;
        //   * it clicked through UiAutomator gesture injection, the very
        //     channel this suite documents as PHANTOM-dropped on this busy
        //     screen (see scrollAndShellTap). It now uses the shell `input tap`
        //     channel that is proven to land here.
        var pullStarted = false
        for (round in 1..3) {
            // Round 1 starts where the search above left the viewport (on the
            // button). A failed round's sweep has moved on past it, so later
            // rounds climb back to the top before searching down again.
            if (round > 1) scrollToTop()
            scrollAndShellTap(pullDesc, "Pull to Ollama server instead", attempts = 14)
            if (waitUntil(8_000) { fake.pullTags().contains(pulledTag) }) {
                pullStarted = true
                break
            }
        }
        assertTrue(
            "Tapping 'Pull to Ollama server instead' for $pulledTag must POST " +
                "/api/pull with that tag (fake saw ${fake.pullTags()}); UI:\n" + uiTree(),
            pullStarted
        )
        // The user-visible half of the same fact, checked ONCE rather than per
        // round: the Downloads row exists. It lives above the catalog, so this
        // probe has to climb back up to see it, and the row is KEPT once the
        // pull succeeds (that is what its Clear button clears) — which is what
        // makes the check deterministic even though the fake answers instantly.
        assertTrue(
            "A server pull must render a Downloads row for $pulledTag; UI:\n" + uiTree(),
            serverPullEvidence(pulledTag, 12_000)
        )

        // The fake daemon lists every pulled model in /api/tags from the moment
        // its pull request arrives, so the server-side model list picks it up
        // on the next refresh (which a finished pull triggers itself).
        assertTrue(
            "The pulled model $pulledTag must appear in the server's installed list; UI:\n" + uiTree(),
            hasTextWithScroll(pulledTag, attempts = 16)
        )

        // Pause/resume buttons are intentionally NOT asserted here — see the
        // class KDoc: instant MockWebServer bodies make the DOWNLOADING phase
        // too short to observe deterministically (unit-tested instead). The
        // assertion above is weaker and that is deliberate: it asks only that
        // the row EXISTS, which the kept SUCCESS row satisfies whatever the
        // phase did in between.
    }

    // =====================================================================
    // Catalog refresh — "Find new models" (live library discovery)
    // =====================================================================

    @Test
    fun local_ai_catalog_refresh_discovers_and_installs_new_models() {
        // ---- 1. LocalAiActivity directly, library AND registry on the fake
        device.pressHome()
        val fakeBase = server.url("/").toString().trimEnd('/')
        launchLocalAiDirectly(libraryUrl = fakeBase, registryUrl = fakeBase)
        assertTrue(
            "LocalAiActivity must open (host field visible); UI:\n" + uiTree(),
            hasDesc("localai_host_field", 15_000)
        )

        // ---- 2. Point the OLLAMA host at the same fake server ------------
        // (persists the tuning host; pulls will ride on this dispatcher)
        assertTrue(
            "Host field must be clearable (persisted from the earlier test or default)",
            clearField("localai_host_field")
        )
        val host = server.url("/").toString().trimEnd('/')
        assertTrue("Host field must be typeable", typeIntoField("localai_host_field", host))
        hideImeIfNeeded()
        assertTrue("Connect button must be clickable", clickDesc("localai_connect", 8_000))
        assertTrue(
            "Connection status must show Ollama 0.5.7; UI:\n" + uiTree(),
            statusContains("0.5.7", 20_000)
        )

        // ---- 3. Find new models → GET /library?sort=newest ----------------
        // DUAL-PATH verification-driven tap. The desc path mirrors every other
        // working button in this suite; the TEXT path ("Find new models") is an
        // independent route to the same button that survives any semantics
        // anomaly. After the click the viewport is still AT the row — the
        // catalog caption there separates the failure theories in the message:
        //   Idle → tap dead; Loading → hang; Failed/Ready with 0 mock hits →
        //   library_url extra lost (fetch went to the real ollama.com).
        var qwenFound = false
        var sawLibraryRequest = false
        var buttonNeverFound = false
        var catalogDump = ""
        var lastCaption = "?"
        for (round in 1..4) {
            hideImeIfNeeded()
            scrollToTop()
            // Task 13 added an "On-device engine" section between Connection
            // and Installed models (~1.5 extra screens) — every scroll to the
            // catalog now needs proportionally more quarter-drags.
            val clicked = scrollAndShellTap("localai_refresh_catalog", "Find new models", attempts = 20)
            if (!clicked) {
                buttonNeverFound = true
                catalogDump = dumpCatalogTop()
                break
            }
            // In-place capture: the caption is beside the button right now.
            val deadline = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < deadline) {
                lastCaption = catalogCaptionInPlace()
                if (fake.libraryHits.get() > 0) break
                if (lastCaption == "Failed" || lastCaption == "Ready") break
                try { Thread.sleep(300) } catch (_: InterruptedException) { }
            }
            if (fake.libraryHits.get() > 0) sawLibraryRequest = true
            // Proof tier 2: the discovered card (scrolls away from the button).
            if (hasTextWithScroll("qwen3.5", attempts = 16)) {
                qwenFound = true
                break
            }
        }
        assertTrue(
            "The refresh button must be findable/clickable by desc OR by its " +
                "'Find new models' text; at the catalog top: $catalogDump; " + screenSummary(),
            !buttonNeverFound
        )
        assertTrue(
            "The refresh tap must reach the fake /library within 4 rounds " +
                "(caption after tap: $lastCaption — Idle=tap dead, Loading=hang, " +
                "Failed/Ready with 0 hits=extra lost, fetch went to real ollama.com); " +
                screenSummary(),
            sawLibraryRequest
        )
        assertTrue(
            "Discovered family qwen3.5 must be listed (caption after tap: $lastCaption); " +
                screenSummary(),
            qwenFound
        )

        // ---- 4. Embedding-only families stay hidden ------------------------
        // (bge-m3 exists in the fake library but cannot chat — never listed.)
        assertTrue(
            "Embedding-only family bge-m3 must NOT be listed; UI:\n" + uiTree(),
            !hasText("bge-m3", 2_000)
        )

        // ---- 5. Install the discovered family's phone-friendly tag ---------
        // Install now resolves the tag on the REGISTRY and downloads the real
        // blob onto the BUILT-IN engine (no Ollama daemon involved), so the
        // honest proof is the Installed chip flipping — which only happens
        // once the bytes are on disk and the store has been re-listed.
        // VERIFICATION-DRIVEN like every other tap here: retry if it was lost.
        val tag = "qwen3.5:0.8b"
        val installDesc = "localai_install_$tag"
        var installed = false
        // Whether the Install button was ever FOUND — the counter that
        // separates "the card is not there" from "the tap was lost".
        var installSeen = false
        for (round in 1..3) {
            // A failed round sweeps 12 drags down the screen, which leaves the
            // card far ABOVE the viewport — and none of the scroll-aware
            // helpers here can search upward, so without this reset rounds 2
            // and 3 would silently tap nothing (CI: manifests stayed at 1
            // through all three rounds).
            if (round > 1) scrollToTop()
            if (clickDescContainsWithScroll(installDesc, attempts = 12)) installSeen = true
            // In place FIRST: the chip replaces the Install button inside the
            // card that is already on screen, and the download can finish
            // before the first sweep's drag — so sweep only as a fallback.
            if (hasDescContains("localai_installed_$tag", 5_000)) {
                installed = true
                break
            }
            if (hasDescContainsWithScroll("localai_installed_$tag", attempts = 12)) {
                installed = true
                break
            }
        }
        // The counters separate the failure theories in the message: 0
        // manifests = the tap never reached the registry (dead tap / resolve
        // never ran); manifests > 0 with 0 blobs = the resolve worked but the
        // blob URL did not come back.
        assertTrue(
            "Install must resolve $tag on the fake registry " +
                "(button seen=$installSeen, manifests=${fake.manifestHits.get()}) and download " +
                "its blob (blobs=${fake.blobHits.get()}) onto the on-device engine, flipping the " +
                "card to the Installed chip; UI:\n" + uiTree(),
            installed
        )
        assertTrue(
            "The registry manifest for $tag must have been fetched; " + screenSummary(),
            fake.manifestHits.get() > 0
        )
        assertTrue(
            "The registry blob for $tag must have been downloaded; " + screenSummary(),
            fake.blobHits.get() > 0
        )
    }
}
