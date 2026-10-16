package com.roombrowser

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AgentSessionEntity
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.ChatPluginEntity
import com.roombrowser.data.db.NoteEntity
import com.roombrowser.data.db.PluginEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v16 -> v17 migration, run against a database that really is v16.
 *
 * A fresh install creates v17 directly and never executes this migration, so
 * this is the only automated guard that an upgrading user's database reaches
 * the new version with usable `plugins` and `chat_plugins` tables. Room
 * validates the migrated schema against the entities when it opens the file
 * and throws if they differ — so a wrong migration is not a missing table, it
 * is the app failing to start for every existing user.
 *
 * Building a v16 database: let Room create a real one at the current version,
 * then undo exactly the v16->v17 delta — the two new tables, whose indices
 * drop with them. `agent_sessions` is untouched by v17 (its `plan_json` came
 * at v16), so nothing else has to be stripped for what remains to be v16.
 * A note row written before the revert proves the migration is lossless.
 */
@RunWith(AndroidJUnit4::class)
class PluginMigrationTest {

    private val dbName = "plugin-migration-test.db"
    private lateinit var context: Context

    private val profileId = "22222222-2222-2222-2222-222222222222"
    private val noteId = "note-plugin-keep"
    private val pluginId = "com.example.hello"

    /** Column name -> declared type / NOT NULL / part of the primary key. */
    private data class SqlColumn(val type: String, val notNull: Boolean, val pk: Boolean)

    private val expectedPluginColumns = mapOf(
        "id" to SqlColumn("TEXT", notNull = true, pk = true),
        "name" to SqlColumn("TEXT", notNull = true, pk = false),
        "version" to SqlColumn("TEXT", notNull = true, pk = false),
        "description" to SqlColumn("TEXT", notNull = true, pk = false),
        "author_name" to SqlColumn("TEXT", notNull = true, pk = false),
        "license" to SqlColumn("TEXT", notNull = true, pk = false),
        "entry" to SqlColumn("TEXT", notNull = true, pk = false),
        "api_version" to SqlColumn("INTEGER", notNull = true, pk = false),
        "permissions_json" to SqlColumn("TEXT", notNull = true, pk = false),
        "network_allow_json" to SqlColumn("TEXT", notNull = true, pk = false),
        "config_schema_json" to SqlColumn("TEXT", notNull = true, pk = false),
        "commands_json" to SqlColumn("TEXT", notNull = true, pk = false),
        "hooks_json" to SqlColumn("TEXT", notNull = true, pk = false),
        "dir_name" to SqlColumn("TEXT", notNull = true, pk = false),
        "installed_at" to SqlColumn("INTEGER", notNull = true, pk = false),
        "updated_at" to SqlColumn("INTEGER", notNull = true, pk = false)
    )

