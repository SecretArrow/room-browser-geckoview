package com.roombrowser.domain.export

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.credentials.PasswordCsv
import com.roombrowser.domain.credentials.VaultAuthException
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JVM tests for the passwords export.
 *
 * The load-bearing one is [no password appears in the file in the clear]:
 * everything else here is ergonomics, and that one is the reason this format is
 * sealed rather than a plain `.csv`. The other one that matters is
 * [a password that would break a CSV survives the round trip], because the
 * payload is a CSV and the reader for it is shared with four other browsers —
 * so a hole in the quoting is a hole in both.
 */
class PasswordTransferTest {

    private val passphrase = "correct horse battery".toCharArray()

    private fun entry(
        domain: String = "example.com",
        username: String = "alice",
        password: String = "s3cret",
        title: String? = null
    ) = PasswordTransfer.Entry(domain, username, password, title)

    private fun contents(vararg entries: PasswordTransfer.Entry) =
        PasswordTransfer.Contents(entries.toList())

    // ------------------------------------------------------------ the point

    @Test
    fun `no password appears in the file in the clear`() {
        val file = PasswordTransfer.seal(
            contents(entry(password = "hunter2-very-distinct")),
            passphrase
        )

        assertThat(file).doesNotContain("hunter2-very-distinct")
        assertThat(file).doesNotContain("alice")
        assertThat(file).doesNotContain("example.com")
    }

    @Test
    fun `a password that would break a CSV survives the round trip`() {
        // Each of these is quoted by document() and has to come back byte for
        // byte. The leading-quote case is the one that would swallow the rest
        // of the file if the cell were written unquoted.
        val passwords = listOf(
            "a,b",
            "he said \"hi\"",
            "line one\nline two",
            "\"leading quote",
            "trailing quote\"",
            "both \"of\" them",
            "  spaced  ",
            "CR\rLF",
            "comma, and \"quote\", and\nnewline"
        )

        val restored = PasswordTransfer.open(
            PasswordTransfer.seal(contents(*passwords.map { entry(password = it) }.toTypedArray()), passphrase),
            passphrase
        )

        assertThat(restored.entries.map { it.password }).isEqualTo(passwords)
    }

    @Test
    fun `a login with no password is not carried into the file`() {
        // The store can hold one (`CredentialRepository.save` requires a domain
        // but not a password), and the shared CSV reader counts an empty
        // password cell as incomplete. Writing it would produce a file whose
        // re-import returns fewer logins than the export reported — so it is
        // dropped here, where the count can still be reported, rather than
        // silently lost on the way back in.
        val mixed = listOf(
            entry(domain = "kept.test", password = "pw"),
            entry(domain = "dropped.test", password = "")
        )

        assertThat(PasswordTransfer.carryable(mixed).map { it.domain }).containsExactly("kept.test")

        val document = PasswordTransfer.document(PasswordTransfer.Contents(mixed))
        assertThat(document).contains("kept.test")
        assertThat(document).doesNotContain("dropped.test")
    }

    @Test
    fun `an export of nothing but empty passwords is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            PasswordTransfer.seal(
                contents(entry(password = ""), entry(domain = "other.test", password = "")),
                passphrase
            )
        }
    }

    @Test
    fun `an awkward password does not shift the rows that follow it`() {
        // The failure this guards against is silent: one bad cell offsets every
        // later row, so the user's logins come back with each other's passwords.
        val sealed = PasswordTransfer.seal(
            contents(
                entry(domain = "first.test", username = "one", password = "a,\"b\nc"),
                entry(domain = "second.test", username = "two", password = "plain"),
                entry(domain = "third.test", username = "three", password = "\"")
            ),
            passphrase
        )

        val restored = PasswordTransfer.open(sealed, passphrase)

        assertThat(restored.entries.map { it.domain })
            .containsExactly("first.test", "second.test", "third.test")
        assertThat(restored.entries.map { it.username })
            .containsExactly("one", "two", "three")
        assertThat(restored.entries.map { it.password })
            .containsExactly("a,\"b\nc", "plain", "\"")
    }

    // ------------------------------------------------- the document is a CSV

    @Test
    fun `the decrypted document is a CSV the shared reader already understands`() {
        // This is what makes "import from Room Browser" the same code path as
        // "import from Chrome". If this test fails, that claim is false.
        val document = PasswordTransfer.document(
            contents(entry(domain = "example.com", username = "alice", password = "pw", title = "Work"))
        )

        val parsed = PasswordCsv.parse(document) as PasswordCsv.Result.Parsed

        assertThat(parsed.rows).hasSize(1)
        assertThat(parsed.rows.single().domain).isEqualTo("example.com")
        assertThat(parsed.rows.single().username).isEqualTo("alice")
        assertThat(parsed.rows.single().password).isEqualTo("pw")
        assertThat(parsed.rows.single().title).isEqualTo("Work")
    }

    @Test
    fun `the document is shaped like Chrome's export`() {
        // Deliberate: the point of the CSV payload is that a user who decrypts
        // this file by hand can hand it to Chrome's or Brave's importer.
        val document = PasswordTransfer.document(contents(entry(title = "Example")))

