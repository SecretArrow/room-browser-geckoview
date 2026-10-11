package com.roombrowser.agent

import com.roombrowser.domain.agent.ActionBudgetTracker
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.BudgetDecision
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult
import java.security.MessageDigest

/**
 * Puts one turn's action budget in front of another executor.
 *
 * A DECORATOR rather than a rule inside `AgentToolExecutor`, because the budget
 * is a property of ONE TURN: the executor is built fresh for each turn, and the
 * plan-mode and headless gates wrap the same one — the budget sits inside them
 * so a route in cannot skip it, while a plan-mode refusal does not spend an
 * action on something that never ran.
 *
 * The page digest is taken from the last READ of the page, not measured before
 * every action: a probe per step would cost a script round-trip for a fact the
 * executor already measures around the mutating tools, and the digest only has
 * to be good enough to tell "the page moved" from "it did not".
 */
class ActionBudgetGate(
    private val inner: ToolExecutor,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val tracker: ActionBudgetTracker = ActionBudgetTracker()
) : ToolExecutor {

    /** The page as the agent last read it; null until it has read one. */
    private var pageDigest: String? = null

    /** Set once a ceiling ended the turn; the loop's stop hooks read it. */
    var stopped: Boolean = false
        private set

    /** The sentence to end the turn with when [stopped] is set. */
    var stopReason: String = ""
        private set

    override suspend fun execute(name: String, argsJson: String): ToolResult {
        when (val decision = tracker.admit(name, argsJson, pageDigest, nowMs())) {
            is BudgetDecision.Allow -> Unit
            // The action is refused, the turn goes on: the model is told why
            // and gets to try something else with the steps it has left.
            is BudgetDecision.Refuse -> return ToolResult(false, decision.reason)
            is BudgetDecision.Stop -> {
                stopped = true
                stopReason = decision.reason
                return ToolResult(false, decision.reason)
            }
        }
        val result = inner.execute(name, argsJson)
        tracker.recordResult(result.ok)
        if (name == AgentTools.READ_PAGE || name == AgentTools.NAVIGATE) {
            pageDigest = digestOf(result.output)
        }
        return result
    }

    private fun digestOf(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
