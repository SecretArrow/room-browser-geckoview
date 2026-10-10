package com.roombrowser.main

import com.google.common.truth.Truth.assertThat
import com.roombrowser.browser.wallet.RestoreReport
import com.roombrowser.data.repo.ProfileRepositoryImpl
import com.roombrowser.domain.export.WalletBackup
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.wallet.model.ChainType
import org.junit.Test

/**
 * The two pure decisions behind the export picker and the import report — the
 * ones a user sees the consequence of, and the ones that do not need a device
 * to be wrong.
 */
class ImportReportTest {

    private fun profile(name: String = "Work") =
        Profile(id = ProfileId("11111111-1111-1111-1111-111111111111"), name = name, createdAt = 1L)

    private fun summary(
        bookmarks: Int = 0,
        notes: Int = 0,
        sitePermissions: Int = 0,
        siteSettings: Int = 0,
        credentials: Int = 0,
        totp: Int = 0,
        wallet: RestoreReport? = null
    ) = ProfileRepositoryImpl.ImportSummary(
        profile = profile(),
        bookmarks = bookmarks,
        notes = notes,
        sitePermissions = sitePermissions,
        siteSettings = siteSettings,
        credentials = credentials,
        totp = totp,
        wallet = wallet
    )

    private fun wallet(
        phrase: Boolean = false,
        derived: Int = 0,
        imported: Int = 0,
        skipped: List<RestoreReport.SkippedKey> = emptyList()
    ) = RestoreReport(
        walletLabel = "Wallet",
        phraseRestored = phrase,
        derivedAccountCount = derived,
        importedAccountCount = imported,
        skipped = skipped
    )

    @Test
    fun `the picker opens on the complete file`() {
        val all = ExportSections()

        assertThat(
            listOf(
                all.bookmarks, all.notes, all.passwords,
                all.totp, all.sitePermissions, all.siteSettings
            )
        ).containsExactly(true, true, true, true, true, true)
    }

    @Test
    fun `the wallet is the one section that opens unticked`() {
        // Deliberate, and the only exception: every other section costs the user
        // privacy if the file leaks, the wallet costs them the money.
        assertThat(ExportSections().wallet).isFalse()
        assertThat(ExportSections(wallet = true).wallet).isTrue()
    }

    @Test
    fun `the vault is asked for only when a sealed block is selected`() {
        assertThat(ExportSections().needsVault).isTrue()
        assertThat(ExportSections(passwords = false).needsVault).isTrue()
        assertThat(ExportSections(totp = false).needsVault).isTrue()
        assertThat(ExportSections(passwords = false, totp = false).needsVault).isFalse()
        // The wallet is sealed too, so it raises the same prompt on its own.
        assertThat(
            ExportSections(passwords = false, totp = false, wallet = true).needsVault
        ).isTrue()
    }

    @Test
    fun `a passwords-only file selects the logins and nothing else`() {
        val file = ExportSections.PASSWORDS_FILE

        assertThat(file.bookmarks).isFalse()
        assertThat(file.notes).isFalse()
        assertThat(file.totp).isFalse()
        assertThat(file.sitePermissions).isFalse()
        assertThat(file.siteSettings).isFalse()
        assertThat(file.wallet).isFalse()
        assertThat(file.passwords).isTrue()
        assertThat(file.needsVault).isTrue()
    }

    @Test
    fun `the report names every part that was written`() {
        val line = importedSummaryLine(
            summary(
                bookmarks = 3, notes = 1, sitePermissions = 2,
                siteSettings = 4, credentials = 5, totp = 2
            )
        )

        assertThat(line).isEqualTo(
            "Imported \"Work\" (3 bookmarks, 1 note, 5 passwords, " +
                "2 authenticator accounts, 2 site permissions, 4 per-site settings)"
        )
    }

    @Test
    fun `a count of one is not pluralised`() {
        val line = importedSummaryLine(summary(bookmarks = 1, credentials = 1))

        assertThat(line).contains("1 bookmark")
        assertThat(line).contains("1 password")
        assertThat(line).doesNotContain("1 bookmarks")
        assertThat(line).doesNotContain("1 passwords")
    }

    @Test
    fun `a part the import wrote nothing of is not reported`() {
        // The credential store drops a login whose domain canonicalizes to
        // nothing, and the count follows the writes — so a section the file
        // carried but the device refused must not appear.
        val line = importedSummaryLine(summary(bookmarks = 2))

        assertThat(line).isEqualTo("Imported \"Work\" (2 bookmarks)")
        assertThat(line).doesNotContain("password")
        assertThat(line).doesNotContain("authenticator")
        assertThat(line).doesNotContain("site")
    }

    @Test
    fun `the report names what a restored wallet took`() {
        val line = importedSummaryLine(
            summary(bookmarks = 1, wallet = wallet(phrase = true, derived = 3, imported = 2))
        )

        assertThat(line).isEqualTo(
            "Imported \"Work\" (1 bookmark, 5 wallet accounts, recovery phrase)"
        )
    }

    @Test
    fun `a wallet key the build could not take is named, not counted as restored`() {
        // The one failure a user must not learn about from a missing balance
        // later: the file carried a key and the restore left it behind.
        val line = importedSummaryLine(
            summary(
                wallet = wallet(
                    phrase = true,
                    derived = 1,
                    imported = 0,
                    skipped = listOf(
                        RestoreReport.SkippedKey("EVM", "Legacy", "not a valid private key")
                    )
                )
            )
        )

        assertThat(line).contains("1 wallet account")
        assertThat(line).contains("1 wallet key skipped")
        assertThat(line).doesNotContain("2 wallet accounts")
    }

