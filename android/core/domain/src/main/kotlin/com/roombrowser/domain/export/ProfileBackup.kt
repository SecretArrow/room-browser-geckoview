package com.roombrowser.domain.export

import com.roombrowser.domain.credentials.PasswordVaultCrypto
import com.roombrowser.domain.model.Profile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The single verdict on an import candidate: one valid outcome, or one clear
 * rejection. [ProfileBackup.parse] NEVER returns a half-parsed profile —
 * [Parsed] only exists for a fully decoded, version-supported payload.
 */
sealed interface ProfileBackupResult {
    /** A complete, supported payload — safe to restore. */
    data class Parsed(val payload: ProfileBackup.BackupPayload) : ProfileBackupResult

    /** The file was written by a NEWER app (`formatVersion` above what this
     *  build understands). Restoring it is refused, not guessed at. */
    data class InvalidVersion(val found: Int, val maxSupported: Int) : ProfileBackupResult

    /** Not a Room Browser export at all (bad JSON, missing profile, blank
     *  id, unusable version number). [detail] is safe to show the user. */
    data class Malformed(val detail: String) : ProfileBackupResult
}

/**
 * Profile backup / restore — export format v3 (spec section 29).
 *
 * ## Schema (formatVersion 3)
 *
 * ```
 * {
 *   "formatVersion": 3,
 *   "profile":      { ...full Profile, incl. settings + themeJson... },
 *   "bookmarks":    [ { url, title, folder?, position } ],
 *   "sitePermissions": [ { host, permission, decision } ],
 *   "siteSettings": [ { host, shieldsDisabled?, jsEnabled?, cookiesBlocked?,
 *                        desktopMode?, autoplayBlocked?, popupBlocked? } ],
 *   "notes":        [ { title, body } ],
 *   "vault": { scheme, saltB64, iterations, ivB64, ciphertextB64 } | null,
 *   "totp":  { scheme, saltB64, iterations, ivB64, ciphertextB64 } | null
 * }
 * ```
 *
 * `sitePermissions` / `siteSettings` mirror the app's Room entities minus the
 * profile id (which is always remapped on import); a null toggle means
 * "inherit the profile setting", exactly like the entity.
 *
 * ## Versioning contract
 *  - **v1 files** (formatVersion 1: no `vault`; `sitePermissions` /
 *    `siteSettings` were declared but never populated by the v1 exporter)
 *    still parse and import — they restore profile + bookmarks only.
 *    A legacy v1 `siteSettings` entry shaped `{ host, settingsJson }` is
 *    tolerated: the unknown `settingsJson` key is ignored and the toggles
 *    default to null (inherit).
 *  - **v2**: when `vault != null` the file carries the profile's saved
 *    logins sealed under the user's export passphrase — the importer MUST
 *    decrypt it with [PasswordVaultCrypto] before anything is written;
 *    `vault == null` means the profile had no saved logins.
 *  - **v3**: the file may additionally carry `totp` — the profile's
 *    authenticator accounts, sealed under the same export passphrase as
 *    `vault` but as an independent ciphertext. v1 and v2 files still import
 *    (`totp` is null for them); the bump exists so an OLDER build refuses a
 *    v3 file rather than importing it minus the authenticator secrets —
 *    silently dropping authenticator seeds is the unacceptable outcome.
 *  - **Future versions** (`formatVersion > [FORMAT_VERSION]`) are rejected
 *    with [ProfileBackupResult.InvalidVersion] — never a partial parse, and
 *    the version's fields are not guessed at.
 *  - Unknown keys inside a SUPPORTED version are ignored (forward tolerance
 *    within a version).
 *
 * ## Never-exported guarantees
 * There is no field for cookies, sessions, cache, IndexedDB, localStorage or
 * browsing history — enforced by construction. Saved passwords exist ONLY
 * inside `vault.ciphertextB64` (authenticated encryption); a plaintext
 * password NEVER appears anywhere in the export JSON.
 */
object ProfileBackup {

    const val FORMAT_VERSION = 3

    @Serializable
    data class BookmarkExport(val url: String, val title: String, val folder: String? = null, val position: Int = 0)

    /** Mirrors SitePermissionEntity (profileId omitted — remapped on import). */
    @Serializable
    data class SitePermissionExport(val host: String, val permission: String, val decision: String)

    /**
     * Mirrors SiteSettingEntity (profileId omitted — remapped on import).
     * Every toggle is nullable: null = "inherit the profile setting", the
     * entity's own semantics. (The never-populated v1 DTO carried a
     * `settingsJson` string instead; such keys are ignored on read.)
     */
    @Serializable
    data class SiteSettingExport(
        val host: String,
        val shieldsDisabled: Boolean? = null,
        val jsEnabled: Boolean? = null,
        val cookiesBlocked: Boolean? = null,
        val desktopMode: Boolean? = null,
        val autoplayBlocked: Boolean? = null,
        val popupBlocked: Boolean? = null
    )

