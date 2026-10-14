package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentPlan
import org.junit.Test

/**
 * The shape of a scheduled run: the config it drives the loop with, and the one
 * line its row ends up carrying.
 *
 * The list is required here for the same reason it is in a chat — the run has to
 * say what it is doing before it does it — and the notices a chat turn shows
 * inline have no transcript to land in, so they ride out on the row instead.
 */
class HeadlessAiTaskRunTest {

    @Test
    fun a_scheduled_run_may_not_act_before_it_has_written_its_list() {
        assertThat(scheduledRunConfig("m", 10, 0.2, "p").planBeforeActions).isTrue()
    }

    @Test
    fun a_scheduled_run_is_not_re_asked_for_a_list_as_its_answer() {
        // There is no Plan mode in a scheduled run, so a plain answer with no
        // list is a finished run rather than one to be asked again.
        assertThat(scheduledRunConfig("m", 10, 0.2, "p").planIsTheAnswer).isFalse()
    }

    @Test
    fun the_model_and_the_budget_still_come_from_the_task() {
        val config = scheduledRunConfig("glm-4.6", 40, 0.7, "system")
        assertThat(config.model).isEqualTo("glm-4.6")
        assertThat(config.steps).isEqualTo(40)
        assertThat(config.temperature).isEqualTo(0.7)
        assertThat(config.systemPrompt).isEqualTo("system")
    }

    @Test
    fun an_answer_with_nothing_to_add_is_the_summary_on_its_own() {
        assertThat(completedSummary("Booked it.", emptyList())).isEqualTo("Booked it.")
    }

    @Test
    fun the_open_step_count_rides_out_on_the_summary() {
        val notice = AgentPlan.completionNotice(
            listOf(
                AgentPlan.Item("look the date up", AgentPlan.Status.DONE),
                AgentPlan.Item("book the table", AgentPlan.Status.PENDING)
            )
        )

        val summary = completedSummary("Halfway.", listOf(notice))

        assertThat(summary).contains("Halfway.")
        assertThat(summary).contains("1 of 2")
        assertThat(summary).contains("still open")
    }

    @Test
    fun the_answer_comes_before_what_the_loop_noticed() {
        val summary = completedSummary(
            "Done.",
            listOf("Step limit reached (75) — wrapping up.", "Plan: 0 of 2 steps done — 2 still open.")
        )

        assertThat(summary.startsWith("Done. Step limit reached")).isTrue()
    }
}
