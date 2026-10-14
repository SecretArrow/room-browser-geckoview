package com.roombrowser.domain.profile

import com.roombrowser.domain.model.ProfileId

/**
 * Profile directory architecture (spec section 46).
 *
 * Storage identity is derived from the immutable profile UUID — never from
 * the (mutable, non-unique) profile name. Renaming a profile therefore
 * never changes its storage identity.
 */
object ProfileDirectoryLayout {

    const val ROOT = "profiles"
    const val METADATA = "metadata"
    const val BROWSER_DATA = "browser_data"
    const val CACHE = "cache"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"

    fun profileDir(profileId: ProfileId): String =
        "$ROOT/profile_${profileId.value}"

    fun metadataDir(profileId: ProfileId): String =
        "${profileDir(profileId)}/$METADATA"

    fun browserDataDir(profileId: ProfileId): String =
        "${profileDir(profileId)}/$BROWSER_DATA"

    fun cacheDir(profileId: ProfileId): String =
        "${profileDir(profileId)}/$CACHE"

    fun downloadsDir(profileId: ProfileId): String =
        "${profileDir(profileId)}/$DOWNLOADS"

    fun settingsDir(profileId: ProfileId): String =
        "${profileDir(profileId)}/$SETTINGS"
}
