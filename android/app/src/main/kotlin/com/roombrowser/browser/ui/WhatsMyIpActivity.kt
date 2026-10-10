package com.roombrowser.browser.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roombrowser.RoomBrowserApp
import com.roombrowser.data.net.ConnectionFacts
import com.roombrowser.data.net.DeviceFacts
import com.roombrowser.data.net.SystemFacts
import com.roombrowser.data.net.fetchExitIp
import com.roombrowser.domain.model.BrowserGlobalSettings
import com.roombrowser.domain.model.DnsMode
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import com.roombrowser.domain.model.UserAgents
import com.roombrowser.domain.net.ExitIp
import com.roombrowser.domain.net.NetFacts
import com.roombrowser.domain.proxy.ProxyHealthRules
import com.roombrowser.engine.EngineRuntime
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomCardShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * "What's My IP" — what this profile looks like from the far end.
 *
 * Everything here is reported, nothing is configured, and the two halves are
 * kept apart on purpose:
 *
 *  - the ADDRESS is measured DIRECTLY, never through the profile's proxy. That
 *    reading is the one a proxy is judged against, so asking for it through a
 *    proxy would confirm the proxy by trusting it.
 *  - the IDENTITY (user agent, proxy route) is read from the profile's own
 *    settings through the same resolvers the engine is configured from, so a
 *    row cannot claim something the engine does not send.
 *
 * A refresh re-measures both: the address may have changed, and so may the
 * transport it came over.
 */
class WhatsMyIpActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val profileIdValue = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileIdValue.isNullOrBlank()) {
            // Nothing to report on — the launcher always names a profile.
            finish()
            return
        }
        val profileId = ProfileId(profileIdValue)
        val appContext = applicationContext
        setContent {
            RoomBrowserTheme {
                var facts by remember { mutableStateOf<IpFacts?>(null) }
                var reading by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(profileId) {
                    facts = loadFacts(appContext, profileId)
                }
                WhatsMyIpScreen(
                    facts = facts,
                    reading = reading,
                    onRefresh = {
                        if (!reading) {
                            reading = true
                            scope.launch {
                                facts = loadFacts(appContext, profileId)
                                reading = false
                            }
                        }
                    },
                    onClose = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"

        fun launch(context: Context, profileId: String) {
            context.startActivity(
                Intent(context, WhatsMyIpActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
            )
        }
    }
}

/** What the profile sends, and where each part of it comes from. */
private data class IdentityFacts(
    val userAgent: String,
    val sourceLabel: String,
    val engineName: String,
    val engineStockUserAgent: String
)

/** One reading of the screen, so a refresh replaces the whole picture at once. */
private data class IpFacts(
    val ipv4: String?,
    val ipv6: String?,
    val identity: IdentityFacts,
    val proxySummary: String,
    val proxyScopes: String,
    val dnsSummary: String,
    val connection: ConnectionFacts,
    val device: DeviceFacts
)

/** Bounded so a network that never answers fails the reading instead of hanging it. */
private const val PROBE_TIMEOUT_MS = 8_000L

private const val UNKNOWN = "unknown"

private suspend fun loadFacts(context: Context, profileId: ProfileId): IpFacts =
    withContext(Dispatchers.IO) {
        val graph = (context.applicationContext as RoomBrowserApp).graph
        val profile = runCatching { graph.profileRepo.getProfile(profileId) }.getOrNull()
        val settings = profile?.settings ?: ProfileSettings()
        val global = runCatching { graph.appState.globalSettingsSnapshot() }
            .getOrDefault(BrowserGlobalSettings())

        // A fresh client, never the browser's: the browser's carries this
        // profile's proxy and its DNS choice, and a proxied address is the
        // wrong answer to "what address does the internet see ME from".
        val client = OkHttpClient()
        val ipv4 = runCatching {
            fetchExitIp(client, PROBE_TIMEOUT_MS, ProxyHealthRules::looksLikeIp)
        }.getOrNull()
        val ipv6 = runCatching {
            fetchExitIp(
                client,
                PROBE_TIMEOUT_MS,
                ProxyHealthRules::looksLikeIp,
                ExitIp.IPV6_ENDPOINTS
            )
        }.getOrNull()

        val host = runCatching { EngineRuntime.host() }.getOrNull()
        val source = NetFacts.uaSource(settings)
        val chosen = UserAgents.effectiveUserAgent(settings)
        // Asked only when the answer is the one on screen: the engine may not
        // be able to say yet, and it is asked for nothing else here.
        val engineStock = if (profile != null && chosen == null) {
            runCatching { host?.defaultUserAgent(context) }.getOrNull().orEmpty()
        } else ""

        IpFacts(
            ipv4 = ipv4,
            ipv6 = ipv6,
            identity = IdentityFacts(
                userAgent = when {
                    profile == null -> UNKNOWN
                    chosen != null -> chosen
                    engineStock.isNotEmpty() -> engineStock
                    else -> UNKNOWN
                },
                sourceLabel = if (profile == null) {
                    "This profile could not be read"
                } else {
                    NetFacts.uaSourceLabel(source)
                },
                engineName = runCatching { host?.engineName(context) }.getOrNull().orEmpty(),
                engineStockUserAgent = engineStock
            ),
            proxySummary = NetFacts.proxySummary(
                finderEnabled = global.proxyEnabled,
                mode = settings.proxyMode,
                host = settings.proxyHost,
                port = settings.proxyPort,
                scheme = settings.proxyScheme,
                scopes = settings.proxyScopes
            ),
            proxyScopes = if (global.proxyEnabled && settings.proxyScopes.isEmpty()) {
                "none - nothing is routed"
            } else {
                NetFacts.scopeList(settings.proxyScopes).ifEmpty { "none - nothing is routed" }
            },
            dnsSummary = dnsSummary(settings),
            connection = SystemFacts.connection(context),
            device = SystemFacts.device(context)
        )
    }

/**
 * The profile's own DNS choice. A mode with no endpoint stored falls back to
 * the system resolver, so the row says that rather than naming a setting the
 * engine cannot act on.
 */
private fun dnsSummary(settings: ProfileSettings): String = when (settings.dnsMode) {
    DnsMode.SYSTEM -> "System resolver"
    DnsMode.AUTO -> "Automatic - secure where the network offers it"
    DnsMode.DOH -> settings.dohUrl?.takeIf { it.isNotBlank() }
        ?.let { "DNS over HTTPS - $it" }
        ?: "DNS over HTTPS, but no URL is saved - using the system resolver"
    DnsMode.DOT -> settings.dotHostname?.takeIf { it.isNotBlank() }
        ?.let { "DNS over TLS - $it" }
        ?: "DNS over TLS, but no hostname is saved - using the system resolver"
}

@Composable
private fun WhatsMyIpScreen(
    facts: IpFacts?,
    reading: Boolean,
    onRefresh: () -> Unit,
    onClose: () -> Unit
) {
    val extras = LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxSize()
            .background(extras.background)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.semantics { contentDescription = "Close" }
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                "What's My IP",
                style = MaterialTheme.typography.titleLarge,
                color = extras.textPrimary
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onRefresh,
                enabled = !reading,
                modifier = Modifier.semantics { contentDescription = "whatsmyip_refresh" }
            ) {
                if (reading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = extras.primary
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(if (reading) "Reading" else "Refresh")
            }
        }

        val shown = facts
        if (shown == null) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = extras.primary)
                Spacer(Modifier.height(16.dp))
                Text("Reading this device and its network", color = extras.textSecondary)
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Section("Network") {
                    FactRow(
                        label = "Public address (IPv4)",
                        value = shown.ipv4 ?: "No reading - no IP service answered",
                        desc = "whatsmyip_ipv4"
                    )
                    FactRow(
                        label = "Public address (IPv6)",
                        value = shown.ipv6
                            ?: "None - this network gave the probe no IPv6 route",
                        desc = "whatsmyip_ipv6"
                    )
                    Text(
                        "Measured directly, without this profile's proxy. A proxy route " +
                            "is a deliberate detour, so the address above is the one every " +
                            "proxy decision is judged against.",
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.textSecondary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Section("What this profile says it is") {
                    FactRow(
                        label = "User agent sent",
                        value = shown.identity.userAgent,
                        desc = "whatsmyip_user_agent"
                    )
                    FactRow(label = "Coming from", value = shown.identity.sourceLabel)
                    FactRow(
                        label = "Engine",
                        value = shown.identity.engineName.ifBlank { UNKNOWN }
                    )
                    FactRow(
                        label = "Engine's own user agent",
                        value = shown.identity.engineStockUserAgent.ifBlank {
                            "Not reported - this profile supplies its own"
                        }
                    )
                }

                Section("Route") {
                    FactRow(
                        label = "Proxy for page traffic",
                        value = shown.proxySummary,
                        desc = "whatsmyip_proxy"
                    )
                    FactRow(label = "Scopes routed", value = shown.proxyScopes)
                    FactRow(label = "DNS", value = shown.dnsSummary)
                }

                Section("This connection") {
                    FactRow(label = "Transport", value = shown.connection.transport)
                    FactRow(
                        label = "Metered",
                        value = if (shown.connection.metered) "Yes - a metered network" else "No"
                    )
                    FactRow(label = "VPN", value = if (shown.connection.vpn) "Yes" else "No")
                    FactRow(
                        label = "Local addresses",
                        value = shown.connection.localAddresses.ifEmpty { listOf("None reported") }
                            .joinToString("\n")
                    )
                    FactRow(
                        label = "System DNS servers",
                        value = shown.connection.systemDns.ifEmpty { listOf("None reported") }
                            .joinToString("\n")
                    )
                    FactRow(
                        label = "Private DNS",
                        value = shown.connection.privateDns ?: UNKNOWN
                    )
                    FactRow(
                        label = "Network operator",
                        value = shown.connection.operatorName ?: "Not reported"
                    )
                }

                Section("This device") {
                    FactRow(
                        label = "App",
                        value = "${shown.device.appVersion} (${shown.device.appVersionCode}) - " +
                            shown.device.packageName
                    )
                    FactRow(
                        label = "Android",
                        value = "${shown.device.androidRelease} (API ${shown.device.apiLevel})"
                    )
                    FactRow(
                        label = "Device",
                        value = "${shown.device.manufacturer} ${shown.device.model}"
                    )
                    FactRow(label = "Primary ABI", value = shown.device.primaryAbi)
                    FactRow(label = "Locale", value = shown.device.locale)
                    FactRow(label = "Time zone", value = shown.device.timeZone)
                    FactRow(
                        label = "Screen",
                        value = "${shown.device.screenPixels}, ${shown.device.density}"
                    )
                }

                Text(
                    "None of this leaves the device. The two address readings are the " +
                        "only requests this screen makes, and they go to the public " +
                        "IP-echo services the network check already uses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textSecondary,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val extras = LocalRoomExtras.current
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = extras.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoomCardShape)
            .background(extras.surface)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        content = content
    )
}

@Composable
private fun FactRow(label: String, value: String, desc: String? = null) {
    val extras = LocalRoomExtras.current
    val description = desc
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textPrimary,
            modifier = if (description == null) {
                Modifier
            } else {
                Modifier.semantics { contentDescription = description }
            }
        )
    }
}