    @Test
    fun `a restored phrase is derived for the chains the file names`() {
        // The file, not the importing build, decides: its derived entries are
        // exactly the ones carrying no key.
        val chains = derivedChainsOf(
            WalletBackup.Payload(
                walletLabel = "Main",
                createdAt = 1L,
                mnemonic = "abandon ability",
                accounts = listOf(
                    WalletBackup.KeyEntry("EVM", "EVM 1", "0x1", "m/44'/60'/0'/0/0"),
                    WalletBackup.KeyEntry("Solana", "Solana 1", "So1", "m/44'/501'/0'/0'"),
                    // Imported: its chain is restored by the key itself, so it
                    // must not also drag an index-0 derived account in.
                    WalletBackup.KeyEntry("Bitcoin", "Legacy", "bc1", "", privateKey = "0xdead"),
                    WalletBackup.KeyEntry("EVM", "EVM 2", "0x2", "m/44'/60'/0'/0/1")
                )
            )
        )

        assertThat(chains).containsExactly(ChainType.EVM, ChainType.SOLANA)
    }

    @Test
    fun `a chain named by its label is recognised, and an unknown one is left out`() {
        // The file stores the label the user saw ("Solana", not "SOLANA"); a
        // chain this build no longer knows is dropped rather than guessed at.
        val chains = derivedChainsOf(
            WalletBackup.Payload(
                walletLabel = "Main",
                createdAt = 1L,
                accounts = listOf(
                    WalletBackup.KeyEntry("solana", "Solana 1", "So1", "m/44'/501'/0'/0'"),
                    WalletBackup.KeyEntry("Dogecoin", "DOGE 1", "D1", "m/44'/3'/0'/0/0")
                )
            )
        )

        assertThat(chains).containsExactly(ChainType.SOLANA)
    }

    @Test
    fun `the prompt says which half of a wallet is going into the file`() {
        // A count would be a lie here: a phrase is not an item, and a wallet
        // backed up by its phrase alone must not read as "0".
        val entry = WalletBackup.KeyEntry("EVM", "Legacy", "0x1", "", privateKey = "0xdead")
        val phraseOnly = WalletBackup.Contents("Main", 1L, "abandon ability", emptyList())
        val keysOnly = WalletBackup.Contents("Main", 1L, null, listOf(entry, entry))
        val both = WalletBackup.Contents("Main", 1L, "abandon ability", listOf(entry))

        assertThat(walletPhraseFor(phraseOnly)).isEqualTo("the recovery phrase")
        assertThat(walletPhraseFor(keysOnly)).isEqualTo("2 imported private key(s)")
        assertThat(walletPhraseFor(both))
            .isEqualTo("the recovery phrase and 1 imported private key(s)")
    }

    @Test
    fun `a profile that carried no content still reports its settings`() {
        // The profile row itself always imports (settings + theme), so the
        // report says what came across rather than listing nothing at all.
        assertThat(importedSummaryLine(summary())).isEqualTo("Imported \"Work\" (settings only)")
    }

    // -------------------------------------------------------- bulk report

    private fun named(
        name: String,
        bookmarks: Int = 0,
        credentials: Int = 0
    ) = ProfileRepositoryImpl.ImportSummary(
        profile = profile(name),
        bookmarks = bookmarks,
        notes = 0,
        sitePermissions = 0,
        siteSettings = 0,
        credentials = credentials,
        totp = 0,
        wallet = null
    )

    @Test
    fun `a batch report names every profile and sums the sections`() {
        val line = importedBundleLine(
            listOf(
                named("Work", bookmarks = 1, credentials = 2),
                named("Home", bookmarks = 2)
            )
        )

        assertThat(line).isEqualTo("Imported 2 profiles — Work, Home (3 bookmarks, 2 passwords)")
    }

    @Test
    fun `a batch of one still reads as a sentence`() {
        // A container may legitimately hold a single profile, and "1 profiles"
        // is the kind of thing a user reads as a bug in the app.
        assertThat(importedBundleLine(listOf(named("Work"))))
            .isEqualTo("Imported 1 profile — Work (settings only)")
    }

    @Test
    fun `the batch report sums wallets across profiles, skips included`() {
        val line = importedBundleLine(
            listOf(
                ProfileRepositoryImpl.ImportSummary(
                    profile = profile("Work"),
                    bookmarks = 0,
                    notes = 0,
                    sitePermissions = 0,
                    siteSettings = 0,
                    credentials = 0,
                    totp = 0,
                    wallet = wallet(phrase = true, derived = 2)
                ),
                ProfileRepositoryImpl.ImportSummary(
                    profile = profile("Home"),
                    bookmarks = 0,
                    notes = 0,
                    sitePermissions = 0,
                    siteSettings = 0,
                    credentials = 0,
                    totp = 0,
                    wallet = wallet(
                        imported = 1,
                        skipped = listOf(RestoreReport.SkippedKey("BTC", "Legacy", "unknown chain"))
                    )
                )
            )
        )

        // Every key the file carried is accounted for: three restored, one
        // named as skipped, and the phrase reported once rather than twice.
        assertThat(line).contains("3 wallet accounts")
        assertThat(line).contains("1 wallet key skipped")
        assertThat(line).contains("recovery phrase")
        assertThat(line).startsWith("Imported 2 profiles — Work, Home (")
    }
}
