package com.roombrowser.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.roombrowser.RoomBrowserApp
import com.roombrowser.browser.wallet.WalletActivityRecord
import com.roombrowser.browser.wallet.WalletAccountRecord
import com.roombrowser.browser.wallet.WalletEngineApi
import com.roombrowser.browser.wallet.WalletLockState
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.security.PinLockCrypto
import com.roombrowser.domain.security.WalletLockStatus
import com.roombrowser.domain.theme.BuiltInThemes
import com.roombrowser.domain.wallet.model.BalanceResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.security.BiometricGate
import com.roombrowser.security.PinUnlockResult
import com.roombrowser.security.WalletLockManager
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.ui.common.RoomCard
import com.roombrowser.ui.common.RoomCardShape
import com.roombrowser.ui.common.SectionHeader
import com.roombrowser.ui.common.SettingActionRow
import com.roombrowser.ui.common.SettingsGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Wallet dashboard — the per-profile multi-chain wallet UI.
 *
 * PROCESS (critical): declared android:process=":browser" on purpose. The
 * wallet engine's session (lock state, accounts, pending dApp requests) is
 * per-process state in the AppGraph of the ':browser' process — running
 * here means this screen shares the engine with the browsing surface and
 * the dApp bridge: unlocking here unlocks the in-page wallet, and decisions
 * made here settle the page's pending request. It never hosts a WebView.
 *
 * ENGINE BINDING: the activity binds the engine to its profile on entry
 * ([WalletEngineApi.bind] is idempotent) and does NOT unbind — the engine
 * is the process-wide session shared with the browser; profile switches
 * restart the ':browser' process, which is the session's teardown path.
 *
 * UNLOCK GATE: a LOCKED wallet triggers the biometric / device-credential
 * gate ONCE on entry ("Wallet"). Failure or a device without any credential
 * keeps the locked pane with a manual "Unlock" retry — wallet CONTENTS are
 * never rendered while locked (PasswordsActivity pattern). Onboarding
 * (NO_WALLET) needs no gate: the wallet does not exist yet, and the
 * create-flow's reveal screen shows the phrase the user just generated in
 * this same session.
 *
 * STATE: the engine IS the state holder — every StateFlow is collected
 * directly here (no second ViewModel). All actions go through
 * [WalletEngineApi] and reflect immediately through those flows.
 */
class WalletActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: insets are consumed by the Compose UI below — nothing
        // ever overlaps the status bar, cutouts or the navigation buttons.
        enableEdgeToEdge()
        val profileIdValue = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileIdValue.isNullOrBlank()) {
            // No profile to show a wallet for — nothing to do here.
            finish()
            return
        }
        val profileId = ProfileId(profileIdValue)
        val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty()
        val graph = (application as RoomBrowserApp).graph
        val engine = graph.walletEngine
        engine.bind(profileId)
        val biometricsAvailable = BiometricGate.canAuthenticate(this)
        setContent {
            // Wear this profile's own theme (snapshot loaded once, exactly
            // like PasswordsActivity — live re-theming belongs to the
            // browsing surface).
            var spec by remember { mutableStateOf(BuiltInThemes.default()) }
            LaunchedEffect(Unit) {
                runCatching { graph.profileRepo.getProfile(profileId) }.getOrNull()?.let { profile ->
                    spec = BuiltInThemes.resolveOrDefault(profile.themeJson)
                }
            }
            RoomBrowserTheme(spec = spec) {
                WalletRoot(
                    engine = engine,
                    walletLock = graph.walletLock,
                    profileId = profileId,
                    profileName = profileName,
                    biometricsAvailable = biometricsAvailable,
                    onClose = { finish() },
                    onUnlockRequest = {
                        // Failure keeps the wallet locked (and this screen on
                        // its locked pane); success unlocks for the session.
                        BiometricGate.unlock(
                            this,
                            "Wallet",
                            { engine.unlock() },
                            { }
                        )
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROFILE_NAME = "profile_name"

        fun launch(context: Context, profileId: String, profileName: String) {
            context.startActivity(
                Intent(context, WalletActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_PROFILE_NAME, profileName)
            )
        }
    }
}

/**
 * Wallet surface: locked gate pane, onboarding (no wallet yet) or the
 * dashboard. All engine work is launched from [rememberCoroutineScope];
 * sheet opens happen only in callbacks, never in composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletRoot(
    engine: WalletEngineApi,
    walletLock: WalletLockManager,
    profileId: ProfileId,
    profileName: String,
    biometricsAvailable: Boolean,
    onClose: () -> Unit,
    onUnlockRequest: () -> Unit
) {
    val context = LocalContext.current
    val lockState by engine.lockState.collectAsState()
    val wallet by engine.wallet.collectAsState()
    val pending by engine.pendingRequests.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Wallet PIN state. null pinEnabled means "policy not loaded yet" — the
    // entry gate below waits for it so a PIN wallet is not ambushed by the
    // device prompt, and a no-PIN wallet keeps its existing behaviour.
    var pinEnabled by remember { mutableStateOf<Boolean?>(null) }
    var pinStatus by remember { mutableStateOf<WalletLockStatus>(WalletLockStatus.Ready) }
    var pinError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(profileId) {
        // A read failure defaults to "no PIN" (the device path), never to a
        // pane the user cannot get past.
        pinEnabled = runCatching { walletLock.policy(profileId.value) }
            .getOrNull()?.pinEnabled ?: false
        pinStatus = runCatching { walletLock.status(profileId.value) }
            .getOrDefault(WalletLockStatus.Ready)
    }

    // Any successful unlock (PIN or device credential) clears the retry
    // counter, so an owner who recovers via the device credential is not left
    // rate-limited on their next PIN attempt.
    LaunchedEffect(lockState) {
        if (lockState == WalletLockState.UNLOCKED) {
            runCatching { walletLock.clearFailures(profileId.value) }
        }
    }

    // ONBOARDING PIN: engine.createWallet/importWallet persist the wallet
    // row the MOMENT they run, which flips lockState NO_WALLET → LOCKED via
    // the repo observer — mid-flow. Without the pin, the when() below would
    // swap the onboarding out of composition at that instant: the
    // recovery-phrase reveal and the confirmation quiz would be UNREACHABLE
    // and a UI-created wallet unrecoverable (its phrase returned by
    // createWallet dies with the discarded composition state). The pin keeps
    // onboarding in place until IT reports ready (quiz done / import done);
    // NO_WALLET always shows onboarding anyway. Dropped state (process death
    // mid-onboarding) resumes on the locked pane — the wallet is usable, but
    // a phrase abandoned before reveal is gone for good (v1 documented risk).
    var onboardingPinned by remember {
        mutableStateOf(engine.lockState.value == WalletLockState.NO_WALLET)
    }
    // True once THIS surface's own onboarding flow left the choice screen
    // (create/import started here) — the ONLY legitimate holder of the pin
    // below. A pin acquired from a STALE lockState (the ':browser' process's
    // Room instance had not yet seen a wallet that another process just
    // created — CI 36842626140, the pipeline e2e's repo-level wallet
    // creation) must RELEASE the moment the engine reports a wallet exists,
    // or the entry gate (locked pane) can never render and a "Create a new
    // wallet" tap would race a second wallet into existence.
    var onboardingFlowStartedHere by remember { mutableStateOf(false) }
    LaunchedEffect(lockState) {
        if (lockState != WalletLockState.NO_WALLET && !onboardingFlowStartedHere) {
            onboardingPinned = false
        }
    }

    // Gate on entry (LOCKED only). A wallet with a PIN shows its own PIN form
    // instead of the device prompt; a wallet without one keeps the previous
    // auto-prompt. Keyed on pinEnabled so it fires once the policy is known.
    LaunchedEffect(pinEnabled) {
        if (pinEnabled == false && engine.lockState.value == WalletLockState.LOCKED) {
            onUnlockRequest()
        }
    }

    fun onMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    // A wrong PIN never leaves the pane; it only advances the retry counter
    // (persisted in app_state, so killing the app does not reset it).
    fun submitPin(pin: String) {
        val chars = pin.toCharArray()
        scope.launch {
            val result = runCatching { walletLock.verifyPin(profileId.value, chars) }
            PinLockCrypto.wipe(chars)
            when (val outcome = result.getOrNull()) {
                PinUnlockResult.Unlocked -> {
                    pinError = null
                    engine.unlock()
                }
                is PinUnlockResult.Wrong -> {
                    pinStatus = outcome.status
                    pinError = if (outcome.attemptsUntilBackoff > 0) {
                        "Wrong PIN. ${outcome.attemptsUntilBackoff} attempts left before a delay."
                    } else {
                        "Wrong PIN."
                    }
                }
                is PinUnlockResult.Backoff -> {
                    pinStatus = outcome.status
                    pinError = "Too many attempts — wait for the delay to end."
                }
                PinUnlockResult.NoPin -> {
                    pinEnabled = false
                    pinError = null
                }
                null -> pinError = "Could not check the PIN. Try again."
            }
        }
    }

    fun setWalletPin(pin: CharArray) {
        scope.launch {
            val failure = runCatching { walletLock.setPin(profileId.value, pin) }.exceptionOrNull()
            PinLockCrypto.wipe(pin)
            if (failure != null) {
                onMessage("Could not save the wallet PIN")
                return@launch
            }
            pinEnabled = true
            pinStatus = WalletLockStatus.Ready
            pinError = null
        }
    }

    fun removeWalletPin() {
        scope.launch {
            val failure = runCatching { walletLock.removePin(profileId.value) }.exceptionOrNull()
            if (failure != null) {
                onMessage("Could not remove the wallet PIN")
                return@launch
            }
            pinEnabled = false
            pinStatus = WalletLockStatus.Ready
            pinError = null
        }
    }

    // ONE balance-refresh path for the whole screen: the top bar's action and
    // the dashboard's connection card both drive this one. Two independent
    // flags would let the top bar look busy while the card claims "offline",
    // and the card is this screen's single answer to "is the wallet talking
    // to its chains?" — so the truth lives here, in the one place both read.
    var refreshing by remember { mutableStateOf(false) }
    var lastRefreshedAt by remember { mutableStateOf<Long?>(null) }
    fun refreshBalances() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            val failure = runCatching { engine.refreshBalances() }.exceptionOrNull()
            refreshing = false
            lastRefreshedAt = System.currentTimeMillis()
            // The card reports a failed READ in place; this only covers the
            // call itself blowing up (an unbound engine, a dead scope).
            if (failure != null) onMessage("Balance refresh failed")
        }
    }

    /** Opens a URL outside the app (explorer links). */
    fun openLink(url: String) {
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }.isSuccess
        if (!opened) onMessage("Could not open link")
    }

    /** Send success: hash in a snackbar, explorer link behind the action. */
    fun onSent(hash: String, explorerUrl: String?) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Sent ${shortenAddress(hash)}",
                actionLabel = explorerUrl?.let { "View" },
                withDismissAction = explorerUrl == null
            )
            if (result == SnackbarResult.ActionPerformed && explorerUrl != null) {
                openLink(explorerUrl)
            }
        }
    }

    Scaffold(
        // Keyboard rides under the whole screen (adjustResize semantics);
        // insets are applied EXPLICITLY below — the TopAppBar handles the
        // status bar itself.
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            // Padded above the navigation bar (contentWindowInsets is zeroed
            // on this Scaffold, so a bare host would draw under the buttons).
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom)
                )
            )
        },
        topBar = {
            TopAppBar(
                title = { Text("Wallet") },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { contentDescription = "Close wallet" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (lockState == WalletLockState.UNLOCKED) {
                        WalletIconButton(
                            label = "Refresh balances",
                            icon = Icons.Filled.Refresh,
                            onClick = { refreshBalances() }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Above the navigation bar and beside display cutouts.
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
        ) {
            when {
                // Pinned onboarding OR no wallet yet → onboarding. Creating/
                // importing does NOT unlock the engine session by design —
                // when onboarding completes, fire the entry gate right away
                // (the user just proved ownership of the phrase in this
                // session) instead of dumping them on the locked pane; on
                // failure/no device credential the pane takes over with its
                // manual retry, exactly like the entry path.
                onboardingPinned || lockState == WalletLockState.NO_WALLET -> WalletOnboarding(
                    engine = engine,
                    profileName = profileName,
                    onMessage = { onMessage(it) },
                    onFlowStarted = { onboardingFlowStartedHere = true },
                    onWalletReady = {
                        onboardingPinned = false
                        onUnlockRequest()
                    }
                )
                lockState == WalletLockState.LOCKED -> LockedWalletPane(
                    biometricsAvailable = biometricsAvailable,
                    pinEnabled = pinEnabled == true,
                    pinStatus = pinStatus,
                    pinError = pinError,
                    onPinSubmit = { pin -> submitPin(pin) },
                    onUnlock = onUnlockRequest
                )
                else -> WalletDashboard(
                    engine = engine,
                    walletLabel = wallet?.label ?: "Wallet",
                    profileName = profileName,
                    pendingCount = pending.size,
                    refreshing = refreshing,
                    lastRefreshedAt = lastRefreshedAt,
                    pinEnabled = pinEnabled == true,
                    onSetPin = { pin -> setWalletPin(pin) },
                    onRemovePin = { removeWalletPin() },
                    onMessage = { onMessage(it) },
                    onRefresh = { refreshBalances() },
                    onSent = { hash, explorerUrl -> onSent(hash, explorerUrl) },
                    onOpenExplorer = { url -> openLink(url) }
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Pending dApp confirmations (this activity's own queue view). The
    // queue drives which sheet is up; the sheet itself settles the request
    // through the engine (Approve / Reject / dismiss-as-reject), so the
    // request leaves the queue and the next one — if any — takes its place.
    // Only shown while UNLOCKED: locked users see nothing but the gate.
    // ------------------------------------------------------------------
    var dismissedRequestId by remember { mutableStateOf<String?>(null) }
    val activeRequest = pending.firstOrNull { it.id != dismissedRequestId }
    if (activeRequest != null && lockState == WalletLockState.UNLOCKED) {
        WalletDappRequestSheet(
            request = activeRequest,
            engine = engine,
            onDismiss = { dismissedRequestId = activeRequest.id }
        )
    }
}

/** The locked pane: no wallet content is ever rendered here. */
@Composable
private fun LockedWalletPane(
    biometricsAvailable: Boolean,
    pinEnabled: Boolean,
    pinStatus: WalletLockStatus,
    pinError: String?,
    onPinSubmit: (String) -> Unit,
    onUnlock: () -> Unit
) {
    val extras = LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoomCardShape)
                .background(extras.surfaceAlt.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = extras.primary,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Wallet locked",
            style = MaterialTheme.typography.titleMedium,
            color = extras.textPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                pinEnabled -> "This wallet has its own PIN, and your device unlock still works."
                biometricsAvailable -> {
                    "Unlock with your fingerprint, face or device PIN to view and " +
                        "use this profile's wallet."
                }
                else -> {
                    "This device has no screen lock. Set a PIN, pattern or password " +
                        "in system settings to use the wallet."
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        if (pinEnabled) {
            WalletPinUnlockSection(
                status = pinStatus,
                error = pinError,
                onPinSubmit = onPinSubmit,
                onUseDevice = onUnlock
            )
        } else {
            Button(
                onClick = onUnlock,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Unlock") }
        }
    }
}

/**
 * The unlocked dashboard: the connection card, header (wallet label +
 * profile), the pending badge, an Accounts / Activity switch, chain-filtered
 * account cards with per-chain Send / Receive, the add-account and network
 * entries, and the local activity list with explorer links. Every sheet open
 * happens in a callback; all lists come straight from the engine's flows.
 *
 * THE TWO BITS OF STATE THAT ARE NOT THE ENGINE'S: which account each chain's
 * Send / Receive acts from, and which address was just copied. The engine
 * models accounts as a plain list with no "current" one, so the choice is
 * [activeAccountByChain] here, defaulting to the chain's first account —
 * exactly what the sheets already defaulted to — and handed to them as a
 * preselection rather than becoming a second source of truth.
 */
@Composable
private fun WalletDashboard(
    engine: WalletEngineApi,
    walletLabel: String,
    profileName: String,
    pendingCount: Int,
    refreshing: Boolean,
    lastRefreshedAt: Long?,
    pinEnabled: Boolean,
    onSetPin: (CharArray) -> Unit,
    onRemovePin: () -> Unit,
    onMessage: (String) -> Unit,
    onRefresh: () -> Unit,
    onSent: (hash: String, explorerUrl: String?) -> Unit,
    onOpenExplorer: (url: String) -> Unit
) {
    val context = LocalContext.current
    val accounts by engine.accounts.collectAsState()
    val balances by engine.balances.collectAsState()
    val activeNetworks by engine.activeNetworks.collectAsState()
    val activities by engine.activities.collectAsState()

    var showAccountsSection by remember { mutableStateOf(true) }
    var chainFilter by remember { mutableStateOf<ChainType?>(null) }
    var addAccountOpen by remember { mutableStateOf(false) }
    var importKeyOpen by remember { mutableStateOf(false) }
    var networkPickerChain by remember { mutableStateOf<ChainType?>(null) }
    var addNetworkOpen by remember { mutableStateOf(false) }
    var chainlistOpen by remember { mutableStateOf(false) }
    var sendChain by remember { mutableStateOf<ChainType?>(null) }
    var receiveChain by remember { mutableStateOf<ChainType?>(null) }
    var backupOpen by remember { mutableStateOf(false) }
    var revealPhraseOpen by remember { mutableStateOf(false) }
    var connectedSitesOpen by remember { mutableStateOf(false) }
    var lockSettingsOpen by remember { mutableStateOf(false) }
    var activeAccountByChain by remember { mutableStateOf<Map<ChainType, String>>(emptyMap()) }
    // The address the user just copied, so the card that was tapped can
    // confirm the copy AT the tap: a snackbar alone lands at the far bottom
    // of the screen, seconds after the finger moved.
    var copiedAddress by remember { mutableStateOf<String?>(null) }

    /**
     * The account a chain's Send / Receive acts from (its first, by default).
     * The rule itself lives in [WalletOverview], where it can be tested: the
     * unlocked dashboard never composes under the instrumented suite.
     */
    fun activeAccountOf(chain: ChainType): WalletAccountRecord? =
        WalletOverview.activeAccount(accounts, chain, activeAccountByChain[chain])

    fun copyAddressWithFeedback(address: String) {
        // Addresses are public — a plain clip (no sensitive flag).
        copyAddress(context, address)
        copiedAddress = address
        onMessage("Address copied — ${shortenAddress(address)}")
    }

    LaunchedEffect(copiedAddress) {
        if (copiedAddress != null) {
            delay(1600)
            copiedAddress = null
        }
    }

    // Populate balances once per dashboard entry (offline-tolerant: a read
    // that fails renders as that account's own error state).
    LaunchedEffect(Unit) { onRefresh() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        // Header: the wallet, and the ONE account its primary actions act
        // from. It used to name only the wallet file, while Send / Receive
        // were repeated under every chain section — so a wallet on five
        // chains offered five identical pairs and no single place to act.
        val focusedChain = WalletOverview.focusedChain(accounts, chainFilter)
        val focusedAccount = focusedChain?.let { activeAccountOf(it) }
        WalletOverviewCard(
            walletLabel = walletLabel,
            profileName = profileName,
            chain = focusedChain,
            networkName = focusedChain?.let { activeNetworks[it]?.name },
            account = focusedAccount,
            balance = focusedAccount?.let { balances[it.id] },
            checking = refreshing,
            copied = focusedAccount != null && copiedAddress == focusedAccount.address,
            onCopy = {
                focusedAccount?.let { copyAddressWithFeedback(it.address) }
            },
            onOpenNetwork = { chain -> networkPickerChain = chain },
            onSend = { chain -> sendChain = chain },
            onReceive = { chain -> receiveChain = chain }
        )
        // Connection first: before any balance is read, the user should know
        // whether the reads are working at all. With no accounts there is
        // nothing to be connected to, and the accounts empty state says so.
        if (accounts.isNotEmpty()) {
            WalletConnectionCard(
                accounts = accounts,
                balances = balances,
                activeNetworks = activeNetworks,
                refreshing = refreshing,
                lastRefreshedAt = lastRefreshedAt,
                onRetry = onRefresh,
                onOpenNetwork = { chain -> networkPickerChain = chain }
            )
        }
        // Pending badge: the FIRST pending request's sheet is already up;
        // this only announces the queue behind it.
        if (pendingCount > 1) {
            WalletInfoNote(
                "$pendingCount site requests are waiting — answer the open " +
                    "confirmation first; the next one follows."
            )
        }
        // Section switch.
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = showAccountsSection,
                onClick = { showAccountsSection = true },
                label = { Text("Accounts") }
            )
            FilterChip(
                selected = !showAccountsSection,
                onClick = { showAccountsSection = false },
                label = { Text("Activity") }
            )
        }
        if (showAccountsSection) {
            // Chain filter.
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = chainFilter == null,
                    onClick = { chainFilter = null },
                    label = { Text("All") }
                )
                ChainType.entries.forEach { chain ->
                    FilterChip(
                        selected = chainFilter == chain,
                        onClick = { chainFilter = chain },
                        label = { Text(chain.displayName) }
                    )
                }
            }
            val visibleAccounts = accounts.filter { account ->
                chainFilter == null || account.chainType == chainFilter
            }
            // Local capture: delegated state can't be smart-cast.
            val filter = chainFilter
            val chains = ChainType.entries.filter { chain ->
                visibleAccounts.any { it.chainType == chain }
            }
            when {
                chains.isNotEmpty() -> chains.forEach { chain ->
                    val chainAccounts = visibleAccounts.filter { it.chainType == chain }
                    val active = activeAccountOf(chain)
                    val networkName = activeNetworks[chain]?.name
                    // The header names the NETWORK, not just the chain: which
                    // chain an address lives on is a property of its format,
                    // but which network it is being read and spent on is a
                    // choice — and it is the one that decides fees and
                    // whether the balance is real money.
                    SectionHeader(
                        if (networkName == null) chain.displayName
                        else "${chain.displayName} · $networkName"
                    )
                    chainAccounts.forEach { account ->
                        WalletAccountCard(
                            account = account,
                            balance = balances[account.id],
                            checking = refreshing,
                            selectable = chainAccounts.size > 1,
                            isActive = active?.id == account.id,
                            copied = copiedAddress == account.address,
                            onSelect = {
                                activeAccountByChain =
                                    activeAccountByChain + (chain to account.id)
                            },
                            onCopy = { copyAddressWithFeedback(account.address) }
                        )
                    }
                    // Send / Receive are NOT repeated here. They live once, in
                    // the header, acting on the chain the filter selects —
                    // which is also why the chip row above now chooses the
                    // active chain and not just what is visible.
                }
                // No account can be shown yet, and the reason matters: before
                // the first read lands, "No accounts yet" would tell a user
                // with accounts that they have none.
                accounts.isEmpty() && (refreshing || lastRefreshedAt == null) ->
                    WalletAccountsLoading()
                filter == null -> EmptyState(
                    "No accounts yet",
                    "Use Add account below to derive one from this wallet's recovery " +
                        "phrase, or to import a private key."
                )
                else -> EmptyState(
                    "No ${filter.displayName} accounts",
                    "Switch the chain filter above, or add a ${filter.displayName} " +
                        "account below."
                )
            }
            // Manage and Networks render even with NO accounts, and outside the
            // chain-filter branch: the empty state above tells the user to add
            // an account, but the section holding that entry used to live
            // inside the non-empty branch — so a wallet with nothing in it
            // offered no way to put anything in.
            SectionHeader("Manage")
            SettingsGroup {
                // The wallet PIN is optional and per-profile; the device
                // credential always stays available underneath it.
                SettingActionRow(
                    title = "Wallet lock",
                    subtitle = if (pinEnabled) {
                        "Wallet PIN on — device unlock also works"
                    } else {
                        "Set a PIN for this profile's wallet"
                    },
                    leadingIcon = Icons.Filled.Lock,
                    onClick = { lockSettingsOpen = true }
                )
                SettingActionRow(
                    title = "Add account",
                    subtitle = "Derive a new account or import a private key",
                    leadingIcon = Icons.Filled.Add,
                    onClick = { addAccountOpen = true }
                )
                // The wallet outlives the phone only if its keys are
                // written down somewhere else. Onboarding offers this at
                // the reveal; this is the same export for a wallet that
                // was created before the user thought about it.
                SettingActionRow(
                    title = "Export wallet keys",
                    subtitle = "Recovery phrase and imported keys, sealed with a password",
                    leadingIcon = Icons.Filled.FileDownload,
                    onClick = { backupOpen = true }
                )
                // The export above is for the user who has nothing yet and
                // wants a file; this is for the one who has the paper but
                // cannot read it, and only needs to look. Both exist because
                // the phrase was always recoverable from the engine and the
                // app simply never offered it a second time.
                SettingActionRow(
                    title = "Show recovery phrase",
                    subtitle = "Read the words that restore this wallet",
                    leadingIcon = Icons.Filled.Key,
                    onClick = { revealPhraseOpen = true }
                )
                // A granted permission is invisible from here, but it is the
                // reason a site stops asking to connect. Leaving it with no
                // way to review or take back would make "connected" a state
                // the user can only enter, never leave.
                SettingActionRow(
                    title = "Connected sites",
                    subtitle = "Sites you have approved, and their permissions",
                    leadingIcon = Icons.Filled.Public,
                    onClick = { connectedSitesOpen = true }
                )
            }
            // Every chain this wallet actually holds an account on, plus any
            // chain with a selected network — never a filtered view, because
            // a network is set per chain, not per account.
            val networkChains = ChainType.entries.filter { chain ->
                accounts.any { it.chainType == chain } || activeNetworks.containsKey(chain)
            }
            if (networkChains.isNotEmpty()) {
                SectionHeader("Networks")
                networkChains.forEach { chain ->
                    SettingsGroup {
                        SettingActionRow(
                            title = "${chain.displayName} network",
                            value = activeNetworks[chain]?.name ?: "None selected",
                            leadingIcon = Icons.Filled.Language,
                            onClick = { networkPickerChain = chain }
                        )
                    }
                }
            }
        } else {
            if (activities.isEmpty()) {
                EmptyState(
                    "No wallet activity yet",
                    "Sends and signed requests from this profile appear here."
                )
            } else {
                activities.forEach { record ->
                    WalletActivityRow(record = record, onOpenExplorer = onOpenExplorer)
                }
            }
        }
    }

    // ---------- Sheets (render points; opens happen in callbacks) ----------

    if (lockSettingsOpen) {
        WalletLockSettingsSheet(
            pinEnabled = pinEnabled,
            onSetPin = onSetPin,
            onRemovePin = onRemovePin,
            onDismiss = { lockSettingsOpen = false }
        )
    }
    if (addAccountOpen) {
        AddAccountSheet(
            engine = engine,
            onImportKey = {
                addAccountOpen = false
                importKeyOpen = true
            },
            onMessage = { onMessage(it) },
            onDismiss = { addAccountOpen = false }
        )
    }
    if (importKeyOpen) {
        ImportKeySheet(
            engine = engine,
            onMessage = { onMessage(it) },
            onDismiss = { importKeyOpen = false }
        )
    }
    networkPickerChain?.let { chain ->
        NetworkPickerSheet(
            engine = engine,
            chainType = chain,
            onAddNetwork = {
                networkPickerChain = null
                addNetworkOpen = true
            },
            onBrowseChainlist = {
                networkPickerChain = null
                chainlistOpen = true
            },
            onMessage = { onMessage(it) },
            onDismiss = { networkPickerChain = null }
        )
    }
    if (addNetworkOpen) {
        AddNetworkSheet(
            engine = engine,
            onMessage = { onMessage(it) },
            onDismiss = { addNetworkOpen = false }
        )
    }
    if (chainlistOpen) {
        ChainlistSheet(
            engine = engine,
            onMessage = { onMessage(it) },
            onDismiss = { chainlistOpen = false }
        )
    }
    if (connectedSitesOpen) {
        ConnectedSitesSheet(
            engine = engine,
            onMessage = { onMessage(it) },
            onDismiss = { connectedSitesOpen = false }
        )
    }
    if (revealPhraseOpen) {
        RevealPhraseSheet(
            engine = engine,
            onMessage = { onMessage(it) },
            onDismiss = { revealPhraseOpen = false }
        )
    }
    sendChain?.let { chain ->
        SendSheet(
            engine = engine,
            chainType = chain,
            // The dashboard's active account for this chain is the sheet's
            // starting point; the picker inside can still change it.
            initialAccountId = activeAccountOf(chain)?.id,
            onDismiss = { sendChain = null },
            onSent = { hash, explorerUrl -> onSent(hash, explorerUrl) }
        )
    }
    receiveChain?.let { chain ->
        ReceiveSheet(
            engine = engine,
            chainType = chain,
            initialAccountId = activeAccountOf(chain)?.id,
            onCopyAddress = { address -> copyAddressWithFeedback(address) },
            onDismiss = { receiveChain = null }
        )
    }

    // Null mnemonic: the phrase comes from the vault, so this path needs the
    // unlocked session the dashboard is only rendered behind.
    WalletBackupFlow(
        open = backupOpen,
        engine = engine,
        walletLabel = walletLabel,
        profileLabel = profileName,
        mnemonicInHand = null,
        onMessage = onMessage,
        onDone = { backupOpen = false }
    )
}

/**
 * The wallet header: which wallet this is, and the one account its primary
 * actions act from.
 *
 * WHY ONE ACCOUNT AND NO TOTAL: a wallet holding 1 ETH and 1 SOL holds "2" of
 * nothing. A cross-chain total is only meaningful in a currency the chains are
 * priced in, and this app has no price feed — adding one would mean a network
 * call on every dashboard entry and a headline number that is wrong whenever
 * it is stale. So the header states what is true: the selected chain's account
 * and that account's own balance, with the chain named above it.
 *
 * The account is reachable here as well as in its chain section, because the
 * actions are here: a user who taps Send should not have to scroll to find out
 * which address it will spend from.
 */
@Composable
private fun WalletOverviewCard(
    walletLabel: String,
    profileName: String,
    chain: ChainType?,
    networkName: String?,
    account: WalletAccountRecord?,
    balance: BalanceResult?,
    checking: Boolean,
    copied: Boolean,
    onCopy: () -> Unit,
    onOpenNetwork: (ChainType) -> Unit,
    onSend: (ChainType) -> Unit,
    onReceive: (ChainType) -> Unit
) {
    val extras = LocalRoomExtras.current
    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        withGradient = false
    ) {
        // fillMaxWidth, not just padding: RoomCard's content is a Box, so a
        // wrapping Column would leave the Send / Receive Row below with no
        // width to divide — weighted children need a bounded width.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(extras.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.AccountBalanceWallet,
                        contentDescription = null,
                        tint = extras.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        walletLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = extras.textPrimary
                    )
                    if (profileName.isNotBlank()) {
                        Text(
                            "Profile: $profileName",
                            style = MaterialTheme.typography.labelMedium,
                            color = extras.textSecondary
                        )
                    }
                }
            }
            // No account to act from: the wallet itself is the whole header.
            // The chain filter naming a chain this wallet holds nothing on is
            // the way here, and the empty state below says so.
            if (chain == null || account == null) return@Column
            Spacer(Modifier.height(10.dp))
            // The chain the actions below will use, and the way to change it.
            // Naming the NETWORK too, not just the chain: which chain an
            // address lives on is a property of its format, but which network
            // it is read and spent on decides fees and whether the balance is
            // real money.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenNetwork(chain) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Language,
                    contentDescription = null,
                    tint = extras.icon,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (networkName.isNullOrBlank()) {
                        "${chain.displayName} · no network selected"
                    } else {
                        "${chain.displayName} · $networkName"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = extras.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "Change",
                    style = MaterialTheme.typography.labelLarge,
                    color = extras.primary
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        account.label.ifBlank { chain.displayName },
                        style = MaterialTheme.typography.bodyLarge,
                        color = extras.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        shortenAddress(account.address),
                        style = MaterialTheme.typography.labelMedium,
                        color = extras.textSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    AccountBalance(balance = balance, checking = checking)
                    WalletIconButton(
                        label = if (copied) "Address copied" else "Copy address",
                        icon = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        tint = if (copied) extras.primary else extras.icon,
                        onClick = onCopy
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onSend(chain) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "Send on ${chain.displayName}"
                        }
                ) { Text("Send") }
                OutlinedButton(
                    onClick = { onReceive(chain) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "Receive on ${chain.displayName}"
                        }
                ) { Text("Receive") }
            }
        }
    }
}

