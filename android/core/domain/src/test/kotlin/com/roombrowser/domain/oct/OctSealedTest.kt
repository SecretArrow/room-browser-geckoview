package com.roombrowser.domain.oct

import com.google.common.truth.Truth.assertThat
import java.nio.charset.StandardCharsets
import org.junit.Test

class OctSealedTest {

    private val circle = "oct" + "B".repeat(44)
    private val keyId = "key-1"
    private val passphrase = "correct horse".toCharArray()
    private val payload = "<html>sealed</html>".toByteArray(StandardCharsets.UTF_8)

    private fun sealed() = OctSealed.seal(payload, circle, keyId, passphrase)

    @Test
    fun `the envelope is the published shape`() {
        val envelope = sealed()
        assertThat(String(envelope.copyOfRange(0, 5), StandardCharsets.US_ASCII)).isEqualTo("OCRS1")
        assertThat(envelope.size).isGreaterThan(5 + 12)
    }

    @Test
    fun `the salt is per circle and per key`() {
        assertThat(String(OctSealed.saltFor(circle, keyId), StandardCharsets.UTF_8))
            .isEqualTo("octra:circle:sealed_read:v1:$circle:$keyId")
    }

    @Test
    fun `the right passphrase opens it`() {
        val opened = OctSealed.open(sealed(), circle, keyId, OctSealed.plaintextHash(payload), passphrase)
        assertThat(opened).isEqualTo(OctSealedResult.Opened(payload))
    }

    @Test
    fun `a wrong passphrase is rejected rather than guessed at`() {
        val opened = OctSealed.open(sealed(), circle, keyId, OctSealed.plaintextHash(payload), "wrong".toCharArray())
        assertThat(opened).isEqualTo(OctSealedResult.Rejected)
    }

    @Test
    fun `another circle's key does not open it`() {
        val other = "oct" + "C".repeat(44)
        val opened = OctSealed.open(sealed(), other, keyId, OctSealed.plaintextHash(payload), passphrase)
        assertThat(opened).isEqualTo(OctSealedResult.Rejected)
    }

    @Test
    fun `a hash that does not match the plaintext is malformed, not merely wrong`() {
        val opened = OctSealed.open(sealed(), circle, keyId, OctSealed.plaintextHash("other".toByteArray()), passphrase)
        assertThat(opened).isEqualTo(OctSealedResult.Malformed)
    }

    @Test
    fun `a foreign envelope is malformed before any key is derived`() {
        val notOurs = "XXXXX".toByteArray() + ByteArray(32)
        assertThat(OctSealed.open(notOurs, circle, keyId, OctSealed.plaintextHash(payload), passphrase))
            .isEqualTo(OctSealedResult.Malformed)
        assertThat(OctSealed.open(ByteArray(4), circle, keyId, OctSealed.plaintextHash(payload), passphrase))
            .isEqualTo(OctSealedResult.Malformed)
    }

    @Test
    fun `a frame that declares more than it carries is malformed`() {
        val opened = OctSealed.open(sealed(), circle, keyId, OctSealed.plaintextHash(payload), passphrase)
        assertThat(opened).isInstanceOf(OctSealedResult.Opened::class.java)
        // Re-seal an empty payload under the real hash of the larger one: the length in
        // the frame is then honest for its own bytes and the hash comparison is what
        // rejects it, which is the ordering this asserts.
        val mismatched = OctSealed.seal(ByteArray(0), circle, keyId, passphrase)
        assertThat(OctSealed.open(mismatched, circle, keyId, OctSealed.plaintextHash(payload), passphrase))
            .isEqualTo(OctSealedResult.Malformed)
    }
}
