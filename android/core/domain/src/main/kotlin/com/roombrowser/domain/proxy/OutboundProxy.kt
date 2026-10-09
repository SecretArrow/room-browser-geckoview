package com.roombrowser.domain.proxy

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * The proxy the app's OWN HTTP clients should use, per scope.
 *
 * Separate from the engine's page proxy because it is a different mechanism with a
 * different blast radius: the engine's is one process-wide call, while these are the
 * OkHttp clients the agent and the wallet build. Process-wide for the same reason the
 * engine's is — this app binds ONE profile per process, and the proxy is that profile's.
 *
 * Read through [selector] and never through a snapshot: OkHttp consults a ProxySelector on
 * every connection, so an entry installed at a bind applies to the very next request
 * without anything having to rebuild a client that was constructed before it.
 */
object OutboundProxy {

    @Volatile
    private var installed: Map<ProxyScope, ProxyCandidate> = emptyMap()

    /**
     * The platform's own selector, asked whenever a scope has no proxy of ours — so a
     * client with this installed behaves exactly as it did before when the feature is off,
     * including honouring a proxy configured on the device.
     */
    private val platformDefault: ProxySelector? = ProxySelector.getDefault()

    fun install(scope: ProxyScope, candidate: ProxyCandidate?) {
        installed = if (candidate == null) installed - scope else installed + (scope to candidate)
    }

    fun clear() {
        installed = emptyMap()
    }

    fun candidate(scope: ProxyScope): ProxyCandidate? = installed[scope]

    fun selector(scope: ProxyScope): ProxySelector = ScopedProxySelector(scope)

    private class ScopedProxySelector(private val scope: ProxyScope) : ProxySelector() {

        override fun select(uri: URI): List<Proxy> {
            val candidate = installed[scope]
                ?: return platformDefault?.select(uri) ?: listOf(Proxy.NO_PROXY)
            val host = uri.host
            if (host == null || isLoopback(host)) return listOf(Proxy.NO_PROXY)
            val type = when (candidate.scheme) {
                ProxyScheme.SOCKS4, ProxyScheme.SOCKS5 -> Proxy.Type.SOCKS
                else -> Proxy.Type.HTTP
            }
            return listOf(
                Proxy(type, InetSocketAddress.createUnresolved(candidate.host, candidate.port))
            )
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }

    /**
     * Loopback never goes through a proxy, on any scope.
     *
     * The same rule the engine's override carries, for the same reason: a remote middlebox
     * cannot help with a local endpoint, and sending it there takes the app's own local RPC
     * down whenever the proxy does.
     */
    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "::1" || host == "[::1]" ||
            host.startsWith("127.") || host == "127.0.0.1"
}
