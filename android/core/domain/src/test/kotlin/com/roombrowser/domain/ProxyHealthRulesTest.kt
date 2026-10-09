package com.roombrowser.domain.proxy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProxyHealthRulesTest {

    private val now = 1_000_000L

    private fun health(id: String, latency: Long, exit: String?, at: Long = now) =
        ProxyHealth(id = id, latencyMs = latency, exitIp = exit, checkedAtMs = at)

    private fun candidate(host: String, port: Int = 8080, scheme: ProxyScheme = ProxyScheme.HTTP) =
        ProxyCandidate(host = host, port = port, scheme = scheme)

    @Test
    fun `an exit ip equal to the direct ip is not a proxy`() {
        // The transparent-proxy case: the page loads, so every other signal says success.
        val h = health("a:1", 10, exit = "203.0.113.5")
        assertThat(ProxyHealthRules.isUsable(h, directIp = "203.0.113.5")).isFalse()
    }

    @Test
    fun `a different exit ip is usable`() {
        val h = health("a:1", 10, exit = "198.51.100.7")
        assertThat(ProxyHealthRules.isUsable(h, directIp = "203.0.113.5")).isTrue()
    }

    @Test
    fun `an unobserved or uncomparable candidate is not usable`() {
        assertThat(ProxyHealthRules.isUsable(null, directIp = "203.0.113.5")).isFalse()
        assertThat(ProxyHealthRules.isUsable(health("a:1", 10, exit = null), "203.0.113.5")).isFalse()
        assertThat(ProxyHealthRules.isUsable(health("a:1", 10, exit = "  "), "203.0.113.5")).isFalse()
        // No direct address to compare against: a transparent proxy would pass, so it fails closed.
        assertThat(ProxyHealthRules.isUsable(health("a:1", 10, exit = "198.51.100.7"), null)).isFalse()
        assertThat(ProxyHealthRules.isUsable(health("a:1", 10, exit = "198.51.100.7"), "  ")).isFalse()
    }

    @Test
    fun `only something address shaped counts as an observation`() {
        assertThat(ProxyHealthRules.looksLikeIp("203.0.113.5")).isTrue()
        assertThat(ProxyHealthRules.looksLikeIp(" 198.51.100.7\n")).isTrue()
        assertThat(ProxyHealthRules.looksLikeIp("2001:db8::1")).isTrue()
        // A captive portal answers 200 with a page; that text differs from the direct IP
        // and would otherwise be filed as a working proxy.
        assertThat(ProxyHealthRules.looksLikeIp("<html>Sign in to WiFi</html>")).isFalse()
        assertThat(ProxyHealthRules.looksLikeIp("999.1.1.1")).isFalse()
        assertThat(ProxyHealthRules.looksLikeIp("203.0.113.5:8080")).isFalse()
        assertThat(ProxyHealthRules.looksLikeIp("")).isFalse()
        assertThat(ProxyHealthRules.looksLikeIp(null)).isFalse()
    }

    @Test
    fun `a portal page is not a usable exit`() {
        val h = health("a:1", 10, exit = "<html>Sign in</html>")
        assertThat(ProxyHealthRules.isUsable(h, directIp = "203.0.113.5")).isFalse()
    }

    @Test
    fun `freshness expires`() {
        assertThat(ProxyHealthRules.isFresh(health("a:1", 10, "1.1.1.1"), now)).isTrue()
        assertThat(ProxyHealthRules.isFresh(health("a:1", 10, "1.1.1.1", at = now - 1), now)).isTrue()
        assertThat(ProxyHealthRules.isFresh(health("a:1", 10, "1.1.1.1", at = now - ProxyHealthRules.MAX_AGE_MS - 1), now)).isFalse()
        assertThat(ProxyHealthRules.isFresh(null, now)).isFalse()
    }

    @Test
    fun `ranking puts verified candidates first by latency`() {
        val fast = candidate("10.0.0.1")
        val slow = candidate("10.0.0.2")
        val unverified = candidate("10.0.0.3")
        val health = mapOf(
            fast.id to health(fast.id, 90, exit = "198.51.100.1"),
            slow.id to health(slow.id, 400, exit = "198.51.100.2")
        )
        val ranked = ProxyHealthRules.rank(listOf(unverified, slow, fast), health, directIp = "203.0.113.5")
        assertThat(ranked.map { it.id }).containsExactly(fast.id, slow.id, unverified.id).inOrder()
    }

    @Test
    fun `ranking keeps unverified candidates rather than emptying the list`() {
        // A first run has no sweep results at all; the list must still be usable.
        val a = candidate("10.0.0.1")
        val b = candidate("10.0.0.2")
        val ranked = ProxyHealthRules.rank(listOf(a, b), emptyMap(), directIp = "203.0.113.5")
        assertThat(ranked.map { it.id }).containsExactly(a.id, b.id).inOrder()
    }

    @Test
    fun `a transparent candidate never reaches the front`() {
        val transparent = candidate("10.0.0.1")
        val real = candidate("10.0.0.2")
        val health = mapOf(
            transparent.id to health(transparent.id, 5, exit = "203.0.113.5"),
            real.id to health(real.id, 900, exit = "198.51.100.2")
        )
        val ranked = ProxyHealthRules.rank(listOf(transparent, real), health, directIp = "203.0.113.5")
        // Faster, but it is the direct connection wearing a proxy's name.
        assertThat(ranked.first().id).isEqualTo(real.id)
    }

    @Test
    fun `pick skips what this profile already failed through`() {
        val a = candidate("10.0.0.1")
        val b = candidate("10.0.0.2")
        val health = mapOf(
            a.id to health(a.id, 10, exit = "198.51.100.1"),
            b.id to health(b.id, 20, exit = "198.51.100.2")
        )
        val candidates = listOf(a, b)
        assertThat(ProxyHealthRules.pick(candidates, health, "203.0.113.5")?.id).isEqualTo(a.id)
        assertThat(ProxyHealthRules.pick(candidates, health, "203.0.113.5", avoid = setOf(a.id))?.id).isEqualTo(b.id)
        assertThat(ProxyHealthRules.pick(candidates, health, "203.0.113.5", avoid = setOf(a.id, b.id))).isNull()
    }

    @Test
    fun `pages is the default scope and the sensitive ones are not`() {
        assertThat(ProxyScope.DEFAULT).containsExactly(ProxyScope.PAGES)
        assertThat(ProxyScope.DEFAULT).doesNotContain(ProxyScope.AGENT)
        assertThat(ProxyScope.DEFAULT).doesNotContain(ProxyScope.WALLET)
    }
}
