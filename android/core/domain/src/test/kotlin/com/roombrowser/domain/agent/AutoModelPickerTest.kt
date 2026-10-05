package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * AUTO's two decisions: WHICH models to try and in what order, and how far down
 * that list a run may go before giving up. Both are asserted here because both
 * are load-bearing at a schedule's expense — a wrong order wastes a probe, and
 * an unbounded walk would spend a task's run on the provider's whole catalogue.
 */
class AutoModelPickerTest {

    @Test
    fun the_providers_own_default_is_tried_first() {
        val candidates = AutoModelPicker.candidates(
            defaultModel = "llama3.2:1b",
            listed = listOf("qwen2.5:7b", "llama3.2:1b")
        )
        assertThat(candidates.first()).isEqualTo("llama3.2:1b")
        assertThat(candidates).containsExactly("llama3.2:1b", "qwen2.5:7b").inOrder()
    }

    @Test
    fun tool_capable_models_come_before_the_rest() {
        val candidates = AutoModelPicker.candidates(
            defaultModel = null,
            listed = listOf("nomic-embed-text", "qwen2.5:7b", "moondream")
        )
        assertThat(candidates.first()).isEqualTo("qwen2.5:7b")
        assertThat(candidates).containsExactly("qwen2.5:7b", "nomic-embed-text", "moondream")
    }

    @Test
    fun a_model_appearing_twice_is_only_probed_once() {
        val candidates = AutoModelPicker.candidates(
            defaultModel = "gpt-4o-mini",
            listed = listOf("gpt-4o-mini", "gpt-4o-mini", "  gpt-4o-mini  ")
        )
        assertThat(candidates).containsExactly("gpt-4o-mini")
    }

    @Test
    fun blank_and_absent_names_are_dropped_rather_than_probed() {
        val candidates = AutoModelPicker.candidates(defaultModel = "   ", listed = listOf("", " ", "m"))
        assertThat(candidates).containsExactly("m")
    }

    @Test
    fun nothing_to_choose_from_yields_no_candidates() {
        assertThat(AutoModelPicker.candidates(null, emptyList())).isEmpty()
    }

    @Test
    fun the_first_model_that_answers_is_the_one_used() = runTest {
        val probed = mutableListOf<String>()
        val chosen = AutoModelPicker.firstWorking(listOf("a", "b", "c")) { model ->
            probed += model
            model == "b"
        }
        assertThat(chosen).isEqualTo("b")
        assertThat(probed).containsExactly("a", "b").inOrder()
    }

    @Test
    fun a_probe_that_throws_counts_as_a_refusal_not_as_the_end_of_the_search() = runTest {
        val chosen = AutoModelPicker.firstWorking(listOf("a", "b")) { model ->
            if (model == "a") throw IllegalStateException("model not found") else true
        }
        assertThat(chosen).isEqualTo("b")
    }

    @Test
    fun the_search_stops_after_the_probe_budget() = runTest {
        var probes = 0
        val chosen = AutoModelPicker.firstWorking(List(50) { "model-$it" }) {
            probes++
            false
        }
        assertThat(chosen).isNull()
        assertThat(probes).isEqualTo(AutoModelPicker.MAX_PROBES)
    }

    @Test
    fun no_candidate_answering_is_null_and_not_an_exception() = runTest {
        assertThat(AutoModelPicker.firstWorking(emptyList()) { true }).isNull()
    }

    @Test
    fun the_probe_is_one_short_user_turn() {
        // The probe's cost is the point: one message, and nothing that could be
        // mistaken for an instruction to the page.
        assertThat(AutoModelPicker.PROBE_MESSAGES).hasSize(1)
        assertThat(AutoModelPicker.PROBE_MESSAGES.single().role).isEqualTo("user")
        assertThat(AutoModelPicker.PROBE_MESSAGES.single().content).isEqualTo("Reply with OK.")
    }

    // ------------------------------------------------- against a real gateway

    /** Answers for the models in [answering], and only those — which is what a
     *  provider that lists a model it will not serve looks like from here. */
    private class FakeGateway(
        private val listed: List<String>,
        private val answering: Set<String>,
        private val listFails: Boolean = false
    ) : AgentGateway {
        val probed = mutableListOf<String>()

        override suspend fun listModels(): List<String> {
            if (listFails) throw IllegalStateException("no /models endpoint")
            return listed
        }

        override suspend fun chat(
            request: ChatRequest,
            events: suspend (StreamEvent) -> Unit
        ): ChatMessage {
            probed += request.model
            if (request.model !in answering) throw IllegalStateException("model unavailable")
            return ChatMessage(role = "assistant", content = "OK")
        }
    }

    @Test
    fun the_listed_model_that_answers_is_the_one_chosen() = runTest {
        val gateway = FakeGateway(listed = listOf("a", "b"), answering = setOf("b"))
        assertThat(AutoModelPicker.firstWorkingOn(gateway, configured = null)).isEqualTo("b")
        // The offer that did not answer was really called: this is a probe, not
        // a lookup, and a lookup would have returned "a".
        assertThat(gateway.probed).containsExactly("a", "b").inOrder()
    }

    @Test
    fun a_provider_with_no_model_list_still_gets_its_default_tried() = runTest {
        val gateway = FakeGateway(listed = emptyList(), answering = setOf("llama3"), listFails = true)
        assertThat(AutoModelPicker.firstWorkingOn(gateway, configured = "llama3")).isEqualTo("llama3")
        assertThat(gateway.probed).containsExactly("llama3")
    }

    @Test
    fun nothing_answering_is_null_so_no_turn_is_sent() = runTest {
        val gateway = FakeGateway(listed = listOf("a", "b"), answering = emptySet())
        assertThat(AutoModelPicker.firstWorkingOn(gateway, configured = null)).isNull()
    }
}
