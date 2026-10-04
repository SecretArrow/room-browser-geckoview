package com.roombrowser.domain.engine

/**
 * Privacy filtering engine: tracker/ad blocking, popup blocking,
 * malicious-site protection and HTTPS upgrade decisions.
 *
 * Decisions are host-based (and keyword-based for a small set of
 * high-confidence patterns) so the engine never needs full URLs,
 * which keeps logging privacy-friendly.
 */
class FilterEngine(
    adHosts: Set<String> = emptySet(),
    trackerHosts: Set<String> = emptySet(),
    maliciousHosts: Set<String> = emptySet(),
    private val keywordPatterns: List<KeywordRule> = DEFAULT_KEYWORD_RULES
) {

    data class KeywordRule(val pattern: String, val category: FilterCategory)

    enum class FilterCategory { AD, TRACKER, CROSS_SITE_TRACKER, MALICIOUS, POPUP }

    sealed interface Decision {
        data class Blocked(val category: FilterCategory, val reason: String) : Decision
        data class Allowed(val upgradedToHttps: Boolean = false) : Decision
    }

    private val ads: Set<String> = adHosts.map { it.lowercase() }.toSet()
    private val trackers: Set<String> = trackerHosts.map { it.lowercase() }.toSet()
    private val malicious: Set<String> = maliciousHosts.map { it.lowercase() }.toSet()

    /**
     * The loaded rule sets, read-only, for a caller that has to run the SAME
     * rules somewhere this class cannot reach.
     *
     * WHY THESE EXIST. A sub-resource can be blocked in two places and only
     * one of them is reachable from Kotlin: Android WebView reports every
     * sub-resource to `shouldInterceptRequest`, but GeckoView has no
     * per-request callback at all — a request can only be cancelled from a
     * WebExtension's `webRequest.onBeforeRequest`, which is JavaScript. That
     * listener has to answer SYNCHRONOUSLY, so it cannot ask this engine for a
     * decision; it has to hold the rules itself.
     *
     * The alternative was to ship a second copy of the host list inside the
     * extension, which is a second source of truth for a privacy decision:
     * the two lists would drift, and the drift would be invisible — the app
     * would answer "not on the list" for a navigation while the blocker
     * cancelled the very same host as a sub-resource. Exposing the loaded sets
     * instead keeps ONE list, read from `assets/filters/hosts.txt` by
     * [com.roombrowser.data.filters.FilterListLoader] and handed to whichever
     * blocker the engine provides.
     *
     * These are the NORMALISED sets (lowercased at construction), not the
     * constructor arguments: a caller matching against them must lowercase its
     * own host first, exactly as [decide] does.
     */
    val adRuleHosts: Set<String> get() = ads

    /** See [adRuleHosts]. */
    val trackerRuleHosts: Set<String> get() = trackers

    /** See [adRuleHosts]. */
    val maliciousRuleHosts: Set<String> get() = malicious

    /**
     * The keyword rules, in the order [decide] applies them — first match
     * wins, and it is the order that decides which category a URL lands in
     * when two patterns both match.
     */
    val keywordRules: List<KeywordRule> get() = keywordPatterns

    /**
     * Decide what to do with a (sub)resource request.
     *
     * @param requestHost host of the requested resource
     * @param pageHost    host of the page that triggered the request (null for main frame)
     * @param path        path part of the URL, used for keyword rules (default "/")
     *
     * The block* defaults mirror ProfileSettings' compatibility-first
     * defaults (all shields OFF). They used to default to `true`, which meant
     * a caller that simply omitted them silently got blocking ON — the
     * opposite of what the product promises a fresh install. The only
     * production caller passes all four explicitly; these exist so a future
     * one that forgets cannot disagree with the product default.
     */
    fun decide(
        requestHost: String,
        pageHost: String? = null,
        path: String = "/",
        blockAds: Boolean = false,
        blockTrackers: Boolean = false,
        blockCrossSite: Boolean = false,
        blockMalicious: Boolean = false
    ): Decision {
        val host = requestHost.lowercase().trimEnd('.')
        if (host.isEmpty()) return Decision.Allowed()

        if (blockMalicious && matchesSuffix(malicious, host)) {
            return Decision.Blocked(FilterCategory.MALICIOUS, "host on malicious-site blocklist")
        }
        if (blockAds && matchesSuffix(ads, host)) {
            return Decision.Blocked(FilterCategory.AD, "host on ad blocklist")
        }
        if (blockTrackers && matchesSuffix(trackers, host)) {
            val crossSite = pageHost != null && pageHost.lowercase() != host
            return if (blockCrossSite || !crossSite) {
                Decision.Blocked(
                    if (crossSite) FilterCategory.CROSS_SITE_TRACKER else FilterCategory.TRACKER,
                    "host on tracker blocklist"
                )
            } else Decision.Allowed()
        }

        if (blockAds || blockTrackers) {
            val lowered = "$host${path.lowercase()}"
            for (rule in keywordPatterns) {
                if (lowered.contains(rule.pattern)) {
                    val relevant = when (rule.category) {
                        FilterCategory.AD -> blockAds
                        FilterCategory.TRACKER, FilterCategory.CROSS_SITE_TRACKER -> blockTrackers
                        else -> true
                    }
                    if (relevant) {
                        return Decision.Blocked(rule.category, "keyword rule '${rule.pattern}'")
                    }
                }
            }
        }
        return Decision.Allowed()
    }

    /** Main-frame malicious/ad/tracker check without page context. */
    fun blockedCategory(host: String): FilterCategory? {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return null
        return when {
            matchesSuffix(malicious, h) -> FilterCategory.MALICIOUS
            matchesSuffix(ads, h) -> FilterCategory.AD
            matchesSuffix(trackers, h) -> FilterCategory.TRACKER
            else -> null
        }
    }

    /** Heuristic malicious-site signals used when the host is not on any list. */
    fun suspiciousSignals(url: String): List<String> {
        val signals = mutableListOf<String>()
        val lowered = url.lowercase()
        if (lowered.startsWith("http://")) signals += "insecure http connection"
        if (Regex("^https?://[0-9]{1,3}(\\.[0-9]{1,3}){3}").containsMatchIn(lowered)) {
            signals += "IP address used instead of a domain name"
        }
        if (lowered.contains("xn--")) signals += "punycode domain (possible homograph)"
        return signals
    }

    /**
     * Match [host] and every parent domain against the blocklist set.
     * Blocks both exact hosts ("doubleclick.net") and their subdomains
     * ("cdn.doubleclick.net") — but never unrelated TLD suffixes.
     */
    private fun matchesSuffix(set: Set<String>, host: String): Boolean {
        if (host in set) return true
        var idx = host.indexOf('.')
        while (idx != -1) {
            if (host.substring(idx + 1) in set) return true
            idx = host.indexOf('.', idx + 1)
        }
        return false
    }

    companion object {
        val DEFAULT_KEYWORD_RULES = listOf(
            KeywordRule("/ads/", FilterCategory.AD),
            KeywordRule("/adserver", FilterCategory.AD),
            KeywordRule("/advert", FilterCategory.AD),
            KeywordRule("doubleclick.net", FilterCategory.AD),
            KeywordRule("googlesyndication", FilterCategory.AD),
            KeywordRule("/analytics.js", FilterCategory.TRACKER),
            KeywordRule("/gtag/js", FilterCategory.TRACKER),
            KeywordRule("google-analytics.com", FilterCategory.TRACKER),
            KeywordRule("connect.facebook.net", FilterCategory.TRACKER),
            KeywordRule("scorecardresearch", FilterCategory.TRACKER),
            KeywordRule("/telemetry", FilterCategory.TRACKER),
            KeywordRule("/beacon.gif", FilterCategory.TRACKER),
            KeywordRule("hotjar.com", FilterCategory.TRACKER),
            KeywordRule("mixpanel.com", FilterCategory.TRACKER),
            KeywordRule("segment.io", FilterCategory.TRACKER),
            KeywordRule("amplitude.com", FilterCategory.TRACKER),
            KeywordRule("fullstory.com", FilterCategory.TRACKER)
        )
    }
}
