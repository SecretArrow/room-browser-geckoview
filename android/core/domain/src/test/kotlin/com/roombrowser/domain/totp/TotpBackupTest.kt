package com.roombrowser.domain.totp

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.credentials.VaultAuthException
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JVM tests for the sealed two-factor export.
 *
 * The two that carry weight are [the readable part lists accounts but not
 * secrets] — the deliberate divergence from WalletBackup — and the version
 * rejection, because silently reading a newer file would drop accounts.
 */
class TotpBackupTest {

    private val passphrase = "correct horse battery".toCharArray()
    private val header = TotpBackup.Header(profileLabel = "Work", exportedAt = 1_759_400_000_000L)

    private fun entry(
        issuer: String = "Acme",
        account: String = "alice@acme.test",
        secret: String = "JBSWY3DPEHPK3PXP"
    ) = TotpBackup.Entry(issuer = issuer, account = account, secret = secret)

    private fun contents(vararg entries: TotpBackup.Entry) = TotpBackup.Contents(entries.toList())

    @Test
    fun `the right passphrase round trips every account`() {
        val file = TotpBackup.seal(
            contents(entry(), entry(issuer = "GitHub", account = "octocat")),
            header,
            passphrase
        )

        val restored = TotpBackup.open(file, passphrase)

        assertThat(restored.entries.map { it.issuer }).containsExactly("Acme", "GitHub").inOrder()
        assertThat(restored.entries.map { it.account })
            .containsExactly("alice@acme.test", "octocat").inOrder()
    }

    @Test
    fun `algorithm digits and period round trip`() {
        val file = TotpBackup.seal(
            contents(
                TotpBackup.Entry("Acme", "alice", "JBSWY3DPEHPK3PXP", TotpAlgorithm.SHA256, 8, 60)
            ),
            header,
            passphrase
        )

        val only = TotpBackup.open(file, passphrase).entries.single()

        assertThat(only.algorithm).isEqualTo(TotpAlgorithm.SHA256)
        assertThat(only.digits).isEqualTo(8)
        assertThat(only.period).isEqualTo(60)
    }

    @Test
    fun `a wrong passphrase fails as an auth error`() {
        val file = TotpBackup.seal(contents(entry()), header, passphrase)

        val thrown = assertThrows(VaultAuthException::class.java) {
            TotpBackup.open(file, "not the passphrase".toCharArray())
        }

        assertThat(thrown).hasMessageThat().contains("passphrase")
    }

    @Test
    fun `a corrupt file is rejected as a format error`() {
        assertThrows(TotpBackupFormatException::class.java) {
            TotpBackup.open("not json at all", passphrase)
        }
        val thrown = assertThrows(TotpBackupFormatException::class.java) {
            TotpBackup.open("""{"kind":"room-browser-totp"}""", passphrase)
        }

        assertThat(thrown).hasMessageThat().contains("two-factor")
    }

    @Test
    fun `a file from a newer Room Browser is refused by name`() {
        val file = TotpBackup.seal(contents(entry()), header, passphrase)
            .replace("\"formatVersion\": 1", "\"formatVersion\": 99")

        val thrown = assertThrows(TotpBackupFormatException::class.java) {
            TotpBackup.open(file, passphrase)
        }

        assertThat(thrown).hasMessageThat().contains("newer Room Browser")
    }

    @Test
    fun `the readable part lists accounts but not secrets`() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        val document = TotpBackup.document(contents(entry(secret = secret)), header)
        val humanPart = document.substringBefore("-----BEGIN ROOM BROWSER TOTP DATA-----")

        // Deliberate divergence from WalletBackup: the text a person reads never
        // carries seed material; only the structured block below it does.
        assertThat(humanPart).contains("Acme")
        assertThat(humanPart).doesNotContain(secret)
        assertThat(document).contains(secret)
    }

    @Test
    fun `the sealed file never contains a secret in the clear`() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

        val file = TotpBackup.seal(contents(entry(secret = secret)), header, passphrase)

        assertThat(file).doesNotContain(secret)
    }

    @Test
    fun `isSealedFile claims our files and not the other formats`() {
        val file = TotpBackup.seal(contents(entry()), header, passphrase)

        assertThat(TotpBackup.isSealedFile(file)).isTrue()
        assertThat(TotpBackup.isSealedFile("name,url,username,password\n")).isFalse()
        assertThat(TotpBackup.isSealedFile("""{"kind":"room-browser-passwords","vault":{}}"""))
            .isFalse()
        assertThat(TotpBackup.isSealedFile("")).isFalse()
    }

    @Test
    fun `an empty export is refused rather than sealed`() {
        assertThrows(IllegalArgumentException::class.java) {
            TotpBackup.seal(TotpBackup.Contents(emptyList()), header, passphrase)
        }
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            TotpBackup.seal(contents(entry()), header, CharArray(0))
        }

        assertThat(thrown).hasMessageThat().contains("passphrase")
    }

    @Test
    fun `the filename carries the profile and a timestamp`() {
        val name = TotpBackup.fileName("Work Account", 1_759_400_000_000L)

        assertThat(name).startsWith("room-browser-totp-work-account-")
        assertThat(name).endsWith(".txt")
        assertThat(name).matches("room-browser-totp-work-account-\\d{8}-\\d{6}\\.txt")
    }
}
