package com.roombrowser.agent

import com.roombrowser.domain.agent.TurnUsage

/**
 * Where the model's answer lands in the transcript.
 *
 * WHY THIS IS NOT INLINE IN THE CONTROLLER: the answer reaches the panel TWICE
 * — once as the streamed deltas that build the bubble the user watches appear,
 * and once as the finished message. Replacing the open bubble with the finished
 * one is what stops an answer being shown twice; it is also what stops a
 * re-asked turn keeping the attempt it threw away, and that one no screen test
 * can see, because it is a disagreement between the screen and the stored row.
 * So the rule lives here, where a JVM test can reach it.
 *
 * The same object is the seam where the answer's REPORTED token usage reaches
 * the assistant row: [answer] hands a non-null [TurnUsage] to the injected
 * recorder, so the count travels with the exact row it belongs to while this
 * object stays dependency-free (the repo + row id are bound by the caller —
 * only the caller knows the id its `addMessage` returned).
 */
internal object AgentTranscript {

    /**
     * The transcript once the finished answer arrives, [openIndex] being the
     * bubble still being streamed into (-1 when there is none).
     *
     * An open bubble is REPLACED — its text is the deltas of this same answer,
     * so appending would duplicate it and the discarded text of a re-asked turn
     * would survive. Anything else (a turn that never streamed, or whose bubble
     * a tool call already closed) gets a bubble of its own, because there the
     * answer really is a new line of the conversation.
     *
     * @param usage the token usage the provider reported for THIS answer
     *   (AgentEvent.FinalAnswer.usage). Written onto the assistant row via
     *   [recordUsage]; absent, or no recorder wired, and the row's token
     *   columns simply stay null — they are never filled with an estimate.
     * @param recordUsage the persistence hook, bound by the caller to
     *   `repo.setMessageUsage(rowId, …)`. Called at most once, only when
     *   [usage] is non-null; a failure inside it is swallowed — bookkeeping
     *   must never break the chat.
     */
    suspend fun answer(
        entries: List<AgentEntry>,
        openIndex: Int,
        answer: String,
        at: Long,
        usage: TurnUsage? = null,
        recordUsage: suspend (TurnUsage) -> Unit = {}
    ): List<AgentEntry> {
        // parent wires usage from the controller's FinalAnswer branch — the
        // call site there passes event.usage and binds [recordUsage] to the
        // repo + the id of the assistant row it writes for this answer.
        usage?.let { runCatching { recordUsage(it) } }
        val open = openIndex.takeIf { it >= 0 && it < entries.size }
            ?.let { entries[it] as? AgentEntry.Assistant }
            ?.takeIf { it.streaming }
            ?: return entries + AgentEntry.Assistant(
                text = answer,
                thinking = "",
                streaming = false,
                at = at
            )
        return entries.toMutableList().apply {
            set(openIndex, open.copy(text = answer, streaming = false))
        }
    }
}
