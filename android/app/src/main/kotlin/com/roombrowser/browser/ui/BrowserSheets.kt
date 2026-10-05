package com.roombrowser.browser.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SafetyCheck
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.domain.model.LanguagePresets
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.qr.QrCodeGenerator
import com.roombrowser.ui.common.GlassBar
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.ProfileAvatar
import com.roombrowser.ui.common.RoomBottomSheetShape
import com.roombrowser.ui.common.RoomSheetHeader
import kotlinx.coroutines.launch

/**
 * Floating browser bottom toolbar (glass bar, themed navBar color,
 * tab-count badge) + the redesigned action sheets. All colors follow the
 * per-profile theme.
 *
 * Brave-style navigation bar: Back / Forward / Refresh / Tabs / Wallet /
 * More — 6 × 48dp touch targets (288dp) + 2×10dp outer + 2×6dp inner
 * padding = exactly 320dp, the smallest common screen width; 360dp
 * screens get comfortable ~8dp gaps between buttons. Bookmarks and
 * profile switching moved into the Page Actions sheet so the bar stays
 * lean while the omnibox above reclaims the width the nav arrows used
 * to eat.
 *
 * The refresh slot doubles as a STOP control while a page is loading
 * (the standard browser pattern), and greys out on the start page where
 * there is nothing to reload.
 *
 * IT IS ALSO THE AGENT'S SLOT WHEN THE PILL IS OFF. With "Show AI Agent
 * button" switched off there is no floating pill, and the agent would have
 * no entry point inside the page at all — so this one slot becomes the way
 * in, carrying the pill's own icon. Reload is not lost with it: the omnibox
 * row above has carried the same Reload / Stop control all along, so the
 * only thing that moves is which thumb reaches it. The swap follows the
 * setting alone rather than the pill's live visibility (`showAgentButton ||
 * running`), because a slot that changes identity mid-task is worse than one
 * that is predictable.
 */
@Composable
fun BrowserBottomBar(
    viewModel: BrowserViewModel,
    onOpenTabs: () -> Unit,
    onOpenAgent: () -> Unit,
    onShowPageActions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val extras = LocalRoomExtras.current
    val page = viewModel.pageState
    GlassBar(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Web-history back — mirrors the system Back gesture.
            IconButton(
                onClick = { viewModel.goBack() },
                enabled = page.canGoBack,
                modifier = Modifier.semantics { contentDescription = "Go back" }
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = if (page.canGoBack) extras.icon else extras.icon.copy(alpha = 0.35f)
                )
            }
            // Web-history forward.
            IconButton(
                onClick = { viewModel.goForward() },
                enabled = page.canGoForward,
                modifier = Modifier.semantics { contentDescription = "Go forward" }
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = if (page.canGoForward) extras.icon else extras.icon.copy(alpha = 0.35f)
                )
            }
            // Refresh — becomes Stop while a page is loading; disabled on
            // the start page (nothing to reload there). It yields the slot to
            // the agent whenever the floating pill is switched off (see the
            // header comment): one entry point has to exist, and the omnibox
            // row already carries an identical Reload / Stop.
            if (viewModel.agent.settings.showAgentButton) {
                IconButton(
                    onClick = { if (page.loading) viewModel.stopLoading() else viewModel.reload() },
                    enabled = !page.isHomepage,
                    modifier = Modifier.semantics {
                        contentDescription = if (page.loading) "Stop loading" else "Reload page"
                    }
                ) {
                    Icon(
                        if (page.loading) Icons.Filled.Close else Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = if (page.isHomepage) extras.icon.copy(alpha = 0.35f) else extras.icon
                    )
                }
            } else {
                IconButton(
                    onClick = onOpenAgent,
                    modifier = Modifier.semantics { contentDescription = "Open Room Agent" }
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = extras.icon
                    )
                }
            }
            // Tabs with live count badge
            Box {
                IconButton(
                    onClick = onOpenTabs,
                    modifier = Modifier.semantics { contentDescription = "Open tab grid" }
                ) {
                    Icon(Icons.Filled.Tab, contentDescription = null, tint = extras.icon)
                }
                if (viewModel.tabs.isNotEmpty()) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp, end = 4.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(extras.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            viewModel.tabs.size.coerceAtMost(99).toString(),
                            color = extras.onButton,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            // The wallet, which used to be buried under Settings. Sharing did
            // not lose its slot: the Page Actions sheet still has a Share row,
            // and a bar this wide has to spend its six targets on the things a
            // user reaches for mid-page rather than on one already one tap
            // away. The wallet has no other entry point.
            IconButton(
                onClick = {
                    WalletActivity.launch(
                        context,
                        profileId = viewModel.profileId.value,
                        profileName = viewModel.profile.name
                    )
                },
                modifier = Modifier.semantics { contentDescription = "Wallet" }
            ) {
                Icon(
                    Icons.Filled.AccountBalanceWallet,
                    contentDescription = null,
                    tint = extras.icon
                )
            }
            IconButton(
                onClick = onShowPageActions,
                modifier = Modifier.semantics { contentDescription = "Page actions and settings" }
            ) {
                Icon(Icons.Filled.MoreHoriz, contentDescription = null, tint = extras.icon)
            }
        }
    }
}

