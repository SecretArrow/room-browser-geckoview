package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The five sentences the panel shows are part of the hand-over contract, so
 * they are pinned here rather than left to a literal in the UI file.
 */
class AgentPauseLabelsTest {

    @Test
    fun every_visible_state_has_exactly_the_sentence_it_promises() {
        assertThat(AgentPauseLabels.labelOf(AgentPause.ChallengeDetected(ChallengeKind.RECAPTCHA)))
            .isEqualTo("CAPTCHA Detected")
        assertThat(AgentPauseLabels.labelOf(AgentPause.WaitingForUser))
            .isEqualTo("Waiting for User Verification")
        assertThat(AgentPauseLabels.labelOf(AgentPause.VerificationSuccessful))
            .isEqualTo("Verification Successful")
        assertThat(AgentPauseLabels.labelOf(AgentPause.VerificationFailed))
            .isEqualTo("Verification Failed")
        assertThat(AgentPauseLabels.labelOf(AgentPause.Paused)).isEqualTo("Task Paused")
    }

    @Test
    fun a_running_turn_has_nothing_to_show() {
        assertThat(AgentPauseLabels.labelOf(AgentPause.None)).isNull()
    }

    @Test
    fun the_hand_over_says_the_agent_will_not_solve_it() {
        val text = AgentPauseLabels.handOverText(ChallengeKind.CLOUDFLARE_TURNSTILE)

        assertThat(text).contains("Cloudflare Turnstile")
        assertThat(text).contains("does not solve")
    }
}
