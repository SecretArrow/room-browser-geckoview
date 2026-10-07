package com.roombrowser.engine

import android.content.Intent
import android.content.IntentSender

/**
 * The app's window, as an engine that has to open another app's window and
 * read its answer is allowed to see it.
 *
 * WHY AN ENGINE NEEDS THIS AT ALL. A WebView answers the app's own prompts
 * through callbacks the app already implements, because the app owns it and is
 * itself an Activity. GeckoView is a separate process with no Activity of its
 * own: when a page's request needs a SYSTEM window, the engine hands the app a
 * `PendingIntent` and waits for the result. Passkeys are that case -- the
 * credential UI belongs to the device's credential provider and is only
 * reachable through an Activity -- so with no delegate installed the engine's
 * WebAuthn path fails the moment a page asks for a credential, and a page whose
 * only sign-in is a passkey sits on a spinner it will never leave.
 *
 * THE CONTRACT IS THE SHAPE OF A RESULT, not the shape of GeckoView's
 * interface: an [IntentSender] and a callback. `android.content.IntentSender`
 * is framework, not engine, so `:app` never names
 * `GeckoRuntime.ActivityDelegate` and the boundary test keeps holding.
 *
 * EXACTLY ONE CALLBACK, ALWAYS. An implementation must invoke [onResult] once
 * for every call, with null when the user cancelled or the window could not be
 * opened. Never calling it is not a refusal but a hang: the engine is holding
 * the page's request open until it resolves.
 */
fun interface EngineActivityDelegate {
    fun startIntentSenderForResult(intentSender: IntentSender, onResult: (Intent?) -> Unit)
}
