package com.roombrowser.data.repo

import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AppStateDao
import com.roombrowser.data.db.AppStateEntity
import com.roombrowser.domain.agent.RetryPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * JVM tests of the agent-settings write path against a map-backed DAO — no
 * Room, no Android.
 *
 * The agent settings live as ONE json blob under one key, so every save
 * replaces the whole value and SQLite has no per-field write to interleave
 * safely. That leaves exactly one property worth pinning: a settings save must
 * MERGE into what is stored, never resurrect the copy the caller was holding.
 *
 * That property is the difference between a switch that stays on and one that
 * silently flips back the next time an unrelated setting is edited somewhere
 * else.
 */
class AppStateRepositoryTest {

    /**
     * Hand-written map-backed DAO.
     *
     * Written out rather than mocked because both accessors `delay(1)` on
     * purpose, and a hand-written `suspend fun` may call `delay` directly
     * without depending on whether a mocking library's answer block is
     * suspend-capable.
     *
     * The delay is what makes these tests able to fail: under runTest it is
     * virtual time, so it costs nothing, but it SUSPENDS — which is what lets
     * two concurrent updates interleave at their read/write boundary. A fake
     * that returned without ever suspending would run each update start to
     * finish and would pass against a racing implementation just as happily as
     * against a serialised one.
     */
    private class MapDao : AppStateDao {
        val rows = linkedMapOf<String, String>()

        override suspend fun get(key: String): String? {
            delay(1)
            return rows[key]
        }

        override fun observe(key: String): Flow<String?> = flowOf(rows[key])

        override suspend fun put(entity: AppStateEntity) {
            delay(1)
            rows[entity.key] = entity.value
        }

        override suspend fun remove(key: String) {
            rows.remove(key)
        }
    }

    private val dao = MapDao()
    private val repo = AppStateRepository(dao)

    private suspend fun stored(): AgentSettings = repo.agentSettingsSnapshot()

    @Test
    fun `the transform receives the stored blob, not a caller's copy`() = runTest {
        repo.saveAgentSettings(
            AgentSettings(useDefaultContext = true, defaultContext = "be terse", maxSteps = 5)
        )
        val seen = mutableListOf<AgentSettings>()

        repo.updateAgentSettings {
            seen += it
            it.copy(maxSteps = 9)
        }

        // useDefaultContext is the field that used to vanish: a writer holding
        // a copy from before it was switched on wrote the whole blob back with
        // useDefaultContext = false, and the standing context stopped being
        // sent with nothing on screen to say so.
        assertThat(seen.single().useDefaultContext).isTrue()
        assertThat(seen.single().defaultContext).isEqualTo("be terse")
        assertThat(seen.single().maxSteps).isEqualTo(5)
    }

    @Test
    fun `an unrelated edit does not revert the standing context`() = runTest {
        repo.updateAgentSettings {
            it.copy(useDefaultContext = true, defaultContext = "be terse")
        }

        // A later, entirely unrelated edit — a step budget. This is how the old
        // code lost the context: it saved its own stale copy of everything else.
        repo.updateAgentSettings { it.copy(maxSteps = 3) }

        val after = stored()
        assertThat(after.useDefaultContext).isTrue()
        assertThat(after.defaultContext).isEqualTo("be terse")
        assertThat(after.maxSteps).isEqualTo(3)
    }

    @Test
    fun `concurrent updates all survive`() = runTest {
        val edits = listOf<(AgentSettings) -> AgentSettings>(
            { it.copy(useDefaultContext = true) },
            { it.copy(defaultContext = "be terse") },
            { it.copy(maxSteps = 7) },
            { it.copy(temperature = 0.9) }
        )

        edits.map { edit -> async { repo.updateAgentSettings(edit) } }.forEach { it.await() }

        // Without the lock all four read the same starting blob and the last
        // writer wins, leaving exactly one of the four changes in place.
        val after = stored()
        assertThat(after.useDefaultContext).isTrue()
        assertThat(after.defaultContext).isEqualTo("be terse")
        assertThat(after.maxSteps).isEqualTo(7)
        assertThat(after.temperature).isEqualTo(0.9)
    }