/**
 * One account: label, address, its balance state, and the two actions that
 * belong to the identity itself — copy the address, and (only when the chain
 * holds more than one account) make this the account Send / Receive act from.
 *
 * The balance is never a bare "—": a read still in flight spins, a read that
 * failed says so in place, and only a real value reads as one. "—" used to
 * stand for all three, which is how a failed read passed for a zero balance.
 */
@Composable
private fun WalletAccountCard(
    account: WalletAccountRecord,
    balance: BalanceResult?,
    checking: Boolean,
    selectable: Boolean,
    isActive: Boolean,
    copied: Boolean,
    onSelect: () -> Unit,
    onCopy: () -> Unit
) {
    val extras = LocalRoomExtras.current
    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        withGradient = false
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (selectable) {
                        // selectable, not clickable: the point of the tap is
                        // WHICH account is chosen, and a screen reader has to
                        // hear that as a selection, not as an action.
                        Modifier.selectable(
                            selected = isActive,
                            role = Role.RadioButton,
                            onClick = onSelect
                        )
                    } else {
                        Modifier
                    }
                )
                // Read as one statement ("EVM 1, 0x1234…abcd, 1.25 ETH"); the
                // copy button keeps its own node, since it is its own action.
                .semantics(mergeDescendants = true) {}
                .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(extras.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.AccountCircle,
                    contentDescription = null,
                    tint = extras.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        account.label.ifBlank { account.chainType.displayName },
                        style = MaterialTheme.typography.bodyLarge,
                        color = extras.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (selectable && isActive) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Active",
                            style = MaterialTheme.typography.labelSmall,
                            color = extras.primary
                        )
                    }
                }
                Text(
                    shortenAddress(account.address),
                    style = MaterialTheme.typography.labelMedium,
                    color = extras.textSecondary,
                    fontFamily = FontFamily.Monospace
                )
                if (balance is BalanceResult.Error) {
                    Text(
                        balance.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                AccountBalance(balance = balance, checking = checking)
                WalletIconButton(
                    label = if (copied) "Address copied" else "Copy address",
                    icon = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    tint = if (copied) extras.primary else extras.icon,
                    onClick = onCopy
                )
            }
        }
    }
}

