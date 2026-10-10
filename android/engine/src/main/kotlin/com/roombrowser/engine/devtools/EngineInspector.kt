package com.roombrowser.engine.devtools

/**
 * The inspection handle for ONE live session.
 *
 * EVERY member is defaulted, so an engine implements only what it can actually
 * serve and the app reads the gap off [capabilities] rather than off an
 * exception or a silently empty list. This is the same discipline
 * `EngineSessionListener` uses, and for the same reason: one edition of this
 * browser can do things the other cannot, and the difference has to be a
 * declared property rather than a surprise at the call site.
 *
 * The app owns the `EngineSession` this handle belongs to, so anything a page
 * script can answer is asked through that session's `evaluateJs` and does not
 * appear here. What is here is only what the ENGINE has to supply.
 *
 * THREADING. Capture sinks are invoked on the main thread. The suspending
 * members may be called from any thread.
 */
interface EngineInspector {

    val capabilities: DeveloperToolsCapabilities

    /**
     * Begin reporting console messages from the engine's own side -- the
     * messages a page-side patch cannot see, such as a CSP violation the engine
     * raised. Absent (the default) when [DevToolsCapability.ENGINE_CONSOLE] is
     * not declared.
     *
     * Replacing a previous sink is a requirement, not a convenience: the app
     * re-registers when a panel reopens.
     */
    fun startConsoleCapture(sink: (EngineConsoleMessage) -> Unit) {}

    fun stopConsoleCapture() {}

    /** Begin reporting request/response signals. Same replacement rule. */
    fun startNetworkCapture(sink: (EngineNetworkSignal) -> Unit) {}

    fun stopNetworkCapture() {}

    /**
     * The cookies the engine would send to [origin].
     *
     * An engine that can report only name and value sets
     * [EngineCookie.attributesKnown] to false rather than guessing the flags.
     */
    suspend fun cookies(origin: String): List<EngineCookie> = emptyList()

    /** Erase cookies for [origin]. Returns whether anything was removed. */
    suspend fun clearCookies(origin: String): Boolean = false

    /** The current document's transport security state, or null when unknown. */
    suspend fun securityInfo(): EngineSecurityInfo? = null

    /**
     * Open a raw debugging-protocol session, or null when this engine has none
     * or the probe that proves one did not succeed.
     */
    suspend fun openProtocolSession(): EngineProtocolSession? = null

    /** Detach every sink and release the protocol session. Idempotent. */
    fun close() {}

    companion object {
        /** A handle that declares nothing and serves nothing. */
        val NONE: EngineInspector = object : EngineInspector {
            override val capabilities = DeveloperToolsCapabilities.NONE
        }
    }
}
