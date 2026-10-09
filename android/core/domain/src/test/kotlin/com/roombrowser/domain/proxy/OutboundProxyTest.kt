package com.roombrowser.domain.proxy

import com.google.common.truth.Truth.assertThat
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import org.junit.After
import org.junit.Test

/**
 * The scoped OkHttp selector. What matters here is that a scope with nothing installed
 * behaves exactly as it did before this existed, that an installed entry is used verbatim,
 * and that loopback is never sent to it.
 */
class OutboundProxyTest {

    @After
    fun tearDown() {
        OutboundProxy.clear()
    }

    @Test
    fun `a scope with nothing installed is left to the platform`() {
        val uri = URI("https://example.com/rpc")
        val platform = ProxySelector.getDefault()?.select(uri) ?: listOf(Proxy.NO_PROXY)
        assertThat(OutboundProxy.selector(ProxyScope.WALLET).select(uri)).isEqualTo(platform)
    }

    @Test
    fun `an installed http entry is used for that scope only`() {
        OutboundProxy.install(ProxyScope.WALLET, ProxyCandidate(host = "203.0.113.9", port = 8080))
        val uri = URI("https://example.com/rpc")

        val wallet = OutboundProxy.selector(ProxyScope.WALLET).select(uri)
        assertThat(wallet).hasSize(1)
        assertThat(wallet.first().type()).isEqualTo(Proxy.Type.HTTP)
        assertThat(wallet.first().address().toString()).contains("203.0.113.9")

        // The platform default, not merely "something other than the wallet entry":
        // installing on one scope must leave the others exactly as they were.
        val untouched = ProxySelector.getDefault()?.select(uri) ?: listOf(Proxy.NO_PROXY)
        assertThat(OutboundProxy.selector(ProxyScope.AGENT).select(uri)).isEqualTo(untouched)
    }

    @Test
    fun `a socks entry is carried as socks`() {
        OutboundProxy.install(
            ProxyScope.AGENT,
            ProxyCandidate(host = "198.51.100.4", port = 1080, scheme = ProxyScheme.SOCKS5)
        )
        val selected = OutboundProxy.selector(ProxyScope.AGENT).select(URI("https://example.com/v1"))
        assertThat(selected.first().type()).isEqualTo(Proxy.Type.SOCKS)
    }

    @Test
    fun `loopback is never routed through an installed entry`() {
        OutboundProxy.install(ProxyScope.AGENT, ProxyCandidate(host = "203.0.113.9", port = 8080))
        val selector = OutboundProxy.selector(ProxyScope.AGENT)
        listOf("http://127.0.0.1:11434/api", "http://localhost:8080/", "http://[::1]:9000/").forEach { url ->
            assertThat(selector.select(URI(url))).contains(Proxy.NO_PROXY)
        }
    }

    @Test
    fun `clearing a scope withdraws it without touching the other`() {
        val candidate = ProxyCandidate(host = "203.0.113.9", port = 8080)
        OutboundProxy.install(ProxyScope.AGENT, candidate)
        OutboundProxy.install(ProxyScope.WALLET, candidate)
        OutboundProxy.install(ProxyScope.AGENT, null)

        assertThat(OutboundProxy.candidate(ProxyScope.AGENT)).isNull()
        assertThat(OutboundProxy.candidate(ProxyScope.WALLET)).isEqualTo(candidate)
    }
}
