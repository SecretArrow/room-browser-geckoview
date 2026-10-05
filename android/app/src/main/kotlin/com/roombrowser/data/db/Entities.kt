package com.roombrowser.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Profile identity + settings (settings serialized as JSON). */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String, // immutable UUID
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "icon") val icon: String,
    @ColumnInfo(name = "color_argb") val colorArgb: Long,
    @ColumnInfo(name = "is_locked") val isLocked: Boolean,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_active_at") val lastActiveAt: Long,
    @ColumnInfo(name = "settings_json") val settingsJson: String,
    /** Full per-profile theme snapshot (RoomThemeSpec JSON); "" = default. */
    @ColumnInfo(name = "theme_json", defaultValue = "") val themeJson: String = ""
)

@Entity(
    tableName = "tabs",
    indices = [Index("profile_id"), Index("profile_id", "position")]
)
data class TabEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "is_private") val isPrivate: Boolean,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "group_name") val groupName: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_viewed_at") val lastViewedAt: Long,
    @ColumnInfo(name = "closed_at") val closedAt: Long? = null // reopen-closed-tab support
)

@Entity(
    tableName = "bookmarks",
    indices = [Index("profile_id"), Index("profile_id", "folder")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "folder") val folder: String? = null,
    @ColumnInfo(name = "position") val position: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "history",
    indices = [Index("profile_id"), Index("profile_id", "visited_at")]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "visited_at") val visitedAt: Long
)

@Entity(
    tableName = "downloads",
    indices = [Index("profile_id"), Index("status")]
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "destination") val destination: String, // content uri or file path
    @ColumnInfo(name = "total_bytes") val totalBytes: Long,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long,
    @ColumnInfo(name = "status") val status: String, // DownloadStatus.name
    @ColumnInfo(name = "error") val error: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    /**
     * The User-Agent the transfer must present.
     *
     * Stored rather than held in memory because an interrupted download is
     * re-queued when the engine is rebuilt (a profile switch, or the app coming
     * back), and the profile it belongs to presents a device — a resume that
     * went out under a different UA than the original request would be two
     * identities fetching one file.
     */
    @ColumnInfo(name = "user_agent") val userAgent: String = ""
)

/** Per-profile, per-site permission decisions. */
@Entity(
    tableName = "site_permissions",
    primaryKeys = ["profile_id", "host", "permission"],
    indices = [Index("profile_id")]
)
data class SitePermissionEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "permission") val permission: String, // PermissionKind name
    @ColumnInfo(name = "decision") val decision: String // PermissionDecision name
)

/** Per-profile, per-site content settings. Null value = inherit profile setting. */
@Entity(
    tableName = "site_settings",
    primaryKeys = ["profile_id", "host"],
    indices = [Index("profile_id")]
)
data class SiteSettingEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "shields_disabled") val shieldsDisabled: Boolean? = null,
    @ColumnInfo(name = "js_enabled") val jsEnabled: Boolean? = null,
    @ColumnInfo(name = "cookies_blocked") val cookiesBlocked: Boolean? = null,
    @ColumnInfo(name = "desktop_mode") val desktopMode: Boolean? = null,
    @ColumnInfo(name = "autoplay_blocked") val autoplayBlocked: Boolean? = null,
    @ColumnInfo(name = "popup_blocked") val popupBlocked: Boolean? = null
)

/** profile_network_history (spec section 6 / 74). */
@Entity(
    tableName = "ip_history",
    indices = [Index("profile_id"), Index("ip"), Index("last_seen_at")]
)
data class IpHistoryEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "ip") val ip: String,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long
)

/** Real blocking events power the privacy dashboard (no fake statistics). */
@Entity(
    tableName = "block_events",
    indices = [Index("profile_id", "ts"), Index("profile_id", "host")]
)
data class BlockEventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "host") val host: String, // host only — never full URLs
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "ts") val ts: Long
)

/** Cross-process app state KV (active profile, global settings JSON, ...). */
@Entity(tableName = "app_state")
data class AppStateEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String
)

// =========================================================================
// PER-PROFILE THEME SYSTEM — schema v4
// =========================================================================

/**
 * A user-saved custom theme in the local gallery ("My themes"). Themes
 * actually applied to profiles are full snapshots on the profile row
 * (profiles.theme_json) — the gallery is only a picker source, so editing
 * one profile never mutates another profile's look.
 */
@Entity(tableName = "themes")
data class CustomThemeEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "spec_json") val specJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

// =========================================================================
// AI AGENT (autonomous browsing assistant) — schema v2, chats per-tab since v10
// =========================================================================

