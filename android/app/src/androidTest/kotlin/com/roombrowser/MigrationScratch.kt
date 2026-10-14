package com.roombrowser

import android.database.sqlite.SQLiteDatabase

/**
 * Undoes the v16 delta on `agent_sessions`, in place: the `plan_json` column
 * goes, everything v15 had stays.
 *
 * Every migration test in this source set builds its "old" database the same
 * way — let Room create a real one at the CURRENT version, then undo one
 * version's worth of change. That is exact for the table the test is about and
 * only for that table: every other table is left in its current shape. So a
 * later migration that ALTERs one of them finds the column already there, and
 * the replay dies with "duplicate column name" — which is what happens here,
 * because v16 added `plan_json` to `agent_sessions` and four tests replay from a
 * version below it.
 *
 * SQLite cannot drop a column on the API levels this app supports (DROP COLUMN
 * needs 3.35; Android 11 ships 3.28), so the table is rebuilt from its v15
 * CREATE TABLE with the rows copied across, ids included. `tab_id` and both
 * indices arrived at v10 and are recreated; the table's only other index
 * dropped with it.
 *
 * A later version that alters `agent_sessions` again has to extend this the same
 * way. The shape is written out rather than derived from the entity on purpose:
 * derived, it would follow every future column and the test would prove nothing.
 */
internal fun stripPlanJsonColumn(db: SQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE `agent_sessions_v15` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`profile_id` TEXT NOT NULL, " +
            "`title` TEXT NOT NULL, " +
            "`provider_id` INTEGER NOT NULL, " +
            "`model` TEXT NOT NULL, " +
            "`created_at` INTEGER NOT NULL, " +
            "`updated_at` INTEGER NOT NULL, " +
            "`tab_id` TEXT NOT NULL DEFAULT '')"
    )
    db.execSQL(
        "INSERT INTO `agent_sessions_v15` " +
            "(`id`, `profile_id`, `title`, `provider_id`, `model`, `created_at`, `updated_at`, `tab_id`) " +
            "SELECT `id`, `profile_id`, `title`, `provider_id`, `model`, `created_at`, `updated_at`, `tab_id` " +
            "FROM `agent_sessions`"
    )
    db.execSQL("DROP TABLE `agent_sessions`")
    db.execSQL("ALTER TABLE `agent_sessions_v15` RENAME TO `agent_sessions`")
    db.execSQL(
        "CREATE INDEX `index_agent_sessions_profile_id` ON `agent_sessions` (`profile_id`)"
    )
    db.execSQL(
        "CREATE INDEX `index_agent_sessions_profile_id_tab_id` " +
            "ON `agent_sessions` (`profile_id`, `tab_id`)"
    )
}
