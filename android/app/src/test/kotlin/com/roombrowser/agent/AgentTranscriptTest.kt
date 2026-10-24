package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.TurnUsage
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Where a finished answer lands in the transcript.
 *
 * Two failures this pins: an answer shown twice because the streamed bubble and
 * the finished message were both kept, and a re-asked turn whose thrown-away
 * attempt stays glued to the answer on screen while the stored row has only the
 * answer — the second is invisible from either side alone, because it is the
 * two of them disagreeing.
 */
class AgentTranscriptTest {

    @Test
    fun `the streamed answer is replaced rather than shown twice`() {
        val entries = listOf(AgentEntry.Assistant("Hello", "", streaming = true, at = 1))

        val after = runBlocking {
            AgentTranscript.answer(entries, openIndex = 0, answer = "Hello!", at = 2)
        }

        assertThat(after).hasSize(1)
        assertThat((after[0] as AgentEntry.Assistant).text).isEqualTo("Hello!")
        assertThat((after[0] as AgentEntry.Assistant).streaming).isFalse()
    }

    @Test
    fun `a re-asked turn does not keep the attempt it threw away`() {
        // Plan mode re-asks when the model answered without a step list. The
        // bubble then holds that answer plus the one that replaced it, and only
        // the second is stored.
        val entries = listOf(
            AgentEntry.Assistant("Sure, I could do that.", "", streaming = true, at = 1)
        )

        val after = runBlocking {
            AgentTranscript.answer(entries, openIndex = 0, answer = "1. Open the page", at = 2)
        }

        assertThat(after).hasSize(1)
        assertThat((after[0] as AgentEntry.Assistant).text).isEqualTo("1. Open the page")
    }

    @Test
    fun `an answer that never streamed gets its own bubble`() {
        val entries = listOf(AgentEntry.User("do it", 1))

        val after = runBlocking {
            AgentTranscript.answer(entries, openIndex = -1, answer = "Stopped.", at = 2)
        }

        assertThat(after).hasSize(2)
        assertThat(after.last()).isInstanceOf(AgentEntry.Assistant::class.java)
        assertThat((after.last() as AgentEntry.Assistant).text).isEqualTo("Stopped.")
    }

    @Test
    fun `a bubble a tool call already closed is left alone`() {
        val entries = listOf(
            AgentEntry.Assistant("Opening it.", "", streaming = false, at = 1),
            AgentEntry.Tool("c1", "navigate", "Open example.com", running = false, ok = true, summary = "ok", at = 2)
        )

        val after = runBlocking {
            AgentTranscript.answer(entries, openIndex = -1, answer = "It is open.", at = 3)
        }

        assertThat(after).hasSize(3)
        assertThat((after[0] as AgentEntry.Assistant).text).isEqualTo("Opening it.")
        assertThat((after[2] as AgentEntry.Assistant).text).isEqualTo("It is open.")
    }

    @Test
    fun `an index pointing at something other than a bubble appends`() {
        val entries = listOf(AgentEntry.Notice("Step limit reached", error = false, at = 1))

        val after = runBlocking {
            AgentTranscript.answer(entries, openIndex = 0, answer = "Done.", at = 2)
        }

        assertThat(after).hasSize(2)
        assertThat(after.last()).isInstanceOf(AgentEntry.Assistant::class.java)
        assertThat((after.last() as AgentEntry.Assistant).text).isEqualTo("Done.")
    }

    @Test
    fun `an index past the end appends rather than throwing`() {
        val entries = listOf(AgentEntry.User("hi", 1))

        val after = runBlocking {
            AgentTranscript.answer(entries, openIndex = 7, answer = "Hello.", at = 2)
        }

        assertThat(after).hasSize(2)
        assertThat((after.last() as AgentEntry.Assistant).text).isEqualTo("Hello.")
    }

    @Test
    fun `the answer keeps the thinking the bubble was showing`() {
        val entries = listOf(AgentEntry.Assistant("", "weighing it up", streaming = true, at = 1))

        val after = runBlocking {
            AgentTranscript.answer(entries, openIndex = 0, answer = "Yes.", at = 2)
        }

        assertThat((after[0] as AgentEntry.Assistant).thinking).isEqualTo("weighing it up")
    }

    @Test
    fun `a reported usage reaches the recorder and an absent one does not`() = runBlocking<Unit> {
        val recorded = mutableListOf<TurnUsage>()
        val entries = listOf(AgentEntry.User("hi", 1))

        AgentTranscript.answer(
            entries, openIndex = -1, answer = "Done.", at = 2,
            usage = TurnUsage(promptTokens = 120, completionTokens = 45),
            recordUsage = { recorded.add(it) }
        )
        assertThat(recorded).containsExactly(TurnUsage(120, 45))

        // No report → the recorder is never invoked: absence is persisted as
        // absence, never as a number.
        AgentTranscript.answer(
            entries, openIndex = -1, answer = "Done again.", at = 3,
            usage = null,
            recordUsage = { recorded.add(it) }
        )
        assertThat(recorded).hasSize(1)
    }

    @Test
    fun `a failing recorder never breaks the transcript`() = runBlocking<Unit> {
        val entries = listOf(AgentEntry.User("hi", 1))

        val after = AgentTranscript.answer(
            entries, openIndex = -1, answer = "Done.", at = 2,
            usage = TurnUsage(10, 5),
            recordUsage = { error("persistence blew up") }
        )

        assertThat(after).hasSize(2)
        assertThat((after.last() as AgentEntry.Assistant).text).isEqualTo("Done.")
    }
}
