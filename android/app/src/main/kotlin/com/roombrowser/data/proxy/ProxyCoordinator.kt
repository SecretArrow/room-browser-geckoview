package com.roombrowser.data.proxy

import com.roombrowser.data.repo.AppStateRepository
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.proxy.ProxyCandidate
import com.roombrowser.domain.proxy.ProxyHealth
import com.roombrowser.domain.proxy.ProxyHealthRules
import com.roombrowser.domain.proxy.ProxyMode
import com.roombrowser.domain.proxy.ProxyScope
import com.roombrowser.domain.proxy.ProxySweepState

/**
 * What the bind should do about the proxy.
 *
 * [Direct] and [Unavailable] are the same engine call and different messages: the first
 * is the feature being off, the second is the user having asked for a proxy and not got
 * one. Collapsing them would let a broken finder look like a setting that works.
 */
sealed interface ProxyDecision {
    data object Direct : ProxyDecision
    data class Use(val candidate: ProxyCandidate) : ProxyDecision
    data object Unavailable : ProxyDecision
}

/**
 * Decides which endpoint a profile should be using, and owns the sweep that answers it.
 *
 * The decision is per profile and re-made at every bind, which is what makes "each profile
 * gets its own proxy" the same mechanism as "auto use" rather than two features that have to
 * agree.
 */
class ProxyCoordinator(
    private val appState: AppStateRepository,
    private val catalogue: ProxyCatalogue,
    private val sweeper: ProxySweeper = ProxySweeper()
) {

    suspend fun state(): ProxySweepState = appState.proxySweep() ?: ProxySweepState()

    suspend fun candidates(): List<ProxyCandidate> = catalogue.candidates()

    /** Verified first by latency, then the unverified rest, so a cold start is still usable. */
    suspend fun ranked(): List<ProxyCandidate> {
        val sweep = state()
        return ProxyHealthRules.rank(candidates(), sweep.byId(), sweep.directIp)
    }

    /**
     * What [profile] should use right now for its page traffic.
     *
     * Sweeps inline when AUTO has nothing usable to go on, because the alternative is a
     * setting that appears to do nothing on the run where the user just turned it on. The
     * bind budget is short: the first load is waiting behind this.
     */
    suspend fun resolveForBind(profile: Profile): ProxyDecision =
        resolveScope(profile, ProxyScope.PAGES)

    /**
     * The same decision, asked for one scope.
     *
     * The scopes are independent because they carry different traffic and the user chose
     * them separately — a profile may route pages and leave its wallet on the direct
     * network, which is the default and the point of having scopes at all.
     */
    suspend fun resolveScope(profile: Profile, scope: ProxyScope): ProxyDecision {
        val settings = profile.settings
        if (!appState.globalSettingsSnapshot().proxyEnabled) return ProxyDecision.Direct
        if (settings.proxyMode == ProxyMode.OFF) return ProxyDecision.Direct
        if (scope !in settings.proxyScopes) return ProxyDecision.Direct

        if (settings.proxyMode == ProxyMode.MANUAL) {
            return pinnedCandidate(profile)?.let { ProxyDecision.Use(it) } ?: ProxyDecision.Unavailable
        }

        if (needsSweep()) sweep(BIND_SWEEP_BUDGET_MS)
        val sweep = state()
        val health = sweep.byId()
        val usable = ranked().firstOrNull { ProxyHealthRules.isUsable(health[it.id], sweep.directIp) }
        return usable?.let { ProxyDecision.Use(it) } ?: ProxyDecision.Unavailable
    }

    /** The manual endpoint, as the user typed it. [Profile.settings.proxyPinnedId] is provenance only. */
    private fun pinnedCandidate(profile: Profile): ProxyCandidate? {
        val settings = profile.settings
        val host = settings.proxyHost?.trim().orEmpty()
        if (host.isEmpty() || settings.proxyPort !in 1..65535) return null
        return ProxyCandidate(host = host, port = settings.proxyPort, scheme = settings.proxyScheme)
    }

    private suspend fun needsSweep(): Boolean {
        val sweep = state()
        val known = sweep.health.filter { ProxyHealthRules.isFresh(it, System.currentTimeMillis()) }
        return known.none { ProxyHealthRules.isUsable(it, sweep.directIp) }
    }

    /**
     * Probe a bounded slice and persist the verdict.
     *
     * Results MERGE rather than replace, keyed by endpoint: a sweep covers a slice of the
     * catalogue, so replacing would throw away what the previous slice established. Entries
     * that have aged out are dropped as they are merged, so what is stored is only ever as
     * old as [ProxyHealthRules.MAX_AGE_MS].
     */
    suspend fun sweep(budgetMs: Long = SWEEP_BUDGET_MS): ProxySweeper.SweepResult {
        val pool = candidates()
        val previous = state()
        val directIp = sweeper.directIp()
        val result = sweeper.sweep(
            candidates = pool,
            directIp = directIp,
            startIndex = previous.cursor,
            budgetMs = budgetMs
        )
        val now = System.currentTimeMillis()
        val merged = LinkedHashMap<String, ProxyHealth>()
        previous.health
            .filter { ProxyHealthRules.isFresh(it, now) }
            .forEach { merged[it.id] = it }
        result.health.forEach { merged[it.id] = it }
        appState.setProxySweep(
            ProxySweepState(
                directIp = directIp ?: previous.directIp,
                cursor = result.nextIndex,
                checkedAtMs = now,
                health = merged.values.toList()
            )
        )
        return result
    }

    private companion object {
        /** The first load is held behind a bind-time sweep, so this one is deliberately short. */
        const val BIND_SWEEP_BUDGET_MS = 15_000L
        const val SWEEP_BUDGET_MS = 45_000L
    }
}
