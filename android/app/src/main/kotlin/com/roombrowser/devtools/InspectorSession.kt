package com.roombrowser.devtools

import com.roombrowser.engine.EngineSession
import com.roombrowser.engine.devtools.DeveloperToolsCapabilities
import com.roombrowser.engine.devtools.EngineInspector
import kotlinx.coroutines.suspendCancellableCoroutine
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
     * Runs [script] in the page and returns its raw result, or `null` if the
     * engine never answered.
     *
     * `null` is the ONLY failure signal, deliberately: the console and network
     * panels must be able to say "the engine did not answer" rather than
     * render an empty list, and an engine that drops the call (GeckoView's
     * eval queue is bounded and drops on overflow) is exactly the case where
     * an empty list would be a lie.
     */
    suspend fun rawEval(script: String): String? =
        suspendCancellableCoroutine { continuation ->
            try {
                session.evaluateJs(script) { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resume(null)
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
    }

    /** The engine returns string results JSON-encoded — undo that, and leave a non-string result alone. */
    private fun unquote(jsResult: String): String = runCatching {
        if (jsResult.length >= 2 && jsResult.startsWith("\"") && jsResult.endsWith("\"")) {
            json.decodeFromString(String.serializer(), jsResult)
        } else {
            jsResult
        }
    }.getOrDefault(jsResult)
}
