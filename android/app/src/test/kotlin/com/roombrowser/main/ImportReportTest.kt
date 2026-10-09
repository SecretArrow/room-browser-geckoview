package com.roombrowser.main

import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.repo.ProfileRepositoryImpl
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
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
        totp: Int = 0
    ) = ProfileRepositoryImpl.ImportSummary(
        profile = profile(),
        bookmarks = bookmarks,
        notes = notes,
        sitePermissions = sitePermissions,
        siteSettings = siteSettings,
        credentials = credentials,
        totp = totp
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
    fun `the vault is asked for only when a vault-backed part is selected`() {
        assertThat(ExportSections().needsVault).isTrue()
        assertThat(ExportSections(passwords = false).needsVault).isTrue()
        assertThat(ExportSections(totp = false).needsVault).isTrue()
        // Nothing behind the vault: an export with no prompt at all.
        assertThat(ExportSections(passwords = false, totp = false).needsVault).isFalse()
    }

    @Test
    fun `a passwords-only file selects the logins and nothing else`() {
        val file = ExportSections.PASSWORDS_FILE

        assertThat(file.bookmarks).isFalse()
        assertThat(file.notes).isFalse()
        assertThat(file.totp).isFalse()
        assertThat(file.sitePermissions).isFalse()
        assertThat(file.siteSettings).isFalse()
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
    fun `a profile that carried no content still reports its settings`() {
        // The profile row itself always imports (settings + theme), so the
        // report says what came across rather than listing nothing at all.
        assertThat(importedSummaryLine(summary())).isEqualTo("Imported \"Work\" (settings only)")
    }
}
