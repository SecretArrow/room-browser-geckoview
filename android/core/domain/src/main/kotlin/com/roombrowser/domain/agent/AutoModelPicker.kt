package com.roombrowser.domain.agent

/**
 * Picks the model an AUTO task runs on.
 *
 * AUTO has to mean something stronger than "the first name in the provider's
 * /models response": that response is a list of offers, and an offer that the
 * account cannot call, or that the server has no weights for, fails at the
 * first turn — which for a scheduled task is a failure nobody is watching.
 * So the candidates are PROBED, in order, and the first one that really answers
 * is the one used.
 *
 * The provider's own default model goes first: it is the model a person chose,
 * and it should win whenever it works. Tool-capable families come next, because
 * a browser agent that cannot call tools can do almost nothing, then the rest —
 * a model that only speaks the text contract is still usable ([PromptToolGateway]
 * adapts it).
 *
 * Pure logic with the call injected, so the ORDER and the bounding are unit
 * tested without a network.
 */
object AutoModelPicker {

    /**
     * How many models may be probed before giving up. A probe is a real call, so
     * an AUTO task must not walk a hundred-model list at its own schedule's
     * expense — the first few are enough to answer "is anything working here",
     * and none of them answering is itself the answer.
     */
    const val MAX_PROBES = 3

    /** The probe's whole conversation: one word is enough to prove a model
     *  answers, and it keeps the cost of an AUTO run negligible. */
    val PROBE_MESSAGES: List<ChatMessage> = listOf(ChatMessage(role = "user", content = "Reply with OK."))

    /** Candidate model ids, best first, without repeats. */
    fun candidates(defaultModel: String?, listed: List<String>): List<String> {
        val ordered = buildList {
            defaultModel?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            addAll(listed.mapNotNull { it.trim().takeIf(String::isNotEmpty) })
        }
        return ordered.distinct().sortedByDescending { ToolCapableModels.supports(it) }
    }

    /**
     * The first candidate [probe] accepts, or null when none of the probed ones
     * did. A probe that throws counts as a refusal, not as an error to surface:
     * a model the provider will not answer for is exactly what AUTO is looking
     * past, and the caller reports "none of them answered" with the last reason.
     */
    suspend fun firstWorking(candidates: List<String>, probe: suspend (String) -> Boolean): String? =
        candidates.take(MAX_PROBES).firstOrNull { model ->
            runCatching { probe(model) }.getOrDefault(false)
        }

    /**
     * [firstWorking] against a real gateway: the list comes from the provider,
     * and the probe is one short turn, so a model that answers at all is a model
     * this account can actually call.
     *
     * A list that cannot be fetched is not a failure — [configured] alone is
     * then the whole set of candidates, which is the right answer when a
     * provider has no `/models` endpoint but does have a working default.
     */
    suspend fun firstWorkingOn(gateway: AgentGateway, configured: String?): String? {
        val listed = runCatching { gateway.listModels() }.getOrDefault(emptyList())
        return firstWorking(candidates(configured, listed)) { candidate ->
            gateway.chat(
                ChatRequest(model = candidate, messages = PROBE_MESSAGES, tools = null),
                events = {}
            )
            true
        }
    }
}
