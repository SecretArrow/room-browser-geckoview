package com.roombrowser.domain.proxy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The "safest first" ordering the network-warning picker uses.
 *
 * The point of these tests is that the ranking is a FILTER as well as an order: a function
 * that simply sorted everything would put a transparent proxy at the front for being fast,
 * which is the exact failure this picker exists to avoid.
 */
class ProxySafeRankingTest {

    private val now = 1_000_000L
    private val direct = "203.0.113.5"

    private fun health(id: String, latency: Long, exit: String?) =
        ProxyHealth(id = id, latencyMs = latency, exitIp = exit, checkedAtMs = now)

    private fun candidate(
        host: String,
        port: Int = 8080,
        scheme: ProxyScheme = ProxyScheme.HTTP,
        country: String? = null
    ) = ProxyCandidate(host = host, port = port, scheme = scheme, country = country)

    @Test
    fun `only verified candidates are ranked`() {
        val good = candidate("10.0.0.1")
        val transparent = candidate("10.0.0.2")
        val dead = candidate("10.0.0.3")
        val health = mapOf(
            good.id to health(good.id, 120, "198.51.100.1"),
            // Answers, but exits at our own address: the transparent-proxy failure.
            transparent.id to health(transparent.id, 5, direct)
            // dead has no health row at all.
        )
        val ranked = ProxyHealthRules.rankSafe(listOf(transparent, dead, good), health, direct)
        // Negative control: a function that ranked everything would list all three, and would
        // put the fast transparent one first.
        assertThat(ranked.map { it.id }).containsExactly(good.id).inOrder()
    }

    @Test
    fun `https is preferred over http at equal latency`() {
        val plain = candidate("10.0.0.1", scheme = ProxyScheme.HTTP)
        val secure = candidate("10.0.0.2", scheme = ProxyScheme.HTTPS)
        val health = mapOf(
            plain.id to health(plain.id, 50, "198.51.100.1"),
            secure.id to health(secure.id, 50, "198.51.100.2")
        )
        val ranked = ProxyHealthRules.rankSafe(listOf(plain, secure), health, direct)
        assertThat(ranked.map { it.id }).containsExactly(secure.id, plain.id).inOrder()
    }

    @Test
    fun `lower latency wins within a scheme`() {
        val fast = candidate("10.0.0.1")
        val slow = candidate("10.0.0.2")
        val health = mapOf(
            fast.id to health(fast.id, 30, "198.51.100.1"),
            slow.id to health(slow.id, 300, "198.51.100.2")
        )
        val ranked = ProxyHealthRules.rankSafe(listOf(slow, fast), health, direct)
        assertThat(ranked.map { it.id }).containsExactly(fast.id, slow.id).inOrder()
    }

    @Test
    fun `equal scheme and latency break ties by id`() {
        val a = candidate("10.0.0.1")
        val b = candidate("10.0.0.2")
        val health = mapOf(
            a.id to health(a.id, 40, "198.51.100.1"),
            b.id to health(b.id, 40, "198.51.100.2")
        )
        val ranked = ProxyHealthRules.rankSafe(listOf(b, a), health, direct)
        assertThat(ranked.map { it.id }).containsExactly(a.id, b.id).inOrder()
    }

    @Test
    fun `nothing comparable means nothing is safe`() {
        val a = candidate("10.0.0.1")
        val health = mapOf(a.id to health(a.id, 10, "198.51.100.1"))
        // Without the direct address there is nothing to compare against, so it fails closed.
        assertThat(ProxyHealthRules.rankSafe(listOf(a), health, null)).isEmpty()
        assertThat(ProxyHealthRules.rankSafe(listOf(a), health, "  ")).isEmpty()
    }
}
