package com.roombrowser.domain.totp

import kotlinx.serialization.Serializable

/**
 * The HMAC hash of an `otpauth` entry.
 *
 * [macName] is what `javax.crypto.Mac` expects; [uriValue] is the Key URI
 * Format's spelling. All three are in the JDK and on Android, so a TOTP engine
 * needs no third-party dependency.
 */
@Serializable
enum class TotpAlgorithm(val macName: String, val uriValue: String) {
    SHA1("HmacSHA1", "SHA1"),
    SHA256("HmacSHA256", "SHA256"),
    SHA512("HmacSHA512", "SHA512");

    companion object {
        /** The algorithm named by [value], or null when it is not one we compute. */
        fun fromUriValue(value: String): TotpAlgorithm? =
            entries.firstOrNull { it.uriValue.equals(value, ignoreCase = true) }
    }
}
