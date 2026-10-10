package com.roombrowser.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.roombrowser.data.net.fetchExitIp
import com.roombrowser.data.repo.IpCache
import com.roombrowser.domain.proxy.ProxyHealthRules
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomCardShape
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
 *
 * Manifest entry (owner: main agent — reported, not edited here):
 * `process=":browser"`, `exported="false"`, same configChanges set as
 * BrowserActivity, `windowSoftInputMode="adjustResize"`.
 */
class NetworkWarningActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ip = intent.getStringExtra(EXTRA_IP) ?: ""
        val previousProfileName =
            intent.getStringExtra(EXTRA_PREVIOUS_PROFILE_NAME)?.takeIf { it.isNotBlank() }
                ?: "another profile"
        val lastSeenAt = intent.getLongExtra(EXTRA_LAST_SEEN, 0L)
        val appState = (application as RoomBrowserApp).graph.appState
        setContent {
            RoomBrowserTheme {
                var refreshing by remember { mutableStateOf(false) }
                var observedIp by remember { mutableStateOf<String?>(null) }
                var refreshFailed by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                NetworkWarningScreen(
                    ip = ip,
                    observedIp = observedIp,
                    refreshing = refreshing,
                    refreshFailed = refreshFailed,
                    previousProfileName = previousProfileName,
                    lastSeenAt = lastSeenAt,
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
                                    appState.setIpCache(IpCache(seen, System.currentTimeMillis()))
                                    observedIp = seen
                                } else {
                                    refreshFailed = true
                                }
                                refreshing = false
                            }
                        }
                    },
                    onContinue = { finishWith(RESULT_CONTINUE) },
                    onSwitchProfile = { finishWith(RESULT_SWITCH) },
                    onDontWarnAgain = { finishWith(RESULT_SUPPRESS) }
                )
            }
        }
    }


    /** The ONLY exits are the three decisions (Back = RESULT_CANCELED). */
    private fun finishWith(resultCode: Int) {
        setResult(resultCode)
        finish()
    }

    companion object {
        const val EXTRA_IP = "com.roombrowser.extra.NET_WARNING_IP"
        const val EXTRA_PREVIOUS_PROFILE_NAME =
            "com.roombrowser.extra.NET_WARNING_PREVIOUS_PROFILE_NAME"
        const val EXTRA_LAST_SEEN = "com.roombrowser.extra.NET_WARNING_LAST_SEEN"

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
    }
}

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
    onRefresh: () -> Unit,
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
    Column(
        Modifier
            .fillMaxSize()
            .background(extras.background)
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
                    modifier = Modifier.semantics { contentDescription = "net_warning_refresh" }
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
}