/** Page actions sheet (long-press / menu per spec section 37). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageActionsSheet(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit,
    onShowFindBar: () -> Unit,
    onTranslate: () -> Unit,
    onShowQr: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfileSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenAiTasks: () -> Unit,
    onOpenAgentSettings: () -> Unit,
    onOpenAgentSessions: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenNotes: () -> Unit,
    onShowQuickSwitcher: () -> Unit,
    onShowShields: () -> Unit = {}
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Page Actions")
            SheetAction(Icons.AutoMirrored.Filled.ArrowBack, "Back") { viewModel.goBack(); onDismiss() }
            SheetAction(Icons.AutoMirrored.Filled.ArrowForward, "Forward") { viewModel.goForward(); onDismiss() }
            SheetAction(Icons.Filled.Add, "New tab") { viewModel.loadUrl("about:home", newTab = true); onDismiss() }
            SheetAction(Icons.Filled.Lock, "New private tab") { viewModel.startPrivateTab(); onDismiss() }
            SheetAction(Icons.Filled.Description, "Notes") { onOpenNotes() }
            SheetAction(Icons.Filled.SafetyCheck, "Shields") { onDismiss(); onShowShields() }
            SheetAction(Icons.Filled.FindInPage, "Find in page") { onShowFindBar() }
            SheetAction(Icons.Filled.Language, "Translate") { onTranslate() }
            SheetAction(Icons.Filled.DesktopWindows, if (viewModel.pageState.desktopMode) "Desktop site: ON" else "Desktop site: OFF") {
                viewModel.toggleDesktopMode(); onDismiss()
            }
            SheetAction(Icons.Filled.MenuBook, "Reader mode") { viewModel.enterReaderMode(); onDismiss() }
            SheetAction(
                Icons.Filled.Star,
                if (viewModel.bookmarks.any { it.url == viewModel.pageState.url }) "Remove bookmark" else "Add bookmark"
            ) { viewModel.toggleBookmark(); onDismiss() }
            SheetAction(Icons.Filled.PictureAsPdf, "Save page as PDF") {
                // KNOWN GAP, REPORTED RATHER THAN FAKED. Printing needs a
                // `PrintDocumentAdapter`, and only a WebView can build one
                // (`createPrintDocumentAdapter`). The facade exposes neither
                // that nor anything like it, and rendering the page above the
                // boundary would be a different feature wearing this button's
                // name — so the action says what happened instead of silently
                // doing nothing.
                viewModel.showMessage("Save as PDF is not supported by this engine yet")
                onDismiss()
            }
            SheetAction(Icons.Filled.QrCodeScanner, "QR: share this page as code") { onShowQr() }
            SheetAction(Icons.Filled.Share, "Share") {
                val url = viewModel.pageState.url
                if (url != "about:home") {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(Intent.createChooser(share, "Share link"))
                }
                onDismiss()
            }
            SheetAction(Icons.Filled.Add, "Add to Home screen") {
                addShortcutToHomeScreen(context, viewModel)
                onDismiss()
            }
            SheetAction(Icons.Filled.StarBorder, "Bookmarks") { onOpenBookmarks(); onDismiss() }
            SheetAction(Icons.Filled.Download, "Downloads") { onOpenDownloads(); onDismiss() }
            SheetAction(Icons.Filled.History, "History") { onOpenHistory(); onDismiss() }

            SheetSectionLabel("Appearance")
            SheetAction(Icons.Filled.Palette, "Theme studio") {
                com.roombrowser.theme.ui.ThemeStudioActivity.launch(
                    context, viewModel.profileId.value
                )
                onDismiss()
            }

            SheetSectionLabel("AI Agent")
            SheetAction(Icons.Filled.AutoAwesome, "AI Agents") { onOpenAgent() }
            SheetAction(Icons.Filled.Schedule, "AI Tasks") { onOpenAiTasks() }
            // The parenthetical "(providers & models)" is gone on purpose:
            // this row sits directly under "AI Agents" in the same section,
            // so the suffix was repeating the section, widening the row and
            // wrapping the label on a narrow screen — for no information.
            SheetAction(Icons.Filled.SmartToy, "AI Agent Settings") { onOpenAgentSettings() }
            SheetAction(Icons.Filled.History, "AI Agent chats") { onOpenAgentSessions() }

            SheetSectionLabel("Settings")
            SheetAction(Icons.Filled.Settings, "Browser settings") { onOpenSettings() }
            SheetAction(Icons.Filled.Person, "Profile settings") { onOpenProfileSettings() }
            SheetAction(Icons.Filled.SwapHoriz, "Switch profile") { onShowQuickSwitcher(); onDismiss() }
            SheetAction(Icons.Filled.Info, "About Room Browser") { onOpenAbout() }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Real pinned-shortcut request via ShortcutManager (API 26+). */
