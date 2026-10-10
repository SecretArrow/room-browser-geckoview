package com.roombrowser.engine.devtools

/**
 * One thing an engine may or may not be able to expose to Developer Tools.
 *
 * This set is the app's only source of truth about which panels can be shown.
 * A capability is present when the engine can actually serve it; the module
 * hides a panel whose capability is absent rather than rendering it empty, so
 * an absent capability is a deliberate answer and not a placeholder.
 */
enum class DevToolsCapability {
    /**
     * The app can run its own probe scripts in the live page and read back a
     * JSON answer. This is `EngineSession.evaluateJs`, so it is present in both
     * editions and is the substrate nearly every panel is built on.
     */
    PAGE_SCRIPTING,

    /**
     * A page-side console patch can be installed at document start and reports
     * back over the engine's own page-to-host transport.
     */
    CONSOLE_CAPTURE,

    /**
     * The engine's OWN console messages are observable, which adds entries the
     * page patch cannot see -- a CSP violation reported by the engine, a
     * message from a frame the patch did not reach.
     */
    ENGINE_CONSOLE,

    /** Requests are observable at all: method, URL, and frame. */
    NETWORK_REQUEST_LINE,

    /** Responses are observable: status and response headers. */
    NETWORK_RESPONSE_HEADERS,

    /** Cookie attributes (HttpOnly, Secure, Expires, SameSite) are readable. */
    COOKIE_ATTRIBUTES,

    /** Transport security state is readable: protocol, CSP, mixed content. */
    SECURITY_INFO,

    /** The connection's certificate is readable. */
    SECURITY_CERTIFICATE,

    /** A raw engine debugging protocol session can be opened. */
    PROTOCOL_SESSION,

    /** A CPU profile can be captured. */
    CPU_PROFILER,

    /** A JS debugger -- breakpoints and stepping -- is available. */
    JS_DEBUGGER
}

/** The Developer Tools panels, in the order the panel strip shows them. */
enum class DevToolsPanelId(val title: String) {
    ELEMENTS("Elements"),
    CONSOLE("Console"),
    SOURCES("Sources"),
    NETWORK("Network"),
    PERFORMANCE("Performance"),
    MEMORY("Memory"),
    APPLICATION("Application"),
    SECURITY("Security"),
    AUDIT("Audit"),
    RECORDER("Recorder")
}

/**
 * What this edition's engine can actually do, and the honest reason for each
 * absence.
 *
 * [notes] is keyed by a capability that is MISSING and states why this engine
 * cannot serve it, so the panel can explain a gap instead of only omitting it.
 * A capability that is merely not implemented yet has no note: "not yet" and
 * "cannot" are different answers and the screen must not blur them. A
 * capability that is present must not have a note.
 *
 * The engine's NAME is deliberately not here -- it comes from
 * `EngineHost.engineName`, so there is exactly one place that answers it.
 */
data class DeveloperToolsCapabilities(
    val capabilities: Set<DevToolsCapability>,
    val notes: Map<DevToolsCapability, String> = emptyMap()
) {

    fun has(capability: DevToolsCapability): Boolean = capability in capabilities

    fun hasAll(required: Set<DevToolsCapability>): Boolean =
        capabilities.containsAll(required)

    /** The reason [capability] is unavailable, or null when it is available. */
    fun noteFor(capability: DevToolsCapability): String? =
        if (has(capability)) null else notes[capability]

    companion object {
        val NONE = DeveloperToolsCapabilities(capabilities = emptySet())
    }
}
