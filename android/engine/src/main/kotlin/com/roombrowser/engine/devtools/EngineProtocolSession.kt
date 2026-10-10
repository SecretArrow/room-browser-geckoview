package com.roombrowser.engine.devtools

import kotlinx.coroutines.flow.Flow

/**
 * A raw engine debugging-protocol session.
 *
 * Payloads are JSON strings rather than parsed types on purpose: the protocol
 * is the engine's own, and naming its types here would put engine classes back
 * on the app's compile classpath, which is the one thing this module exists to
 * prevent.
 *
 * Only the WebView edition has a candidate mechanism for this (Chromium's
 * DevTools protocol over an abstract-namespace socket), and only once a probe
 * has proved it reachable in-process. An engine that cannot offer one returns
 * null from `EngineInspector.openProtocolSession`.
 */
interface EngineProtocolSession {

    /** Send one request and await its reply, both as JSON text. */
    suspend fun send(method: String, paramsJson: String): String

    /** Protocol notifications, unframed. */
    val events: Flow<String>

    /** Release the transport. Idempotent. */
    fun close()
}
