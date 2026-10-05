package com.roombrowser.domain.totp

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP over `javax.crypto.Mac`.
 *
 * No third-party OTP dependency: HmacSHA1/256/512 ship with the JDK and with
 * Android, so the RFC's own test vectors are unit-testable in the fast JVM job.
 */
object TotpGenerator {

    /** `10^digits` for the two digit counts an `otpauth` URI may carry. */
    private val MODULUS = intArrayOf(
        0, 10, 100, 1_000, 10_000, 100_000,
        1_000_000, 10_000_000, 100_000_000
    )

    /**
     * The code for [timeMillis] under [secret] (raw key bytes).
     *
     * Dynamic truncation exactly as RFC 6238 §5.3: the low nibble of the last
     * HMAC byte selects a 4-byte window, whose top bit is masked off to keep the
     * value positive before the modulo.
     */
    fun generate(
        secret: ByteArray,
        timeMillis: Long,
        algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
        digits: Int = 6,
        period: Int = 30
    ): String {
        require(secret.isNotEmpty()) { "A TOTP secret must not be empty" }
        require(digits == 6 || digits == 8) { "digits must be 6 or 8, was $digits" }
        require(period > 0) { "period must be positive, was $period" }
        val counter = timeMillis / 1_000L / period
        val hash = hmac(algorithm, secret, counterBytes(counter))
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % MODULUS[digits]).toString().padStart(digits, '0')
    }

    /** The code for a stored entry, whose [TotpEntry.secret] is Base32. */
    fun generate(entry: TotpEntry, timeMillis: Long): String = generate(
        secret = Base32.decode(entry.secret),
        timeMillis = timeMillis,
        algorithm = entry.algorithm,
        digits = entry.digits,
        period = entry.period
    )

    /**
     * Whole seconds left in the step containing [timeMillis], in `1..period`.
     * A period boundary returns [period], not 0, so a UI ring never shows an
     * empty countdown for a frame.
     */
    fun secondsRemaining(timeMillis: Long, period: Int = 30): Int {
        require(period > 0) { "period must be positive, was $period" }
        val elapsed = Math.floorMod(timeMillis / 1_000L, period.toLong())
        return (period - elapsed).toInt()
    }

    private fun hmac(algorithm: TotpAlgorithm, key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm.macName)
        mac.init(SecretKeySpec(key, algorithm.macName))
        return mac.doFinal(data)
    }

    /** The counter as an 8-byte big-endian value — the RFC's HMAC input. */
    private fun counterBytes(counter: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (counter ushr (8 * (7 - i))).toByte()
        return out
    }
}
