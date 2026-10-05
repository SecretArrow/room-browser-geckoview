package com.roombrowser.domain.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

/**
 * Behaviour is exercised at a LOW iteration count so the suite stays fast; the
 * production cost is asserted explicitly instead of being paid on every test.
 */
class PinLockCryptoTest {

    private val iterations = 1_000
    private val salt = ByteArray(PinLockCrypto.SALT_BYTES) { it.toByte() }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun verifierB64(pin: String, iterations: Int = this.iterations): String =
        b64(PinLockCrypto.deriveVerifier(pin.toCharArray(), salt, iterations))

    @Test
    fun `correct pin verifies`() {
        val stored = verifierB64("123456")
        val ok = PinLockCrypto.verify("123456".toCharArray(), b64(salt), iterations, stored)
        assertThat(ok).isTrue()
    }

    @Test
    fun `wrong pin does not verify`() {
        val stored = verifierB64("123456")
        val ok = PinLockCrypto.verify("123457".toCharArray(), b64(salt), iterations, stored)
        assertThat(ok).isFalse()
    }

    @Test
    fun `pin of a different length does not verify`() {
        val stored = verifierB64("123456")
        assertThat(PinLockCrypto.verify("1234567".toCharArray(), b64(salt), iterations, stored))
            .isFalse()
        assertThat(PinLockCrypto.verify("12345".toCharArray(), b64(salt), iterations, stored))
            .isFalse()
    }

    @Test
    fun `same pin under a different salt derives a different verifier`() {
        val otherSalt = PinLockCrypto.newSalt(SecureRandom())
        val a = PinLockCrypto.deriveVerifier("123456".toCharArray(), salt, iterations)
        val b = PinLockCrypto.deriveVerifier("123456".toCharArray(), otherSalt, iterations)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `stored material never contains the pin`() {
        val pin = "0921"
        val verifier = PinLockCrypto.deriveVerifier(pin.toCharArray(), salt, iterations)
        val pinBytes = pin.toByteArray(StandardCharsets.UTF_8)

        assertThat(salt.size).isEqualTo(PinLockCrypto.SALT_BYTES)
        assertThat(verifier.size).isEqualTo(PinLockCrypto.KEY_BITS / 8)
        assertThat(salt).isNotEqualTo(pinBytes)
        assertThat(verifier).isNotEqualTo(pinBytes)
        assertThat(String(verifier, StandardCharsets.UTF_8)).doesNotContain(pin)
        assertThat(b64(verifier)).doesNotContain(pin)
    }

    @Test
    fun `malformed stored material fails closed`() {
        assertThat(PinLockCrypto.verify("123456".toCharArray(), "not base64!", iterations, "AAAA"))
            .isFalse()
        assertThat(PinLockCrypto.verify("123456".toCharArray(), b64(salt), iterations, "not base64!"))
            .isFalse()
        assertThat(PinLockCrypto.verify("123456".toCharArray(), b64(salt), iterations, ""))
            .isFalse()
        assertThat(PinLockCrypto.verify("123456".toCharArray(), b64(ByteArray(0)), iterations, "AAAA"))
            .isFalse()
    }

    @Test
    fun `non positive iteration count fails closed`() {
        assertThat(PinLockCrypto.verify("123456".toCharArray(), b64(salt), 0, verifierB64("123456")))
            .isFalse()
        assertThat(PinLockCrypto.verify("123456".toCharArray(), b64(salt), -1, verifierB64("123456")))
            .isFalse()
    }

    @Test
    fun `verifier is opaque regardless of iteration count`() {
        val low = verifierB64("123456", 1)
        val high = verifierB64("123456", 2_000)
        assertThat(low).isNotEqualTo(high)
        assertThat(PinLockCrypto.verify("123456".toCharArray(), b64(salt), 2_000, low)).isFalse()
    }

    @Test
    fun `production iteration count is the owasp guidance`() {
        assertThat(PinLockCrypto.ITERATIONS).isEqualTo(210_000)
        assertThat(PinLockCrypto.SALT_BYTES).isEqualTo(16)
        assertThat(PinLockCrypto.KEY_BITS).isEqualTo(256)
    }

    @Test
    fun `short pins are rejected and wiped`() {
        assertThat(PinLockCrypto.isAcceptablePin("12345".toCharArray())).isFalse()
        assertThat(PinLockCrypto.isAcceptablePin("123456".toCharArray())).isTrue()

        val pin = "123456".toCharArray()
        PinLockCrypto.wipe(pin)
        assertThat(String(pin)).isEqualTo("\u0000\u0000\u0000\u0000\u0000\u0000")
    }
}
