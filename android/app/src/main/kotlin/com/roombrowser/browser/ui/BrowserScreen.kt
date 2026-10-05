package com.roombrowser.browser.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import com.roombrowser.browser.BrowserViewModel
import com.roombrowser.domain.engine.UrlIntelligence
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.qr.QrCodeGenerator
import com.roombrowser.security.BiometricGate
import com.roombrowser.ui.common.LocalRoomExtras
import kotlinx.coroutines.launch

/** Screen routing inside the browser activity. */
sealed interface BrowserRoute {
    data object Browser : BrowserRoute
    data object Tabs : BrowserRoute
    data object Bookmarks : BrowserRoute
    data object History : BrowserRoute
    data object Downloads : BrowserRoute
    data object PrivacyDashboard : BrowserRoute
    data object Settings : BrowserRoute
    data object ProfileSettings : BrowserRoute
    data object About : BrowserRoute
}

/**
 * The route an agent-requested screen name maps to, or null when that name is
 * not one of this activity's screens (the others are activities of their own,
 * started by BrowserViewModel.openScreen). Internal so a unit test can hold it
 * against AgentAppActions — a name that maps to nothing would otherwise be a
 * tool that silently does nothing.
 */
internal fun agentRoute(screen: String): BrowserRoute? = when (screen) {
    "home" -> BrowserRoute.Browser
    "tabs" -> BrowserRoute.Tabs
    "bookmarks" -> BrowserRoute.Bookmarks
    "history" -> BrowserRoute.History
    "downloads" -> BrowserRoute.Downloads
    "privacy" -> BrowserRoute.PrivacyDashboard
    "settings" -> BrowserRoute.Settings
    "profile_settings" -> BrowserRoute.ProfileSettings
    "about" -> BrowserRoute.About
    else -> null
}

/**
 * A request to open [url] in the engine.
 *
 * The nonce is load-bearing. An engine launch can arrive twice with the SAME
 * url — a deep link re-delivered, a share target, `am start` on an already
 * running engine — and on a running engine it arrives at onNewIntent, not
 * onCreate. A bare String compares equal to the previous request, so the
 * effect keyed on it would not re-run and the second launch would be
 * silently ignored; the nonce makes every request a new one.
 */
data class LaunchRequest(val url: String, val nonce: Long)

/**
 * The browser shell: omnibox, toolbar, WebView host, homepage, error
 * pages, IP conflict warning, find-in-page and reader mode.
 */
