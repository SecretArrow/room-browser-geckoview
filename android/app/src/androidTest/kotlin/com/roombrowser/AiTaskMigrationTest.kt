package com.roombrowser

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AiTaskEntity
import com.roombrowser.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v10 → v12 migration chain, run against a database that really is v10.
 *
 * Every other test starts from a fresh install, which creates the current
 * version directly and never executes these migrations. Room validates the
 * migrated schema when it opens the database and throws if it does not match the
 * entities — so a wrong migration is not a missing column, it is the app failing
 * to start for every existing user. This is the only automated guard for that.
 *
 * Building a v10 database: rather than hand-write the schema of every table at
 * v10, let Room create a real database at the current version and undo exactly
 * the v10→v12 delta — the `ai_tasks` table and its index. Every other table is
 * left at the CURRENT shape, so a later version that altered one of them has to
 * be undone here as well: `agent_sessions` gained `plan_json` at v16 and is
 * stripped too. What remains is genuinely the v10 schema.
 */
@RunWith(AndroidJUnit4::class)
class AiTaskMigrationTest {

    private val dbName = "ai-task-migration-test.db"
    private lateinit var context: Context

    private val profileId = "11111111-1111-1111-1111-111111111111"

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
    fun upgrading_from_v10_adds_an_usable_ai_tasks_table() = runBlocking<Unit> {
        // 1. A real database, created at the current version. The query is what
        //    puts it on disk: build() alone opens nothing, and step 2 needs a
        //    file to rewrite.
        val created = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        assertThat(created.aiTaskDao().all()).isEmpty()
        created.close()

        // 2. Strip it back to v10.
        revertToV10()

        // 3. Open through Room again: runs every step from v10 up to the
        //    current version, then Room's own validation against the entities
        //    — the step that throws, and takes the app down with it, when a
        //    migration is wrong. The app's own list, so a later version bump
        //    cannot leave this test opening a database it cannot reach.
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val dao = upgraded.aiTaskDao()
            assertThat(dao.all()).isEmpty()

            val id = dao.upsert(
                AiTaskEntity(
                    name = "morning",
                    prompt = "summarize the news",
                    profileId = profileId,
                    scheduleJson = """{"kind":"DAILY","minuteOfDay":540}""",
                    permissionsJson = "{}",
                    enabled = true,
                    createdAt = 1L
                )
            )
            val saved = dao.get(id)
            assertThat(saved).isNotNull()
            assertThat(saved!!.name).isEqualTo("morning")
            assertThat(saved.profileId).isEqualTo(profileId)
            // v12's column really exists on the migrated table, and reads as
            // "no choice made" — the value every pre-existing row carries.
            assertThat(saved.runConfigJson).isEmpty()

            // The profile-deletion cascade index exists and works.
            dao.deleteAllForProfile(profileId)
            assertThat(dao.all()).isEmpty()
        } finally {
            upgraded.close()
        }
    }

    /**
     * Rewrites the freshly created database into the v10 shape, in place: v10
     * had no `ai_tasks` table at all. Dropping it (and its index with it) and
     * the `room_master_table` row — which holds the current schema hash and has
     * no business in a database that claims to be v10 — leaves exactly v10,
     * once `agent_sessions` is back in its pre-v16 shape too.
     */
    private fun revertToV10() {
        val db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(dbName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        try {
            stripMessageUsageColumns(db)
            stripTaskPlanJsonColumn(db)
            stripPlanJsonColumn(db)
            db.execSQL("DROP TABLE IF EXISTS `ai_tasks`")
            db.execSQL("DROP TABLE IF EXISTS `room_master_table`")
            // plugins/chat_plugins arrive at v17; a database reverted to this
            // test's older version must not carry their v18 shape forward, or
            // the replay's 17 -> 18 ADD COLUMN finds the columns already there.
            db.execSQL("DROP TABLE IF EXISTS `chat_plugins`")
            db.execSQL("DROP TABLE IF EXISTS `plugins`")
            // The v19 delta is undone here too: the usage columns leave
            // agent_messages and agent_skills drops, or the replay's
            // 18 -> 19 step dies on a duplicate column. (v10 replay)
            stripAgentMessageUsageColumns(db)
            db.version = 10
        } finally {
            db.close()
        }
    }
}