/**
 * A user-configured AI agent provider. Five protocols are supported:
 *  - [PROTOCOL_OPENAI] — any OpenAI-compatible chat/completions API
 *    (Z.ai, OpenAI, OpenRouter, Groq, DeepSeek, Ollama, LM Studio, custom...)
 *  - [PROTOCOL_OPENCODE] — an `opencode serve` server (session-based REST
 *    API on its own machine, bridged by OpenCodeAgentGateway)
 *  - [PROTOCOL_OLLAMA] — a native Ollama server (/api/chat + /api/tags;
 *    managed by the Local AI screen, bridged by OllamaAgentGateway)
 *  - [PROTOCOL_LOCAL] — the embedded on-device llama.cpp engine (no server,
 *    no network; models are .gguf files managed in Local AI)
 *  - [PROTOCOL_ANTHROPIC] — the Anthropic Messages API shape (POST /messages,
 *    x-api-key + anthropic-version), spoken by aggregators such as AgentRouter
 *    through the @ai-sdk/anthropic SDK and bridged by AnthropicAgentGateway,
 *    which probes once and falls back to the OpenAI shape when the endpoint is
 *    not Anthropic-shaped
 *
 * The API key is stored ENCRYPTED with an AndroidKeyStore AES-GCM key.
 *
 * `protocol` is a plain String column (defaultValue "OPENAI"), so adding a new
 * protocol value needs NO Room migration: existing rows keep their value and
 * only new/edited Anthropic providers ever store "ANTHROPIC".
 */
@Entity(tableName = "agent_providers")
data class AgentProviderEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "api_key_enc") val apiKeyEnc: String, // "" = no key (local servers)
    @ColumnInfo(name = "default_model") val defaultModel: String,
    @ColumnInfo(name = "protocol", defaultValue = "OPENAI") val protocol: String = PROTOCOL_OPENAI,
    /**
     * How this provider is asked to call tools — a [com.roombrowser.domain.agent.ToolMode]
     * name, "AUTO" when unset. Stored as a name so a new mode needs no
     * migration; legacy rows read as AUTO (see `ToolMode.fromStored`).
     */
    @ColumnInfo(name = "tool_mode", defaultValue = "AUTO") val toolMode: String = TOOL_MODE_DEFAULT,
    @ColumnInfo(name = "created_at") val createdAt: Long
) {
    companion object {
        const val PROTOCOL_OPENAI = "OPENAI"
        const val PROTOCOL_OPENCODE = "OPENCODE"
        const val PROTOCOL_OLLAMA = "OLLAMA"
        const val PROTOCOL_LOCAL = "LOCAL"
        const val PROTOCOL_ANTHROPIC = "ANTHROPIC"

        /** Mirrors `ToolMode.DEFAULT.name` without a domain import here. */
        const val TOOL_MODE_DEFAULT = "AUTO"
    }
}

/**
 * One agent chat session, scoped to a profile AND — normally — to one tab.
 *
 * `tab_id` is the tab whose conversation this is. A tab resolves the chat it
 * shows through it (AgentRepository.sessionForTab), which is what makes
 * switching tabs switch conversations rather than continue the one before.
 *
 * `""` means the chat belongs to no tab: every chat written before per-tab
 * chats existed reads as "", and so does one the user detached by starting a
 * new chat in that tab. Those are exactly the rows the history list is for.
 *
 * The id is kept even when its tab is closed, so reopening the tab (which
 * reuses the id) brings the conversation back with it.
 *
 * Deliberately NOT unique on (profile_id, tab_id): all of a profile's
 * unbound chats share the "" value, so a unique index would leave a profile
 * room for precisely one history entry.
 */
@Entity(
    tableName = "agent_sessions",
    indices = [Index("profile_id"), Index("profile_id", "tab_id")]
)
data class AgentSessionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: String,
    /** The owning tab's id, or "" when this chat is not bound to a tab. */
    @ColumnInfo(name = "tab_id", defaultValue = "") val tabId: String = "",
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "model") val model: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/**
 * One message row of an agent session. Roles: "user", "assistant", "tool".
 * Tool rows carry the tool name/args/result for display in the chat UI;
 * on session continuation only user/assistant rows are replayed to the
 * provider (tool-call linkage is only valid within a single turn).
 */
@Entity(
    tableName = "agent_messages",
    indices = [Index("session_id")]
)
data class AgentMessageEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "tool_name") val toolName: String? = null,
    @ColumnInfo(name = "tool_args") val toolArgs: String? = null,
    @ColumnInfo(name = "tool_result") val toolResult: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

