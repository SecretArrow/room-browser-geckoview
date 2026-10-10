package com.roombrowser.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.roombrowser.agent.AiTaskPageHost
import com.roombrowser.agent.AiTaskPageHosts
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.browser.ui.BrowserScreen
import com.roombrowser.browser.ui.LaunchRequest
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.security.BiometricGate
import com.roombrowser.ui.common.RoomBrowserTheme
import kotlinx.coroutines.launch

/**
 * Browser engine activity — runs in the dedicated ':browser' process.
 *
 * CONTRACT (see PROFILE_ISOLATION.md):
 *  1. ProfileEngine.bindProcessToProfile(profileId) is called in onCreate
 *     BEFORE any WebView exists — this selects the per-profile WebView data
 *     directory suffix (cookies/localStorage/IndexedDB/cache/service
 *     workers are therefore physically separated per profile).
 *  2. If the process is already bound to a DIFFERENT profile (should not
 *     normally happen — the switch executor restarts the process), the
 *     activity schedules a proper restart instead of mixing profiles.
 *  3. The activity is recreate-safe (process death, config changes) — the
 *     active profile is re-read from Room (app_state) when the intent extra
 *     is absent.
 */
class BrowserActivity : FragmentActivity() {

    private var boundProfileId: ProfileId? = null
    private var pendingSwitch = false
    private var browserViewModel: BrowserViewModel? = null
    private var taskPageHost: AiTaskPageHost? = null

    /**
     * The engine's outstanding open request, as Compose state.
     *
     * State, not a plain field, because it CHANGES during the activity's
     * life: this activity is `singleTask`, so a second launch (deep link,
     * share target, `am start`) is delivered to [onNewIntent] and onCreate
     * never runs again. A plain field read once at setContent time could
     * never carry that, which is how the second launch used to be dropped.
     */
    private var launchRequest by mutableStateOf<LaunchRequest?>(null)

    /**
     * How many times this launch or a later [onNewIntent] asked for the agent.
     *
     * A counter, not a flag, for the same reason [launchRequest] is state and
     * not a field: [AgentActivity] falls back to this activity when it had no
     * ViewModel to draw, and a flag could only ever fire once.
     */
    private var openAgentSignal by mutableIntStateOf(0)

    /** True while the full-screen NetworkWarningActivity is on top. */
    private var networkWarningRunning = false

    /** True once the warning has taken window focus away from this activity. */
    private var networkWarningFocusLost = false

    private lateinit var networkWarningLauncher: ActivityResultLauncher<Intent>

    /**
     * The passkey (and any other system-window) bridge, and its launcher.
     *
     * The bridge is built once at construction because the delegate has to be
     * installed BEFORE the engine binds; the launcher it forwards to is
     * registered in [onCreate], which runs before any prompt can arrive.
     */
    private val engineActivityBridge = EngineActivityBridge { request ->
        passkeyLauncher.launch(request)
    }
    private lateinit var passkeyLauncher: ActivityResultLauncher<IntentSenderRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge with runtime insets: the UI applies WindowInsets
        // padding itself, so nothing ever overlaps the 3-button navigation
        // bar (Back / Home / Recents) or the status bar.
        enableEdgeToEdge()

