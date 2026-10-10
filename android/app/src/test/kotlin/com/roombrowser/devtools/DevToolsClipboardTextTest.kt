package com.roombrowser.devtools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What a copied block says, since that is what leaves the app and gets pasted into a report. */
class DevToolsClipboardTextTest {

    @Test
    fun a_copied_console_line_names_its_origin_and_its_place_in_the_page() {
        val text = consoleText(
            listOf(
                ConsoleEntry(
                    level = "error",
                    text = "boom",
                    source = "https://example.com/a.js",
                    line = 7,
                    fromEngine = false
                )
            )
        )
        assertThat(text).isEqualTo("[error/page] (https://example.com/a.js:7) boom")
    }

    @Test
    fun an_engine_message_is_not_written_as_though_the_page_logged_it() {
        val text = consoleText(
            listOf(ConsoleEntry(level = "warning", text = "CSP blocked inline script", fromEngine = true))
        )
        assertThat(text).isEqualTo("[warning/engine] CSP blocked inline script")
    }

    @Test
    fun a_copied_console_block_keeps_the_order_the_panel_showed() {
        val text = consoleText(
            listOf(
                ConsoleEntry(level = "log", text = "one"),
                ConsoleEntry(level = "log", text = "two")
            )
        )
        assertThat(text.lines()).containsExactly(
            "[log/page] one",
            "[log/page] two"
        ).inOrder()
    }

    @Test
    fun a_copied_network_row_says_its_size_was_hidden_rather_than_leaving_it_blank() {
        val text = networkText(
            listOf(
                NetworkEntry(
                    kind = "RESOURCE",
                    url = "https://cdn.example.net/f.woff2",
                    resourceType = "css",
                    sizesHidden = true
                )
            )
        )
        assertThat(text).contains("[sizes hidden: no Timing-Allow-Origin]")
    }

    @Test
    fun a_copied_network_row_carries_the_headers_after_redaction() {
        val text = networkText(
            listOf(
                NetworkEntry(
                    kind = "REQUEST",
                    url = "https://example.com/",
                    method = "GET",
                    requestHeaders = listOf(HeaderEntry("Authorization", REDACTED))
                )
            )
        )
        assertThat(text.lines()).containsExactly(
            "REQUEST GET status unknown https://example.com/",
            "    > Authorization: $REDACTED"
        ).inOrder()
    }

    @Test
    fun the_caption_names_the_cap_and_any_loss() {
        assertThat(feedCaption(shown = 200, kept = 2000, cap = 2000, dropped = 0))
            .isEqualTo("200 of 2000 · 2000 max")
        assertThat(feedCaption(shown = 200, kept = 2000, cap = 2000, dropped = 57))
            .isEqualTo("200 of 2000 · 2000 max · 57 older dropped")
    }

    @Test
    fun the_no_reading_sentence_names_its_bound_and_no_cause() {
        val line = noReadingLine()
        assertThat(line).contains("10 s")
        // The panel cannot tell a timeout from an undecodable reply, so the
        // sentence must not claim one.
        assertThat(line).doesNotContain("timeout")
        assertThat(line).doesNotContain("error")
    }
}
