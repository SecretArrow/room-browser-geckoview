package com.roombrowser.browser

import android.content.Intent
import android.content.IntentSender
import androidx.activity.result.IntentSenderRequest
import com.roombrowser.engine.EngineActivityDelegate

/**
 * The browser activity's answer to an engine that needs a system window
 * opened on the page's behalf -- in practice, the device's passkey prompt.
 *
 * ONE LAUNCHER, MANY REQUESTS, ANSWERED IN ORDER. A single
 * `registerForActivityResult` launcher keeps one request code for its whole
 * life, so two launches in flight at once are not told apart by the framework.
 * Passkey prompts are modal system UI and arrive one at a time, so a queue is
 * the honest model. Keeping only the newest callback would leave the earlier
 * engine call waiting for a result that never comes -- which is the endless
 * spinner this exists to remove.
 */
internal class EngineActivityBridge(
    private val launch: (IntentSenderRequest) -> Unit
) : EngineActivityDelegate {

    private val pending = ArrayDeque<(Intent?) -> Unit>()

    override fun startIntentSenderForResult(
        intentSender: IntentSender,
        onResult: (Intent?) -> Unit
    ) {
        pending.addLast(onResult)
        launch(IntentSenderRequest.Builder(intentSender).build())
    }

    /** Answer the oldest outstanding request with whatever the window returned. */
    fun deliver(data: Intent?) {
        if (pending.isEmpty()) return
        pending.removeFirst().invoke(data)
    }

    /**
     * Answer everything still outstanding with a cancellation.
     *
     * Called when the activity goes away. A callback left in the queue would
     * hold the engine's `GeckoResult` unresolved, and an unresolved one is a
     * page that never finishes loading -- the failure mode, not a tidy-up.
     */
    fun cancelAll() {
        while (pending.isNotEmpty()) pending.removeFirst().invoke(null)
    }
}
