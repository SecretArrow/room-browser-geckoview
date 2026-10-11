package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Redaction has two failure modes and only one of them is obvious.
 *
 * Masking too little leaks a credential; masking too much destroys the export
 * it was supposed to make safe to read. Every test that proves a secret is gone
 * has a partner below proving the non-secret parts of the same text survived —
 * because "mask everything" would pass the first half of this file on its own.
 *
 * Every value here is obviously fake. A test fixture must never look like a
 * live key, and this file is about exactly that mistake.
 */
class SecretRedactionTest {

    // ------------------------------------------------------------- headers

    @Test
    fun authorization_header_value_is_masked_and_the_name_survives() {
        val redacted = SecretRedaction.redact("Authorization: Bearer FAKE-NOT-A-REAL-TOKEN")

        assertThat(redacted).contains("Authorization:")
        assertThat(redacted).doesNotContain("FAKE-NOT-A-REAL-TOKEN")
        assertThat(redacted).contains(SecretRedaction.MASK)
    }

    @Test
    fun cookie_and_set_cookie_values_are_masked() {
        val cookies = SecretRedaction.redact("Cookie: sid=FAKE-SESSION-VALUE")
        val setCookie = SecretRedaction.redact("Set-Cookie: sid=FAKE-SESSION-VALUE; HttpOnly")

        assertThat(cookies).contains("Cookie:")
        assertThat(cookies).doesNotContain("FAKE-SESSION-VALUE")
        assertThat(setCookie).contains("Set-Cookie:")
        assertThat(setCookie).doesNotContain("FAKE-SESSION-VALUE")
    }

    @Test
    fun api_key_header_is_masked_whatever_its_case() {
        listOf("X-Api-Key", "x-api-key", "API-KEY").forEach { name ->
            val redacted = SecretRedaction.redact("$name: FAKE-API-KEY-0000")
            assertThat(redacted).doesNotContain("FAKE-API-KEY-0000")
            assertThat(redacted).contains(name)
        }
    }

    // ------------------------------------------------------------- query strings

    @Test
    fun token_and_key_query_values_are_masked_but_other_parameters_are_not() {
        val redacted = SecretRedaction.redact(
            "GET https://example.test/path?page=2&token=FAKE-TOKEN&key=FAKE-KEY&sort=asc"
        )

        assertThat(redacted).doesNotContain("FAKE-TOKEN")
        assertThat(redacted).doesNotContain("FAKE-KEY")
        // Negative control: the parameters that are not credentials are the
        // reason this line is in a bug report at all.
        assertThat(redacted).contains("page=2")
        assertThat(redacted).contains("sort=asc")
        assertThat(redacted).contains("token=")
    }

    // ------------------------------------------------------------- shaped strings

    @Test
    fun a_jwt_shaped_string_is_masked() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWtlIn0.FAKE-SIGNATURE-NOT-REAL"

        val redacted = SecretRedaction.redact("session: $jwt")

        assertThat(redacted).doesNotContain(jwt)
        assertThat(redacted).contains(SecretRedaction.MASK)
    }

    @Test
    fun a_pem_private_key_body_is_masked_and_its_markers_survive() {
        val pem = """
            -----BEGIN PRIVATE KEY-----
            FAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKE
            -----END PRIVATE KEY-----
        """.trimIndent()

        val redacted = SecretRedaction.redact(pem)

        assertThat(redacted).contains("-----BEGIN PRIVATE KEY-----")
        assertThat(redacted).contains("-----END PRIVATE KEY-----")
        assertThat(redacted).doesNotContain("FAKEFAKEFAKEFAKE")
    }

    @Test
    fun a_labelled_seed_phrase_is_masked() {
        val phrase = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima"

        val redacted = SecretRedaction.redact("seed phrase: $phrase")

        assertThat(redacted).doesNotContain("foxtrot")
        assertThat(redacted).contains("seed phrase:")
    }

    // ------------------------------------------------------------- negative controls

    @Test
    fun ordinary_headers_keep_both_their_name_and_their_value() {
        val line = "Content-Type: text/html; charset=utf-8"

        assertThat(SecretRedaction.redact(line)).isEqualTo(line)
    }

    @Test
    fun ordinary_prose_is_untouched() {
        val prose = "The user asked for a summary of the article about seed banks and passwords."

        assertThat(SecretRedaction.redact(prose)).isEqualTo(prose)
    }

    @Test
    fun a_short_hex_string_is_not_treated_as_key_material() {
        // A 40-character git hash is the shape a reader most needs back.
        val sha = "0f1e2d3c4b5a69788796a5b4c3d2e1f0abcd1234"

        assertThat(SecretRedaction.redact("commit $sha")).contains(sha)
    }

    @Test
    fun a_bare_base64_prefix_is_not_treated_as_a_jwt() {
        val notAJwt = "eyJhbGciOiJIUzI1NiJ9-not-a-token"

        assertThat(SecretRedaction.redact(notAJwt)).isEqualTo(notAJwt)
    }

    @Test
    fun a_url_with_no_credential_survives_intact() {
        val url = "https://example.test/docs/getting-started?lang=en#install"

        assertThat(SecretRedaction.redact(url)).isEqualTo(url)
    }

    @Test
    fun redaction_is_idempotent() {
        val once = SecretRedaction.redact("Authorization: Bearer FAKE-NOT-A-REAL-TOKEN")

        assertThat(SecretRedaction.redact(once)).isEqualTo(once)
    }
}
