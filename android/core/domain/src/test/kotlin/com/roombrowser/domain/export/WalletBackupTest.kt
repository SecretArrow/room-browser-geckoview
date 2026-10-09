package com.roombrowser.domain.export

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.credentials.VaultAuthException
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import org.junit.Assert.assertThrows

/**
 * JVM tests for the wallet-keys export.
 *
 * The load-bearing one is [the recovery phrase never appears in the file]:
 * everything else here is ergonomics, and that one is the reason this format
 * is not a plaintext `.txt`. A backup that quietly wrote the seed in the
 * clear would pass every other test in this class.
 */
class WalletBackupTest {

    private lateinit var previousZone: TimeZone

    @Before
    fun fixTimeZone() {
        // Fixed so the formatted timestamps can be asserted exactly; the zone
        // is the device's in production, only the FORMAT is pinned there.
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(previousZone)
    }

    private val at = 1_759_400_000_000L // 2025-10-02 10:13:20 UTC

    private val phrase = "abandon ability able about above absent absorb abstract " +
        "absurd abuse access accident"

    private fun contents(
        mnemonic: String? = phrase,
        accounts: List<WalletBackup.KeyEntry> = listOf(
            WalletBackup.KeyEntry("EVM", "EVM 1", "0x1111111111111111", "m/44'/60'/0'/0/0"),
            WalletBackup.KeyEntry("Solana", "Solana 1", "So1anaAddr", "m/44'/501'/0'/0'")
        )
    ) = WalletBackup.Contents(
        walletLabel = "Main",
        createdAt = at,
        mnemonic = mnemonic,
        accounts = accounts
    )

    private fun header() = WalletBackup.Header(profileLabel = "Work", exportedAt = at)

    // ------------------------------------------------------------ the point

    @Test
    fun `the recovery phrase never appears in the file`() {
        val file = WalletBackup.seal(contents(), header(), "correct horse battery".toCharArray())

        // Not the phrase, and not any single word of it: a file that leaked
        // one word would leak the whole thing to a dictionary attack, and a
        // word-by-word check catches an encoding change that a whole-phrase
        // check would sail past.
        assertThat(file).doesNotContain(phrase)
        phrase.split(" ").forEach { word ->
            assertThat(file).doesNotContain(word)
        }
        assertThat(file).doesNotContain("Main")
        assertThat(file).doesNotContain("m/44'")
    }

    @Test
    fun `a sealed file opens with its passphrase and yields the keys document`() {
        val passphrase = "correct horse battery".toCharArray()
        val file = WalletBackup.seal(contents(), header(), passphrase)

        val opened = WalletBackup.open(file, "correct horse battery".toCharArray()).document

        // Round trip through open(), not through the raw cipher: what this
        // proves is that THIS file is readable by THIS code.
        assertThat(opened).contains("Room Browser - wallet keys")
        assertThat(opened).contains("Wallet: Main")
        assertThat(opened).contains("Profile: Work")
        assertThat(opened).contains("  1. abandon")
        assertThat(opened).contains(" 12. accident")
        assertThat(opened).contains("0x1111111111111111")
        assertThat(opened).contains("m/44'/60'/0'/0/0")
    }

    @Test
    fun `the wrong passphrase fails cleanly instead of returning garbage`() {
        val file = WalletBackup.seal(contents(), header(), "right passphrase".toCharArray())

        assertThrows(VaultAuthException::class.java) {
            WalletBackup.open(file, "wrong passphrase".toCharArray())
        }
    }

    @Test
    fun `a tampered ciphertext is rejected`() {
        val passphrase = "correct horse battery".toCharArray()
        val file = WalletBackup.seal(contents(), header(), passphrase)
        // Flip one base64 character inside the ciphertext field. GCM's tag is
        // what makes this an error rather than a subtly wrong document.
        val marker = "\"ciphertextB64\": \""
        val start = file.indexOf(marker) + marker.length
        val original = file[start]
        val tampered = file.replaceRange(start, start + 1, if (original == 'A') "B" else "A")

        assertThrows(VaultAuthException::class.java) {
            WalletBackup.open(tampered, "correct horse battery".toCharArray())
        }
    }

    @Test
    fun `two exports of the same wallet are different files`() {
        val passphrase = "correct horse battery".toCharArray()

        val first = WalletBackup.seal(contents(), header(), passphrase)
        val second = WalletBackup.seal(contents(), header(), passphrase)

        // Fresh salt and IV per seal: identical files would tell an observer
        // that nothing changed between two exports, and would let one
        // precomputed table attack every file the user ever writes.
        assertThat(first).isNotEqualTo(second)
    }

    // ------------------------------------------------------------- contents

