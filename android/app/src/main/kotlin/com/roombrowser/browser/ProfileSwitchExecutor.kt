package com.roombrowser.browser

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.profile.ProfileSwitchStateMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Executes the profile-switch security protocol (spec section 48) against
 * the domain state machine:
 *
 *   1. stop navigation          5. release profile resources
 *   2. save tab state           6. load new profile context
 *   3. destroy browser context  7. restore new profile tabs
 *   4. flush profile state
 *
 * Because WebView.setDataDirectorySuffix() is process-wide, steps 6-7 are
 * performed by RESTARTING the ':browser' process with the new profile id:
 * the current WebViews are destroyed, tabs are persisted, a restart intent
 * is scheduled via AlarmManager, and the process terminates itself.
 */
class ProfileSwitchExecutor(
    private val context: Context,
    private val stateMachine: ProfileSwitchStateMachine = ProfileSwitchStateMachine()
) {

    /** Steps the executor performs in-process before the restart. */
    interface Host {
        /** Step 1 — stop current navigation. */
        fun stopNavigation()
        /** Step 2 — persist all open tab state to Room. */
        suspend fun saveTabState()
        /** Step 3 — destroy every WebView in the browser context. */
        fun destroyBrowserContext()
        /** Step 4 — flush cookies/storage state to disk. */
        fun flushProfileState()
        /** Step 5 — release in-memory caches, thumbnails, pooled objects. */
        fun releaseProfileResources()
        /** Private-tab cleanup hook (session cookies). */
        fun cleanupPrivateTabs()
        /** Target activity class used for the restart intent. */
        val restartActivityClass: Class<*>
    }

    /**
     * Runs the protocol and restarts the process bound to [to].
     *
     * [scope] is the CALLER's scope (the activity's `lifecycleScope`) on
     * purpose. This used to allocate `CoroutineScope(Dispatchers.Main.immediate)`
     * per switch and never cancel it, so the coroutine — which captures [host],
     * and through it the Activity and its ViewModel — stayed rooted for as long
     * as it ran, with no owner able to stop it.
     *
     * [onFailed] is how an aborted switch becomes visible. [runStep] rethrows,
     * and a throw out of a bare `launch` is an UNCAUGHT exception: it killed
     * the ':browser' process before [scheduleRestart] had run, so a switch that
     * failed at any step left a dead engine with no restart scheduled and no
     * message. Now the throw is caught, the process is left alive on the old
     * profile — which the state machine has already rolled back to — and the
     * caller gets to re-enable its UI and say so.
     */
    fun switch(
        from: ProfileId,
        to: ProfileId,
        restartIntentExtras: Intent.() -> Unit,
        host: Host,
        scope: CoroutineScope,
        onFailed: (Throwable) -> Unit = {}
    ) {
        check(stateMachine.snapshot().state == ProfileSwitchStateMachine.State.IDLE) {
            "A profile switch is already in progress"
        }
        scope.launch {
            try {
                stateMachine.onEvent(ProfileSwitchStateMachine.Event.Begin(from, to))

                runStep(ProfileSwitchStateMachine.Step.STOP_NAVIGATION) { host.stopNavigation() }
                runStep(ProfileSwitchStateMachine.Step.SAVE_TAB_STATE) { host.saveTabState() }
                runStep(ProfileSwitchStateMachine.Step.DESTROY_BROWSER_CONTEXT) { host.destroyBrowserContext() }
                runStep(ProfileSwitchStateMachine.Step.FLUSH_PROFILE_STATE) { host.flushProfileState() }
                runStep(ProfileSwitchStateMachine.Step.RELEASE_PROFILE_RESOURCES) { host.releaseProfileResources() }

                // Steps 6-7 (load new profile + restore its tabs) happen AFTER
                // the restart. The kill is LAST and only on this path: a
                // scheduleRestart that threw must never be followed by a
                // process kill with no relaunch scheduled.
                scheduleRestart(host, to, restartIntentExtras)
                terminateProcess()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                onFailed(t)
            }
        }
    }

    private suspend fun runStep(step: ProfileSwitchStateMachine.Step, block: suspend () -> Unit) {
        try {
            block()
            stateMachine.onEvent(ProfileSwitchStateMachine.Event.StepCompleted)
        } catch (t: Throwable) {
            stateMachine.onEvent(ProfileSwitchStateMachine.Event.Error(step, t.message ?: "unknown error"))
            // Recover to a safe state: abort the switch, keep the old profile.
            stateMachine.onEvent(ProfileSwitchStateMachine.Event.Abort)
            throw IllegalStateException("Profile switch failed at $step", t)
        }
    }

    /** Fire-and-forget cleanup of a private-tab session. */
    fun cleanupPrivateSession(host: Host) {
        host.cleanupPrivateTabs()
    }

    private fun scheduleRestart(host: Host, to: ProfileId, extras: Intent.() -> Unit) {
        val intent = Intent(context, host.restartActivityClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_PROFILE_ID, to.value)
            extras()
        }
        // PRIMARY relaunch path (root-cause fix for the API 29+ background
        // activity launch block): THIS process is the foreground app at the
        // moment of the switch (the browser surface is visible), so a direct
        // startActivity is NOT a background start and is always permitted.
        // The launch stays in flight while the process terminates below;
        // the system then re-launches the activity in a FRESH ':browser'
        // process bound to the new profile — the exact mechanism every
        // MainActivity-driven profile create already uses (CI-proven
        // "Displayed +~1.3s cold" on every engine boot). The alarm-only
        // variant this replaces was BAL-DENIED on the CI emulator once the
        // task died with the process (logcat: "Background activity start ..."
        // with no Displayed line ever following), leaving the switcher
        // create-then-switch flow with a dead screen forever.
        runCatching { context.startActivity(intent) }
        // BACKSTOP only: if the in-flight launch above is somehow lost before
        // the system registers it, the alarm relaunches.
        //
        // IT MUST NOT BE ABLE TO DESTROY ANYTHING. The copy below drops
        // FLAG_ACTIVITY_CLEAR_TASK, so a backstop that arrives on top of a live
        // engine becomes onNewIntent on the singleTask instance -- harmless --
        // while still starting a fresh one when nothing is up.
        //
        // That is a fix, not caution. The alarm is due at T+350 and the
        // activity it races does not reach cancelPendingRestartAlarm() until
        // ~T+1150, so the cancel is a LOSING RACE BY CONSTRUCTION: it wins only
        // when the system happens to defer the inexact alarm past the cold
        // start. When it lost (run 37185155108) the backstop's CLEAR_TASK tore
        // down the freshly-started engine, its replacement's start was
        // cancelled, and the process was left alive with no activity at all --
        // onResume, then onCleared 43 ms later, no frame ever drawn. A backstop
        // that cannot destroy needs no cancellation to be safe.
        val backstop = Intent(intent).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val pending = PendingIntent.getActivity(
            context,
            SWITCH_BACKSTOP_REQUEST_CODE,
            backstop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + RESTART_DELAY_MS
        // WAKEUP: the process dies moments after this call, so the alarm is the
        // last thing that can bring the engine back if the primary launch above
        // was lost -- it must survive Doze. See [scheduleEngineRestart] for why
        // this is not a bare setExactAndAllowWhileIdle.
        alarm.scheduleEngineRestart(triggerAt, pending)
    }

    /**
     * Hard-terminate ONLY the ':browser' process. The Android system treats
     * this as a normal process death; the scheduled restart relaunches the
     * browser activity with the NEW profile id, whose WebView data-directory
     * suffix differs → genuinely separate storage context.
     */
    private fun terminateProcess() {
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
            Runtime.getRuntime().exit(0)
        }, KILL_DELAY_MS)
    }

    companion object {
        const val EXTRA_PROFILE_ID = "com.roombrowser.extra.PROFILE_ID"
        const val EXTRA_RESTART = "com.roombrowser.extra.RESTART"
        private const val RESTART_DELAY_MS = 350L
        private const val KILL_DELAY_MS = 250L
    }
}
