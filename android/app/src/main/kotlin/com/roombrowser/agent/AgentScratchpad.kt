package com.roombrowser.agent

import android.content.Context
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.domain.model.Profile
import com.roombrowser.engine.EngineSession

/**
 * The detached engine session `compute_js` runs in: a blank document with no
 * network, created on first use and closed with the turn.
 *
 * The session is created the same way as a headless chat's page (see
 * [BrowserAgentController.createHeadlessPage]) and is NEVER attached to a view,
 * so a script here cannot appear on the user's screen or touch the page they
 * are on. It is deliberately separate from the turn's own page: a chat's tab is
 * live and may have navigated, and the whole point of the scratchpad is that it
 * is not.
 */
class AgentScratchpad(private val context: Context, private val profile: Profile) {

    private var session: EngineSession? = null

    /** The scratchpad's session, started on first call; null when it cannot start. */
    fun session(): EngineSession? {
        session?.let { return it }
        return runCatching {
            ProfileEngine.createSession(
                context = context,
                profile = profile,
                sessionId = "agent-scratchpad-${System.currentTimeMillis()}",
                isPrivate = false
            ).also { it.loadHtml(BLANK_DOCUMENT) }
        }.getOrNull().also { session = it }
    }

    fun close() {
        session?.let { runCatching { it.close() } }
        session = null
    }

    private companion object {
        const val BLANK_DOCUMENT =
            "<!doctype html><html><head><meta charset=\"utf-8\"></head><body></body></html>"
    }
}