/**
 * The balance slot: a real value, a spinner while the read is in flight, or an
 * honest word for one that failed or has not run. The account's own error text
 * (rendered under its address) names WHAT failed; this only avoids pretending
 * the answer was zero.
 */
@Composable
private fun AccountBalance(balance: BalanceResult?, checking: Boolean) {
    val extras = LocalRoomExtras.current
    when {
        balance is BalanceResult.Ok -> Text(
            "${balance.amount} ${balance.symbol}",
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textPrimary
        )
        balance is BalanceResult.Error -> Text(
            "Unavailable",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        checking -> CircularProgressIndicator(
            modifier = Modifier
                .size(16.dp)
                .semantics { contentDescription = "Checking balance" },
            strokeWidth = 2.dp,
            color = extras.primary
        )
        else -> Text(
            "Not loaded",
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary
        )
    }
}

/**
 * How the wallet's chain reads are doing after the last balance pass. The
 * engine exposes no connectivity flag, and a per-account failure arrives as
 * [BalanceResult.Error] — so the split between SOME reads failing and ALL of
 * them failing is what separates a flaky endpoint from being offline.
 */
private enum class WalletConnection { CHECKING, ONLINE, PARTIAL, OFFLINE }

/** Per-chain read health, for the dot beside a network name. */
private enum class ChainReach { REACHABLE, PARTIAL, UNREACHABLE, UNKNOWN }

private fun connectionState(
    accounts: List<WalletAccountRecord>,
    balances: Map<String, BalanceResult>,
    refreshing: Boolean
): WalletConnection {
    val known = accounts.mapNotNull { balances[it.id] }
    // Nothing has ever come back: the first pass is still running.
    if (known.isEmpty()) return WalletConnection.CHECKING
    val failed = known.count { it is BalanceResult.Error }
    return when {
        failed == known.size -> WalletConnection.OFFLINE
        failed > 0 || refreshing -> WalletConnection.PARTIAL
        else -> WalletConnection.ONLINE
    }
}

private fun chainReach(
    chainAccounts: List<WalletAccountRecord>,
    balances: Map<String, BalanceResult>
): ChainReach {
    val known = chainAccounts.mapNotNull { balances[it.id] }
    if (known.isEmpty()) return ChainReach.UNKNOWN
    val failed = known.count { it is BalanceResult.Error }
    return when {
        failed == 0 -> ChainReach.REACHABLE
        failed == known.size -> ChainReach.UNREACHABLE
        else -> ChainReach.PARTIAL
    }
}

private fun reachLabel(reach: ChainReach): String = when (reach) {
    ChainReach.REACHABLE -> "Reachable"
    ChainReach.PARTIAL -> "Partial"
    ChainReach.UNREACHABLE -> "Unreachable"
    ChainReach.UNKNOWN -> "Checking…"
}

/** Dot / label colour for a chain's read health — theme accent and theme error only. */
@Composable
private fun reachColor(reach: ChainReach): Color {
    val extras = LocalRoomExtras.current
    return when (reach) {
        ChainReach.REACHABLE -> extras.primary
        ChainReach.PARTIAL, ChainReach.UNKNOWN -> extras.textSecondary
        ChainReach.UNREACHABLE -> MaterialTheme.colorScheme.error
    }
}

/**
 * The screen's single answer to "is this wallet talking to its chains?".
 *
 * A failed balance read used to be one small line inside one account card plus
 * a snackbar that had already faded — easy to miss, and a missed read is
 * indistinguishable from an empty wallet. This states the connection first,
 * then names each chain's active network and whether the last read from that
 * chain worked. Each row opens the network picker: "which network, and is it
 * reachable" is only useful if the answer can be changed from where it is read.
 */
@Composable
private fun WalletConnectionCard(
    accounts: List<WalletAccountRecord>,
    balances: Map<String, BalanceResult>,
    activeNetworks: Map<ChainType, NetworkConfig>,
    refreshing: Boolean,
    lastRefreshedAt: Long?,
    onRetry: () -> Unit,
    onOpenNetwork: (ChainType) -> Unit
) {
    val extras = LocalRoomExtras.current
    val state = connectionState(accounts, balances, refreshing)
    val chains = ChainType.entries.filter { chain -> accounts.any { it.chainType == chain } }
    val updated = lastRefreshedAt?.let { " · Updated ${formatClock(it)}" }.orEmpty()
    val title = when (state) {
        WalletConnection.CHECKING -> "Checking balances…"
        WalletConnection.ONLINE -> "Connected"
        WalletConnection.PARTIAL -> "Some balances unavailable"
        WalletConnection.OFFLINE -> "Offline"
    }
    val detail = when (state) {
        WalletConnection.CHECKING -> "Asking each chain for the current balance."
        WalletConnection.ONLINE -> "Every chain answered the last balance check.$updated"
        WalletConnection.PARTIAL ->
            "At least one chain did not answer. Funds are unaffected — retry to refresh."
        // Deliberately explicit: "offline" must not read as "your money is
        // gone". The keys and the chain state are untouched; only the view is.
        WalletConnection.OFFLINE ->
            "No chain answered the last check. Your keys and funds are unaffected — " +
                "the balances are simply unknown right now."
    }
    val accent = when (state) {
        WalletConnection.CHECKING, WalletConnection.PARTIAL -> extras.textSecondary
        WalletConnection.ONLINE -> extras.primary
        WalletConnection.OFFLINE -> MaterialTheme.colorScheme.error
    }
    val retryable = state == WalletConnection.OFFLINE || state == WalletConnection.PARTIAL

    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        withGradient = false
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (state == WalletConnection.CHECKING) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(18.dp)
                                .semantics { contentDescription = "Checking balances" },
                            strokeWidth = 2.dp,
                            color = accent
                        )
                    } else {
                        Icon(
                            when (state) {
                                WalletConnection.ONLINE -> Icons.Filled.CloudDone
                                WalletConnection.PARTIAL -> Icons.Filled.Cloud
                                else -> Icons.Filled.CloudOff
                            },
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = extras.textPrimary
                    )
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelMedium,
                        color = extras.textSecondary
                    )
                }
                if (retryable) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Retry") }
                }
            }
            if (chains.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(extras.border.copy(alpha = 0.5f))
                )
                chains.forEach { chain ->
                    val reach = chainReach(accounts.filter { it.chainType == chain }, balances)
                    val networkName = activeNetworks[chain]?.name
                    val statusColor = reachColor(reach)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(onClickLabel = "Change network") {
                                onOpenNetwork(chain)
                            }
                            // One node for the whole row, so a screen reader
                            // reads the chain, its network and the outcome of
                            // the last read as the one statement they are.
                            .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${chain.displayName} · ${networkName ?: "No network selected"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = extras.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            reachLabel(reach),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }
                }
            }
        }
    }
}

