package com.roombrowser.domain.engine

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.engine.UrlIntelligence.Input
import com.roombrowser.domain.oct.OctUri
import org.junit.Test

class UrlIntelligenceTest {

    @Test
    fun `full https url is web`() {
        val (input, url) = UrlIntelligence.classify("https://example.com/path")
        assertThat(input).isEqualTo(Input.Web("https://example.com/path", upgradedToHttps = false))
        assertThat(url).isEqualTo("https://example.com/path")
    }

    @Test
    fun `explicitly typed http url stays http - user intent is respected`() {
        // Compatibility fix: http-only sites must stay reachable when the
        // user explicitly types the http:// scheme.
        val (input, url) = UrlIntelligence.classify("http://example.com")
        assertThat(input).isEqualTo(Input.Web("http://example.com", upgradedToHttps = false))
        assertThat(url).isEqualTo("http://example.com")
    }

    @Test
    fun `upgrade helper still upgrades when called explicitly`() {
        val upgraded = UrlIntelligence.upgrade("http://example.com")
        assertThat(upgraded.url).isEqualTo("https://example.com")
        assertThat(upgraded.upgradedToHttps).isTrue()
    }

    @Test
    fun `upgrade keeps explicit non-default ports on http - compatibility first`() {
        // CI-proven: upgrading http://host:port in place sends TLS at a
        // plaintext endpoint, which can hang with NO error callback on old
        // WebView stacks (BrowserNavigationE2eTest, WebView 83 emulator).
        val kept = UrlIntelligence.upgrade("http://localhost:48869/page2")
        assertThat(kept.url).isEqualTo("http://localhost:48869/page2")
        assertThat(kept.upgradedToHttps).isFalse()

        val keptNoPath = UrlIntelligence.upgrade("http://192.168.1.1:8080")
        assertThat(keptNoPath.url).isEqualTo("http://192.168.1.1:8080")
        assertThat(keptNoPath.upgradedToHttps).isFalse()
    }

    @Test
    fun `upgrade of port 80 strips the default port cleanly`() {
        val upgraded = UrlIntelligence.upgrade("http://example.com:80/path")
        assertThat(upgraded.url).isEqualTo("https://example.com/path")
        assertThat(upgraded.upgradedToHttps).isTrue()
    }

    @Test
    fun `bare host gets https scheme`() {
        val (input, url) = UrlIntelligence.classify("example.com")
        assertThat(input).isInstanceOf(Input.Web::class.java)
        assertThat(url).isEqualTo("https://example.com")
    }

    @Test
    fun `bare host with path`() {
        val (_, url) = UrlIntelligence.classify("example.com/a/b?x=1")
        assertThat(url).isEqualTo("https://example.com/a/b?x=1")
    }

    @Test
    fun `ipv4 literal becomes http url`() {
        val (_, url) = UrlIntelligence.classify("192.168.1.10:8080")
        assertThat(url).isEqualTo("http://192.168.1.10:8080")
    }

    @Test
    fun `ipv6 literal becomes bracketed http url`() {
        val (_, url) = UrlIntelligence.classify("2001:db8::1")
        assertThat(url).isEqualTo("http://[2001:db8::1]")
    }

    @Test
    fun `localhost detected`() {
        val (_, url) = UrlIntelligence.classify("localhost:3000")
        assertThat(url).isEqualTo("http://localhost:3000")
    }

    @Test
    fun `file uri passed through`() {
        val (_, url) = UrlIntelligence.classify("file:///sdcard/doc.pdf")
        assertThat(url).isEqualTo("file:///sdcard/doc.pdf")
    }

    @Test
    fun `plain query is search`() {
        val (input, url) = UrlIntelligence.classify("how to boil eggs")
        assertThat(input).isInstanceOf(Input.Search::class.java)
        assertThat(url).contains("duckduckgo.com")
        assertThat(url).contains("how+to+boil+eggs")
    }

    @Test
    fun `query with spaces is search`() {
        val (input, _) = UrlIntelligence.classify("example.com has spaces")
        assertThat(input).isInstanceOf(Input.Search::class.java)
    }

    @Test
    fun `javascript scheme is treated as search`() {
        val (input, _) = UrlIntelligence.classify("javascript:alert(1)")
        assertThat(input).isInstanceOf(Input.Search::class.java)
    }

    @Test
    fun `an oct uri is a circle, not a search query`() {
        val circle = "oct" + "A".repeat(44)
        val (input, url) = UrlIntelligence.classify("oct://$circle/post/1")
        assertThat(input).isEqualTo(Input.Oct(OctUri.parse("oct://$circle/post/1")!!))
        assertThat(url).isEqualTo("oct://$circle/post/1")
    }

    @Test
    fun `a bare oct uri resolves to the circle index`() {
        val circle = "oct" + "A".repeat(44)
        val (input, url) = UrlIntelligence.classify("oct://$circle")
        assertThat(input).isEqualTo(Input.Oct(OctUri(circle, "/index.html")))
        assertThat(url).isEqualTo("oct://$circle/index.html")
    }

    @Test
    fun `an oct uri that names no circle stays a search`() {
        val (input, _) = UrlIntelligence.classify("oct://not-a-circle")
        assertThat(input).isInstanceOf(Input.Search::class.java)
    }

    @Test
    fun `hostOf parses hosts`() {
        assertThat(UrlIntelligence.hostOf("https://Example.COM/path")).isEqualTo("example.com")
        assertThat(UrlIntelligence.hostOf("https://user@site.org:8443/x")).isEqualTo("site.org")
        assertThat(UrlIntelligence.hostOf("not a url")).isNull()
    }

    @Test
    fun `displayUrl strips scheme`() {
        assertThat(UrlIntelligence.displayUrl("https://example.com/")).isEqualTo("example.com")
        assertThat(UrlIntelligence.displayUrl("http://example.com/x")).isEqualTo("example.com/x")
    }

    @Test
    fun `looksLikeUrl heuristics`() {
        assertThat(UrlIntelligence.looksLikeUrl("https://example.com")).isTrue()
        assertThat(UrlIntelligence.looksLikeUrl("example.com")).isTrue()
        assertThat(UrlIntelligence.looksLikeUrl("hello world")).isFalse()
    }

    @Test
    fun `a circle's own address survives the engine reporting a blank document`() {
        val circle = "oct://" + "A".repeat(44) + "/index.html"
        assertThat(UrlIntelligence.settledUrl("about:blank", circle)).isEqualTo(circle)
        // The reported url is what an ordinary page keeps: only a blank report over an
        // oct address is substituted, so a real about:blank navigation stays blank.
        assertThat(UrlIntelligence.settledUrl("https://example.com/", circle))
            .isEqualTo("https://example.com/")
        assertThat(UrlIntelligence.settledUrl("about:blank", "about:blank")).isEqualTo("about:blank")
        assertThat(UrlIntelligence.settledUrl("about:blank", "about:home")).isEqualTo("about:blank")
        assertThat(UrlIntelligence.settledUrl("about:blank", null)).isEqualTo("about:blank")
        assertThat(UrlIntelligence.settledUrl("about:blank", "oct://not-a-circle")).isEqualTo("about:blank")
    }
}
