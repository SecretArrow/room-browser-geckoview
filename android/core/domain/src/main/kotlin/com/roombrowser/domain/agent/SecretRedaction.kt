package com.roombrowser.domain.agent

/**
 * Masks the credentials that travel through text the app stores, shows or
 * sends to a model.
 *
 * Applied at data entry AND again at export, because the two answer different
 * questions: entry keeps a secret out of a buffer that might later be read by
 * someone else, and export catches anything that entered before this existed or
 * by a route that forgot to call it.
 *
 * Header NAMES are always kept. "the request carried an Authorization header"
 * is the fact a bug report needs, and it is not the secret; dropping the name
 * would make the redaction useless for debugging.
 *
 * Known limits, stated rather than papered over:
 *  - a bare BIP-39 mnemonic with no "seed phrase:"/"mnemonic:" label in front of
 *    it is not detected, because detecting it would mean masking ordinary
 *    twelve-word prose;
 *  - a long hex string is masked only at exactly 64 or 128 characters (a private
 *    key or a seed), so a 40-character commit hash survives.
 */
object SecretRedaction {

    const val MASK = "<redacted>"

    private val SECRET_HEADERS = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
        "api-key",
        "apikey",
        "x-auth-token",
        "x-csrf-token",
        "x-amz-security-token",
        "www-authenticate"
    )

    private val SECRET_QUERY_KEYS = setOf(
        "token",
        "access_token",
        "refresh_token",
        "api_key",
        "apikey",
        "key",
        "secret",
        "password",
        "auth",
        "session"
    )

    /** `Authorization: Bearer xyz`, `- Cookie: a=b`, `"X-Api-Key": "k"`. */
    private val HEADER_LINE = Regex("""(?mi)^([ \t>\-*]*"?([A-Za-z0-9_-]+)"?[ \t]*:[ \t]*)(\S.*)$""")

    /** The same pair inside a JSON object on one line. */
    private val HEADER_JSON = Regex(
        """(?i)("(?:authorization|cookie|set-cookie|x-api-key|api-key|x-auth-token|x-csrf-token)"[ \t]*:[ \t]*")([^"]*)(")"""
    )

    /** A query pair. The name is kept; only a credential name's value is masked. */
    private val QUERY_PAIR = Regex("""(?i)([?&]([A-Za-z0-9_]+)=)([^&\s"'#<>\\]+)""")

    /** Three base64url segments whose header decodes to `{"...`. */
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\b""")

    private val PEM_BLOCK = Regex(
        """-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""
    )

    /** A raw 32-byte key or a 64-byte seed, and nothing that is merely long. */
    private val KEY_HEX = Regex(
        """(?<![0-9a-fA-F])[0-9a-fA-F]{128}(?![0-9a-fA-F])|(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])"""
    )

    /** Twelve or more lowercase words after an explicit seed/mnemonic label. */
    private val LABELLED_MNEMONIC = Regex(
        """(?i)(\b(?:seed phrase|mnemonic|recovery phrase|secret phrase)\b[ \t]*[:=][ \t]*)([a-z]{3,}(?:[ \t]+[a-z]{3,}){11,})"""
    )

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = HEADER_LINE.replace(out) { m ->
            if (m.groupValues[2].lowercase() in SECRET_HEADERS) m.groupValues[1] + MASK else m.value
        }
        out = HEADER_JSON.replace(out) { m -> m.groupValues[1] + MASK + m.groupValues[3] }
        out = QUERY_PAIR.replace(out) { m ->
            if (m.groupValues[2].lowercase() in SECRET_QUERY_KEYS) m.groupValues[1] + MASK else m.value
        }
        out = JWT.replace(out, MASK)
        out = PEM_BLOCK.replace(out) { pemMasked(it.value) }
        out = LABELLED_MNEMONIC.replace(out) { m -> m.groupValues[1] + MASK }
        out = KEY_HEX.replace(out, MASK)
        return out
    }

    /** The markers stay, the key material between them goes. */
    private fun pemMasked(block: String): String {
        val lines = block.lines()
        if (lines.size <= 2) return MASK
        return lines.first() + "\n" + MASK + "\n" + lines.last()
    }
}
