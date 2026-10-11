package com.roombrowser.domain.agent

/**
 * The granular permissions an AI-agent tool can require.
 *
 * These are a different axis from the browser's per-site web permissions: they
 * say what the agent may read or do on this profile's behalf, and are stored per
 * profile in `ProfileSettings.agentWithheldPermissions`. A tool whose permission
 * the profile withholds is refused by name — the tool is never silently allowed
 * and never quietly absent from a result that reads like "there was nothing
 * there".
 *
 * The names are the wire spelling (also what a candidate patch's manifest
 * declares), so they are strings rather than an enum: a profile that stores a
 * name this build does not know simply has an inert entry, which is the safe
 * direction.
 */
object AgentPermissions {

    const val DEVTOOLS_READ = "devtools.read"
    const val DEVTOOLS_NETWORK = "devtools.network"
    const val DEVTOOLS_STORAGE = "devtools.storage"
    const val DEVTOOLS_COOKIES = "devtools.cookies"
    const val DEVTOOLS_SECURITY = "devtools.security"
    const val EXPORT_DIAGNOSTICS = "agent.exportDiagnostics"
    const val AGENT_COMPUTE = "agent.compute"

    /** Every permission this build knows, for the settings screen and the tests. */
    val ALL = setOf(
        DEVTOOLS_READ, DEVTOOLS_NETWORK, DEVTOOLS_STORAGE, DEVTOOLS_COOKIES,
        DEVTOOLS_SECURITY, EXPORT_DIAGNOSTICS, AGENT_COMPUTE
    )

    /**
     * What each tool requires. A tool absent from this map needs no permission.
     *
     * `devtools_cookies` is declared but names no tool yet: reading cookies is
     * the Application panel's job, which this change does not own. It is listed
     * so the settings screen and the stored set agree with the plan, and it is
     * the one permission that, today, cannot be exercised by a tool.
     */
    fun required(tool: String): Set<String> = when (tool) {
        AgentTools.DEVTOOLS_CONSOLE,
        AgentTools.DEVTOOLS_INSPECT,
        AgentTools.DEVTOOLS_ELEMENTS,
        AgentTools.DEVTOOLS_DIAGNOSE -> setOf(DEVTOOLS_READ)

        AgentTools.DEVTOOLS_NETWORK -> setOf(DEVTOOLS_NETWORK)

        AgentTools.DEVTOOLS_AUDIT,
        AgentTools.DEVTOOLS_STORAGE -> setOf(DEVTOOLS_STORAGE)

        AgentTools.DEVTOOLS_SECURITY -> setOf(DEVTOOLS_SECURITY)

        AgentTools.DEVTOOLS_REPORT -> setOf(EXPORT_DIAGNOSTICS)

        AgentTools.COMPUTE_JS -> setOf(AGENT_COMPUTE)

        AgentTools.DATA_TRANSFORM -> setOf(AGENT_COMPUTE)

        else -> emptySet()
    }

    /**
     * The refusal for a tool whose permission is withheld, or null when the tool
     * may run. [withheld] is `ProfileSettings.agentWithheldPermissions`.
     */
    fun refusal(tool: String, withheld: Set<String>): String? {
        val missing = required(tool).firstOrNull { it in withheld } ?: return null
        return "$tool is turned off for this profile — the \"${label(missing)}\" AI-agent permission " +
            "is not granted. Grant it in Agent settings, or use a tool that does not need it."
    }

    fun granted(tool: String, withheld: Set<String>): Boolean = required(tool).none { it in withheld }

    /** A human label for a permission, used by settings and by a refusal. */
    fun label(permission: String): String = when (permission) {
        DEVTOOLS_READ -> "Read the console and inspect elements"
        DEVTOOLS_NETWORK -> "Read the network log"
        DEVTOOLS_STORAGE -> "Read page storage and run the audit"
        DEVTOOLS_COOKIES -> "Read cookies"
        DEVTOOLS_SECURITY -> "Read the connection's security"
        EXPORT_DIAGNOSTICS -> "Export diagnostic reports"
        AGENT_COMPUTE -> "Compute in a scratchpad"
        else -> permission
    }

    /** One line explaining what a permission lets the agent do, for the settings row. */
    fun description(permission: String): String = when (permission) {
        DEVTOOLS_READ ->
            "Lets the agent read the page's console errors and inspect elements — the evidence most bugs are diagnosed from. Reading only; it changes nothing."
        DEVTOOLS_NETWORK ->
            "Lets the agent read the list of requests the page made, including their status and timing. Header values and token-bearing URLs are redacted before the agent sees them."
        DEVTOOLS_STORAGE ->
            "Lets the agent read the Application panel's view of what the page has stored — storage keys with their value lengths, IndexedDB, Cache Storage, service workers, the manifest and the usage estimate — and run the page audit. It never returns stored values in bulk."
        DEVTOOLS_COOKIES ->
            "Lets a tool read the site's cookies. Nothing in this build uses it yet; the Application panel that will is a separate change."
        DEVTOOLS_SECURITY ->
            "Lets the agent read the connection's transport state and what the page can observe about its own security — protocol, secure context, mixed content, meta-tag CSP. On an edition with no certificate API the report says so instead of leaving the section blank."
        EXPORT_DIAGNOSTICS ->
            "Lets the agent build a diagnostic report and save it for the user to share. The report is redacted, but it is still a file leaving the profile, so it is its own permission."
        AGENT_COMPUTE ->
            "Lets the agent evaluate JavaScript in a detached scratchpad page to calculate and transform data it already has. The scratchpad has no network, no filesystem and no packages, and it cannot see or change the page you are on."
        else -> permission
    }
}
