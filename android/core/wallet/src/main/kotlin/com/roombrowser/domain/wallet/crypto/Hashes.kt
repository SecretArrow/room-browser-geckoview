package com.roombrowser.domain.wallet.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA3Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter

/**
 * Hash primitives shared by every chain adapter.
 *
 * All digests use the BouncyCastle lightweight API directly (no JCA provider
 * registration) so they behave identically on the JVM and on Android without
 * touching the platform's stripped-down BC subset.
 */
object Hashes {

    fun sha256(data: ByteArray): ByteArray = digest(SHA256Digest()) { it.update(data, 0, data.size) }

    /** Double SHA-256 (Bitcoin). */
    fun sha256d(data: ByteArray): ByteArray = sha256(sha256(data))

    /** Keccak-256 (the pre-standard SHA3 used by Ethereum, NOT SHA3-256). */
    fun keccak256(data: ByteArray): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(data, 0, data.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    /** NIST SHA3-256 (used by Aptos). */
    fun sha3_256(data: ByteArray): ByteArray = digest(SHA3Digest(256)) { it.update(data, 0, data.size) }

    /** BLAKE2b-256 (used by Sui). */
    fun blake2b256(data: ByteArray): ByteArray {
        val digest = Blake2bDigest(256)
        digest.update(data, 0, data.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    fun ripemd160(data: ByteArray): ByteArray = digest(RIPEMD160Digest()) { it.update(data, 0, data.size) }

    /** HMAC-SHA512 (BIP32 / SLIP-0010 derivation). */
    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA512Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(mac.macSize)
        mac.doFinal(out, 0)
        return out
    }

    private inline fun digest(
        d: org.bouncycastle.crypto.Digest,
        feed: (org.bouncycastle.crypto.Digest) -> Unit
    ): ByteArray {
        feed(d)
        val out = ByteArray(d.digestSize)
        d.doFinal(out, 0)
        return out
    }
}

/** Hex helpers — lowercase everywhere, "0x" prefix handled explicitly. */
object Hex {
    fun encode(bytes: ByteArray): String =
        buildString(bytes.size * 2) {
            for (b in bytes) {
                append("0123456789abcdef"[(b.toInt() shr 4) and 0xf])
                append("0123456789abcdef"[b.toInt() and 0xf])
            }
        }

    fun decode(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").removePrefix("0X")
        require(clean.length % 2 == 0) { "Hex string must have even length" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex character in input" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun decodeOrNull(hex: String): ByteArray? = try {
        decode(hex)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Renders a byte array as 0x-prefixed lowercase hex. */
    fun encode0x(bytes: ByteArray): String = "0x" + encode(bytes)
}
