package com.roombrowser.agent

import com.roombrowser.domain.agent.AgentAppActions
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.task.AiTaskPermissions
import com.roombrowser.domain.task.profileUnattendedRefusal
import com.roombrowser.domain.task.unattendedRefusal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The grants a task was saved with, applied in front of the executor a visible
 * run has to use.
 *
 * A run that drives a real tab goes through [AgentToolExecutor] — the chat's
 * executor, which is the only one built for a live tab and therefore has no
 * permission model of its own. Without this gate the same task would silently
 * gain the app-control and wallet tools it is denied when it runs hidden, so
 * the surface a task chose would change what the task may do.
 *
 * [allowProfileTools] is the one thing a SETTING can add: with it on, the
 * profile's notes and authenticator codes become reachable to an unattended
 * run, which is what makes "log in with the code and write down what happened"
 * work with nobody watching. It never widens to an action that destroys
 * something — see [AgentAppActions.UNATTENDED_PROFILE_ACTIONS].
 */
class ScheduledRunToolGate(
    private val delegate: ToolExecutor,
    private val permissions: AiTaskPermissions,
    private val confirmActions: Boolean,
    private val allowProfileTools: Boolean = false
) : ToolExecutor {

    override suspend fun execute(name: String, argsJson: String): ToolResult {
        val action = actionOf(argsJson)
        if (name in AgentAppActions.PROFILE_TOOLS) {
            // Answered here rather than by the permission map, because a
            // setting can hand these over and the map cannot know about it.
            if (!allowProfileTools) return ToolResult(false, profileUnattendedRefusal(name))
            if (!AgentAppActions.unattendedAllowsProfileAction(name, action)) {
                return ToolResult(false, unattendedRefusal(name))
            }
        } else {
            permissions.refusal(name)?.let { return ToolResult(false, it) }
        }
        if (name in TAB_TOOLS) {
            return ToolResult(
                false,
                "tool '$name' is not available to a scheduled run: it keeps to the one page it " +
                    "was given. Use navigate and read_page instead."
            )
        }
        // "Would this need to ask?" is a different question for an app tool than
        // for a page tool: reading a generated code asks nobody, while clicking
        // is the ask. [AgentAppActions.isWrite] answers the app-tool half
        // exactly; the page tools are all interactive by nature.
        val needsTheUser = if (name in AgentAppActions.PROFILE_TOOLS) {
            AgentAppActions.isWrite(name, action)
        } else {
            name in AgentTools.INTERACTIVE_TOOLS
        }
        if (confirmActions && needsTheUser) {
            return ToolResult(false, unattendedRefusal(name))
        }
        return delegate.execute(name, argsJson)
    }

    private companion object {
        val TAB_TOOLS = setOf(
            AgentTools.OPEN_NEW_TAB,
            AgentTools.LIST_TABS,
            AgentTools.SWITCH_TAB,
            AgentTools.CLOSE_TAB
        )

        /** The action a call names, or null when the arguments cannot be read. */
        fun actionOf(argsJson: String): String? = runCatching {
            (AgentJson.parseToJsonElement(argsJson) as? JsonObject)
                ?.get("action")
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()
    }
}
