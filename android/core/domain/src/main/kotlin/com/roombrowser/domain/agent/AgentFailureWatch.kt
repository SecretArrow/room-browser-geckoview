package com.roombrowser.domain.agent

/**
 * Notices one tool failing twice in a row and refuses the third attempt,
 * answering it with a correction instead of running it again.
 *
 * The action budget already ends a turn after three failures of ANY kind. That
 * is the right ceiling but a blunt one — two different tools failing once each
 * is not a loop — and it arrives too late to be useful: by the time it stops the
 * turn, the model has already spent the steps. This watches per TOOL, refuses
 * before the repeat is executed, and words the refusal so the model knows what
 * to do instead.
 *
 * A successful read_page or navigate clears every streak: a fresh view of the
 * page is new evidence, and it is exactly the way out the refusal names, so it
 * has to actually work.
 */
class AgentFailureWatch(private val threshold: Int = DEFAULT_THRESHOLD) {

    private val failuresInARow = HashMap<String, Int>()
    private val lastFailure = HashMap<String, String>()

    /**
     * The correction to return INSTEAD of running [name], or null when the call
     * may go ahead. Never records anything: a refused call did not run.
     */
    fun refusal(name: String): String? {
        val count = failuresInARow[name] ?: 0
        if (count < threshold) return null
        val last = lastFailure[name]
        return "refused: '$name' has failed $count times in a row, so the same call will fail " +
            "again. Do not repeat it — read the page again and take a different route, or tell " +
            "the user what is blocking you." +
            (last?.let { " Last error: $it" } ?: "")
    }

    /** Feeds one executed call's outcome in. */
    fun record(name: String, ok: Boolean, output: String) {
        if (ok) {
            if (name == AgentTools.READ_PAGE || name == AgentTools.NAVIGATE) {
                failuresInARow.clear()
                lastFailure.clear()
            } else {
                failuresInARow.remove(name)
                lastFailure.remove(name)
            }
            return
        }
        failuresInARow[name] = (failuresInARow[name] ?: 0) + 1
        lastFailure[name] = UntrustedContent.payload(output).replace('\n', ' ').trim().take(MAX_REASON_CHARS)
    }

    /** Between turns: a failure in a previous turn is not this turn's loop. */
    fun reset() {
        failuresInARow.clear()
        lastFailure.clear()
    }

    companion object {
        const val DEFAULT_THRESHOLD = 2
        private const val MAX_REASON_CHARS = 240
    }
}
