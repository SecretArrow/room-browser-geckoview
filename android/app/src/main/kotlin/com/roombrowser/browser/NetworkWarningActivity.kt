package com.roombrowser.browser

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roombrowser.RoomBrowserApp
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.data.net.fetchExitIp
import com.roombrowser.data.proxy.ProxyCoordinator
import com.roombrowser.data.repo.IpCache
import com.roombrowser.domain.model.BrowserGlobalSettings
import com.roombrowser.domain.proxy.ProxyCandidate
import com.roombrowser.domain.proxy.ProxyHealthRules
import com.roombrowser.domain.proxy.ProxyPicker
import com.roombrowser.domain.proxy.ProxyScheme
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.PagingFooter
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomCardShape
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The user's decision on the profile network warning. */
enum class NetworkWarningDecision {
    /** Continue with THIS profile (session-acknowledged). */
    CONTINUE,

    /** Pick a different profile (re-opens the quick switcher). */
    SWITCH,

    /** Never warn again for this IP (persisted suppression). */
    SUPPRESS
}

/**
 * Full-screen Profile Network Warning (spec sections 6 / 74) — replaces the
 * old IpWarningDialog.
 *
 * Runs in the ':browser' process next to BrowserActivity, which launches it
 * FOR RESULT while a pending network decision gates the profile:
 *
 *  - The gate is PERSISTED (app_state `net_decision_pending`), so process
 *    death / activity recreation re-launches this screen — the decision can
 *    never be bypassed, and system Back only finishes with RESULT_CANCELED
 *    (BrowserActivity immediately re-launches while the flag stands).
 *  - Exactly three decisions: Continue, Switch Profile, Don't Warn Again
 *    for This IP. Everything about the conflict arrives via Intent extras;
 *    the only thing this screen reads for itself is a refreshed address,
 *    and a refresh is never a decision — the gate still stands after one.
 *  - Refresh Proxy is NOT a fourth decision: it finds a verified proxy and
 *    carries the choice back so BrowserActivity can apply it to this session
 *    only. Nothing about the profile's own proxy settings is written.
 *
 * Manifest entry (owner: main agent — reported, not edited here):
 * `process=":browser"`, `exported="false"`, same configChanges set as
 * BrowserActivity, `windowSoftInputMode="adjustResize"`.
 */
class NetworkWarningActivity : ComponentActivity() {

