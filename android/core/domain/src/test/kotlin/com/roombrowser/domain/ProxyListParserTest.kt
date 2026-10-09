package com.roombrowser.domain.proxy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProxyListParserTest {

    @Test
    fun `plain host colon port is the common shape`() {
        // Real lines, as the verified sources publish them.
        val parsed = ProxyListParser.parse("8.211.42.167:5060\n", source = "speedx")
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].host).isEqualTo("8.211.42.167")
        assertThat(parsed[0].port).isEqualTo(5060)
        assertThat(parsed[0].scheme).isEqualTo(ProxyScheme.HTTP)
        assertThat(parsed[0].id).isEqualTo("8.211.42.167:5060")
    }

    @Test
    fun `scheme prefixed lines carry their scheme`() {
        val parsed = ProxyListParser.parse("http://45.65.137.218:999")
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].host).isEqualTo("45.65.137.218")
        assertThat(parsed[0].port).isEqualTo(999)
        assertThat(parsed[0].scheme).isEqualTo(ProxyScheme.HTTP)
        assertThat(parsed[0].endpoint).isEqualTo("http://45.65.137.218:999")
    }

    @Test
    fun `a socks prefix is recognised rather than read as a host`() {
        val parsed = ProxyListParser.parse("socks5://203.0.113.7:1080")
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].scheme).isEqualTo(ProxyScheme.SOCKS5)
        assertThat(parsed[0].host).isEqualTo("203.0.113.7")
    }

    @Test
    fun `country suffixed lines keep the country out of the port`() {
        // hideip.me publishes `host:port:Country`. A naive colon-split reads the country
        // as part of the endpoint; this is the format that must not degrade silently.
        val parsed = ProxyListParser.parse("8.219.97.248:80:Singapore")
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].host).isEqualTo("8.219.97.248")
        assertThat(parsed[0].port).isEqualTo(80)
        assertThat(parsed[0].country).isEqualTo("Singapore")
    }

    @Test
    fun `a label that is not ascii still keeps its endpoint`() {
        // hideip really publishes `Türkiye`. Validating the label against an ASCII country list
        // threw away a working endpoint, so the label is treated as free text.
        val parsed = ProxyListParser.parse("185.200.37.66:8080:Türkiye")
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].host).isEqualTo("185.200.37.66")
        assertThat(parsed[0].port).isEqualTo(8080)
        assertThat(parsed[0].country).isEqualTo("Türkiye")
        assertThat(ProxyListParser.parse("198.51.100.9:3128:DE")[0].country).isEqualTo("DE")
    }

    @Test
    fun `malformed lines are dropped rather than guessed at`() {
        // The negative controls: each of these would produce a wrong endpoint if the
        // parser accepted it, and a wrong endpoint is a silent failure to proxy.
        val text = """
            1.2.3.4
            1.2.3.4:99999
            1.2.3.4:0
            1.2.3.4:abc
            1.2.3.4:80:8080
            1.2.3.4:80:US:extra
            1.2.3.4:80:${"x".repeat(41)}
            2001:db8::1:443
            host with space:80
            :8080
            socks5://
        """.trimIndent()
        assertThat(ProxyListParser.parse(text)).isEmpty()
    }

    @Test
    fun `comments and blanks are skipped`() {
        val text = """
            # a source header
            8.211.42.167:5060

            45.65.137.218:999 # inline note
        """.trimIndent()
        val parsed = ProxyListParser.parse(text)
        assertThat(parsed.map { it.id }).containsExactly("8.211.42.167:5060", "45.65.137.218:999").inOrder()
    }

    @Test
    fun `the same endpoint from two sources is one candidate`() {
        val text = "8.211.42.167:5060\n8.211.42.167:5060\n"
        val parsed = ProxyListParser.parse(text)
        assertThat(parsed).hasSize(1)
    }

    @Test
    fun `an https listing of a known endpoint wins over the http one`() {
        // Only CONNECT can carry an https page, so the second listing is the better proxy.
        val parsed = ProxyListParser.parse("8.211.42.167:5060\nhttps://8.211.42.167:5060\n")
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].scheme).isEqualTo(ProxyScheme.HTTPS)
    }

    @Test
    fun `a trailing path is not part of the endpoint`() {
        val parsed = ProxyListParser.parse("8.211.42.167:5060/status")
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].endpoint).isEqualTo("http://8.211.42.167:5060")
    }

    @Test
    fun `order follows the source`() {
        val parsed = ProxyListParser.parse("8.211.42.167:5060\n45.65.137.218:999\n192.0.2.10:8080\n")
        assertThat(parsed.map { it.id })
            .containsExactly("8.211.42.167:5060", "45.65.137.218:999", "192.0.2.10:8080")
            .inOrder()
    }
}
