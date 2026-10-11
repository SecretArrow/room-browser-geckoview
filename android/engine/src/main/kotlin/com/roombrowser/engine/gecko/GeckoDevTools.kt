package com.roombrowser.engine.gecko

import com.roombrowser.engine.devtools.DeveloperToolsCapabilities
import com.roombrowser.engine.devtools.DevToolsCapability
import com.roombrowser.engine.devtools.EngineConsoleMessage
import com.roombrowser.engine.devtools.EngineCookie
import com.roombrowser.engine.devtools.EngineInspector
import com.roombrowser.engine.devtools.EngineNetworkSignal
import com.roombrowser.engine.devtools.EngineSecurityInfo
import com.roombrowser.engine.devtools.parseCookieHeader
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener

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
            // Read from the page, not from the engine: GeckoView hands the
            // embedder no cookie store, so this is the page's own view of its
            // cookies. Names and values only -- see [COOKIE_ATTRIBUTES_NOTE].
            DevToolsCapability.COOKIES,
            // GeckoSession.ProgressDelegate.onSecurityChange hands over the live
            // connection's X509Certificate, which is the whole reason the
            // Security panel is worth having on this edition.
            DevToolsCapability.SECURITY_INFO,
            DevToolsCapability.SECURITY_CERTIFICATE
        ),
        notes = mapOf(
            DevToolsCapability.ENGINE_CONSOLE to ENGINE_CONSOLE_NOTE,
            DevToolsCapability.COOKIE_ATTRIBUTES to COOKIE_ATTRIBUTES_NOTE
        )
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
     * The second in-scope absence, and the honest shape of what this build does
     * instead.
     *
     * The cookie LIST is served -- from the page's own `document.cookie` -- but
     * nothing here can describe a cookie. `StorageController` clears cookies and
     * reads none, and the `browser.cookies` WebExtension API that WOULD report
     * domain, expiry, SameSite and the HttpOnly/Secure flags is available to a
     * BACKGROUND script only; this build's `roombridge` port is a content
     * script's. Saying that is what keeps "the flags are unknown" from reading
     * as a bug in the panel.
     */
    const val COOKIE_ATTRIBUTES_NOTE: String =
        "This edition reads cookies from the page itself, which reports a cookie's name " +
            "and value and nothing else -- no domain, path, expiry, SameSite, HttpOnly or " +
            "Secure flag, and no cookie the page cannot see. GeckoView exposes no cookie " +
            "store to the app, and the browser.cookies extension API that would describe " +
            "one belongs to a background script, which this build's bridge is not."

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

    /**
     * The cookies the PAGE can see for this origin.
     *
     * A FALLBACK, and it is a weaker answer than the WebView edition's: this is
     * `document.cookie` evaluated in the page, not the engine's cookie store, so
     * a cookie the server marked HttpOnly never appears here and no cookie's
     * flags are readable at all. The alternative -- `browser.cookies.getAll()`
     * over the extension's port -- needs a background script, and this build's
     * `roombridge` port belongs to the content-script half of the extension, so
     * it cannot be reached from here without rebuilding that bridge. See
     * [GeckoDevTools.COOKIE_ATTRIBUTES_NOTE] for how that is stated in the panel.
     *
     * [parseCookieHeader] is shared with the WebView edition because both answer
     * in the same flat `name=value; name2=value2` shape, and an unanswered eval
     * yields an empty list: the capability is declared, so "no cookies" is the
     * honest floor rather than a blank. [origin] is not used to narrow anything --
     * the script runs in the document on screen, and that document IS the origin.
     */
    override suspend fun cookies(origin: String): List<EngineCookie> =
        parseCookieHeader(evalString("document.cookie"))

    /** One script's string result, decoded from the JSON-encoded reply, or null when nothing answered in time. */
    private suspend fun evalString(script: String): String? {
        val raw = withTimeoutOrNull(COOKIE_TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { continuation ->
                try {
                    session.evaluateJs(script) { value ->
                        if (continuation.isActive) continuation.resume(value)
                    }
                } catch (t: Throwable) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return null
        // The reply is JSON, like WebView's evaluateJavascript: a string result
        // arrives quoted and escaped, and only a string result is of use here.
        if (!text.startsWith("\"")) return text
        return runCatching { JSONTokener(text).nextValue() as? String }.getOrNull()
    }

    override fun close() {
        val wasArmed = networkSink != null
        consoleSink = null
        networkSink = null
        session.sendConsoleControl(armed = false)
        if (wasArmed) session.host.onNetworkCaptureChanged(this, armed = false)
    }

    private companion object {
        /** How long one cookie eval may take before it is reported as unanswered. */
        const val COOKIE_TIMEOUT_MS = 5_000L
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