    /** The session-only proxy the user chose, carried back on finish. */
    private var chosenProxy: ProxyCandidate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ip = intent.getStringExtra(EXTRA_IP) ?: ""
        val previousProfileName =
            intent.getStringExtra(EXTRA_PREVIOUS_PROFILE_NAME)?.takeIf { it.isNotBlank() }
                ?: "another profile"
        val lastSeenAt = intent.getLongExtra(EXTRA_LAST_SEEN, 0L)
        val graph = runCatching { (application as RoomBrowserApp).graph }.getOrNull()
        val appState = graph?.appState
        val proxyCoordinator = graph?.proxyCoordinator
        // BrowserGlobalSettings is app-wide (not per-profile); the ':browser'
        // graph carries it, and the same flow the settings screen writes feeds it.
        val globalSettingsFlow = appState?.globalSettings ?: flowOf(BrowserGlobalSettings())
        setContent {
            RoomBrowserTheme {
                val globalSettings by globalSettingsFlow.collectAsState(BrowserGlobalSettings())
                var refreshing by remember { mutableStateOf(false) }
                var observedIp by remember { mutableStateOf<String?>(null) }
                var refreshFailed by remember { mutableStateOf(false) }
                var proxyScanning by remember { mutableStateOf(false) }
                var proxyNote by remember { mutableStateOf<String?>(null) }
                var proxyError by remember { mutableStateOf<String?>(null) }
                var proxyChoices by remember { mutableStateOf<List<FoundProxy>>(emptyList()) }
                var pickerOpen by remember { mutableStateOf(false) }
                var airplaneShortcutUnavailable by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                NetworkWarningScreen(
                    ip = ip,
                    observedIp = observedIp,
                    refreshing = refreshing,
                    refreshFailed = refreshFailed,
                    previousProfileName = previousProfileName,
                    lastSeenAt = lastSeenAt,
                    proxyScanning = proxyScanning,
                    proxyNote = proxyNote,
                    proxyError = proxyError,
                    proxyChoices = proxyChoices,
                    pickerOpen = pickerOpen,
                    offerNetworkChangeOptions = globalSettings.offerNetworkChangeOptions,
                    offerAirplaneModeShortcut = globalSettings.offerAirplaneModeShortcut,
                    airplaneShortcutUnavailable = airplaneShortcutUnavailable,
                    onRefresh = {
                        if (!refreshing) {
                            refreshing = true
                            refreshFailed = false
                            scope.launch {
                                // A fresh OkHttpClient, not the browser's: the browser's
                                // carries this profile's proxy (and its DNS choice), and a
                                // proxied reading is the wrong answer to "is this still the
                                // address I am warned about?".
                                val seen = runCatching {
                                    fetchExitIp(
                                        OkHttpClient(),
                                        PROBE_TIMEOUT_MS,
                                        ProxyHealthRules::looksLikeIp
                                    )
                                }.getOrNull()
                                if (seen != null) {
                                    appState?.setIpCache(
                                        IpCache(seen, System.currentTimeMillis())
                                    )
                                    observedIp = seen
                                } else {
                                    refreshFailed = true
                                }
                                refreshing = false
                            }
                        }
                    },
                    onRefreshProxy = {
                        // The picker has no other way in, so gating the button alone
                        // would leave a dialog that nothing could have opened.
                        if (globalSettings.offerNetworkChangeOptions && !proxyScanning) {
                            proxyScanning = true
                            proxyNote = null
                            proxyError = null
                            proxyChoices = emptyList()
                            pickerOpen = false
                            // A re-scan replaces the previous answer, so the pick it
                            // produced goes with it: otherwise the screen can report that
                            // no proxy was found while still carrying the old one back.
                            chosenProxy = null
                            scope.launch {
                                val outcome = scanForProxies(proxyCoordinator)
                                proxyScanning = false
                                when (outcome) {
                                    ProxyScanOutcome.Unsupported -> proxyError = ENGINE_NO_PROXY
                                    ProxyScanOutcome.CannotCarry -> proxyError = ENGINE_CANNOT_CARRY
                                    ProxyScanOutcome.Failed -> proxyError = SCAN_FAILED
                                    ProxyScanOutcome.None -> proxyNote = NO_PROXY_FOUND
                                    is ProxyScanOutcome.Found ->
                                        if (outcome.proxies.size == 1) {
                                            val only = outcome.proxies.first()
                                            chosenProxy = only.candidate
                                            proxyNote = selectedNote(only)
                                        } else {
                                            proxyChoices = outcome.proxies
                                            pickerOpen = true
                                        }
                                }
                            }
                        }
                    },
                    onPickProxy = { found ->
                        chosenProxy = found.candidate
                        pickerOpen = false
                        proxyNote = selectedNote(found)
                    },
                    onClosePicker = { pickerOpen = false },
                    onOpenAirplaneMode = {
                        val airplaneSettings = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                        // Android forbids toggling Airplane Mode directly, so this is
                        // only ever the settings screen — and some devices ship none.
                        if (airplaneSettings.resolveActivity(packageManager) == null) {
                            airplaneShortcutUnavailable = true
                        } else {
                            airplaneShortcutUnavailable =
                                runCatching { startActivity(airplaneSettings) }.isFailure
                        }
                    },
                    onContinue = { finishWith(RESULT_CONTINUE) },
                    onSwitchProfile = { finishWith(RESULT_SWITCH) },
                    onDontWarnAgain = { finishWith(RESULT_SUPPRESS) }
                )
            }
        }
    }

    /**
     * Sweep the catalogue and keep only what a sweep verified as usable, filtered to the
     * schemes this engine can actually carry and ranked safest first.
     *
     * "Nothing usable was found" and "the engine cannot carry what was found" are kept
     * apart: a WebView sweep that found only SOCKS endpoints found working proxies, and
     * saying otherwise would be false.
     */
    private suspend fun scanForProxies(coordinator: ProxyCoordinator?): ProxyScanOutcome {
        if (coordinator == null) return ProxyScanOutcome.Failed
        return runCatching {
            if (!ProfileEngine.proxySupported()) return ProxyScanOutcome.Unsupported
            coordinator.sweep(PROXY_SCAN_BUDGET_MS)
            val sweep = coordinator.state()
            val health = sweep.byId()
            val usable = ProxyHealthRules.rankSafe(coordinator.candidates(), health, sweep.directIp)
            if (usable.isEmpty()) return ProxyScanOutcome.None
            val carriable = usable
                .filter { ProfileEngine.proxySupportsScheme(it.scheme) }
                .mapNotNull { candidate ->
                    health[candidate.id]?.let { FoundProxy(candidate, it.latencyMs) }
                }
            if (carriable.isEmpty()) ProxyScanOutcome.CannotCarry
            else ProxyScanOutcome.Found(carriable)
        }.getOrElse { ProxyScanOutcome.Failed }
    }

    /** The ONLY exits are the three decisions (Back = RESULT_CANCELED). */
    private fun finishWith(resultCode: Int) {
        val data = Intent()
        chosenProxy?.let { candidate ->
            data.putExtra(EXTRA_CHOSEN_HOST, candidate.host)
            data.putExtra(EXTRA_CHOSEN_PORT, candidate.port)
            data.putExtra(EXTRA_CHOSEN_SCHEME, candidate.scheme.id)
            candidate.country?.let { data.putExtra(EXTRA_CHOSEN_COUNTRY, it) }
        }
        setResult(resultCode, data)
        finish()
    }

    companion object {
        const val EXTRA_IP = "com.roombrowser.extra.NET_WARNING_IP"
        const val EXTRA_PREVIOUS_PROFILE_NAME =
            "com.roombrowser.extra.NET_WARNING_PREVIOUS_PROFILE_NAME"
        const val EXTRA_LAST_SEEN = "com.roombrowser.extra.NET_WARNING_LAST_SEEN"

        /** The session-only proxy the screen carried back, if one was chosen. */
        const val EXTRA_CHOSEN_HOST = "com.roombrowser.extra.NET_WARNING_CHOSEN_HOST"
        const val EXTRA_CHOSEN_PORT = "com.roombrowser.extra.NET_WARNING_CHOSEN_PORT"
        const val EXTRA_CHOSEN_SCHEME = "com.roombrowser.extra.NET_WARNING_CHOSEN_SCHEME"
        const val EXTRA_CHOSEN_COUNTRY = "com.roombrowser.extra.NET_WARNING_CHOSEN_COUNTRY"

        /**
         * Decision result codes — allocated from Activity.RESULT_FIRST_USER
         * (= 1) upward, so they can never collide with RESULT_OK (-1) or
         * RESULT_CANCELED (0, which a system-Back finish reports).
         */
        const val RESULT_CONTINUE = 1
        const val RESULT_SWITCH = 2
        const val RESULT_SUPPRESS = 3

        /** Bounded so a dead network fails the refresh instead of hanging it. */
        private const val PROBE_TIMEOUT_MS = 6_000L

        /** A scan is tens of connections and takes seconds; the screen stays up meanwhile. */
        private const val PROXY_SCAN_BUDGET_MS = 25_000L

        private const val NO_PROXY_FOUND =
            "No working proxy was found — staying on the direct connection."

        private const val SCAN_FAILED =
            "Could not look for a proxy. Check the connection and try again."

        private const val ENGINE_NO_PROXY =
            "This engine cannot route pages through a proxy, so there is nothing to find."

        private const val ENGINE_CANNOT_CARRY =
            "Proxies were found, but this engine cannot carry any of their schemes."

        /** Rebuilds the session-only proxy the screen carried back, if one was chosen. */
        fun chosenProxyFrom(data: Intent?): ProxyCandidate? {
            val host = data?.getStringExtra(EXTRA_CHOSEN_HOST)?.takeIf { it.isNotBlank() }
                ?: return null
            val port = data.getIntExtra(EXTRA_CHOSEN_PORT, 0)
            if (port !in 1..65535) return null
            return ProxyCandidate(
                host = host,
                port = port,
                scheme = ProxyScheme.fromId(
                    data.getStringExtra(EXTRA_CHOSEN_SCHEME).orEmpty()
                ) ?: ProxyScheme.HTTP,
                country = data.getStringExtra(EXTRA_CHOSEN_COUNTRY)
            )
        }
    }
}

