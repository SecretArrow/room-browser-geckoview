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

    /**
     * Response headers are observable. A status read on its own is not this
     * capability: the network feed reports a status wherever the engine gives
     * one, and this stays absent until the headers themselves are readable.
     */
    NETWORK_RESPONSE_HEADERS,

    /**
     * Cookies for an origin are readable at all -- their names and values.
     *
     * Deliberately separate from [COOKIE_ATTRIBUTES]: an engine can list cookies
     * without being able to say whether they are HttpOnly, and blurring the two
     * would either hide a list this build really has or claim flags it never
     * read. WHICH cookies a list holds is the engine's business, though, and the
     * difference is real: an edition whose only store is the page's own cannot
     * see a cookie the server marked HttpOnly, and it says so in its notes.
     */
    COOKIES,

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

/** The Developer Tools panels. Declaration order is not display order; the app's registry decides that. */
enum class DevToolsPanelId(val title: String) {
    /**
     * This module's own summary of the document and of what the edition can
     * inspect -- not one of Chrome's panels, and the only entry here that
     * exists because the browser is its own product rather than a port of one.
     * It requires nothing, so it is always present.
     */
    OVERVIEW("Overview"),
    ELEMENTS("Elements"),
    CONSOLE("Console"),
    SOURCES("Sources"),
    NETWORK("Network"),
    PERFORMANCE("Performance"),
    MEMORY("Memory"),
    APPLICATION("Application"),
    SECURITY("Security"),
    AUDIT("Audit"),
    RECORDER("Recorder"),

    /**
     * The find-anything panel: the page's own globals, storage keys, cookie
     * names and element text, searched from one query field. It is served by
     * the same scripting substrate as the other probe panels, so it needs no
     * capability of its own and both editions have it.
     */
    SEARCH("Search")
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

    /**
     * Whether this edition can serve panel [id] at all.
     *
     * THE ANSWER IS "HIDE IT", NOT "SHOW IT EMPTY", so this is the whole
     * mechanism behind the promise that a panel an engine cannot back is absent
     * rather than broken-looking. It lives here, on the capability layer, rather
     * than beside the panels: it is a statement about the engine, it must be
     * testable without Compose, and it is the thing a future panel has to be
     * added to.
     *
     * Two entries are satisfied by EITHER of a pair -- a Console is served by a
     * page-side patch or by the engine's own messages, and an engine with one of
     * them has a Console -- which is why this is a predicate rather than a set of
     * required capabilities.
     */
    fun serves(id: DevToolsPanelId): Boolean = when (id) {
        // Requires nothing: it reports the page and this very capability set.
        DevToolsPanelId.OVERVIEW -> true
        DevToolsPanelId.CONSOLE -> has(DevToolsCapability.CONSOLE_CAPTURE) ||
            has(DevToolsCapability.ENGINE_CONSOLE)
        DevToolsPanelId.NETWORK -> has(DevToolsCapability.NETWORK_REQUEST_LINE)
        DevToolsPanelId.APPLICATION -> has(DevToolsCapability.PAGE_SCRIPTING)
        DevToolsPanelId.SEARCH -> has(DevToolsCapability.PAGE_SCRIPTING)
        // Present but not yet built. Each is served by capabilities this layer
        // already tracks, so the day its panel lands it appears by itself.
        DevToolsPanelId.ELEMENTS -> has(DevToolsCapability.PAGE_SCRIPTING)
        DevToolsPanelId.SECURITY -> has(DevToolsCapability.SECURITY_INFO)
        DevToolsPanelId.AUDIT -> has(DevToolsCapability.PAGE_SCRIPTING)
        DevToolsPanelId.RECORDER -> has(DevToolsCapability.PAGE_SCRIPTING)
        DevToolsPanelId.SOURCES -> has(DevToolsCapability.JS_DEBUGGER)
        DevToolsPanelId.PERFORMANCE -> has(DevToolsCapability.PAGE_SCRIPTING)
        DevToolsPanelId.MEMORY -> has(DevToolsCapability.CPU_PROFILER)
    }

    /**
     * Every panel id this edition's engine could serve, whether or not its panel
     * has been built yet.
     *
     * It is not the strip's contents -- the app's registry decides what is
     * actually shown -- but it is the answer to "what would this engine allow if
     * we built it", which is what a diagnostic report needs.
     */
    fun servedPanels(): List<DevToolsPanelId> = DevToolsPanelId.entries.filter { serves(it) }

    companion object {
        val NONE = DeveloperToolsCapabilities(capabilities = emptySet())
    }
}
