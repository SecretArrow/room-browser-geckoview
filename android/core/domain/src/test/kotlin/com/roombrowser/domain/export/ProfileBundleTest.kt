package com.roombrowser.domain.export

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.credentials.PasswordVaultCrypto
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import org.junit.Test

/** The multi-profile container around [ProfileBackup]'s single-profile format. */
class ProfileBundleTest {

    private fun payloadFor(name: String, id: String, vault: ProfileBackup.VaultBackup? = null) =
        ProfileBackup.BackupPayload(
            profile = Profile(
                id = ProfileId(id),
                name = name,
                createdAt = 1720000000000,
                lastActiveAt = 1720000000500
            ),
            bookmarks = listOf(ProfileBackup.BookmarkExport("https://$name.test", name)),
            notes = listOf(ProfileBackup.NoteExport("note-$name", "body-$name")),
            vault = vault
        )

    private fun threeProfiles(): List<ProfileBackup.BackupPayload> = listOf(
        payloadFor("alpha", "11111111-2222-3333-4444-555555555555"),
        payloadFor("beta", "66666666-7777-8888-9999-aaaaaaaaaaaa"),
        payloadFor("gamma", "bbbbbbbb-cccc-dddd-eeee-ffffffffffff")
    )

    /** Wrap raw entries in the container by hand, so a test can carry an entry
     *  no exporter would ever write. */
    private fun rawBundle(vararg entries: String, bundleVersion: Int = 1): String =
        """{"bundleVersion": $bundleVersion, "profiles": [${entries.joinToString(",")}]}"""

    @Test
    fun `roundtrip keeps every profile and each one's own sections`() {
        val bundle = ProfileBackup.BackupBundle(profiles = threeProfiles())

        val restored = ProfileBackup.parseBundle(ProfileBackup.serializeBundle(bundle))

        val parsed = restored as ProfileBundleResult.Parsed
        assertThat(parsed.bundle.bundleVersion).isEqualTo(ProfileBackup.BUNDLE_VERSION)
        assertThat(parsed.bundle.profiles.map { it.profile.name })
            .containsExactly("alpha", "beta", "gamma").inOrder()
        assertThat(parsed.bundle.profiles.map { it.bookmarks.single().title })
            .containsExactly("alpha", "beta", "gamma").inOrder()
        assertThat(parsed.bundle.profiles[1].notes.single().body).isEqualTo("body-beta")
        // Each entry keeps the single-profile version number it was written
        // with: the container's version never renumbers the payloads.
        assertThat(parsed.bundle.profiles.map { it.formatVersion }.distinct())
            .containsExactly(ProfileBackup.FORMAT_VERSION)
    }

    @Test
    fun `sealed blocks stay per-profile and still refuse a wrong passphrase`() {
        val mine = PasswordVaultCrypto.encrypt(
            """[{"id":"c1","domain":"a.test","username":"ada","password":"hunter2"}]""",
            "one passphrase for the whole file".toCharArray()
        )
        val bundle = ProfileBackup.BackupBundle(
            profiles = listOf(
                payloadFor("alpha", "11111111-2222-3333-4444-555555555555", vault = ProfileBackup.VaultBackup.from(mine)),
                payloadFor("beta", "66666666-7777-8888-9999-aaaaaaaaaaaa")
            )
        )

        val parsed = ProfileBackup.parseBundle(ProfileBackup.serializeBundle(bundle))
            as ProfileBundleResult.Parsed

        assertThat(parsed.bundle.profiles[0].vault).isNotNull()
        assertThat(parsed.bundle.profiles[1].vault).isNull()
        val opened = PasswordVaultCrypto.decrypt(
            parsed.bundle.profiles[0].vault!!.toCipherData(),
            "one passphrase for the whole file".toCharArray()
        )
        assertThat(opened).contains("hunter2")
        assertThat(
            runCatching {
                PasswordVaultCrypto.decrypt(
                    parsed.bundle.profiles[0].vault!!.toCipherData(),
                    "not the passphrase".toCharArray()
                )
            }.isFailure
        ).isTrue()
    }