/** What a proxy scan ended in, so the screen can say the right thing. */
private sealed interface ProxyScanOutcome {
    data class Found(val proxies: List<FoundProxy>) : ProxyScanOutcome
    data object None : ProxyScanOutcome
    data object Failed : ProxyScanOutcome
    data object Unsupported : ProxyScanOutcome
    data object CannotCarry : ProxyScanOutcome
}

private fun describeProxy(found: FoundProxy): String =
    "${found.candidate.host}:${found.candidate.port} · " +
        "${found.candidate.scheme.id.uppercase()} · ${found.latencyMs} ms"

/**
 * Says a proxy was CHOSEN, not that it is in use: nothing is handed to the engine until the
 * screen returns a decision, so a note claiming "using ..." would be wrong on Back.
 */
private fun selectedNote(found: FoundProxy): String =
    "Selected ${describeProxy(found)} for this session."

/**
 * Green for an address that has CHANGED since the warning was raised (user
 * request: "kalau ip sudah berbeda buat warna ip hijau"). Two values because
 * the card follows the profile theme — a light green reads on a dark surface,
 * a dark one on a light surface, and the profile picks which.
 */
private val ChangedIpGreenDark = Color(0xFFA5D6A7)
private val ChangedIpGreenLight = Color(0xFF1B5E20)