private fun addShortcutToHomeScreen(context: android.content.Context, viewModel: BrowserViewModel) {
    runCatching {
        val url = viewModel.pageState.url
        if (url == "about:home") return
        val shortcutManager = context.getSystemService(android.content.pm.ShortcutManager::class.java)
        if (shortcutManager?.isRequestPinShortcutSupported == true) {
            val intent = Intent(context, com.roombrowser.browser.BrowserActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(com.roombrowser.browser.BrowserActivity.EXTRA_PROFILE_ID, viewModel.profileId.value)
                putExtra(com.roombrowser.browser.BrowserActivity.EXTRA_INITIAL_URL, url)
            }
            val info = android.content.pm.ShortcutInfo.Builder(context, "site-${url.hashCode()}")
                .setShortLabel(viewModel.pageState.title.ifBlank { url }.take(10))
                .setIntent(intent)
                .build()
            shortcutManager.requestPinShortcut(info, null)
        }
    }
}

@Composable
private fun SheetSectionLabel(text: String) {
    val extras = LocalRoomExtras.current
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = extras.primary,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape((extras.radius * 0.7f).dp))
            .clickable(onClick = onClick)
            // Addressable + announced as one action (TalkBack reads the
            // label instead of raw child texts; UI tests target the row
            // itself, which carries the click action).
            .semantics { contentDescription = label }
            .padding(horizontal = 8.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(extras.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = extras.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = extras.textPrimary)
    }
}

