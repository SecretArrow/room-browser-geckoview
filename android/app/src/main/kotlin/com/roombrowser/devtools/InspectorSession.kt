package com.roombrowser.devtools

import com.roombrowser.engine.EngineSession
import com.roombrowser.engine.devtools.DeveloperToolsCapabilities
import com.roombrowser.engine.devtools.EngineInspector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

/**
 * One tab's Developer Tools attachment, and the unit everything tears down.
 *
 * It owns the engine's [EngineInspector] handle for the life of one
 * [EngineSession]. It is created when a panel needs it and closed on exactly
 * four paths — the user closing the panel, the tab closing, the engine closing
 * the session underneath us, and the tab-list watcher that catches any tab id
 * that disappears without one of the first three firing. Whatever a later
 * panel subscribes to (console, network, a protocol session) is registered
 * here and cancelled by [close], so there is one teardown path and not one per
 * panel.
 *
 * The facade's inspector defaults to [EngineInspector.NONE] with every
 * capability absent, so this class is safe on an engine that has nothing to
 * serve: every call resolves to a null/empty answer rather than throwing.
 */
class InspectorSession(
    val tabId: String,
    private val session: EngineSession
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The engine's per-session handle. Never null — an engine that serves nothing returns [EngineInspector.NONE]. */
    val inspector: EngineInspector = session.inspector()

    val capabilities: DeveloperToolsCapabilities get() = inspector.capabilities

    /**
     * The two live feeds. Both are bounded here rather than in the panels, so
     * there is one cap per feed and [close] frees them on every teardown path.
     */
    val console = DeveloperToolsRing<ConsoleEntry>(CONSOLE_CAP)
    val network = DeveloperToolsRing<NetworkEntry>(NETWORK_CAP)

    /**
     * Bumped when a feed appends. The panels read a snapshot and recompose off
     * this counter instead of polling, so a panel that is not open costs
     * nothing and one that is open redraws exactly when there is something new.
     */
    var consoleRevision by mutableIntStateOf(0)
        private set
    var networkRevision by mutableIntStateOf(0)
        private set

    fun startConsole() {
        inspector.startConsoleCapture { message ->
            console.add(message.toEntry())
            consoleRevision++
        }
    }

    fun stopConsole() {
        runCatching { inspector.stopConsoleCapture() }
    }

    fun startNetwork() {
        inspector.startNetworkCapture { signal ->
            network.add(signal.toEntry())
            networkRevision++
        }
    }

    fun stopNetwork() {
        runCatching { inspector.stopNetworkCapture() }
    }

    /**
     * Empties a feed and wakes the panel.
     *
     * The clear goes through the session for the same reason the append does:
     * a panel caches its snapshot against the revision, so emptying the ring
     * from the panel would leave the rows it just removed on screen until the
     * next entry arrived -- a clear button that looks broken.
     */
    fun clearConsole() {
        console.clear()
        consoleRevision++
    }

    fun clearNetwork() {
        network.clear()
        networkRevision++
    }

    /**
     * Adds the rows a page-timing pull produced and wakes the panel.
     *
     * The revision is bumped here rather than by the panel so there is one
     * writer for the feed and the counter cannot drift from it.
     */
    fun addResourceTimings(timings: List<ResourceTiming>) {
        if (timings.isEmpty()) return
        timings.forEach { network.add(it.toEntry()) }
        networkRevision++
    }

    /**
     * The page's own view of what it loaded, for the timing and size the engine
     * does not report. Null when the page did not answer or the reply did not
     * decode — the panel says so rather than showing an empty list.
     */
    suspend fun resourceTimings(): List<ResourceTiming>? {
        val raw = rawEval(DeveloperToolsScripts.networkProbeJs()) ?: return null
        val text = unquote(raw)
        if (text.isBlank() || text == "null" || text == "undefined") return null
        return runCatching {
            json.decodeFromString(ListSerializer(ResourceTiming.serializer()), text)
        }.getOrNull()
    }

    /**
     * Runs [script] in the page and returns its raw result, or `null` if no
     * answer arrived.
     *
     * `null` is the ONLY failure signal, deliberately: the console and network
     * panels must be able to say "the engine did not answer" rather than
     * render an empty list, and an engine that drops the call (GeckoView's
     * eval queue is bounded and drops on overflow) is exactly the case where
     * an empty list would be a lie.
     *
     * Bounded by [PROBE_TIMEOUT_MS] because the engine can also simply never
     * call back — a port that never connects leaves the callback outstanding
     * forever — and a promise that never settles has to surface as something
     * other than "still reading", or the panel reports progress it is not
     * making. The callback may still fire after the timeout; the continuation
     * is no longer active, so the late value is dropped rather than delivered
     * twice.
     */
    suspend fun rawEval(script: String): String? = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            try {
                session.evaluateJs(script) { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    /** Runs the app-authored page overview probe. Null when the engine did not answer or the reply did not decode. */
    suspend fun pageOverview(): PageOverview? {
        val raw = rawEval(DeveloperToolsScripts.pageOverviewJs()) ?: return null
        val text = unquote(raw)
        if (text.isBlank() || text == "null" || text == "undefined") return null
        return runCatching { json.decodeFromString(PageOverview.serializer(), text) }.getOrNull()
    }

    fun close() {
        runCatching { inspector.close() }
        console.clear()
        network.clear()
    }

    /** The engine returns string results JSON-encoded — undo that, and leave a non-string result alone. */
    private fun unquote(jsResult: String): String = runCatching {
        if (jsResult.length >= 2 && jsResult.startsWith("\"") && jsResult.endsWith("\"")) {
            json.decodeFromString(String.serializer(), jsResult)
        } else {
            jsResult
        }
    }.getOrDefault(jsResult)

    companion object {
        /** How long one page probe may take before it is reported as unanswered. */
        const val PROBE_TIMEOUT_MS = 10_000L

        /** Entries kept per feed. Shown in the panel, so the number is a promise. */
        const val CONSOLE_CAP = 2_000
        const val NETWORK_CAP = 2_000
    }
}
