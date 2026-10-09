package com.roombrowser.domain.proxy

import kotlinx.serialization.Serializable

/** A proxy's wire protocol. WebView supports the first two only; GeckoView also has SOCKS. */
@Serializable
enum class ProxyScheme(val id: String) {
    HTTP("http"),
    HTTPS("https"),
    SOCKS4("socks4"),
    SOCKS5("socks5");

    companion object {
        fun fromId(id: String): ProxyScheme? = entries.firstOrNull { it.id == id.lowercase() }
    }
}

/**
 * A profile's proxy policy. [AUTO] re-picks from the verified list on every bind,
 * [MANUAL] keeps the endpoint the user pinned even when a sweep ranks another higher.
 */
@Serializable
enum class ProxyMode { OFF, AUTO, MANUAL }

/**
 * Which of the app's traffic a proxy carries. These are genuinely separate paths —
 * [PAGES] is the engine, the other two are the app's own OkHttp clients — so a proxy
 * can be right for a page and wrong for a wallet RPC.
 */
@Serializable
enum class ProxyScope {
    PAGES,
    AGENT,
    WALLET;

    companion object {
        /**
         * Pages only. The network layer is where a proxy is most useful and least
         * dangerous (an https page through CONNECT hides its content from the operator),
         * so it is the one scope a user gets without asking; agent and wallet traffic
         * carries API keys and RPC payloads and stays direct until asked.
         */
        val DEFAULT: Set<ProxyScope> = setOf(PAGES)
    }
}

/**
 * One proxy endpoint as a source list describes it. [host] and [port] are the identity —
 * the same endpoint listed by two sources is one candidate, and [source] is only provenance.
 */
@Serializable
data class ProxyCandidate(
    val host: String,
    val port: Int,
    val scheme: ProxyScheme = ProxyScheme.HTTP,
    val country: String? = null,
    val source: String? = null
) {
    val id: String get() = "$host:$port"

    /** `scheme://host:port`, the spelling a user recognises and a "copy" action can hand out. */
    val endpoint: String get() = "${scheme.id}://$host:$port"
}

/** One entry of the bundled source catalogue. */
@Serializable
data class ProxySource(
    val id: String,
    val label: String,
    val url: String
)

@Serializable
data class ProxySourceCatalogue(val sources: List<ProxySource>)