/** Profile quick switcher (spec section 5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileQuickSwitcherSheet(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit,
    onSwitch: (ProfileId) -> Unit
) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()
    var showCreateDialog by remember { mutableStateOf(false) }
    // In-flight flag for the create. It lives HERE and not in the dialog
    // because this is where the launch and both of its outcomes are: a
    // failure has to re-arm the button so the user can fix the name and try
    // again, and only this scope knows when that happened.
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Switch Profile")
            Text(
                "Switching closes the current browsing context completely before opening the next profile.",
                style = MaterialTheme.typography.bodyMedium,
                color = extras.textSecondary
            )
            Spacer(Modifier.height(12.dp))
            viewModel.allProfiles.forEach { profile ->
                val current = profile.id == viewModel.profileId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape((extras.radius * 0.7f).dp))
                        .clickable(enabled = !current) { onSwitch(profile.id) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfileAvatar(profile.icon, profile.colorArgb, size = 38)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile.name + if (current) "  (current)" else "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (current) extras.primary else extras.textPrimary
                        )
                        Text(
                            "Switch to this profile",
                            style = MaterialTheme.typography.labelMedium,
                            color = extras.textSecondary
                        )
                    }
                }
            }
            // ---- Create New Profile (always the LAST action) ----------------
            // Create-then-switch: the new profile row exists (and the dialog
            // is closed) BEFORE onSwitch runs the profile-switch executor —
            // the current session is never torn down for a profile that
            // failed to materialize. On failure the dialog stays and a
            // snackbar explains.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape((extras.radius * 0.7f).dp))
                    .clickable { showCreateDialog = true }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(extras.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = extras.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Create New Profile",
                        style = MaterialTheme.typography.bodyLarge,
                        color = extras.textPrimary
                    )
                    Text(
                        "Add another profile and switch to it",
                        style = MaterialTheme.typography.labelMedium,
                        color = extras.textSecondary
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showCreateDialog) {
        QuickCreateProfileDialog(
            initialName = viewModel.suggestedProfileName(),
            creating = creating,
            onDismiss = { showCreateDialog = false; creating = false },
            onCreate = { name ->
                // Second guard behind the button's own `enabled`: a tap that
                // was already in flight when the recomposition landed must
                // not start a SECOND createProfileFromSwitcher. The
                // duplicate-name check inside it is not transactional, so two
                // concurrent creates can both pass it and leave two
                // identically named profiles racing the switch below.
                if (!creating) {
                    creating = true
                    scope.launch {
                        runCatching { viewModel.createProfileFromSwitcher(name) }
                            .onSuccess { created ->
                                creating = false
                                showCreateDialog = false
                                // EXISTING switch path — BrowserActivity.switchProfile
                                // runs the 7-step process-restart protocol.
                                onSwitch(created.id)
                            }
                            .onFailure { failure ->
                                // Snackbar + stay: the dialog remains open, the
                                // current profile/session is untouched, and the
                                // button re-arms for another attempt.
                                creating = false
                                viewModel.postMessage(failure.message ?: "Could not create profile")
                            }
                    }
                }
            }
        )
    }
}

/**
 * Minimal create dialog for the quick switcher: a name field only — icon and
 * color are picked automatically (the full editor lives on the main screen).
 *
 * [creating] is the owner's in-flight flag: the dialog stays on screen until
 * the suspend create returns, so the button has to go dead for that window.
 */
