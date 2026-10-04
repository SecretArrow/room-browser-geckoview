package com.roombrowser.engine

import com.roombrowser.domain.engine.FilterEngine

/**
 * Everything a sub-resource blocker needs to make the same decision the app
 * makes for a navigation, in a form that can cross into an engine process.
 *
 * WHY THE FILTER IS DATA AND NOT A CALLBACK. The WebView edition decides
 * sub-resources in the app: `shouldInterceptRequest` hands it a URL and the app
 * answers block-or-allow from [FilterEngine]. GeckoView has no such callback --
 * the only hook that can CANCEL a request is a WebExtension's
 * `webRequest.onBeforeRequest`, which runs in JavaScript, in the extension
 * process, and must answer SYNCHRONOUSLY. There is no round trip available, so
 * the rules have to travel to the decision instead of the request travelling
 * to the decision.
 *
 * The consequence is deliberate and worth stating plainly: the matching
 * ALGORITHM is written twice, once in [FilterEngine.decide] and once in the
 * extension's `blocker.js`. The DATA is written once. Every rule here is read
 * from the one bundled list (`assets/filters/hosts.txt`), so a host added to
 * the list is enforced by both engines on the next launch, and the two
 * editions cannot disagree about WHAT is blocked -- only, at worst, about a
 * detail of the matching control flow, which `blocker.js` mirrors step for
 * step and cites.
 *
 * [shieldsDisabledHosts] is a set of hosts, not a predicate: the blocker runs
 * where the site-settings database is not reachable, so the exemptions are
 * resolved into a snapshot and handed over. The lookup KEY is the REQUEST host,
 * which is what the WebView edition's `shouldInterceptRequest` passes to
 * `siteSettingFor` and therefore what the two editions must agree on -- see
 * `WebClients.kt`. It is a faithful copy of that behaviour rather than a
 * correction of it.
 */
data class ResourceFilter(
    /** Normalised (lowercase) ad hosts; subdomains of an entry match too. */
    val adHosts: Set<String>,
    /** Normalised tracker hosts; subdomains of an entry match too. */
    val trackerHosts: Set<String>,
    /** Normalised malicious-site hosts; matched before every other rule. */
    val maliciousHosts: Set<String>,
    /**
     * Keyword rules in [FilterEngine.decide]'s application order. Order is
     * load-bearing: the first pattern that matches decides the category, so a
     * reordered list reports different statistics for the same URL.
     */
    val keywordRules: List<FilterEngine.KeywordRule>,
    /** Block hosts on [adHosts] (subject to [shieldsDisabledHosts]). */
    val blockAds: Boolean,
    /** Block hosts on [trackerHosts] (subject to [shieldsDisabledHosts]). */
    val blockTrackers: Boolean,
    /**
     * Block a [trackerHosts] entry even when the page IS that host. Without
     * it, a tracker host is only blocked when it differs from the page host.
     */
    val blockCrossSite: Boolean,
    /**
     * Block hosts on [maliciousHosts]. Not subject to
     * [shieldsDisabledHosts]: turning shields off for a site excuses its
     * advertising, never its malware.
     */
    val blockMalicious: Boolean,
    /**
     * Hosts whose stored site setting turns shields off. Looked up by the
     * REQUEST host, mirroring `WebClients.onResourceRequest`.
     */
    val shieldsDisabledHosts: Set<String>
)

/**
 * Where a blocker reports a request it cancelled.
 *
 * The report is what keeps the privacy dashboard honest: a block the user can
 * see counted has to have actually happened, and a block that happened has to
 * be counted. It is a callback rather than a return value because the decision
 * this describes was already made, in another process, by the time it arrives.
 */
fun interface BlockedResourceSink {
    /**
     * @param host the request host that was blocked, already lowercased.
     * @param category why it was blocked, in the app's own vocabulary.
     */
    fun onBlocked(host: String, category: FilterEngine.FilterCategory)
}
