package com.roombrowser.domain.export

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.credentials.PasswordVaultCrypto
import com.roombrowser.domain.credentials.SavedCredential
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Test

class ProfileBackupTest {

    private fun profile() = Profile(
        id = ProfileId("11111111-2222-3333-4444-555555555555"),
        name = "Research",
        createdAt = 1720000000000
    )

    private fun payload(vault: ProfileBackup.VaultBackup? = null) =
        ProfileBackup.BackupPayload(
            profile = profile(),
            bookmarks = listOf(ProfileBackup.BookmarkExport("https://example.com", "Example")),
            sitePermissions = listOf(
                ProfileBackup.SitePermissionExport("example.com", "CAMERA", "BLOCK"),
                ProfileBackup.SitePermissionExport("no-script.net", "POPUPS", "ASK")
            ),
            siteSettings = listOf(
                ProfileBackup.SiteSettingExport(
                    host = "example.com", jsEnabled = false, desktopMode = true
                )
            ),
            vault = vault
        )

    /** A real sealed vault + the exact credential JSON that went into it. */
    private fun sealedVault(): Pair<ProfileBackup.VaultBackup, String> {
        val plaintext = """
            [{"id":"c1","profileId":"11111111-2222-3333-4444-555555555555",
              "domain":"example.com","username":"ada","password":"hunter2-trustno1",
              "title":null,"createdAt":1,"updatedAt":2}]
        """.trimIndent()
        val cipher = PasswordVaultCrypto.encrypt(plaintext, "correct horse battery".toCharArray())
        return ProfileBackup.VaultBackup.from(cipher) to plaintext
    }

    @Test
    fun `roundtrip v2 preserves profile, site data and the sealed vault`() {
        val (vault, _) = sealedVault()
        val raw = ProfileBackup.serialize(payload(vault = vault))

        val restored = ProfileBackup.parse(raw) as ProfileBackupResult.Parsed

        assertThat(restored.payload.formatVersion).isEqualTo(2)
        assertThat(restored.payload.profile.id.value)
            .isEqualTo("11111111-2222-3333-4444-555555555555")
        assertThat(restored.payload.profile.name).isEqualTo("Research")
        assertThat(restored.payload.profile.settings.searchEngineId).isEqualTo("duckduckgo")
        assertThat(restored.payload.bookmarks.single().title).isEqualTo("Example")
        assertThat(restored.payload.sitePermissions).hasSize(2)
        assertThat(restored.payload.sitePermissions.first().decision).isEqualTo("BLOCK")
        assertThat(restored.payload.siteSettings.single().host).isEqualTo("example.com")
        assertThat(restored.payload.siteSettings.single().jsEnabled).isFalse()
        assertThat(restored.payload.siteSettings.single().desktopMode).isTrue()
        assertThat(restored.payload.siteSettings.single().autoplayBlocked).isNull()
        assertThat(restored.payload.vault).isEqualTo(vault)
    }

    @Test
    fun `v1 file without vault or site data still imports`() {
        // Exactly what the v1 exporter wrote: formatVersion 1, profile
        // (ProfileId serializes as {"value": …}), bookmarks — no vault, and
        // the never-populated site lists absent.
        val v1 = """
            {
              "formatVersion": 1,
              "profile": {
                "id": { "value": "11111111-2222-3333-4444-555555555555" },
                "name": "Research",
                "createdAt": 1720000000000
              },
              "bookmarks": [ { "url": "https://example.com", "title": "Example" } ]
            }
        """.trimIndent()

        val restored = ProfileBackup.parse(v1) as ProfileBackupResult.Parsed

        assertThat(restored.payload.formatVersion).isEqualTo(1)
        assertThat(restored.payload.vault).isNull()
        assertThat(restored.payload.sitePermissions).isEmpty()
        assertThat(restored.payload.siteSettings).isEmpty()
        assertThat(restored.payload.notes).isEmpty()
        assertThat(restored.payload.bookmarks.single().url).isEqualTo("https://example.com")
    }

    @Test
    fun `notes round-trip through a v2 export`() {
        val withNotes = ProfileBackup.BackupPayload(
            profile = profile(),
            notes = listOf(
                ProfileBackup.NoteExport(title = "groceries", body = "milk\neggs"),
                ProfileBackup.NoteExport(title = "", body = "untitled scrap")
            )
        )

        val restored = ProfileBackup.parse(ProfileBackup.serialize(withNotes))
            as ProfileBackupResult.Parsed

        // Still v2: notes are an additive field with a default, so the format
        // version does not move for them (see the versioning contract).
        assertThat(restored.payload.formatVersion).isEqualTo(2)
        assertThat(restored.payload.notes).hasSize(2)
        assertThat(restored.payload.notes.first().title).isEqualTo("groceries")
        assertThat(restored.payload.notes.first().body).isEqualTo("milk\neggs")
        assertThat(restored.payload.notes[1].body).isEqualTo("untitled scrap")
    }

    @Test
    fun `v2 backup written before notes existed still imports`() {
        // Exactly what a pre-notes exporter wrote: a v2 object with no `notes`
        // key at all. The field must default to empty, not reject the file.
        val beforeNotes = """
            {
              "formatVersion": 2,
              "profile": {
                "id": { "value": "11111111-2222-3333-4444-555555555555" },
                "name": "Research",
                "createdAt": 1720000000000
              },
              "bookmarks": [ { "url": "https://example.com", "title": "Example" } ]
            }
        """.trimIndent()

        val restored = ProfileBackup.parse(beforeNotes) as ProfileBackupResult.Parsed

        assertThat(restored.payload.notes).isEmpty()
        assertThat(restored.payload.bookmarks.single().title).isEqualTo("Example")
    }

