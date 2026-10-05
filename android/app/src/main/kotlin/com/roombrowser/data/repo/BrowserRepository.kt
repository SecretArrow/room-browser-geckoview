package com.roombrowser.data.repo

import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.BlockEventEntity
import com.roombrowser.data.db.BookmarkEntity
import com.roombrowser.data.db.CategoryCount
import com.roombrowser.data.db.DownloadEntity
import com.roombrowser.data.db.HistoryEntity
import com.roombrowser.data.db.IpHistoryEntity
import com.roombrowser.data.db.NoteEntity
import com.roombrowser.data.db.SitePermissionEntity
import com.roombrowser.data.db.SiteSettingEntity
import com.roombrowser.data.db.TabEntity
import kotlinx.coroutines.flow.first
import com.roombrowser.domain.model.PermissionDecision
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.profile.ProfileDirectoryLayout
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

enum class PermissionKind { CAMERA, MICROPHONE, LOCATION, NOTIFICATIONS, CLIPBOARD, BLUETOOTH, USB, POPUPS, DOWNLOADS, SENSORS, AUTOPLAY }

/**
 * Facade over the per-profile browser data DAOs.
 * Every query is scoped by profileId — nothing is ever cross-profile.
 */
class BrowserRepository(private val db: AppDatabase) {

    private val tabs = db.tabDao()
    private val bookmarks = db.bookmarkDao()
    private val history = db.historyDao()
    private val downloads = db.downloadDao()
    private val site = db.siteSettingsDao()
    private val ip = db.ipHistoryDao()
    private val stats = db.statsDao()
    private val notes = db.noteDao()

    // ---------- Tabs ----------
    fun observeTabs(profileId: ProfileId): Flow<List<TabEntity>> = tabs.observeOpen(profileId.value)
    fun observeTabCount(profileId: ProfileId): Flow<Int> = tabs.observeCount(profileId.value)
    suspend fun openTabs(profileId: ProfileId): List<TabEntity> = tabs.openTabs(profileId.value)
    suspend fun tab(id: String): TabEntity? = tabs.get(id)

    suspend fun newTab(profileId: ProfileId, url: String, title: String, isPrivate: Boolean = false): TabEntity {
        // The id/timestamps are minted here; the POSITION is computed inside
        // the insert statement itself (max over ALL rows incl. closed) —
        // see TabDao.insertNextPosition for why that must stay atomic.
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        tabs.insertNextPosition(
            id = id,
            profileId = profileId.value,
            title = title,
            url = url,
            isPrivate = isPrivate,
            createdAt = now,
            lastViewedAt = now
        )
        // Read back the persisted row (it carries the position SQL chose).
        return tabs.get(id)
            ?: throw IllegalStateException("Inserted tab row $id is missing")
    }

    /** Touches ONLY last_viewed_at (never rewrites a whole possibly-stale row). */
    suspend fun touchTab(id: String, ts: Long) = tabs.touch(id, ts)

    suspend fun updateTab(tab: TabEntity) = tabs.update(tab)
    suspend fun closeTab(id: String) = tabs.close(id, System.currentTimeMillis())
    suspend fun reopenTab(id: String) = tabs.reopen(id)
    suspend fun recentlyClosed(profileId: ProfileId, limit: Int = 10) = tabs.recentlyClosed(profileId.value, limit)
    suspend fun moveTab(id: String, position: Int) = tabs.setposition(id, position)
    suspend fun groupTab(id: String, group: String?) = tabs.setGroup(id, group)
    suspend fun pinTab(id: String, pinned: Boolean) = tabs.setPinned(id, pinned)
    suspend fun deleteTab(id: String) = tabs.delete(id)
    suspend fun purgeOldClosedTabs(days: Int) =
        tabs.purgeClosedBefore(System.currentTimeMillis() - days * 86_400_000L)

    // ---------- Bookmarks ----------
    fun observeBookmarks(profileId: ProfileId): Flow<List<BookmarkEntity>> = bookmarks.observeAll(profileId.value)
    suspend fun bookmarks(profileId: ProfileId): List<BookmarkEntity> = bookmarks.all(profileId.value)
    suspend fun isBookmarked(profileId: ProfileId, url: String): Boolean =
        bookmarks.find(profileId.value, url) != null

