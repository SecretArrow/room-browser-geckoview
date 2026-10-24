package com.roombrowser

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AgentMessageEntity
import com.roombrowser.data.db.AgentSkillEntity
import com.roombrowser.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v18 -> v19 migration, run against a database that really is v18.
 *
 * A fresh install creates v19 directly and never executes this migration, so
 * this is the only automated guard that an upgrading user's database reaches
 * the new version with both halves of the delta usable: the two nullable
 * usage columns on `agent_messages` (existing rows must read back as null,
 * never as a fake zero) and the `agent_skills` table with its unique handle.
 * Room validates the migrated schema against the entities when it opens the
 * file and throws if they differ — so a wrong migration is not a missing
 * column, it is the app failing to start for every existing user.
 *
 * Building a v18 database: let Room create a real one at the current version,
 * then undo exactly the v18->v19 delta. The two added columns are removed by
 * rebuilding `agent_messages` in its v18 shape and copying the rows across —
 * SQLite's DROP COLUMN is too new to assume on every device the suite runs
 * on — and the `agent_skills` table (its unique index drops with it) plus the
 * `room_master_table` row are dropped outright. The copied rows survive, so
 * the upgrade path is exercised against real data rather than an empty table.
 */
@RunWith(AndroidJUnit4::class)
class AgentSkillMigrationTest {

    private val dbName = "agent-skill-migration-test.db"
    private lateinit var context: Context

    private val sessionId = 4242L
    private val answerText = "an answer written before the schema bump"

    /** Column name -> declared type / NOT NULL / part of the primary key. */
    private data class SqlColumn(val type: String, val notNull: Boolean, val pk: Boolean)

    private val expectedSkillColumns = mapOf(
        "id" to SqlColumn("INTEGER", notNull = true, pk = true),
        "skill_id" to SqlColumn("TEXT", notNull = true, pk = false),
        "display_name" to SqlColumn("TEXT", notNull = true, pk = false),
        "description" to SqlColumn("TEXT", notNull = true, pk = false),
        "source_repo" to SqlColumn("TEXT", notNull = true, pk = false),
        "sub_path" to SqlColumn("TEXT", notNull = true, pk = false),
        "version" to SqlColumn("TEXT", notNull = true, pk = false),
        "commit_ref" to SqlColumn("TEXT", notNull = true, pk = false),
        "skill_md_sha256" to SqlColumn("TEXT", notNull = true, pk = false),
        "npm_deps_json" to SqlColumn("TEXT", notNull = true, pk = false),
        "enabled" to SqlColumn("INTEGER", notNull = true, pk = false),
        "installed_at" to SqlColumn("INTEGER", notNull = true, pk = false),
        "updated_at" to SqlColumn("INTEGER", notNull = true, pk = false)
    )

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
    fun upgrading_from_v18_adds_usage_columns_and_a_usable_agent_skills_table() = runBlocking<Unit> {
        // 1. A real database at the current version, with an assistant message
        //    row the migration must carry through untouched.
        val created = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        created.agentDao().insertMessage(
            AgentMessageEntity(
                sessionId = sessionId,
                role = "assistant",
                content = answerText,
                createdAt = 1L
            )
        )
        assertThat(created.agentDao().usageForSession(sessionId).turns).isEqualTo(1)
        created.close()

        // 2. Strip it back to v18: no usage columns, no `agent_skills` table.
        revertToV18()
        assertThat(messageColumns().keys).doesNotContain("prompt_tokens")
        assertThat(messageColumns().keys).doesNotContain("completion_tokens")
        assertThat(tableNames()).doesNotContain("agent_skills")

        // 3. Open through Room again: it runs every step from v18 up to the
        //    current version — the real MIGRATION_18_19 among them, so the DDL
        //    under test is production code, not a copy — then validates against
        //    the entities.
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val dao = upgraded.agentDao()

            // The pre-existing message survived, and its usage columns read
            // back as NULL — the "unknown" a provider-silent row must carry,
            // never a fabricated zero.
            val totals = dao.usageForSession(sessionId)
            assertThat(totals.turns).isEqualTo(1)
            assertThat(totals.promptTokens).isEqualTo(0L)
            assertThat(totals.completionTokens).isEqualTo(0L)
            assertThat(messageColumns()["prompt_tokens"]!!.notNull).isFalse()
            assertThat(messageColumns()["completion_tokens"]!!.notNull).isFalse()

            // Usage records onto the migrated message and sums back.
            dao.setMessageUsage(1L, 120, 45)
            val after = dao.usageForSession(sessionId)
            assertThat(after.promptTokens).isEqualTo(120L)
            assertThat(after.completionTokens).isEqualTo(45L)

            // The skills table is usable end to end: insert, read, replace by
            // the unique handle, disable, delete.
            val skillDao = upgraded.agentSkillDao()
            assertThat(skillDao.all()).isEmpty()
            skillDao.upsert(
                AgentSkillEntity(
                    skillId = "binance-agentic-wallet",
                    displayName = "binance-agentic-wallet",
                    description = "drives the baw CLI",
                    sourceRepo = "binance/binance-skills-hub",
                    subPath = "skills/binance-web3/binance-agentic-wallet",
                    version = "1.13.0",
                    commitRef = "main",
                    skillMdSha256 = "ab".repeat(32),
                    npmDepsJson = "[\"@binance/agentic-wallet\"]",
                    enabled = true,
                    installedAt = 10L,
                    updatedAt = 11L
                )
            )
            val saved = skillDao.bySkillId("binance-agentic-wallet")
            assertThat(saved).isNotNull()
            assertThat(saved!!.npmDepsJson).isEqualTo("[\"@binance/agentic-wallet\"]")

            // A second upsert with the same skill_id REPLACES the row — the
            // unique index + @Upsert pair the re-install path rides.
            skillDao.upsert(
                (saved!!).copy(version = "1.14.0", updatedAt = 99L)
            )
            assertThat(skillDao.all()).hasSize(1)
            assertThat(skillDao.bySkillId("binance-agentic-wallet")!!.version).isEqualTo("1.14.0")

            skillDao.setEnabled("binance-agentic-wallet", false, 120L)
            assertThat(skillDao.bySkillId("binance-agentic-wallet")!!.enabled).isFalse()

            skillDao.delete("binance-agentic-wallet")
            assertThat(skillDao.all()).isEmpty()
        } finally {
            upgraded.close()
        }

