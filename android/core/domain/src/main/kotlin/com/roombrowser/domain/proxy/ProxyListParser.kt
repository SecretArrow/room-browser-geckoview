package com.roombrowser.domain.proxy

/**
 * Parses the free-proxy list formats found in the wild. They are all line-oriented and
 * differ only in spelling — `host:port`, `host:port:Country` (hideip.me) and
 * `scheme://host:port` (proxifly) — so one tolerant parser is both shorter and safer than
 * one per source: a source that changes its spelling degrades to a shorter list instead of
 * a wrong one. Every line either yields a candidate or is dropped; nothing is guessed.
 */
object ProxyListParser {

    private val SCHEMES = listOf(
        "socks5://" to ProxyScheme.SOCKS5,
        "socks4://" to ProxyScheme.SOCKS4,
        "socks://" to ProxyScheme.SOCKS5,
        "https://" to ProxyScheme.HTTPS,
        "http://" to ProxyScheme.HTTP
    )

    private val HOSTNAME = Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$")

    /** A label is free text; only its length is worth bounding (`Türkiye`, `Singapore`, `US`). */
    private const val MAX_LABEL = 40

    /** Parse a whole list, de-duplicated by endpoint and ordered as the source listed it. */
    fun parse(text: String, source: String? = null): List<ProxyCandidate> =
        merge(listOf(text.lineSequence().mapNotNull { parseLine(it, source) }.toList()))

    /**
     * Several already-parsed lists as one, under the same rule [parse] uses within a list.
     * The rule lives here rather than at each call site so a bundled list and a live one
     * cannot disagree about which of two listings of one endpoint wins.
     */
    fun merge(lists: List<List<ProxyCandidate>>): List<ProxyCandidate> {
        val byId = LinkedHashMap<String, ProxyCandidate>()
        for (candidate in lists.flatten()) {
            val previous = byId[candidate.id]
            byId[candidate.id] = when {
                previous == null -> candidate
                previous.scheme == ProxyScheme.HTTP && candidate.scheme != ProxyScheme.HTTP -> candidate
                else -> previous
            }
        }
        return byId.values.toList()
    }

    /** One line, or null when it is a comment, blank, or anything this parser will not vouch for. */
    fun parseLine(line: String, source: String? = null): ProxyCandidate? {
        // Inline comments appear in several of these lists; '#' cannot occur in a host or port.
        val head = line.substringBefore('#').trim()
        if (head.isEmpty()) return null

        var body = head
        var scheme = ProxyScheme.HTTP
        for ((prefix, parsed) in SCHEMES) {
            if (body.startsWith(prefix, ignoreCase = true)) {
                scheme = parsed
                body = body.substring(prefix.length)
                break
            }
        }
        // A trailing path or query is not part of the endpoint (some lists link to a status page).
        body = body.substringBefore('/').trim()

        val parts = body.split(':')
        if (parts.size !in 2..3) return null

        val host = parts[0].trim()
        if (host.isEmpty() || !HOSTNAME.matches(host)) return null

        val port = parts[1].trim().toIntOrNull() ?: return null
        if (port !in 1..65535) return null

        // Third token: hideip.me's country label. The discriminator is numeric vs not, because a
        // numeric third token is a malformed `host:port:port` — but a label is free text, and
        // matching it against an ASCII country list drops good endpoints (hideip lists "Türkiye"),
        // which is the silent failure to proxy this parser exists to avoid.
        val country = when (parts.size) {
            3 -> parts[2].trim().takeIf { it.isNotEmpty() && it.length <= MAX_LABEL && !it.all { c -> c.isDigit() } }
                ?: return null
            else -> null
        }
        return ProxyCandidate(host = host, port = port, scheme = scheme, country = country, source = source)
    }
}
