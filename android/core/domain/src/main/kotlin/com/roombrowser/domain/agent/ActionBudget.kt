package com.roombrowser.domain.agent

import kotlinx.serialization.json.JsonObject

/**
 * The ceilings one turn works under.
 *
 * This is a rate limit against HAMMERING ONE CONTROL, and nothing else. It
 * exists because a model that clicks the same button fifteen times while a page
 * sits still is spending the user's money and the site's patience for nothing.
 *
 * It is explicitly NOT a disguise: there are no randomised delays, no identity
 * rotation, no browser masking, and nothing here makes automation look human.
 * The delays are fixed and small, and they are there to stop a loop, not to
 * fool a detector.
 */
data class ActionBudget(
    /** Every tool call in a turn, refused wholesale once it is reached. */
    val maxActionsPerTurn: Int = 60,
    /** One tool, so `click` cannot eat the whole turn by itself. */
    val maxActionsPerTool: Int = 15,
    /** The least time allowed between two identical consecutive actions. */
    val identicalActionMinIntervalMs: Long = 900L,
    /** Failures in a row that end the turn outright. */
    val maxConsecutiveFailures: Int = 3
)

/** What the budget says about one action. */
sealed interface BudgetDecision {
    data object Allow : BudgetDecision

    /** The action may not run, but the turn goes on. */
    data class Refuse(val reason: String) : BudgetDecision

    /** The turn is over: a ceiling was reached or the agent is failing in a loop. */
    data class Stop(val reason: String) : BudgetDecision
}

/**
 * Tracks one turn's action budget.
 *
 * Stateful on purpose — a ceiling is a fact about what already happened — but
 * with the clock INJECTED, so every rule is testable on the JVM without a
 * device and without waiting.
 */
class ActionBudgetTracker(private val limits: ActionBudget = ActionBudget()) {

    private var actionsThisTurn = 0
    private val actionsByTool = HashMap<String, Int>()
    private var lastSignature: String? = null
    private var lastPageDigest: String? = null
    private var lastActionAtMs = 0L
    private var consecutiveFailures = 0

    val used: Int get() = actionsThisTurn

    /**
     * Decides whether [name] may run now, and RECORDS it when the answer is
     * [BudgetDecision.Allow] — so a caller cannot admit an action without it
     * counting, which is the only way a limit can be exceeded by accident.
     *
     * [pageDigest] is a canonical digest of the page as the agent last read it,
     * or null when it has not read one. The same action against an unchanged
     * page is refused as having had no effect: this is what stops the click →
     * nothing → click loop, and it is honest, because the agent has no evidence
     * anything happened.
     */
    fun admit(name: String, argsJson: String, pageDigest: String?, nowMs: Long): BudgetDecision {
        if (consecutiveFailures >= limits.maxConsecutiveFailures) {
            return BudgetDecision.Stop(
                "stopping: the last ${limits.maxConsecutiveFailures} actions all failed, so " +
                    "repeating them will not work. Explain what failed and what you would need."
            )
        }
        if (actionsThisTurn >= limits.maxActionsPerTurn) {
            return BudgetDecision.Stop(
                "stopping: this turn has used its whole action budget " +
                    "(${limits.maxActionsPerTurn}). Summarise what you found."
            )
        }
        if ((actionsByTool[name] ?: 0) >= limits.maxActionsPerTool) {
            return BudgetDecision.Refuse(
                "refused: '$name' has already run ${limits.maxActionsPerTool} times this turn, its " +
                    "limit. Try something else, or tell the user what is blocking you."
            )
        }
        val signature = signatureOf(name, argsJson)
        if (signature == lastSignature) {
            if (pageDigest != null && pageDigest == lastPageDigest) {
                return BudgetDecision.Refuse(
                    "refused: this action had no effect — the same $name already ran and the page " +
                        "has not changed since. Read the page again and choose a different action."
                )
            }
            val since = nowMs - lastActionAtMs
            if (since < limits.identicalActionMinIntervalMs) {
                return BudgetDecision.Refuse(
                    "refused: the same action ran ${since}ms ago. At least " +
                        "${limits.identicalActionMinIntervalMs}ms must pass between identical " +
                        "repeats. Read the page again first."
                )
            }
        }
        actionsThisTurn++
        actionsByTool[name] = (actionsByTool[name] ?: 0) + 1
        lastSignature = signature
        lastPageDigest = pageDigest
        lastActionAtMs = nowMs
        return BudgetDecision.Allow
    }

    /** Feeds an outcome back in. A success clears the failure run. */
    fun recordResult(ok: Boolean) {
        consecutiveFailures = if (ok) 0 else consecutiveFailures + 1
    }

    /** Between turns. A failed turn's counters must not carry into the next. */
    fun reset() {
        actionsThisTurn = 0
        actionsByTool.clear()
        lastSignature = null
        lastPageDigest = null
        lastActionAtMs = 0L
        consecutiveFailures = 0
    }

    /**
     * `tool` plus its arguments in a fixed shape: key order and formatting are
     * not the model's choice, so `{"a":1,"b":2}` and `{"b":2, "a":1}` are the
     * same action.
     */
    private fun signatureOf(name: String, argsJson: String): String =
        "$name|${canonicalArgs(argsJson)}"

    private fun canonicalArgs(argsJson: String): String = runCatching {
        when (val element = AgentJson.parseToJsonElement(argsJson.ifBlank { "{}" })) {
            is JsonObject -> element.entries
                .sortedBy { it.key }
                .joinToString(",") { "${it.key}=${it.value}" }
            else -> element.toString()
        }
    }.getOrDefault(argsJson.trim())
}
