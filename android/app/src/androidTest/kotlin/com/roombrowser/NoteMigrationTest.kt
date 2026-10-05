package com.roombrowser

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v12 → v13 migration, run against a database that really is v12.
 *
 * Every other test starts from a fresh install, which creates v13 directly and
 * never executes this migration. Room validates the migrated schema when it
 * opens the database and throws if it does not match the entities — so a wrong
 * migration is not a missing column, it is the app failing to start for every
 * existing user. This is the only automated guard for that.
 *
 * Building a v12 database: rather than hand-write the schema of every table at
 * v12, let Room create a real database at the current version and undo exactly
 * the v12→v13 delta — the `notes` table (its indices drop with it). Nothing
 * else changed, so what remains is genuinely the v12 schema.
 */
@RunWith(AndroidJUnit4::class)
class NoteMigrationTest {

    private val dbName = "note-migration-test.db"
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
    fun upgrading_from_v12_adds_an_usable_notes_table() = runBlocking<Unit> {
        // 1. A real database, created at the current version. The query is what
        //    puts it on disk: build() alone opens nothing, and step 2 needs a
        //    file to rewrite.
        val created = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        assertThat(created.noteDao().allForProfile(profileId)).isEmpty()
        created.close()

        // 2. Strip it back to v12.
        revertToV12()

        // 3. Open through Room again: runs every step from v12 up to the
        //    current version, then Room's own validation against the entities
        //    — the step that throws, and takes the app down with it, when a
        //    migration is wrong. The app's own list, so a later version bump
        //    cannot leave this test opening a database it cannot reach.
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val dao = upgraded.noteDao()
            assertThat(dao.allForProfile(profileId)).isEmpty()

            val id = "note-1"
            dao.upsert(
                NoteEntity(
                    id = id,
                    profileId = profileId,
                    title = "groceries",
                    body = "milk\neggs",
                    createdAt = 1L,
                    updatedAt = 2L
                )
            )
            val saved = dao.byId(id)
            assertThat(saved).isNotNull()
            assertThat(saved!!.title).isEqualTo("groceries")
            assertThat(saved.profileId).isEqualTo(profileId)
            assertThat(saved.body).isEqualTo("milk\neggs")

            // The title/body columns are NOT NULL and readable; the
            // profile-scoped index the entity implies really exists, because
            // this scan goes through it.
            assertThat(dao.allForProfile(profileId)).hasSize(1)

            // The profile-deletion cascade works on the migrated table.
            dao.deleteAllForProfile(profileId)
            assertThat(dao.allForProfile(profileId)).isEmpty()
        } finally {
            upgraded.close()
        }
    }

    /**
     * Rewrites the freshly created database into the v12 shape, in place: v12
     * had no `notes` table at all. Dropping it (and its indices with it) and
     * the `room_master_table` row — which holds the current schema hash and has
     * no business in a database that claims to be v12 — leaves exactly v12.
     */
    private fun revertToV12() {
        val db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(dbName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        try {
            db.execSQL("DROP TABLE IF EXISTS `notes`")
            db.execSQL("DROP TABLE IF EXISTS `room_master_table`")
            db.version = 12
        } finally {
            db.close()
        }
    }
}
