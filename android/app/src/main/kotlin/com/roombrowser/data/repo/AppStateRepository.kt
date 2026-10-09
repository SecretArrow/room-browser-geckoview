package com.roombrowser.data.repo

import com.roombrowser.data.db.AppStateDao
import com.roombrowser.data.db.AppStateEntity
import com.roombrowser.domain.agent.LocalAiTuning
import com.roombrowser.domain.agent.RetryPolicy
import com.roombrowser.domain.model.BrowserGlobalSettings
import com.roombrowser.domain.proxy.ProxySweepState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Cross-process app state keys (Room-backed, multi-instance-safe). */
object AppStateKeys {
    const val GLOBAL_SETTINGS = "global_settings"
    const val ACTIVE_PROFILE_ID = "active_profile_id"
    const val FIRST_RUN_DONE = "first_run_done"
    const val WARNED_NETWORKS = "warned_networks"

    /**
     * LEGACY app-global "don't warn again for this IP" set, from before the
     * suppression was scoped per profile. It is NO LONGER written: see
     * [SUPPRESSED_IPS_PREFIX] and [AppStateRepository.suppressedIps]. One
     * last read of this key seeds the active profile's scoped key, then the
     * row is deleted, so a pre-upgrade suppression is honoured exactly once
     * instead of silencing every profile forever.
     */
    const val SUPPRESSED_IPS = "suppressed_ips"

    /**
     * Per-profile suppression key prefix: `suppressed_ips:<profileId>`.
     * Suppressions are privacy-isolated like everything else — one profile
     * must never silence another profile's network warning.
     */
    const val SUPPRESSED_IPS_PREFIX = "suppressed_ips:"

    const val IP_CACHE = "network_ip_cache"
    const val EXTERNAL_URL = "external_url"
    const val SESSION_ID = "browser_session_id"
    const val AGENT_SETTINGS = "agent_settings"
    const val LOCAL_AI_SETTINGS = "local_ai_settings"

    /** The last free-proxy sweep's verdict: direct IP, cursor and per-endpoint health. */
    const val PROXY_SWEEP = "proxy_sweep"

    /** Pending profile-network decision (NetworkWarningActivity gate). */
    const val PENDING_NET_DECISION = "net_decision_pending"

    /** Profile id still owed the post-create "import your passwords?" offer. */
    const val PASSWORD_IMPORT_OFFER = "password_import_offer"

    /** Per-profile lock record prefix: `profile_lock:<profileId>`. */
    const val PROFILE_LOCK_PREFIX = "profile_lock:"

    /**
     * The pre-rename key, `wallet_lock:<profileId>`. A released webview build
     * (v1.0.127, tag on 51bee47) still wrote it, so a PIN set there must be
     * adopted rather than dropped — see [profileLockRecord].
     */
    const val LEGACY_PROFILE_LOCK_PREFIX = "wallet_lock:"
}