    @Test
    fun `imported keys are carried and derived accounts are not`() {
        val file = WalletBackup.seal(
            contents(
                accounts = listOf(
                    WalletBackup.KeyEntry("EVM", "EVM 1", "0xderived", "m/44'/60'/0'/0/0"),
                    WalletBackup.KeyEntry(
                        "EVM", "Imported", "0ximported", "", privateKey = "0xdeadbeef"
                    )
                )
            ),
            header(),
            "correct horse battery".toCharArray()
        )

        val opened = WalletBackup.open(file, "correct horse battery".toCharArray()).document

        assertThat(opened).contains("NOT restored by the recovery phrase")
        assertThat(opened).contains("private key: 0xdeadbeef")
        // The derived account is listed for reference, but its key is
        // re-derived from the phrase — writing it would duplicate the secret.
        assertThat(opened).contains("re-derived from the phrase above")
        assertThat(opened).doesNotContain("private key: 0xderived")
    }

    @Test
    fun `a wallet with no phrase says so rather than showing an empty section`() {
        val file = WalletBackup.seal(
            contents(
                mnemonic = null,
                accounts = listOf(
                    WalletBackup.KeyEntry("EVM", "Imported", "0xabc", "", privateKey = "0xkey")
                )
            ),
            header(),
            "correct horse battery".toCharArray()
        )

        val opened = WalletBackup.open(file, "correct horse battery".toCharArray()).document

        assertThat(opened).contains("Recovery phrase: none")
        assertThat(opened).contains("private key: 0xkey")
    }

    @Test
    fun `a wallet with nothing to restore is refused, not written as a decoy`() {
        val empty = contents(mnemonic = null, accounts = emptyList())

        assertThat(empty.isEmpty).isTrue()
        assertThrows(IllegalArgumentException::class.java) {
            WalletBackup.seal(empty, header(), "correct horse battery".toCharArray())
        }
    }

