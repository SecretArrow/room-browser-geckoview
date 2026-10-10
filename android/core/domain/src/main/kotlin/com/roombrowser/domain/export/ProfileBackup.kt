package com.roombrowser.domain.export

import com.roombrowser.domain.credentials.PasswordVaultCrypto
import com.roombrowser.domain.model.Profile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
 * The verdict on a multi-profile file. Same shape and same rule as
 * [ProfileBackupResult]: [Parsed] carries every profile in the file or the
 * call answers with exactly one rejection, so a five-profile file can never
 * restore three of them and call it a success.
 */
sealed interface ProfileBundleResult {
    data class Parsed(val bundle: ProfileBackup.BackupBundle) : ProfileBundleResult

    data class InvalidVersion(val found: Int, val maxSupported: Int) : ProfileBundleResult

    data class Malformed(val detail: String) : ProfileBundleResult
}

/**
 * Profile backup / restore — export format v4 (spec section 29).
 *
 * ## Schema (formatVersion 4)
 *
 * ```
 * {
 *   "formatVersion": 4,
 *   "profile":      { ...full Profile, incl. settings + themeJson... },
 *   "bookmarks":    [ { url, title, folder?, position } ],
 *   "sitePermissions": [ { host, permission, decision } ],
 *   "siteSettings": [ { host, shieldsDisabled?, jsEnabled?, cookiesBlocked?,
 *                        desktopMode?, autoplayBlocked?, popupBlocked? } ],
 *   "notes":        [ { title, body } ],
 *   "vault":  { scheme, saltB64, iterations, ivB64, ciphertextB64 } | null,
 *   "totp":   { scheme, saltB64, iterations, ivB64, ciphertextB64 } | null,
 *   "wallet": { scheme, saltB64, iterations, ivB64, ciphertextB64 } | null
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
 *  - **v4**: the file may additionally carry `wallet` — the profile's wallet
 *    keys (recovery phrase and imported private keys), sealed under the same
 *    export passphrase as `vault` and `totp`, again as an independent
 *    ciphertext. v1–v3 files still import (`wallet` is null for them). The
 *    bump exists for the same reason as v3's, and the stakes are higher: an
 *    older build that imported this file without the block would restore
 *    every other section and silently produce a profile whose wallet is
 *    gone, with the user's money behind it.
 *  - **Future versions** (`formatVersion > [FORMAT_VERSION]`) are rejected
 *    with [ProfileBackupResult.InvalidVersion] — never a partial parse, and
 *    the version's fields are not guessed at.
 *  - Unknown keys inside a SUPPORTED version are ignored (forward tolerance
 *    within a version).
 *
 * ## Multi-profile files
 * A backup of SEVERAL profiles at once is the same payloads in a container:
 *
 * ```
 * { "bundleVersion": 1, "profiles": [ { ...BackupPayload... }, ... ] }
 * ```
 *
 * The container has its own version, deliberately separate from
 * `formatVersion`: each entry keeps the single-profile number it was written
 * with, so a bundle of v4 profiles stays readable by the same checks that
 * govern one profile, and a change to the container never renumbers them.
 * [isBundle] tells the two file kinds apart on the top-level `profiles` key —
 * never on the version number, which both kinds carry.
 *
 * ## Never-exported guarantees
 * There is no field for cookies, sessions, cache, IndexedDB, localStorage or
 * browsing history — enforced by construction. Saved passwords exist ONLY
 * inside `vault.ciphertextB64`, authenticator seeds only inside
 * `totp.ciphertextB64` and wallet keys only inside `wallet.ciphertextB64`
 * (all authenticated encryption); a plaintext password, seed or private key
 * NEVER appears anywhere in the export JSON.
 */
object ProfileBackup {

    const val FORMAT_VERSION = 4

    /** The multi-profile container's own version, independent of
     *  [FORMAT_VERSION] — see the "Multi-profile files" contract above. */
    const val BUNDLE_VERSION = 1

