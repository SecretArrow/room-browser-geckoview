package com.roombrowser.domain.profile

import com.roombrowser.domain.model.Devices
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.UaMode
import java.security.SecureRandom

/**
 * Storage port implemented by the app layer (Room-backed).
 * The domain keeps pure logic and stays JVM-testable.
 */
interface ProfileStore {
    suspend fun profiles(): List<Profile>
    suspend fun get(id: ProfileId): Profile?
    suspend fun put(profile: Profile)
    suspend fun remove(id: ProfileId, cascadeData: Boolean)
    suspend fun updateSettings(id: ProfileId, settings: ProfileSettings)
    suspend fun copyProfileData(from: ProfileId, to: ProfileId, options: CopyOptions)
    suspend fun resetProfileData(id: ProfileId)
}

data class CopyOptions(
    val settings: Boolean = true,
    val bookmarks: Boolean = true,
    val history: Boolean = true,
    /** Cookies / cache / sessions / site data are NEVER copied by default. */
    val cookies: Boolean = false,
    val cache: Boolean = false,
    val sessions: Boolean = false,
    val siteData: Boolean = false
)

/** Tab-count port for the profile cards. */
interface TabCountStore {
    suspend fun tabCount(profileId: ProfileId): Int
}

/**
 * Profile manager: create / open / edit / duplicate / delete / rename /
 * re-style / reset / lock. UUID is the immutable storage identity.
 *
 * [randomDeviceId] picks the device for newly created profiles that have no
 * explicit identity configured; it is handed the ids already in use so two
 * profiles do not present the same handset (injectable for deterministic
 * tests).
 *
 * [randomSeed] mints the fingerprint seed a new profile carries; injectable
 * like [randomDeviceId], and separate from it on purpose — the seed is what
 * keeps two profiles apart when the catalogue hands them the same handset.
 */
