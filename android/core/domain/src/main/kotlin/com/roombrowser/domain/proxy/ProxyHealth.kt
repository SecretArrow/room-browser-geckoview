package com.roombrowser.domain.proxy

import kotlinx.serialization.Serializable

/** What one health check observed about one candidate. */
@Serializable
data class ProxyHealth(
    val id: String,
    val latencyMs: Long,
    val exitIp: String?,
    val checkedAtMs: Long
)

/**
 * The rules that decide which proxies may be used, kept pure so they are unit-testable in
 * the fast job rather than discovered on a device.
 */
object ProxyHealthRules {

    /** A sweep result older than this is re-probed before it is trusted again. */
    const val MAX_AGE_MS: Long = 30L * 60L * 1000L

    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val IPV6 = Regex("^[0-9A-Fa-f:]{2,45}$")

    /**
     * Whether [value] could be an address at all. A probe reads whatever the far end returned,
     * and a captive portal or an error page answers 200 with prose — text that differs from
     * the direct address, and so would otherwise be recorded as a working proxy.
     */
    fun looksLikeIp(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return false
        IPV4.matchEntire(text)?.let { match ->
            return match.groupValues.drop(1).all { it.toIntOrNull() in 0..255 }
        }
        return text.contains(':') && IPV6.matches(text)
    }

    fun isFresh(health: ProxyHealth?, nowMs: Long): Boolean =
        health != null && nowMs - health.checkedAtMs in 0..MAX_AGE_MS

    /**
     * Usable means the exit address was OBSERVED and differs from the direct one. An exit IP
     * equal to the direct IP is a transparent or dead proxy — the one failure that otherwise
     * reads as success, since the page loads perfectly while nothing is proxied.
     *
     * A null [directIp] is not usable either. Without the direct address there is nothing to
     * compare against, so a transparent proxy would pass; the sweep fetches the direct IP
     * first and a candidate is only ever called verified relative to it.
     */
    fun isUsable(health: ProxyHealth?, directIp: String?): Boolean {
        val exit = health?.exitIp?.takeIf { looksLikeIp(it) } ?: return false
        val direct = directIp?.takeIf { looksLikeIp(it) } ?: return false
        return exit != direct
    }

    /**
     * Candidates a profile may pick from, best first: verified ones by latency, then the
     * unverified rest in the source's own order so a first run with no sweep still has
     * something to try.
     */
    fun rank(
        candidates: List<ProxyCandidate>,
        health: Map<String, ProxyHealth>,
        directIp: String?
    ): List<ProxyCandidate> {
        val (verified, rest) = candidates.partition { isUsable(health[it.id], directIp) }
        return verified.sortedWith(
            compareBy({ health.getValue(it.id).latencyMs }, { it.id })
        ) + rest
    }

    /**
     * The endpoint AUTO should use for [profileKey]: the best candidate this profile has not
     * already failed through. [avoid] is per profile and per session — an endpoint that failed
     * for one profile is still offered to another, because the failure was the relationship
     * between that profile and the endpoint, not a property of the endpoint.
     */
    fun pick(
        candidates: List<ProxyCandidate>,
        health: Map<String, ProxyHealth>,
        directIp: String?,
        avoid: Set<String> = emptySet()
    ): ProxyCandidate? = rank(candidates, health, directIp).firstOrNull { it.id !in avoid }
}
