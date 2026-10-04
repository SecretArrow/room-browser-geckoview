package com.roombrowser.browser.ui

import android.content.pm.PackageManager
import com.roombrowser.engine.PermissionResponder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.browser.WebPermissions
import com.roombrowser.data.repo.PermissionKind
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBottomSheetShape
import com.roombrowser.ui.common.RoomSheetHeader

/**
 * Renders whatever the WEB ENGINE is waiting on: the camera/microphone sheet
 * and the location sheet.
 *
 * The state lives in the viewModel, not in a `showX` boolean like every other
 * sheet on this screen, because the page decides when a request arrives — the
 * user cannot open this one. That is also why nothing may simply close it: the
 * page's promise is pending until the app answers, so DISMISSAL REFUSES.
 *
 * Hosted once, next to the browsing surface, so it is only ever raised over
 * the tab that asked (the viewModel refuses a background engine's request
 * outright rather than queueing it).
 */
@Composable
fun SitePermissionHost(viewModel: BrowserViewModel) {
    val media = viewModel.pendingPermission
    val location = viewModel.pendingGeolocation
    // Media first when a page asks for both in the same breath: two modal
    // sheets cannot stack, and the camera is the larger concession. The
    // location request stays pending in the viewModel and is raised as soon
    // as this one is answered.
    if (media != null) {
        MediaPermissionSheet(viewModel = viewModel, pending = media)
    } else if (location != null) {
        LocationPermissionSheet(viewModel = viewModel)
    }
}