/** AI agent behavior settings (app-global, stored as JSON in app_state). */
@Serializable
data class AgentSettings(
    val enabled: Boolean = true,
    /**
     * Visibility of the floating "AI Agent" button on the browser surface.
     * HIDDEN by default — the agent stays reachable from the page-actions
     * menu, and the button (or live status pill) only appears when the
     * user opts in here (or while a task is running).
     */
    val showAgentButton: Boolean = false,
    val defaultProviderId: Long? = null,
    val defaultModel: String? = null,
    /**
     * The chat asks the provider which of its models actually answers, instead
     * of sending [defaultModel]. [defaultModel] is kept as the last explicit
     * pick, so turning AUTO off restores it rather than losing it.
     */
    val defaultModelAuto: Boolean = false,
    /**
     * The chat runs on a hidden page instead of the tab the user is looking
     * at. Off by default: the visible tab is what makes "click that button"
     * mean anything to the person giving the order.
     */
    val chatHeadless: Boolean = false,
    /**
     * Room Agent opens as its own screen instead of the panel over the page.
     * Off by default: the panel keeps the page visible while the agent drives
     * it, which is the whole point of watching an agent work.
     *
     * On, the agent opens in a full-screen activity. It is the SAME browser
     * and the same conversation — the activity shares the browser process's
     * live state rather than starting a second one — so this changes where the
     * chat is drawn and nothing about what the turn may do.
     */
    val chatInOwnScreen: Boolean = false,
    /**
     * The chat is in PLAN mode: it only reads and opens pages, and nothing it
     * runs is clicked, typed, submitted, posted or signed. On by default — the
     * owner wants an unattended agent that cannot submit anything anywhere —
     * so acting on the page is opted into, from the mode control in the chat
     * header.
     *
     * This is where [AgentMode.PLAN] is stored, and it outranks [yolo]: a
     * read-only turn refuses an acting tool before any prompt could be raised,
     * so nothing acts while it is on.
     */
    val chatOnly: Boolean = true,
    val temperature: Double = 0.2,
    val maxSteps: Int = 25,
    /** On by default, so an action is never taken without the user seeing it. */
    val confirmActions: Boolean = true,
    /**
     * Let the agent read the DIGITS of a code generated from a stored
     * authenticator seed. Off by default, and the switch matters because a
     * digit the agent reads stops being private to this device: it is sent to
     * the configured provider (a remote one unless the on-device model or a
     * local Ollama endpoint is in use), written in plaintext into the
     * profile's `agent_messages` table as part of the tool result, and
     * included in any chat export.
     *
     * Off does not disable the tool: `copy` and `fill` still place the current
     * code where it is needed without the model ever holding it.
     */
    val otpDigitsToAgent: Boolean = false,
    /**
     * Let a SCHEDULED AI task use the profile's notes and authenticator codes.
     * Off by default, and the cost is the one a scheduled run always carries:
     * nobody is watching the outcome. With it on, a page that talks the task
     * into it can pull a live code and read the notes. Deleting a note is
     * never allowed to a run, whatever this says.
     */
    val taskProfileTools: Boolean = false,
    val includePageContext: Boolean = true,
    /**
     * The user's standing context for the AI Agent — the text that is put in
     * front of EVERY request while [useDefaultContext] is on, so the agent
     * keeps answering inside the same frame ("I am working on a Solidity
     * audit", "answer in Indonesian", "this profile is for the staging
     * cluster") without it being retyped or re-attached each turn.
     *
     * It is a USER message, not part of the system prompt. That matters for
     * two reasons: it is visible in the conversation history the provider
     * receives (so a later turn cannot silently lose it), and it sits
     * strictly below the system prompt's authority — a page or an attachment
     * can never promote text into the instruction slot reserved for the
     * app's own prompt.
     *
     * Blank means "nothing saved yet"; [useDefaultContext] is the switch, so
     * an empty context is inert rather than an error.
     */
    val defaultContext: String = "",
    /** Whether [defaultContext] is sent. Off until the user turns it on. */
    val useDefaultContext: Boolean = false,
    val systemPromptOverride: String? = null,
    /**
     * Where the user last dragged the floating AI Agent pill, as a FRACTION
     * (0..1) of the draggable range on each axis — not pixels.
     *
     * Fractions, because the pixel answer is wrong on the next device state:
     * a position saved in portrait would put the pill off-screen in
     * landscape, and a position saved on a 1080p phone would sit in a corner
     * on a tablet. A fraction of the range survives rotation, multi-window
     * resize, split screen and a different phone with no migration at all.
     *
     * Null means "never dragged" — the pill keeps its designed home at the
     * bottom-end corner, which is also the position that cannot collide with
     * the browser's own bottom chrome.
     */
    val agentButtonXFrac: Float? = null,
    val agentButtonYFrac: Float? = null,
    /**
     * The local decision gate: ask an Ollama decision model (protocol
     * `OLLAMA`, model `nimble` or another `/v1/systemone` model) what should
     * happen with each action before the blanket [confirmActions] rule is
     * applied. Off by default.
     *
     * Only meaningful together with a reachable local Ollama 0.35+ server:
     * the endpoint refuses cloud models, so this can never be served by a
     * hosted provider.
     */
    val decisionGate: Boolean = false,
    /** Provider hosting the decision model — an `OLLAMA`-protocol provider. */
    val decisionProviderId: Long? = null,
    /** Decision model tag, e.g. `nimble`, `tev1`, `tev1:0.8b`. */
    val decisionModel: String = "",
    /**
     * The user's own policy text, sent as the question's instructions. Blank
     * means the built-in policy in
     * [com.roombrowser.domain.agent.ActionGate.DEFAULT_POLICY].
     */
    val decisionPolicy: String = "",
    /**
     * YOLO — every state-changing action runs without asking.
     *
     * Set from the approval prompt's third answer ("Always allow"), not just
     * from the settings switch, because that is where the user is actually
     * being interrupted and decides they would rather not be. It overrides
     * BOTH rules at once: the local decision gate is not consulted and the
     * Confirm actions prompt is not shown. Off by default, and the only
     * setting in this app that removes every check at once — so it is
     * surfaced in the chat when it turns on and in the panel on every turn
     * while it is on.
     */
    val yolo: Boolean = false,
    /**
     * Send a provider request again when it fails.
     *
     * Off by default: a retry spends the user's quota at a third party, so
     * it is opted into rather than inherited. The four fields below are one
     * feature — the switch is the gate and the other three only shape what
     * it does.
     */
    val retryOnError: Boolean = false,
    /** Total attempts, counting the first. 1 means "no retry". */
    val retryMaxAttempts: Int = RetryPolicy.DEFAULT_ATTEMPTS,
    /**
     * How long to wait before each retry, in SECONDS.
     *
     * Seconds, not milliseconds, because seconds are what the settings screen
     * asks in and what a person decides in: "wait 6 seconds" is a choice
     * someone makes, "6000" is a number they have to translate first. The
     * transport still speaks milliseconds, and [retryPolicy] converts once,
     * here, rather than at every reader.
     */
    val retryDelaySeconds: Int = RetryPolicy.DEFAULT_DELAY_SECONDS,
    /**
     * Which HTTP statuses are worth another try. Sorted on write so the
     * stored JSON is stable — an unordered set would re-encode differently
     * on every save and make the row look changed when it is not.
     */
    val retryStatusCodes: List<Int> = RetryPolicy.DEFAULT_STATUS_CODES.sorted(),
    /**
     * Also retry failures that carried no HTTP status at all — refused
     * connection, DNS, TLS, or a stream that died before the first token.
     * This is the case that actually happens on a phone moving between
     * networks, so it is on whenever the feature is.
     */
    val retryConnectionFailures: Boolean = true
) {
    /** This setting as the transport-layer rule the gateways consume. */
    fun retryPolicy(): RetryPolicy = RetryPolicy(
        enabled = retryOnError,
        maxAttempts = retryMaxAttempts,
        statusCodes = retryStatusCodes.toSet(),
        retryConnectionFailures = retryConnectionFailures,
        delayMs = retryDelaySeconds * 1000L
    )

    /** What every turn may do, as the chat header shows it. */
    val chatMode: AgentMode get() = AgentMode.of(this)
}

