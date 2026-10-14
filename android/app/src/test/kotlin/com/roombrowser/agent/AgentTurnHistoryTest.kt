package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.repo.AgentSettings
import com.roombrowser.domain.agent.AgentPlan
import com.roombrowser.domain.agent.ChatMessage
import org.junit.Test

/**
 * JVM tests of one turn's message assembly ([buildTurnHistory]).
 *
 * The standing context is the app's easiest promise to break silently: the
 * user switches it on ONCE and then expects it in EVERY request, with nothing
 * on screen to confirm it actually rode along. There was no test here at all
 * before — the e2e suite proved the toggle PERSISTED, never that it was SENT —
 * which is how "sometimes it is not used as the basis for the chat" could
 * survive a green pipeline.
 */
class AgentTurnHistoryTest {

    private val prompt = "You are a browsing agent."
    private val prior = listOf(
        ChatMessage(role = "user", content = "first"),
        ChatMessage(role = "assistant", content = "answer")
    )

    private fun history(
        settings: AgentSettings = AgentSettings(),
        pageSnapshot: String? = null,
        attachments: List<AgentAttachment> = emptyList(),
        request: String = "do the thing",
        plan: List<AgentPlan.Item> = emptyList()
    ) = buildTurnHistory(prompt, prior, pageSnapshot, settings, attachments, request, plan)

    @Test
    fun `the standing context is sent when the switch is on`() {
        val messages = history(
            AgentSettings(defaultContext = "Answer in Indonesian.", useDefaultContext = true)
        )

        assertThat(messages.any { it.content.orEmpty().contains("Answer in Indonesian.") }).isTrue()
    }

    @Test
    fun `the standing context is withheld when the switch is off`() {
        val messages = history(
            AgentSettings(defaultContext = "Answer in Indonesian.", useDefaultContext = false)
        )

        // Off is not the same as deleted: the text is kept, and kept out.
        assertThat(messages.none { it.content.orEmpty().contains("Answer in Indonesian.") }).isTrue()
    }

    @Test
    fun `a switch left on over blank text sends no empty context block`() {
        val messages = history(AgentSettings(defaultContext = "   ", useDefaultContext = true))

        assertThat(messages.none { it.content.orEmpty().contains("Standing context") }).isTrue()
    }

    @Test
    fun `the context is trimmed before it is sent`() {
        val messages = history(
            AgentSettings(defaultContext = "  be terse  ", useDefaultContext = true)
        )

        assertThat(messages.any { it.content.orEmpty().endsWith("be terse") }).isTrue()
        assertThat(messages.none { it.content.orEmpty().contains("  be terse") }).isTrue()
    }

    @Test
    fun `the order is system then prior turns then snapshot then context then request`() {
        val messages = history(
            settings = AgentSettings(defaultContext = "be terse", useDefaultContext = true),
            pageSnapshot = "PAGE SNAPSHOT"
        )

        assertThat(messages.first().role).isEqualTo("system")
        assertThat(messages[1].content).isEqualTo("first")
        assertThat(messages[2].content).isEqualTo("answer")
        assertThat(messages[3].content).contains("PAGE SNAPSHOT")
        assertThat(messages[4].content).contains("be terse")
        // The request stays the last word: nothing is appended after it.
        assertThat(messages.last().content).isEqualTo("do the thing")
    }

    @Test
    fun `the context is re-sent on a turn that already has history`() {
        // A provider keeps no memory between turns, so a context that only rode
        // the first request is gone by the third. This is the third.
        val messages = history(
            settings = AgentSettings(defaultContext = "be terse", useDefaultContext = true),
            request = "and now the third"
        )

        assertThat(messages.any { it.content.orEmpty().contains("be terse") }).isTrue()
    }

    @Test
    fun `the page snapshot is omitted when the user did not include the page`() {
        val messages = history(
            settings = AgentSettings(defaultContext = "be terse", useDefaultContext = true),
            pageSnapshot = null
        )

        assertThat(messages.none { it.content.orEmpty().contains("The user's request follows.") }).isTrue()
        // ...and the context still rides along without it.
        assertThat(messages.any { it.content.orEmpty().contains("be terse") }).isTrue()
    }

    @Test
    fun `the step list rides with the prior turns, ahead of the snapshot`() {
        val messages = history(
            settings = AgentSettings(defaultContext = "be terse", useDefaultContext = true),
            pageSnapshot = "PAGE SNAPSHOT",
            plan = listOf(
                AgentPlan.Item("open the site", AgentPlan.Status.DONE),
                AgentPlan.Item("sign in", AgentPlan.Status.IN_PROGRESS)
            )
        )

        assertThat(messages.first().role).isEqualTo("system")
        assertThat(messages[1].content).isEqualTo("first")
        assertThat(messages[2].content).isEqualTo("answer")
        // The list is this conversation's own state, so it sits with its rows,
        // and the pinned tail behind it keeps the order it had.
        assertThat(messages[3].content).contains("[>] sign in")
        assertThat(messages[4].content).contains("PAGE SNAPSHOT")
        assertThat(messages[5].content).contains("be terse")
        assertThat(messages.last().content).isEqualTo("do the thing")
    }

    @Test
    fun `a chat with no list is sent without one`() {
        val messages = history()

        assertThat(messages.none { it.content.orEmpty().contains("step list") }).isTrue()
    }
}
