package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The verifier is only useful if "nothing changed" is a fact rather than an
 * impression, so the digest is pinned as stable under the three inputs it
 * covers and different under each of them.
 */
class AgentStepVerifierTest {

    private val page = PageDigestDto(
        url = "https://example.test/cart",
        textLength = 120,
        textHash = "abc123",
        elements = "1:button:0:0|2:input:0:0"
    )

    @Test
    fun the_same_page_gives_the_same_digest() {
        assertThat(AgentStepVerifier.digest(page)).isEqualTo(AgentStepVerifier.digest(page.copy()))
    }

    @Test
    fun a_change_in_any_measured_part_changes_the_digest() {
        val base = AgentStepVerifier.digest(page)

        assertThat(AgentStepVerifier.digest(page.copy(url = "https://example.test/checkout")))
            .isNotEqualTo(base)
        assertThat(AgentStepVerifier.digest(page.copy(textHash = "def456"))).isNotEqualTo(base)
        assertThat(AgentStepVerifier.digest(page.copy(elements = "1:button:1:0|2:input:0:0")))
            .isNotEqualTo(base)
        assertThat(AgentStepVerifier.digest(page.copy(textLength = 121))).isNotEqualTo(base)
    }

    @Test
    fun an_unchanged_page_is_reported_as_having_changed_nothing() {
        val digest = AgentStepVerifier.digest(page)

        val result = AgentStepVerifier.compare(digest, digest, "clicked [3]")

        assertThat(result.changed).isFalse()
        assertThat(result.observed).isEqualTo("clicked [3]")
        assertThat(result.evidence).contains("did NOT change")
    }

    @Test
    fun a_changed_page_is_reported_as_changed() {
        val before = AgentStepVerifier.digest(page)
        val after = AgentStepVerifier.digest(page.copy(textHash = "def456"))

        val result = AgentStepVerifier.compare(before, after, "clicked [3]")

        assertThat(result.changed).isTrue()
        assertThat(result.evidence).contains("changed")
    }

    @Test
    fun an_unmeasurable_page_is_reported_as_unknown_rather_than_unchanged() {
        val result = AgentStepVerifier.compare(null, AgentStepVerifier.digest(page), "clicked [3]")

        assertThat(result.changed).isFalse()
        assertThat(result.evidence).contains("unknown")
    }
}
