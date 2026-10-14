package com.roombrowser.data.repo

import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.CustomThemeEntity
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.theme.RoomThemeSpec
import com.roombrowser.domain.theme.ThemeJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Theme repository:
 *  - [applyToProfile] writes a FULL theme snapshot onto the profile row
 *    (profiles.theme_json). This is what guarantees per-profile isolation:
 *    each profile owns its own copy, so editing profile A's theme can never
 *    change profile B.
 *  - The local gallery (themes table) stores user-saved custom themes that
 *    can be picked, duplicated, renamed, exported and re-applied.
 *
 * Works in BOTH processes (multi-instance invalidation keeps the ':browser'
 * process in sync when the Theme Studio saves changes from the main one).
 */
class ThemeRepository(db: AppDatabase) {

    private val themeDao = db.themeDao()
    private val profileDao = db.profileDao()

    /** Live gallery of user-saved custom themes (newest first). */
    fun observeGallery(): Flow<List<RoomThemeSpec>> =
        themeDao.observeAll().map { list -> list.mapNotNull { ThemeJson.decode(it.specJson) } }

    suspend fun gallery(): List<RoomThemeSpec> =
        themeDao.all().mapNotNull { ThemeJson.decode(it.specJson) }

    suspend fun saveToGallery(spec: RoomThemeSpec, createdAt: Long): RoomThemeSpec {
        var toSave = spec.sanitized()
        if (!toSave.id.startsWith(RoomThemeSpec.CUSTOM_PREFIX)) {
            toSave = toSave.withIdentity(newCustomId(), toSave.name)
        }
        themeDao.upsert(
            CustomThemeEntity(
                id = toSave.id,
                name = toSave.name,
                specJson = ThemeJson.encode(toSave),
                createdAt = createdAt
            )
        )
        return toSave
    }

    suspend fun rename(id: String, newName: String) {
        val existing = themeDao.get(id) ?: return
        val spec = ThemeJson.decode(existing.specJson) ?: return
        themeDao.upsert(
            existing.copy(
                name = newName,
                specJson = ThemeJson.encode(spec.copy(name = newName))
            )
        )
    }

    suspend fun duplicate(id: String, createdAt: Long): RoomThemeSpec? {
        val existing = themeDao.get(id) ?: return null
        val spec = ThemeJson.decode(existing.specJson) ?: return null
        val copy = spec.withIdentity(newCustomId(), "${spec.name} copy")
        themeDao.upsert(
            CustomThemeEntity(
                id = copy.id,
                name = copy.name,
                specJson = ThemeJson.encode(copy),
                createdAt = createdAt
            )
        )
        return copy
    }

    suspend fun delete(id: String) {
        themeDao.delete(id)
    }

    /** Persist the full theme snapshot for ONE profile (per-profile isolation). */
    suspend fun applyToProfile(profileId: ProfileId, spec: RoomThemeSpec) {
        profileDao.updateTheme(profileId.value, ThemeJson.encode(spec))
    }

    /** Reset a profile back to the built-in default theme. */
    suspend fun resetProfile(profileId: ProfileId) {
        profileDao.updateTheme(profileId.value, "")
    }

    private fun newCustomId(): String =
        RoomThemeSpec.CUSTOM_PREFIX + UUID.randomUUID().toString().replace("-", "").take(20)
}
