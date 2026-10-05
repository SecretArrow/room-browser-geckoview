package com.roombrowser.domain.totp

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JVM tests for the RFC 4648 Base32 codec.
 *
 * The RFC's own §10 vectors pin the alphabet and the bit packing; the
 * load-bearing tests are the normalisation ones and the rejection of a symbol
 * outside the alphabet, because those are what stand between a pasted setup key
 * and a silently different secret.
 */
class Base32Test {

    @Test
    fun `encodes the RFC 4648 vectors unpadded`() {
        assertThat(Base32.encode("".toByteArray())).isEmpty()
        assertThat(Base32.encode("f".toByteArray())).isEqualTo("MY")
        assertThat(Base32.encode("fo".toByteArray())).isEqualTo("MZXQ")
        assertThat(Base32.encode("foo".toByteArray())).isEqualTo("MZXW6")
        assertThat(Base32.encode("foob".toByteArray())).isEqualTo("MZXW6YQ")
        assertThat(Base32.encode("fooba".toByteArray())).isEqualTo("MZXW6YTB")
        assertThat(Base32.encode("foobar".toByteArray())).isEqualTo("MZXW6YTBOI")
    }

    @Test
    fun `decodes padded and unpadded RFC 4648 vectors`() {
        assertThat(Base32.decode("MY======").decodeToString()).isEqualTo("f")
        assertThat(Base32.decode("MZXW6===").decodeToString()).isEqualTo("foo")
        assertThat(Base32.decode("MZXW6YTBOI").decodeToString()).isEqualTo("foobar")
        assertThat(Base32.decode("MZXW6YTBOI======").decodeToString()).isEqualTo("foobar")
    }

    @Test
    fun `normalises lowercase and embedded spaces`() {
        // A setup key is often shown as "mzxw 6ytb oi"; all three spellings are
        // the same secret and must decode identically.
        assertThat(Base32.decode("mzxw6ytboi").decodeToString()).isEqualTo("foobar")
        assertThat(Base32.decode("MZXW 6YTB OI").decodeToString()).isEqualTo("foobar")
        assertThat(Base32.decode("  mzxw6ytboi  ").decodeToString()).isEqualTo("foobar")
    }

    @Test
    fun `round trips arbitrary bytes`() {
        val bytes = ByteArray(64) { (it * 37 + 11).toByte() }

        assertThat(Base32.decode(Base32.encode(bytes)).asList()).isEqualTo(bytes.asList())
    }

    @Test
    fun `round trips the RFC 6238 seed`() {
        val seed = "12345678901234567890".toByteArray(Charsets.US_ASCII)

        assertThat(Base32.encode(seed)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertThat(Base32.decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ").asList())
            .isEqualTo(seed.asList())
    }

    @Test
    fun `rejects a character outside the alphabet`() {
        // '0', '1', '8', '9' and punctuation are not in A-Z2-7. Skipping one
        // would renumber every following symbol, producing a different seed.
        assertThrows(IllegalArgumentException::class.java) { Base32.decode("MZXW6YTBO1") }
        assertThrows(IllegalArgumentException::class.java) { Base32.decode("MZXW6YTB0") }
        assertThrows(IllegalArgumentException::class.java) { Base32.decode("MZXW6YTB!") }

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            Base32.decode("MZXW 6YTB 09")
        }
        assertThat(thrown).hasMessageThat().contains("base32")
    }

    @Test
    fun `rejects an empty string and an impossible length`() {
        assertThrows(IllegalArgumentException::class.java) { Base32.decode("") }
        // A single leftover symbol (1 mod 8) cannot carry a byte.
        val thrown = assertThrows(IllegalArgumentException::class.java) { Base32.decode("MZXW6YTBO") }
        assertThat(thrown).hasMessageThat().contains("length")
    }
}