    /** A ceiling on how many entries one file may carry. Not a product limit:
     *  it is the bound on what a hostile or corrupt file can make this app
     *  decode, restore and hold in memory at once. */
    const val MAX_BUNDLE_PROFILES = 64

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
        val totp: VaultBackup? = null,
        /** The profile's wallet keys, sealed with the SAME export passphrase
         *  the other two blocks use and again as an INDEPENDENT ciphertext.
         *  null = the profile had no wallet, which is also what every v1–v3
         *  file carries. Content is sealed and opened by
         *  [com.roombrowser.domain.export.WalletBackup.sealBlock] /
         *  [com.roombrowser.domain.export.WalletBackup.openBlock].
         *
         *  THIS BLOCK IS MONEY, unlike the other two. A file carrying it is
         *  worth exactly what the wallet holds, so the export UI leaves it
         *  unticked and says so; anything that puts it in a file without the
         *  user asking has handed out the wallet. */
        val wallet: VaultBackup? = null
    ) {
        init {
            require(profile.id.value.isNotBlank())
        }
    }

    /**
     * Several profiles in one file. Each entry is a verbatim [BackupPayload],
     * so every per-profile guarantee above holds unchanged; this type adds
     * only the container.
     */
    @Serializable
    data class BackupBundle(
        val bundleVersion: Int = BUNDLE_VERSION,
        val profiles: List<BackupPayload> = emptyList()
    ) {
        init {
            require(profiles.isNotEmpty()) { "a bundle carries at least one profile" }
        }
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun serialize(payload: BackupPayload): String = json.encodeToString(BackupPayload.serializer(), payload)

    fun serializeBundle(bundle: BackupBundle): String =
        json.encodeToString(BackupBundle.serializer(), bundle)

    /**
     * Does this text hold SEVERAL profiles rather than one? Read from the
     * top-level `profiles` key: a single payload has no such key, and the two
     * kinds share a version number's shape, so the version cannot tell them
     * apart. Text that is not JSON at all answers false and is then rejected
     * by [parse], which has the better message for it.
     */
    fun isBundle(text: String): Boolean = runCatching {
        (json.parseToJsonElement(text) as? JsonObject)?.containsKey("profiles") == true
    }.getOrDefault(false)

    /**
     * Validate + decode a multi-profile file. The same rule as [parse]: one
     * fully decoded bundle, or one rejection. A single unusable entry rejects
     * the whole file — a bulk restore that quietly skipped a profile would
     * leave the user believing they had restored it.
     */
    fun parseBundle(text: String): ProfileBundleResult {
        if (text.isBlank()) {
            return ProfileBundleResult.Malformed("the file is empty")
        }
        val bundle = try {
            json.decodeFromString(BackupBundle.serializer(), text)
        } catch (e: SerializationException) {
            return ProfileBundleResult.Malformed(
                e.message?.lineSequence()?.firstOrNull()
                    ?: "not a Room Browser multi-profile export"
            )
        } catch (e: IllegalArgumentException) {
            return ProfileBundleResult.Malformed(e.message ?: "the profile data is not valid")
        }
        if (bundle.bundleVersion > BUNDLE_VERSION) {
            return ProfileBundleResult.InvalidVersion(bundle.bundleVersion, BUNDLE_VERSION)
        }
        if (bundle.bundleVersion < 1) {
            return ProfileBundleResult.Malformed(
                "bundleVersion must be at least 1, found ${bundle.bundleVersion}"
            )
        }
        if (bundle.profiles.size > MAX_BUNDLE_PROFILES) {
            return ProfileBundleResult.Malformed(
                "that file holds ${bundle.profiles.size} profiles; the most this app " +
                    "restores at once is $MAX_BUNDLE_PROFILES"
            )
        }
        bundle.profiles.forEach { entry ->
            if (entry.formatVersion > FORMAT_VERSION) {
                return ProfileBundleResult.InvalidVersion(entry.formatVersion, FORMAT_VERSION)
            }
            if (entry.formatVersion < 1) {
                return ProfileBundleResult.Malformed(
                    "formatVersion must be at least 1, found ${entry.formatVersion}"
                )
            }
        }
        return ProfileBundleResult.Parsed(bundle)
    }

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
