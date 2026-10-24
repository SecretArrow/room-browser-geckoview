package com.roombrowser.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room Browser metadata database.
 *
 * NOTE ON MULTI-PROCESS: the browser engine runs in the ':browser' process
 * while profile management runs in the main process. Both access this
 * database, so multi-instance invalidation is mandatory.
 *
 * Browser-engine storage (cookies, localStorage, IndexedDB, cache, service
 * workers) is NEVER stored in Room — it lives in the per-profile WebView
 * data directories (see PROFILE_ISOLATION.md).
 */
@Database(
    entities = [
        ProfileEntity::class,
        TabEntity::class,
        BookmarkEntity::class,
        HistoryEntity::class,
        DownloadEntity::class,
        SitePermissionEntity::class,
        SiteSettingEntity::class,
        IpHistoryEntity::class,
        BlockEventEntity::class,
        AppStateEntity::class,
        CustomThemeEntity::class,
        AgentProviderEntity::class,
        AgentSessionEntity::class,
        AgentMessageEntity::class,
        CredentialEntity::class,
        WalletEntity::class,
        WalletAccountEntity::class,
        WalletNetworkEntity::class,
        DappPermissionEntity::class,
        WalletActivityEntity::class,
        WalletActiveNetworkEntity::class,
        AiTaskEntity::class,
        NoteEntity::class,
        TotpEntity::class,
        AgentSiteMemoryEntity::class,
        PluginEntity::class,
        ChatPluginEntity::class,
        AgentSkillEntity::class
    ],
    version = 20,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun tabDao(): TabDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun siteSettingsDao(): SiteSettingsDao
    abstract fun ipHistoryDao(): IpHistoryDao
    abstract fun statsDao(): StatsDao
    abstract fun appStateDao(): AppStateDao
    abstract fun themeDao(): ThemeDao
    abstract fun agentDao(): AgentDao
    abstract fun credentialDao(): CredentialDao
    abstract fun walletDao(): WalletDao
    abstract fun walletAccountDao(): WalletAccountDao
    abstract fun walletNetworkDao(): WalletNetworkDao
    abstract fun dappPermissionDao(): DappPermissionDao
    abstract fun walletActivityDao(): WalletActivityDao
    abstract fun aiTaskDao(): AiTaskDao
    abstract fun noteDao(): NoteDao
    abstract fun totpDao(): TotpDao
    abstract fun agentSiteMemoryDao(): AgentSiteMemoryDao
    abstract fun pluginDao(): PluginDao
    abstract fun chatPluginDao(): ChatPluginDao
    abstract fun agentSkillDao(): AgentSkillDao

    companion object {
        const val NAME = "room-browser.db"

        /**
         * v1 → v2: adds the AI agent tables (providers / sessions /
         * messages). Pure additive CREATE TABLE + INDEX statements — no
         * existing table is touched, so the migration is lossless.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_providers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`base_url` TEXT NOT NULL, " +
                        "`api_key_enc` TEXT NOT NULL, " +
                        "`default_model` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`profile_id` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`provider_id` INTEGER NOT NULL, " +
                        "`model` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_sessions_profile_id` " +
                        "ON `agent_sessions` (`profile_id`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_messages` (" +
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
                    "CREATE INDEX IF NOT EXISTS `index_agent_messages_session_id` " +
                        "ON `agent_messages` (`session_id`)"
                )
            }
        }

        /**
         * v2 → v3: adds the `protocol` column to agent_providers
         * (OPENAI = chat/completions, OPENCODE = `opencode serve`). Additive
         * ALTER TABLE with a default — existing rows stay OPENAI. Lossless.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `agent_providers` ADD COLUMN `protocol` TEXT NOT NULL DEFAULT 'OPENAI'"
                )
            }
        }

        /**
         * v3 → v4: per-profile theme system. Adds `profiles.theme_json`
         * (full RoomThemeSpec snapshot; "" = built-in default theme) and the
         * `themes` gallery table for user-saved custom themes. Purely
         * additive — no existing data is touched. Lossless.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `profiles` ADD COLUMN `theme_json` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `themes` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`spec_json` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * Adds the per-provider tool mode. Existing rows get the AUTO default,
         * which is what they were already doing implicitly — send `tools` and
         * read `tool_calls` back — so an upgrade changes no provider's
         * behaviour until the user picks a different mode.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `agent_providers` ADD COLUMN `tool_mode` TEXT NOT NULL DEFAULT 'AUTO'"
                )
            }
        }

        /**
         * Adds the User-Agent a download must present. Existing rows get the
         * empty string, which the engine reads as "no UA on record" and sends
         * no User-Agent header for — the same request they would have made
         * before this column existed, so an upgrade changes no behaviour.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `downloads` ADD COLUMN `user_agent` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v6 → v7: adds the password manager `credentials` table. Purely
         * additive CREATE TABLE + INDEX statements — no existing table is
         * touched, so the migration is lossless. The column set mirrors
         * CredentialEntity exactly (snake_case names, NOT NULL on non-null
         * Kotlin types, nullable title), which is what Room's schema
         * validation compares against after a migration.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `credentials` (" +
                        "`id` TEXT NOT NULL, " +
                        "`profile_id` TEXT NOT NULL, " +
                        "`domain` TEXT NOT NULL, " +
                        "`username` TEXT NOT NULL, " +
                        "`password_enc` TEXT NOT NULL, " +
                        "`title` TEXT, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_credentials_profile_id` " +
                        "ON `credentials` (`profile_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_credentials_profile_id_domain` " +
                        "ON `credentials` (`profile_id`, `domain`)"
                )
            }
        }

        /**
         * v7 → v8: adds the multi-chain wallet tables (wallets /
         * wallet_accounts / wallet_networks / dapp_permissions /
         * wallet_activities / wallet_active_networks). Purely additive
         * CREATE TABLE + INDEX statements — no existing table is touched,
         * so the migration is lossless. The column sets mirror the wallet
         * entities exactly (same snake_case names, NOT NULL on non-null
         * Kotlin types), which is what Room's schema validation compares
         * against after a migration.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wallets` (" +
                        "`id` TEXT NOT NULL, " +
                        "`profile_id` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`mnemonic_enc` TEXT, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_wallets_profile_id` " +
                        "ON `wallets` (`profile_id`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wallet_accounts` (" +
                        "`id` TEXT NOT NULL, " +
                        "`wallet_id` TEXT NOT NULL, " +
                        "`chain_type` TEXT NOT NULL, " +
                        "`address` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`path` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`private_key_enc` TEXT, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wallet_accounts_wallet_id` " +
                        "ON `wallet_accounts` (`wallet_id`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_wallet_accounts_wallet_id_chain_type_address` " +
                        "ON `wallet_accounts` (`wallet_id`, `chain_type`, `address`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wallet_networks` (" +
                        "`id` TEXT NOT NULL, " +
                        "`profile_id` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`is_custom` INTEGER NOT NULL, " +
                        "`payload` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wallet_networks_profile_id` " +
                        "ON `wallet_networks` (`profile_id`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_wallet_networks_profile_id_id` " +
                        "ON `wallet_networks` (`profile_id`, `id`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dapp_permissions` (" +
                        "`id` TEXT NOT NULL, " +
                        "`profile_id` TEXT NOT NULL, " +
                        "`host` TEXT NOT NULL, " +
                        "`chain_type` TEXT NOT NULL, " +
                        "`account_address` TEXT NOT NULL, " +
                        "`methods_json` TEXT NOT NULL, " +
                        "`granted_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dapp_permissions_profile_id` " +
                        "ON `dapp_permissions` (`profile_id`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_dapp_permissions_profile_id_host_chain_type_account_address` " +
                        "ON `dapp_permissions` (`profile_id`, `host`, `chain_type`, `account_address`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wallet_activities` (" +
                        "`id` TEXT NOT NULL, " +
                        "`profile_id` TEXT NOT NULL, " +
                        "`chain_type` TEXT NOT NULL, " +
                        "`network_name` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`account_address` TEXT NOT NULL, " +
                        "`to_address` TEXT, " +
                        "`display_amount` TEXT NOT NULL, " +
                        "`hash` TEXT, " +
                        "`explorer_url` TEXT, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wallet_activities_profile_id` " +
                        "ON `wallet_activities` (`profile_id`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wallet_active_networks` (" +
                        "`profile_id` TEXT NOT NULL, " +
                        "`chain_type` TEXT NOT NULL, " +
                        "`network_id` TEXT NOT NULL, " +
                        "PRIMARY KEY(`profile_id`, `chain_type`))"
                )
            }
        }

        /**
         * v8 → v9: wallet_networks' primary key was the table-wide `id`
         * (the NetworkConfig id, e.g. "EVM:1") — one profile silently owned
         * every row of a network and any other profile's seeding collided
         * on the PK (caught by the wallet-isolation e2e). Rebuilt with the
         * composite (profile_id, id) primary key; existing rows copy over
         * as-is (v8 ids were globally unique, so no conflict is possible).
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wallet_networks_v9` (" +
                        "`id` TEXT NOT NULL, " +
                        "`profile_id` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`is_custom` INTEGER NOT NULL, " +
                        "`payload` TEXT NOT NULL, " +
                        "PRIMARY KEY(`profile_id`, `id`))"
                )
                db.execSQL(
                    "INSERT INTO `wallet_networks_v9` (`id`, `profile_id`, `enabled`, `is_custom`, `payload`) " +
                        "SELECT `id`, `profile_id`, `enabled`, `is_custom`, `payload` FROM `wallet_networks`"
                )
                db.execSQL("DROP TABLE `wallet_networks`")
                db.execSQL("ALTER TABLE `wallet_networks_v9` RENAME TO `wallet_networks`")
            }
        }

        /**
         * v9 → v10: per-tab agent chats. Adds `agent_sessions.tab_id` plus
         * the (profile_id, tab_id) index the per-tab lookup reads through.
         *
         * Purely additive: every existing chat gets the "" default, which is
         * read as "not bound to a tab" — the chat stays in the history list
         * and nothing is lost, but no tab claims it, so each tab starts its
         * own conversation from the next message on.
         *
         * A binding outlives the tab it names: closing a tab leaves the chat
         * bound to its id, and reopening a closed tab reuses that id (see
         * BrowserViewModel.reopenClosedTab), so the conversation comes back
         * with the tab. A chat bound to an id no live tab carries is simply
         * not reachable until something reopens it — never wrong, only idle.
         *
         * `internal` rather than private so AgentSessionMigrationTest can run
         * it against a database built back into its v9 shape. Nothing else in
         * a fresh install ever executes this: a new install creates v10
         * directly, so without that test no CI job would notice a migration
         * that bricks the upgrade for everyone who already has the app.
         */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `agent_sessions` ADD COLUMN `tab_id` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_sessions_profile_id_tab_id` " +
                        "ON `agent_sessions` (`profile_id`, `tab_id`)"
                )
            }
        }

        /**
         * v10 → v11: adds the scheduled-AI-tasks table. Purely additive
         * CREATE TABLE + INDEX statements — no existing table is touched, so
         * the migration is lossless. The column set mirrors AiTaskEntity
         * exactly (snake_case names, NOT NULL only on non-null Kotlin types),
         * which is what Room's schema validation compares against after a
         * migration.
         *
         * `internal` rather than private for the same reason as
         * [MIGRATION_9_10]: a fresh install creates the current version directly
         * and never executes this, so only AiTaskMigrationTest would notice a
         * migration that bricks the upgrade for existing installs.
         */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_tasks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`prompt` TEXT NOT NULL, " +
                        "`profile_id` TEXT NOT NULL, " +
                        "`schedule_json` TEXT NOT NULL, " +
                        "`permissions_json` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`last_run_at_ms` INTEGER, " +
                        "`last_run_status` TEXT NOT NULL, " +
                        "`last_result_summary` TEXT, " +
                        "`created_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ai_tasks_profile_id` " +
                        "ON `ai_tasks` (`profile_id`)"
                )
            }
        }

        /**
         * v11 → v12: adds the per-task provider/model/execution-mode choice.
         * Additive with a default, so an existing task keeps running exactly as
         * it did (AUTO on the agent's default provider, headless).
         *
         * `internal` for the same reason as [MIGRATION_10_11]: only
         * AiTaskMigrationTest executes it.
         */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `ai_tasks` ADD COLUMN `run_config_json` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v12 → v13: adds the per-profile notes table. Purely additive
         * CREATE TABLE + INDEX statements — no existing table is touched, so
         * the migration is lossless. The column set and both indices mirror
         * NoteEntity exactly (snake_case names, NOT NULL on non-null Kotlin
         * types), which is what Room's schema validation compares against
         * after a migration.
         *
         * `internal` for the same reason as [MIGRATION_10_11]: only
         * NoteMigrationTest executes it, since a fresh install creates v13
         * directly.
         */
        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notes` (" +
                        "`id` TEXT NOT NULL, " +
                        "`profile_id` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`body` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notes_profile_id` " +
                        "ON `notes` (`profile_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notes_profile_id_updated_at` " +
                        "ON `notes` (`profile_id`, `updated_at`)"
                )
            }
        }

        /**
         * v13 → v14: adds the per-profile authenticator table. Purely additive
         * CREATE TABLE + INDEX statements — no existing table is touched, so
         * the migration is lossless. The column set and both indices mirror
         * TotpEntity exactly (snake_case names, NOT NULL on non-null Kotlin
         * types, a NULLABLE `last_used_at`), which is what Room's schema
         * validation compares against after a migration.
         *
         * `last_used_at` is in this CREATE TABLE rather than a later ALTER: the
         * table is new here, so adding it now costs nothing and adding it later
         * would cost another migration.
         *
         * `internal` for the same reason as [MIGRATION_10_11]: only
         * TotpMigrationTest executes it, since a fresh install creates v14
         * directly.
         */
        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `totp_entries` (" +
                        "`id` TEXT NOT NULL, " +
                        "`profile_id` TEXT NOT NULL, " +
                        "`issuer` TEXT NOT NULL, " +
                        "`account` TEXT NOT NULL, " +
                        "`secret_enc` TEXT NOT NULL, " +
                        "`algorithm` TEXT NOT NULL, " +
                        "`digits` INTEGER NOT NULL, " +
                        "`period` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`last_used_at` INTEGER, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_totp_entries_profile_id` " +
                        "ON `totp_entries` (`profile_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_totp_entries_profile_id_last_used_at` " +
                        "ON `totp_entries` (`profile_id`, `last_used_at`)"
                )
            }
        }

        /**
         * v14 → v15: adds the per-profile, per-site agent memory table. Purely
         * additive CREATE TABLE + INDEX statements — no existing table is
         * touched, so the migration is lossless. The column set and both indices
         * mirror AgentSiteMemoryEntity exactly (snake_case names, NOT NULL on
         * non-null Kotlin types), which is what Room's schema validation
         * compares against after a migration.
         *
         * `internal` for the same reason as [MIGRATION_10_11]: only
         * AgentSiteMemoryMigrationTest executes it, since a fresh install
         * creates v15 directly.
         */
        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_site_memory` (" +
                        "`profile_id` TEXT NOT NULL, " +
                        "`origin` TEXT NOT NULL, " +
                        "`key` TEXT NOT NULL, " +
                        "`value` TEXT NOT NULL, " +
                        "`updated_at_ms` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`profile_id`, `origin`, `key`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_site_memory_profile_id` " +
                        "ON `agent_site_memory` (`profile_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_site_memory_profile_id_origin` " +
                        "ON `agent_site_memory` (`profile_id`, `origin`)"
                )
            }
        }

        /**
         * v15 → v16: `agent_sessions.plan_json`, the step list the chat last
         * wrote with `plan`.
         *
         * Purely additive: every existing chat gets "", which reads as "no
         * list yet", so nothing is lost and the next turn writes its own.
         *
         * `internal` for the same reason as [MIGRATION_10_11]: only
         * AgentSessionMigrationTest executes it, since a fresh install creates
         * v16 directly.
         */
        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `agent_sessions` ADD COLUMN `plan_json` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v16 → v17: adds the chat-plugin tables — installed plugins and the
         * per-chat bindings that decide which plugin runs where. Purely additive
         * CREATE TABLE + INDEX statements, so the migration is lossless; the
         * column sets mirror PluginEntity and ChatPluginEntity exactly
         * (snake_case names, NOT NULL on non-null Kotlin types, SQLite affinity
         * for the Kotlin types: String → TEXT, Long/Int/Boolean → INTEGER), and
         * both foreign keys carry the CASCADE the entities declare — that is
         * what makes deleting a chat (or a plugin) clean up its bindings
         * without a hand-written delete path in front of them.
         *
         * `internal` for the same reason as [MIGRATION_10_11]: only
         * PluginMigrationTest (the e2e one) executes it, since a fresh install
         * creates v17 directly.
         */
        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plugins` (" +
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
                    "CREATE TABLE IF NOT EXISTS `chat_plugins` (" +
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
                    "CREATE INDEX IF NOT EXISTS `index_chat_plugins_session_id` " +
                        "ON `chat_plugins` (`session_id`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_chat_plugins_session_id_plugin_id` " +
                        "ON `chat_plugins` (`session_id`, `plugin_id`)"
                )
            }
        }

        /**
         * v17 → v18: the ZAgents module columns. A row installed from a
         * module.json is still a plugins row — one table, one installer, one
         * runtime — so the format is a column, not a second table: `format`
         * (defaulting to `plugin`, which is what every existing row already
         * is), the module's own name/version/entry spellings, its timeout,
         * its `inputs` form, and the staged-activation flag; the per-chat
         * binding gains the chat's answers to that form.
         *
         * Purely additive ALTER TABLE statements — no existing row is
         * touched, and every new column reads as "none/never" for one, so
         * the migration is lossless. The NOT NULL columns carry the
         * defaults the entities declare, which is what Room's schema
         * validation compares after a migration.
         *
         * `internal` for the same reason as [MIGRATION_10_11]: only
         * PluginMigrationTest (the e2e one) executes it, since a fresh
         * install creates v18 directly.
         */
        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `plugins` ADD COLUMN `format` TEXT NOT NULL DEFAULT 'plugin'"
                )
                db.execSQL("ALTER TABLE `plugins` ADD COLUMN `zagents_name` TEXT")
                db.execSQL("ALTER TABLE `plugins` ADD COLUMN `zagents_version` TEXT")
                db.execSQL("ALTER TABLE `plugins` ADD COLUMN `zagents_entry` TEXT")
                db.execSQL("ALTER TABLE `plugins` ADD COLUMN `timeout_seconds` INTEGER")
                db.execSQL("ALTER TABLE `plugins` ADD COLUMN `inputs_json` TEXT")
                db.execSQL(
                    "ALTER TABLE `plugins` ADD COLUMN `prompt_staged` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE `chat_plugins` ADD COLUMN `inputs_values_json` TEXT")
            }
        }

        /**
         * v18 → v19: token-usage columns on agent messages and the agent-skill
         * table. Two ALTERs on `agent_messages` — nullable columns, so no
         * default is needed and existing rows read back as null ("unknown") —
         * plus one additive CREATE TABLE + UNIQUE INDEX for `agent_skills`, the
         * metadata row of an installed SKILL.md package. The column set mirrors
         * [AgentSkillEntity] exactly (snake_case, NOT NULL on non-null Kotlin
         * types, INTEGER affinity for Long/Boolean), which is what the
         * migration-test read-back compares.
         *
         * `internal` for the same reason as [MIGRATION_16_17]: a fresh install
         * creates v19 directly, so only AgentSkillMigrationTest executes this.
         */
        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `prompt_tokens` INTEGER")
                db.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `completion_tokens` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_skills` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`skill_id` TEXT NOT NULL, " +
                        "`display_name` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, " +
                        "`source_repo` TEXT NOT NULL, " +
                        "`sub_path` TEXT NOT NULL, " +
                        "`version` TEXT NOT NULL, " +
                        "`commit_ref` TEXT NOT NULL, " +
                        "`skill_md_sha256` TEXT NOT NULL, " +
                        "`npm_deps_json` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`installed_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_skills_skill_id` " +
                        "ON `agent_skills` (`skill_id`)"
                )
            }
        }

        /**
         * v19 -> v20: the scheduled-run plan line. A run's only transcript is
         * its task row, and the runner now overwrites `plan_json` on every
         * PlanUpdated event so the task list shows the step list flipping
         * status in real time — the same list shape a chat carries on
         * `agent_sessions.plan_json`, on the row a task actually has.
         */
        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `ai_tasks` ADD COLUMN `plan_json` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Every migration, oldest first — the ONE list. [build] applies it and
         * the migration tests apply it too, so a version bump cannot leave a
         * test registering a subset that stops short of the current version.
         */
        internal val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
            MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
            MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
            MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
            MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20
        )

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .enableMultiInstanceInvalidation()
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}
