package com.roombrowser.data.repo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM tests of the mode the chat header shows.
 *
 * The three modes the header offers are DERIVED from the two flags that are
 * persisted, so this is the one place that decides what a combination means.
 * It matters because the header is where the user reads what the agent is
 * allowed to do; a mode that disagreed with the gate would say "Ask" over a
 * turn that never asks, or "YOLO" over one that refuses every action.
 */
class AgentModeTest {

    @Test
    fun `the shipped defaults are Plan`() {
        assertThat(AgentSettings().chatMode).isEqualTo(AgentMode.PLAN)
    }

    @Test
    fun `acting with the prompt on is Ask`() {
        val settings = AgentSettings(chatOnly = false, yolo = false, confirmActions = true)
        assertThat(settings.chatMode).isEqualTo(AgentMode.ASK)
    }

    @Test
    fun `acting with the prompt off is still Ask, not YOLO`() {
        // confirmActions off is not a mode: the approval prompt's third answer
        // is what writes YOLO, and only that switch may claim it.
        val settings = AgentSettings(chatOnly = false, yolo = false, confirmActions = false)
        assertThat(settings.chatMode).isEqualTo(AgentMode.ASK)
    }

    @Test
    fun `always allow is YOLO`() {
        val settings = AgentSettings(chatOnly = false, yolo = true)
        assertThat(settings.chatMode).isEqualTo(AgentMode.YOLO)
    }

    @Test
    fun `read only wins over always allow`() {
        // A Plan turn refuses an acting tool before the approval prompt could
        // be raised, so YOLO cannot do what it says beside it — and a header
        // that claimed YOLO there would be promising an action that never runs.
        val settings = AgentSettings(chatOnly = true, yolo = true)
        assertThat(settings.chatMode).isEqualTo(AgentMode.PLAN)
    }

    @Test
    fun `every mode has a name and an explanation`() {
        AgentMode.entries.forEach { mode ->
            assertThat(mode.title).isNotEmpty()
            assertThat(mode.blurb).isNotEmpty()
        }
    }
}
