package com.roombrowser.domain.agent

/**
 * Why the agent is not moving right now.
 *
 * A pause is always honoured BETWEEN steps. Nothing here can interrupt a tool
 * that has already started, because an action cut in half is a page left in a
 * state nobody chose.
 */
sealed interface AgentPause {

    /** Running normally. */
    data object None : AgentPause

    /** The user pressed Pause. Nothing is happening, and nothing will until Resume. */
    data object Paused : AgentPause

    /** A challenge was found. Shown before anyone is asked to do anything. */
    data class ChallengeDetected(val kind: ChallengeKind) : AgentPause

    /** Handed over: the user has to finish the check themselves, in the page. */
    data object WaitingForUser : AgentPause

    /** The user finished the check and the page reads normally again. */
    data object VerificationSuccessful : AgentPause

    /** The check is still there after the user said they were done. */
    data object VerificationFailed : AgentPause
}

/**
 * The exact sentences the UI shows for each state. In one place so the panel,
 * the notification and any test agree on them.
 */
object AgentPauseLabels {

    const val CAPTCHA_DETECTED = "CAPTCHA Detected"
    const val WAITING_FOR_USER = "Waiting for User Verification"
    const val VERIFICATION_SUCCESSFUL = "Verification Successful"
    const val VERIFICATION_FAILED = "Verification Failed"
    const val TASK_PAUSED = "Task Paused"

    /** Null while there is nothing to say. */
    fun labelOf(state: AgentPause): String? = when (state) {
        AgentPause.None -> null
        AgentPause.Paused -> TASK_PAUSED
        is AgentPause.ChallengeDetected -> CAPTCHA_DETECTED
        AgentPause.WaitingForUser -> WAITING_FOR_USER
        AgentPause.VerificationSuccessful -> VERIFICATION_SUCCESSFUL
        AgentPause.VerificationFailed -> VERIFICATION_FAILED
    }

    /** What the agent says in the chat when it hands control over. */
    fun handOverText(kind: ChallengeKind): String =
        "This page is showing ${ChallengeDetector.describe(kind)}. The agent does not solve " +
            "these and has stopped. Finish the check in the page, then press Resume — it will " +
            "read the page again and carry on only if the check is gone."
}
