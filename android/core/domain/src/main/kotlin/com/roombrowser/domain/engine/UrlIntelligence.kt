package com.roombrowser.domain.engine

import com.roombrowser.domain.model.SearchEngines
import com.roombrowser.domain.oct.OctUri

/**
 * Omnibox URL/search intelligence: detects URLs, bare hosts, IP literals,
 * localhost, file URIs versus plain search queries, and performs
 * HTTPS upgrades.
 */
object UrlIntelligence {

    sealed interface Input {
        /** A web address the WebView can load directly. */
        data class Web(val url: String, val upgradedToHttps: Boolean) : Input
        data class Search(val query: String) : Input
        /** An Octra Circle. Not a web address: the app resolves and renders it itself. */
        data class Oct(val circle: OctUri) : Input
    }

    private val PROTOCOL = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*$")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(:\\d+)?$")
    private val IPV6 = Regex("^[0-9a-fA-F:]+:[0-9a-fA-F:.]+$")
    private val LOOKS_LIKE_DOMAIN = Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+(:\\d+)?(/.*)?$")
    private val LOCALHOST = Regex("^(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s")

    /**
     * Classify raw omnibox input.
     * Supports: full URLs, bare hosts, IPv4/IPv6 literals, localhost,
     * file:// URIs, and search queries.
     */
    fun classify(input: String, searchEngineId: String = "duckduckgo"): Pair<Input, String> {
        val raw = input.trim()
        if (raw.isEmpty()) return Input.Search("") to ""

        // Explicit file URI (WebView supports with file access enabled per setting)
        if (raw.startsWith("file://", ignoreCase = true)) {
            return Input.Web(raw, upgradedToHttps = false) to raw
        }

        // Whitespace → search
        if (WHITESPACE.containsMatchIn(raw)) {
            return Input.Search(raw) to SearchEngines.buildSearchUrl(searchEngineId, raw)
        }

        // IPv4 literal (with optional port)
        if (IPV4.matches(raw)) {
            val octets = raw.substringBefore(':').split('.')
            if (octets.all { it.toInt() in 0..255 }) {
                return Input.Web("http://$raw", upgradedToHttps = false) to "http://$raw"
            }
        }

        // IPv6 literal (with optional port)
        if (raw.contains(':') && IPV6.matches(raw)) {
            return Input.Web("http://[$raw]", upgradedToHttps = false) to "http://[$raw]"
        }

        // localhost
        if (LOCALHOST.matches(raw)) {
            return Input.Web("http://$raw", upgradedToHttps = false) to "http://$raw"
        }

        // Explicit scheme
        if (PROTOCOL.matches(raw)) {
            val lowered = raw.lowercase()
            if (lowered.startsWith("http://")) {
                // An explicitly typed http:// URL is EXPLICIT USER INTENT —
                // load it as-is (http-only sites must stay reachable).
                // Automatic upgrades happen only for link navigations inside
                // pages, and those carry an automatic http fallback.
                return Input.Web(raw, upgradedToHttps = false) to raw
            }
            if (lowered.startsWith("https://")) {
                return Input.Web(raw, upgradedToHttps = false) to raw
            }
            // `oct://` before the search fallback: a circle is a page this app fetches
            // and renders itself. A malformed one is claimed by nobody and stays a search.
            if (lowered.startsWith(OctUri.PREFIX)) {
                OctUri.parse(raw)?.let { return Input.Oct(it) to it.raw }
            }
            // about:, data:, blob:, javascript: treated as search to avoid surprises
            return Input.Search(raw) to SearchEngines.buildSearchUrl(searchEngineId, raw)
        }

        // Bare domain
        if (LOOKS_LIKE_DOMAIN.matches(raw) && raw.contains('.')) {
            return upgrade("https://$raw") to "https://$raw"
        }

        // Everything else is a search
        return Input.Search(raw) to SearchEngines.buildSearchUrl(searchEngineId, raw)
    }

    /** Upgrade an http URL to https when requested.
     *
     *  Explicit NON-DEFAULT ports are left untouched (compatibility-first,
     *  the same philosophy as the off-by-default shields): a port was spelled
     *  out precisely because the endpoint is non-standard — localhost dev
     *  servers, router/IoT admin panels, local tooling — and TLS on such
     *  ports is rare. Upgrading them in place (port kept) has been proven to
     *  dead-end: the TLS attempt against a plaintext endpoint can hang for
     *  a very long time on older WebView stacks with NO error callback, so
     *  the page silently never loads (CI-proven by BrowserNavigationE2eTest
     *  against a plain-http MockWebServer on a WebView 83 emulator).
     *  Default-port URLs (no port, or :80) upgrade to clean https. */
    fun upgrade(url: String): Input.Web {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true)) {
            return Input.Web(trimmed, upgradedToHttps = false)
        }
        val rest = trimmed.substring(7)
        val slash = rest.indexOf('/')
        val authority = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else ""
        val colon = authority.lastIndexOf(':')
        val port = if (colon >= 0) authority.substring(colon + 1) else ""
        if (port.isNotEmpty() && port != "80") {
            return Input.Web(trimmed, upgradedToHttps = false)
        }
        val host = if (colon >= 0 && port == "80") authority.substring(0, colon) else authority
        return Input.Web("https://$host$path", upgradedToHttps = true)
    }

    /** Extract a registered-ish host from a URL (null when unparseable). */
    fun hostOf(url: String): String? = runCatching {
        val uri = java.net.URI(url.trim())
        uri.host?.lowercase() ?: uri.authority?.substringBefore(':')?.lowercase()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Pretty-print a URL for the omnibox (strip scheme, www prefix kept). */
    fun displayUrl(url: String): String {
        var u = url.trim()
        if (u.startsWith("https://")) u = u.removePrefix("https://")
        else if (u.startsWith("http://")) u = u.removePrefix("http://")
        return u.removeSuffix("/")
    }

    /** True when the clipboard text looks like loadable URL content. */
    fun looksLikeUrl(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || WHITESPACE.containsMatchIn(t)) return false
        return classify(t).first is Input.Web
    }
}