// =========================================================================
// PASSWORD MANAGER (per-profile credential vault) — schema v7
// =========================================================================

/**
 * One saved login of a profile's password manager.
 *
 * The password is stored ONLY as ciphertext: password_enc is the
 * AndroidKeyStore AES-256-GCM blob produced by the profile's vault key
 * (alias roomvault-<safeSuffix>, see com.roombrowser.security.VaultCrypto).
 * A plaintext password never reaches disk in any form — a copied database
 * file yields no secrets, and deleting the profile deletes its key, which
 * makes any surviving blob permanently undecryptable.
 */
@Entity(
    tableName = "credentials",
    indices = [Index("profile_id"), Index("profile_id", "domain")]
)
data class CredentialEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String, // UUID; fresh on import
    @ColumnInfo(name = "profile_id") val profileId: String,
    /** Canonical host: lowercase, no scheme/path, no trailing dot. */
    @ColumnInfo(name = "domain") val domain: String,
    @ColumnInfo(name = "username") val username: String,
    /** base64(iv||ciphertext+tag) under the profile's vault key. */
    @ColumnInfo(name = "password_enc") val passwordEnc: String,
    /** Optional user label; null = no label. */
    @ColumnInfo(name = "title") val title: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

// =========================================================================
// MULTI-CHAIN CRYPTO WALLET — schema v8
// =========================================================================

/**
 * The profile's wallet (one per profile in v1 — enforced by the UNIQUE
 * index on profile_id). The BIP39 mnemonic is stored ONLY as ciphertext:
 * mnemonic_enc is the AndroidKeyStore AES-256-GCM blob produced by the
 * profile's wallet key (alias roomwallet-&lt;safeSuffix&gt;, see
 * com.roombrowser.security.WalletKeyCrypto). A NULL mnemonic means a
 * wallet built from imported accounts only — no seed phrase exists to
 * reveal. A plaintext mnemonic never reaches disk in any form.
 */
@Entity(
    tableName = "wallets",
    indices = [Index(value = ["profile_id"], unique = true)]
)
data class WalletEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String, // UUID
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "label") val label: String,
    /** base64(iv||ciphertext+tag) under the profile's wallet key; null = no mnemonic. */
    @ColumnInfo(name = "mnemonic_enc") val mnemonicEnc: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * One derived or imported account on one chain. No plaintext key material:
 * private_key_enc holds the imported key's vault blob and is NULL for
 * mnemonic-derived accounts (their key is re-derived from the wallet's
 * encrypted mnemonic on use, never stored). The UNIQUE index keeps one
 * address per (wallet, chain): re-adding an existing account fails loudly
 * at the schema level.
 */
@Entity(
    tableName = "wallet_accounts",
    indices = [
        Index("wallet_id"),
        Index(value = ["wallet_id", "chain_type", "address"], unique = true)
    ]
)
data class WalletAccountEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String, // UUID
    @ColumnInfo(name = "wallet_id") val walletId: String,
    /** ChainType name ("EVM", "SOLANA", ...). */
    @ColumnInfo(name = "chain_type") val chainType: String,
    @ColumnInfo(name = "address") val address: String,
    @ColumnInfo(name = "label") val label: String,
    /** BIP44 path for derived accounts; "" for imports. */
    @ColumnInfo(name = "path") val path: String,
    /** WalletAccountRecord.Source name: "DERIVED" or "IMPORTED". */
    @ColumnInfo(name = "source") val source: String,
    /** base64(iv||ciphertext+tag) for imported keys; null for derived. */
    @ColumnInfo(name = "private_key_enc") val privateKeyEnc: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * A network row of a profile: the full [com.roombrowser.domain.wallet.model.NetworkConfig]
 * serialized as JSON in `payload` (schema stays stable when the config
 * gains fields), plus per-profile UI state. `id` IS the NetworkConfig id
 * ("EVM:137", "SOLANA:mainnet-beta", ...). The COMPOSITE primary key
 * (profile_id, id) makes seeding and upserts idempotent PER PROFILE: a
 * table-wide `id` primary key (v8's mistake, found by the wallet isolation
 * e2e) silently gave ONE profile exclusive ownership of every network row —
 * the second profile's seeding collided on the PK and got nothing.
 */
@Entity(
    tableName = "wallet_networks",
    primaryKeys = ["profile_id", "id"]
)
data class WalletNetworkEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "is_custom") val isCustom: Boolean,
    /** NetworkConfig JSON (kotlinx.serialization). */
    @ColumnInfo(name = "payload") val payload: String
)

