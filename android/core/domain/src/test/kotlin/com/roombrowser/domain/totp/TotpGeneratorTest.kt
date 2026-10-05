package com.roombrowser.domain.totp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM tests for the RFC 6238 generator.
 *
 * [matches every RFC 6238 Appendix B vector at 8 digits] is the authoritative
 * one: those are the only published vectors for TOTP, and they exercise all
 * three hashes, dynamic truncation and the leading-zero case. The 6-digit run
 * uses the same truncated value read modulo 10^6, since the RFC publishes only
 * the 8-digit form.
 */
class TotpGeneratorTest {

    private val sha1Seed = "12345678901234567890".toByteArray(Charsets.US_ASCII)
    private val sha256Seed = "12345678901234567890123456789012".toByteArray(Charsets.US_ASCII)
    private val sha512Seed =
        "1234567890123456789012345678901234567890123456789012345678901234"
            .toByteArray(Charsets.US_ASCII)

    private data class Vector(val time: Long, val algorithm: TotpAlgorithm, val code: String)

    /** RFC 6238 Appendix B, in full. */
    private val vectors = listOf(
        Vector(59L, TotpAlgorithm.SHA1, "94287082"),
        Vector(59L, TotpAlgorithm.SHA256, "46119246"),
        Vector(59L, TotpAlgorithm.SHA512, "90693936"),
        Vector(1111111109L, TotpAlgorithm.SHA1, "07081804"),
        Vector(1111111109L, TotpAlgorithm.SHA256, "68084774"),
        Vector(1111111109L, TotpAlgorithm.SHA512, "25091201"),
        Vector(1111111111L, TotpAlgorithm.SHA1, "14050471"),
        Vector(1111111111L, TotpAlgorithm.SHA256, "67062674"),
        Vector(1111111111L, TotpAlgorithm.SHA512, "99943326"),
        Vector(1234567890L, TotpAlgorithm.SHA1, "89005924"),
        Vector(1234567890L, TotpAlgorithm.SHA256, "91819424"),
        Vector(1234567890L, TotpAlgorithm.SHA512, "93441116"),
        Vector(2000000000L, TotpAlgorithm.SHA1, "69279037"),
        Vector(2000000000L, TotpAlgorithm.SHA256, "90698825"),
        Vector(2000000000L, TotpAlgorithm.SHA512, "38618901"),
        Vector(20000000000L, TotpAlgorithm.SHA1, "65353130"),
        Vector(20000000000L, TotpAlgorithm.SHA256, "77737706"),
        Vector(20000000000L, TotpAlgorithm.SHA512, "47863826")
    )

    private fun seedFor(algorithm: TotpAlgorithm): ByteArray = when (algorithm) {
        TotpAlgorithm.SHA1 -> sha1Seed
        TotpAlgorithm.SHA256 -> sha256Seed
        TotpAlgorithm.SHA512 -> sha512Seed
    }

    @Test
    fun `matches every RFC 6238 Appendix B vector at 8 digits`() {
        for (vector in vectors) {
            val actual = TotpGenerator.generate(
                secret = seedFor(vector.algorithm),
                timeMillis = vector.time * 1000,
                algorithm = vector.algorithm,
                digits = 8,
                period = 30
            )
            assertThat(actual).isEqualTo(vector.code)
        }
    }

    @Test
    fun `the 6 digit code is the 8 digit vector modulo 10^6`() {
        for (vector in vectors) {
            val actual = TotpGenerator.generate(
                secret = seedFor(vector.algorithm),
                timeMillis = vector.time * 1000,
                algorithm = vector.algorithm,
                digits = 6,
                period = 30
            )
            assertThat(actual).isEqualTo(vector.code.takeLast(6))
        }
    }

    @Test
    fun `generates from a stored entry whose secret is base32`() {
        // SHA1 / 8 digits at t=59 is 94287082, so an entry built from the same
        // seed must reproduce it.
        val entry = TotpEntry(
            id = "id-1",
            profileId = "profile-1",
            issuer = "Acme",
            account = "alice",
            secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
            algorithm = TotpAlgorithm.SHA1,
            digits = 8,
            period = 30,
            createdAt = 0
        )

        assertThat(TotpGenerator.generate(entry, 59_000)).isEqualTo("94287082")
    }

    @Test
    fun `a code with leading zeros keeps its width`() {
        // 07081804 is the SHA1 vector at 1111111109; a generator that dropped
        // the pad would return a 7-digit code.
        val entry = TotpEntry(
            id = "id-1",
            profileId = "profile-1",
            issuer = "Acme",
            account = "alice",
            secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
            digits = 8,
            createdAt = 0
        )

        assertThat(TotpGenerator.generate(entry, 1_111_111_109_000).first()).isEqualTo('0')
        assertThat(TotpGenerator.generate(entry, 1_111_111_109_000)).hasLength(8)
    }

    @Test
    fun `seconds remaining counts down and rolls over at the boundary`() {
        assertThat(TotpGenerator.secondsRemaining(0, 30)).isEqualTo(30)
        assertThat(TotpGenerator.secondsRemaining(1_000, 30)).isEqualTo(29)
        assertThat(TotpGenerator.secondsRemaining(29_000, 30)).isEqualTo(1)
        assertThat(TotpGenerator.secondsRemaining(30_000, 30)).isEqualTo(30)
        assertThat(TotpGenerator.secondsRemaining(1_111_111_110_000, 30)).isEqualTo(30)
    }
}
