package com.roombrowser.domain.totp

import java.io.ByteArrayOutputStream

/** [text] is not a supported `otpauth` URI — see the rejection list in [OtpAuthUri]. */
class OtpAuthUriFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * The `otpauth://totp/...` Key URI Format, as Google Authenticator and the rest
 * of the ecosystem write it — a QR code and a hand-entered setup key both arrive
 * in this shape.
 *
 * The parser is deliberately strict. A missing secret, an unknown algorithm, a
 * digit count other than 6/8, an out-of-range period and `hotp` (a counter this
 * app does not model) are all REJECTED with a message, never defaulted. Silently
 * guessing one of those would generate plausible codes for a different account,
 * which is the one failure the user cannot see.
 *
 * `issuer` is optional and read from both places the format allows: the
 * `issuer=` query parameter wins when present, otherwise the prefix of the
 * label (`Issuer:account`) is used.
 */
object OtpAuthUri {

    /** How long a step may be. Outside this the URI is broken or hostile. */
    private val PERIOD_RANGE = 1..300

    /** A decoded `otpauth` URI. [issuer] is null when neither position carried one. */
    data class Parsed(
        val issuer: String?,
        val account: String,
        val secret: String,
        val algorithm: TotpAlgorithm,
        val digits: Int,
        val period: Int
    )

    /**
     * @throws OtpAuthUriFormatException when the text is not a TOTP URI, or a
     *   field is missing, unknown or out of range.
     */
    fun parse(text: String): Parsed {
        val trimmed = text.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0 || !trimmed.substring(0, schemeEnd).equals("otpauth", ignoreCase = true)) {
            throw OtpAuthUriFormatException("not an otpauth URI")
        }
        val rest = trimmed.substring(schemeEnd + 3)
        val slash = rest.indexOf('/')
        if (slash < 0) throw OtpAuthUriFormatException("the otpauth URI has no label")
        val type = rest.substring(0, slash)
        if (type.equals("hotp", ignoreCase = true)) {
            throw OtpAuthUriFormatException("HOTP needs a counter, which this app does not support")
        }
        if (!type.equals("totp", ignoreCase = true)) {
            throw OtpAuthUriFormatException("unsupported OTP type '$type'")
        }

        val afterType = rest.substring(slash + 1)
        val queryStart = afterType.indexOf('?')
        val rawLabel = if (queryStart < 0) afterType else afterType.substring(0, queryStart)
        val rawQuery = if (queryStart < 0) {
            ""
        } else {
            afterType.substring(queryStart + 1).substringBefore('#')
        }
        val params = parseQuery(rawQuery)

        // Split the LABEL before decoding, so an account that itself contains an
        // encoded colon (%3A) is not mistaken for the issuer separator.
        val colon = rawLabel.indexOf(':')
        val labelIssuer = if (colon < 0) null else percentDecode(rawLabel.substring(0, colon))
        val account = percentDecode(if (colon < 0) rawLabel else rawLabel.substring(colon + 1))
        val issuer = params["issuer"]?.takeIf { it.isNotBlank() }
            ?: labelIssuer?.takeIf { it.isNotBlank() }

        val secret = params["secret"]?.trim().orEmpty()
        if (secret.isEmpty()) throw OtpAuthUriFormatException("the otpauth URI has no secret")
        try {
            Base32.decode(secret)
        } catch (e: IllegalArgumentException) {
            throw OtpAuthUriFormatException("the otpauth secret is not valid Base32", e)
        }

        val algorithm = params["algorithm"]?.takeIf { it.isNotBlank() }?.let {
            TotpAlgorithm.fromUriValue(it)
                ?: throw OtpAuthUriFormatException("unsupported algorithm '$it'")
        } ?: TotpAlgorithm.SHA1

        val digits = params["digits"]?.takeIf { it.isNotBlank() }?.let {
            it.toIntOrNull() ?: throw OtpAuthUriFormatException("digits is not a number: '$it'")
        } ?: 6
        if (digits != 6 && digits != 8) {
            throw OtpAuthUriFormatException("digits must be 6 or 8, was $digits")
        }

        val period = params["period"]?.takeIf { it.isNotBlank() }?.let {
            it.toIntOrNull() ?: throw OtpAuthUriFormatException("period is not a number: '$it'")
        } ?: 30
        if (period !in PERIOD_RANGE) {
            throw OtpAuthUriFormatException(
                "period $period is outside ${PERIOD_RANGE.first}..${PERIOD_RANGE.last}"
            )
        }

        return Parsed(issuer, account, secret, algorithm, digits, period)
    }

    /**
     * Builds the URI for an account. The label's `:` between issuer and account
     * is the only unencoded separator; both parts are percent-encoded, so an
     * issuer or account containing a colon cannot be read back as a boundary.
     */
    fun build(
        issuer: String,
        account: String,
        secret: String,
        algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
        digits: Int = 6,
        period: Int = 30
    ): String {
        require(secret.isNotBlank()) { "A TOTP secret is required" }
        require(digits == 6 || digits == 8) { "digits must be 6 or 8, was $digits" }
        require(period in PERIOD_RANGE) {
            "period $period is outside ${PERIOD_RANGE.first}..${PERIOD_RANGE.last}"
        }
        val label = if (issuer.isBlank()) {
            percentEncode(account)
        } else {
            "${percentEncode(issuer)}:${percentEncode(account)}"
        }
        return buildString {
            append("otpauth://totp/").append(label)
            append("?secret=").append(percentEncode(secret))
            if (issuer.isNotBlank()) append("&issuer=").append(percentEncode(issuer))
            append("&algorithm=").append(algorithm.uriValue)
            append("&digits=").append(digits)
            append("&period=").append(period)
        }
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        val params = HashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = percentDecode(pair.substring(0, eq)).lowercase()
            params[key] = percentDecode(pair.substring(eq + 1))
        }
        return params
    }

    /**
     * Percent-decoding that treats `+` as a literal (it is in a URI, unlike a
     * form body) and decodes multi-byte UTF-8 correctly.
     */
    private fun percentDecode(value: String): String {
        if (value.indexOf('%') < 0) return value
        val bytes = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '%') {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
                continue
            }
            if (i + 3 > value.length) {
                throw OtpAuthUriFormatException("bad percent-encoding in '$value'")
            }
            val code = value.substring(i + 1, i + 3).toIntOrNull(16)?.takeIf { it in 0..255 }
                ?: throw OtpAuthUriFormatException("bad percent-encoding in '$value'")
            bytes.write(code)
            i += 3
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /** RFC 3986 unreserved characters stay literal; everything else is `%XX`. */
    private fun percentEncode(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xff
            val c = b.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '-' || c == '.' || c == '_' || c == '~'
            ) {
                out.append(c)
            } else {
                out.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0f])
            }
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