/** Minimalist, theme-following full-screen decision surface. */
@Composable
private fun NetworkWarningScreen(
    ip: String,
    observedIp: String?,
    refreshing: Boolean,
    refreshFailed: Boolean,
    previousProfileName: String,
    lastSeenAt: Long,
    proxyScanning: Boolean,
    proxyNote: String?,
    proxyError: String?,
    proxyChoices: List<FoundProxy>,
    pickerOpen: Boolean,
    offerNetworkChangeOptions: Boolean,
    offerAirplaneModeShortcut: Boolean,
    airplaneShortcutUnavailable: Boolean,
    onRefresh: () -> Unit,
    onRefreshProxy: () -> Unit,
    onPickProxy: (FoundProxy) -> Unit,
    onClosePicker: () -> Unit,
    onOpenAirplaneMode: () -> Unit,
    onContinue: () -> Unit,
    onSwitchProfile: () -> Unit,
    onDontWarnAgain: () -> Unit
) {
    val extras = LocalRoomExtras.current
    // Only a reading that came back AND differs counts as changed — and a
    // failed re-check retires the verdict, so a stale reading is never painted
    // green as if the newest probe had produced it.
    val changed = observedIp != null && observedIp != ip && !refreshFailed
    val changedGreen = if (extras.dark) ChangedIpGreenDark else ChangedIpGreenLight
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Box(
        Modifier
            .fillMaxSize()
            .background(extras.background)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // Edge-to-edge: content stays clear of the status bar, the
                // navigation bar and any display cutout (same pattern as the
                // browser shell).
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                )
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoomCardShape)
                    .background(extras.surfaceAlt),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = extras.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Profile Network Warning",
                style = MaterialTheme.typography.headlineSmall,
                color = extras.textPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "This profile is being opened from a public IP previously associated " +
                    "with the \"$previousProfileName\" profile.",
                style = MaterialTheme.typography.bodyMedium,
                color = extras.textSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    // The app's one card radius (RoomCardShape is exactly this
                    // 0.8-of-the-profile-radius shape) rather than a local copy.
                    .clip(RoomCardShape)
                    .background(extras.surface)
                    .padding(16.dp)
            ) {
                // The address itself stays a plain text node — the verdict below
                // carries the state, so a reader hears a sentence rather than a
                // bare id on the address.
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Current IP: ${observedIp ?: ip}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (changed) changedGreen else extras.textPrimary
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onRefresh,
                        enabled = !refreshing,
                        modifier = Modifier.semantics {
                            contentDescription = "net_warning_refresh"
                        }
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = extras.primary
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("Refresh IP")
                    }
                }
                if (lastSeenAt > 0L) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Last seen: ${timeFormat.format(Date(lastSeenAt))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textSecondary
                    )
                }
                if (changed) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "This address differs from the one that was warned about.",
                        style = MaterialTheme.typography.bodySmall,
                        color = changedGreen,
                        modifier = Modifier.semantics {
                            contentDescription = "net_warning_ip_changed"
                        }
                    )
                } else if (observedIp != null && !refreshFailed) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Still the same address.",
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.textSecondary,
                        modifier = Modifier.semantics {
                            contentDescription = "net_warning_ip_unchanged"
                        }
                    )
                }
                if (refreshFailed) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Could not reach an IP service. Check the connection and try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics {
                            contentDescription = "net_warning_refresh_failed"
                        }
                    )
                }
            }
            if (offerNetworkChangeOptions) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onRefreshProxy,
                    enabled = !proxyScanning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "net_warning_refresh_proxy" }
                ) {
                    if (proxyScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = extras.primary
                        )
                    } else {
                        Icon(
                            Icons.Filled.SwapHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Refresh Proxy")
                }
                if (proxyNote != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        proxyNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics {
                            contentDescription = "net_warning_proxy_note"
                        }
                    )
                }
                if (proxyError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        proxyError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics {
                            contentDescription = "net_warning_proxy_failed"
                        }
                    )
                }
            }
            if (offerAirplaneModeShortcut) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onOpenAirplaneMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "net_warning_airplane_mode" }
                ) {
                    Icon(
                        Icons.Filled.AirplanemodeActive,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Open Airplane Mode settings")
                }
                if (airplaneShortcutUnavailable) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This device has no Airplane Mode settings screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics {
                            contentDescription = "net_warning_airplane_mode_unavailable"
                        }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "A shared public IP does not prove that profiles belong to the same " +
                    "person. This is an informational warning only.",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Continue")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSwitchProfile, modifier = Modifier.fillMaxWidth()) {
                Text("Switch Profile")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDontWarnAgain, modifier = Modifier.fillMaxWidth()) {
                Text("Don't Warn Again for This IP")
            }
        }
        if (offerNetworkChangeOptions && pickerOpen && proxyChoices.isNotEmpty()) {
            ProxyPickerDialog(
                choices = proxyChoices,
                onPick = onPickProxy,
                onDismiss = onClosePicker
            )
        }
    }
}