        val fromIntent = intent.getStringExtra(EXTRA_PROFILE_ID)
        var profileIdString = fromIntent
        if (profileIdString == null) {
            // Cold restore path: read the persisted active profile
            val graph = (application as com.roombrowser.RoomBrowserApp).graph
            profileIdString = kotlinx.coroutines.runBlocking {
                graph.appState.activeProfileIdSnapshot()
            }
        }
        if (profileIdString == null) {
            // No profile selected → back to the picker
            startActivity(
                Intent(this, com.roombrowser.main.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            return
        }

        val profileId = ProfileId(profileIdString)
        val initialUrl = intent.getStringExtra(EXTRA_INITIAL_URL)
            ?: savedInstanceState?.getString(EXTRA_INITIAL_URL)
        launchRequest = initialUrl?.let { LaunchRequest(it, System.nanoTime()) }
        if (intent.getBooleanExtra(AgentActivity.EXTRA_OPEN_AGENT, false)) openAgentSignal++

        // ---- Passkeys ------------------------------------------------
        // The credential prompt belongs to the system's credential provider
        // and only an Activity can start it, so the engine -- which has no
        // Activity -- hands us the PendingIntent and waits. Installed BEFORE
        // the bind below, because the bind is what creates the engine runtime
        // and wires this in; a delegate that arrived afterwards would leave
        // every passkey request failing, which a page shows as a sign-in that
        // spins forever.
        passkeyLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            engineActivityBridge.deliver(
                if (result.resultCode == RESULT_OK) result.data else null
            )
        }
        ProfileEngine.setActivityDelegate(engineActivityBridge)

        // THE isolation-critical step: bind this process to the profile.
        val bound = ProfileEngine.bindProcessToProfile(profileId)
        if (!bound) {
            // Process already bound to another profile → restart cleanly.
            scheduleSelfRestart(profileId, initialUrl)
            return
        }
        boundProfileId = profileId
        // THIS instance is the restart, so the alarms that raced it here have
        // done their job and are cancelled. This is TIDYING, not the safety
        // property: the backstop can now only ever deliver an onNewIntent, so
        // losing this race costs nothing. It used to be the whole defence, and
        // it used to lose -- the alarm is due at T+350 while this method runs
        // at ~T+1150, so whether it fired first was decided by how far the
        // system deferred an inexact alarm that day (see
        // ProfileSwitchExecutor.scheduleRestart).
        cancelPendingRestartAlarm()

        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return BrowserViewModel(application, profileId) as T
            }
        }
        val viewModel = ViewModelProvider(this, factory)[BrowserViewModel::class.java]
        browserViewModel = viewModel
        // Published for the surfaces that are a different Activity but the
        // same browser — the full-screen Room Agent, which must not build a
        // second ViewModel on the engine this process already bound.
        BrowserSession.publish(viewModel)

        // The surface a task saved as Headed or Standard runs on. Registered
        // here because it needs a live tab strip, and dropped in onDestroy so a
        // run can never open a tab on a dead ViewModel.
        val pageHost = BrowserTaskPageHost(
            vm = viewModel,
            profileData = com.roombrowser.agent.GraphAgentProfileData(
                graph = (application as com.roombrowser.RoomBrowserApp).graph,
                profileId = viewModel.profileId,
                context = application
            ),
            otpDigitsAllowed = {
                (application as com.roombrowser.RoomBrowserApp).graph.appState
                    .agentSettingsSnapshot().otpDigitsToAgent
            }
        )
        taskPageHost = pageHost
        AiTaskPageHosts.register(pageHost)

        // ---- Profile network warning (replaces the old IpWarningDialog) ---
        // The pending decision is STATE, not a dialog: while the gate stands,
        // this launcher loop keeps the full-screen warning in place. System
        // Back finishes it with RESULT_CANCELED — the gate is still armed, so
        // the warning comes straight back. Only an explicit decision (or a
        // persisted suppression) releases browsing.
        networkWarningLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            Log.d(TAG, "warning result: code=${result.resultCode}")
            networkWarningRunning = false
            when (result.resultCode) {
                NetworkWarningActivity.RESULT_CONTINUE ->
                    viewModel.resolveNetworkWarning(NetworkWarningDecision.CONTINUE)
                NetworkWarningActivity.RESULT_SWITCH ->
                    viewModel.resolveNetworkWarning(NetworkWarningDecision.SWITCH)
                NetworkWarningActivity.RESULT_SUPPRESS ->
                    viewModel.resolveNetworkWarning(NetworkWarningDecision.SUPPRESS)
                else -> Unit // Back / cancel: the decision is still pending.
            }
            launchNetworkWarningIfNeeded()
        }
        lifecycleScope.launch {
            // repeatOnLifecycle, not a bare collect: a bare collector stays
            // subscribed while the activity is STOPPED, so a gate armed behind
            // another screen was consumed by a collector that could not act on
            // it (launchNetworkWarningIfNeeded bails below RESUMED) and the
            // value was gone by the time the activity came back — the exact
            // hole onResume exists to patch. Restarting the collection on
            // STARTED re-reads the StateFlow's current value, so the gate is
            // re-delivered on every return instead of being dropped once.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.networkGateState.collect { gated ->
                    if (gated) launchNetworkWarningIfNeeded()
                }
            }
        }

        setContent {
            // Whole-engine theming from THIS profile's theme snapshot —
            // changes live when the Theme Studio (default process) applies a
            // new theme (Room multi-instance invalidation → observeProfile).
            RoomBrowserTheme(spec = viewModel.themeSpec) {
                BrowserScreen(
                    activity = this,
                    viewModel = viewModel,
                    launchRequest = launchRequest,
                    onSwitchProfile = { targetProfileId -> switchProfile(targetProfileId) },
                    openAgentSignal = openAgentSignal
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A second launch of an ALREADY RUNNING engine lands here, never in
        // onCreate: the activity is singleTask, so the system reuses this
        // instance and delivers the intent. Reading the extra only in
        // onCreate meant the intent was recorded and then read by nobody —
        // the browser stayed where it was and the URL was silently dropped.
        intent.getStringExtra(EXTRA_INITIAL_URL)?.let { url ->
            launchRequest = LaunchRequest(url, System.nanoTime())
        }
        if (intent.getBooleanExtra(AgentActivity.EXTRA_OPEN_AGENT, false)) openAgentSignal++
    }

    override fun onResume() {
        super.onResume()
        // Deterministic cross-process re-sync: providers / agent settings
        // configured in the settings activities (default process) while this
        // engine was backgrounded become visible immediately — even when
        // Room's multi-instance invalidation ping is lost on slow
        // filesystems (observed on the CI emulator).
        browserViewModel?.agent?.refreshProviders()
        // A gate armed while paused (a StateFlow collector already consumed
        // its value) is picked up here instead.
        launchNetworkWarningIfNeeded()
    }

    /**
     * SELF-HEALING re-engagement point for the network-warning gate.
     *
     * CI forensics (run 9399640, NetworkWarning cycle 2) caught a state where
     * the warning was dismissed with system Back and NEVER relaunched: no
     * NetworkWarningActivity start was logged, meaning every attempt inside
     * [launchNetworkWarningIfNeeded] was rejected — and the only guard that
     * can stay stuck across the whole resume path is a stale
     * [networkWarningRunning] left behind by a result callback that never
     * dispatched. Window focus is the late signal: focus can only RETURN to
     * this activity after the warning's window is gone, so a running-flag
     * that is still set at that point is stale. Reset it and give the gate
     * one more launch attempt — the gate can no longer be bypassed by a lost
     * result delivery.
     *
     * The "return" has to be enforced, not assumed: a freshly launched
     * activity also gains focus for the first time right after onResume,
     * before the warning it just launched has taken it. That gain has no
     * preceding loss and is not a return — reading it as one started a
     * SECOND warning instance (run 37466963560: two launches 47 ms apart,
     * and the Continue tap reached neither).
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            networkWarningFocusLost = true
            return
        }
        if (networkWarningRunning && networkWarningFocusLost) {
            Log.w(TAG, "stale networkWarningRunning reset on focus gain (result callback lost?)")
            networkWarningRunning = false
        }
        launchNetworkWarningIfNeeded()
    }

    /**
     * Launches the network warning activity while a decision is pending —
     * state-driven, called from the result callback, the gate collector and
     * onResume (never from composition). Privacy toggles shape the extras:
     * the previous profile's NAME is only included when the user allows it.
     */
    private fun launchNetworkWarningIfNeeded() {
        val viewModel = browserViewModel ?: run {
            Log.d(TAG, "gate: no view model yet")
            return
        }
        if (!viewModel.networkGateState.value || networkWarningRunning) {
            Log.d(
                TAG,
                "gate: skip (gated=${viewModel.networkGateState.value} " +
                    "running=$networkWarningRunning)"
            )
            return
        }
        if (lifecycle.currentState < Lifecycle.State.RESUMED) {
            Log.d(TAG, "gate: skip (state=${lifecycle.currentState})")
            return
        }
        val payload = viewModel.pendingNetWarning
        if (payload == null) {
            // Pending flag without a decodable payload: the decision cannot
            // be presented, and holding the gate would brick the profile.
            // Clear the stale state (documented corruption valve).
            Log.w(TAG, "gate: unreadable payload — discarding")
            viewModel.discardUnreadableNetworkWarning()
            return
        }
        Log.d(TAG, "gate: launching warning (ip=${payload.ip})")
        networkWarningRunning = true
        networkWarningFocusLost = false
        val showName = viewModel.globalSettings.showPreviousProfileName
        val showLastSeen = viewModel.globalSettings.showLastSeenTime
        networkWarningLauncher.launch(
            Intent(this, NetworkWarningActivity::class.java)
                .putExtra(NetworkWarningActivity.EXTRA_IP, payload.ip)
                .putExtra(
                    NetworkWarningActivity.EXTRA_PREVIOUS_PROFILE_NAME,
                    if (showName) payload.previousProfileName else ""
                )
                .putExtra(
                    NetworkWarningActivity.EXTRA_LAST_SEEN,
                    if (showLastSeen) payload.lastSeenAt else 0L
                )
        )
    }

    /**
     * The desktop Developer Tools chord, on an external keyboard or DeX.
     *
     * It is intercepted on the ACTIVITY rather than in Compose because the
     * engine view is a real Android view that takes focus: a Compose
     * `onPreviewKeyEvent` never sees a key aimed at it, and `dispatchKeyEvent`
     * sees every key before the view hierarchy does.
     *
     * `@SuppressLint("RestrictedApi")`: androidx.core marks its own
     * `ComponentActivity.dispatchKeyEvent` override restricted because
     * overriding it without chaining would cut the `OnBackPressedDispatcher`
     * key path. Every key this override does not claim is handed straight back
     * to `super`, so that path still runs and the restriction's reason does not
     * apply here.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            isDeveloperToolsChord(event)
        ) {
            browserViewModel?.toggleDeveloperTools()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** F12, or Ctrl+Shift+I — the two chords a desktop browser uses. */
    private fun isDeveloperToolsChord(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_F12 -> true
        KeyEvent.KEYCODE_I -> event.isCtrlPressed && event.isShiftPressed
        else -> false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        launchRequest?.let { outState.putString(EXTRA_INITIAL_URL, it.url) }
    }

    override fun onDestroy() {
        // Answer anything still waiting BEFORE the delegate goes: an engine
        // call left unresolved is a page that never finishes loading. Then
        // withdraw the delegate, so a prompt arriving after this activity is
        // gone fails cleanly instead of launching into a dead window.
        engineActivityBridge.cancelAll()
        ProfileEngine.setActivityDelegate(null)
        taskPageHost?.let { AiTaskPageHosts.unregister(it) }
        taskPageHost = null
        // compareAndSet, not a plain null: a profile switch finishes this
        // instance AFTER its replacement has already published, and a plain
        // clear would withdraw the live ViewModel out from under it.
        browserViewModel?.let { BrowserSession.clear(it) }
        super.onDestroy()
    }

    /**
     * Profile switch through the executor: runs the 7-step security
     * protocol and restarts this process bound to the new profile.
     */
    private fun switchProfile(
        targetProfileId: ProfileId,
        extras: Intent.() -> Unit = {}
    ) {
        if (pendingSwitch) return
        pendingSwitch = true
        val current = boundProfileId ?: return
        val viewModel = ViewModelProvider(this, browserFactory(current))[BrowserViewModel::class.java]

        val executor = ProfileSwitchExecutor(this)
        executor.switch(
            from = current,
            to = targetProfileId,
            restartIntentExtras = extras,
            // The activity's own scope: the protocol captures this activity and
            // its ViewModel, so the activity has to be what owns and can cancel
            // it (the executor used to make its own scope and never cancel it).
            scope = lifecycleScope,
            // An aborted switch leaves this process alive on the OLD profile.
            // Without this, pendingSwitch stayed true for good and the user
            // could never try again — on a screen that still showed the old
            // profile with no explanation.
            onFailed = { error ->
                pendingSwitch = false
                Log.w(TAG, "profile switch aborted: ${error.message}")
                viewModel.showMessage("Profile switch failed — still on this profile")
            },
            host = object : ProfileSwitchExecutor.Host {
                override fun stopNavigation() {
                    viewModel.stopLoading()
                    viewModel.activeSession?.stop()
                }

                override suspend fun saveTabState() {
                    viewModel.captureThumbnail()
                    viewModel.persistActiveTabNow()
                }

                override fun destroyBrowserContext() {
                    // Per-tab engines: EVERY live engine must go, not just the
                    // active one (the process restart would reap them, but the
                    // explicit destroy keeps the switch protocol honest).
                    viewModel.destroyAllWebViews()
                    viewModel.detachActiveSession()
                }

                override fun flushProfileState() {
                    // Last act before the process is killed: the engine commits
                    // whatever it still holds in memory. WebView keeps cookies
                    // in RAM and would lose them here; GeckoView has already
                    // written them through and says so by doing nothing. The
                    // engine owns that difference now, so no WebView type comes
                    // back above the boundary.
                    ProfileEngine.flushProfileState(application)
                }

                override fun releaseProfileResources() {
                    viewModel.clearInMemoryState()
                }

                override fun cleanupPrivateTabs() {
                    // The clear is SESSION-scoped, so it has to reach the live
                    // engines -- which is why it lives on the ViewModel and not
                    // here. Routing it through the ViewModel also means it and
                    // the tab-close path (BrowserViewModel.closeTab) do exactly
                    // the same thing, so a private tab's cookies do not depend
                    // on how the tab went away.
                    //
                    // Called only by ProfileSwitchExecutor.cleanupPrivateSession,
                    // which nothing invokes today; closing a private tab is the
                    // path that actually runs, and that one calls the ViewModel
                    // directly.
                    viewModel.clearPrivateSessionArtifacts()
                }

                override val restartActivityClass: Class<*> = BrowserActivity::class.java
            }
        )
    }

    private fun scheduleSelfRestart(profileId: ProfileId, url: String?) {
        val intent = Intent(this, BrowserActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_PROFILE_ID, profileId.value)
            url?.let { putExtra(EXTRA_INITIAL_URL, it) }
        }
        val pending = android.app.PendingIntent.getActivity(
            this, SELF_RESTART_REQUEST_CODE, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        // WAKEUP: the process dies the line below — only the alarm can
        // relaunch the engine, so it must fire even if the screen sleeps
        // mid-switch (CI showed the non-wakeup variant deferring ~5 s
        // while idle, leaving a dead surface up that long). See
        // [scheduleEngineRestart] for why this is not a bare
        // setExactAndAllowWhileIdle.
        alarm.scheduleEngineRestart(android.os.SystemClock.elapsedRealtime() + 350, pending)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /**
     * Cancels every pending engine-restart alarm.
     *
     * TWO tokens, and they cannot be one: [SELF_RESTART_REQUEST_CODE] relaunches
     * with CLEAR_TASK (safe, because its process is already dead when it fires)
     * and [SWITCH_BACKSTOP_REQUEST_CODE] relaunches without it (so it can never
     * tear down a live instance). `Intent.filterEquals` ignores flags, so only
     * the request code separates them -- see RestartAlarm.kt.
     *
     * A token that was never armed has no PendingIntent to read, so this is
     * safe to call unconditionally on every bind.
     */
    private fun cancelPendingRestartAlarm() {
        val alarm = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        for (requestCode in RESTART_REQUEST_CODES) {
            runCatching {
                // NO_CREATE, never UPDATE_CURRENT. The two tokens are told
                // apart by their request code alone, so UPDATE_CURRENT here
                // rewrites the ARMED token to this throwaway intent -- dropping
                // its profile extra and handing it CLEAR_TASK, the one flag the
                // backstop was deliberately built without. NO_CREATE reads the
                // armed token back untouched instead.
                val pending = android.app.PendingIntent.getActivity(
                    this, requestCode,
                    Intent(this, BrowserActivity::class.java),
                    android.app.PendingIntent.FLAG_NO_CREATE or
                        android.app.PendingIntent.FLAG_IMMUTABLE
                ) ?: return@runCatching
                alarm.cancel(pending)
            }
        }
    }

    /** Biometric gate for locked profiles (called from the UI). */
    fun gateProfile(profileName: String, onUnlocked: () -> Unit, onLocked: () -> Unit) {
        BiometricGate.unlock(this, profileName, onUnlocked, onLocked)
    }

    private fun browserFactory(profileId: ProfileId): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return BrowserViewModel(application, profileId) as T
            }
        }

    companion object {
        private const val TAG = "RoomGate"
        const val EXTRA_PROFILE_ID = "com.roombrowser.extra.PROFILE_ID"
        const val EXTRA_INITIAL_URL = "com.roombrowser.extra.INITIAL_URL"
    }
}
