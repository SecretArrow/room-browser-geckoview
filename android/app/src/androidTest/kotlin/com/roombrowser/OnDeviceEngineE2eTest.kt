package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * E2E for the EMBEDDED on-device llama.cpp engine (protocol "LOCAL") — the
 * real app UI, a REAL model file, NO MockWebServer:
 *
 *   LocalAiActivity (launched DIRECTLY, default process — same pattern as
 *   LocalAiE2eTest; package name resolved from the instrumentation target
 *   context because debug builds carry an applicationId suffix)
 *     -> "On-device engine" section header (below Connection)
 *     -> engine version line (desc localengine_version) is non-blank
 *     -> the seeded smoke model appears (desc localengine_use_smoke-story-260k)
 *     -> "Try" runs the WHOLE native stack on the emulator: JNI load of the
 *        260K-parameter stories model + a 24-token completion of "Once upon
 *        a time" — the result dialog (desc localengine_try_output) must show
 *        at least 4 characters and must NOT be a load failure ("Could not…")
 *     -> the import / download affordances exist (localengine_import,
 *        localengine_url_field, localengine_download)
 *
 * The model is seeded BEFORE the activity opens: the GGUF smoke asset
 * (ggml-org tinyllamas stories260K, ~1.13 MB, GGUF v3, base model without a
 * chat template) is copied from the test APK's assets into the app's
 * noBackupFilesDir/on_device_models directory — exactly where
 * OnDeviceModelStore and the LocalLlamaGateway routing look for it.
 *
 * Generation is SLOW on the x86_64 emulator (a 260K model still goes through
 * the full JNI → ggml → token loop), so the dialog wait polls for up to
 * 150 s with UiDevice.waitForIdle between rounds.
 *
 * Compose nodes are driven exactly like LocalAiE2eTest: semantics
 * content-description nodes + scroll-aware helpers with small deterministic
 * drags (off-screen rows are not exposed to the accessibility tree).
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceEngineE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    // =====================================================================
    // Model seeding — before the activity ever opens
    // =====================================================================

    /**
     * Copies the smoke GGUF from the test APK assets into the app-private
     * on-device models directory (noBackupFilesDir/on_device_models), the
     * same location OnDeviceModelStore manages. Overwrites any stale copy so
     * the test is deterministic across repeated runs.
     */
    private fun seedSmokeModel(): File {
        val modelsDir = File(targetContext.noBackupFilesDir, "on_device_models").apply { mkdirs() }
        val target = File(modelsDir, "smoke-story-260k.gguf")
        instrumentation.context.assets.open("local_ai/smoke-story-260k.gguf").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        assertTrue(
            "the smoke model must be seeded (${target.length()} bytes at ${target.path})",
            target.length() > 1_000_000L
        )
        return target
    }

    // =====================================================================
    // Launch helper
    // =====================================================================

    /**
     * Launches LocalAiActivity directly (default process, own window — no
     * browser round-trip, no fake server needed: the on-device engine is
     * local by definition). The activity is not exported, so the primary
     * path is the app's own context; the `am start` fallback uses the REAL
     * application id resolved from the instrumentation target context.
     */
    private fun launchLocalAiDirectly() {
        runCatching {
            val intent = Intent()
                .setClassName(targetContext, "com.roombrowser.agent.ui.LocalAiActivity")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            targetContext.startActivity(intent)
        }
        if (!hasDesc("localai_host_field", 4_000)) {
            device.executeShellCommand(
                "am start -f 0x10008000 -n ${targetContext.packageName}/com.roombrowser.agent.ui.LocalAiActivity"
            )
            device.waitForIdle(2_000)
        }
    }

    // =====================================================================
    // UiAutomator helpers (proven patterns from LocalAiE2eTest)
    // =====================================================================

    private fun hasText(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs)

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

    private fun hasDescContains(part: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.descContains(part)), timeoutMs)

    private fun hasTextContains(part: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.textContains(part)), timeoutMs)

    /** Scroll-aware text-contains wait: off-screen rows are NOT exposed to
     *  the a11y tree — small deterministic drags between polls. */
    private fun hasTextContainsWithScroll(part: String, attempts: Int = 10): Boolean {
        for (i in 1..attempts) {
            if (hasTextContains(part, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    /** Scroll-aware desc wait — same reason as [hasTextContainsWithScroll]. */
    private fun hasDescContainsWithScroll(part: String, attempts: Int = 12): Boolean {
        for (i in 1..attempts) {
            if (hasDescContains(part, 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    /** Scroll-aware EXACT-desc click (the Try button of a model row). */
    private fun clickDescWithScroll(desc: String, attempts: Int = 12): Boolean {
        for (i in 1..attempts) {
            if (clickDesc(desc, 1_500)) return true
            dragUpQuarter()
        }
        return false
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

    /** SLOW drag (100 steps ≈ no fling momentum) that scrolls ~1/4 of the
     *  screen — deterministic. */
    private fun dragUpQuarter() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 5 / 8,
            device.displayWidth / 2, device.displayHeight * 3 / 8, 100
        )
        device.waitForIdle(600)
    }

    /** Reverse of [dragUpQuarter] — scrolls the viewport toward the START. */
    private fun dragDownQuarter() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 8,
            device.displayWidth / 2, device.displayHeight * 5 / 8, 100
        )
        device.waitForIdle(600)
    }

    /** Reset to the top of the scrollable screen (see LocalAiE2eTest). */
    private fun scrollToTop() {
        repeat(18) { dragDownQuarter() }
    }

    /**
     * Reads the version line IN PLACE: the node with desc
     * "localengine_version" (its accessibility text is "llama.cpp <build>"
     * or the honest unavailable message). Returns null when not visible.
     */
    private fun engineVersionText(): String? = try {
        device.findObject(By.desc("localengine_version"))?.let { node ->
            runCatching { node.text }.getOrNull()
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Waits for the Try-result dialog and returns its text. Polls for up to
     * [timeoutMs] — loading + generating 24 tokens on the emulator takes
     * real time; UiDevice.waitForIdle between rounds keeps the a11y tree
     * queries cheap.
     */
    private fun waitForTryOutput(timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val node = runCatching { device.findObject(By.desc("localengine_try_output")) }.getOrNull()
            if (node != null) {
                val text = runCatching { node.text }.getOrNull()
                if (!text.isNullOrBlank()) return text
            }
            runCatching { device.waitForIdle(1_000) }
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                }
        }
        return null
    }

    /** Probes the on-device-engine nodes for readable failure messages. */
    private fun uiTree(): String = try {
        val sb = StringBuilder()
        for (probe in listOf(
            "section header" to By.textContains("On-device engine"),
            "localengine_version" to By.desc("localengine_version"),
            "localengine_models_list" to By.desc("localengine_models_list"),
            "localengine_use_smoke-story-260k" to By.desc("localengine_use_smoke-story-260k"),
            "localengine_try_smoke-story-260k" to By.desc("localengine_try_smoke-story-260k"),
            "localengine_try_output" to By.desc("localengine_try_output"),
            "localengine_import" to By.desc("localengine_import"),
            "localengine_url_field" to By.desc("localengine_url_field"),
            "localengine_download" to By.desc("localengine_download")
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
    fun on_device_engine_smoke() {
        // ---- 1. Seed the model BEFORE the activity opens ------------------
        seedSmokeModel()

        // ---- 2. Open LocalAiActivity directly -----------------------------
        device.pressHome()
        launchLocalAiDirectly()
        assertTrue(
            "LocalAiActivity must open (host field visible); UI:\n" + uiTree(),
            hasDesc("localai_host_field", 15_000)
        )

        // ---- 3. The On-device engine section is right below Connection ----
        scrollToTop()
        assertTrue(
            "The 'On-device engine' section header must appear; UI:\n" + uiTree(),
            hasTextContainsWithScroll("On-device engine", attempts = 6)
        )

        // ---- 4. Engine version line: non-blank (either the llama.cpp build
        //         string or the honest unavailable message) ------------------
        var version: String? = null
        for (i in 1..8) {
            version = engineVersionText()
            if (!version.isNullOrBlank()) break
            dragUpQuarter()
        }
        assertTrue(
            "The engine version line (desc localengine_version) must show non-blank text; UI:\n" + uiTree(),
            !version.isNullOrBlank()
        )

        // ---- 5. The seeded smoke model is listed ---------------------------
        assertTrue(
            "The seeded model smoke-story-260k must be listed; UI:\n" + uiTree(),
            hasDescContainsWithScroll("localengine_use_smoke-story-260k", attempts = 12) ||
                hasTextContainsWithScroll("smoke-story-260k", attempts = 8)
        )

        // ---- 6. Try: the WHOLE native stack (load + 24-token generation) --
        assertTrue(
            "The Try button for smoke-story-260k must be clickable; UI:\n" + uiTree(),
            clickDescWithScroll("localengine_try_smoke-story-260k", attempts = 12)
        )
        val output = waitForTryOutput(150_000)
        val dialogText = output.orEmpty()
        assertTrue(
            "The model test dialog (desc localengine_try_output) must appear with at least 4 " +
                "characters of text within 150 s; UI:\n" + uiTree(),
            dialogText.length >= 4
        )
        assertTrue(
            "The model test must actually GENERATE — load failures are not acceptable " +
                "on the x86_64 emulator. Dialog said: '$dialogText'",
            !dialogText.startsWith("Could not")
        )

        // ---- 7. Dismiss the dialog ----------------------------------------
        if (!clickTextNode("Close")) {
            device.pressBack()
            device.waitForIdle(800)
        }

        // ---- 8. Import + download affordances exist ------------------------
        assertTrue(
            "The Import .gguf button must exist; UI:\n" + uiTree(),
            hasDescContainsWithScroll("localengine_import", attempts = 12)
        )
        assertTrue(
            "The download URL field must exist; UI:\n" + uiTree(),
            hasDescContainsWithScroll("localengine_url_field", attempts = 12)
        )
        assertTrue(
            "The Download button must exist; UI:\n" + uiTree(),
            hasDescContainsWithScroll("localengine_download", attempts = 12)
        )
    }

    /** Text-node click used for the dialog's Close button. */
    private fun clickTextNode(text: String): Boolean {
        val node = runCatching { device.findObject(By.text(text)) }.getOrNull() ?: return false
        return clickSmart(node)
    }
}
