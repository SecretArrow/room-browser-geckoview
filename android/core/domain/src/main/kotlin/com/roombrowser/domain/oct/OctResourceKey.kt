package com.roombrowser.domain.oct

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * The resource key a circle's asset is stored under, and the only name a sealed circle
 * will answer to.
 *
 * The framing is the protocol's: `SHA-256(tag || 0x00 || for each part: u32be(len) || part)`,
 * hex, lowercase. It is not a hash of a concatenation — the length prefix is what stops
 * two different (circle, path) pairs from framing into the same bytes.
 */
object OctResourceKey {

    private const val TAG = "octra:circle_resource_key:v1"

    fun of(circleId: String, path: String): String =
        OctSealed.hex(derive(TAG, circleId, path))

    internal fun derive(tag: String, vararg parts: String): ByteArray {
        val framed = ByteArrayOutputStream()
        framed.write(tag.toByteArray(Charsets.UTF_8))
        framed.write(0)
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            framed.write(
                byteArrayOf(
                    (bytes.size ushr 24).toByte(),
                    (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(),
                    bytes.size.toByte()
                )
            )
            framed.write(bytes)
        }
        return MessageDigest.getInstance("SHA-256").digest(framed.toByteArray())
    }
}
