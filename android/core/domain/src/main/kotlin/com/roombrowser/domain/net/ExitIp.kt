package com.roombrowser.domain.net

/**
 * The endpoints that echo the caller's own address, in the order they are tried.
 *
 * One list on purpose. Several places ask the same question — "what address
 * does the internet see me from?" — the profile network check, the network
 * warning's refresh and the diagnostics screen. A second copy of the list is how
 * those answers start disagreeing.
 *
 * All three are HTTPS: the address is the answer, and a plain-HTTP endpoint
 * hands it to every middlebox on the way.
 */
object ExitIp {

    val ENDPOINTS = listOf(
        "https://api.ipify.org",
        "https://icanhazip.com",
        "https://checkip.amazonaws.com"
    )

    /**
     * The same question, asked over IPv6 only.
     *
     * [ENDPOINTS] answers with the IPv4 address on any dual-stack network, which is
     * the address a site sees only when the connection actually went out over IPv4.
     * A separate list is what lets the diagnostics screen report the other half
     * honestly — and report its ABSENCE, which is a fact about the network and not a
     * failure of the probe.
     */
    val IPV6_ENDPOINTS = listOf("https://api6.ipify.org")
}

/**
 * Whether a probe's answer is an address at all.
 *
 * A probe reads whatever the far end returned, and a captive portal or an error
 * page answers 200 with prose. Without this check that prose would be shown as
 * "the address the internet sees you from", which is the one thing this screen
 * must not do.
 *
 * It belongs beside the endpoints it validates: a rule about what one of those
 * endpoints can answer has no meaning anywhere else.
 */
object IpAddress {

    private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
    private val IPV6 = Regex("""[0-9a-fA-F:]{2,39}""")

    fun looksLike(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return false
        IPV4.matchEntire(text)?.let { match ->
            return match.groupValues.drop(1).all { it.toIntOrNull() in 0..255 }
        }
        return text.contains(':') && IPV6.matches(text)
    }
}
