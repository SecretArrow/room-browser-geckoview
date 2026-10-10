package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContextPresetTest {

    @Test
    fun there_is_no_preset_without_text() {
        assertThat(ContextPresets.of("", "Named", "")).isNull()
        assertThat(ContextPresets.of("", "Named", "   \n  ")).isNull()
        // Whitespace-only is the same as empty, but surrounding whitespace on a
        // real body is not part of it.
        assertThat(requireNotNull(ContextPresets.of("", "Named", "  keep me  ")).text)
            .isEqualTo("keep me")
    }

    @Test
    fun a_blank_name_is_derived_from_the_first_line_of_the_text() {
        assertThat(ContextPresets.of("", "", "Answer in Indonesian.\nBe concise.")!!.name)
            .isEqualTo("Answer in Indonesian.")
        // Leading blank lines are skipped rather than becoming the label.
        assertThat(ContextPresets.of("", "  ", "\n\n   \nSecond line wins")!!.name)
            .isEqualTo("Second line wins")
        // Whitespace-only TEXT never reaches the label: `of` refuses it. The
        // "Untitled context" fallback is for a name that collapses to nothing,
        // which is why it is asserted on `label` directly.
        assertThat(ContextPresets.label("", "   ")).isEqualTo("Untitled context")
    }

    @Test
    fun a_name_is_collapsed_to_one_line_and_capped() {
        assertThat(ContextPresets.label("  Staging\n  cluster  ", "ignored"))
            .isEqualTo("Staging cluster")
        val long = "x".repeat(ContextPresets.NAME_MAX_CHARS + 20)
        val capped = ContextPresets.label(long, "ignored")
        assertThat(capped).hasLength(ContextPresets.NAME_MAX_CHARS + 1)
        assertThat(capped).endsWith("…")
        // A derived label is capped by the same rule as a typed one.
        assertThat(ContextPresets.label("", long)).isEqualTo(capped)
    }

    @Test
    fun upsert_replaces_in_place_and_appends_a_new_one() {
        val a = ContextPreset("a", "A", "first")
        val b = ContextPreset("b", "B", "second")
        val start = listOf(a, b)

        assertThat(ContextPresets.upsert(start, ContextPreset("a", "A2", "changed")))
            .containsExactly(ContextPreset("a", "A2", "changed"), b).inOrder()
        assertThat(ContextPresets.upsert(start, ContextPreset("c", "C", "third")))
            .containsExactly(a, b, ContextPreset("c", "C", "third")).inOrder()
    }

    @Test
    fun preset_ids_are_unique_so_two_presets_can_share_a_name() {
        val first = ContextPresets.of("", "Same", "one")!!
        val second = ContextPresets.of("", "Same", "two")!!
        assertThat(first.id).isNotEqualTo(second.id)
        assertThat(ContextPresets.upsert(listOf(first), second)).hasSize(2)
    }

    @Test
    fun the_library_is_capped_but_an_existing_preset_can_still_be_edited() {
        val full = (1..ContextPresets.MAX_PRESETS).map {
            ContextPreset("id$it", "name$it", "text$it")
        }
        assertThat(ContextPresets.upsert(full, ContextPreset("new", "N", "t")))
            .hasSize(ContextPresets.MAX_PRESETS)
        // At the cap, editing in place must still work — otherwise a full
        // library would be frozen.
        val edited = ContextPresets.upsert(full, ContextPreset("id1", "renamed", "t"))
        assertThat(edited).hasSize(ContextPresets.MAX_PRESETS)
        assertThat(edited.first().name).isEqualTo("renamed")
    }

    @Test
    fun remove_drops_only_the_named_id() {
        val a = ContextPreset("a", "A", "first")
        val b = ContextPreset("b", "B", "second")
        assertThat(ContextPresets.remove(listOf(a, b), "a")).containsExactly(b)
        // An unknown id is a no-op rather than an error: two windows can race a
        // delete, and the second one must not clear the library.
        assertThat(ContextPresets.remove(listOf(a, b), "gone")).containsExactly(a, b).inOrder()
        assertThat(ContextPresets.remove(emptyList(), "a")).isEmpty()
    }

    @Test
    fun a_snippet_is_one_line() {
        assertThat(ContextPresets.snippet("a\n\n  b   c ")).isEqualTo("a b c")
        val long = ContextPresets.snippet("y".repeat(400))
        assertThat(long.length).isAtMost(97)
        assertThat(long).endsWith("…")
    }
}
