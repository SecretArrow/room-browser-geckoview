package com.roombrowser.domain.net

/**
 * The endpoints that echo the caller's own address, in the order they are tried.
 *
 * One list on purpose. Three places ask the same question — "what address does
 * the internet see me from?" — the profile network check, the proxy sweep that
 * verifies a candidate against it, and the network warning's refresh. A second
 * copy of the list is how those answers start disagreeing, and a proxy is only
 * verified relative to the direct address it was measured against.
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
}
