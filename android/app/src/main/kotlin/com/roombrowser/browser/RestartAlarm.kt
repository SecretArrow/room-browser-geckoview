package com.roombrowser.browser

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build

/**
 * Request code of the token that RE-LAUNCHES the engine: armed by
 * `BrowserActivity.scheduleSelfRestart`, where the alarm is the only thing
 * left that can bring the browser back (the process dies on the next line).
 */
internal const val SELF_RESTART_REQUEST_CODE = 4242

/**
 * Request code of the backstop token armed by [ProfileSwitchExecutor].
 *
 * It is a SEPARATE token from [SELF_RESTART_REQUEST_CODE] on purpose, and the
 * request code is the only thing that can separate them: `Intent.filterEquals`
 * -- which decides whether two `PendingIntent`s are the same token -- compares
 * component, action, data and categories but NOT flags. Two tokens that differ
 * only in their flags would therefore be one token, and the second
 * `FLAG_UPDATE_CURRENT` would silently rewrite the first one's intent.
 */
internal const val SWITCH_BACKSTOP_REQUEST_CODE = 4243

/** Both engine-restart tokens, for the one call site that cancels them. */
internal val RESTART_REQUEST_CODES =
    intArrayOf(SELF_RESTART_REQUEST_CODE, SWITCH_BACKSTOP_REQUEST_CODE)

/**
 * The engine-restart backstop alarm, shared by [ProfileSwitchExecutor] and
 * `BrowserActivity.scheduleSelfRestart`.
 *
 * Both call sites kill the ':browser' process on the next line, so nothing
 * but this alarm can bring the engine back — which is why they want it to
 * survive Doze. They each used to call `setExactAndAllowWhileIdle` inside a
 * `runCatching`, with a plain `set()` in `onFailure`.
 *
 * That exact call throws `SecurityException` on API 31+ unless the app holds
 * `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM`, and this app holds NEITHER on
 * purpose: `USE_EXACT_ALARM` is a Play-restricted permission for alarm clock
 * and calendar apps, which a browser is not. So on every Android 12+ device
 * the exact call threw every time and the fallback was the only path that
 * ever ran — and that fallback was `set()`, which Doze may defer for
 * minutes, leaving a dead browser surface on screen for exactly as long.
 *
 * Asking [AlarmManager.canScheduleExactAlarms] first means the exact variant
 * is attempted only where it can succeed, and the fallback is
 * `setAndAllowWhileIdle`: still inexact, but it breaks Doze, which is the
 * property that actually matters here.
 */
internal fun AlarmManager.scheduleEngineRestart(
    triggerAtElapsed: Long,
    pending: PendingIntent
) {
    val exactAllowed =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms()
    runCatching {
        if (exactAllowed) {
            setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pending
            )
        } else {
            setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pending
            )
        }
    }.onFailure {
        // The permission can be revoked between the check and the call, and
        // some vendors restrict exact alarms further. Never let the backstop
        // throw out of a teardown path.
        runCatching {
            setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsed, pending
            )
        }
    }
}
