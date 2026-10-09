package com.roombrowser.engine

import com.roombrowser.domain.proxy.ProxyScheme

/**
 * One proxy endpoint, in the terms the facade can carry without naming an engine type.
 * [ProxyScheme] travels through because the engines genuinely differ: WebView carries
 * HTTP and HTTPS only, Necko also carries SOCKS, and only the implementation can say so.
 */
data class EngineProxyConfig(
    val host: String,
    val port: Int,
    val scheme: ProxyScheme = ProxyScheme.HTTP
)

/**
 * Hosts that are never proxied. The `oct://` host document is served from a local origin,
 * so sending loopback through a proxy would break the one scheme reachable only here.
 */
val PROXY_BYPASS_HOSTS: List<String> = listOf("127.0.0.1", "localhost", "[::1]")
