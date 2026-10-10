package com.roombrowser.engine.gecko

import com.roombrowser.engine.devtools.DeveloperToolsCapabilities
import com.roombrowser.engine.devtools.DevToolsCapability
import com.roombrowser.engine.devtools.EngineConsoleMessage
import com.roombrowser.engine.devtools.EngineInspector
import com.roombrowser.engine.devtools.EngineNetworkSignal
import org.json.JSONObject

/**
 * What the GeckoView edition can serve to Developer Tools, and the per-session
 * handle that serves it.
 *
 * The declared set is the set this build ACTUALLY implements, and one source
 * feeds both the host's capability report and every session's inspector, so the
 * screen can never describe a capability the engine does not hand out.
 */
internal object GeckoDevTools {

    val CAPABILITIES: DeveloperToolsCapabilities = DeveloperToolsCapabilities(
        capabilities = setOf(
            DevToolsCapability.PAGE_SCRIPTING,
            DevToolsCapability.CONSOLE_CAPTURE,
            DevToolsCapability.NETWORK_REQUEST_LINE,
            DevToolsCapability.NETWORK_RESPONSE_HEADERS
        ),
        notes = mapOf(DevToolsCapability.ENGINE_CONSOLE to ENGINE_CONSOLE_NOTE)
    )

    /**
     * The one in-scope absence: the engine raises console messages of its own
     * (a CSP violation, an error in a frame the page patch did not reach) and
     * offers the host no way to read any of them.
     */
    const val ENGINE_CONSOLE_NOTE: String =
        "GeckoView exposes no console callback: GeckoSession has no onConsoleMessage and " +
            "there is no ConsoleDelegate, and GeckoRuntimeSettings.consoleOutput(true) only " +
            "writes engine messages to logcat under the tag GeckoConsole at a fixed level, " +
            "with no way to read them in the app."

    /** A fresh handle per session: a capture sink belongs to one session, not to the engine. */
    fun inspector(session: GeckoEngineSession): GeckoInspector = GeckoInspector(session)
}

/**
 * One session's inspection handle.
 *
 * The console feed arrives on this session's own `roombridge` port. The network
 * feed arrives on the extension-wide `roomblock` port, which has no session
 * behind it, so the host delivers it here and it is engine-wide rather than
 * per-tab -- see [GeckoEngineHost.onNetworkCaptureChanged].
 */
internal class GeckoInspector(private val session: GeckoEngineSession) : EngineInspector {

    override val capabilities: DeveloperToolsCapabilities = GeckoDevTools.CAPABILITIES

    @Volatile
    private var consoleSink: ((EngineConsoleMessage) -> Unit)? = null

    @Volatile
    private var networkSink: ((EngineNetworkSignal) -> Unit)? = null

    /** True while a panel is listening; read from the port's own thread. */
    val consoleArmed: Boolean get() = consoleSink != null

    override fun startConsoleCapture(sink: (EngineConsoleMessage) -> Unit) {
        consoleSink = sink
        session.sendConsoleControl(armed = true)
    }

    override fun stopConsoleCapture() {
        consoleSink = null
        session.sendConsoleControl(armed = false)
    }

    override fun startNetworkCapture(sink: (EngineNetworkSignal) -> Unit) {
        networkSink = sink
        session.host.onNetworkCaptureChanged(this, armed = true)
    }

    override fun stopNetworkCapture() {
        networkSink = null
        session.host.onNetworkCaptureChanged(this, armed = false)
    }

    /** A `"console"` port message from the page world, already decoded from its JSON string. */
    internal fun onConsoleEntry(entryJson: String) {
        val message = decodeConsoleEntry(entryJson) ?: return
        consoleSink?.invoke(message)
    }

    /** One network event from the extension-wide blocker port. */
    internal fun onNetworkSignal(signal: EngineNetworkSignal) {
        networkSink?.invoke(signal)
    }

    override fun close() {
        val wasArmed = networkSink != null
        consoleSink = null
        networkSink = null
        session.sendConsoleControl(armed = false)
        if (wasArmed) session.host.onNetworkCaptureChanged(this, armed = false)
    }
}

/**
 * Decode one page-world console entry.
 *
 * It crosses the world boundary as a JSON string (see main.js), so it is parsed
 * here rather than shaped in JS. `line` is 0 when the page did not know one and
 * is reported absent rather than as line 0.
 */
internal fun decodeConsoleEntry(entryJson: String): EngineConsoleMessage? {
    val entry = runCatching { JSONObject(entryJson) }.getOrNull() ?: return null
    val line = entry.optInt("line", 0)
    return EngineConsoleMessage(
        level = entry.optString("level").ifEmpty { "log" },
        text = entry.optString("text"),
        source = entry.optString("source").takeIf { it.isNotEmpty() },
        line = if (line > 0) line else null,
        timestampMs = entry.optLong("ts", 0L),
        fromEngine = false
    )
}