/**
 * A granted dApp permission. The host is the WebView-VERIFIED host (never
 * the page's claimed origin), and the UNIQUE index keeps one row per
 * (profile, host, chain, account) so a re-grant refreshes the methods
 * list instead of stacking duplicates.
 */
@Entity(
    tableName = "dapp_permissions",
    indices = [
        Index("profile_id"),
        Index(
            value = ["profile_id", "host", "chain_type", "account_address"],
            unique = true
        )
    ]
)
data class DappPermissionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String, // UUID; stable across re-grants
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "host") val host: String,
    /** ChainType name. */
    @ColumnInfo(name = "chain_type") val chainType: String,
    @ColumnInfo(name = "account_address") val accountAddress: String,
    /** JSON array of permitted method names. */
    @ColumnInfo(name = "methods_json") val methodsJson: String,
    @ColumnInfo(name = "granted_at") val grantedAt: Long
)

/**
 * Locally-recorded wallet activity — what THIS wallet sent or signed, not
 * chain indexing. Rows are append-only history for the activity list.
 */
@Entity(
    tableName = "wallet_activities",
    indices = [Index("profile_id")]
)
data class WalletActivityEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    /** ChainType name. */
    @ColumnInfo(name = "chain_type") val chainType: String,
    @ColumnInfo(name = "network_name") val networkName: String,
    /** WalletActivityRecord.Kind name (SEND, SIGN_MESSAGE, ...). */
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "account_address") val accountAddress: String,
    @ColumnInfo(name = "to_address") val toAddress: String?,
    /** Human-readable amount, e.g. "0.1 ETH" or "message". */
    @ColumnInfo(name = "display_amount") val displayAmount: String,
    @ColumnInfo(name = "hash") val hash: String?,
    @ColumnInfo(name = "explorer_url") val explorerUrl: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * The profile's explicitly-chosen active network per chain family
 * (the "current network" selector). Absent row = fall back to the first
 * ENABLED network of the chain (see WalletRepository.activeNetwork).
 */
@Entity(
    tableName = "wallet_active_networks",
    primaryKeys = ["profile_id", "chain_type"]
)
data class WalletActiveNetworkEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    /** ChainType name. */
    @ColumnInfo(name = "chain_type") val chainType: String,
    /** The chosen wallet_networks.id for that chain. */
    @ColumnInfo(name = "network_id") val networkId: String
)

// =========================================================================
// SCHEDULED AI TASKS — schema v11
// =========================================================================

/**
 * One user-defined AI task on a schedule: a prompt the agent runs against a
 * profile at the times described by [scheduleJson].
 *
 * [scheduleJson] is a TaskSchedule serialized the same way profiles store
 * `settings_json` — a TEXT column the domain type owns, so adding a schedule
 * field never needs a migration. [permissionsJson] is an AiTaskPermissions
 * the same way.
 *
 * [lastRunAtMs] is the schedule occurrence the task has been ACCOUNTED for,
 * whether it ran or was explicitly deferred — not "last time it succeeded".
 * That is what makes catch-up fire exactly once: a delivery that was missed
 * (Doze) is one occurrence, and once stamped it is never owed again. It is
 * null until the first delivery, so a task whose time already passed when it
 * was created waits for the next occurrence instead of firing at once.
 *
 * [lastRunStatus] is an AiTaskRunStatus name ("", "COMPLETED", "DEFERRED",
 * "FAILED"); [lastResultSummary] carries the human-readable detail (a
 * deferral reason included). Both are written together with [lastRunAtMs].
 */
@Entity(
    tableName = "ai_tasks",
    indices = [Index("profile_id")]
)
data class AiTaskEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    /** The instruction the agent is sent when the task fires. */
    @ColumnInfo(name = "prompt") val prompt: String,
    /** The profile the task runs against. */
    @ColumnInfo(name = "profile_id") val profileId: String,
    /** TaskSchedule JSON. */
    @ColumnInfo(name = "schedule_json") val scheduleJson: String,
    /** AiTaskPermissions JSON. */
    @ColumnInfo(name = "permissions_json") val permissionsJson: String,
    /** AiTaskRunConfig JSON: which provider, which model, which surface. Empty
     *  on rows written before the choice existed — those read as AUTO. */
    @ColumnInfo(name = "run_config_json", defaultValue = "") val runConfigJson: String = "",
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "last_run_at_ms") val lastRunAtMs: Long? = null,
    @ColumnInfo(name = "last_run_status") val lastRunStatus: String = "",
    @ColumnInfo(name = "last_result_summary") val lastResultSummary: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