    suspend fun addBookmark(profileId: ProfileId, url: String, title: String, folder: String? = null): Long {
        if (isBookmarked(profileId, url)) return -1
        val pos = (bookmarks.maxPosition(profileId.value) ?: -1) + 1
        return bookmarks.upsert(
            BookmarkEntity(
                profileId = profileId.value, url = url, title = title,
                folder = folder, position = pos, createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateBookmark(id: Long, title: String, folder: String?) = bookmarks.updateMeta(id, title, folder)
    suspend fun deleteBookmark(id: Long) = bookmarks.delete(id)
    suspend fun deleteAllBookmarks(profileId: ProfileId) = bookmarks.deleteAllFor(profileId.value)

    // ---------- Notes ----------
    fun observeNotes(profileId: ProfileId): Flow<List<NoteEntity>> = notes.observe(profileId.value)
    suspend fun notes(profileId: ProfileId): List<NoteEntity> = notes.allForProfile(profileId.value)

    /**
     * Create (id == null) or update (id != null) one note. The id and
     * timestamps are minted here; updated_at is always stamped, created_at
     * only on create.
     */
    suspend fun saveNote(profileId: ProfileId, title: String, body: String, id: String? = null): String {
        val now = System.currentTimeMillis()
        val existing = id?.let { notes.byId(it) }
        val noteId = existing?.id ?: id ?: UUID.randomUUID().toString()
        notes.upsert(
            NoteEntity(
                id = noteId,
                profileId = profileId.value,
                title = title,
                body = body,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )
        return noteId
    }

    suspend fun deleteNote(id: String) = notes.delete(id)

    // ---------- History ----------
    fun observeRecentHistory(profileId: ProfileId, limit: Int = 20): Flow<List<HistoryEntity>> =
        history.observeRecent(profileId.value, limit)

    suspend fun searchHistory(profileId: ProfileId, needle: String): List<HistoryEntity> =
        if (needle.isBlank()) emptyList() else history.search(profileId.value, needle.trim())

    suspend fun recordVisit(profileId: ProfileId, url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        history.insert(
            HistoryEntity(
                profileId = profileId.value,
                url = url,
                title = title.ifBlank { url },
                visitedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteHistoryItem(id: Long) = history.delete(id)
    suspend fun clearHistory(profileId: ProfileId, since: Long) =
        history.deleteSince(profileId.value, since)

    suspend fun distinctSitesSince(profileId: ProfileId, since: Long): Int =
        history.distinctSites(profileId.value, since)

    // ---------- Downloads ----------
    fun observeDownloads(profileId: ProfileId): Flow<List<DownloadEntity>> = downloads.observeAll(profileId.value)
    suspend fun downloadsFor(profileId: ProfileId): List<DownloadEntity> =
        observeDownloads(profileId).first()
    suspend fun download(id: Long): DownloadEntity? = downloads.get(id)
    suspend fun activeDownloads(profileId: ProfileId): List<DownloadEntity> =
        downloads.activeFor(profileId.value)
    suspend fun insertDownload(entry: DownloadEntity): Long = downloads.insert(entry)
    suspend fun updateDownload(entry: DownloadEntity) = downloads.update(entry)
    suspend fun updateDownloadProgress(id: Long, downloaded: Long, total: Long) =
        downloads.updateProgress(id, downloaded, total)
    suspend fun updateDownloadStatus(id: Long, status: String, error: String?) =
        downloads.updateStatus(id, status, error)
    suspend fun completeDownload(
        id: Long,
        status: String,
        destination: String,
        downloaded: Long,
        total: Long,
        completedAt: Long
    ) = downloads.updateCompleted(id, status, destination, downloaded, total, completedAt)
    suspend fun deleteDownload(id: Long) = downloads.delete(id)

    // ---------- Site permissions & settings ----------
    suspend fun permissions(profileId: ProfileId): List<SitePermissionEntity> =
        site.permissions(profileId.value)

    suspend fun permissionFor(profileId: ProfileId, host: String, kind: PermissionKind): PermissionDecision? =
        site.permissionsFor(profileId.value, host)
            .firstOrNull { it.permission == kind.name }
            ?.let { runCatching { PermissionDecision.valueOf(it.decision) }.getOrNull() }

    suspend fun setPermission(profileId: ProfileId, host: String, kind: PermissionKind, decision: PermissionDecision) {
        site.upsertPermission(
            SitePermissionEntity(
                profileId = profileId.value,
                host = host,
                permission = kind.name,
                decision = decision.name
            )
        )
    }

    suspend fun resetPermissions(profileId: ProfileId, host: String) =
        site.resetPermissionsFor(profileId.value, host)

    suspend fun siteSetting(profileId: ProfileId, host: String): SiteSettingEntity? =
        site.siteSetting(profileId.value, host)

    suspend fun allSiteSettings(profileId: ProfileId): List<SiteSettingEntity> =
        site.siteSettings(profileId.value)

    suspend fun upsertSiteSetting(entity: SiteSettingEntity) = site.upsertSiteSetting(entity)
    suspend fun clearSiteSetting(profileId: ProfileId, host: String) = site.clearSiteSetting(profileId.value, host)

    // ---------- IP history ----------
    suspend fun allIpHistory(): List<IpHistoryEntity> = ip.all()
    suspend fun ipHistoryFor(profileId: ProfileId): List<IpHistoryEntity> = ip.forProfile(profileId.value)
    fun observeIpHistory(profileId: ProfileId): Flow<List<IpHistoryEntity>> = ip.observeForProfile(profileId.value)
    suspend fun upsertIpHistory(entity: IpHistoryEntity): Long = ip.upsert(entity)
    suspend fun findIpAssociation(profileId: ProfileId, ipAddr: String): IpHistoryEntity? =
        ip.forProfile(profileId.value).firstOrNull { it.ip == ipAddr }
    suspend fun forgetIp(profileId: ProfileId, ipAddr: String) = ip.forgetIp(profileId.value, ipAddr)
    suspend fun clearIpHistory(profileId: ProfileId) = ip.deleteAllFor(profileId.value)
    suspend fun purgeIpHistory(cutoff: Long) = ip.purgeBefore(cutoff)

    // ---------- Privacy stats ----------
    suspend fun recordBlock(profileId: ProfileId, host: String, category: String) {
        stats.insert(
            BlockEventEntity(
                profileId = profileId.value,
                host = host,
                category = category,
                ts = System.currentTimeMillis()
            )
        )
    }

    suspend fun statCounts(profileId: ProfileId, since: Long): List<CategoryCount> =
        stats.countsSince(profileId.value, since)

    suspend fun statCountsForHost(profileId: ProfileId, host: String, since: Long): List<CategoryCount> =
        stats.countsSinceForHost(profileId.value, host, since)

    // ---------- Profile storage (diagnostics) ----------
    fun profileStorageDirs(baseDir: File, profileId: ProfileId): List<File> =
        ProfileDirectoryLayout.allDirs(profileId).map { File(baseDir, it) }
}
