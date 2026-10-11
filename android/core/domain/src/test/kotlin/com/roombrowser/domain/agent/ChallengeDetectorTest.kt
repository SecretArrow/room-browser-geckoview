package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The detector only ever decides "stop and ask a person", so its cost of being
 * wrong is one wasted tap — and its cost of missing is a turn that clicks
 * through a page that was asking for a human. Both directions are pinned here.
 */
class ChallengeDetectorTest {

    @Test
    fun a_cloudflare_interstitial_is_turnstile() {
        val hit = ChallengeDetector.detect(pageText = "Just a moment... enabling challenge")

        assertThat(hit.kind).isEqualTo(ChallengeKind.CLOUDFLARE_TURNSTILE)
        assertThat(hit.detected).isTrue()
    }

    @Test
    fun turnstile_is_found_in_a_script_url_alone() {
        val hit = ChallengeDetector.detect(
            network = listOf("https://challenges.cloudflare.com/turnstile/v0/api.js")
        )

        assertThat(hit.kind).isEqualTo(ChallengeKind.CLOUDFLARE_TURNSTILE)
    }

    @Test
    fun recaptcha_is_recognised_above_the_generic_wording() {
        val hit = ChallengeDetector.detect(
            pageText = "Please verify you are human",
            console = listOf("loaded https://www.google.com/recaptcha/api.js")
        )

        assertThat(hit.kind).isEqualTo(ChallengeKind.RECAPTCHA)
    }

    @Test
    fun hcaptcha_is_recognised() {
        val hit = ChallengeDetector.detect(pageText = "Complete the hCaptcha to continue")

        assertThat(hit.kind).isEqualTo(ChallengeKind.HCAPTCHA)
    }

    @Test
    fun a_bare_human_check_is_a_generic_interstitial() {
        val hit = ChallengeDetector.detect(pageText = "Checking your browser before accessing")

        assertThat(hit.kind).isEqualTo(ChallengeKind.GENERIC_INTERSTITIAL)
    }

    // ------------------------------------------------------------- negative control

    @Test
    fun an_ordinary_page_is_not_a_challenge() {
        val hit = ChallengeDetector.detect(
            pageText = "Welcome back. Your orders shipped today.",
            console = listOf("INFO: analytics loaded"),
            network = listOf("https://example.test/api/orders"),
            url = "https://example.test/account",
            title = "Account overview"
        )

        assertThat(hit.kind).isEqualTo(ChallengeKind.NONE)
        assertThat(hit.detected).isFalse()
    }

    @Test
    fun empty_evidence_is_not_a_challenge() {
        assertThat(ChallengeDetector.detect().kind).isEqualTo(ChallengeKind.NONE)
        assertThat(ChallengeDetector.detect(pageText = "   ").kind).isEqualTo(ChallengeKind.NONE)
    }

    @Test
    fun every_kind_has_a_sentence_for_the_ui() {
        ChallengeKind.entries.forEach { kind ->
            assertThat(ChallengeDetector.describe(kind)).isNotEmpty()
        }
    }
}
