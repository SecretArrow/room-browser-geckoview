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

/**
 * Undoes the v18->v19 delta on `agent_messages`, in place: the two nullable
 * usage columns go, everything v18 had stays.
 *
 * Same story as [stripPlanJsonColumn] above — the migration tests build their
 * "old" database from the CURRENT schema, so a replay from below v19 finds
 * the columns already there and dies with "duplicate column name". Rebuilt
 * from the v18 CREATE TABLE with the rows copied across, ids included; the
 * two columns are nullable with no index, so nothing else drops with them.
 */
internal fun stripMessageUsageColumns(db: SQLiteDatabase) {
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
            "(`id`, `session_id`, `role`, `content`, `tool_name`, `tool_args`, `tool_result`, `created_at`) " +
            "SELECT `id`, `session_id`, `role`, `content`, `tool_name`, `tool_args`, `tool_result`, `created_at` " +
            "FROM `agent_messages`"
    )
    db.execSQL("DROP TABLE `agent_messages`")
    db.execSQL("ALTER TABLE `agent_messages_v18` RENAME TO `agent_messages`")
    db.execSQL(
        "CREATE INDEX `index_agent_messages_session_id` ON `agent_messages` (`session_id`)"
    )
}

/**
 * The sibling edition's name for [stripMessageUsageColumns], kept so a test
 * ported from either edition compiles against either file: one body, two
 * names, and a rename on one side cannot orphan the other's callers.
 */
internal fun stripAgentMessageUsageColumns(db: SQLiteDatabase) =
    stripMessageUsageColumns(db)

/**
 * Undoes the v19->v20 delta on `ai_tasks`, in place: the `plan_json` column
 * goes, everything v19 had stays.
 *
 * Same pattern again: the migration tests that replay from below v20 must not
 * leave the column in place, or the replay's 19 -> 20 ADD COLUMN dies with
 * "duplicate column name" before it tests anything.
 */
internal fun stripTaskPlanJsonColumn(db: SQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE `ai_tasks_v19` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, " +
            "`prompt` TEXT NOT NULL, " +
            "`profile_id` TEXT NOT NULL, " +
            "`schedule_json` TEXT NOT NULL, " +
            "`permissions_json` TEXT NOT NULL, " +
            "`run_config_json` TEXT NOT NULL DEFAULT '', " +
            "`enabled` INTEGER NOT NULL, " +
            "`last_run_at_ms` INTEGER, " +
            "`last_run_status` TEXT NOT NULL, " +
            "`last_result_summary` TEXT, " +
            "`created_at` INTEGER NOT NULL)"
    )
    db.execSQL(
        "INSERT INTO `ai_tasks_v19` " +
            "(`id`, `name`, `prompt`, `profile_id`, `schedule_json`, `permissions_json`, " +
            "`run_config_json`, `enabled`, `last_run_at_ms`, `last_run_status`, `last_result_summary`, `created_at`) " +
            "SELECT `id`, `name`, `prompt`, `profile_id`, `schedule_json`, `permissions_json`, " +
            "`run_config_json`, `enabled`, `last_run_at_ms`, `last_run_status`, `last_result_summary`, `created_at` " +
            "FROM `ai_tasks`"
    )
    db.execSQL("DROP TABLE `ai_tasks`")
    db.execSQL("ALTER TABLE `ai_tasks_v19` RENAME TO `ai_tasks`")
    db.execSQL(
        "CREATE INDEX `index_ai_tasks_profile_id` ON `ai_tasks` (`profile_id`)"
    )
}
