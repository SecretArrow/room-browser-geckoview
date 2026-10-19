package com.roombrowser.agent.ui

import com.google.common.truth.Truth.assertThat
import com.roombrowser.agent.AgentEntry
import org.junit.Test

/**
 * [TranscriptKeyCache] — the memo that keeps a streamed token from re-running
 * the O(n) [transcriptKeys] pass.
 *
 * A wrong HIT here is worse than a miss: it hands one row's LazyColumn key to
 * another row (state follows the wrong survivor), and a key that repeats
 * itself outright is a crash. A wrong MISS is only wasted work. So the hits
 * are pinned to be the very same list instance the first computation returned,
 * and every shape change a real conversation produces — a row landing, a chat
 * switching, the list shrinking — is pinned to recompute.
 */
class TranscriptKeyCacheTest {

    @Test
    fun `a streamed token reuses the cached key list as-is`() {
        val cache = TranscriptKeyCache()
        val row = AgentEntry.Assistant("hel", thinking = "", streaming = true, at = 5L)
        val first = cache.keysFor(listOf(row))
        // The controller copy()-replaces the whole list per delta, exactly
        // like this.
        val grown = listOf(row.copy(text = "hello"))
        assertThat(cache.keysFor(grown)).isSameInstanceAs(first)
    }

    @Test
    fun `a landed row recomputes the keys`() {
        val cache = TranscriptKeyCache()
        val start = listOf(AgentEntry.User("hi", at = 1L))
        cache.keysFor(start)
        val grown = start + AgentEntry.Assistant("yo", thinking = "", streaming = false, at = 2L)
        assertThat(cache.keysFor(grown)).isEqualTo(transcriptKeys(grown))
    }

    @Test
    fun `a switched chat recomputes rather than reusing the old keys`() {
        val cache = TranscriptKeyCache()
        val first = listOf(
            AgentEntry.User("hi", at = 1L),
            AgentEntry.Notice("x", error = false, at = 2L)
        )
        cache.keysFor(first)
        // Same size, different rows: only the stem scan can tell this apart
        // from an in-place streaming update.
        val second = listOf(
            AgentEntry.User("yo", at = 9L),
            AgentEntry.Notice("y", error = true, at = 10L)
        )
        val keys = cache.keysFor(second)
        assertThat(keys).isEqualTo(transcriptKeys(second))
        assertThat(keys).isNotEqualTo(transcriptKeys(first))
    }

    @Test
    fun `same-millisecond rows keep their distinct keys through a hit`() {
        val cache = TranscriptKeyCache()
        val rows = listOf(
            AgentEntry.User("a", at = 7L),
            AgentEntry.User("b", at = 7L),
            AgentEntry.User("c", at = 7L)
        )
        val keys = cache.keysFor(rows)
        assertThat(keys.toSet()).hasSize(3)
        // An in-place copy among the repeats must not disturb any of them.
        val grown = listOf(rows[0], rows[1].copy(text = "b2"), rows[2])
        assertThat(cache.keysFor(grown)).isSameInstanceAs(keys)
    }

    @Test
    fun `a tool finishing keeps its key through the cache`() {
        val cache = TranscriptKeyCache()
        val running = AgentEntry.Tool(
            callId = "call-1",
            name = "click",
            label = "Click",
            running = true,
            ok = false,
            summary = "",
            at = 1L
        )
        val keys = cache.keysFor(listOf(running))
        assertThat(keys[0]).contains("call-1")
        val done = running.copy(running = false, ok = true, summary = "clicked")
        assertThat(cache.keysFor(listOf(done))).isSameInstanceAs(keys)
    }

    @Test
    fun `a shorter replacement list recomputes`() {
        // A switch to a shorter chat is the one shape the stem scan cannot
        // vouch for; it must fall through to a full pass.
        val cache = TranscriptKeyCache()
        cache.keysFor(listOf(AgentEntry.User("a", at = 1L), AgentEntry.User("b", at = 2L)))
        val shorter = listOf(AgentEntry.User("z", at = 9L))
        assertThat(cache.keysFor(shorter)).isEqualTo(transcriptKeys(shorter))
    }

    @Test
    fun `the same list object is a pure hit`() {
        val cache = TranscriptKeyCache()
        val rows = listOf(AgentEntry.User("a", at = 1L))
        val keys = cache.keysFor(rows)
        assertThat(cache.keysFor(rows)).isSameInstanceAs(keys)
    }
}
