package com.roombrowser.domain.totp

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JVM tests for the `otpauth://` Key URI Format parser and builder.
 *
 * The rejections matter most: a URI with a missing secret, an unknown algorithm,
 * a wrong digit count, an out-of-range period or a `hotp` counter must fail
 * loudly rather than be defaulted into a working-looking account.
 */
class OtpAuthUriTest {

    private val secret = "JBSWY3DPEHPK3PXP"

    @Test
    fun `reads a url-encoded account and an issuer in the label`() {
        val parsed = OtpAuthUri.parse(
            "otpauth://totp/ACME%20Co:alice%40acme.test?secret=$secret"
        )

        assertThat(parsed.issuer).isEqualTo("ACME Co")
        assertThat(parsed.account).isEqualTo("alice@acme.test")
        assertThat(parsed.secret).isEqualTo(secret)
    }

    @Test
    fun `reads the issuer from the query parameter when the label has no prefix`() {
        val parsed = OtpAuthUri.parse("otpauth://totp/alice%40acme.test?secret=$secret&issuer=Acme")

        assertThat(parsed.issuer).isEqualTo("Acme")
        assertThat(parsed.account).isEqualTo("alice@acme.test")
    }

    @Test
    fun `prefers the query issuer over the label prefix`() {
        val parsed = OtpAuthUri.parse("otpauth://totp/LabelCo:alice?secret=$secret&issuer=QueryCo")

        assertThat(parsed.issuer).isEqualTo("QueryCo")
    }

    @Test
    fun `an issuer is optional`() {
        val parsed = OtpAuthUri.parse("otpauth://totp/alice?secret=$secret")

        assertThat(parsed.issuer).isNull()
        assertThat(parsed.account).isEqualTo("alice")
    }

    @Test
    fun `defaults to SHA1 six digits and thirty seconds`() {
        val parsed = OtpAuthUri.parse("otpauth://totp/Acme:alice?secret=$secret")

        assertThat(parsed.algorithm).isEqualTo(TotpAlgorithm.SHA1)
        assertThat(parsed.digits).isEqualTo(6)
        assertThat(parsed.period).isEqualTo(30)
    }

    @Test
    fun `reads an explicit algorithm digits and period`() {
        val parsed = OtpAuthUri.parse(
            "otpauth://totp/Acme:alice?secret=$secret&algorithm=SHA512&digits=8&period=60"
        )

        assertThat(parsed.algorithm).isEqualTo(TotpAlgorithm.SHA512)
        assertThat(parsed.digits).isEqualTo(8)
        assertThat(parsed.period).isEqualTo(60)
    }

    @Test
    fun `a missing secret is rejected`() {
        val thrown = assertThrows(OtpAuthUriFormatException::class.java) {
            OtpAuthUri.parse("otpauth://totp/Acme:alice?issuer=Acme")
        }

        assertThat(thrown).hasMessageThat().contains("secret")
    }

    @Test
    fun `an unknown algorithm is rejected rather than defaulted`() {
        val thrown = assertThrows(OtpAuthUriFormatException::class.java) {
            OtpAuthUri.parse("otpauth://totp/Acme:alice?secret=$secret&algorithm=MD5")
        }

        assertThat(thrown).hasMessageThat().contains("MD5")
    }

    @Test
    fun `a digit count other than six or eight is rejected`() {
        assertThrows(OtpAuthUriFormatException::class.java) {
            OtpAuthUri.parse("otpauth://totp/Acme:alice?secret=$secret&digits=7")
        }
        val thrown = assertThrows(OtpAuthUriFormatException::class.java) {
            OtpAuthUri.parse("otpauth://totp/Acme:alice?secret=$secret&digits=six")
        }

        assertThat(thrown).hasMessageThat().contains("digits")
    }

    @Test
    fun `a period outside the sane range is rejected`() {
        assertThrows(OtpAuthUriFormatException::class.java) {
            OtpAuthUri.parse("otpauth://totp/Acme:alice?secret=$secret&period=0")
        }
        val thrown = assertThrows(OtpAuthUriFormatException::class.java) {
            OtpAuthUri.parse("otpauth://totp/Acme:alice?secret=$secret&period=100000")
        }

        assertThat(thrown).hasMessageThat().contains("period")
    }

    @Test
    fun `hotp is rejected because this app has no counter`() {
        val thrown = assertThrows(OtpAuthUriFormatException::class.java) {
            OtpAuthUri.parse("otpauth://hotp/Acme:alice?secret=$secret&counter=0")
        }

        assertThat(thrown).hasMessageThat().contains("HOTP")
    }

    @Test
    fun `junk is rejected`() {
        assertThrows(OtpAuthUriFormatException::class.java) { OtpAuthUri.parse("") }
        assertThrows(OtpAuthUriFormatException::class.java) {
            OtpAuthUri.parse("https://example.com/?secret=$secret")
        }
        assertThrows(OtpAuthUriFormatException::class.java) { OtpAuthUri.parse("otpauth://totp/") }
        val thrown = assertThrows(OtpAuthUriFormatException::class.java) {
            OtpAuthUri.parse("just some text")
        }

        assertThat(thrown).hasMessageThat().contains("otpauth")
    }

    @Test
    fun `a secret that is not base32 is rejected`() {
        val thrown = assertThrows(OtpAuthUriFormatException::class.java) {
            OtpAuthUri.parse("otpauth://totp/Acme:alice?secret=not-base32!")
        }

        assertThat(thrown).hasMessageThat().contains("Base32")
    }

    @Test
    fun `build produces a URI that parses back to the same values`() {
        val uri = OtpAuthUri.build(
            issuer = "Acme Co",
            account = "alice@acme.test",
            secret = secret,
            algorithm = TotpAlgorithm.SHA512,
            digits = 8,
            period = 60
        )
        val parsed = OtpAuthUri.parse(uri)

        assertThat(parsed.issuer).isEqualTo("Acme Co")
        assertThat(parsed.account).isEqualTo("alice@acme.test")
        assertThat(parsed.secret).isEqualTo(secret)
        assertThat(parsed.algorithm).isEqualTo(TotpAlgorithm.SHA512)
        assertThat(parsed.digits).isEqualTo(8)
        assertThat(parsed.period).isEqualTo(60)
    }

    @Test
    fun `build omits the label issuer when there is none`() {
        val uri = OtpAuthUri.build(issuer = "", account = "alice", secret = secret)

        assertThat(uri).startsWith("otpauth://totp/alice?secret=")
        assertThat(uri).doesNotContain("issuer=")
    }
}
