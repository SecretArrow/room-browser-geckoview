package com.roombrowser

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.NoteEntity
import com.roombrowser.data.db.TotpEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v13 -> v14 migration, run against a database that really is v13.
 *
 * Every other test starts from a fresh install, which creates v14 directly and
 * never executes this migration. Room validates the migrated schema when it
 * opens the database and throws if it does not match the entities — so a wrong
 * migration is not a missing column, it is the app failing to start for every
 * existing user. This is the only automated guard for that.
 *
 * Building a v13 database: rather than hand-write the schema of every table at
 * v13, let Room create a real database at the current version and undo exactly
 * the v13->v14 delta — the `totp_entries` table, whose indices drop with it.
 * Nothing else changed, so what remains is genuinely v13.
 */
@RunWith(AndroidJUnit4::class)
class TotpMigrationTest {

    private val dbName = "totp-migration-test.db"
    private lateinit var context: Context

    private val profileId = "11111111-1111-1111-1111-111111111111"
    private val noteId = "note-keep"
    private val totpId = "totp-1"

    /** Column name -> declared type / NOT NULL / primary key, from table_info. */
    private data class SqlColumn(val type: String, val notNull: Boolean, val pk: Boolean)

    private val expectedColumns = mapOf(
        "id" to SqlColumn("TEXT", notNull = true, pk = true),
        "profile_id" to SqlColumn("TEXT", notNull = true, pk = false),
        "issuer" to SqlColumn("TEXT", notNull = true, pk = false),
        "account" to SqlColumn("TEXT", notNull = true, pk = false),
        "secret_enc" to SqlColumn("TEXT", notNull = true, pk = false),
        "algorithm" to SqlColumn("TEXT", notNull = true, pk = false),
        "digits" to SqlColumn("INTEGER", notNull = true, pk = false),
        "period" to SqlColumn("INTEGER", notNull = true, pk = false),
        "created_at" to SqlColumn("INTEGER", notNull = true, pk = false),
        "last_used_at" to SqlColumn("INTEGER", notNull = false, pk = false)
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
    fun upgrading_from_v13_adds_a_usable_totp_entries_table() = runBlocking<Unit> {
        // 1. A real database at the current version, with a row in a table the
        //    migration does not touch — the losslessness check.
        val created = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        created.noteDao().upsert(
            NoteEntity(
                id = noteId,
                profileId = profileId,
                title = "keep me",
                body = "pre-2FA data",
                createdAt = 1L,
                updatedAt = 2L
            )
        )
        assertThat(created.noteDao().byId(noteId)).isNotNull()
        created.close()

        // 2. Strip it back to v13, which had no `totp_entries` table.
        revertToV13()
        assertThat(tableNames()).doesNotContain("totp_entries")

        // 3. Open through Room again: it runs every step from v13 up to the
        //    current version — the real MIGRATION_13_14 among them, so the DDL
        //    under test is production code, not a copy — then runs its own
        //    validation against the entities. The app's own list, so a later
        //    version bump cannot leave this test opening a database it cannot
        //    reach.
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val dao = upgraded.totpDao()
            assertThat(dao.allForProfile(profileId)).isEmpty()

            // The pre-existing note survived the migration untouched.
            val note = upgraded.noteDao().byId(noteId)
            assertThat(note).isNotNull()
            assertThat(note!!.title).isEqualTo("keep me")
            assertThat(note.body).isEqualTo("pre-2FA data")

            // Every column of the new table is usable, including the NULLABLE
            // last_used_at — the one a copied DDL most often gets wrong.
            dao.upsert(
                TotpEntity(
                    id = totpId,
                    profileId = profileId,
                    issuer = "Acme",
                    account = "owner@acme.test",
                    secretEnc = "ciphertext-blob",
                    algorithm = "SHA1",
                    digits = 6,
                    period = 30,
                    createdAt = 10L,
                    lastUsedAt = null
                )
            )
            val saved = dao.byId(totpId)
            assertThat(saved).isNotNull()
            assertThat(saved!!.profileId).isEqualTo(profileId)
            assertThat(saved.issuer).isEqualTo("Acme")
            assertThat(saved.account).isEqualTo("owner@acme.test")
            assertThat(saved.secretEnc).isEqualTo("ciphertext-blob")
            assertThat(saved.algorithm).isEqualTo("SHA1")
            assertThat(saved.digits).isEqualTo(6)
            assertThat(saved.period).isEqualTo(30)
            assertThat(saved.createdAt).isEqualTo(10L)
            assertThat(saved.lastUsedAt).isNull()

            assertThat(dao.countForProfile(profileId)).isEqualTo(1)
            assertThat(dao.allForProfile(profileId)).hasSize(1)

            // The nullable last_used_at really accepts a value — the column the
            // "recently used" sort reads.
            dao.markUsed(totpId, 20L)
            assertThat(dao.byId(totpId)!!.lastUsedAt).isEqualTo(20L)

            // The profile-deletion cascade works on the migrated table, and the
            // note is still untouched.
            dao.deleteAllForProfile(profileId)
            assertThat(dao.countForProfile(profileId)).isEqualTo(0)
            assertThat(upgraded.noteDao().byId(noteId)).isNotNull()
        } finally {
            upgraded.close()
        }

        // 4. The table the migration created has exactly the entity's columns
        //    and both indices, read back from the file — the schema Room itself
        //    validated, stated explicitly rather than inferred.
        assertThat(totpColumns()).containsExactlyEntriesIn(expectedColumns)
        assertThat(totpIndexNames()).containsAtLeast(
            "index_totp_entries_profile_id",
            "index_totp_entries_profile_id_last_used_at"
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

    private fun totpColumns(): Map<String, SqlColumn> {
        val db = openRaw()
        try {
            val out = linkedMapOf<String, SqlColumn>()
            val cursor = db.rawQuery("PRAGMA table_info(`totp_entries`)", null)
            try {
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                val typeIdx = cursor.getColumnIndexOrThrow("type")
                val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
                val pkIdx = cursor.getColumnIndexOrThrow("pk")
                while (cursor.moveToNext()) {
                    out[cursor.getString(nameIdx)] = SqlColumn(
                        type = cursor.getString(typeIdx),
                        notNull = cursor.getInt(notNullIdx) == 1,
                        pk = cursor.getInt(pkIdx) == 1
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

    private fun totpIndexNames(): List<String> {
        val db = openRaw()
        try {
            val out = mutableListOf<String>()
            val cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'totp_entries'",
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
     * Rewrites the freshly created database into the v13 shape, in place: v13
     * had no `totp_entries` table at all. Dropping it (and its indices with it)
     * and the `room_master_table` row — which holds the current schema hash and
     * has no business in a database that claims to be v13 — leaves exactly v13.
     */
    private fun revertToV13() {
        val db = openRaw()
        try {
            db.execSQL("DROP TABLE IF EXISTS `totp_entries`")
            db.execSQL("DROP TABLE IF EXISTS `room_master_table`")
            db.version = 13
        } finally {
            db.close()
        }
    }
}