/**
 * The accounts list before its first read lands. Rendering the empty state
 * here would tell a user who HAS accounts that they have none.
 */
@Composable
private fun WalletAccountsLoading() {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(18.dp)
                .semantics { contentDescription = "Checking accounts" },
            strokeWidth = 2.dp,
            color = extras.primary
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Checking your accounts…",
            style = MaterialTheme.typography.bodyMedium,
            color = extras.textSecondary
        )
    }
}

/** One locally-recorded wallet activity row with its explorer link. */
@Composable
private fun WalletActivityRow(
    record: WalletActivityRecord,
    onOpenExplorer: (url: String) -> Unit
) {
    val extras = LocalRoomExtras.current
    RoomCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        withGradient = false
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(extras.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    activityKindIcon(record.kind),
                    contentDescription = null,
                    tint = extras.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    record.displayAmount,
                    style = MaterialTheme.typography.bodyLarge,
                    color = extras.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                record.toAddress?.let {
                    Text(
                        "To ${shortenAddress(it)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = extras.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    record.networkName + " · " + formatTime(record.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                record.hash?.let {
                    Text(
                        shortenAddress(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.textSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            record.explorerUrl?.let { url ->
                WalletIconButton(
                    label = "View on explorer",
                    icon = Icons.Filled.Public,
                    onClick = { onOpenExplorer(url) }
                )
            }
        }
    }
}

/** Kind icon for an activity record. */
private fun activityKindIcon(kind: WalletActivityRecord.Kind): ImageVector = when (kind) {
    WalletActivityRecord.Kind.SEND -> Icons.Filled.ArrowUpward
    WalletActivityRecord.Kind.DAPP_SEND -> Icons.Filled.Language
    WalletActivityRecord.Kind.SIGN_MESSAGE -> Icons.Filled.Description
    WalletActivityRecord.Kind.SIGN_TRANSACTION -> Icons.Filled.Receipt
}

/** Locale-aware short date + time for activity rows. */
private fun formatTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))

/** Clock time of the last balance pass, for the connection card's "Updated …". */
private fun formatClock(epochMillis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMillis))

/**
 * Places an address on the clipboard. Addresses are PUBLIC identity
 * (unlike passwords): a plain clip is correct; the snackbar that follows
 * announces what was copied.
 */
private fun copyAddress(context: Context, address: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("address", address))
}
