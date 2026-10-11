package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The ceilings are the only thing standing between a stuck model and a control
 * being hammered, so each rule is pinned separately — including the ones that
 * must NOT fire, because a rate limit that refuses good work is its own defect.
 *
 * The clock is injected, so nothing here waits.
 */
class ActionBudgetTest {

    private fun reasonOf(decision: BudgetDecision): String = when (decision) {
        is BudgetDecision.Refuse -> decision.reason
        is BudgetDecision.Stop -> decision.reason
        BudgetDecision.Allow -> ""
    }

    @Test
    fun the_turn_budget_stops_the_turn_rather_than_refusing_one_action() {
        val tracker = ActionBudgetTracker(ActionBudget(maxActionsPerTurn = 2))

        assertThat(tracker.admit("click", "{\"ref\":1}", null, 0)).isEqualTo(BudgetDecision.Allow)
        assertThat(tracker.admit("scroll", "{\"direction\":\"down\"}", null, 5_000))
            .isEqualTo(BudgetDecision.Allow)

        val third = tracker.admit("read_page", "{}", null, 10_000)
        assertThat(third).isInstanceOf(BudgetDecision.Stop::class.java)
        assertThat(reasonOf(third)).contains("action budget")
    }

    @Test
    fun one_tool_cannot_use_up_the_turn() {
        val tracker = ActionBudgetTracker(ActionBudget(maxActionsPerTool = 2))

        assertThat(tracker.admit("click", "{\"ref\":1}", null, 0)).isEqualTo(BudgetDecision.Allow)
        assertThat(tracker.admit("click", "{\"ref\":2}", null, 5_000)).isEqualTo(BudgetDecision.Allow)

        val third = tracker.admit("click", "{\"ref\":3}", null, 10_000)
        assertThat(third).isInstanceOf(BudgetDecision.Refuse::class.java)
        assertThat(reasonOf(third)).contains("limit")
    }

    @Test
    fun the_same_action_on_an_unchanged_page_is_refused_as_having_had_no_effect() {
        val tracker = ActionBudgetTracker()

        assertThat(tracker.admit("click", "{\"ref\":5}", "page-1", 0)).isEqualTo(BudgetDecision.Allow)

        val repeat = tracker.admit("click", "{\"ref\":5}", "page-1", 20_000)
        assertThat(repeat).isInstanceOf(BudgetDecision.Refuse::class.java)
        assertThat(reasonOf(repeat)).contains("no effect")
    }

    @Test
    fun the_same_action_is_allowed_again_once_the_page_has_changed() {
        val tracker = ActionBudgetTracker()

        assertThat(tracker.admit("scroll", "{\"direction\":\"down\"}", "page-1", 0))
            .isEqualTo(BudgetDecision.Allow)
        // A different digest is evidence the page moved, so the repeat is work.
        assertThat(tracker.admit("scroll", "{\"direction\":\"down\"}", "page-2", 2_000))
            .isEqualTo(BudgetDecision.Allow)
    }

    @Test
    fun identical_consecutive_actions_are_spaced_apart_even_when_the_page_changed() {
        val tracker = ActionBudgetTracker(ActionBudget(identicalActionMinIntervalMs = 1_000))

        assertThat(tracker.admit("scroll", "{\"direction\":\"down\"}", "page-1", 0))
            .isEqualTo(BudgetDecision.Allow)

        val tooSoon = tracker.admit("scroll", "{\"direction\":\"down\"}", "page-2", 200)
        assertThat(tooSoon).isInstanceOf(BudgetDecision.Refuse::class.java)
        assertThat(reasonOf(tooSoon)).contains("passed between identical repeats")
    }

    @Test
    fun a_different_action_is_never_spaced_out() {
        val tracker = ActionBudgetTracker()

        assertThat(tracker.admit("click", "{\"ref\":5}", "page-1", 0)).isEqualTo(BudgetDecision.Allow)
        assertThat(tracker.admit("click", "{\"ref\":6}", "page-1", 1)).isEqualTo(BudgetDecision.Allow)
    }

    @Test
    fun argument_key_order_does_not_make_a_different_action() {
        val tracker = ActionBudgetTracker()

        assertThat(tracker.admit("click", "{\"a\":1,\"b\":2}", "page-1", 0))
            .isEqualTo(BudgetDecision.Allow)

        val reordered = tracker.admit("click", "{\"b\":2, \"a\":1}", "page-1", 20_000)
        assertThat(reordered).isInstanceOf(BudgetDecision.Refuse::class.java)
        assertThat(reasonOf(reordered)).contains("no effect")
    }

    @Test
    fun repeated_failure_stops_the_turn() {
        val tracker = ActionBudgetTracker(ActionBudget(maxConsecutiveFailures = 2))

        tracker.admit("click", "{\"ref\":1}", null, 0)
        tracker.recordResult(false)
        tracker.admit("scroll", "{\"direction\":\"down\"}", null, 1_000)
        tracker.recordResult(false)

        val decision = tracker.admit("read_page", "{}", null, 2_000)
        assertThat(decision).isInstanceOf(BudgetDecision.Stop::class.java)
        assertThat(reasonOf(decision)).contains("all failed")
    }

    @Test
    fun a_success_clears_the_failure_run() {
        val tracker = ActionBudgetTracker(ActionBudget(maxConsecutiveFailures = 2))

        tracker.admit("click", "{\"ref\":1}", null, 0)
        tracker.recordResult(false)
        tracker.admit("scroll", "{\"direction\":\"down\"}", null, 1_000)
        tracker.recordResult(true)

        assertThat(tracker.admit("read_page", "{}", null, 2_000)).isEqualTo(BudgetDecision.Allow)
    }

    @Test
    fun reset_returns_the_tracker_to_a_fresh_turn() {
        val tracker = ActionBudgetTracker(ActionBudget(maxActionsPerTurn = 1))

        tracker.admit("click", "{\"ref\":1}", null, 0)
        tracker.reset()

        assertThat(tracker.used).isEqualTo(0)
        assertThat(tracker.admit("click", "{\"ref\":1}", null, 0)).isEqualTo(BudgetDecision.Allow)
    }
}