    @Test
    fun `the update returns what it stored`() = runTest {
        val returned = repo.updateAgentSettings {
            it.copy(defaultContext = "be terse", useDefaultContext = true)
        }

        assertThat(returned).isEqualTo(stored())
    }

    @Test
    fun `saveAgentSettings still replaces the whole blob`() = runTest {
        repo.updateAgentSettings {
            it.copy(useDefaultContext = true, defaultContext = "be terse")
        }

        // Kept for writes that ARE authoritative by construction (a backup
        // import replacing every setting). That it clears what the update path
        // merges is the point of it rather than a bug, so it is pinned here
        // instead of being "fixed" later by a reader who assumes all writes
        // should merge.
        repo.saveAgentSettings(AgentSettings(maxSteps = 11))

        val after = stored()
        assertThat(after.useDefaultContext).isFalse()
        assertThat(after.defaultContext).isEmpty()
        assertThat(after.maxSteps).isEqualTo(11)
    }

    @Test
    fun `the retry pause is stored in seconds and handed to the transport in milliseconds`() {
        val policy = AgentSettings(retryOnError = true, retryDelaySeconds = 6).retryPolicy()

        // The screen asks in seconds and the transport waits in millis; the
        // conversion happens once, so a factor-of-1000 mistake here is a
        // six-millisecond pause nobody would notice until a provider was
        // being hammered.
        assertThat(policy.delay).isEqualTo(6_000L)
    }

    @Test
    fun `a settings blob written before the pause setting existed still decodes`() = runTest {
        // The blob is ONE json object, so the field's absence is the normal
        // case for every profile saved by an earlier build. kotlinx fills in
        // the default — and that default has to be the six seconds, not zero:
        // a zero pause would hammer the provider the user asked us to back
        // off from.
        val legacy = """
            {"enabled":true,"showAgentButton":false,"retryOnError":true,
             "retryMaxAttempts":3,"retryConnectionFailures":true}
        """.trimIndent()
        dao.rows[AppStateKeys.AGENT_SETTINGS] = legacy

        val settings = repo.agentSettingsSnapshot()

        assertThat(settings.retryOnError).isTrue()
        assertThat(settings.retryDelaySeconds).isEqualTo(RetryPolicy.DEFAULT_DELAY_SECONDS)
        assertThat(settings.retryPolicy().delay).isEqualTo(RetryPolicy.DEFAULT_DELAY_MS)
    }

    /** A record as the pre-rename build wrote it, under `wallet_lock:<id>`. */
    private val legacyPinJson = """
        {"pinSaltB64":"c2FsdA==","pinIterations":120000,"pinVerifierB64":"dmVy","failedAttempts":0}
    """.trimIndent()

    @Test
    fun `a PIN set under the pre-rename key is adopted, not dropped`() = runTest {
        // A released webview build (v1.0.127) still wrote `wallet_lock:`. Reading
        // only the new key would make that PIN vanish on upgrade and reopen the
        // wallet ungated, so the first read adopts the old row.
        dao.rows["wallet_lock:p1"] = legacyPinJson

        val record = repo.profileLockRecord("p1")

        assertThat(record?.pinConfigured).isTrue()
        assertThat(dao.rows).containsKey("profile_lock:p1")
        assertThat(dao.rows).doesNotContainKey("wallet_lock:p1")
    }

    @Test
    fun `clearing the lock also removes the pre-rename row`() = runTest {
        // Otherwise the adoption above finds the old row again and resurrects a
        // PIN the user had just removed.
        dao.rows["wallet_lock:p1"] = legacyPinJson

        repo.clearProfileLockRecord("p1")

        assertThat(repo.profileLockRecord("p1")).isNull()
    }

    @Test
    fun `a record already under the new key wins over a stale pre-rename row`() = runTest {
        dao.rows["wallet_lock:p1"] = legacyPinJson
        repo.saveProfileLockRecord(
            "p1",
            WalletLockRecord(pinSaltB64 = "bg==", pinIterations = 9, pinVerifierB64 = "bg==")
        )

        assertThat(repo.profileLockRecord("p1")?.pinSaltB64).isEqualTo("bg==")
    }
}
