package com.roombrowser.domain.profile

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.Devices
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.UaMode
import com.roombrowser.domain.model.UserAgents
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class ProfileManagerTest {

    private lateinit var store: FakeProfileStore
    private lateinit var manager: ProfileManager
    private var now = 1000L

    @Before
    fun setUp() {
        store = FakeProfileStore()
        manager = ProfileManager(store, clock = { now })
    }

    @Test
    fun `create assigns uuid and first profile becomes default`() = runTest {
        val p = manager.create("Personal", "\uD83D\uDE00", 0xFF2196F3)
        assertThat(p.id.value).isNotEmpty()
        assertThat(p.isDefault).isTrue()
        assertThat(p.name).isEqualTo("Personal")
        assertThat(store.profiles()).hasSize(1)
    }

    @Test
    fun `uuid persists across manager instances`() = runTest {
        val p = manager.create("Work", "W", 0xFF000000)
        val other = ProfileManager(store, clock = { now })
        assertThat(other.profiles().single().id).isEqualTo(p.id)
    }

    @Test
    fun `duplicate name rejected`() = runTest {
        manager.create("Personal", "x", 0)
        var thrown = false
        try { manager.create("personal", "y", 0) } catch (e: IllegalArgumentException) { thrown = true }
        assertThat(thrown).isTrue()
    }

    @Test
    fun `rename keeps storage identity`() = runTest {
        val p = manager.create("Personal", "x", 0)
        manager.rename(p.id, "Family")
        val renamed = manager.profiles().single()
        assertThat(renamed.name).isEqualTo("Family")
        assertThat(renamed.id).isEqualTo(p.id)
        assertThat(renamed.createdAt).isEqualTo(p.createdAt)
    }

    @Test
    fun `duplicate creates isolated copy with unique uuid`() = runTest {
        val original = manager.create("Research", "R", 0xFF9C27B0)
        now += 50
        val copy = manager.duplicate(original.id, CopyOptions(settings = true, bookmarks = true))
        assertThat(copy.id).isNotEqualTo(original.id)
        assertThat(copy.name).isEqualTo("Research Copy")
        assertThat(copy.isDefault).isFalse()
        assertThat(manager.profiles()).hasSize(2)
    }

    @Test
    fun `duplicate names get numbered suffixes`() = runTest {
        val a = manager.create("Research", "R", 0)
        manager.duplicate(a.id, CopyOptions())
        val second = manager.duplicate(a.id, CopyOptions())
        assertThat(second.name).isEqualTo("Research Copy 2")
    }

    @Test
    fun `delete default promotes another profile`() = runTest {
        val a = manager.create("A", "a", 0)
        now += 10
        val b = manager.create("B", "b", 0)
        assertThat(manager.profiles().first { it.id == a.id }.isDefault).isTrue()
        manager.delete(a.id)
        val remaining = manager.profiles()
        assertThat(remaining).hasSize(1)
        assertThat(remaining.single().isDefault).isTrue()
        assertThat(remaining.single().id).isEqualTo(b.id)
    }

    @Test
    fun `delete cascades profile data`() = runTest {
        val a = manager.create("A", "a", 0)
        manager.delete(a.id)
        assertThat(store.resetCalled).isEmpty()
        assertThat(store.removedIds).containsExactly(a.id)
    }

    @Test
    fun `restyle does not change identity`() = runTest {
        val p = manager.create("A", "\uD83D\uDE00", 0xFF111111)
        manager.restyle(p.id, icon = "\uD83D\uDE0E", colorArgb = 0xFF222222)
        val updated = manager.profiles().single()
        assertThat(updated.id).isEqualTo(p.id)
        assertThat(updated.icon).isEqualTo("\uD83D\uDE0E")
        assertThat(updated.colorArgb).isEqualTo(0xFF222222)
    }

    @Test
    fun `settings update persists`() = runTest {
        val p = manager.create("A", "a", 0)
        manager.updateSettings(p.id, ProfileSettings(searchEngineId = "brave", desktopModeDefault = true))
        val updated = manager.profiles().single()
        assertThat(updated.settings.searchEngineId).isEqualTo("brave")
        assertThat(updated.settings.desktopModeDefault).isTrue()
    }

    @Test
    fun `reset data delegates`() = runTest {
        val p = manager.create("A", "a", 0)
        manager.resetData(p.id)
        assertThat(store.resetCalled).containsExactly(p.id)
    }

    @Test
    fun `markActive updates lastActiveAt`() = runTest {
        val p = manager.create("A", "a", 0)
        now += 12345
        manager.markActive(p.id)
        assertThat(manager.profiles().single().lastActiveAt).isEqualTo(13345)
    }

    @Test
    fun `new profile gets a device when none set`() = runTest {
        val randomManager = ProfileManager(
            store,
            clock = { now },
            randomDeviceId = { "samsung-sm-s918b" }
        )
        val p = randomManager.create("Random", "r", 0)
        assertThat(p.settings.deviceId).isEqualTo("samsung-sm-s918b")
        assertThat(p.settings.uaMode).isEqualTo(UaMode.DEFAULT)
        // The assignment is what gets persisted, not just what is returned.
        assertThat(store.profiles().single().settings.deviceId).isEqualTo("samsung-sm-s918b")
    }

    @Test
    fun `second profile is offered the devices already taken`() = runTest {
        val offered = mutableListOf<Set<String>>()
        val randomManager = ProfileManager(
            store,
            clock = { now },
            randomDeviceId = { taken ->
                offered += taken
                "device-" + taken.size
            }
        )
        randomManager.create("One", "1", 0)
        randomManager.create("Two", "2", 0)
        randomManager.create("Three", "3", 0)
        // Each new profile sees every device handed out before it, so the
        // picker can keep them distinct. The first set is typed explicitly:
        // an empty setOf() leaves containsExactly's vararg with nothing to
        // infer from.
        assertThat(offered).containsExactly(
            emptySet<String>(),
            setOf("device-0"),
            setOf("device-0", "device-1")
        )
    }

    @Test
    fun `duplicate does not reuse the source device`() = runTest {
        val randomManager = ProfileManager(
            store,
            clock = { now },
            randomDeviceId = { taken -> if ("device-a" in taken) "device-b" else "device-a" }
        )
        val original = randomManager.create("Research", "R", 0)
        val copy = randomManager.duplicate(original.id, CopyOptions())
        assertThat(original.settings.deviceId).isEqualTo("device-a")
        assertThat(copy.settings.deviceId).isEqualTo("device-b")
    }

    @Test
    fun `setDevice clears a preset so identity has one answer`() = runTest {
        val p = manager.create(
            "Work", "w", 0,
            settings = ProfileSettings(uaMode = UaMode.PRESET, uaPresetId = "firefox_android"),
            randomizeDevice = false
        )
        assertThat(p.settings.deviceId).isNull()
        val device = Devices.all.first()
        manager.setDevice(p.id, device.id)
        val after = manager.profiles().single().settings
        assertThat(after.deviceId).isEqualTo(device.id)
        assertThat(after.uaMode).isEqualTo(UaMode.DEFAULT)
        assertThat(after.uaPresetId).isNull()
        assertThat(after.customUserAgent).isNull()
        // ...and the device's UA is what the profile now sends.
        assertThat(UserAgents.effectiveUserAgent(after)).isEqualTo(device.userAgent)
    }

    @Test
    fun `setDevice rejects an unknown id`() = runTest {
        val p = manager.create("Work", "w", 0)
        var thrown = false
        try { manager.setDevice(p.id, "no-such-device") } catch (e: IllegalArgumentException) { thrown = true }
        assertThat(thrown).isTrue()
    }

    @Test
    fun `clearing the device leaves the profile on the WebView default`() = runTest {
        val p = manager.create("Work", "w", 0, randomizeDevice = false)
        manager.setDevice(p.id, Devices.all.first().id)
        manager.setDevice(p.id, null)
        val after = manager.profiles().single().settings
        assertThat(after.deviceId).isNull()
        assertThat(UserAgents.effectiveUserAgent(after)).isNull()
    }

    @Test
    fun `explicit custom UA is preserved`() = runTest {
        val randomManager = ProfileManager(
            store,
            clock = { now },
            randomDeviceId = { "samsung-sm-s918b" }
        )
        val p = randomManager.create(
            "Custom", "c", 0,
            settings = ProfileSettings(uaMode = UaMode.CUSTOM, customUserAgent = "MyUA/1.0")
        )
        assertThat(p.settings.uaMode).isEqualTo(UaMode.CUSTOM)
        assertThat(p.settings.customUserAgent).isEqualTo("MyUA/1.0")
        assertThat(p.settings.uaPresetId).isNull()
    }

    @Test
    fun `import path does not assign a device`() = runTest {
        val randomManager = ProfileManager(
            store,
            clock = { now },
            randomDeviceId = { "samsung-sm-s918b" }
        )
        val p = randomManager.create("Imported", "i", 0, randomizeDevice = false)
        assertThat(p.settings.deviceId).isNull()
        assertThat(p.settings.uaMode).isEqualTo(UaMode.DEFAULT)
        assertThat(p.settings.uaPresetId).isNull()
        assertThat(p.settings.customUserAgent).isNull()
    }

    @Test
    fun `create mints a fingerprint seed and persists it`() = runTest {
        val seeded = ProfileManager(
            store,
            clock = { now },
            randomDeviceId = { "samsung-sm-s918b" },
            randomSeed = { "seed-a" }
        )
        val p = seeded.create("Seed", "s", 0)
        assertThat(p.settings.fingerprintSeed).isEqualTo("seed-a")
        // Persisted, not merely returned: the engine reads the stored row.
        assertThat(store.profiles().single().settings.fingerprintSeed).isEqualTo("seed-a")
    }

    @Test
    fun `each created profile gets its own seed`() = runTest {
        val seeds = ArrayDeque(listOf("seed-a", "seed-b"))
        val seeded = ProfileManager(
            store,
            clock = { now },
            randomDeviceId = { id -> "device-" + id.size },
            randomSeed = { seeds.removeFirst() }
        )
        val one = seeded.create("One", "1", 0)
        val two = seeded.create("Two", "2", 0)
        assertThat(one.settings.fingerprintSeed).isEqualTo("seed-a")
        assertThat(two.settings.fingerprintSeed).isEqualTo("seed-b")
    }

    @Test
    fun `duplicate preserves the source seed`() = runTest {
        val seeded = ProfileManager(
            store,
            clock = { now },
            randomDeviceId = { "samsung-sm-s918b" },
            randomSeed = { "seed-a" }
        )
        val original = seeded.create("Research", "R", 0)
        val copy = seeded.duplicate(original.id, CopyOptions())
        // A copy is the same persona in a new row.
        assertThat(copy.settings.fingerprintSeed).isEqualTo("seed-a")
    }

    @Test
    fun `a seed restored from a backup is kept, not re-rolled`() = runTest {
        val p = manager.create(
            "Imported", "i", 0,
            settings = ProfileSettings(fingerprintSeed = "from-the-file"),
            randomizeDevice = false
        )
        assertThat(p.settings.fingerprintSeed).isEqualTo("from-the-file")
    }

    @Test
    fun `the catalogue is large enough to keep profiles distinct`() {
        // Distinct assignment is only meaningful while the catalogue has room:
        // a catalogue that ran out would start handing back shared devices.
        assertThat(Devices.all.size).isAtLeast(1000)
        assertThat(Devices.all.map { it.id }.toSet()).hasSize(Devices.all.size)
        assertThat(Devices.all.map { it.userAgent }.toSet()).hasSize(Devices.all.size)
    }
}

class FakeProfileStore : ProfileStore {
    val map = linkedMapOf<ProfileId, Profile>()
    val removedIds = mutableListOf<ProfileId>()
    val resetCalled = mutableListOf<ProfileId>()
    val copies = mutableListOf<Pair<ProfileId, ProfileId>>()

    override suspend fun profiles(): List<Profile> = map.values.toList()

    override suspend fun get(id: ProfileId): Profile? = map[id]

    override suspend fun put(profile: Profile) {
        map[profile.id] = profile
    }

    override suspend fun remove(id: ProfileId, cascadeData: Boolean) {
        map.remove(id)
        removedIds += id
    }

    override suspend fun updateSettings(id: ProfileId, settings: ProfileSettings) {
        map[id] = map[id]!!.copy(settings = settings)
    }

    override suspend fun copyProfileData(from: ProfileId, to: ProfileId, options: CopyOptions) {
        copies += from to to
    }

    override suspend fun resetProfileData(id: ProfileId) {
        resetCalled += id
    }
}
