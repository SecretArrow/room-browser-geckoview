package com.roombrowser.data.repo

import androidx.room.withTransaction
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.BookmarkEntity
import com.roombrowser.data.db.NoteEntity
import com.roombrowser.data.db.ProfileEntity
import com.roombrowser.data.db.SitePermissionEntity
import com.roombrowser.data.db.SiteSettingEntity
import com.roombrowser.domain.export.ProfileBackup
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.profile.CopyOptions
import com.roombrowser.domain.profile.ProfileStore
import com.roombrowser.security.TotpKeyCrypto
import com.roombrowser.security.VaultCrypto
import com.roombrowser.security.WalletKeyCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Room-backed implementation of the domain ProfileStore.
 * Storage identity = immutable UUID; the profile NAME is never used as an
 * identifier (spec section 46).
 */
class ProfileRepositoryImpl(db: AppDatabase) : ProfileStore {

    /** Kept for the import transaction — the DAO handles below are the same
     *  database's views, so [importBackup] can wrap them all in ONE
     *  [withTransaction]. */
    private val database: AppDatabase = db

    private val dao = db.profileDao()
    private val bookmarkDao = db.bookmarkDao()
    private val tabDao = db.tabDao()
    private val historyDao = db.historyDao()
    private val siteSettingsDao = db.siteSettingsDao()
    private val ipDao = db.ipHistoryDao()
    private val statsDao = db.statsDao()
    private val downloadDao = db.downloadDao()
    private val credentialDao = db.credentialDao()
    private val walletDao = db.walletDao()
    private val walletAccountDao = db.walletAccountDao()
    private val walletNetworkDao = db.walletNetworkDao()
    private val dappPermissionDao = db.dappPermissionDao()
    private val walletActivityDao = db.walletActivityDao()
    private val aiTaskDao = db.aiTaskDao()
    private val noteDao = db.noteDao()
    private val totpDao = db.totpDao()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun observeProfiles(): Flow<List<Profile>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeProfile(id: ProfileId): Flow<Profile?> =
        dao.observeAll().map { list -> list.firstOrNull { it.id == id.value }?.toDomain() }

    suspend fun getProfile(id: ProfileId): Profile? = dao.get(id.value)?.toDomain()

    override suspend fun profiles(): List<Profile> = dao.all().map { it.toDomain() }

    override suspend fun get(id: ProfileId): Profile? = getProfile(id)

    override suspend fun put(profile: Profile) {
        dao.upsert(profile.toEntity())
    }

    override suspend fun remove(id: ProfileId, cascadeData: Boolean) {
        dao.delete(id.value)
        if (cascadeData) {
            tabDao.deleteAllFor(id.value)
            bookmarkDao.deleteAllFor(id.value)
            historyDao.deleteAllFor(id.value)
            siteSettingsDao.deleteAllPermissionsFor(id.value)
            siteSettingsDao.deleteAllSiteSettingsFor(id.value)
            ipDao.deleteAllFor(id.value)
            statsDao.deleteAllFor(id.value)
            downloadDao.deleteAllFor(id.value)
            // Saved logins are profile data too: rows first, THEN the
            // Keystore key. That order fails closed — if anything dies in
            // between, any surviving ciphertext stays permanently
            // undecryptable instead of leaving a decryptable orphan. With
            // cascadeData=false the key is kept on purpose so the profile's
            // data (and its passwords) stay recoverable on restore.
            credentialDao.deleteAllForProfile(id.value)
            VaultCrypto.deleteKey(id)
            // Wallet data is profile data too: same rows-first-then-key
            // order as the credentials cascade (fail closed — a mid-cascade
            // death leaves ciphertext undecryptable, never a decryptable
            // orphan), and the wallet's Keystore key is separate from the
            // password vault's. Accounts go before the wallets row they
            // reference; cascadeData=false keeps the key on purpose so the
            // profile's wallet (and passwords) stay recoverable on restore.
            walletAccountDao.deleteAllForProfile(id.value)
            walletDao.deleteAllForProfile(id.value)
            walletNetworkDao.deleteAllForProfile(id.value)
            walletNetworkDao.deleteActiveNetworksForProfile(id.value)
            dappPermissionDao.deleteAllForProfile(id.value)
            walletActivityDao.deleteAllForProfile(id.value)
            // Scheduled tasks belong to the profile they run against; a task
            // pointing at a deleted profile could never run anyway.
            aiTaskDao.deleteAllForProfile(id.value)
            // Notes are profile content like bookmarks — no keystore key
            // involved, so they simply go with the profile.
            noteDao.deleteAllForProfile(id.value)
            // 2FA accounts: rows first, then their OWN Keystore key — the same
            // fail-closed order as the credential and wallet cascades above.
            totpDao.deleteAllForProfile(id.value)
            TotpKeyCrypto.deleteKey(id)
            WalletKeyCrypto.deleteKey(id)
        }
    }

    override suspend fun updateSettings(id: ProfileId, settings: ProfileSettings) {
        dao.updateSettings(id.value, json.encodeToString(ProfileSettings.serializer(), settings))
    }

    /**
     * Duplicate profile data. Only metadata types (settings/bookmarks/history)
     * can be copied. Cookies/cache/sessions/site data NEVER cross profiles —
     * see CopyOptions and PROFILE_ISOLATION.md.
     */
    override suspend fun copyProfileData(from: ProfileId, to: ProfileId, options: CopyOptions) {
        if (options.bookmarks) {
            val maxPos = bookmarkDao.maxPosition(to.value) ?: 0
            bookmarkDao.all(from.value).forEachIndexed { i, b ->
                bookmarkDao.upsert(
                    b.copy(id = 0, profileId = to.value, position = maxPos + 1 + i)
                )
            }
        }
        if (options.history) {
            historyDao.since(from.value, 0).take(10_000).forEach {
                historyDao.insert(it.copy(id = 0, profileId = to.value))
            }
        }
        // options.settings handled by caller (settings serialized on ProfileEntity)
    }

