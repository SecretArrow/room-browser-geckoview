package com.roombrowser.domain.totp

/**
 * RFC 4648 Base32 (`A-Z2-7`) — the encoding every `otpauth` secret uses.
 *
 * There was no Base32 in this repo (the wallet's Base58 and Bech32 are
 * unrelated alphabets), and a lenient decoder would be the wrong tool anyway.
 * Real setup keys arrive inconsistently: lowercase, grouped in fours with
 * spaces, `=` padding present or missing. This codec normalises all three so
 * the user can paste what their provider showed them, but it REJECTS any
 * character outside the alphabet. A decoder that skipped junk would turn a
 * mistyped key into a different — and perfectly plausible-looking — secret,
 * and the user would only discover it when every code it generated was wrong.
 */
object Base32 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private val LOOKUP = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    /** Encodes [bytes] without `=` padding — the form an `otpauth` secret is written in. */
    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(buffer shr bits) and 0x1f])
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
        return out.toString()
    }

    /**
     * Decodes a secret, accepting lowercase, embedded whitespace and optional
     * `=` padding. Trailing `=` is not trusted for length: it is dropped and the
     * symbol count alone decides how many bytes come out, so padded and unpadded
     * forms of one secret decode identically.
     *
     * @throws IllegalArgumentException on an empty string, a symbol outside the
     *   alphabet, or a symbol count that cannot encode whole bytes.
     */
    fun decode(text: String): ByteArray {
        val cleaned = StringBuilder(text.length)
        for (c in text) {
            if (c.isWhitespace() || c == '=') continue
            // Fold only ASCII lower case; anything else is validated below, so a
            // non-ASCII letter that folds to a valid symbol is still rejected.
            val symbol = if (c in 'a'..'z') c - 32 else c
            val index = if (symbol.code < 128) LOOKUP[symbol.code] else -1
            require(index >= 0) { "Invalid base32 character '$c'" }
            cleaned.append(symbol)
        }
        val symbols = cleaned.length
        require(symbols > 0) { "Empty base32 string" }
        val remainder = symbols % 8
        require(remainder != 1 && remainder != 3 && remainder != 6) {
            "Invalid base32 length $symbols"
        }
        val out = ByteArray(symbols * 5 / 8)
        var buffer = 0
        var bits = 0
        var index = 0
        for (c in cleaned) {
            buffer = (buffer shl 5) or LOOKUP[c.code]
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[index++] = ((buffer shr bits) and 0xff).toByte()
            }
        }
        return out
    }
}
