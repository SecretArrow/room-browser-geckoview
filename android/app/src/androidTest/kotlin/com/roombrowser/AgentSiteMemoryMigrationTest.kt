package com.roombrowser

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AgentSiteMemoryEntity
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v14 -> v15 migration, run against a database that really is v14.
 *
 * A fresh install creates v15 directly and never executes this migration, so
 * this is the only automated guard that an upgrading user's database reaches the
 * new version with a usable `agent_site_memory` table. Room validates the
 * migrated schema against the entities when it opens the file and throws if they
 * differ — so a wrong migration is not a missing table, it is the app failing to
 * start for every existing user.
 *
 * Building a v14 database: let Room create a real one at the current version,
 * then undo exactly the v14->v15 delta — the `agent_site_memory` table, whose
 * indices drop with it. Every other table is left at the CURRENT shape, so a
 * later version that altered one of them has to be undone here as well:
 * `agent_sessions` gained `plan_json` at v16 and is stripped too. What remains
 * is v14.
 */
@RunWith(AndroidJUnit4::class)
class AgentSiteMemoryMigrationTest {

    private val dbName = "agent-site-memory-migration-test.db"
    private lateinit var context: Context

    private val profileId = "11111111-1111-1111-1111-111111111111"
    private val noteId = "note-keep"
    private val origin = "https://shop.example.com"

    /** Column name -> declared type / NOT NULL / part of the primary key. */
    private data class SqlColumn(val type: String, val notNull: Boolean, val pk: Boolean)

    private val expectedColumns = mapOf(
        "profile_id" to SqlColumn("TEXT", notNull = true, pk = true),
        "origin" to SqlColumn("TEXT", notNull = true, pk = true),
        "key" to SqlColumn("TEXT", notNull = true, pk = true),
        "value" to SqlColumn("TEXT", notNull = true, pk = false),
        "updated_at_ms" to SqlColumn("INTEGER", notNull = true, pk = false)
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
    fun upgrading_from_v14_adds_a_usable_agent_site_memory_table() = runBlocking<Unit> {
        // 1. A real database at the current version, with a row in a table the
        //    migration does not touch — the losslessness check.
        val created = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        created.noteDao().upsert(
            NoteEntity(
                id = noteId,
                profileId = profileId,
                title = "keep me",
                body = "pre-memory data",
                createdAt = 1L,
                updatedAt = 2L
            )
        )
        assertThat(created.noteDao().byId(noteId)).isNotNull()
        created.close()

        // 2. Strip it back to v14, which had no `agent_site_memory` table.
        revertToV14()
        assertThat(tableNames()).doesNotContain("agent_site_memory")

        // 3. Open through Room again: it runs every step from v14 up to the
        //    current version — the real MIGRATION_14_15 among them, so the DDL
        //    under test is production code, not a copy — then validates against
        //    the entities. The app's own list, so a later bump cannot leave this
        //    test opening a database it cannot reach.
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val dao = upgraded.agentSiteMemoryDao()
            assertThat(dao.allForProfile(profileId)).isEmpty()

            // The pre-existing note survived the migration untouched.
            val note = upgraded.noteDao().byId(noteId)
            assertThat(note).isNotNull()
            assertThat(note!!.title).isEqualTo("keep me")

            // Every column is usable, and the composite key really upserts
            // rather than appending a second row.
            dao.upsert(
                AgentSiteMemoryEntity(
                    profileId = profileId,
                    origin = origin,
                    key = "cart",
                    value = "2 items",
                    updatedAtMs = 10L
                )
            )
            val saved = dao.get(profileId, origin, "cart")
            assertThat(saved).isNotNull()
            assertThat(saved!!.value).isEqualTo("2 items")
            assertThat(saved.updatedAtMs).isEqualTo(10L)

            dao.upsert(
                AgentSiteMemoryEntity(
                    profileId = profileId,
                    origin = origin,
                    key = "cart",
                    value = "3 items",
                    updatedAtMs = 20L
                )
            )
            assertThat(dao.countForOrigin(profileId, origin)).isEqualTo(1)
            assertThat(dao.get(profileId, origin, "cart")!!.value).isEqualTo("3 items")

            // A different origin is a separate bucket for the same key.
            dao.upsert(
                AgentSiteMemoryEntity(
                    profileId = profileId,
                    origin = "https://other.test",
                    key = "cart",
                    value = "kept apart",
                    updatedAtMs = 30L
                )
            )
            assertThat(dao.forOrigin(profileId, origin)).hasSize(1)
            assertThat(dao.allForProfile(profileId)).hasSize(2)

            // Per-entry and per-site deletes work on the migrated table.
            dao.deleteOne(profileId, origin, "cart")
            assertThat(dao.countForOrigin(profileId, origin)).isEqualTo(0)
            dao.deleteOrigin(profileId, "https://other.test")
            assertThat(dao.allForProfile(profileId)).isEmpty()

            // And the profile-deletion cascade clears it, leaving the note.
            dao.upsert(
                AgentSiteMemoryEntity(
                    profileId = profileId,
                    origin = origin,
                    key = "again",
                    value = "v",
                    updatedAtMs = 40L
                )
            )
            dao.deleteAllForProfile(profileId)
            assertThat(dao.countForOrigin(profileId, origin)).isEqualTo(0)
            assertThat(upgraded.noteDao().byId(noteId)).isNotNull()
        } finally {
            upgraded.close()
        }

        // 4. The table the migration created has exactly the entity's columns
        //    and both indices, read back from the file — the schema Room itself
        //    validated, stated explicitly rather than inferred.
        assertThat(siteMemoryColumns()).containsExactlyEntriesIn(expectedColumns)
        assertThat(siteMemoryIndexNames()).containsAtLeast(
            "index_agent_site_memory_profile_id",
            "index_agent_site_memory_profile_id_origin"
        )
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

    private fun siteMemoryColumns(): Map<String, SqlColumn> {
        val db = openRaw()
        try {
            val out = linkedMapOf<String, SqlColumn>()
            val cursor = db.rawQuery("PRAGMA table_info(`agent_site_memory`)", null)
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

    private fun siteMemoryIndexNames(): List<String> {
        val db = openRaw()
        try {
            val out = mutableListOf<String>()
            val cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'agent_site_memory'",
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
     * Rewrites the freshly created database into the v14 shape, in place: v14
     * had no `agent_site_memory` table. Dropping it (and its indices with it)
     * and the `room_master_table` row — which holds the current schema hash and
     * has no business in a database that claims to be v14 — leaves exactly v14,
     * once `agent_sessions` is back in its pre-v16 shape too.
     */
    private fun revertToV14() {
        val db = openRaw()
        try {
            stripPlanJsonColumn(db)
            db.execSQL("DROP TABLE IF EXISTS `agent_site_memory`")
            db.execSQL("DROP TABLE IF EXISTS `room_master_table`")
            // plugins/chat_plugins arrive at v17; a database reverted to this
            // test's older version must not carry their v18 shape forward, or
            // the replay's 17 -> 18 ADD COLUMN finds the columns already there.
            db.execSQL("DROP TABLE IF EXISTS `chat_plugins`")
            db.execSQL("DROP TABLE IF EXISTS `plugins`")
            // The v19 delta is undone here too: the usage columns leave
            // agent_messages and agent_skills drops, or the replay's
            // 18 -> 19 step dies on a duplicate column. (v14 replay)
            stripAgentMessageUsageColumns(db)
            db.version = 14
        } finally {
            db.close()
        }
    }
}