@Composable
fun BrowserScreen(
    activity: Activity,
    viewModel: BrowserViewModel,
    launchRequest: LaunchRequest?,
    onSwitchProfile: (targetProfileId: ProfileId) -> Unit
) {
    var route by remember { mutableStateOf<BrowserRoute>(BrowserRoute.Browser) }
    var agentPanelExpanded by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val message by viewModel.snackbar.collectAsState()

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.snackbar.value = null
        }
    }

    val agentMessage by viewModel.agent.messages.collectAsState()
    LaunchedEffect(agentMessage) {
        agentMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.agent.messages.value = null
        }
    }

    LaunchedEffect(launchRequest) {
        viewModel.refreshAllProfiles()
        if (launchRequest != null) viewModel.loadUrl(launchRequest.url, newTab = true)
    }

    var showPageActions by remember { mutableStateOf(false) }
    var showQuickSwitcher by remember { mutableStateOf(false) }
    var showShields by remember { mutableStateOf(false) }
    var showFindBar by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    // System-Back exit confirmation — a page with no back history left must
    // NEVER leave the app without an explicit user decision (user mandate:
    // "kalau yang dibuka bukan url dasar jangan keluarkan app, cukup tampilkan
    // konfirmasi dulu").
    var showExitConfirm by remember { mutableStateOf(false) }

    // "Switch Profile" decision from the network warning activity: re-open
    // the quick switcher once the engine resumes (counter, so every new
    // request re-fires the LaunchedEffect).
    val switcherSignal by viewModel.quickSwitcherSignal.collectAsState()
    LaunchedEffect(switcherSignal) {
        if (switcherSignal > 0) showQuickSwitcher = true
    }

    // A screen the agent asked for (app_open). The request is cleared the
    // moment it is applied, so a request that arrives while another screen is
    // already open still lands, and the user's own later navigation is theirs.
    val screenRequest by viewModel.screenRequest.collectAsState()
    LaunchedEffect(screenRequest) {
        val target = screenRequest?.let { agentRoute(it) } ?: return@LaunchedEffect
        route = target
        viewModel.screenRequest.value = null
    }

    // AI settings & chat history live in their OWN activities (default
    // process) — the browser surface simply launches them and, for chat
    // history, receives the picked session back as a result.
    val agentSessionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val sessionId = result.data?.getLongExtra(
            com.roombrowser.agent.ui.AgentSessionsActivity.EXTRA_SESSION_ID, -1L
        ) ?: -1L
        if (sessionId > 0) {
            viewModel.agent.openSession(sessionId)
            agentPanelExpanded = true
        }
    }

    fun launchAgentSettings() {
        com.roombrowser.agent.ui.AgentSettingsActivity.launch(
            activity, viewModel.profileId.value
        )
    }

    fun launchAiTasks() {
        com.roombrowser.agent.ui.AiTasksActivity.launch(activity)
    }

    fun launchAgentSessions() {
        agentSessionsLauncher.launch(
            Intent(activity, com.roombrowser.agent.ui.AgentSessionsActivity::class.java).apply {
                putExtra(
                    com.roombrowser.agent.ui.AgentSessionsActivity.EXTRA_PROFILE_ID,
                    viewModel.profileId.value
                )
            }
        )
    }

    Scaffold(
        // Whole-scaffold background follows the profile theme — the glass
        // bottom bar and every routed screen sit on a cohesive canvas.
        containerColor = LocalRoomExtras.current.background,
        // Keyboard: same semantics as the previous adjustResize window — the
        // whole browser UI (toolbar included) rides above the IME. IME insets
        // are consumed here so the agent composer's imePadding() never
        // double-applies.
        modifier = Modifier.imePadding(),
        // Insets are applied EXPLICITLY (bottomBar + content Box below) —
        // deterministic, no double-counting, on every API level 28..35+.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // HTML5 fullscreen media hides ALL browser chrome: no toolbar
            // competing with the video for the bottom of the screen. With an
            // empty bottomBar the Scaffold's content padding collapses, so
            // the media container below owns the full window height.
            if (!viewModel.isFullscreen) {
                BrowserBottomBar(
                    // THE fix for the 3-button collision: the toolbar is padded
                    // above the system Back / Home / Recents bar (plus display
                    // cutouts in landscape).
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.systemBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                    ),
                    viewModel = viewModel,
                    onOpenTabs = { route = BrowserRoute.Tabs },
                    onOpenAgent = { agentPanelExpanded = true },
                    onShowPageActions = { showPageActions = true }
                )
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Below the status bar / beside cutouts: omnibox, tab strip
                // and every routed screen start INSIDE the safe area — EXCEPT
                // while HTML5 fullscreen media is showing, where the media
                // container must fill the whole window (the system bars
                // themselves are hidden then, see FullscreenMediaHost).
                .windowInsetsPadding(
                    if (viewModel.isFullscreen) {
                        WindowInsets(0, 0, 0, 0)
                    } else {
                        WindowInsets.systemBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    }
                )
        ) {
            when (route) {
                BrowserRoute.Browser -> BrowserContent(
                    viewModel = viewModel,
                    onShowShields = { showShields = true },
                    onOpenPrivacyDashboard = { route = BrowserRoute.PrivacyDashboard }
                )
                BrowserRoute.Tabs -> TabGridScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.Bookmarks -> BookmarksScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.History -> HistoryScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.Downloads -> DownloadsScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.PrivacyDashboard -> PrivacyDashboardScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.Settings -> BrowserSettingsScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Browser })
                BrowserRoute.ProfileSettings -> ProfileSettingsScreen(viewModel = viewModel, onClose = { route = BrowserRoute.Settings })
                BrowserRoute.About -> AboutScreen(onClose = { route = BrowserRoute.Settings })
            }

            // The floating AI agent panel lives above the browsing surface.
            if (route == BrowserRoute.Browser && !viewModel.isFullscreen) {
                com.roombrowser.agent.ui.AgentPanelHost(
                    viewModel = viewModel,
                    expanded = agentPanelExpanded,
                    onExpandedChange = { agentPanelExpanded = it },
                    onOpenSettings = { launchAgentSettings() },
                    onOpenSessions = { launchAgentSessions() }
                )
            }

            // Fullscreen media (HTML5). The ENGINE renders the media inside
            // its own view now — the app is told the state and nothing else —
            // so all that is left here is dropping the chrome and hiding the
            // system bars, which the host below does.
            if (viewModel.isFullscreen) {
                FullscreenMediaHost(activity = activity)
            }
        }
    }

    // ------------------------------------------------------------------
    // System Back button — a browser must NEVER die on the first press.
    // Priority (most specific first):
    //   fullscreen video → reader mode → find-in-page → agent panel →
    //   sub-screen route → web history → exit confirmation → background.
    // ModalBottomSheets/dialogs register their own (later = higher
    // priority) callbacks, so they close themselves before this runs.
    // The web-history branch actually WORKS now: canGoBack is live-tracked
    // via doUpdateVisitedHistory, so Back walks pages like a real browser.
    // When a non-home page has no history left, an explicit confirmation
    // (Exit / Back to start page / Cancel) stands between the user and
    // leaving the app. The homepage keeps the instant-background contract
    // (E2EBrowseFlowTest.system_back_backgrounds_app_without_killing_engine).
    // ------------------------------------------------------------------
    BackHandler {
        when {
            viewModel.isFullscreen -> viewModel.exitFullscreen()
            viewModel.readerContent != null -> viewModel.exitReaderMode()
            showFindBar -> {
                viewModel.clearFindInPage()
                showFindBar = false
            }
            agentPanelExpanded -> agentPanelExpanded = false
            // Sub-screens whose in-app back returns to their PARENT screen
            // (ProfileSettings / About open from the Settings screen) must
            // land in the SAME place from the system Back gesture.
            route == BrowserRoute.ProfileSettings || route == BrowserRoute.About -> route = BrowserRoute.Settings
            route != BrowserRoute.Browser -> route = BrowserRoute.Browser
            viewModel.pageState.canGoBack -> viewModel.goBack()
            !viewModel.pageState.isHomepage -> showExitConfirm = true
            else -> activity.moveTaskToBack(true)   // keep engine + tabs alive
        }
    }

    // ---------- Sheets & dialogs ----------

    if (showPageActions) {
        PageActionsSheet(
            viewModel = viewModel,
            onDismiss = { showPageActions = false },
            onShowFindBar = { showFindBar = true; showPageActions = false },
            onTranslate = { showTranslateDialog = true; showPageActions = false },
            onShowQr = { showQrDialog = true; showPageActions = false },
            onOpenSettings = { route = BrowserRoute.Settings; showPageActions = false },
            onOpenProfileSettings = { route = BrowserRoute.ProfileSettings; showPageActions = false },
            onOpenAbout = { route = BrowserRoute.About; showPageActions = false },
            onOpenAgent = { agentPanelExpanded = true; showPageActions = false },
            onOpenAiTasks = { launchAiTasks(); showPageActions = false },
            onOpenAgentSettings = { launchAgentSettings(); showPageActions = false },
            onOpenAgentSessions = { launchAgentSessions(); showPageActions = false },
            onOpenBookmarks = { route = BrowserRoute.Bookmarks; showPageActions = false },
            onOpenDownloads = { route = BrowserRoute.Downloads; showPageActions = false },
            onOpenHistory = { route = BrowserRoute.History; showPageActions = false },
            onShowQuickSwitcher = { showQuickSwitcher = true; showPageActions = false },
            onShowShields = { showShields = true; showPageActions = false }
        )
    }

    if (showQuickSwitcher) {
        ProfileQuickSwitcherSheet(
            viewModel = viewModel,
            onDismiss = { showQuickSwitcher = false },
            onSwitch = { target ->
                showQuickSwitcher = false
                onSwitchProfile(target)
            }
        )
    }

    if (showShields) {
        ShieldsSheet(
            viewModel = viewModel,
            onDismiss = { showShields = false }
        )
    }

    // The one sheet NOT driven by a local boolean: the PAGE decides when a
    // permission request arrives, so it is rendered from viewModel state.
    // Gated on the browsing route — a background tab's request is already
    // refused upstream, and raising a camera prompt over the bookmarks list
    // would ask about a page the user cannot see. It stays pending until they
    // come back, rather than being lost.
    if (route == BrowserRoute.Browser) {
        SitePermissionHost(viewModel = viewModel)
    }

    // HTTP Basic/Digest auth challenge. NOT gated on the route: the WebView
    // holds the whole navigation open until the handler is answered, so a
    // challenge that is merely deferred reads as a page that never loads.
    // Only ONE can be outstanding at a time (the ViewModel cancels the rest),
    // which is also why the dialog cannot be dismissed into limbo — every
    // exit path settles the handler.
    viewModel.pendingHttpAuth?.let { challenge ->
        HttpAuthDialog(
            host = challenge.host,
            realm = challenge.realm,
            onSignIn = { user, password -> viewModel.submitHttpAuth(user, password) },
            onCancel = { viewModel.dismissHttpAuth() }
        )
    }

    if (showFindBar) {
        FindInPageBar(
            onFind = { viewModel.findInPage(it) },
            onNext = { viewModel.findInPageNavigate(true, it) },
            onPrevious = { viewModel.findInPageNavigate(false, it) },
            onClose = {
                viewModel.clearFindInPage()
                showFindBar = false
            }
        )
    }

    if (showTranslateDialog) {
        TranslateDialog(
            viewModel = viewModel,
            onDismiss = { showTranslateDialog = false }
        )
    }

    if (showQrDialog) {
        QrShareDialog(
            content = viewModel.pageState.url,
            onDismiss = { showQrDialog = false }
        )
    }

    // ---------- Password vault sheets (render points only) ----------
    // The offer appears when the user focuses a login form on a page whose
    // host family has saved logins (vault unlocked for the session) — or in
    // its LOCKED variant when the vault is still locked, in which case the
    // only action is the user's explicit unlock (never an automatic prompt).
    // The save prompt appears after a login form submits — including while
    // the vault is LOCKED; the biometric gate for "Save"/"Unlock" needs an
    // Activity, which the ViewModel does not have, so those actions borrow
    // this one through a callback (never started from recomposition).
    viewModel.vaultOffer?.let { offer ->
        VaultOfferSheet(
            host = offer.host,
            credentials = offer.credentials,
            locked = offer.locked,
            onPick = { viewModel.fillVaultCredential(it) },
            onUnlock = {
                viewModel.unlockVaultForOffer { onSuccess, onFailure ->
                    val fragmentActivity = activity as? FragmentActivity
                    if (fragmentActivity != null) {
                        BiometricGate.unlock(fragmentActivity, "Password vault", onSuccess, onFailure)
                    } else {
                        // No fragment host = no biometric prompt = no unlock.
                        onFailure()
                    }
                }
            },
            onDismiss = { viewModel.dismissVaultOffer() }
        )
    }

    viewModel.vaultSavePrompt?.let { prompt ->
        VaultSaveSheet(
            host = prompt.host,
            username = prompt.username,
            onSave = {
                viewModel.savePromptedLogin { onSuccess, onFailure ->
                    val fragmentActivity = activity as? FragmentActivity
                    if (fragmentActivity != null) {
                        BiometricGate.unlock(fragmentActivity, "Password vault", onSuccess, onFailure)
                    } else {
                        // No fragment host = no biometric prompt = no unlock.
                        onFailure()
                    }
                }
            },
            onNotNow = { viewModel.dismissVaultSavePrompt() }
        )
    }

    // ---------- Wallet dApp confirmation sheet (render point only) ------
    // The engine (bound to this profile, shared with the wallet dashboard)
    // owns the pending-request queue; the FIRST pending request renders as
    // a confirmation sheet. The sheet itself settles the request through
    // engine.decideDappRequest (Approve or Reject — outside-tap/back =
    // reject), after which it leaves composition on its own; onDismiss is
    // deliberately empty for that reason. The queue is read through the
    // ViewModel's MIRROR flow (walletRequests) — reading the engine's own
    // flow here would lazy-load the crypto stack during first composition
    // and stall the first frames; the engine reference is only resolved
    // inside the non-empty branch, i.e. strictly after the deferred bind.
    val pendingWalletRequests by viewModel.walletRequests.collectAsState()
    pendingWalletRequests.firstOrNull()?.let { request ->
        WalletDappRequestSheet(
            request = request,
            engine = viewModel.walletEngine,
            onDismiss = { }
        )
    }

    // ---------- System-Back exit confirmation (non-home, no history) ------
    // Fired by the BackHandler's `!isHomepage` branch: the current page has
    // no back history left, so leaving the app requires an EXPLICIT choice.
    // "Exit" keeps the engine + tabs alive via moveTaskToBack (same contract
    // as the homepage back), "Back to start page" returns to about:home with
    // a clean per-tab history, "Cancel" (or outside-tap) simply stays.
    if (showExitConfirm) {
        val page = viewModel.pageState
        val host = UrlIntelligence.hostOf(page.url)?.let { "You are viewing $it." } ?: ""
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("Exit Room Browser?") },
            text = {
                Text(
                    (if (host.isBlank()) "" else "$host ") +
                        "This page has no back history left. Your tabs and the engine stay alive in the background."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirm = false
                        activity.moveTaskToBack(true)
                    }
                ) { Text("Exit") }
            },
            dismissButton = {
                Column {
                    TextButton(
                        onClick = {
                            showExitConfirm = false
                            viewModel.goHome()
                        }
                    ) { Text("Back to start page") }
                    TextButton(onClick = { showExitConfirm = false }) { Text("Cancel") }
                }
            }
        )
    }

    viewModel.readerContent?.let { reader ->
        ReaderScreen(
            content = reader,
            onClose = { viewModel.exitReaderMode() }
        )
    }
    // NOTE: the profile network warning is NOT a dialog anymore — a pending
    // decision launches the full-screen NetworkWarningActivity (BrowserActivity
    // owns the launch loop; while the gate stands no URL can load).
}