    @Test
    fun `legacy v1 siteSettings shape is tolerated`() {
        // A v1 file that somehow carried the old {host, settingsJson} shape:
        // the unknown key is ignored and the toggles default to null
        // ("inherit the profile setting") — never a parse failure.
        val legacy = """
            {
              "formatVersion": 1,
              "profile": {
                "id": { "value": "11111111-2222-3333-4444-555555555555" },
                "name": "Research",
                "createdAt": 1720000000000
              },
              "siteSettings": [ { "host": "example.com", "settingsJson": "{\"js\":false}" } ]
            }
        """.trimIndent()

        val restored = ProfileBackup.parse(legacy) as ProfileBackupResult.Parsed

        assertThat(restored.payload.siteSettings.single().host).isEqualTo("example.com")
        assertThat(restored.payload.siteSettings.single().jsEnabled).isNull()
        assertThat(restored.payload.siteSettings.single().shieldsDisabled).isNull()
    }

    @Test
    fun `newer format version is rejected with the supported maximum`() {
        val raw = ProfileBackup.serialize(payload())
            .replace(Regex("(\"formatVersion\"\\s*:\\s*)\\d+"), "$1" + "99")

        val result = ProfileBackup.parse(raw)

        assertThat(result)
            .isEqualTo(ProfileBackupResult.InvalidVersion(99, ProfileBackup.FORMAT_VERSION))
    }

    @Test
    fun `export never contains plaintext passwords or unencrypted vault fields`() {
        val (vault, plaintext) = sealedVault()
        val raw = ProfileBackup.serialize(payload(vault = vault))

        // No plaintext password anywhere in the file…
        assertThat(raw).doesNotContain("hunter2-trustno1")
        // …and no unencrypted vault fields (the credential array exists only
        // as ciphertext; base64 cannot contain a quote, so these quoted-key
        // checks cannot false-positive on blob content).
        assertThat(raw).doesNotContain("\"password\"")
        assertThat(raw).doesNotContain("\"credentials\"")
        ProfileBackup.neverExported.forEach { forbidden ->
            assertThat(raw.lowercase()).doesNotContain("\"$forbidden\"")
        }
        // The data is sealed, not missing: the blob really decrypts back to
        // the credential array with the passphrase.
        val opened = PasswordVaultCrypto.decrypt(
            vault.toCipherData(), "correct horse battery".toCharArray()
        )
        assertThat(opened).isEqualTo(plaintext)
    }

    @Test
    fun `malformed input is rejected, never half-parsed`() {
        assertThat(ProfileBackup.parse(""))
            .isInstanceOf(ProfileBackupResult.Malformed::class.java)
        assertThat(ProfileBackup.parse("   "))
            .isInstanceOf(ProfileBackupResult.Malformed::class.java)
        assertThat(ProfileBackup.parse("not json at all"))
            .isInstanceOf(ProfileBackupResult.Malformed::class.java)
        // Valid JSON, wrong shape entirely.
        assertThat(ProfileBackup.parse("[]"))
            .isInstanceOf(ProfileBackupResult.Malformed::class.java)
        // Valid object, but no profile.
        assertThat(ProfileBackup.parse("""{"formatVersion":2}"""))
            .isInstanceOf(ProfileBackupResult.Malformed::class.java)
        // A profile with a blank id violates the payload invariant.
        assertThat(
            ProfileBackup.parse(
                """{"formatVersion":2,"profile":{"id":{"value":""},"name":"X","createdAt":1}}"""
            )
        ).isInstanceOf(ProfileBackupResult.Malformed::class.java)
        // An unusable version number.
        val zeroVersion = ProfileBackup.serialize(payload())
            .replace(Regex("(\"formatVersion\"\\s*:\\s*)\\d+"), "$1" + "0")
        assertThat(ProfileBackup.parse(zeroVersion))
            .isInstanceOf(ProfileBackupResult.Malformed::class.java)
    }

    @Test
    fun `unknown keys tolerated for forward compatibility`() {
        val raw = ProfileBackup.serialize(payload()).replaceFirst(
            "{",
            "{\"futureField\":42,"
        )

        val restored = ProfileBackup.parse(raw) as ProfileBackupResult.Parsed

        assertThat(restored.payload.profile.name).isEqualTo("Research")
        assertThat(restored.payload.sitePermissions).hasSize(2)
    }

    @Test
    fun `full export-import codec chain restores the credentials verbatim`() {
        // The exact chain the app runs (MainViewModel): SavedCredential array
        // → JSON → PasswordVaultCrypto.encrypt → VaultBackup → file → parse
        // → decrypt → SavedCredential array. Proves the two ends agree on the
        // codec, so an export made by one build imports on another.
        val creds = listOf(
            SavedCredential(
                id = "c1",
                profileId = "11111111-2222-3333-4444-555555555555",
                domain = "example.com",
                username = "ada",
                password = "hunter2-trustno1",
                title = "Work",
                createdAt = 1,
                updatedAt = 2
            )
        )
        val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val plaintext = codec.encodeToString(ListSerializer(SavedCredential.serializer()), creds)

        val cipher = PasswordVaultCrypto.encrypt(plaintext, "correct horse battery".toCharArray())
        val file = ProfileBackup.serialize(payload(vault = ProfileBackup.VaultBackup.from(cipher)))

        val parsed = ProfileBackup.parse(file) as ProfileBackupResult.Parsed
        val vault = parsed.payload.vault ?: error("vault missing")
        val opened = PasswordVaultCrypto.decrypt(vault.toCipherData(), "correct horse battery".toCharArray())
        val restoredCreds =
            codec.decodeFromString(ListSerializer(SavedCredential.serializer()), opened)

        assertThat(restoredCreds).isEqualTo(creds)
    }
}
