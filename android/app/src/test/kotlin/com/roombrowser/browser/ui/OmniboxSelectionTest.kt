package com.roombrowser.browser.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins what a double tap on the address bar must leave behind: the whole URL
 * selected, so the next keystroke replaces it instead of prepending to it.
 *
 * Only the selection arithmetic is testable here — whether the gesture fires
 * is not reachable from a JVM test, and the instrumented suite would have to
 * read the field's selection back to assert it.
 */
class OmniboxSelectionTest {

    @Test
    fun `select all covers every character of the url`() {
        val value = TextFieldValue("https://example.com/some/path", selection = TextRange(7))

        val result = selectAllIn(value)

        assertThat(result.selection).isEqualTo(TextRange(0, 29))
        assertThat(result.text).isEqualTo("https://example.com/some/path")
    }

    @Test
    fun `a selection at the end still expands to the whole url`() {
        val value = TextFieldValue("roombrowser.com", selection = TextRange(15))

        assertThat(selectAllIn(value).selection).isEqualTo(TextRange(0, 15))
    }

    @Test
    fun `an empty field selects nothing rather than an impossible range`() {
        val value = TextFieldValue("", selection = TextRange(0))

        assertThat(selectAllIn(value).selection).isEqualTo(TextRange(0, 0))
    }
}
