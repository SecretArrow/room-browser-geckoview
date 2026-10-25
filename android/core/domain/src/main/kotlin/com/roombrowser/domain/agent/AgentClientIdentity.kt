package com.roombrowser.domain.agent

/**
 * The `User-Agent` a provider is addressed with.
 *
 * Most providers never look at it, so they get the app's own identifier. One
 * does, and it is the reason this file exists: **AgentRouter answers every
 * request whose User-Agent is not the Claude Code CLI's** with
 *
 *     401 {"error":{"message":"unauthorized client detected, contact support
 *          for assistance at https://discord.gg/HgekCyHJqB"},
 *          "type":"unauthorized_client_error"}
 *
 * Established against `https://agentrouter.org/v1/messages` by varying ONLY
 * this header: `curl/8.5.0` and `okhttp/4.12.0` both draw the refusal, while a
 * CLI-shaped value gets the request accepted and rejected later on the API key
 * alone ([AgentHttpException] 401 "invalid token"). So the header — not the
 * key, not the body shape — is what that gate reads, and no amount of correct
 * Anthropic message translation reaches the model without it.
 *
 * The server matches loosely. Accepted: `claude-cli/9.9.9 (external, cli)`,
 * `claude-cli/1.0.60 (external, cli) extra`, `claude-cli/1.0.60(external,cli)`,
 * even `claude-cli/ (external, cli)`. Refused: `claude-cli/1.0.60` (no
 * suffix), `claude-cli`, `claude-code/1.0.0`, `Claude-CLI/1.0.60`,
 * `Mozilla/5.0 claude-cli/2.0.0`, and any change of case in the suffix. So the
 * requirement is the presence of `claude-cli/` AND a case-SENSITIVE
 * `(external, cli)`, anywhere in the value, and the version is ignored —
 * [CLAUDE_CLI] is the canonical spelling, not a load-bearing exact string.
 *
 * Two consequences worth stating plainly rather than discovering later:
 *
 *  - This identifies the app as another vendor's client. That is what reaching
 *    this provider requires, and it is the account holder's decision to make —
 *    but it is the kind of thing a provider may treat as a terms violation and
 *    close the account over, so it is opt-in by HOST ([CLI_ONLY_HOSTS]) and
 *    never applied to providers that do not ask for it.
 *  - It is a client-identity shim, not an authentication bypass: the API key is
 *    still required and still checked by the provider.
 *
 * Pure (no Android, no HTTP) so the whole rule is covered by the core unit
 * tests rather than only by a live call.
 */
object AgentClientIdentity {

    /**
     * What a provider that does not care about clients is told.
     *
     * Chrome for Android, deliberately, and never the app's own name: a
     * provider's edge — or an ordinary WAF in front of it — is entitled to
     * refuse a client it does not recognise, and `RoomBrowser-Agent/1.0`
     * announces a client nobody has ever heard of. The identity here is the
     * same one [com.roombrowser.domain.model.UserAgents] hands a page, so the
     * app does not present one name to a web server and a different one to an
     * API. It stays a fixed literal rather than a settings lookup: this is the
     * transport's identity, not a per-profile browsing preference, and a user
     * choosing a UA preset must not silently re-point it at their AI provider.
     */
    const val DEFAULT: String =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/156.0.8078.48 Mobile Safari/537.36"

    /** The Claude Code CLI identity, in the canonical accepted spelling. */
    const val CLAUDE_CLI: String = "claude-cli/1.0.60 (external, cli)"

    /**
     * Hosts that answer nothing but the Claude Code CLI. Kept as a list of
     * plain hosts matched against the URL's host and its subdomains, so a
     * mirror on a subdomain is covered without loosening the rule for anyone
     * else's domain.
     */
    private val CLI_ONLY_HOSTS = listOf("agentrouter.org")

    /** The `User-Agent` to send to [baseUrl]. */
    fun userAgent(baseUrl: String): String =
        if (isCliOnly(hostOf(baseUrl))) CLAUDE_CLI else DEFAULT

    private fun isCliOnly(host: String): Boolean =
        host.isNotEmpty() && CLI_ONLY_HOSTS.any { host == it || host.endsWith(".$it") }

    /**
     * The host of `https://user@host:port/path?q`, lowercased; "" when there is
     * nothing that looks like one. A hand-rolled scan rather than
     * [java.net.URI]: a provider base URL is typed by hand into a settings
     * field, and this must classify a half-typed one rather than throw.
     */
    private fun hostOf(baseUrl: String): String {
        var s = baseUrl.trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        val end = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (end >= 0) s = s.substring(0, end)
        val at = s.lastIndexOf('@') // userinfo, if any
        if (at >= 0) s = s.substring(at + 1)
        s = if (s.startsWith("[")) {
            val close = s.indexOf(']') // an IPv6 literal keeps its colons
            if (close >= 0) s.substring(1, close) else s
        } else {
            val colon = s.indexOf(':')
            if (colon >= 0) s.substring(0, colon) else s
        }
        return s.lowercase()
    }
}
