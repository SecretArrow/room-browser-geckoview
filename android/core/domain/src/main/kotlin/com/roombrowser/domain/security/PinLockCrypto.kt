package com.roombrowser.domain.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Wallet-lock PIN derivation and verification. Pure JVM (javax.crypto only, no
 * Android), so the whole scheme is unit-tested in the fast `:core:domain` job
 * rather than only on a device.
 *
 * The PIN is NEVER stored. What is stored is a one-way PBKDF2-HMAC-SHA256
 * verifier plus its per-profile salt and iteration count; an unlock re-derives
 * the verifier from the typed PIN and compares it in constant time.
 *
 * The scheme and cost deliberately match
 * [com.roombrowser.domain.credentials.PasswordVaultCrypto] (PBKDF2-HMAC-SHA256,
 * 210 000 iterations, 16-byte salt) instead of inventing a second KDF policy
 * for the same threat class.
 *
 * HONEST LIMIT: a short PIN has so little entropy that a stolen salt+verifier is
 * offline-brute-forceable in hours regardless of the iteration count. PBKDF2
 * buys time; it does not turn a 4-digit PIN into a secret. The retry backoff in
 * [WalletLockStateMachine] defends the on-device entry path only — it is worth
 * nothing against an attacker who already has the app's data.
 */
object PinLockCrypto {

    const val SCHEME = "pbkdf2-sha256"

    /** OWASP 2023+ guidance for PBKDF2-HMAC-SHA256. */
    const val ITERATIONS = 210_000
    const val SALT_BYTES = 16
    const val KEY_BITS = 256

    /** The app refuses to configure a wallet PIN shorter than this. */
    const val MIN_PIN_LENGTH = 6

    fun isAcceptablePin(pin: CharArray): Boolean = pin.size >= MIN_PIN_LENGTH

    /** A fresh per-profile salt. */
    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(SALT_BYTES).also(random::nextBytes)

    /** The PBKDF2 verifier for [pin] under [salt]; the caller wipes [pin]. */
    fun deriveVerifier(pin: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        require(iterations > 0) { "iterations must be positive" }
        val spec = PBEKeySpec(pin, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            // The spec keeps its own copy of the PIN chars; drop it as soon as
            // the verifier exists.
            spec.clearPassword()
        }
    }

    /**
     * Constant-time comparison of a typed PIN against the stored verifier.
     * Returns false (never throws) on a malformed or unusable stored record,
     * so a corrupted row fails closed as "wrong PIN" instead of crashing the
     * locked pane.
     */
    fun verify(pin: CharArray, saltB64: String, iterations: Int, verifierB64: String): Boolean {
        if (iterations <= 0) return false
        val salt = decodeOrNull(saltB64) ?: return false
        val expected = decodeOrNull(verifierB64) ?: return false
        if (salt.isEmpty() || expected.isEmpty()) return false
        return MessageDigest.isEqual(deriveVerifier(pin, salt, iterations), expected)
    }

    /** Best-effort zeroization of in-memory secret material. */
    fun wipe(chars: CharArray) {
        chars.fill('\u0000')
    }

    private fun decodeOrNull(b64: String): ByteArray? =
        try {
            Base64.getDecoder().decode(b64)
        } catch (_: IllegalArgumentException) {
            null
        }
}
