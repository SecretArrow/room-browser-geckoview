package com.roombrowser

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AgentSessionEntity
import com.roombrowser.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v9 → v10 migration, run against a database that really is v9.
 *
 * WHY THIS TEST EXISTS. Every other test in the suite — and every CI job —
 * starts from a FRESH install, which creates v10 directly and never executes
 * a single migration. So a migration can be wrong in a way nothing notices
 * until it reaches a user who already has the app: Room validates the
 * migrated schema when it opens the database and throws if it does not match
 * the entities, which means a broken migration is not a missing column, it is
 * the app failing to start for everyone who upgrades. There is no other
 * automated guard for that, and none at all short of shipping it.
 *
 * HOW IT BUILDS A v9 DATABASE. Rather than hand-write the schema of all
 * twenty-odd tables at v9 — which would be a second, silently rotting copy of
 * the entities — it lets Room create a real database and then undoes exactly
 * the v9→v10 delta: the `tab_id` column and its index. Nothing else changed
 * in v10, so what is left is genuinely the v9 schema for the one table this
 * test is about, and any LATER change to that table makes the revert below
 * incomplete and this test wrong — which is why the revert is written out in
 * full, with the v9 shape named explicitly, instead of being derived from the
 * entity it is testing. Tables added by later versions are left alone: they
 * stay at the current shape, which is what the migration list below expects.
 */
@RunWith(AndroidJUnit4::class)
class AgentSessionMigrationTest {

    private val dbName = "agent-session-migration-test.db"
    private lateinit var context: Context

    private val profile = "11111111-1111-1111-1111-111111111111"
    private val tabA = "aaaaaaaa-1111-1111-1111-111111111111"
    private val tabB = "bbbbbbbb-2222-2222-2222-222222222222"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun upgrading_from_v9_keeps_every_chat_and_adds_the_tab_column() = runBlocking<Unit> {
        // 1. A real database with a real chat in it.
        val created = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        created.agentDao().insertSession(
            AgentSessionEntity(
                profileId = profile,
                title = "chat from before",
                providerId = 1,
                model = "m",
                createdAt = 1,
                updatedAt = 1
            )
        )
        created.close()

        revertToV9()

        // 2. Open through Room again. This runs MIGRATION_9_10 and then Room's
        //    own validation of the result against the entities — the step that
        //    throws, and takes the app down with it, when a migration is wrong.
        //
        //    The database above is created at the CURRENT version, so every
        //    step on the way back up has to be registered, and the app's own
        //    list is what supplies them. A hand-picked subset went stale the
        //    moment v12 was added: opening then failed with "a migration from
        //    9 to 12 was required but not found", so the test proved nothing
        //    about the migration it exists for.
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        try {
            // 3. The chat written at v9 is still there, and reads as bound to
            //    no tab — history, as the migration promises, not lost.
            val sessions = upgraded.agentDao().sessions(profile)
            assertThat(sessions).hasSize(1)
            assertThat(sessions.first().title).isEqualTo("chat from before")
            assertThat(sessions.first().tabId).isEmpty()
            // 4. And the column is usable for what it was added for.
            assertThat(upgraded.agentDao().sessionForTab(profile, tabA)).isNull()
            upgraded.agentDao().setTab(sessions.first().id, tabB)
            assertThat(upgraded.agentDao().sessionForTab(profile, tabB)?.id)
                .isEqualTo(sessions.first().id)
        } finally {
            upgraded.close()
        }
    }

    /**
     * Rewrites the freshly created database into the v9 shape, in place.
     *
     * SQLite cannot drop a column on the API levels this app supports
     * (DROP COLUMN needs 3.35; Android 11 ships 3.28), so agent_sessions is
     * rebuilt from the v9 CREATE TABLE and its rows copied across, keeping
     * their ids. The `room_master_table` row goes with it: it holds the v10
     * schema hash, and a database that claims to be v9 has no business
     * carrying it.
     */
    private fun revertToV9() {
        val db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(dbName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        try {
            db.execSQL(
                "CREATE TABLE `agent_sessions_v9` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`profile_id` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`provider_id` INTEGER NOT NULL, " +
                    "`model` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO `agent_sessions_v9` " +
                    "(`id`, `profile_id`, `title`, `provider_id`, `model`, `created_at`, `updated_at`) " +
                    "SELECT `id`, `profile_id`, `title`, `provider_id`, `model`, `created_at`, `updated_at` " +
                    "FROM `agent_sessions`"
            )
            // Dropping the table drops its indexes too, so the ones v9 had
            // are recreated and the v10 one simply never comes back.
            db.execSQL("DROP TABLE `agent_sessions`")
            db.execSQL("ALTER TABLE `agent_sessions_v9` RENAME TO `agent_sessions`")
            db.execSQL(
                "CREATE INDEX `index_agent_sessions_profile_id` ON `agent_sessions` (`profile_id`)"
            )
            db.execSQL("DROP TABLE IF EXISTS `room_master_table`")
            db.version = 9
        } finally {
            db.close()
        }
    }
}
