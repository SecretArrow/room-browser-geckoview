package com.roombrowser.domain.paging

/**
 * Fixed-size paging for the model pickers.
 *
 * Ten per page: providers answer `/models` with anything from three ids to
 * several hundred, and one tile per id turns a picker into a wall. Ten keeps
 * a short list a single page (the footer hides itself) while a long one
 * becomes a few taps instead of an endless scroll.
 */
object Paging {

    const val PAGE_SIZE = 10

    fun pageCount(total: Int): Int =
        if (total <= 0) 0 else (total + PAGE_SIZE - 1) / PAGE_SIZE

    /**
     * [page] clamped into range. A live search can shrink the list under a
     * page number the user already turned to, and the view must follow it
     * rather than render an empty page.
     */
    fun clampPage(page: Int, total: Int): Int {
        val pages = pageCount(total)
        return if (pages == 0) 0 else page.coerceIn(0, pages - 1)
    }

    fun <T> slice(items: List<T>, page: Int): List<T> {
        if (items.isEmpty()) return emptyList()
        val from = clampPage(page, items.size) * PAGE_SIZE
        return items.subList(from, (from + PAGE_SIZE).coerceAtMost(items.size)).toList()
    }

    /** The window the footer states, 1-based and inclusive: "11-20 of 240". */
    fun windowLabel(page: Int, total: Int): String {
        if (total <= 0) return "0 of 0"
        val current = clampPage(page, total)
        val first = current * PAGE_SIZE + 1
        val last = (first + PAGE_SIZE - 1).coerceAtMost(total)
        return "$first-$last of $total"
    }
}
