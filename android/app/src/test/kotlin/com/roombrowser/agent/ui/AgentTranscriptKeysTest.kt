package com.roombrowser.agent.ui

import com.google.common.truth.Truth.assertThat
import com.roombrowser.agent.AgentEntry
import org.junit.Test

/**
 * The transcript's LazyColumn keys.
 *
 * A duplicate key is not a cosmetic problem — LazyColumn throws on it and the
 * agent panel is gone — while an unstable key throws away item state on every
 * frame. The entries carry only a timestamp (plus a call id for tools), so the
 * interesting cases are the ones where a timestamp repeats.
 */
class AgentTranscriptKeysTest {

    @Test
    fun `one key per entry`() {
        val entries = listOf(
            AgentEntry.User("hi", at = 1L),
            AgentEntry.Assistant("hello", thinking = "", streaming = false, at = 2L),
            AgentEntry.Notice("done", error = false, at = 3L)
        )
        assertThat(transcriptKeys(entries)).hasSize(3)
    }

    @Test
    fun `the same transcript keys the same way twice`() {
        val entries = listOf(
            AgentEntry.User("hi", at = 1L),
            AgentEntry.Assistant("hello", thinking = "", streaming = false, at = 2L)
        )
        assertThat(transcriptKeys(entries)).isEqualTo(transcriptKeys(entries))
    }

    @Test
    fun `entries written in the same millisecond still get distinct keys`() {
        // Two rows inserted back to back share a timestamp; a naive
        // "role:timestamp" key would collide here and crash the list.
        val entries = listOf(
            AgentEntry.User("hi", at = 7L),
            AgentEntry.User("hi", at = 7L),
            AgentEntry.User("hi", at = 7L)
        )
        val keys = transcriptKeys(entries)
        assertThat(keys.toSet()).hasSize(3)
    }

    @Test
    fun `a tool keys on its call id, not its timestamp`() {
        val entries = listOf(
            tool(callId = "a", at = 7L),
            tool(callId = "b", at = 7L)
        )
        val keys = transcriptKeys(entries)
        assertThat(keys.toSet()).hasSize(2)
        assertThat(keys[0]).contains("a")
    }

    @Test
    fun `a streaming answer keeps its key as the text grows`() {
        // Keying on content would re-create the item on every token, which is
        // what collapses an expanded "Thought process" mid-answer.
        val start = AgentEntry.Assistant("", thinking = "", streaming = true, at = 5L)
        val later = start.copy(text = "a whole answer", thinking = "some thinking")
        assertThat(transcriptKeys(listOf(later))).isEqualTo(transcriptKeys(listOf(start)))
    }

    @Test
    fun `a new chat does not reuse the previous chat's keys`() {
        // Same shape, different timestamps: the second chat's entries must not
        // answer to the first chat's keys, or the panel would hand them the
        // remembered state of the transcript that was just replaced.
        val first = listOf(AgentEntry.User("hi", at = 1L))
        val second = listOf(AgentEntry.User("hi", at = 2L))
        assertThat(transcriptKeys(second)).isNotEqualTo(transcriptKeys(first))
    }

    private fun tool(callId: String, at: Long) = AgentEntry.Tool(
        callId = callId,
        name = "click",
        label = "Click",
        running = false,
        ok = true,
        summary = "",
        at = at
    )
}