        assertThat(document.lineSequence().first()).isEqualTo("name,url,username,password")
        assertThat(document).contains("Example,https://example.com,alice,s3cret")
    }

    @Test
    fun `a login with no title writes an empty name cell`() {
        assertThat(PasswordTransfer.document(contents(entry(title = null))))
            .isEqualTo("name,url,username,password\n,https://example.com,alice,s3cret\n")
    }

    @Test
    fun `the username is written trimmed so the round trip is exact`() {
        // The shared CSV reader trims usernames (as it must for Chrome's files),
        // so writing them trimmed is what makes our own round trip exact rather
        // than nearly exact.
        val restored = PasswordTransfer.open(
            PasswordTransfer.seal(contents(entry(username = "  alice  ")), passphrase),
            passphrase
        )

        assertThat(restored.entries.single().username).isEqualTo("alice")
    }

    @Test
    fun `a password is written untrimmed`() {
        val restored = PasswordTransfer.open(
            PasswordTransfer.seal(contents(entry(password = "  spaced  ")), passphrase),
            passphrase
        )

        assertThat(restored.entries.single().password).isEqualTo("  spaced  ")
    }

    // ------------------------------------------------------------ the envelope

    @Test
    fun `a sealed file is recognised as one of ours before any passphrase exists`() {
        // The import screen's first question has to be asked before it can ask
        // the second, so this has to work on the raw text.
        val file = PasswordTransfer.seal(contents(entry()), passphrase)

        assertThat(PasswordTransfer.isSealedFile(file)).isTrue()
    }

    @Test
    fun `a plain CSV is not claimed as one of ours`() {
        assertThat(PasswordTransfer.isSealedFile("name,url,username,password\na,https://b,c,d\n")).isFalse()
        assertThat(PasswordTransfer.isSealedFile("")).isFalse()
        assertThat(PasswordTransfer.isSealedFile("   \n ")).isFalse()
    }

    @Test
    fun `another app's JSON is not claimed as one of ours`() {
        assertThat(PasswordTransfer.isSealedFile("""{"hello":"world"}""")).isFalse()
        // Valid JSON that simply is not an envelope: must not throw here, or
        // the import screen would crash instead of reporting a wrong file.
        assertThat(PasswordTransfer.isSealedFile("""{"kind":"something-else","vault":{}}""")).isFalse()
    }

    @Test
    fun `a wrong passphrase is reported as an auth failure not a format failure`() {
        // The two are different things to tell the user: "that passphrase is
        // wrong" versus "that is not one of our files".
        val file = PasswordTransfer.seal(contents(entry()), passphrase)

        assertThrows(VaultAuthException::class.java) {
            PasswordTransfer.open(file, "not the passphrase".toCharArray())
        }
    }

    @Test
    fun `a tampered file is rejected rather than decrypted to garbage`() {
        // One character of the ciphertext is changed and the length kept, so
        // the base64 still decodes and what fails is GCM's authentication —
        // which is the property being asserted. Truncating it instead would
        // fail in the decoder and prove nothing about the cipher.
        val file = PasswordTransfer.seal(contents(entry()), passphrase)
        val needle = "\"ciphertextB64\": \""
        val at = file.indexOf(needle) + needle.length
        val tampered = file.substring(0, at) +
            (if (file[at] == 'A') 'B' else 'A') +
            file.substring(at + 1)

        assertThrows(VaultAuthException::class.java) {
            PasswordTransfer.open(tampered, passphrase)
        }
    }

    @Test
    fun `a file from a newer Room Browser is refused by name`() {
        val file = PasswordTransfer.seal(contents(entry()), passphrase)
            .replace("\"formatVersion\": 1", "\"formatVersion\": 99")

        val thrown = assertThrows(PasswordTransferFormatException::class.java) {
            PasswordTransfer.open(file, passphrase)
        }
        assertThat(thrown).hasMessageThat().contains("newer Room Browser")
    }

    @Test
    fun `opening something that is not one of our files reports a format error`() {
        assertThrows(PasswordTransferFormatException::class.java) {
            PasswordTransfer.open("name,url,username,password\n", passphrase)
        }
        assertThrows(PasswordTransferFormatException::class.java) {
            PasswordTransfer.open("", passphrase)
        }
    }

    @Test
    fun `an empty export is refused rather than sealed`() {
        // A file that restores nothing, reported as a successful export, is
        // worse than an error: the user would delete the profile believing
        // their passwords were saved.
        assertThrows(IllegalArgumentException::class.java) {
            PasswordTransfer.seal(PasswordTransfer.Contents(emptyList()), passphrase)
        }
    }

    @Test
    fun `an empty passphrase is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            PasswordTransfer.seal(contents(entry()), CharArray(0))
        }
    }

    // ------------------------------------------------------------ the filename

    @Test
    fun `the filename carries the profile and a timestamp`() {
        val name = PasswordTransfer.fileName("Work Account", 1_759_400_000_000L)

        assertThat(name).startsWith("room-browser-passwords-work-account-")
        assertThat(name).endsWith(".txt")
        assertThat(name).matches("room-browser-passwords-work-account-\\d{8}-\\d{6}\\.txt")
    }

    @Test
    fun `a profile label with nothing usable still yields a filename`() {
        // A label of only punctuation or non-Latin script must not produce a
        // name that starts with a bare dash or is empty after the prefix.
        val name = PasswordTransfer.fileName("***", 1_759_400_000_000L)

        assertThat(name).startsWith("room-browser-passwords-passwords-")
    }
}
