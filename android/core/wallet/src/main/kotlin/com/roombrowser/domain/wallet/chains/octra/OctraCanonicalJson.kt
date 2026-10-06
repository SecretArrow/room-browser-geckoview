package com.roombrowser.domain.wallet.chains.octra

/**
 * Octra's signed-payload serialiser.
 *
 * Octra does **not** use RFC 8785 / JCS: the bytes a signature covers are a
 * JSON object written in a FIXED field order with no whitespace at all. Key
 * order is therefore part of the wire format, not a formatting preference —
 * reordering these fields, or letting a generic `Json` encoder near them,
 * changes the signed bytes and every signature the chain receives is rejected.
 *
 * Order: `from, to_, amount, nonce, ou, timestamp, op_type [, encrypted_data]
 * [, message]`. Numbers are emitted bare; every string goes through [escape].
 * [transfer] covers everything up to `op_type` plus the trailing `message`;
 * `encrypted_data` is not modelled because nothing in this wallet produces it.
 *
 * Note the trailing underscore in `to_`. It is not a typo in this file.
 */
object OctraCanonicalJson {

    const val OP_STANDARD = "standard"

    /**
     * A plain transfer. [amountRaw] and [feeRaw] are decimal strings of raw
     * units (1 OCT = 1_000_000), [timestamp] is integer seconds. [message] is
     * the optional trailing field and is omitted entirely when null — an
     * empty string is NOT the same payload and must be passed to sign one.
     */
    fun transfer(
        from: String,
        to: String,
        amountRaw: String,
        nonce: Long,
        feeRaw: String,
        timestamp: Long,
        opType: String = OP_STANDARD,
        message: String? = null
    ): String = buildString {
        append("{\"from\":\"").append(escape(from)).append('"')
        append(",\"to_\":\"").append(escape(to)).append('"')
        append(",\"amount\":\"").append(escape(amountRaw)).append('"')
        append(",\"nonce\":").append(nonce)
        append(",\"ou\":\"").append(escape(feeRaw)).append('"')
        append(",\"timestamp\":").append(timestamp)
        append(",\"op_type\":\"").append(escape(opType)).append('"')
        if (message != null) append(",\"message\":\"").append(escape(message)).append('"')
        append('}')
    }

    /**
     * The escaping the reference implementation applies to every string
     * field: the seven JSON short escapes, and any other control code point
     * below 0x20 as a lowercase four-digit `\u` escape. Everything else —
     * including non-ASCII — is emitted raw as UTF-8.
     *
     * Iterating by UTF-16 char rather than by code point is deliberate and
     * byte-identical here: both halves of a surrogate pair are ≥ 0x20, so a
     * supplemental character passes through unchanged.
     */
    private fun escape(s: String): String {
        val out = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else ->
                    if (ch < ' ') {
                        out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(ch)
                    }
            }
        }
        return out.toString()
    }
}