    /**
     * Mirrors NoteEntity (profileId omitted — remapped on import). Timestamps
     * are not carried: an imported note gets fresh ones, like a bookmark.
     */
    @Serializable
    data class NoteExport(val title: String, val body: String)

    /**
     * The profile's saved logins, sealed under the user's export passphrase
     * by [PasswordVaultCrypto] (scheme "pbkdf2-sha256-aes256-gcm"). Field
     * set = the cipher's JSON shape; the plaintext credential array exists
     * only inside `ciphertextB64`. `null` in a payload = no saved logins.
     */
    @Serializable
    data class VaultBackup(
        val scheme: String,
        val saltB64: String,
        val iterations: Int,
        val ivB64: String,
        val ciphertextB64: String
    ) {
        companion object {
            fun from(cipher: PasswordVaultCrypto.VaultCipherData): VaultBackup = VaultBackup(
                scheme = cipher.scheme,
                saltB64 = cipher.saltB64,
                iterations = cipher.iterations,
                ivB64 = cipher.ivB64,
                ciphertextB64 = cipher.ciphertextB64
            )
        }

        /** Back to the crypto layer's own shape for decrypt/encrypt calls. */
        fun toCipherData(): PasswordVaultCrypto.VaultCipherData =
            PasswordVaultCrypto.VaultCipherData(
                scheme = scheme,
                saltB64 = saltB64,
                iterations = iterations,
                ivB64 = ivB64,
                ciphertextB64 = ciphertextB64
            )
    }

    @Serializable
    data class BackupPayload(
        val formatVersion: Int = FORMAT_VERSION,
        val profile: Profile,
        val bookmarks: List<BookmarkExport> = emptyList(),
        val sitePermissions: List<SitePermissionExport> = emptyList(),
        val siteSettings: List<SiteSettingExport> = emptyList(),
        /** Per-profile notes. Absent in backups written before notes existed,
         *  which is why the field defaults to empty rather than needing a
         *  format bump (see the versioning contract above). */
        val notes: List<NoteExport> = emptyList(),
        /** null = this profile has no saved logins (v1 files, or a v2 export
         *  of a profile whose vault was empty). */
        val vault: VaultBackup? = null,
        /** The profile's authenticator accounts, sealed with the SAME export
         *  passphrase the `vault` block uses but as an INDEPENDENT ciphertext
         *  (one prompt, two blobs). null = the profile had no authenticator
         *  accounts, which is also what every v1/v2 file carries. Content is
         *  sealed and opened by
         *  [com.roombrowser.domain.totp.TotpBackup.sealContents] /
         *  [com.roombrowser.domain.totp.TotpBackup.openContents]. */
        val totp: VaultBackup? = null
    ) {
        init {
            require(profile.id.value.isNotBlank())
        }
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun serialize(payload: BackupPayload): String = json.encodeToString(BackupPayload.serializer(), payload)

    /**
     * Validate + decode an export file. This is the ONLY import door: it
     * returns the full verdict instead of throwing, so a caller can never
     * mistake a rejection for data. [ProfileBackupResult.Parsed] carries a
     * complete payload or the call answers with exactly one rejection —
     * a profile is never half-parsed.
     */
    fun parse(text: String): ProfileBackupResult {
        if (text.isBlank()) {
            return ProfileBackupResult.Malformed("the file is empty")
        }
        val payload = try {
            json.decodeFromString(BackupPayload.serializer(), text)
        } catch (e: SerializationException) {
            // Missing/ill-typed fields, unknown structure, bad enum names.
            // kotlinx messages can be multi-line — keep the first for the UI.
            return ProfileBackupResult.Malformed(
                e.message?.lineSequence()?.firstOrNull() ?: "not a Room Browser export"
            )
        } catch (e: IllegalArgumentException) {
            // BackupPayload/ProfileId invariants (e.g. a blank profile id).
            return ProfileBackupResult.Malformed(e.message ?: "the profile data is not valid")
        }
        return when {
            payload.formatVersion > FORMAT_VERSION ->
                ProfileBackupResult.InvalidVersion(payload.formatVersion, FORMAT_VERSION)
            payload.formatVersion < 1 ->
                ProfileBackupResult.Malformed(
                    "formatVersion must be at least 1, found ${payload.formatVersion}"
                )
            else -> ProfileBackupResult.Parsed(payload)
        }
    }

    /**
     * The backup never contains these as fields — enforced by construction
     * (there is no such key anywhere in the schema). Passwords ride ONLY
     * inside the sealed `vault` blob, so their names never appear as JSON
     * keys either — base64 cannot contain the quote character, which is what
     * makes the quoted-key check below sound.
     */
    val neverExported = listOf(
        "cookies", "sessions", "cache", "credentials", "passwords",
        "indexeddb", "localstorage", "browsing history"
    )
}
