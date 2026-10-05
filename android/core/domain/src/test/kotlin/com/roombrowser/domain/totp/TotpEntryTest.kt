package com.roombrowser.domain.totp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The security property of an entry that a generated `toString` would break. */
class TotpEntryTest {

    private fun entry(secret: String = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ") = TotpEntry(
        id = "id-1",
        profileId = "profile-1",
        issuer = "Acme",
        account = "alice@acme.test",
        secret = secret,
        algorithm = TotpAlgorithm.SHA256,
        digits = 8,
        period = 60,
        createdAt = 10,
        lastUsedAt = 20
    )

    @Test
    fun `toString redacts the secret`() {
        val text = entry().toString()

        assertThat(text).doesNotContain("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertThat(text).contains("REDACTED")
        assertThat(text).contains("alice@acme.test")
    }

    @Test
    fun `lastUsedAt may be null when the account was never used`() {
        val fresh = TotpEntry(
            id = "id-2",
            profileId = "profile-1",
            issuer = "Acme",
            account = "bob@acme.test",
            secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
            createdAt = 10
        )

        assertThat(fresh.lastUsedAt).isNull()
    }
}