    @Test
    fun `an empty passphrase is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            WalletBackup.seal(contents(), header(), CharArray(0))
        }
    }

    // ---------------------------------------------------------------- file

    @Test
    fun `a file that is not ours is rejected with a readable reason`() {
        val passphrase = "correct horse battery".toCharArray()

        assertThrows(WalletBackupFormatException::class.java) {
            WalletBackup.open("{\"hello\":1}", passphrase)
        }
        assertThrows(WalletBackupFormatException::class.java) {
            WalletBackup.open("", passphrase)
        }
    }

    @Test
    fun `a file from a newer Room Browser is refused rather than guessed at`() {
        // Derived from the current constant rather than hardcoded: this test
        // exists to pin the REFUSAL, and a literal here would quietly stop
        // testing anything the first time the format is bumped — it would
        // replace a string the sealed file no longer contains, leaving a file
        // this build happily accepts.
        val newer = WalletBackup.FORMAT_VERSION + 1
        val file = WalletBackup.seal(contents(), header(), "correct horse battery".toCharArray())
            .replace("\"formatVersion\": ${WalletBackup.FORMAT_VERSION}", "\"formatVersion\": $newer")

        val thrown = assertThrows(WalletBackupFormatException::class.java) {
            WalletBackup.open(file, "correct horse battery".toCharArray())
        }
        assertThat(thrown).hasMessageThat().contains("newer")
    }

    @Test
    fun `a profile export is not mistaken for a wallet keys file`() {
        // Both formats carry a `vault` block; `kind` is what tells them
        // apart, and getting this wrong would mean trying to restore a
        // bookmark list as a seed phrase.
        val foreign = """
            {
              "formatVersion": 2,
              "vault": {
                "scheme": "pbkdf2-sha256-aes256-gcm",
                "saltB64": "AAAA", "iterations": 210000,
                "ivB64": "AAAAAAAAAAAAAAAA", "ciphertextB64": "AAAA"
              }
            }
        """.trimIndent()

        assertThrows(WalletBackupFormatException::class.java) {
            WalletBackup.open(foreign, "correct horse battery".toCharArray())
        }
    }

    @Test
    fun `the filename sorts by date and survives every filesystem`() {
        val name = WalletBackup.fileName("Main Wallet", at)

        assertThat(name).isEqualTo("room-browser-wallet-keys-main-wallet-20251002-101320.txt")
        assertThat(name).doesNotContain(" ")
        assertThat(name).doesNotContain(":")
        assertThat(name.all { it.code < 128 }).isTrue()
    }

    @Test
    fun `a label that is all punctuation still yields a usable filename`() {
        val name = WalletBackup.fileName("***", at)

        assertThat(name).isEqualTo("room-browser-wallet-keys-wallet-20251002-101320.txt")
    }

    // ------------------------------------------------------------ read back

    @Test
    fun `the document is what the user reads, with the data block below it`() {
        val document = WalletBackup.document(contents(), header())

        // Both halves are present, in the order the format promises: a human
        // reads the top, an import reads the fenced block underneath it.
        assertThat(document).contains("Recovery phrase (12 words)")
        assertThat(document.indexOf("12. accident"))
            .isLessThan(document.indexOf("-----BEGIN ROOM BROWSER WALLET DATA-----"))
        assertThat(document).contains("-----END ROOM BROWSER WALLET DATA-----")
    }

    @Test
    fun `a file this build wrote restores the exact contents it was given`() {
        val file = WalletBackup.seal(contents(), header(), "correct horse battery".toCharArray())

        val restored = WalletBackup.open(file, "correct horse battery".toCharArray())

        // The structured path is lossless, and it is lossless about the things
        // the readable text cannot carry — the creation time above all, which
        // the legacy path below has to invent.
        assertThat(restored.legacy).isFalse()
        assertThat(restored.payload.toContents()).isEqualTo(contents())
        assertThat(restored.payload.createdAt).isEqualTo(at)
    }

    @Test
    fun `the embedded block is the same cipher the standalone file writes`() {
        // A profile export embeds the wallet through sealBlock; the wallet's own
        // file goes through seal, which is sealBlock plus a wrapper. Pinned so
        // the two entry points cannot drift into two different formats.
        val passphrase = "correct horse battery".toCharArray()
        val block = WalletBackup.sealBlock(contents(), header(), passphrase)

        val fromFile = WalletBackup.open(
            WalletBackup.seal(contents(), header(), passphrase), passphrase
        )
        val fromBlock = WalletBackup.openBlock(block, passphrase)

        assertThat(fromBlock.payload).isEqualTo(fromFile.payload)
        assertThat(fromBlock.payload.toContents()).isEqualTo(contents())
        assertThat(fromBlock.legacy).isFalse()
    }

    @Test
    fun `a block with nothing to restore is refused, like the file form`() {
        assertThrows(IllegalArgumentException::class.java) {
            WalletBackup.sealBlock(
                contents(mnemonic = null, accounts = emptyList()),
                header(),
                "correct horse battery".toCharArray()
            )
        }
    }

    @Test
    fun `a file older than the data block is marked legacy and keeps its imported keys`() {
        // Exactly what a v1 export decrypted to: the readable document and
        // nothing else. `render` is that shape, so this is the real thing and
        // not a hand-written approximation of it.
        val v1 = WalletBackup.render(
            contents(
                accounts = listOf(
                    WalletBackup.KeyEntry("EVM", "EVM 1", "0xderived", "m/44'/60'/0'/0/0"),
                    WalletBackup.KeyEntry(
                        "EVM", "Imported", "0ximported", "", privateKey = "0xdeadbeef"
                    )
                )
            ),
            header()
        )

        val restored = WalletBackup.readPlaintext(v1)

        assertThat(restored.legacy).isTrue()
        assertThat(restored.document).isEqualTo(v1)
        assertThat(restored.payload.walletLabel).isEqualTo("Main")
        assertThat(restored.payload.mnemonic).isEqualTo(phrase)
        // The imported key is the whole reason the legacy path exists: nothing
        // can re-derive it, so a build that refused v1 files would leave those
        // funds behind.
        assertThat(restored.payload.accounts).containsExactly(
            WalletBackup.KeyEntry("EVM", "Imported", "0ximported", "", privateKey = "0xdeadbeef")
        )
        // The derived row is deliberately NOT carried: the phrase above is
        // about to re-create it, and a second copy would duplicate the row.
        assertThat(restored.payload.accounts.map { it.address }).doesNotContain("0xderived")
    }

    @Test
    fun `a truncated data block is refused rather than half parsed`() {
        val full = WalletBackup.document(contents(), header())
        val cut = full.substringBefore("-----END ROOM BROWSER WALLET DATA-----")

        val thrown = assertThrows(WalletBackupFormatException::class.java) {
            WalletBackup.readPlaintext(cut)
        }
        // The readable half is intact, so the failure has to point at the
        // block — otherwise the user goes looking for a problem with the
        // words they can see.
        assertThat(thrown).hasMessageThat().contains("cut off")
    }

    @Test
    fun `a damaged data block is a format error, not a crash`() {
        val full = WalletBackup.document(contents(), header())
        // Close the fence as normal but drop the JSON's closing brace: the
        // block is found, and it does not parse.
        val damaged = full.replace(Regex("\\n\\}\\n-----END"), "\n-----END")
        assertThat(damaged).isNotEqualTo(full)

        val thrown = assertThrows(WalletBackupFormatException::class.java) {
            WalletBackup.readPlaintext(damaged)
        }
        assertThat(thrown).hasMessageThat().contains("damaged")
    }

    @Test
    fun `a v1 document with nothing restorable is refused, not read as an empty wallet`() {
        val nothing = WalletBackup.render(
            contents(mnemonic = null, accounts = emptyList()),
            header()
        )

        assertThrows(WalletBackupFormatException::class.java) {
            WalletBackup.readPlaintext(nothing)
        }
    }
}