/**
 * What an agent turn may do, as the three choices the chat header offers.
 *
 * Derived from the flags that are actually persisted — [AgentSettings.chatOnly]
 * and [AgentSettings.yolo] — rather than stored beside them, so the mode picked
 * in the header, a YOLO answered at an approval prompt and the switches in AI
 * Agent settings cannot end up disagreeing about what the agent may do.
 */
enum class AgentMode(val title: String, val blurb: String) {
    /** Acts on the page, asking before each action. */
    ASK("Ask", "Act on the page, asking before every action"),
    /** Reads, and answers with the steps instead of taking them. */
    PLAN("Plan", "Read pages and answer with the steps, changing nothing"),
    /** Acts without asking. */
    YOLO("YOLO", "Act on the page without being asked");

    companion object {
        /**
         * Read-only wins over everything else: while
         * [AgentSettings.chatOnly] is on, a turn refuses an acting tool before
         * the approval prompt could be raised, so reporting [YOLO] there would
         * name a mode that cannot do what it says.
         */
        fun of(settings: AgentSettings): AgentMode = when {
            settings.chatOnly -> PLAN
            settings.yolo -> YOLO
            else -> ASK
        }
    }
}

@Serializable
data class IpCache(val ip: String?, val checkedAt: Long)

/**
 * The conflict payload behind a PENDING profile-network decision. Stored
 * under [AppStateKeys.PENDING_NET_DECISION] while the full-screen warning
 * stands, so process death / activity recreation can never bypass the
 * decision: BrowserActivity reads it on cold start and re-launches
 * NetworkWarningActivity until the user decides.
 */
@Serializable
data class PendingNetDecision(
    val profileId: String,
    val ip: String,
    val previousProfileName: String,
    val lastSeenAt: Long
)

/**
 * Global settings + app state repository backed by the Room KV table so it
 * can be read from BOTH the main process and the ':browser' process.
 */
