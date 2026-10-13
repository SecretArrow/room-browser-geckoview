package com.roombrowser.agent

import android.content.Context
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.agent.UntrustedContent
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.UserAgents
import com.roombrowser.node.NodeRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `node_run`: one JavaScript program in the embedded Node runtime, as this
 * profile.
 *
 * The identity is the whole reason this class exists rather than the executor
 * calling the runtime directly. Node's own client announces `node` — the one
 * thing on this device that is not the profile, and the one thing the profile's
 * browser exists to prevent — so every script is handed the identity the page
 * would send: the profile's own user agent, or the engine's default when the
 * profile chose none, which is what that profile's pages send. A runtime that
 * was given no identity at all makes no request, so there is no path here that
 * reaches a server as Node.
 *
 * The runtime runs in a process of its own (see [NodeRunner]) but NOT in a
 * sandbox: it shares this app's UID and data directory, so a program the model
 * writes can read and write this profile's own files. That is a limit to state,
 * not to paper over.
 */
class AgentNodeRuntime(
    private val context: Context,
    private val profile: Profile
) {

    private val runner = NodeRunner(context)

    /**
     * Runs [script] and answers with what it produced, fenced as data: the
     * output can be a server's reply or a package's own words, so it is treated
     * exactly like page text rather than trusted for having come from our own
     * runtime.
     *
     * A script's failure — a throw, a timeout, a missing module — is a tool
     * failure carrying the runtime's own words, never a crash of the turn.
     */
    suspend fun run(script: String): ToolResult = withContext(Dispatchers.IO) {
        val userAgent = userAgent()
            ?: return@withContext ToolResult(
                false,
                "this profile has no user agent to send: Node refuses to make a request without " +
                    "one, so nothing was run. Set the profile's identity in Profile settings."
            )
        val result = runner.run(script, userAgent)
        if (!result.ok) {
            ToolResult(false, result.output)
        } else {
            ToolResult(true, UntrustedContent.wrap(result.output, "the Node runtime"))
        }
    }

    /**
     * The identity this profile's pages send. [UserAgents.effectiveUserAgent]
     * answers null when the profile picked none, which means "whatever the
     * engine sends" rather than "no identity" — so the engine is asked for the
     * second half.
     */
    private fun userAgent(): String? =
        UserAgents.effectiveUserAgent(profile.settings)?.takeIf { it.isNotBlank() }
            ?: ProfileEngine.defaultUserAgent(context)?.takeIf { it.isNotBlank() }
}
