package com.roombrowser.domain.oct

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A sealed circle asset, opened with the reader's passphrase.
 *
 * The envelope and the key derivation are the protocol's, not a design choice here: a
 * circle sealed by any other client has to open with this code, so the magic, the salt
 * layout and the iteration count are all fixed by what is already published.
 */
object OctSealed {

    /** `'OCRS1'` — the envelope's first five bytes. */
    private val MAGIC = "OCRS1".toByteArray(Charsets.US_ASCII)

    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    /** Anything larger than this does not arrive inline. */
    const val MAX_INLINE_BYTES = 65_536

    /** The node's own ceiling for one asset. */
    const val MAX_ASSET_BYTES = 33_554_432

    /**
     * The salt for one circle and one key id. Per-circle rather than per-profile, which
     * is what lets a single remembered passphrase serve every circle: the salt is the
     * only thing that has to differ.
     */
    fun saltFor(circleId: String, keyId: String): ByteArray =
        "octra:circle:sealed_read:v1:$circleId:$keyId".toByteArray(Charsets.UTF_8)

    fun deriveKey(circleId: String, keyId: String, passphrase: CharArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, saltFor(circleId, keyId), ITERATIONS, KEY_BITS)
        try {
            val material = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
            return SecretKeySpec(material.encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun open(
        envelope: ByteArray,
        circleId: String,
        keyId: String,
        plaintextHashHex: String,
        passphrase: CharArray
    ): OctSealedResult {
        if (envelope.size <= MAGIC.size + NONCE_BYTES) return OctSealedResult.Malformed
        if (!envelope.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return OctSealedResult.Malformed

        val nonce = envelope.copyOfRange(MAGIC.size, MAGIC.size + NONCE_BYTES)
        val ciphertext = envelope.copyOfRange(MAGIC.size + NONCE_BYTES, envelope.size)
        val frame = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, deriveKey(circleId, keyId, passphrase), GCMParameterSpec(TAG_BITS, nonce))
                doFinal(ciphertext)
            }
        } catch (failed: javax.crypto.AEADBadTagException) {
            return OctSealedResult.Rejected
        } catch (failed: java.security.GeneralSecurityException) {
            return OctSealedResult.Malformed
        }

        if (frame.size < 4) return OctSealedResult.Malformed
        val declared = readU32be(frame, 0)
        if (declared > frame.size - 4) return OctSealedResult.Malformed
        val payload = frame.copyOfRange(4, 4 + declared.toInt())
        if (hex(sha256(payload)) != plaintextHashHex.trim().lowercase()) {
            return OctSealedResult.Malformed
        }
        return OctSealedResult.Opened(payload)
    }

    /** Only used to build fixtures in tests; the app is a reader. */
    internal fun seal(
        payload: ByteArray,
        circleId: String,
        keyId: String,
        passphrase: CharArray,
        nonce: ByteArray = ByteArray(NONCE_BYTES)
    ): ByteArray {
        val frame = ByteArrayOutputStream().apply {
            write(byteArrayOf((payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte()))
            write(payload)
        }.toByteArray()
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, deriveKey(circleId, keyId, passphrase), GCMParameterSpec(TAG_BITS, nonce))
            doFinal(frame)
        }
        return MAGIC + nonce + ciphertext
    }

    internal fun plaintextHash(payload: ByteArray): String = hex(sha256(payload))

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { append(HEX[(it.toInt() shr 4) and 0xF]); append(HEX[it.toInt() and 0xF]) }
    }

    private fun readU32be(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or
            ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or
            (bytes[at + 3].toLong() and 0xFF)

    private const val HEX = "0123456789abcdef"
}

/** What [OctSealed.open] found. */
sealed interface OctSealedResult {

    data class Opened(val bytes: ByteArray) : OctSealedResult {
        override fun equals(other: Any?): Boolean = other is Opened && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * The tag did not verify. GCM cannot say whether that was the passphrase or the
     * bytes, so the reader is asked again rather than told which it was.
     */
    data object Rejected : OctSealedResult

    /** Impossible before any key was derived: not this envelope, not this protocol. */
    data object Malformed : OctSealedResult
}