        // 4. The migrated tables carry exactly the entity's columns, read
        //    back from the file — the schema Room itself validated, stated
        //    explicitly rather than inferred.
        assertThat(skillColumns()).containsExactlyEntriesIn(expectedSkillColumns)
        assertThat(indexNames()).containsExactly("index_agent_skills_skill_id")
    }

    private fun openRaw(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        context.getDatabasePath(dbName).path,
        null,
        SQLiteDatabase.OPEN_READWRITE
    )

    private fun tableNames(): List<String> {
        val db = openRaw()
        try {
            val out = mutableListOf<String>()
            val cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null)
            try {
                while (cursor.moveToNext()) out += cursor.getString(0)
            } finally {
                cursor.close()
            }
            return out
        } finally {
            db.close()
        }
    }

    private fun columnsOf(table: String): Map<String, SqlColumn> {
        val db = openRaw()
        try {
            val out = linkedMapOf<String, SqlColumn>()
            val cursor = db.rawQuery("PRAGMA table_info(`$table`)", null)
            try {
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                val typeIdx = cursor.getColumnIndexOrThrow("type")
                val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
                val pkIdx = cursor.getColumnIndexOrThrow("pk")
                while (cursor.moveToNext()) {
                    out[cursor.getString(nameIdx)] = SqlColumn(
                        type = cursor.getString(typeIdx),
                        notNull = cursor.getInt(notNullIdx) == 1,
                        pk = cursor.getInt(pkIdx) > 0
                    )
                }
            } finally {
                cursor.close()
            }
            return out
        } finally {
            db.close()
        }
    }

    private fun messageColumns(): Map<String, SqlColumn> = columnsOf("agent_messages")

    private fun skillColumns(): Map<String, SqlColumn> = columnsOf("agent_skills")

    private fun indexNames(): List<String> {
        val db = openRaw()
        try {
            val out = mutableListOf<String>()
            val cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'agent_skills'",
                null
            )
            try {
                while (cursor.moveToNext()) out += cursor.getString(0)
            } finally {
                cursor.close()
            }
            return out
        } finally {
            db.close()
        }
    }

    /**
     * Rewrites the freshly created database into the v18 shape, in place: the
     * usage columns are removed by rebuilding `agent_messages` at its v18
     * column set and copying every row across (DROP COLUMN cannot be assumed
     * on the SQLite every emulator here ships), `agent_skills` and its unique
     * index are dropped, and the `room_master_table` row — which holds the
     * current schema hash and has no business in a database that claims to be
     * v18 — goes with them.
     */
    private fun revertToV18() {
        // v18 also predates the scheduled-run plan column (v20): the replay
        // from here crosses 19 -> 20, and the current-shape table would make
        // that ADD COLUMN die with "duplicate column name".
        stripTaskPlanJsonColumn(openRaw())
        val db = openRaw()
        try {
            db.execSQL(
                "CREATE TABLE `agent_messages_v18` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`session_id` INTEGER NOT NULL, " +
                    "`role` TEXT NOT NULL, " +
                    "`content` TEXT NOT NULL, " +
                    "`tool_name` TEXT, " +
                    "`tool_args` TEXT, " +
                    "`tool_result` TEXT, " +
                    "`created_at` INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO `agent_messages_v18` " +
                    "(`id`, `session_id`, `role`, `content`, `tool_name`, `tool_args`, " +
                    "`tool_result`, `created_at`) " +
                    "SELECT `id`, `session_id`, `role`, `content`, `tool_name`, `tool_args`, " +
                    "`tool_result`, `created_at` FROM `agent_messages`"
            )
            db.execSQL("DROP TABLE `agent_messages`")
            db.execSQL("ALTER TABLE `agent_messages_v18` RENAME TO `agent_messages`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_messages_session_id` " +
                    "ON `agent_messages` (`session_id`)"
            )
            db.execSQL("DROP TABLE IF EXISTS `agent_skills`")
            db.execSQL("DROP TABLE IF EXISTS `room_master_table`")
            db.version = 18
        } finally {
            db.close()
        }
    }
}