@Composable
private fun QuickCreateProfileDialog(
    initialName: String,
    creating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    val blank = name.isBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Profile") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                // The blank case used to be invisible: the button looked
                // armed and did nothing. Say so on the field instead.
                isError = blank,
                // The slot is passed unconditionally (empty when the name is
                // fine) rather than as a null: Material reserves the
                // supporting-text row for as long as the slot exists, so the
                // dialog keeps one height and the Create button does not jump
                // under the user's thumb the moment they type a first letter.
                supportingText = {
                    if (blank) {
                        Text("Enter a name for the new profile.")
                    }
                }
            )
        },
        confirmButton = {
            // Was `onClick = { if (name.isNotBlank()) onCreate(name.trim()) }`
            // with no `enabled`: a blank name made the button a silent no-op,
            // and because the dialog only closes once the suspend create has
            // returned, a second tap launched a second creation. Both guards
            // belong in `enabled`, where they are also visible to the user.
            Button(
                enabled = !blank && !creating,
                onClick = { onCreate(name.trim()) }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Site privacy panel (spec section 11). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShieldsSheet(viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    val shields = viewModel.shieldsState
    val extras = LocalRoomExtras.current
    var confirmClearSiteData by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader(shields.host.ifBlank { "Privacy Shield" })
            com.roombrowser.ui.common.RoomCard {
                Column(Modifier.padding(14.dp)) {
                    ShieldStat("Ads blocked", shields.adsBlocked)
                    ShieldStat("Trackers blocked", shields.trackersBlocked)
                    ShieldStat("HTTPS upgrades", shields.httpsUpgrades)
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { viewModel.toggleShieldsForSite(!shields.shieldsDisabled) }) {
                Text(if (shields.shieldsDisabled) "Enable protection for this site" else "Disable protection for this site")
            }
            // Sitting in a sheet titled with the current host, next to two
            // rows that really are per-site, this row read as "clear THIS
            // site's data". It never was: clearSiteDataForCurrentSite() ends
            // in ProfileEngine.clearEngineStorage(), which calls
            // removeAllCookies + WebStorage.deleteAllData +
            // clearHttpAuthUsernamePassword + clearFormData for the whole
            // profile — WebView has no per-origin equivalent (see
            // PROFILE_ISOLATION.md). The label itself is load-bearing
            // elsewhere so it stays; the line under it and the confirmation
            // are where the real scope is now stated, and a wipe that signs
            // the user out of every site must not fire on one tap.
            TextButton(onClick = { confirmClearSiteData = true }) {
                Text("Clear site data")
            }
            Text(
                "Clears cookies, local storage, saved form data and cached files for EVERY site in this profile — WebView cannot narrow this to one host, so you are signed out everywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = extras.textSecondary,
                modifier = Modifier.padding(start = 12.dp, end = 8.dp, bottom = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "JavaScript: ${if (viewModel.profileSettings().javascriptEnabled) "allowed by profile settings" else "blocked by profile settings"}",
                style = MaterialTheme.typography.bodyMedium,
                color = extras.textSecondary
            )
            TextButton(onClick = {
                viewModel.setSiteSetting { it.copy(jsEnabled = !(it.jsEnabled ?: viewModel.profileSettings().javascriptEnabled)) }
            }) { Text("Toggle JavaScript for this site") }
            TextButton(onClick = {
                viewModel.setSiteSetting { it.copy(cookiesBlocked = !(it.cookiesBlocked ?: false)) }
            }) { Text("Toggle cookies for this site") }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmClearSiteData) {
        com.roombrowser.main.ui.ConfirmDialog(
            title = "Clear data for every site?",
            text = "This signs you out of all sites in this profile, not only " +
                "${shields.host.ifBlank { "this one" }}: every cookie, local " +
                "storage entry, saved form entry and stored HTTP credential " +
                "goes. Bookmarks and history are untouched. It cannot be undone.",
            // NOT "Clear site data": that exact string is the sheet's own
            // button, and a distinct confirm label keeps the two separately
            // addressable for anything matching on text.
            confirmLabel = "Clear all site data",
            onDismiss = { confirmClearSiteData = false },
            onConfirm = {
                viewModel.clearSiteDataForCurrentSite()
                confirmClearSiteData = false
            }
        )
    }
}

@Composable
private fun ShieldStat(label: String, value: Int) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = extras.textSecondary, modifier = Modifier.weight(1f))
        Text("$value", style = MaterialTheme.typography.titleMedium, color = extras.primary)
    }
}

/** Find-in-page bar. */
@Composable
fun FindInPageBar(
    onFind: (String) -> Unit,
    onNext: (String) -> Unit,
    onPrevious: (String) -> Unit,
    onClose: () -> Unit
) {
    val extras = LocalRoomExtras.current
    var query by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            // Rendered at window top level (over the browser shell): stay
            // below the status bar and beside display cutouts.
            .windowInsetsPadding(
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .background(extras.surface.copy(alpha = 0.97f))
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onFind(it)
                },
                placeholder = { Text("Find in page") },
                singleLine = true,
                shape = RoundedCornerShape((extras.radius * 0.75f).dp),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onPrevious(query) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous match", tint = extras.icon) }
            IconButton(onClick = { onNext(query) }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match", tint = extras.icon) }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close find bar", tint = extras.icon) }
        }
    }
}

