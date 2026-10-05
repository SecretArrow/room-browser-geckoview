package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The chord table is what makes `press_keys` work on pages that branch on the
 * legacy `keyCode` fields, so the mapping is pinned here rather than trusted.
 */
class KeyChordTest {

    @Test
    fun a_bare_letter_maps_to_its_uppercase_code() {
        val chord = KeyChord.parse("k")
        assertThat(chord).isNotNull()
        assertThat(chord!!.key).isEqualTo("k")
        assertThat(chord.code).isEqualTo("KeyK")
        assertThat(chord.keyCode).isEqualTo(75)
        assertThat(chord.ctrl).isFalse()
    }

    @Test
    fun modifiers_are_recognised_however_they_are_spelled() {
        listOf("ctrl", "Control", "CTRL").forEach { spelling ->
            val chord = KeyChord.parse("$spelling+a")
            assertThat(chord!!.ctrl).isTrue()
        }
        val chord = KeyChord.parse("Cmd+Shift+k")
        assertThat(chord!!.meta).isTrue()
        assertThat(chord.shift).isTrue()
        assertThat(chord.key).isEqualTo("k")
    }

    @Test
    fun named_keys_carry_the_keycode_sites_branch_on() {
        assertThat(KeyChord.parse("Escape")!!.keyCode).isEqualTo(27)
        assertThat(KeyChord.parse("Enter")!!.keyCode).isEqualTo(13)
        assertThat(KeyChord.parse("Tab")!!.keyCode).isEqualTo(9)
        assertThat(KeyChord.parse("ArrowDown")!!.keyCode).isEqualTo(40)
        assertThat(KeyChord.parse("PageDown")!!.keyCode).isEqualTo(34)
    }

    @Test
    fun function_keys_are_numbered_from_f1() {
        assertThat(KeyChord.parse("F1")!!.keyCode).isEqualTo(112)
        assertThat(KeyChord.parse("F12")!!.keyCode).isEqualTo(123)
        assertThat(KeyChord.parse("F13")).isNull()
    }

    @Test
    fun space_is_a_key_not_a_separator() {
        val chord = KeyChord.parse("Control+Space")
        assertThat(chord!!.key).isEqualTo(" ")
        assertThat(chord.code).isEqualTo("Space")
        assertThat(chord.keyCode).isEqualTo(32)
    }

    @Test
    fun an_unknown_or_ambiguous_chord_is_refused_rather_than_guessed() {
        assertThat(KeyChord.parse("Banana")).isNull()
        assertThat(KeyChord.parse("Control+")).isNull()
        assertThat(KeyChord.parse("a+b")).isNull()
        assertThat(KeyChord.parse("")).isNull()
        assertThat(KeyChord.parse(null)).isNull()
    }

    @Test
    fun the_label_reads_the_way_the_agent_typed_it() {
        assertThat(KeyChord.parse("Control+Shift+k")!!.label()).isEqualTo("Control+Shift+k")
        assertThat(KeyChord.parse("Control+Space")!!.label()).isEqualTo("Control+Space")
    }
}
