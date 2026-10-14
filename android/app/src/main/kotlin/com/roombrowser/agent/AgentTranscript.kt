package com.roombrowser.agent

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
     */
    fun answer(
        entries: List<AgentEntry>,
        openIndex: Int,
        answer: String,
        at: Long
    ): List<AgentEntry> {
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