    private val expectedChatPluginColumns = mapOf(
        "id" to SqlColumn("INTEGER", notNull = true, pk = true),
        "session_id" to SqlColumn("INTEGER", notNull = true, pk = false),
        "plugin_id" to SqlColumn("TEXT", notNull = true, pk = false),
        "enabled" to SqlColumn("INTEGER", notNull = true, pk = false),
        "granted_permissions_json" to SqlColumn("TEXT", notNull = true, pk = false),
        "config_json" to SqlColumn("TEXT", notNull = true, pk = false),
        "created_at" to SqlColumn("INTEGER", notNull = true, pk = false),
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
    fun upgrading_from_v16_adds_usable_plugin_tables() = runBlocking<Unit> {
        // 1. A real database at the current version, with a row in a table the
        //    migration does not touch — the losslessness check.
        val created = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        created.noteDao().upsert(
            NoteEntity(
                id = noteId,
                profileId = profileId,
                title = "keep me",
                body = "pre-plugin data",
                createdAt = 1L,
                updatedAt = 2L
            )
        )
        assertThat(created.noteDao().byId(noteId)).isNotNull()
        created.close()

        // 2. Strip it back to v16, which had neither plugin table.
        revertToV16()
        assertThat(tableNames()).doesNotContain("plugins")
        assertThat(tableNames()).doesNotContain("chat_plugins")

        // 3. Open through Room again: it runs every step from v16 up to the
        //    current version — the real MIGRATION_16_17 among them, so the DDL
        //    under test is production code, not a copy — then validates against
        //    the entities. The app's own list, so a later bump cannot leave
        //    this test opening a database it cannot reach.
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        try {
            assertThat(upgraded.pluginDao().all()).isEmpty()

            // The pre-existing note survived the migration untouched.
            val note = upgraded.noteDao().byId(noteId)
            assertThat(note).isNotNull()
            assertThat(note!!.title).isEqualTo("keep me")

            // A plugin row round-trips through the migrated table, and a
            // reinstall of the same id replaces rather than appends.
            upgraded.pluginDao().upsert(plugin(dirName = "com_example_hello", updatedAt = 10L))
            upgraded.pluginDao().upsert(plugin(dirName = "com_example_hello", updatedAt = 20L))
            val installed = upgraded.pluginDao().all()
            assertThat(installed).hasSize(1)
            assertThat(installed.single().dirName).isEqualTo("com_example_hello")
            assertThat(installed.single().updatedAt).isEqualTo(20L)

            // The per-chat binding is usable, and its uniqueness holds: the
            // second enable of the same plugin in the same chat reuses the
            // row rather than appending a second one.
            val sessionId = upgraded.agentDao().insertSession(
                AgentSessionEntity(
                    profileId = profileId,
                    title = "plugin chat",
                    providerId = 1L,
                    model = "m",
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
            upgraded.chatPluginDao().upsert(binding(sessionId, enabled = true, updatedAt = 10L))
            upgraded.chatPluginDao().upsert(
                binding(sessionId, enabled = false, updatedAt = 20L).copy(id = existingId(upgraded, sessionId))
            )
            assertThat(upgraded.chatPluginDao().forSession(sessionId)).hasSize(1)
            assertThat(upgraded.chatPluginDao().forSession(sessionId).single().enabled).isFalse()

            // Deleting the CHAT cascades its bindings away — the chat's plugin
            // state must not outlive the chat, and the note stays.
            upgraded.agentDao().deleteSession(sessionId)
            assertThat(upgraded.chatPluginDao().forSession(sessionId)).isEmpty()
            assertThat(upgraded.noteDao().byId(noteId)).isNotNull()

            // Uninstalling the PLUGIN cascades every chat's binding for it,
            // and the plugin row itself is gone.
            val otherSession = upgraded.agentDao().insertSession(
                AgentSessionEntity(
                    profileId = profileId,
                    title = "another chat",
                    providerId = 1L,
                    model = "m",
                    createdAt = 2L,
                    updatedAt = 2L
                )
            )
            upgraded.chatPluginDao().upsert(binding(otherSession, enabled = true, updatedAt = 30L))
            upgraded.pluginDao().delete(pluginId)
            assertThat(upgraded.chatPluginDao().forSession(otherSession)).isEmpty()
            assertThat(upgraded.pluginDao().byId(pluginId)).isNull()
        } finally {
            upgraded.close()
        }

        // 4. The tables the migration created have exactly the entities'
        //    columns and the chat-binding indices, read back from the file —
        //    the schema Room itself validated, stated explicitly rather than
        //    inferred.
        assertThat(columns("plugins")).containsExactlyEntriesIn(expectedPluginColumns)
        assertThat(columns("chat_plugins")).containsExactlyEntriesIn(expectedChatPluginColumns)
        assertThat(indexNames("chat_plugins")).containsExactly(
            "index_chat_plugins_session_id",
            "index_chat_plugins_session_id_plugin_id"
        )
    }

    // ------------------------------------------------------------- fixtures

    private fun plugin(dirName: String, updatedAt: Long) = PluginEntity(
        id = pluginId,
        name = "Hello World",
        version = "1.0.0",
        description = "A tiny example plugin.",
        authorName = "Jane Doe",
        license = "MIT",
        entry = "main.js",
        apiVersion = 1,
        permissionsJson = "[\"storage\"]",
        networkAllowJson = "[]",
        configSchemaJson = "[]",
        commandsJson = "[]",
        hooksJson = "[]",
        dirName = dirName,
        installedAt = 1L,
        updatedAt = updatedAt
    )

    private fun binding(sessionId: Long, enabled: Boolean, updatedAt: Long) = ChatPluginEntity(
        sessionId = sessionId,
        pluginId = pluginId,
        enabled = enabled,
        grantedPermissionsJson = "[\"storage\"]",
        configJson = "",
        createdAt = 1L,
        updatedAt = updatedAt
    )

    /** The row id the second upsert of the same binding must reuse. */
    private suspend fun existingId(db: AppDatabase, sessionId: Long): Long =
        db.chatPluginDao().forSession(sessionId).single().id

    // ------------------------------------------------------------- raw reads

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

    private fun columns(table: String): Map<String, SqlColumn> {
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

    private fun indexNames(table: String): List<String> {
        val db = openRaw()
        try {
            val out = mutableListOf<String>()
            val cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table'",
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
     * Rewrites the freshly created database into the v16 shape, in place: v16
     * had neither `plugins` nor `chat_plugins`. Dropping them (and their
     * indices with it) and the `room_master_table` row — which holds the
     * current schema hash and has no business in a database that claims to be
     * v16 — leaves exactly v16: `agent_sessions` already carried `plan_json`
     * by then, and nothing else changed in this step.
     */
    private fun revertToV16() {
        val db = openRaw()
        try {
            db.execSQL("DROP TABLE IF EXISTS `chat_plugins`")
            db.execSQL("DROP TABLE IF EXISTS `plugins`")
            db.execSQL("DROP TABLE IF EXISTS `room_master_table`")
            db.version = 16
        } finally {
            db.close()
        }
    }
}