/**
 * The HTTP Basic/Digest credential prompt.
 *
 * Every way out of this dialog settles the WebView's auth handler, because
 * an unsettled handler is a navigation that hangs forever with no error page
 * and no spinner: Sign in proceeds, Cancel and an outside tap/system Back
 * both cancel. The realm is shown verbatim — it is attacker-controlled text
 * from the server, so it is rendered as content below the host, never as the
 * title, and the host (which the user can trust) is what the title asserts.
 *
 * The fields are deliberately NOT prefilled from the vault: WebView's
 * built-in credential store is per-origin and silently reusable, and the
 * audit's scope was making the challenge answerable at all.
 */
@Composable
private fun HttpAuthDialog(
    host: String,
    realm: String,
    onSignIn: (String, String) -> Unit,
    onCancel: () -> Unit
) {
    var user by remember(host, realm) { mutableStateOf("") }
    var password by remember(host, realm) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Sign in to $host") },
        text = {
            Column(
                modifier = Modifier.imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (realm.isNotBlank()) {
                    Text(
                        text = realm,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    singleLine = true,
                    label = { Text("Username") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "httpauth_user" }
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Password") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "httpauth_password" }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSignIn(user, password) },
                enabled = user.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = "httpauth_submit" }
            ) { Text("Sign in") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}

/**
 * The app's side of HTML5 fullscreen media: the scaffold above drops its
 * bottom bar and its status-bar/cutout padding while this is composed, and the
 * system status/navigation bars are hidden through
 * [WindowInsetsControllerCompat].
 *
 * NO VIEW IS HOSTED HERE ANY MORE. The WebView edition was handed the
 * fullscreen view by `onShowCustomView` and had to render it edge-to-edge
 * itself (and `FullscreenMediaHost` did exactly that). The facade hands the
 * app a STATE instead — the engine puts the fullscreen content inside its own
 * view — so the container, its release path and the "child already has a
 * parent" hazard it guarded against are all gone.
 *
 * The previous [WindowInsetsControllerCompat.getSystemBarsBehavior] is
 * captured before hiding and restored on exit; the restore also runs from
 * `onDispose`, so leaving the screen or the activity being recreated while
 * fullscreen can never strand the app with hidden system bars.
 */
@Composable
private fun FullscreenMediaHost(activity: Activity) {
    val window = activity.window
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousBehavior = controller.systemBarsBehavior
        // Swipe reveals the bars transiently instead of pinning them back on.
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
        }
    }
}
