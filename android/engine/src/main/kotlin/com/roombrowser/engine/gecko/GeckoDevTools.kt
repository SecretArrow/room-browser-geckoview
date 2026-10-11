package com.roombrowser.engine.gecko

import com.roombrowser.engine.devtools.DeveloperToolsCapabilities
import com.roombrowser.engine.devtools.DevToolsCapability
import com.roombrowser.engine.devtools.EngineConsoleMessage
import com.roombrowser.engine.devtools.EngineInspector
import com.roombrowser.engine.devtools.EngineNetworkSignal
import com.roombrowser.engine.devtools.EngineSecurityInfo
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
            // GeckoSession.ProgressDelegate.onSecurityChange hands over the live
            // connection's X509Certificate, which is the whole reason the
            // Security panel is worth having on this edition.
            DevToolsCapability.SECURITY_INFO,
            DevToolsCapability.SECURITY_CERTIFICATE
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

    /**
     * What the Security panel says this engine does and does not hand over.
     *
     * Three claims, each checked against the API rather than against what would
     * look good: SecurityInformation carries the certificate, the host, the
     * origin and `isSecure`, and carries NO TLS version and NO cipher suite; and
     * its two mixed-mode fields report content the engine loaded or blocked,
     * which is not the same question as "was the page free of mixed content".
     * Saying so is what stops two "(not reported)" lines from reading as a bug.
     */
    const val SECURITY_NOTE: String =
        "GeckoView reports the connection's certificate and whether the site is secure, but " +
            "no TLS version and no cipher suite, and its mixed-content flags report only what " +
            "it loaded or blocked."

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

    /**
     * The connection's state, as the session's progress delegate last saw it.
     *
     * There is nothing to ask the engine for: GeckoView reports security only
     * through the callback and keeps no readable copy, so this reads the cache
     * that callback fills. Null is "the engine has not reported one for the
     * document on screen", which is a real state after a navigation.
     */
    override suspend fun securityInfo(): EngineSecurityInfo? = session.securityState

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
