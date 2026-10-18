package com.roombrowser.agent

import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The real [AirplaneModeBridge]: Android's own airplane-mode state, the su
 * binary a rooted phone carries, and the system settings screen a phone
 * without root is handed instead.
 *
 * READING NEEDS NO PERMISSION — [Settings.Global.AIRPLANE_MODE_ON] answers
 * to any app — which is what makes the whole feature honest: every path can
 * VERIFY the state instead of trusting a command's exit code or the user's
 * word. WRITING is the opposite: Android forbids a normal app toggling
 * radios, so the only automatic route is `su` (primary: the modern
 * `cmd connectivity airplane-mode` shell command; fallback: the classic
 * settings-write + broadcast pair, ONE su invocation), and a phone without
 * root gets the guided hand-off — the settings screen opened for the person
 * to flip it themselves.
 *
 * THE ROOT DETECTION IS DELIBERATELY CHEAP. The su BINARY's presence is the
 * honest cheap signal; whether the root manager GRANTS the shell to this app
 * is answered only when a command actually runs, which is why [setViaRoot]
 * verifies and reports — a device that carries su but denies it reads as
 * "rooted, the command did not take", never as silent success.
 */
class AirplaneModeController(
    private val context: Context,
    private val allowed: suspend () -> Boolean
) : AirplaneModeBridge {

    @Volatile
    private var rootedAnswer: Boolean? = null

    override val rooted: Boolean
        get() = rootedAnswer ?: listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su"
        ).any { path -> File(path).exists() }.also { rootedAnswer = it }

    override fun currentState(): Boolean? = when (
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1)
    ) {
        1 -> true
        0 -> false
        else -> null
    }

    override suspend fun allowed(): Boolean = allowed()

    override suspend fun setViaRoot(on: Boolean): Boolean {
        // The two pinned fragments are the ONLY thing that ever reaches the
        // shell: no user text, no model output, no state read from the device
        // is interpolated — the command is one of two constants with the
        // direction's own pinned word in it. (SECURITY.md says exactly this.)
        val primary = "cmd connectivity airplane-mode ${if (on) "enable" else "disable"}"
        val fallback =
            "settings put global airplane_mode_on ${if (on) 1 else 0} && " +
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on"
        val ok = runSu(primary) || runSu(fallback)
        // Trust-but-verify: a command that exits 0 but left the state alone
        // (a root manager that denied the shell silently, a vendor that
        // renamed the setting) is a FAILURE, reported as one.
        return ok && currentState() == on
    }

    override fun openSettings(): Boolean = runCatching {
        // startActivity, NOT resolveActivity: on API 30+ package visibility
        // filtering can hide com.android.settings from resolveActivity even
        // though the launch itself works, and a spurious null would claim
        // this device has no such screen. The launch is the only honest test
        // of that — the NetworkWarningActivity shortcut's own lesson.
        context.startActivity(
            Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess

    override suspend fun waitUntil(on: Boolean, timeoutMs: Long): Boolean {
        // Poll, not a broadcast receiver: no registration lifecycle, works
        // from any thread, and sub-second resolution is plenty for a switch a
        // person flips with a finger.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (currentState() == on) return true
            if (System.currentTimeMillis() >= deadline) return currentState() == on
            delay(POLL_MS)
        }
    }

    /**
     * ONE su invocation, stderr merged into stdout, with an 8 s cap: a root
     * prompt nobody answers must not wedge the chat forever — it is destroyed
     * and the answer is whatever the fallback and the verification say.
     *
     * Runs on Dispatchers.IO because every caller of the bridge sits on the
     * main dispatcher (the controller's scope, the executor's own
     * `withContext(Dispatchers.Main)`), and a blocking `waitFor` there would
     * ANR the app for the full 8 seconds.
     */
    private suspend fun runSu(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            try {
                if (!process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return@withContext false
                }
                process.exitValue() == 0
            } finally {
                process.destroyForcibly()
            }
        } catch (_: Exception) {
            // No su on PATH, the shell refused to start, or the wait was
            // interrupted — all one answer: the command did not run.
            false
        }
    }

    private companion object {
        /** The su cap — a root prompt nobody answers must not wedge the chat. */
        const val SU_TIMEOUT_SECONDS = 8L

        /** The waitUntil poll cadence — the same number the command's poll rides. */
        const val POLL_MS = 500L
    }
}
