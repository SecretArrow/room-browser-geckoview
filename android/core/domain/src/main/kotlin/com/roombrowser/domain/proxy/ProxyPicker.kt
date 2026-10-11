package com.roombrowser.domain.proxy

import com.roombrowser.domain.paging.Paging

/**
 * The searched, paged view of the network-warning proxy picker.
 *
 * Kept pure — search and page arithmetic are the parts that are easy to get subtly wrong and
 * cheap to unit-test in the fast job, so neither depends on the device or on Compose.
 */
object ProxyPicker {

    /** Case-insensitive substring over the endpoint's host, its port and its country. */
    fun matches(candidate: ProxyCandidate, query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        return candidate.host.lowercase().contains(needle) ||
            candidate.port.toString().contains(needle) ||
            candidate.country?.lowercase()?.contains(needle) == true
    }

    fun filter(candidates: List<ProxyCandidate>, query: String): List<ProxyCandidate> =
        candidates.filter { matches(it, query) }

    /**
     * The page to show for [query] and [page]. The page is clamped to what the search left, so
     * a query that shrinks the list under a page number the user already turned to shows the
     * last real page rather than a blank one.
     */
    fun page(candidates: List<ProxyCandidate>, query: String, page: Int): Page {
        val filtered = filter(candidates, query)
        val current = Paging.clampPage(page, filtered.size)
        return Page(
            items = Paging.slice(filtered, current),
            pageIndex = current,
            pageCount = Paging.pageCount(filtered.size),
            total = filtered.size
        )
    }

    data class Page(
        val items: List<ProxyCandidate>,
        val pageIndex: Int,
        val pageCount: Int,
        val total: Int
    )
}
