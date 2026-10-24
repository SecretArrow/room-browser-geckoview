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
import com.roombrowser.domain.plugins.PluginFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The plugin-table migrations, run against databases that really are v16 and
 * v17.
 *
 * A fresh install creates the current version directly and never executes
 * these steps, so this is the only automated guard that an upgrading user's
 * database reaches the new version with usable `plugins` and `chat_plugins`
 * tables. Room validates the migrated schema against the entities when it
 * opens the file and throws if they differ — so a wrong migration is not a
 * missing table, it is the app failing to start for every existing user.
 *
 * Building an old database: let Room create a real one at the current
 * version, then undo exactly the delta — v16 had neither plugin table (drop
 * both, indices go with them); v17 had the tables but not the module columns
 * (rebuild both without them, indices recreated by hand). `agent_sessions`
 * is untouched by either step, and a note row written before the revert
 * proves each migration is lossless.
 */
@RunWith(AndroidJUnit4::class)
class PluginMigrationTest {

    private val dbName = "plugin-migration-test.db"
    private lateinit var context: Context

    private val profileId = "22222222-2222-2222-2222-222222222222"
    private val noteId = "note-plugin-keep"
    private val pluginId = "com.example.hello"
    private val moduleId = "zagents.daily"

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
        "updated_at" to SqlColumn("INTEGER", notNull = true, pk = false),
        "format" to SqlColumn("TEXT", notNull = true, pk = false),
        "zagents_name" to SqlColumn("TEXT", notNull = false, pk = false),
        "zagents_version" to SqlColumn("TEXT", notNull = false, pk = false),
        "zagents_entry" to SqlColumn("TEXT", notNull = false, pk = false),
        "timeout_seconds" to SqlColumn("INTEGER", notNull = false, pk = false),
        "inputs_json" to SqlColumn("TEXT", notNull = false, pk = false),
        "prompt_staged" to SqlColumn("INTEGER", notNull = true, pk = false)
    )

    private val expectedChatPluginColumns = mapOf(
        "id" to SqlColumn("INTEGER", notNull = true, pk = true),
        "session_id" to SqlColumn("INTEGER", notNull = true, pk = false),
        "plugin_id" to SqlColumn("TEXT", notNull = true, pk = false),
        "enabled" to SqlColumn("INTEGER", notNull = true, pk = false),
        "granted_permissions_json" to SqlColumn("TEXT", notNull = true, pk = false),
        "config_json" to SqlColumn("TEXT", notNull = true, pk = false),
        "created_at" to SqlColumn("INTEGER", notNull = true, pk = false),
        "updated_at" to SqlColumn("INTEGER", notNull = true, pk = false),
        "inputs_values_json" to SqlColumn("TEXT", notNull = false, pk = false)
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
        //    current version — the real MIGRATION_16_17 and MIGRATION_17_18
        //    among them, so the DDL under test is production code, not a
        //    copy — then validates against the entities. The app's own list,
        //    so a later bump cannot leave this test opening a database it
        //    cannot reach.
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

        // 4. The tables the migrations created have exactly the entities'
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

    @Test
    fun upgrading_from_v17_defaults_the_module_columns() = runBlocking<Unit> {
        // 1. A real database at the current version carrying one plugin row
        //    and one binding — the v17 user's database, module columns and
        //    all, written through the entity so every default is Room's own.
        val created = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        created.noteDao().upsert(
            NoteEntity(
                id = noteId,
                profileId = profileId,
                title = "keep me",
                body = "pre-module data",
                createdAt = 1L,
                updatedAt = 2L
            )
        )
        val sessionId = created.agentDao().insertSession(
            AgentSessionEntity(
                profileId = profileId,
                title = "plugin chat",
                providerId = 1L,
                model = "m",
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        created.pluginDao().upsert(plugin(dirName = "com_example_hello", updatedAt = 10L))
        created.chatPluginDao().upsert(binding(sessionId, enabled = true, updatedAt = 10L))
        created.close()

        // 2. Strip it back to v17: the plugin tables existed, without the
        //    module columns the v18 step adds.
        revertToV17()
        assertThat(columns("plugins")).doesNotContainKey("format")
        assertThat(columns("chat_plugins")).doesNotContainKey("inputs_values_json")

        // 3. Open through Room again — only MIGRATION_17_18 runs — and the
        //    pre-existing rows read with their defaults rather than as
        //    half-columns: every v17 row was a plugin.json row, so `format`
        //    says so and the module-only columns say nothing.
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val old = upgraded.pluginDao().byId(pluginId)!!
            assertThat(old.format).isEqualTo(PluginFormat.PLUGIN)
            assertThat(old.zagentsName).isNull()
            assertThat(old.zagentsVersion).isNull()
            assertThat(old.zagentsEntry).isNull()
            assertThat(old.timeoutSeconds).isNull()
            assertThat(old.inputsJson).isNull()
            assertThat(old.promptStaged).isFalse()
            assertThat(upgraded.pluginDao().byFormat(PluginFormat.PLUGIN).map { it.id })
                .containsExactly(pluginId)
            assertThat(upgraded.pluginDao().byFormat(PluginFormat.ZAGENTS)).isEmpty()

            val oldBinding = upgraded.chatPluginDao().forSession(sessionId).single()
            assertThat(oldBinding.inputsValuesJson).isNull()
            assertThat(upgraded.noteDao().byId(noteId)!!.title).isEqualTo("keep me")

            // A module row round-trips through the new columns, and the
            // staged-activation flag the fresh-chat enable sets is the one
            // write the chat controller makes — set, read, cleared.
            upgraded.pluginDao().upsert(module())
            val stored = upgraded.pluginDao().byId(moduleId)!!
            assertThat(stored.format).isEqualTo(PluginFormat.ZAGENTS)
            assertThat(stored.zagentsName).isEqualTo("daily")
            assertThat(stored.zagentsVersion).isEqualTo("1.2.3.4")
            assertThat(stored.zagentsEntry).isEqualTo("main.js")
            assertThat(stored.timeoutSeconds).isEqualTo(45)
            assertThat(stored.inputsJson).isNotNull()
            assertThat(upgraded.pluginDao().byFormat(PluginFormat.ZAGENTS).map { it.id })
                .containsExactly(moduleId)

            upgraded.pluginDao().setPromptStaged(moduleId, true)
            assertThat(upgraded.pluginDao().byId(moduleId)!!.promptStaged).isTrue()
            upgraded.pluginDao().setPromptStaged(moduleId, false)
            assertThat(upgraded.pluginDao().byId(moduleId)!!.promptStaged).isFalse()

            // And the binding's form answers round-trip the same way —
            // stored only after the form validated, read back verbatim.
            upgraded.chatPluginDao().upsert(
                upgraded.chatPluginDao().forSession(sessionId).single()
                    .copy(inputsValuesJson = """{"email":"you@example.com"}""")
            )
            assertThat(upgraded.chatPluginDao().forSession(sessionId).single().inputsValuesJson)
                .isEqualTo("""{"email":"you@example.com"}""")
        } finally {
            upgraded.close()
        }

        // 4. The migrated tables carry exactly the entities' columns — the
        //    schema Room itself validated, read back from the file.
        assertThat(columns("plugins")).containsExactlyEntriesIn(expectedPluginColumns)
        assertThat(columns("chat_plugins")).containsExactlyEntriesIn(expectedChatPluginColumns)
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

    /** A row a module.json install produces, module columns and all. */
    private fun module() = PluginEntity(
        id = moduleId,
        name = "Daily Digest",
        version = "1.2.3",
        description = "Composes a daily summary.",
        authorName = "",
        license = "",
        entry = "main.js",
        apiVersion = 1,
        permissionsJson = "[\"storage\",\"network\",\"files\",\"chat.context\",\"agent.generate\"]",
        networkAllowJson = "[\"api.onhomeroom.com\"]",
        configSchemaJson = "[]",
        commandsJson = "[]",
        hooksJson = "[]",
        dirName = "zagents_daily",
        installedAt = 1L,
        updatedAt = 1L,
        format = PluginFormat.ZAGENTS,
        zagentsName = "daily",
        zagentsVersion = "1.2.3.4",
        zagentsEntry = "main.js",
        timeoutSeconds = 45,
        inputsJson = "[{\"key\":\"email\",\"type\":\"string\",\"title\":\"Email\",\"required\":true}]"
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
            // v16 also predates the agent schema's later deltas: the replay
            // from here crosses 18 -> 19 (message usage columns) and
            // 19 -> 20 (the task plan column), and the current-shape tables
            // would make those ADD COLUMNs die with "duplicate column name".
            stripMessageUsageColumns(db)
            stripTaskPlanJsonColumn(db)
            db.execSQL("DROP TABLE IF EXISTS `chat_plugins`")
            db.execSQL("DROP TABLE IF EXISTS `plugins`")
            db.execSQL("DROP TABLE IF EXISTS `room_master_table`")
            db.version = 16
        } finally {
            db.close()
        }
    }

    /**
     * Rewrites the freshly created database into the v17 shape, in place:
     * v17 had both plugin tables but none of the module columns, so each
     * table is rebuilt under a temporary name without them, copied across,
     * swapped in, and re-indexed (dropping a table drops its indices). The
     * `room_master_table` row goes for the same reason as [revertToV16]'s —
     * a v17 file has no business carrying the v18 schema hash.
     */
    private fun revertToV17() {
        val db = openRaw()
        try {
            // v17 predates the v18->v19 usage columns: same duplicate-column
            // guard as revertToV16 above.
            stripMessageUsageColumns(db)
            stripTaskPlanJsonColumn(db)
            db.execSQL("DROP TABLE IF EXISTS `room_master_table`")
            db.execSQL(
                "CREATE TABLE `plugins_v17` (" +
                    "`id` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`version` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, " +
                    "`author_name` TEXT NOT NULL, " +
                    "`license` TEXT NOT NULL, " +
                    "`entry` TEXT NOT NULL, " +
                    "`api_version` INTEGER NOT NULL, " +
                    "`permissions_json` TEXT NOT NULL, " +
                    "`network_allow_json` TEXT NOT NULL, " +
                    "`config_schema_json` TEXT NOT NULL, " +
                    "`commands_json` TEXT NOT NULL, " +
                    "`hooks_json` TEXT NOT NULL, " +
                    "`dir_name` TEXT NOT NULL, " +
                    "`installed_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "INSERT INTO `plugins_v17` (`id`,`name`,`version`,`description`,`author_name`," +
                    "`license`,`entry`,`api_version`,`permissions_json`,`network_allow_json`," +
                    "`config_schema_json`,`commands_json`,`hooks_json`,`dir_name`,`installed_at`," +
                    "`updated_at`) SELECT `id`,`name`,`version`,`description`,`author_name`," +
                    "`license`,`entry`,`api_version`,`permissions_json`,`network_allow_json`," +
                    "`config_schema_json`,`commands_json`,`hooks_json`,`dir_name`,`installed_at`," +
                    "`updated_at` FROM `plugins`"
            )
            db.execSQL("DROP TABLE `plugins`")
            db.execSQL("ALTER TABLE `plugins_v17` RENAME TO `plugins`")
            db.execSQL(
                "CREATE TABLE `chat_plugins_v17` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`session_id` INTEGER NOT NULL, " +
                    "`plugin_id` TEXT NOT NULL, " +
                    "`enabled` INTEGER NOT NULL, " +
                    "`granted_permissions_json` TEXT NOT NULL, " +
                    "`config_json` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`session_id`) REFERENCES `agent_sessions`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(`plugin_id`) REFERENCES `plugins`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL(
                "INSERT INTO `chat_plugins_v17` (`id`,`session_id`,`plugin_id`,`enabled`," +
                    "`granted_permissions_json`,`config_json`,`created_at`,`updated_at`) " +
                    "SELECT `id`,`session_id`,`plugin_id`,`enabled`,`granted_permissions_json`," +
                    "`config_json`,`created_at`,`updated_at` FROM `chat_plugins`"
            )
            db.execSQL("DROP TABLE `chat_plugins`")
            db.execSQL("ALTER TABLE `chat_plugins_v17` RENAME TO `chat_plugins`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_chat_plugins_session_id` " +
                    "ON `chat_plugins` (`session_id`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_chat_plugins_session_id_plugin_id` " +
                    "ON `chat_plugins` (`session_id`, `plugin_id`)"
            )
            db.version = 17
        } finally {
            db.close()
        }
    }
}