/**
 * Pick one of the verified proxies for this session.
 *
 * Search and paging are the [ProxyPicker] helper's job; this only draws its page and states
 * the honest trade-off once, plainly.
 */
@Composable
private fun ProxyPickerDialog(
    choices: List<FoundProxy>,
    onPick: (FoundProxy) -> Unit,
    onDismiss: () -> Unit
) {
    val extras = LocalRoomExtras.current
    var query by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(0) }
    val candidates = choices.map { it.candidate }
    val byId = choices.associateBy { it.candidate.id }
    val paged = ProxyPicker.page(candidates, query, page)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use a proxy for this session") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "A free proxy is an untrusted middlebox — it can see which sites you " +
                        "open and, for http, the page itself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textSecondary
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        page = 0
                    },
                    singleLine = true,
                    label = { Text("Search host, port or country") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "net_warning_proxy_search" }
                )
                Spacer(Modifier.height(8.dp))
                if (paged.items.isEmpty()) {
                    Text(
                        "No proxy matches that search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = extras.textSecondary
                    )
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        paged.items.forEach { candidate ->
                            val found = byId[candidate.id] ?: return@forEach
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(found) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${candidate.host}:${candidate.port}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = extras.textPrimary
                                    )
                                    Text(
                                        listOfNotNull(
                                            candidate.scheme.id.uppercase(),
                                            "${found.latencyMs} ms",
                                            candidate.country
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = extras.textSecondary
                                    )
                                }
                            }
                        }
                    }
                    PagingFooter(
                        page = paged.pageIndex,
                        total = paged.total,
                        onPage = { page = it },
                        semanticsPrefix = "net_warning_proxy"
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
