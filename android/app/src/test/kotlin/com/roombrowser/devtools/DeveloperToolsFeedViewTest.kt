package com.roombrowser.devtools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The derivation the two feed panels draw from, pinned without Compose: the
 * window it shows, the keys Compose reuses rows by, and the instance
 * stability that keeps a feed event the active filter excludes from costing
 * anything downstream of the snapshot.
 *
 * THE KEY INVARIANT IS THE ONE THE RECOMPOSITION WIN STANDS ON: when the ring
 * drops an old entry and a new one arrives, every surviving row keeps the key
 * it already had. If that ever breaks, the panel goes back to recomposing all
 * RENDER_MAX rows per console message — which no e2e test can see, because it
 * is a frame budget, not a behaviour.
 */
class DeveloperToolsFeedViewTest {

    private fun log(text: String, level: String = "log") = ConsoleEntry(level = level, text = text)

    private val errorsOnly: (ConsoleEntry) -> Boolean = { it.level == "error" }

    // ---------- the window -------------------------------------------------

    @Test
    fun `the window keeps the newest renderMax rows and nothing more`() {
        val entries = (0 until 250).map { log("line $it") }
        val view = feedView(entries, dropped = 0, renderMax = 200, matches = null)
        assertThat(view.matching).hasSize(250)
        assertThat(view.rows).hasSize(200)
        assertThat(view.rows.first().text).isEqualTo("line 50")
        assertThat(view.rows.last().text).isEqualTo("line 249")
    }

    @Test
    fun `a feed shorter than the window shows all of it`() {
        val entries = (0 until 5).map { log("line $it") }
        val view = feedView(entries, dropped = 0, renderMax = 200, matches = null)
        assertThat(view.rows).hasSize(5)
        assertThat(view.rows).containsExactlyElementsIn(entries).inOrder()
        assertThat(view.keys).containsExactly(0, 1, 2, 3, 4).inOrder()
    }

    @Test
    fun `no filter hands the snapshot itself back as the matching feed`() {
        val entries = listOf(log("a"), log("b"))
        val view = feedView(entries, dropped = 0, renderMax = 200, matches = null)
        // The unfiltered feed is not copied per event: the panel reads the
        // snapshot it already paid for.
        assertThat(view.matching).isSameInstanceAs(entries)
    }

    // ---------- the keys ----------------------------------------------------

    @Test
    fun `a surviving row keeps its key when the window slides`() {
        val entries = (0 until 250).map { log("line $it") }
        val before = feedView(entries, dropped = 0, renderMax = 200, matches = null)
        // The ring is full: the oldest entry leaves and one arrives, which is
        // exactly the event that used to recompose every row on screen.
        val slid = feedView(entries.drop(1) + log("line 250"), dropped = 1, renderMax = 200, matches = null)
        assertThat(slid.keys).containsExactlyElementsIn(before.keys.drop(1) + 250).inOrder()
        // The rows those keys belong to are the same survivors.
        assertThat(slid.rows).containsExactlyElementsIn(before.rows.drop(1) + log("line 250")).inOrder()
    }

    @Test
    fun `keys are unique even when a page logs the same line twice`() {
        val entries = listOf(log("same"), log("same"), log("same"))
        val view = feedView(entries, dropped = 0, renderMax = 200, matches = null)
        // A duplicate key is a crash in composition, not a cosmetic problem —
        // which is why the entries themselves cannot be the keys.
        assertThat(view.keys).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `an empty feed derives an empty window`() {
        // The element type is named because nothing on this line carries one
        // for the compiler to infer: emptyList() and a null filter leave both
        // the function's E and the list's own T unbound.
        val view = feedView(emptyList<ConsoleEntry>(), dropped = 0, renderMax = 200, matches = null)
        assertThat(view.matching).isEmpty()
        assertThat(view.rows).isEmpty()
        assertThat(view.keys).isEmpty()
    }

    // ---------- the filter -------------------------------------------------

    @Test
    fun `the filter keeps the whole matching feed and windows its newest rows`() {
        val entries = listOf(
            log("a", level = "error"),
            log("b"),
            log("c", level = "error"),
            log("d"),
            log("e", level = "error")
        )
        val view = feedView(entries, dropped = 0, renderMax = 2, matches = errorsOnly)
        // The copy control reports every match, not only the drawn window.
        assertThat(view.matching.map { it.text }).containsExactly("a", "c", "e").inOrder()
        assertThat(view.rows.map { it.text }).containsExactly("c", "e").inOrder()
        // The keys are the ordinals of the rows that matched, not their
        // positions in the filtered list.
        assertThat(view.keys).containsExactly(2, 4).inOrder()
    }

    // ---------- the cache --------------------------------------------------

    @Test
    fun `an event the filter excludes hands the same view back`() {
        val cache = FeedViewCache<ConsoleEntry>()
        val errors = listOf(log("a", level = "error"), log("c", level = "error"))
        val entries = listOf(log("b")) + errors
        val first = cache.derive(entries, dropped = 0, renderMax = 200, matches = errorsOnly)
        // A log line arrives; the level filter is active, so nothing the panel
        // derives changed — and the instances must say so.
        val second = cache.derive(entries + log("late"), dropped = 0, renderMax = 200, matches = errorsOnly)
        assertThat(second).isSameInstanceAs(first)
        assertThat(second.matching.map { it.text }).containsExactly("a", "c").inOrder()
    }

    @Test
    fun `a drop the filter excludes hands the same view back`() {
        val cache = FeedViewCache<ConsoleEntry>()
        val errors = listOf(log("a", level = "error"), log("c", level = "error"))
        val first = cache.derive(listOf(log("b")) + errors, dropped = 0, renderMax = 200, matches = errorsOnly)
        // The ring filled and dropped its oldest entry — a log line, invisible
        // under the active filter. The content is unchanged, so the view is.
        val second = cache.derive(errors, dropped = 1, renderMax = 200, matches = errorsOnly)
        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun `a matching event re-derives the view`() {
        val cache = FeedViewCache<ConsoleEntry>()
        val first = cache.derive(listOf(log("a")), dropped = 0, renderMax = 200, matches = null)
        val second = cache.derive(listOf(log("a"), log("b")), dropped = 0, renderMax = 200, matches = null)
        assertThat(second).isNotSameInstanceAs(first)
        assertThat(second.matching).hasSize(2)
        assertThat(second.keys).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `a cleared feed derives from nothing again`() {
        val cache = FeedViewCache<ConsoleEntry>()
        cache.derive(listOf(log("a"), log("b")), dropped = 0, renderMax = 200, matches = null)
        // Clear resets the ring's loss count with its entries, so the ordinals
        // start over — and the keys are still unique within the view.
        val after = cache.derive(listOf(log("c")), dropped = 0, renderMax = 200, matches = null)
        assertThat(after.matching.map { it.text }).containsExactly("c")
        assertThat(after.keys).containsExactly(0)
    }

    @Test
    fun `the unfiltered cache keeps the snapshot itself across an excluded nothing`() {
        val cache = FeedViewCache<ConsoleEntry>()
        val entries = listOf(log("a"), log("b"))
        val first = cache.derive(entries, dropped = 0, renderMax = 200, matches = null)
        // An equal snapshot (same entry instances, new list) still maps to the
        // first view, so the report remember keyed on `matching` hits.
        val second = cache.derive(entries.toList(), dropped = 0, renderMax = 200, matches = null)
        assertThat(second).isSameInstanceAs(first)
        assertThat(second.matching).isSameInstanceAs(entries)
    }
}
