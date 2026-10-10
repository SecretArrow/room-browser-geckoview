package com.roombrowser.devtools

import com.google.common.truth.Truth.assertThat
import com.roombrowser.engine.devtools.EngineConsoleMessage
import com.roombrowser.engine.devtools.EngineNetworkSignal
import org.junit.Test

/**
 * Redaction at data entry, with the negative controls that make it meaningful:
 * a redactor that masks everything would pass a "secrets are gone" test while
 * destroying the panel, so every masking assertion is paired with the benign
 * value that has to survive it.
 */
class DeveloperToolsEntriesTest {

    @Test
    fun a_secret_header_value_is_masked_and_its_name_kept() {
        val headers = redactHeaders(mapOf("Authorization" to "Bearer abc123"))
        assertThat(headers).hasSize(1)
        // The fact that a request carried Authorization is what a report needs.
        assertThat(headers[0].name).isEqualTo("Authorization")
        assertThat(headers[0].value).isEqualTo(REDACTED)
    }

    @Test
    fun an_ordinary_header_value_is_left_alone() {
        val headers = redactHeaders(mapOf("Accept-Language" to "en-GB,en;q=0.9"))
        assertThat(headers[0].value).isEqualTo("en-GB,en;q=0.9")
    }

    @Test
    fun header_matching_ignores_case() {
        val headers = redactHeaders(mapOf("cOoKiE" to "session=deadbeef"))
        assertThat(headers[0].value).isEqualTo(REDACTED)
    }

    @Test
    fun credentials_in_a_url_are_masked_and_the_host_survives() {
        val redacted = redactUrl("https://alice:hunter2@example.com/private?q=1")
        assertThat(redacted).isEqualTo("https://$REDACTED@example.com/private?q=1")
    }

    @Test
    fun a_token_query_parameter_is_masked_and_the_others_are_kept() {
        val redacted = redactUrl("https://example.com/cb?access_token=abc&page=2#frag")
        assertThat(redacted).isEqualTo("https://example.com/cb?access_token=$REDACTED&page=2#frag")
    }

    @Test
    fun a_url_with_no_credential_is_returned_unchanged() {
        val url = "https://example.com/assets/app.js?v=3"
        assertThat(redactUrl(url)).isEqualTo(url)
    }

    @Test
    fun cross_origin_zero_sizes_are_reported_as_hidden() {
        assertThat(sizesAreHidden(crossOrigin = true, sizes = listOf(0.0, 0.0, null))).isTrue()
    }

    @Test
    fun a_same_origin_zero_stays_a_real_zero() {
        // 0 bytes is what a cache hit really reports, and calling that "hidden"
        // would be the same lie in the other direction.
        assertThat(sizesAreHidden(crossOrigin = false, sizes = listOf(0.0, 0.0, 0.0))).isFalse()
    }

    @Test
    fun a_cross_origin_entry_with_a_real_size_is_not_hidden() {
        assertThat(sizesAreHidden(crossOrigin = true, sizes = listOf(1024.0, 900.0, 1200.0))).isFalse()
    }

    @Test
    fun a_console_entry_from_the_page_keeps_its_text_and_source_line() {
        val entry = EngineConsoleMessage(
            level = "error",
            text = "TypeError: x is not a function",
            source = "https://example.com/app.js",
            line = 42,
            timestampMs = 5L,
            fromEngine = false
        ).toEntry()
        assertThat(entry.text).isEqualTo("TypeError: x is not a function")
        assertThat(entry.source).isEqualTo("https://example.com/app.js")
        assertThat(entry.line).isEqualTo(42)
        assertThat(entry.fromEngine).isFalse()
    }

    @Test
    fun the_origin_of_a_console_message_survives_into_the_entry() {
        val entry = EngineConsoleMessage(
            level = "warning",
            text = "Content Security Policy blocked inline script",
            source = null,
            line = null,
            timestampMs = 1L,
            fromEngine = true
        ).toEntry()
        assertThat(entry.fromEngine).isTrue()
    }

    @Test
    fun a_network_signal_masks_its_headers_on_the_way_in() {
        val entry = EngineNetworkSignal(
            kind = EngineNetworkSignal.Kind.REQUEST,
            url = "https://example.com/api?key=abc",
            method = "GET",
            requestHeaders = mapOf("Cookie" to "session=1", "Accept" to "application/json")
        ).toEntry()
        assertThat(entry.url).isEqualTo("https://example.com/api?key=$REDACTED")
        assertThat(entry.requestHeaders.map { it.name to it.value })
            .containsExactly("Cookie" to REDACTED, "Accept" to "application/json")
        assertThat(entry.kind).isEqualTo("REQUEST")
    }

