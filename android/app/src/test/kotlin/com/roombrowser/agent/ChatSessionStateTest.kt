package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentPlan
import org.junit.Test

/**
 * [ChatSessionState] — the object that makes "one chat's turn cannot write into
 * another chat" a structural fact rather than a rule someone remembers.
 *
 * The bugs this class exists to end were both silent: a rebind that re-pointed
 * one shared list spliced another chat's rows into a live turn, and a chat
 * switch that carried a running flag with it made a finished chat look busy.
 * Neither crashed; both just showed the wrong conversation. So the assertions
 * here are about SEPARATION — two states never share a list, a row, a plan or a
 * running flag — and about the one operation that deliberately moves data
 * between two states, [ChatSessionState.adoptFrom].
 */
class ChatSessionStateTest {

    @Test
    fun two_chats_never_share_a_transcript() {
        val a = ChatSessionState(1L)
        val b = ChatSessionState(2L)

        a.appendEntry(AgentEntry.User("hello from A", at = 1L))

        assertThat(a.entries).hasSize(1)
        assertThat(b.entries).isEmpty()
    }

    @Test
    fun a_running_chat_does_not_make_its_neighbour_look_busy() {
        val a = ChatSessionState(1L)
        val b = ChatSessionState(2L)

        a.running = true

        assertThat(a.running).isTrue()
        assertThat(b.running).isFalse()
    }

    @Test
    fun each_chat_keeps_its_own_plan() {
        val a = ChatSessionState(1L)
        val b = ChatSessionState(2L)
        val step = AgentPlan.Item("Fetch the page", AgentPlan.Status.DONE)

        a.plan = listOf(step)

        assertThat(a.plan).containsExactly(step)
        assertThat(b.plan).isEmpty()
    }

    @Test
    fun adoption_moves_the_turn_and_empties_the_scratch_state() {
        // The shape `send` produces on a tab with no chat yet: the turn starts
        // on the null-keyed scratch state, then resolves a real session row.
        val scratch = ChatSessionState(null)
        val resolved = ChatSessionState(7L)
        scratch.appendEntry(AgentEntry.User("first message", at = 1L))
        scratch.running = true
        scratch.statusLine = "Thinking…"

        resolved.adoptFrom(scratch)

        // The row and the turn's live state are now the real chat's.
        assertThat(resolved.entries.map { (it as AgentEntry.User).text })
            .containsExactly("first message")
        assertThat(resolved.running).isTrue()
        assertThat(resolved.statusLine).isEqualTo("Thinking…")
        // ...and the scratch state is inert, so it cannot stay pinned as a
        // running state in the controller's LRU.
        assertThat(scratch.entries).isEmpty()
        assertThat(scratch.running).isFalse()
        assertThat(scratch.statusLine).isNull()
    }

    @Test
    fun adoption_does_not_clobber_a_plan_the_resolved_chat_already_has() {
        // `plan` belongs to the chat, not the turn: adopting a turn onto a chat
        // that already loaded its window must not erase what was loaded.
        val scratch = ChatSessionState(null)
        val resolved = ChatSessionState(7L)
        val loaded = AgentPlan.Item("Already planned", AgentPlan.Status.IN_PROGRESS)
        resolved.plan = listOf(loaded)

        resolved.adoptFrom(scratch)

        assertThat(resolved.plan).containsExactly(loaded)
    }

    @Test
    fun adopting_onto_itself_is_a_no_op() {
        val state = ChatSessionState(1L)
        state.appendEntry(AgentEntry.User("keep me", at = 1L))

        state.adoptFrom(state)

        assertThat(state.entries).hasSize(1)
    }

    @Test
    fun a_streamed_delta_updates_one_row_in_place() {
        // The perf half of the contract: a token must not re-materialize the
        // list, or every delta would invalidate every row.
        val state = ChatSessionState(1L)
        state.appendEntry(AgentEntry.User("ask", at = 1L))
        val index = state.upsertStreaming(-1, "par", "")

        val sameIndex = state.upsertStreaming(index, "tial", "")

        assertThat(sameIndex).isEqualTo(index)
        assertThat(state.entries).hasSize(2)
        assertThat((state.entries[1] as AgentEntry.Assistant).text).isEqualTo("partial")
    }

    @Test
    fun closing_the_streamed_row_strips_the_fence_and_answers_no_open_bubble() {
        val state = ChatSessionState(1L)
        val index = state.upsertStreaming(-1, "done", "")

        val next = state.finalizeStreaming(index)

        assertThat(next).isEqualTo(-1)
        assertThat((state.entries[index] as AgentEntry.Assistant).streaming).isFalse()
    }

    @Test
    fun a_tool_update_for_an_unknown_call_is_a_no_op() {
        val state = ChatSessionState(1L)

        state.updateTool("call-that-never-landed") { it }

        assertThat(state.entries).isEmpty()
    }
}
