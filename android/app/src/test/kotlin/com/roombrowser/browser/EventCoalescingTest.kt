package com.roombrowser.browser

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The burst semantics of the three event-coalescing helpers BrowserViewModel
 * now runs its engine-callback writes through (F-05 / F-10).
 *
 * WHAT IS PINNED HERE, AND WHY EACH ONE MATTERS TO CI:
 *  - a burst collapses to ONE trailing action (the whole point of the fix);
 *  - the LAST values of a burst are the ones that land (e2e asserts tab
 *    url/title and shield counts after a page settles — they must equal
 *    what the un-coalesced code would have shown);
 *  - nothing is EVER dropped — not the tail of a block burst, not the last
 *    tab write — even when the owning scope is torn down mid-burst (the
 *    "every block must persist" invariant);
 *  - superseded windows do not flush early (an early flush would re-emit the
 *    tabs Flow and recompose the strip for an intermediate state).
 *
 * Virtual time makes the 500ms windows instant; the helpers are deliberately
 * engine- and Android-free so this is a plain JVM test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventCoalescingTest {

    private companion object {
        const val WINDOW_MS = 500L
    }

    /** One coalesced tab write, as the flush saw it. */
    private data class WrittenTab(
        val id: String,
        val url: String,
        val title: String?,
        val touch: Boolean
    )

    // ------------------------------------------------------- TrailingDebounce

    @Test
    fun `a burst of debounced calls fires once, after the last call`() = runTest {
        var fired = 0
        val debounce = TrailingDebounce(backgroundScope, WINDOW_MS) { fired++ }

        debounce.call()
        runCurrent()
        advanceTimeBy(300)
        // A second call inside the window restarts it — the burst's last
        // call owns the trailing edge.
        debounce.call()
        runCurrent()
        advanceTimeBy(WINDOW_MS - 1)
        runCurrent()
        assertThat(fired).isEqualTo(0)

        advanceTimeBy(1)
        runCurrent()
        assertThat(fired).isEqualTo(1)
    }

    @Test
    fun `calls separated by a full window each fire`() = runTest {
        var fired = 0
        val debounce = TrailingDebounce(backgroundScope, WINDOW_MS) { fired++ }

        debounce.call()
        runCurrent()
        advanceTimeBy(WINDOW_MS)
        runCurrent()
        assertThat(fired).isEqualTo(1)

        debounce.call()
        runCurrent()
        advanceTimeBy(WINDOW_MS)
        runCurrent()
        assertThat(fired).isEqualTo(2)
    }

    // ---------------------------------------------------- TabWriteCoalescer

    @Test
    fun `start then finish collapses into one write carrying the final values`() = runTest {
        val writes = mutableListOf<WrittenTab>()
        val coalescer = TabWriteCoalescer(backgroundScope, WINDOW_MS) { id, url, title, touch ->
            writes.add(WrittenTab(id, url, title, touch))
        }

        // A background tab's onPageStarted: url only, no title, no touch.
        coalescer.persist("t1", "https://a.test/page", null, false)
        runCurrent()
        advanceTimeBy(300)
        // The same tab's onPageFinished: settled url, the document's title,
        // and the touch of a tab the user looked at.
        coalescer.persist("t1", "https://a.test/page", "A Test Page", true)
        runCurrent()

        advanceTimeBy(WINDOW_MS - 1)
        runCurrent()
        assertThat(writes).isEmpty()
        advanceTimeBy(1)
        runCurrent()

        // ONE write, and it is exactly the row the finish's own write would
        // have produced — the invariant the tab-strip e2e depends on.
        assertThat(writes).containsExactly(WrittenTab("t1", "https://a.test/page", "A Test Page", true))
    }

    @Test
    fun `a null title keeps the last known title instead of blanking it`() = runTest {
        val writes = mutableListOf<WrittenTab>()
        val coalescer = TabWriteCoalescer(backgroundScope, WINDOW_MS) { id, url, title, touch ->
            writes.add(WrittenTab(id, url, title, touch))
        }

        coalescer.persist("t1", "https://a.test/", "First title", false)
        runCurrent()
        advanceTimeBy(200)
        // A navigation that only started has no new document title: null
        // must merge as "keep", never as "erase".
        coalescer.persist("t1", "https://b.test/", null, false)
        runCurrent()
        advanceTimeBy(WINDOW_MS)
        runCurrent()

        assertThat(writes).containsExactly(WrittenTab("t1", "https://b.test/", "First title", false))
    }

    @Test
    fun `touch is sticky across the burst it belongs to`() = runTest {
        val writes = mutableListOf<WrittenTab>()
        val coalescer = TabWriteCoalescer(backgroundScope, WINDOW_MS) { id, url, title, touch ->
            writes.add(WrittenTab(id, url, title, touch))
        }

        // Finish of page A (touch), then start of page B (no touch): the
        // burst was user-visible, and the merged write must still stamp it.
        coalescer.persist("t1", "https://a.test/", "A", true)
        runCurrent()
        advanceTimeBy(200)
        coalescer.persist("t1", "https://b.test/", null, false)
        runCurrent()
        advanceTimeBy(WINDOW_MS)
        runCurrent()

        assertThat(writes).containsExactly(WrittenTab("t1", "https://b.test/", "A", true))
    }

    @Test
    fun `a superseded window does not flush the intermediate state`() = runTest {
        val writes = mutableListOf<WrittenTab>()
        val coalescer = TabWriteCoalescer(backgroundScope, WINDOW_MS) { id, url, title, touch ->
            writes.add(WrittenTab(id, url, title, touch))
        }

        coalescer.persist("t1", "https://a.test/", null, false)
        runCurrent()
        advanceTimeBy(300)
        // Restarting the window cancels the first one: at t=500 the OLD
        // window's deadline passes without a write landing.
        coalescer.persist("t1", "https://b.test/", null, false)
        runCurrent()
        advanceTimeBy(199)
        runCurrent()
        assertThat(writes).isEmpty()

        // The new window closes at t=800 with the merged (latest) url.
        advanceTimeBy(301)
        runCurrent()
        assertThat(writes).containsExactly(WrittenTab("t1", "https://b.test/", null, false))
    }

    @Test
    fun `two tabs coexist and each lands one write`() = runTest {
        val writes = mutableListOf<WrittenTab>()
        val coalescer = TabWriteCoalescer(backgroundScope, WINDOW_MS) { id, url, title, touch ->
            writes.add(WrittenTab(id, url, title, touch))
        }

        coalescer.persist("t1", "https://a.test/", "A", false)
        runCurrent()
        coalescer.persist("t2", "https://b.test/", "B", true)
        runCurrent()
        advanceTimeBy(WINDOW_MS)
        runCurrent()

        assertThat(writes).containsExactly(
            WrittenTab("t1", "https://a.test/", "A", false),
            WrittenTab("t2", "https://b.test/", "B", true)
        )
    }

    @Test
    fun `scope teardown mid-window still lands the merged write`() = runTest {
        val writes = mutableListOf<WrittenTab>()
        val coalescer = TabWriteCoalescer(backgroundScope, WINDOW_MS) { id, url, title, touch ->
            writes.add(WrittenTab(id, url, title, touch))
        }

        coalescer.persist("t1", "https://a.test/", "A", true)
        runCurrent()
        // The window is still open when the ViewModel's scope is cancelled:
        // the last write must not vanish with it.
        backgroundScope.cancel()
        runCurrent()

        assertThat(writes).containsExactly(WrittenTab("t1", "https://a.test/", "A", true))
    }

    @Test
    fun `a home return merges over the abandoned page's finish write`() = runTest {
        val writes = mutableListOf<WrittenTab>()
        val coalescer = TabWriteCoalescer(backgroundScope, WINDOW_MS) { id, url, title, touch ->
            writes.add(WrittenTab(id, url, title, touch))
        }

        // The page's finish, then "Back to start page" inside the same window:
        // about:home must be the last word, exactly as the two sequential
        // writes used to leave it.
        coalescer.persist("t1", "https://a.test/page", "A Page", true)
        runCurrent()
        advanceTimeBy(200)
        coalescer.persist("t1", "about:home", "", true)
        runCurrent()
        advanceTimeBy(WINDOW_MS)
        runCurrent()

        assertThat(writes).containsExactly(WrittenTab("t1", "about:home", "", true))
    }

    @Test
    fun `an awaited snapshot flushes everything now and stays the last word`() = runTest {
        val writes = mutableListOf<WrittenTab>()
        val coalescer = TabWriteCoalescer(backgroundScope, WINDOW_MS) { id, url, title, touch ->
            writes.add(WrittenTab(id, url, title, touch))
        }

        // A background tab's pending write (its own window still open)...
        coalescer.persist("t2", "https://bg.test/", "BG", false)
        runCurrent()
        advanceTimeBy(100)
        // ...and the active tab's finish, whose title the omnibox improved on
        // after the fact (onTitleChanged never persists).
        coalescer.persist("t1", "https://a.test/", "Finish title", true)
        runCurrent()

        // The profile-switch save: awaited, so it lands with NO virtual-time
        // advance, and it must carry the active tab's freshest values.
        coalescer.persistNow("t1", "https://a.test/", "Late title", true)

        assertThat(writes).containsExactly(
            WrittenTab("t2", "https://bg.test/", "BG", false),
            WrittenTab("t1", "https://a.test/", "Late title", true)
        )

        // And no cancelled window may resurrect the pre-switch values later.
        advanceUntilIdle()
        assertThat(writes).hasSize(2)
    }

    // --------------------------------------------------------- BlockRecorder

    @Test
    fun `a burst of blocks flushes once, whole, and in order`() = runTest {
        val batches = mutableListOf<List<Pair<String, String>>>()
        val recorder = BlockRecorder(backgroundScope, WINDOW_MS) { batches.add(it) }

        recorder.record("a.test", "AD")
        runCurrent()
        advanceTimeBy(100)
        recorder.record("a.test", "AD")
        recorder.record("b.test", "TRACKER")
        runCurrent()

        advanceTimeBy(WINDOW_MS)
        runCurrent()

        assertThat(batches).hasSize(1)
        assertThat(batches.single()).containsExactly(
            "a.test" to "AD",
            "a.test" to "AD",
            "b.test" to "TRACKER"
        ).inOrder()
    }

    @Test
    fun `blocks arriving after a closed window start a new batch`() = runTest {
        val batches = mutableListOf<List<Pair<String, String>>>()
        val recorder = BlockRecorder(backgroundScope, WINDOW_MS) { batches.add(it) }

        recorder.record("a.test", "AD")
        runCurrent()
        advanceTimeBy(WINDOW_MS)
        runCurrent()
        assertThat(batches).hasSize(1)

        recorder.record("b.test", "TRACKER")
        runCurrent()
        advanceTimeBy(WINDOW_MS)
        runCurrent()

        assertThat(batches).hasSize(2)
        assertThat(batches.last()).containsExactly("b.test" to "TRACKER")
    }

    @Test
    fun `a quiet recorder flushes nothing`() = runTest {
        val batches = mutableListOf<List<Pair<String, String>>>()
        BlockRecorder(backgroundScope, WINDOW_MS) { batches.add(it) }

        // The drainer parks on receive: no ticker to fire, no flush.
        advanceTimeBy(10_000)
        runCurrent()

        assertThat(batches).isEmpty()
    }

    @Test
    fun `scope teardown mid-burst flushes the tail without dropping a block`() = runTest {
        val batches = mutableListOf<List<Pair<String, String>>>()
        val recorder = BlockRecorder(backgroundScope, WINDOW_MS) { batches.add(it) }

        recorder.record("a.test", "AD")
        recorder.record("b.test", "TRACKER")
        recorder.record("c.test", "CROSS_SITE_TRACKER")
        runCurrent()
        // The window is still open when the ViewModel goes away — the last
        // items of the burst must still reach the database.
        backgroundScope.cancel()
        runCurrent()

        assertThat(batches).hasSize(1)
        assertThat(batches.single()).containsExactly(
            "a.test" to "AD",
            "b.test" to "TRACKER",
            "c.test" to "CROSS_SITE_TRACKER"
        ).inOrder()
    }
}