class ProfileManager(
    private val store: ProfileStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val randomDeviceId: (Set<String>) -> String? = { taken ->
        Devices.random(taken).id
    },
    private val randomSeed: () -> String = { newFingerprintSeed() }
) {

    /**
     * Create a new profile.
     *
     * Every newly created profile automatically presents itself as a distinct
     * Android device, drawn from the bundled catalogue of real handsets
     * (fingerprint diversity between profiles). An explicit identity is
     * always respected: assignment only kicks in when [settings] names no
     * device and still uses [UaMode.DEFAULT]. Import / restore callers pass
     * [randomizeDevice] = false so the payload's settings are preserved
     * verbatim.
     *
     * A seed is minted for every profile that arrives without one; a profile
     * that already carries one keeps it, which preserves export/import.
     */
    suspend fun create(
        name: String,
        icon: String,
        colorArgb: Long,
        settings: ProfileSettings = ProfileSettings(),
        isDefault: Boolean = false,
        randomizeDevice: Boolean = true
    ): Profile {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Profile name must not be empty" }
        require(trimmed.length <= MAX_NAME) { "Profile name too long (max $MAX_NAME)" }
        val existing = store.profiles()
        require(existing.none { it.name.equals(trimmed, ignoreCase = true) }) {
            "A profile with this name already exists"
        }
        val seeded =
            if (settings.fingerprintSeed == null) settings.copy(fingerprintSeed = randomSeed())
            else settings
        val effectiveSettings =
            if (randomizeDevice && seeded.deviceId == null && seeded.uaMode == UaMode.DEFAULT) {
                val taken = existing.mapNotNull { it.settings.deviceId }.toSet()
                seeded.copy(deviceId = randomDeviceId(taken))
            } else {
                seeded
            }
        val profile = Profile(
            id = ProfileId.new(),
            name = trimmed,
            icon = icon.ifBlank { "\uD83D\uDC64" },
            colorArgb = colorArgb,
            isDefault = isDefault || existing.isEmpty(),
            createdAt = clock(),
            lastActiveAt = clock(),
            settings = effectiveSettings
        )
        store.put(profile)
        return profile
    }

    /**
     * Copy a profile. The copy is a different identity: when the source
     * presented itself as a device, the copy gets a device no other profile
     * is using, so the two do not look like the same handset (which would
     * defeat the point of separate profiles). Cosmetic and setting fields
     * are still copied. The fingerprint seed is kept, not re-minted: the copy
     * is the same persona in a new row.
     */
    suspend fun duplicate(id: ProfileId, options: CopyOptions, nameSuffix: String = " Copy"): Profile {
        val source = store.get(id) ?: throw IllegalArgumentException("Profile not found: $id")
        val existingNames = store.profiles().map { it.name.lowercase() }
        var candidate = source.name + nameSuffix
        var i = 2
        while (candidate.lowercase() in existingNames) {
            candidate = source.name + nameSuffix + " " + i
            i++
        }
        val settings = if (source.settings.deviceId != null) {
            val taken = store.profiles().mapNotNull { it.settings.deviceId }.toSet()
            source.settings.copy(deviceId = randomDeviceId(taken))
        } else {
            source.settings
        }
        val copy = source.copy(
            id = ProfileId.new(),
            name = candidate,
            isDefault = false,
            isLocked = false,
            createdAt = clock(),
            lastActiveAt = clock(),
            settings = settings
        )
        store.put(copy)
        store.copyProfileData(source.id, copy.id, options)
        return copy
    }

    suspend fun rename(id: ProfileId, newName: String): Profile {
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "Profile name must not be empty" }
        val profile = store.get(id) ?: throw IllegalArgumentException("Profile not found: $id")
        val others = store.profiles().filter { it.id != id }
        require(others.none { it.name.equals(trimmed, ignoreCase = true) }) {
            "A profile with this name already exists"
        }
        val updated = profile.copy(name = trimmed)
        store.put(updated)
        return updated
    }

    /**
     * Cosmetic update (icon/color). Storage identity (UUID) is untouched.
     */
    suspend fun restyle(id: ProfileId, icon: String? = null, colorArgb: Long? = null): Profile {
        val profile = store.get(id) ?: throw IllegalArgumentException("Profile not found: $id")
        val updated = profile.copy(
            icon = icon ?: profile.icon,
            colorArgb = colorArgb ?: profile.colorArgb
        )
        store.put(updated)
        return updated
    }

    /**
     * One-shot edit of everything the "Edit Profile" dialog can change —
     * name, icon and color — in a SINGLE get → copy → put.
     *
     * The editor used to save by calling [rename] and [restyle] back to back
     * from two independent coroutines. Each ran its own get → copy → put, so
     * both read the SAME row before either wrote it and whichever put landed
     * second overwrote the other's field: the rename or the re-style was
     * silently lost while the UI reported success. One read and one write per
     * save cannot lose an edit.
     *
     * Validation is [rename]'s (non-empty after trimming, no name collision
     * with another profile) and a null icon/color leaves that field alone
     * exactly as [restyle] does. Storage identity (UUID) is untouched.
     */
    suspend fun update(
        id: ProfileId,
        name: String,
        icon: String? = null,
        colorArgb: Long? = null
    ): Profile {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Profile name must not be empty" }
        val profile = store.get(id) ?: throw IllegalArgumentException("Profile not found: $id")
        val others = store.profiles().filter { it.id != id }
        require(others.none { it.name.equals(trimmed, ignoreCase = true) }) {
            "A profile with this name already exists"
        }
        val updated = profile.copy(
            name = trimmed,
            icon = icon ?: profile.icon,
            colorArgb = colorArgb ?: profile.colorArgb
        )
        store.put(updated)
        return updated
    }

    suspend fun setLocked(id: ProfileId, locked: Boolean): Profile {
        val profile = store.get(id) ?: throw IllegalArgumentException("Profile not found: $id")
        val updated = profile.copy(isLocked = locked)
        store.put(updated)
        return updated
    }

    suspend fun setDefault(id: ProfileId) {
        val profiles = store.profiles()
        require(profiles.any { it.id == id }) { "Profile not found: $id" }
        profiles.forEach {
            if (it.isDefault != (it.id == id)) store.put(it.copy(isDefault = it.id == id))
        }
    }

    suspend fun updateSettings(id: ProfileId, settings: ProfileSettings) {
        store.updateSettings(id, settings)
    }

    /**
     * Point a profile at a device, or (with null) take its device away.
     *
     * The device is the single control for identity, so assigning one clears
     * the UA preset and custom fields rather than leaving two contradictory
     * answers behind.
     */
    suspend fun setDevice(id: ProfileId, deviceId: String?) {
        val profile = store.get(id) ?: throw IllegalArgumentException("Profile not found: $id")
        require(deviceId == null || Devices.find(deviceId) != null) {
            "Unknown device: $deviceId"
        }
        store.updateSettings(
            id,
            profile.settings.copy(
                deviceId = deviceId,
                uaMode = if (deviceId != null) UaMode.DEFAULT else profile.settings.uaMode,
                uaPresetId = if (deviceId != null) null else profile.settings.uaPresetId,
                customUserAgent = if (deviceId != null) null else profile.settings.customUserAgent
            )
        )
    }

    /**
     * A device no other profile is using — what the "change device" action
     * draws from. The profile being changed does not count as a user of its
     * own device, so asking for a new one never hands back the current one
     * unless it is genuinely the last handset left.
     */
    suspend fun pickFreeDeviceId(id: ProfileId): String? {
        val taken = store.profiles()
            .filter { it.id != id }
            .mapNotNull { it.settings.deviceId }
            .toSet()
        return randomDeviceId(taken)
    }

    /** The ids every other profile is presenting as, for a picker's "in use" mark. */
    suspend fun devicesInUse(except: ProfileId? = null): Set<String> =
        store.profiles()
            .filter { it.id != except }
            .mapNotNull { it.settings.deviceId }
            .toSet()

    suspend fun markActive(id: ProfileId) {
        val profile = store.get(id) ?: return
        store.put(profile.copy(lastActiveAt = clock()))
    }

    suspend fun delete(id: ProfileId) {
        val profiles = store.profiles()
        val target = profiles.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Profile not found: $id")
        val wasDefault = target.isDefault
        store.remove(id, cascadeData = true)
        if (wasDefault) {
            store.profiles().firstOrNull()?.let { store.put(it.copy(isDefault = true)) }
        }
    }

    suspend fun resetData(id: ProfileId) {
        store.resetProfileData(id)
    }

    suspend fun profiles(): List<Profile> = store.profiles()

    companion object {
        const val MAX_NAME = 40

        private const val SEED_BYTES = 32
        private const val HEX_DIGITS = "0123456789abcdef"

        // 32 CSPRNG bytes, hex-encoded so they survive JSON and a file export.
        private val SEED_RANDOM = SecureRandom()

        private fun newFingerprintSeed(): String {
            val bytes = ByteArray(SEED_BYTES)
            SEED_RANDOM.nextBytes(bytes)
            val out = StringBuilder(SEED_BYTES * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
            }
            return out.toString()
        }
    }
}