    @Test
    fun a_completion_is_its_own_kind_and_never_a_relabelled_response() {
        val entry = EngineNetworkSignal(
            kind = EngineNetworkSignal.Kind.COMPLETED,
            url = "https://example.com/a.css"
        ).toEntry()
        assertThat(entry.kind).isEqualTo("COMPLETED")
    }

    @Test
    fun a_page_timing_row_becomes_a_resource_row_with_its_sizes() {
        val entry = ResourceTiming(
            name = "https://example.com/a.css",
            initiatorType = "link",
            startTime = 12.5,
            duration = 8.0,
            transferSize = 4096.0,
            encodedBodySize = 3000.0,
            decodedBodySize = 12000.0,
            responseStatus = 200,
            nextHopProtocol = "h2",
            crossOrigin = false
        ).toEntry()
        assertThat(entry.kind).isEqualTo(RESOURCE_KIND)
        assertThat(entry.status).isEqualTo(200)
        assertThat(entry.resourceType).isEqualTo("link")
        assertThat(entry.transferSize).isEqualTo(4096.0)
        assertThat(entry.sizesHidden).isFalse()
        assertThat(entry.note).isEqualTo("h2")
    }

    @Test
    fun a_cross_origin_page_timing_row_with_zero_sizes_says_hidden() {
        val entry = ResourceTiming(
            name = "https://cdn.example.net/font.woff2",
            transferSize = 0.0,
            encodedBodySize = 0.0,
            decodedBodySize = 0.0,
            crossOrigin = true
        ).toEntry()
        assertThat(entry.sizesHidden).isTrue()
    }

    @Test
    fun bytes_are_shown_in_the_unit_a_person_would_say() {
        assertThat(formatBytes(0.0)).isEqualTo("0 B")
        assertThat(formatBytes(512.0)).isEqualTo("512 B")
        assertThat(formatBytes(1024.0)).isEqualTo("1 kB")
        assertThat(formatBytes(1536.0)).isEqualTo("1.5 kB")
        assertThat(formatBytes(1024.0 * 1024)).isEqualTo("1 MB")
    }

    @Test
    fun a_kind_the_feed_does_not_contain_gets_no_filter_chip() {
        // WebView emits no completion at all, so offering the chip would be
        // offering a filter that can only come back empty.
        val options = kindOptions(listOf(NetworkEntry(kind = "REQUEST", url = "https://a/")))
        assertThat(options).containsExactly(KIND_ALL, "REQUEST").inOrder()
    }

    @Test
    fun page_timing_rows_get_their_own_chip_instead_of_a_raw_kind_name() {
        val options = kindOptions(
            listOf(
                NetworkEntry(kind = RESOURCE_KIND, url = "https://a/x.css"),
                NetworkEntry(kind = RESOURCE_KIND, url = "https://a/y.js")
            )
        )
        assertThat(options).containsExactly(KIND_ALL, KIND_PAGE).inOrder()
    }

    @Test
    fun a_kind_that_appears_twice_gets_one_chip() {
        val options = kindOptions(
            listOf(
                NetworkEntry(kind = "REQUEST", url = "https://a/"),
                NetworkEntry(kind = "COMPLETED", url = "https://a/"),
                NetworkEntry(kind = "REQUEST", url = "https://a/")
            )
        )
        assertThat(options).containsExactly(KIND_ALL, "REQUEST", "COMPLETED").inOrder()
    }

    @Test
    fun an_empty_feed_still_offers_the_all_chip() {
        assertThat(kindOptions(emptyList())).containsExactly(KIND_ALL)
    }

    @Test
    fun the_document_a_request_belongs_to_is_kept_and_redacted_like_any_url() {
        val entry = EngineNetworkSignal(
            kind = EngineNetworkSignal.Kind.REQUEST,
            url = "https://cdn.example.net/a.js",
            documentUrl = "https://example.com/page?token=abc"
        ).toEntry()
        // A document URL carries secrets just as a request URL does.
        assertThat(entry.documentUrl).isEqualTo("https://example.com/page?token=$REDACTED")
    }

    @Test
    fun an_engine_that_reports_no_document_leaves_it_unset() {
        val entry = EngineNetworkSignal(
            kind = EngineNetworkSignal.Kind.REQUEST,
            url = "https://example.com/a.js"
        ).toEntry()
        assertThat(entry.documentUrl).isNull()
    }

    @Test
    fun a_host_is_read_from_a_url_with_or_without_a_path() {
        assertThat(urlHost("https://example.com/a/b?c=1")).isEqualTo("example.com")
        assertThat(urlHost("https://example.com")).isEqualTo("example.com")
        assertThat(urlHost("https://$REDACTED@example.com:8443/a")).isEqualTo("example.com:8443")
    }

    @Test
    fun a_url_with_no_host_yields_nothing_rather_than_a_fragment() {
        assertThat(urlHost("about:blank")).isNull()
        assertThat(urlHost("")).isNull()
    }
}