    override suspend fun resetProfileData(id: ProfileId) {
        tabDao.deleteAllFor(id.value)
        bookmarkDao.deleteAllFor(id.value)
        historyDao.deleteAllFor(id.value)
        siteSettingsDao.deleteAllPermissionsFor(id.value)
        siteSettingsDao.deleteAllSiteSettingsFor(id.value)
        statsDao.deleteAllFor(id.value)
        ipDao.deleteAllFor(id.value)
        noteDao.deleteAllForProfile(id.value)
    }

    suspend fun touch(id: ProfileId, ts: Long) = dao.touch(id.value, ts)

    /** Persist a full per-profile theme snapshot (Theme Studio "Apply"). */
    suspend fun updateTheme(id: ProfileId, themeJson: String) =
        dao.updateTheme(id.value, themeJson)

    /** What a completed import restored — for the confirmation message. The
     *  credential count is the caller's to add: only it knows how many rows
     *  its writeCredentials step carried. */
    data class ImportSummary(val profile: Profile, val bookmarks: Int, val notes: Int)

    /**
     * ONE Room transaction for a whole backup import: profile row + bookmarks
     * + site permissions + site settings + (via [writeCredentials]) the
     * re-encrypted saved logins. Any exception from any write propagates and
     * Room rolls the transaction back — a half-imported profile (rows without
     * their passwords, or passwords without their profile) can never exist.
     *
     * [profile] must already carry its FINAL identity: the caller mints a
     * FRESH UUID (the file's UUID is only a collision heuristic, never
     * reused) and a non-colliding name — this method never overwrites an
     * existing row because the id is new.
     *
     * [writeCredentials] runs INSIDE the transaction block: the caller passes
     * `credentialRepo.importAll(...)` which re-encrypts every password under
     * THIS profile's device vault key with fresh UUIDs. importAll hops through
     * `withContext(Dispatchers.IO)` internally — that is safe here: Room's
     * suspend DAO layer detects the surrounding transaction via its
     * TransactionElement (which survives context switches) and dispatches each
     * DAO call back onto the transaction thread, so those writes join this
     * transaction and roll back with it.
     *
     * [writeTotp] is the 2FA twin of [writeCredentials] and runs under exactly
     * the same rule.
     */
    suspend fun importBackup(
        profile: Profile,
        bookmarks: List<ProfileBackup.BookmarkExport>,
        sitePermissions: List<ProfileBackup.SitePermissionExport>,
        siteSettings: List<ProfileBackup.SiteSettingExport>,
        notes: List<ProfileBackup.NoteExport> = emptyList(),
        writeCredentials: suspend () -> Unit = {},
        writeTotp: suspend () -> Unit = {}
    ): ImportSummary = database.withTransaction {
        val pid = profile.id.value
        dao.upsert(profile.toEntity())
        val now = System.currentTimeMillis()
        // Positions are preserved from the payload (a fresh profile has no
        // bookmarks to collide with); createdAt is local because the format
        // does not carry it.
        bookmarks.forEach { b ->
            bookmarkDao.upsert(
                BookmarkEntity(
                    profileId = pid,
                    url = b.url,
                    title = b.title,
                    folder = b.folder,
                    position = b.position,
                    createdAt = now
                )
            )
        }
        // Same shape as bookmarks: fresh UUIDs and local timestamps, because
        // the format carries neither.
        notes.forEach { n ->
            noteDao.upsert(
                NoteEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    profileId = pid,
                    title = n.title,
                    body = n.body,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        sitePermissions.forEach { p ->
            siteSettingsDao.upsertPermission(
                SitePermissionEntity(
                    profileId = pid,
                    host = p.host,
                    permission = p.permission,
                    decision = p.decision
                )
            )
        }
        siteSettings.forEach { s ->
            siteSettingsDao.upsertSiteSetting(
                SiteSettingEntity(
                    profileId = pid,
                    host = s.host,
                    shieldsDisabled = s.shieldsDisabled,
                    jsEnabled = s.jsEnabled,
                    cookiesBlocked = s.cookiesBlocked,
                    desktopMode = s.desktopMode,
                    autoplayBlocked = s.autoplayBlocked,
                    popupBlocked = s.popupBlocked
                )
            )
        }
        writeCredentials()
        // Same transaction, same rule: the authenticator rows belong to the
        // profile row above or to nothing. TotpRepository.importAll re-encrypts
        // every seed under the new profile's own key with fresh UUIDs.
        writeTotp()
        ImportSummary(profile, bookmarks.size, notes.size)
    }

    private fun ProfileEntity.toDomain(): Profile = Profile(
        id = ProfileId(id),
        name = name,
        icon = icon,
        colorArgb = colorArgb,
        isLocked = isLocked,
        isDefault = isDefault,
        createdAt = createdAt,
        lastActiveAt = lastActiveAt,
        settings = runCatching {
            json.decodeFromString(ProfileSettings.serializer(), settingsJson)
        }.getOrDefault(ProfileSettings()),
        themeJson = themeJson
    )

    private fun Profile.toEntity(): ProfileEntity = ProfileEntity(
        id = id.value,
        name = name,
        icon = icon,
        colorArgb = colorArgb,
        isLocked = isLocked,
        isDefault = isDefault,
        createdAt = createdAt,
        lastActiveAt = lastActiveAt,
        settingsJson = json.encodeToString(ProfileSettings.serializer(), settings),
        themeJson = themeJson
    )
}