/**
 * Camera / microphone. The OS hop is the interesting part: the app can only
 * truthfully tell the page "yes" when Android itself has granted the runtime
 * permission, so "Allow" is really two answers — the user's, and the
 * platform's. The sheet stays up through the system dialog (the viewModel
 * keeps the request pending until it is settled), and the page is refused if
 * the platform says no, which is the honest outcome: it gets a refusal it can
 * report, not a camera that never opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaPermissionSheet(
    viewModel: BrowserViewModel,
    pending: BrowserViewModel.PendingPermission
) {
    val extras = LocalRoomExtras.current
    val context = LocalContext.current
    val needed = WebPermissions.androidPermissionsFor(pending.kinds)
    val requesterHost = UrlIntelligence.hostOf(pending.requesterOrigin) ?: pending.requesterOrigin
    val pageHost = UrlIntelligence.hostOf(pending.pageUrl)

    // The request the system dialog was opened FOR. A second request from the
    // page replaces the pending one while the dialog is up; without this the
    // dialog's result would be applied to the newer request, answering a
    // question the user was never asked.
    var awaiting by remember { mutableStateOf<PermissionResponder?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val target = awaiting
        awaiting = null
        if (target == null || viewModel.pendingPermission?.responder !== target) return@rememberLauncherForActivityResult
        if (granted.values.all { it }) {
            viewModel.grantPendingPermission()
        } else {
            // EVERY requested permission, not any: the WebView's grant is
            // wholesale, so a page that asked for camera AND microphone and
            // got one of them would open a stream that fails — which it
            // cannot tell apart from a broken device. Refusing is the answer
            // it can actually report.
            viewModel.denyPendingPermission()
            viewModel.showMessage(
                "Android blocked that permission — allow it for Room Browser in system settings"
            )
        }
    }

    fun allow() {
        val missing = needed.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        // Already held at the OS level → the user's tap IS the answer.
        if (missing.isEmpty()) {
            viewModel.grantPendingPermission()
            return
        }
        awaiting = pending.request
        launcher.launch(missing.toTypedArray())
    }

    ModalBottomSheet(
        onDismissRequest = { viewModel.denyPendingPermission() },
        shape = RoomBottomSheetShape
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader(mediaTitle(pending.kinds))
            RequesterBadge(
                label = "Requested by",
                host = requesterHost,
                note = pageHost?.takeIf { it != requesterHost }?.let { "on $it" }
            )
            Spacer(Modifier.height(10.dp))
            pending.kinds.sortedBy { it.ordinal }.forEach { kind ->
                Text(
                    WebPermissions.explanationFor(kind),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.textPrimary
                )
            }
            Spacer(Modifier.height(8.dp))
            PermissionNote(
                "Allowed for this visit only — nothing is remembered, so the " +
                    "next visit asks again. Android may also ask once for the " +
                    "device itself."
            )
            Spacer(Modifier.height(16.dp))
            AllowDenyButtons(
                onAllow = { allow() },
                onDeny = { viewModel.denyPendingPermission() }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Geolocation. Same two-answer shape as the media sheet, minus the per-kind
 * detail: there is one position and one concession.
 *
 * The decision is not retained (`retain = false` upstream), so the sheet is
 * the only place a location grant exists — nothing is stored in the WebView's
 * per-origin store where Settings could neither show nor revoke it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationPermissionSheet(viewModel: BrowserViewModel) {
    val extras = LocalRoomExtras.current
    val context = LocalContext.current
    val pending = viewModel.pendingGeolocation ?: return
    val host = pending.host.ifBlank { pending.origin ?: "this site" }

    // The origin the system dialog was opened FOR — the same guard the media
    // sheet keeps with its request identity, so a second page's request that
    // replaced this one is not answered by a dialog it never prompted.
    var awaitingOrigin by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val target = awaitingOrigin
        awaitingOrigin = null
        val current = viewModel.pendingGeolocation
        if (target == null || current == null || current.origin != target) {
            return@rememberLauncherForActivityResult
        }
        // ANY, not all: on Android 12+ "Approximate" grants coarse WITHOUT
        // fine, and that is a real answer the page can use — requiring both
        // would refuse a user who just gave the site permission to know
        // roughly where they are.
        if (granted.values.any { it }) {
            viewModel.respondGeolocation(allow = true)
        } else {
            viewModel.respondGeolocation(allow = false)
            viewModel.showMessage(
                "Android blocked location — allow it for Room Browser in system settings"
            )
        }
    }

    fun allow() {
        val needed = WebPermissions.androidPermissionsFor(PermissionKind.LOCATION)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            viewModel.respondGeolocation(allow = true)
            return
        }
        awaitingOrigin = pending.origin
        launcher.launch(missing.toTypedArray())
    }

    ModalBottomSheet(
        onDismissRequest = { viewModel.respondGeolocation(allow = false) },
        shape = RoomBottomSheetShape
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Share your location?")
            RequesterBadge(label = "Requested by", host = host, note = null)
            Spacer(Modifier.height(10.dp))
            Text(
                WebPermissions.explanationFor(PermissionKind.LOCATION),
                style = MaterialTheme.typography.bodyMedium,
                color = extras.textPrimary
            )
            Spacer(Modifier.height(8.dp))
            PermissionNote(
                "Allowed for this visit only — nothing is remembered, so the " +
                    "next visit asks again. Android may also ask once for the " +
                    "device's location."
            )
            Spacer(Modifier.height(16.dp))
            AllowDenyButtons(
                onAllow = { allow() },
                onDeny = { viewModel.respondGeolocation(allow = false) }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** "Camera and microphone" / "Camera" — the sheet's title for a request. */
private fun mediaTitle(kinds: Set<PermissionKind>): String {
    val ordered = kinds.sortedBy { it.ordinal }
    if (ordered.isEmpty()) return "Site permission"
    return "Share your " + ordered.joinToString(" and ") { WebPermissions.labelFor(it).lowercase() } + "?"
}

/**
 * Who asked. Deliberately the REQUESTER's origin, with the hosting page named
 * separately when they differ, so a request from a frame is never presented
 * as the site the user is reading.
 */
@Composable
private fun RequesterBadge(label: String, host: String, note: String?) {
    val extras = LocalRoomExtras.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Verified,
            contentDescription = null,
            tint = extras.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = extras.textSecondary
            )
            Text(
                host,
                style = MaterialTheme.typography.titleMedium,
                color = extras.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Quiet explanatory note — the settings screens' InfoNote, inline. */
@Composable
private fun PermissionNote(text: String) {
    val extras = LocalRoomExtras.current
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = extras.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    )
}

/** Allow (primary) / Deny (outlined) — ≥48dp targets. */
@Composable
private fun AllowDenyButtons(onAllow: () -> Unit, onDeny: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onAllow,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) { Text("Allow") }
        OutlinedButton(
            onClick = onDeny,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) { Text("Deny") }
    }
}
