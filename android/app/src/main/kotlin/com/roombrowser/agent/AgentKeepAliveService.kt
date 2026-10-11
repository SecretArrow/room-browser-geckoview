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
import android.os.IBinder
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
 */
class AgentKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStatus: String = "Working…"
    private var lastPaused: Boolean = false

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
                    goForeground(lastStatus)
                    toggle.invoke()
                }
            }
            else -> {
                intent?.getStringExtra(EXTRA_STATUS)?.let { lastStatus = it }
                goForeground(lastStatus)
            }
        }
        return START_NOT_STICKY
    }

    /** Called (same process) by [AgentForeground.status]. */
    fun updateStatus(status: String?) {
        if (status.isNullOrBlank()) return
        lastStatus = status
        notifyLast()
    }

    /** Called (same process) by [AgentForeground.setPaused]. */
    fun setPaused(paused: Boolean) {
        if (paused == lastPaused) return
        lastPaused = paused
        notifyLast()
    }

    private fun notifyLast() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(lastStatus))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — the service still runs.
        }
    }

    /** Called (same process) by [AgentForeground.finish]. */
    fun requestStop() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
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
        } catch (t: Throwable) {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            wakeLock = null
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

    private fun buildNotification(status: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AgentKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pause = PendingIntent.getService(
            this, 2,
            Intent(this, AgentKeepAliveService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Room Agent")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            // Pause sits beside Stop so the two ways out of a running turn are
            // equally close: one holds it, the other ends it.
            .addAction(0, if (lastPaused) "Resume" else "Pause", pause)
            .addAction(0, "Stop", stop)
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