    @Test
    fun `a single-profile file is not mistaken for a bundle, and the reverse`() {
        val single = ProfileBackup.serialize(payloadFor("alpha", "11111111-2222-3333-4444-555555555555"))
        val bundle = ProfileBackup.serializeBundle(
            ProfileBackup.BackupBundle(profiles = threeProfiles())
        )

        assertThat(ProfileBackup.isBundle(single)).isFalse()
        assertThat(ProfileBackup.isBundle(bundle)).isTrue()
        // Not JSON at all: false here, and `parse` owns the message for it.
        assertThat(ProfileBackup.isBundle("#!/bin/sh\necho hi")).isFalse()
        // The single-profile door must not half-read a container either.
        assertThat(ProfileBackup.parse(bundle)).isInstanceOf(ProfileBackupResult.Malformed::class.java)
    }

    @Test
    fun `one unusable entry rejects the whole file, never a partial restore`() {
        val newer = ProfileBackup.serialize(
            payloadFor("alpha", "11111111-2222-3333-4444-555555555555")
        ).replaceFirst("\"formatVersion\": ${ProfileBackup.FORMAT_VERSION}", "\"formatVersion\": 99")
        val good = ProfileBackup.serialize(
            payloadFor("beta", "66666666-7777-8888-9999-aaaaaaaaaaaa")
        )

        val restored = ProfileBackup.parseBundle(rawBundle(good, newer))

        val refused = restored as ProfileBundleResult.InvalidVersion
        assertThat(refused.found).isEqualTo(99)
        assertThat(refused.maxSupported).isEqualTo(ProfileBackup.FORMAT_VERSION)
    }

    @Test
    fun `a newer container is refused and an empty one is malformed`() {
        val good = ProfileBackup.serialize(
            payloadFor("alpha", "11111111-2222-3333-4444-555555555555")
        )

        val newer = ProfileBackup.parseBundle(rawBundle(good, bundleVersion = 2))
        assertThat((newer as ProfileBundleResult.InvalidVersion).found).isEqualTo(2)

        val empty = ProfileBackup.parseBundle(rawBundle(good, bundleVersion = 0))
        assertThat(empty).isInstanceOf(ProfileBundleResult.Malformed::class.java)

        assertThat(ProfileBackup.parseBundle("""{"bundleVersion":1,"profiles":[]}"""))
            .isInstanceOf(ProfileBundleResult.Malformed::class.java)
        assertThat(ProfileBackup.parseBundle("   "))
            .isInstanceOf(ProfileBundleResult.Malformed::class.java)
        assertThat(ProfileBackup.parseBundle("""{"bundleVersion":1}"""))
            .isInstanceOf(ProfileBundleResult.Malformed::class.java)
    }

    @Test
    fun `more profiles than the container will restore is refused, not truncated`() {
        val one = ProfileBackup.serialize(
            payloadFor("alpha", "11111111-2222-3333-4444-555555555555")
        )
        val over = rawBundle(*Array(ProfileBackup.MAX_BUNDLE_PROFILES + 1) { one })

        val refused = ProfileBackup.parseBundle(over) as ProfileBundleResult.Malformed

        assertThat(refused.detail).contains("${ProfileBackup.MAX_BUNDLE_PROFILES + 1}")
        assertThat(refused.detail).contains("${ProfileBackup.MAX_BUNDLE_PROFILES}")
        // Exactly at the cap is still fine.
        val atCap = rawBundle(*Array(ProfileBackup.MAX_BUNDLE_PROFILES) { one })
        assertThat(ProfileBackup.parseBundle(atCap))
            .isInstanceOf(ProfileBundleResult.Parsed::class.java)
    }

    @Test
    fun `a sealed bundle carries no plaintext secret`() {
        val secret = "hunter2-trustno1-unique"
        val sealed = PasswordVaultCrypto.encrypt(
            """[{"id":"c1","domain":"a.test","username":"ada","password":"$secret"}]""",
            "correct horse battery".toCharArray()
        )
        val text = ProfileBackup.serializeBundle(
            ProfileBackup.BackupBundle(
                profiles = listOf(
                    payloadFor("alpha", "11111111-2222-3333-4444-555555555555", vault = ProfileBackup.VaultBackup.from(sealed))
                )
            )
        )

        assertThat(text).doesNotContain(secret)
        // The base64 alphabet has no quote, so a quoted key can only come from
        // the format itself -- and none of these is in it.
        ProfileBackup.neverExported.forEach { assertThat(text.lowercase()).doesNotContain("\"$it\"") }
    }
}
