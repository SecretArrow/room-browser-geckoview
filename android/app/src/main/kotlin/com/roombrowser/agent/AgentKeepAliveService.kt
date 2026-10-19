package com.roombrowser.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.roombrowser.browser.BrowserActivity

/**
 * Foreground (dataSync) service that keeps the ':browser' process alive and
 * the CPU awake while an AI-agent turn is executing, so agent tasks keep
 * running when the user leaves the app or turns the screen off.
 *
 * The service is intentionally tiny: the agent loop itself lives in
 * [BrowserAgentController]; this class only holds a partial wake lock and
 * mirrors the turn's progress into an ongoing notification (with a Stop
 * action that cancels the current turn).
 *
 * Lifecycle (driven via [AgentForeground]):
 *  - begin(context)      → startForegroundService while the app is foreground
 *                          (send() is a user action, so this always holds)
 *  - status(text)        → update the ongoing notification
 *  - finish()            → release the wake lock and stop
 *
 * The notification mirror is paced, not naive: status changes that say what
 * the notification already shows are dropped, and real changes are never
 * re-posted faster than [AgentNotificationPacer.MIN_POST_INTERVAL_MS] apart
 * — see that class for why the streamed turn made both rules load-bearing.
 */
class AgentKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * What the notification currently shows (status text, pause state) and
     * how long ago it was posted — the dedupe and pacing decisions live
     * here rather than in fields on the service, because they are pure
     * decisions a JVM test can pin without instantiating an Android Service.
     */
    private val pacer = AgentNotificationPacer()

    /**
     * Runs the deferred side of the pacer's verdicts. Immediate posts may
     * be made from the agent's IO listener threads; the deferred ones always
     * land on the main looper. Only thread-safe calls (postDelayed/remove)
     * are ever made through it, so no lock is needed to use it.
     */
    private val notifyHandler = Handler(Looper.getMainLooper())

    /**
     * Set once the turn (or the system) has asked the service to stop. The
     * notification is gone by then, so a deferred post still waiting out its
     * pacing window must not be able to resurrect it, and a status call
     * racing in behind the stop must not post a fresh one — either would
     * leave a notification nothing will ever remove. Cleared only by a
     * successful [goForeground], the one event that legitimately puts the
     * notification back (a second turn's begin can re-promote this same
     * instance before the system gets around to destroying it).
     */
    @Volatile
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AgentForeground.instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AgentForeground.stopCurrentTurn?.invoke()
                requestStop()
            }
            // The toggle does the deciding and then reports back through
            // [setPaused], so the action's label can never disagree with the
            // turn's actual state.
            ACTION_PAUSE -> {
                val toggle = AgentForeground.pauseCurrentTurn
                if (toggle == null) {
                    requestStop()
                } else {
                    goForeground(pacer.showingText() ?: "Working…")
                    toggle.invoke()
                }
            }
            else -> {
                val seed = intent?.getStringExtra(EXTRA_STATUS)
                goForeground(seed ?: pacer.showingText() ?: "Working…")
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Called (same process) by [AgentForeground.status]. The pacer decides
     * what the change is worth — see [AgentNotificationPacer] for the three
     * possible answers and why the per-token callers this used to serve
     * cannot reach the notification at all any more.
     */
    fun updateStatus(status: String?) {
        if (status.isNullOrBlank()) return
        apply(pacer.status(status))
    }

    /** Called (same process) by [AgentForeground.setPaused]. */
    fun setPaused(paused: Boolean) {
        apply(pacer.paused(paused))
    }

    private fun apply(verdict: AgentNotificationPacer.Verdict) {
        when (verdict) {
            AgentNotificationPacer.Verdict.NothingToDo -> {}
            AgentNotificationPacer.Verdict.PostNow -> notifyNow()
            is AgentNotificationPacer.Verdict.PostLater ->
                notifyHandler.postDelayed({ notifyNow() }, verdict.delayMs)
        }
    }

    private fun notifyNow() {
        // A stopped turn's notification must not come back to life: the
        // deferred post can fire after requestStop already removed the real
        // one, and a plain notify() would then leave an orphan on the shade.
        if (stopped) return
        pacer.posted()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(pacer.showingText() ?: "Working…"))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — the service still runs.
        }
    }

    /** Called (same process) by [AgentForeground.finish]. */
    fun requestStop() {
        // The turn is over: drop any deferred post still waiting out its
        // pacing window, and refuse the ones that race in behind it — both
        // would re-post a notification that stopForeground is removing.
        notifyHandler.removeCallbacksAndMessages(null)
        stopped = true
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        notifyHandler.removeCallbacksAndMessages(null)
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        if (AgentForeground.instance === this) AgentForeground.instance = null
        super.onDestroy()
    }

    /**
     * Android 15 caps a `dataSync` foreground service at 6 hours per 24 and
     * calls this when the budget runs out. The contract is strict: the service
     * MUST stop itself here, or the system raises
     * `ForegroundServiceDidNotStopInTimeException` and the process is killed —
     * taking the whole ':browser' engine, its tabs and any unsaved page state
     * with it. targetSdk is 35, so the cap applies; without this override a
     * long agent session was a scheduled crash.
     *
     * The wake lock's own 30-minute timeout means a turn should never get
     * anywhere near the cap, so reaching this point means the turn is wedged
     * and stopping is the correct outcome anyway. The turn is cancelled first
     * so the controller unwinds instead of being severed mid-step.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AgentForeground.stopCurrentTurn?.invoke()
        requestStop()
    }

    // ------------------------------------------------------------ internals

    /**
     * Promotes the service, and gives up cleanly if the system refuses.
     *
     * [ServiceCompat.startForeground] is not a safe call on API 31+: if the app
     * has left the foreground between [AgentForeground.begin] and this method
     * running (the user backgrounds the app the instant they hit send, which is
     * exactly what the keep-alive exists for), the system throws
     * `ForegroundServiceStartNotAllowedException`. API 34+ adds a second
     * refusal — a missing or disallowed service type. Either way the throw used
     * to escape [onStartCommand], so the service stayed a plain background
     * service holding a 30-minute PARTIAL_WAKE_LOCK that nothing would release
     * until the process died: a battery drain with no notification to show for
     * it. Now the lock goes back immediately and the service stops; the agent
     * turn keeps running, it just no longer survives the process going away.
     */
    private fun goForeground(status: String) {
        acquireWakeLock()
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(status), type)
            // A direct post supersedes any deferred one still queued: a
            // deferral exists only to carry a change some pacing window
            // postponed, and this post has just shown that change — whatever
            // it was — directly. Two callers can arrive here with one in
            // flight: the Pause tap re-promotes through this method while a
            // status deferral is waiting out its window, and a stop cannot
            // stop the dying turn's own last status calls from SCHEDULING a
            // deferral after requestStop already swept the handler — a stop
            // sweeps once, the unwinding turn may call again. Either leftover
            // would fire into a window it did not open and re-post a shade
            // that already shows its content.
            notifyHandler.removeCallbacksAndMessages(null)
            // Posted directly, outside any pacing window, so the pacer must
            // be told what the notification now shows and that no window is
            // open: the next real status change then posts at once instead
            // of waiting out a window no notify() ever opened. This is what
            // keeps the turn-start "Working…" → first status transition
            // immediate — and clears the pacer's memory of any deferral, so
            // its bookkeeping agrees with the handler sweep above: nothing
            // is in flight.
            pacer.reset(status)
            stopped = false
        } catch (t: Throwable) {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            wakeLock = null
            // Dying without promoting: nothing may post on the way out.
            notifyHandler.removeCallbacksAndMessages(null)
            stopped = true
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "roombrowser:agent").apply {
                setReferenceCounted(false)
                acquire(WAKE_TIMEOUT_MS)
            }
        }
    }

    // The three actions' PendingIntents, built once per service life. Each
    // is FLAG_UPDATE_CURRENT over a fixed request code and a fixed Intent,
    // so a rebuild mints a token that is bit-for-bit the one it replaces —
    // and the per-token path this notification used to serve re-created all
    // three of them on every status line, tens of times a second, purely to
    // hand the system back the same tokens it already held. `by lazy`
    // synchronizes the first build; after that they never change.
    private val openIntent by lazy {
        PendingIntent.getActivity(
            this, 0,
            Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    private val stopIntent by lazy {
        PendingIntent.getService(
            this, 1,
            Intent(this, AgentKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    private val pauseIntent by lazy {
        PendingIntent.getService(
            this, 2,
            Intent(this, AgentKeepAliveService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Room Agent")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            // Pause sits beside Stop so the two ways out of a running turn are
            // equally close: one holds it, the other ends it.
            .addAction(0, if (pacer.isPaused()) "Resume" else "Pause", pauseIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent progress", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val ACTION_START = "com.roombrowser.agent.START"
        const val ACTION_STOP = "com.roombrowser.agent.STOP"
        const val ACTION_PAUSE = "com.roombrowser.agent.PAUSE"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL_ID = "agent_progress"
        private const val NOTIFICATION_ID = 402
        private const val WAKE_TIMEOUT_MS = 30 * 60 * 1000L
    }
}

/**
 * The keep-alive notification's dedupe and pacing, as pure decisions.
 *
 * The streamed turn used to call [AgentForeground.status] for EVERY token
 * with one of two constants ("Writing…"/"Thinking…"), and each call rebuilt
 * the whole notification — three fresh PendingIntents and a
 * NotificationManager.notify() binder round-trip — for text the notification
 * was already showing. The controller now drops identical status text at its
 * own seam; this class is the second, service-side half of the same rule,
 * because it is the half that knows what the notification actually shows:
 *
 *  - a change whose visible text and pause state match what is already
 *    posted is dropped outright — nothing on screen can have changed;
 *  - a real change posts at once, unless one was posted inside the last
 *    [minIntervalMs] — then it is deferred to the end of that window, never
 *    further, and never stacked: ONE deferred post is outstanding, and it
 *    builds from the CURRENT state when it fires, so the last true status
 *    always lands — just never faster than the window allows.
 *
 * Extracted from the service (which cannot live in a JVM test) so these
 * rules are pinned by AgentNotificationPacerTest. All methods are
 * synchronized: status calls arrive on the agent's IO listener threads
 * while pause flips and the deferred post arrive on the main one.
 */
internal class AgentNotificationPacer(
    /** Minimum spacing between actual notification posts. */
    private val minIntervalMs: Long = MIN_POST_INTERVAL_MS,
    /**
     * Monotonic-ish milliseconds, injectable so the window is testable.
     * nanoTime rather than currentTimeMillis because the wall clock can
     * step backwards under NTP, and a pacing window that runs backwards
     * would defer forever.
     */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {

    /** What the service should do with a status or pause change. */
    sealed interface Verdict {
        /**
         * Nothing to do: either the notification already shows exactly this,
         * or a deferred post is outstanding and will carry it — deferred
         * posts build from current state, so one pending post serves every
         * change that lands inside its window.
         */
        object NothingToDo : Verdict

        /** Post the notification now. */
        object PostNow : Verdict

        /** Post it no sooner than [delayMs] from now — the trailing edge. */
        data class PostLater(val delayMs: Long) : Verdict
    }

    /** The status text the notification currently shows; null before the first. */
    private var showingText: String? = null

    /**
     * The pause state its action label currently shows. False from birth,
     * because a promotion posts before any pause flip and that notification
     * already builds the "Pause" label — the mirror starts where the label
     * does. This is what makes the turn-end setPaused(false) of a turn that
     * never paused dedupe exactly like the service's old lastPaused field
     * did, instead of posting a notification whose visible label cannot
     * have changed.
     */
    private var showingPaused: Boolean = false

    /** When the notification was last posted, in [nowMs] units; null never-posted. */
    private var lastPostMs: Long? = null

    /** Whether a deferred post is outstanding. */
    private var pending = false

    @Synchronized
    fun status(text: String): Verdict {
        if (text == showingText) return Verdict.NothingToDo
        showingText = text
        return pace()
    }

    @Synchronized
    fun paused(paused: Boolean): Verdict {
        if (paused == showingPaused) return Verdict.NothingToDo
        showingPaused = paused
        return pace()
    }

    /** The status text a notification build should use. */
    @Synchronized
    fun showingText(): String? = showingText

    /** Whether the Pause action should read "Resume". */
    @Synchronized
    fun isPaused(): Boolean = showingPaused

    /**
     * A post has actually been made — the pacing window restarts from now.
     * The service calls this for every post it performs, immediate or
     * deferred, so the window measures real posts and not verdicts.
     */
    @Synchronized
    fun posted() {
        lastPostMs = nowMs()
        pending = false
    }

    /**
     * The notification was posted DIRECTLY — startForeground, not notify() —
     * so it now shows [text] and no pacing window is open: the next change
     * must post at once. [AgentKeepAliveService.goForeground] calls this
     * after every successful promotion (including the re-promotion a second
     * turn's begin makes of an instance the system never quite destroyed),
     * alongside sweeping the handler of any deferred post still queued — the
     * sweep retires the runnable, this retires the bookkeeping that said one
     * was coming, and the two must agree or a change inside the next window
     * would be dropped on the faith that a post is in flight when it is not.
     * The pause label is NOT reset here: a direct post still builds it from
     * the pause state this pacer already mirrors.
     */
    @Synchronized
    fun reset(text: String) {
        showingText = text
        lastPostMs = null
        pending = false
    }

    /** Runs under the caller's monitor; see the synchronized methods above. */
    private fun pace(): Verdict {
        val now = nowMs()
        val last = lastPostMs
        if (last == null || now - last >= minIntervalMs) {
            lastPostMs = now
            // An immediate post supersedes any deferral still in flight: a
            // scheduled trailing post whose fire time has passed but not yet
            // run (a busy main looper can hold it for a while) must not keep
            // `pending` true, or the NEXT change inside this fresh window
            // would be dropped on the faith that a post is coming — when the
            // post it was waiting for has already been made.
            pending = false
            return Verdict.PostNow
        }
        if (pending) return Verdict.NothingToDo
        pending = true
        return Verdict.PostLater(minIntervalMs - (now - last))
    }

    companion object {
        /**
         * The pacing floor between real posts. The controller's dedupe
         * already keeps the per-token storm away from the notification; this
         * is the second line of defence — any caller that ever becomes hot
         * again (a chatty pause loop, a future per-token status) is capped at
         * two posts a second no matter what it does, while a trailing-edge
         * deferral keeps the LAST true state from being lost to the cap.
         */
        const val MIN_POST_INTERVAL_MS = 500L
    }
}

/**
 * Process-local bus between [BrowserAgentController] and the keep-alive
 * service — both live in the ':browser' process, so direct references are
 * safe and no IPC is needed.
 */
object AgentForeground {

    /** Set while a turn runs — the notification's Stop action invokes it. */
    @Volatile
    var stopCurrentTurn: (() -> Unit)? = null

    /**
     * Set while a turn runs — the notification's Pause/Resume action invokes
     * it. One toggle rather than two actions, because the label the user reads
     * is then always the state they will get.
     */
    @Volatile
    var pauseCurrentTurn: (() -> Unit)? = null

    @Volatile
    internal var instance: AgentKeepAliveService? = null

    /** Starts the keep-alive service (must be called from the foreground). */
    fun begin(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentKeepAliveService::class.java)
                    .setAction(AgentKeepAliveService.ACTION_START)
            )
        }
    }

    /** Mirrors a progress line into the ongoing notification. */
    fun status(text: String?) {
        instance?.updateStatus(text)
    }

    /**
     * Switches the notification's Pause action between "Pause" and "Resume".
     * Driven by the turn's actual state rather than by whoever tapped, so the
     * button cannot end up describing the wrong thing.
     */
    fun setPaused(paused: Boolean) {
        instance?.setPaused(paused)
    }

    /** Stops the service when the turn is over. */
    fun finish() {
        instance?.requestStop()
    }
}
