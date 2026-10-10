package com.roombrowser.engine.devtools

/** One console entry, from the page's own patch or from the engine. */
data class EngineConsoleMessage(
    val level: String,
    val text: String,
    val source: String?,
    val line: Int?,
    val timestampMs: Long,
    val fromEngine: Boolean
)

/**
 * One network observation.
 *
 * Which fields are populated is exactly the engine's declared capability: an
 * engine that offers only [DevToolsCapability.NETWORK_REQUEST_LINE] leaves
 * [status] and [responseHeaders] null, and the panel renders those as unknown
 * rather than as a zero or an empty map.
 *
 * [documentUrl] is the document the request was made for, when the engine
 * reports it. An engine whose capture is browser-wide rather than per-session
 * must fill it in: without it a row cannot be told apart from another tab's.
 */
data class EngineNetworkSignal(
    val kind: Kind,
    val url: String,
    val method: String? = null,
    val status: Int? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val isForMainFrame: Boolean? = null,
    val resourceType: String? = null,
    val timestampMs: Long = 0L,
    val documentUrl: String? = null
) {
    /**
     * Which point of its life a signal describes.
     *
     * [COMPLETED] is emitted only by an engine that can watch a request finish;
     * a signal never invents a completion by reusing [RESPONSE].
     */
    enum class Kind { REQUEST, RESPONSE, COMPLETED, FAILED }
}

/**
 * One cookie.
 *
 * [attributesKnown] is false when the source can report only name and value --
 * WebView's `CookieManager` is that case. The panel then shows the attributes
 * as unknown; it must not turn a missing flag into `secure = false`.
 */
data class EngineCookie(
    val name: String,
    val value: String,
    val domain: String? = null,
    val path: String? = null,
    val secure: Boolean? = null,
    val httpOnly: Boolean? = null,
    val session: Boolean? = null,
    val expiresAtMs: Long? = null,
    val sameSite: String? = null,
    val attributesKnown: Boolean = false
)

/** The transport security state of the current document. */
data class EngineSecurityInfo(
    val secure: Boolean,
    val host: String? = null,
    val protocolVersion: String? = null,
    val cipherSuite: String? = null,
    val certificate: Certificate? = null,
    val mixedContent: Boolean? = null,
    val note: String? = null
) {
    /** The connection's certificate, as the engine reports it. */
    data class Certificate(
        val subject: String? = null,
        val issuer: String? = null,
        val validFromMs: Long? = null,
        val validToMs: Long? = null,
        val fingerprint: String? = null
    )
}