class AppStateRepository(private val dao: AppStateDao) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Serialises [updateAgentSettings] cycles within this process.
     *
     * The agent settings are ONE json blob under one key, so there are no
     * per-field writes for SQLite to interleave safely: a writer that starts
     * from a copy read before another writer committed will overwrite that
     * writer's field when it saves. Holding this across the read AND the write
     * is what makes the cycle a merge instead of a race.
     */
    private val agentSettingsWrites = Mutex()

    val globalSettings: Flow<BrowserGlobalSettings> =
        dao.observe(AppStateKeys.GLOBAL_SETTINGS).map { raw ->
            raw?.let { runCatching { json.decodeFromString(BrowserGlobalSettings.serializer(), it) }.getOrNull() }
                ?: BrowserGlobalSettings()
        }

    suspend fun globalSettingsSnapshot(): BrowserGlobalSettings =
        dao.get(AppStateKeys.GLOBAL_SETTINGS)?.let {
            runCatching { json.decodeFromString(BrowserGlobalSettings.serializer(), it) }.getOrNull()
        } ?: BrowserGlobalSettings()

    suspend fun saveGlobalSettings(settings: BrowserGlobalSettings) {
        dao.put(AppStateEntity(AppStateKeys.GLOBAL_SETTINGS, json.encodeToString(BrowserGlobalSettings.serializer(), settings)))
    }

    val activeProfileId: Flow<String?> = dao.observe(AppStateKeys.ACTIVE_PROFILE_ID)

    suspend fun activeProfileIdSnapshot(): String? = dao.get(AppStateKeys.ACTIVE_PROFILE_ID)

    suspend fun setActiveProfile(profileId: String?) {
        if (profileId == null) dao.remove(AppStateKeys.ACTIVE_PROFILE_ID)
        else dao.put(AppStateEntity(AppStateKeys.ACTIVE_PROFILE_ID, profileId))
    }

    suspend fun firstRunDone(): Boolean = dao.get(AppStateKeys.FIRST_RUN_DONE) == "true"

    suspend fun setFirstRunDone() {
        dao.put(AppStateEntity(AppStateKeys.FIRST_RUN_DONE, "true"))
    }

    suspend fun warnedNetworks(): Set<String> =
        deserializeSet(dao.get(AppStateKeys.WARNED_NETWORKS))

    suspend fun addWarnedNetwork(ip: String) {
        dao.put(AppStateEntity(AppStateKeys.WARNED_NETWORKS, serializeSet(warnedNetworks() + ip)))
    }

    /**
     * IPs [profileId] never warns about again, stored under
     * `suppressed_ips:<profileId>` ([AppStateKeys.SUPPRESSED_IPS_PREFIX]).
     *
     * PROFILE-SCOPED: profiles are privacy-isolated everywhere else in this
     * app, so "don't warn again for this IP" must not cut across them — a
     * suppression clicked in profile A used to silence profile B.
     *
     * MIGRATION (backward compatibility): an installation that predates this
     * scoping holds ONE app-global set under the legacy key
     * [AppStateKeys.SUPPRESSED_IPS]. On the first read for a profile whose
     * scoped row does not exist yet:
     *  - if the legacy row is still there and this profile is the ACTIVE one
     *    (the profile the user was actually on when they suppressed), the
     *    legacy set is seeded into the scoped key and the legacy row is
     *    DELETED — so an existing suppression is still honoured and the user
     *    does not start being warned again, while it is inherited exactly once
     *    instead of leaking into every profile forever;
     *  - any other profile simply reads an empty set (nothing is written, so
     *    the read path stays write-free for a profile that never suppressed);
     *  - with no active profile recorded yet, the profile being read takes the
     *    legacy set: dropping a user's suppression on the floor would be worse
     *    than attributing it to the profile that is being opened.
     *
     * Reading a profile's own row deliberately never consults the legacy key
     * again: once a scoped row exists it is the only source of truth.
     */
    suspend fun suppressedIps(profileId: String): Set<String> {
        dao.get(suppressedIpsKey(profileId))?.let { return deserializeSet(it) }
        val legacy = deserializeSet(dao.get(AppStateKeys.SUPPRESSED_IPS))
        if (legacy.isEmpty()) return emptySet()
        val activeProfileId = dao.get(AppStateKeys.ACTIVE_PROFILE_ID)
        if (activeProfileId != null && activeProfileId != profileId) return emptySet()
        // This profile is the legacy set's heir: adopt it, then retire the
        // app-global row so no other profile can ever inherit it.
        dao.put(AppStateEntity(suppressedIpsKey(profileId), serializeSet(legacy)))
        dao.remove(AppStateKeys.SUPPRESSED_IPS)
        return legacy
    }

    /** Persists "don't warn again for this IP" for [profileId] only. */
    suspend fun suppressIp(profileId: String, ip: String) {
        dao.put(
            AppStateEntity(
                suppressedIpsKey(profileId),
                serializeSet(suppressedIps(profileId) + ip)
            )
        )
    }

    private fun suppressedIpsKey(profileId: String): String =
        AppStateKeys.SUPPRESSED_IPS_PREFIX + profileId

    suspend fun ipCache(): IpCache? = dao.get(AppStateKeys.IP_CACHE)?.let {
        runCatching { json.decodeFromString(IpCache.serializer(), it) }.getOrNull()
    }

    suspend fun setIpCache(cache: IpCache) {
        dao.put(AppStateEntity(AppStateKeys.IP_CACHE, json.encodeToString(IpCache.serializer(), cache)))
    }

    suspend fun proxySweep(): ProxySweepState? = dao.get(AppStateKeys.PROXY_SWEEP)?.let {
        runCatching { json.decodeFromString(ProxySweepState.serializer(), it) }.getOrNull()
    }

    suspend fun setProxySweep(state: ProxySweepState) {
        dao.put(AppStateEntity(AppStateKeys.PROXY_SWEEP, json.encodeToString(ProxySweepState.serializer(), state)))
    }

    // ---------- Pending profile-network decision ----------

    /** The pending warning payload, or null when no decision is pending. */
    suspend fun pendingNetDecision(): PendingNetDecision? =
        dao.get(AppStateKeys.PENDING_NET_DECISION)?.let {
            runCatching { json.decodeFromString(PendingNetDecision.serializer(), it) }.getOrNull()
        }

    /** Arms the gate: persists the payload the warning activity needs. */
    suspend fun setPendingNetDecision(decision: PendingNetDecision) {
        dao.put(
            AppStateEntity(
                AppStateKeys.PENDING_NET_DECISION,
                json.encodeToString(PendingNetDecision.serializer(), decision)
            )
        )
    }

    /** Releases the gate: the user decided (or the state was unreadable). */
    suspend fun clearPendingNetDecision() {
        dao.remove(AppStateKeys.PENDING_NET_DECISION)
    }

    suspend fun externalUrl(): String? = dao.get(AppStateKeys.EXTERNAL_URL)

    suspend fun setExternalUrl(url: String?) {
        if (url == null) dao.remove(AppStateKeys.EXTERNAL_URL)
        else dao.put(AppStateEntity(AppStateKeys.EXTERNAL_URL, url))
    }

    suspend fun newSessionId(): String {
        val id = java.util.UUID.randomUUID().toString()
        dao.put(AppStateEntity(AppStateKeys.SESSION_ID, id))
        return id
    }

    suspend fun sessionId(): String? = dao.get(AppStateKeys.SESSION_ID)

    // ---------- Post-create password-import offer ----------

    /**
     * Profile id owing an "import your passwords?" offer, or null.
     *
     * Persisted because the quick-switcher path creates a profile and then
     * restarts the process, leaving no UI to raise it in; the profile list
     * picks it up on its next appearance.
     */
    fun observePasswordImportOffer(): Flow<String?> =
        dao.observe(AppStateKeys.PASSWORD_IMPORT_OFFER)

    /** null settles the offer. */
    suspend fun setPasswordImportOffer(profileId: String?) {
        if (profileId == null) dao.remove(AppStateKeys.PASSWORD_IMPORT_OFFER)
        else dao.put(AppStateEntity(AppStateKeys.PASSWORD_IMPORT_OFFER, profileId))
    }

    // ---------- AI agent settings ----------

    val agentSettings: Flow<AgentSettings> =
        dao.observe(AppStateKeys.AGENT_SETTINGS).map { raw ->
            raw?.let { runCatching { json.decodeFromString(AgentSettings.serializer(), it) }.getOrNull() }
                ?: AgentSettings()
        }

    suspend fun agentSettingsSnapshot(): AgentSettings =
        dao.get(AppStateKeys.AGENT_SETTINGS)?.let {
            runCatching { json.decodeFromString(AgentSettings.serializer(), it) }.getOrNull()
        } ?: AgentSettings()

    suspend fun saveAgentSettings(settings: AgentSettings) {
        // Non-cancellable persistence primitive: finishing the calling
        // activity mid-write must never lose the agent settings (e.g. the
        // default provider/model selected right after saving a provider).
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            dao.put(AppStateEntity(AppStateKeys.AGENT_SETTINGS, json.encodeToString(AgentSettings.serializer(), settings)))
        }
    }

    /**
     * Read-modify-write of the agent settings against the PERSISTED blob,
     * serialised in-process. This is the write path every settings change
     * should take; [saveAgentSettings] remains only for whole-blob writes that
     * are authoritative by construction (a backup import replacing everything).
     *
     * The caller must NOT hand in a value derived from an in-memory copy. The
     * whole blob is replaced on every save, so a transform applied to a stale
     * base silently reverts everything changed since that copy was read: turn
     * the standing context on in the settings activity and the panel — still
     * holding the settings it loaded before that — would write it back off
     * with its next unrelated edit. Reading the current blob INSIDE the lock
     * makes each write a merge of what is stored rather than a resurrection of
     * what the caller last happened to see.
     *
     * Two writers in different processes can still interleave between the read
     * and the write. That window is a single Room round trip rather than the
     * minutes an in-memory copy can sit around, which is the difference
     * between a race nobody hits and one the user hits daily.
     *
     * @return the value that was persisted.
     */
    suspend fun updateAgentSettings(
        transform: (AgentSettings) -> AgentSettings
    ): AgentSettings = agentSettingsWrites.withLock {
        val next = transform(agentSettingsSnapshot())
        saveAgentSettings(next)
        next
    }

    // ---------- Local AI (Ollama) tuning ----------

    val localAiTuning: Flow<LocalAiTuning> =
        dao.observe(AppStateKeys.LOCAL_AI_SETTINGS).map { raw ->
            raw?.let { runCatching { json.decodeFromString(LocalAiTuning.serializer(), it) }.getOrNull() }
                ?: LocalAiTuning()
        }

    suspend fun localAiTuningSnapshot(): LocalAiTuning =
        dao.get(AppStateKeys.LOCAL_AI_SETTINGS)?.let {
            runCatching { json.decodeFromString(LocalAiTuning.serializer(), it) }.getOrNull()
        } ?: LocalAiTuning()

    suspend fun saveLocalAiTuning(tuning: LocalAiTuning) {
        // Same non-cancellable persistence primitive as saveAgentSettings:
        // a tuning save (or a backup import restoring host + GPU layers)
        // racing the activity teardown must never write half its intent.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            dao.put(AppStateEntity(AppStateKeys.LOCAL_AI_SETTINGS, json.encodeToString(LocalAiTuning.serializer(), tuning)))
        }
    }

    // ---------- Profile lock (wallet + 2FA) ----------

    /**
     * This profile's lock record, or null when no PIN was ever set. A row that
     * cannot be decoded is treated as absent (the device credential still opens
     * the wallet), never as a lockout.
     */
    suspend fun profileLockRecord(profileId: String): WalletLockRecord? {
        dao.get(AppStateKeys.PROFILE_LOCK_PREFIX + profileId)?.let {
            return runCatching { json.decodeFromString(WalletLockRecord.serializer(), it) }.getOrNull()
        }
        // One-time adoption of the pre-rename record; dropping it would reopen
        // an upgraded wallet ungated. The old row is deleted so a later clear()
        // cannot be undone by this fallback finding it again.
        val legacy = dao.get(AppStateKeys.LEGACY_PROFILE_LOCK_PREFIX + profileId) ?: return null
        val record = runCatching {
            json.decodeFromString(WalletLockRecord.serializer(), legacy)
        }.getOrNull() ?: return null
        saveProfileLockRecord(profileId, record)
        dao.remove(AppStateKeys.LEGACY_PROFILE_LOCK_PREFIX + profileId)
        return record
    }

    suspend fun saveProfileLockRecord(profileId: String, record: WalletLockRecord) {
        dao.put(
            AppStateEntity(
                AppStateKeys.PROFILE_LOCK_PREFIX + profileId,
                json.encodeToString(WalletLockRecord.serializer(), record)
            )
        )
    }

    suspend fun clearProfileLockRecord(profileId: String) {
        dao.remove(AppStateKeys.PROFILE_LOCK_PREFIX + profileId)
        dao.remove(AppStateKeys.LEGACY_PROFILE_LOCK_PREFIX + profileId)
    }

    private fun serializeSet(values: Set<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), values.toList())

    private fun deserializeSet(raw: String?): Set<String> =
        raw?.let {
            runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
        }?.toSet() ?: emptySet()
}