/** Translate dialog — opens the Google Translate wrapper in a new tab. */
@Composable
fun TranslateDialog(viewModel: BrowserViewModel, onDismiss: () -> Unit) {
    var target by remember { mutableStateOf(viewModel.profileSettings().translateTargetLanguage) }
    // The field used to be spliced straight into the Translate URL, so an
    // unknown code, an empty one, or anything containing a URL metacharacter
    // went to Google verbatim. LanguagePresets.isSupported is the same
    // validator the settings picker uses: it accepts a curated preset or any
    // well-formed BCP-47-ish tag, and rejects blank and malformed input —
    // its own charset (alphanumerics and hyphens) is what makes the URL safe,
    // and the code is percent-encoded below as well so a future loosening of
    // that regex cannot turn this into parameter injection.
    val code = target.trim()
    val valid = LanguagePresets.isSupported(code)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Translate this page?") },
        text = {
            Column {
                Text("Uses Google Translate's web wrapper. Some sites may not work.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Target language code (e.g. id, en, ja)") },
                    singleLine = true,
                    isError = target.isNotEmpty() && !valid,
                    supportingText = {
                        if (target.isNotEmpty() && !valid) {
                            Text("Not a recognised language code.")
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    val url = viewModel.pageState.url
                    if (url != "about:home") {
                        val encoded = java.net.URLEncoder.encode(url, "UTF-8")
                        val tl = java.net.URLEncoder.encode(code, "UTF-8")
                        viewModel.loadUrl(
                            "https://translate.google.com/translate?sl=auto&tl=$tl&u=$encoded",
                            newTab = true
                        )
                    }
                    onDismiss()
                }
            ) { Text("Translate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** QR share dialog — shows a generated QR for the current page. */
@Composable
fun QrShareDialog(content: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("QR Code") },
        text = {
            // An AlertDialog body does not scroll on its own. In landscape,
            // or at a large font scale, the dialog is capped well below the
            // 240dp code plus its title and button row — the bottom of the
            // code was simply clipped off with no way to reach it.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (content != "about:home") {
                    AndroidView(
                        factory = { ctx ->
                            android.widget.ImageView(ctx).apply {
                                setImageBitmap(QrCodeGenerator.generate(content, 512))
                            }
                        },
                        modifier = Modifier.size(240.dp)
                    )
                } else {
                    Text("Open a page first, then share it as a QR code.")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Reader mode screen with typography controls. */
@Composable
fun ReaderScreen(
    content: BrowserViewModel.ReaderContent,
    onClose: () -> Unit
) {
    val extras = LocalRoomExtras.current
    var fontSize by remember { mutableFloatStateOf(16f) }
    // The reading surface used to be two hardcoded hexes (#FCF8F0 / #101014)
    // with `dark` starting at false, so reader mode opened bright cream on a
    // dark device and ignored the profile's Theme Studio spec entirely —
    // while the control row below it, styled from the real scheme, sat on
    // that foreign background. `dark` is now an OVERRIDE of the theme's own
    // mode rather than an absolute: left alone the page is the themed
    // background, flipped it is the scheme's inverseSurface, which Theme.kt
    // populates as the opposite-luminance surface of whatever palette is
    // active. Either way the colors come from the spec.
    var dark by remember { mutableStateOf(extras.dark) }
    var lineSpacing by remember { mutableFloatStateOf(1.4f) }
    val inverted = dark != extras.dark
    val pageColor = if (inverted) MaterialTheme.colorScheme.inverseSurface else extras.background
    val inkColor = if (inverted) MaterialTheme.colorScheme.inverseOnSurface else extras.textPrimary
    // No inverse counterpart exists for the secondary text role, so the
    // byline is derived from the ink it sits next to instead of the flat
    // Color.Gray it used to be — that gray was unreadable on a dark page.
    val bylineColor = if (inverted) inkColor.copy(alpha = 0.72f) else extras.textSecondary
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxSize()
            .background(pageColor)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout)
                )
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                content.title,
                style = MaterialTheme.typography.headlineSmall,
                color = inkColor
            )
            if (content.byline.isNotBlank()) {
                Text(
                    content.byline,
                    style = MaterialTheme.typography.labelMedium,
                    color = bylineColor
                )
            }
            Spacer(Modifier.height(12.dp))
            // Rendered as pre-formatted text: the extraction strips scripts/styles
            Text(
                text = content.html.replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s{2,}"), " ")
                    .trim(),
                fontSize = fontSize.sp,
                lineHeight = (fontSize * lineSpacing).sp,
                color = inkColor
            )
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .padding(12.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { if (fontSize > 12f) fontSize -= 2f }) { Text("A-") }
            OutlinedButton(onClick = { if (fontSize < 28f) fontSize += 2f }) { Text("A+") }
            OutlinedButton(onClick = { dark = !dark }) { Text(if (dark) "Light" else "Dark") }
            OutlinedButton(onClick = { lineSpacing = if (lineSpacing < 1.8f) 1.8f else 1.4f }) { Text("Spacing") }
            Button(onClick = onClose) { Text("Exit") }
        }
    }
}
