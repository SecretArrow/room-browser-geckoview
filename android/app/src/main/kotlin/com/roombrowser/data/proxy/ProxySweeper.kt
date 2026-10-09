package com.roombrowser.data.proxy

import com.roombrowser.domain.proxy.ProxyCandidate
import com.roombrowser.domain.proxy.ProxyHealth
import com.roombrowser.domain.proxy.ProxyHealthRules
import com.roombrowser.domain.proxy.ProxyScheme
import com.roombrowser.domain.proxy.ProxySweepPlan
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Establishes that a candidate is actually a proxy, by connecting THROUGH it and reading back
 * the address the far end sees.
 *
 * A listing is not evidence. Free lists are full of dead hosts and, worse, of hosts that
 * accept the connection and forward it unchanged — a transparent proxy loads every page
 * perfectly while hiding nothing, and only the exit address can tell the two apart.
 */
class ProxySweeper {

    private val base = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * The address this device reaches the network from, fetched DIRECT.
     *
     * Deliberately not routed through the candidate proxy — and deliberately not read from
     * [com.roombrowser.browser.engine.NetworkIdentity]'s cache either, so that the comparison
     * the sweep makes is between two independent observations rather than one observation
     * taken twice.
     */
    suspend fun directIp(): String? = withContext(Dispatchers.IO) {
        EXIT_IP_ENDPOINTS.firstNotNullOfOrNull { url ->
            fetch(base, url)?.takeIf(ProxyHealthRules::looksLikeIp)
        }
    }

    /**
     * Probe a bounded slice of [candidates] and report what was observed.
     *
     * Bounded twice over: [ProxySweepPlan.MAX_PROBES] candidates at a time, at most
     * [PROBE_CONCURRENCY] connections at once, and it stops as soon as enough candidates have
     * verified. The result of a sweep is not "every endpoint ranked" — that would be tens of
     * thousands of connections to build a list of which eight are alive today.
     */
    suspend fun sweep(
        candidates: List<ProxyCandidate>,
        directIp: String?,
        startIndex: Int = 0,
        budgetMs: Long = SWEEP_BUDGET_MS,
        needed: Int = ProxySweepPlan.ENOUGH_VERIFIED
    ): SweepResult = coroutineScope {
        val slice = ProxySweepPlan.slice(candidates, startIndex)
        val gate = Semaphore(PROBE_CONCURRENCY)
        val observed = Collections.synchronizedList(mutableListOf<ProxyHealth>())
        val verified = AtomicInteger(0)
        val deadline = System.currentTimeMillis() + budgetMs

        slice.map { candidate ->
            async(Dispatchers.IO) {
                if (expired(deadline, verified, needed)) return@async
                gate.withPermit {
                    if (expired(deadline, verified, needed)) return@withPermit
                    val health = probe(candidate) ?: return@withPermit
                    observed.add(health)
                    if (ProxyHealthRules.isUsable(health, directIp)) verified.incrementAndGet()
                }
            }
        }.joinAll()

        SweepResult(
            health = observed.toList(),
            probed = slice.size,
            verified = verified.get(),
            nextIndex = ProxySweepPlan.nextIndex(candidates, startIndex, slice.size)
        )
    }

    /** The latency of the attempt that ANSWERED; a failed endpoint is not timed as a success. */
    private fun probe(candidate: ProxyCandidate): ProxyHealth? {
        val type = when (candidate.scheme) {
            ProxyScheme.SOCKS4, ProxyScheme.SOCKS5 -> Proxy.Type.SOCKS
            else -> Proxy.Type.HTTP
        }
        val client = base.newBuilder()
            .proxy(Proxy(type, InetSocketAddress.createUnresolved(candidate.host, candidate.port)))
            .build()
        EXIT_IP_ENDPOINTS.forEach { url ->
            val started = System.currentTimeMillis()
            val ip = fetch(client, url) ?: return@forEach
            if (!ProxyHealthRules.looksLikeIp(ip)) return@forEach
            return ProxyHealth(
                id = candidate.id,
                latencyMs = System.currentTimeMillis() - started,
                exitIp = ip.trim(),
                checkedAtMs = System.currentTimeMillis()
            )
        }
        return null
    }

    private fun fetch(client: OkHttpClient, url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()

    private fun expired(deadline: Long, verified: AtomicInteger, needed: Int): Boolean =
        System.currentTimeMillis() > deadline || verified.get() >= needed

    data class SweepResult(
        val health: List<ProxyHealth>,
        val probed: Int,
        val verified: Int,
        val nextIndex: Int
    )

    private companion object {
        const val PROBE_TIMEOUT_MS = 4_000L
        const val PROBE_CONCURRENCY = 24
        const val SWEEP_BUDGET_MS = 45_000L

        /** Plain HTTPS endpoints that echo the caller's address. */
        val EXIT_IP_ENDPOINTS = listOf(
            "https://api.ipify.org",
            "https://icanhazip.com",
            "https://checkip.amazonaws.com"
        )
    }
}
