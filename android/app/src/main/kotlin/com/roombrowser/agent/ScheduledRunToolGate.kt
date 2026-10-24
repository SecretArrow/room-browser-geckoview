package com.roombrowser.agent

import com.roombrowser.domain.agent.AgentAppActions
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.plugins.PluginToolNaming
import com.roombrowser.domain.task.AiTaskPermissions
import com.roombrowser.domain.task.pluginUnattendedRefusal
import com.roombrowser.domain.task.profileUnattendedRefusal
import com.roombrowser.domain.task.unattendedRefusal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
        // A chat plugin's tool FIRST, for the same reason the headless
        // executor refuses it first: the permission map would answer it with
        // the NODE group's Node-script wording, and the truth is code a
        // person consented to for ONE chat — which a scheduled run is twice
        // the wrong place for.
        if (PluginToolNaming.isPluginTool(name)) {
            return ToolResult(false, pluginUnattendedRefusal(name))
        }
        // The skills tool, answered HERE rather than by the permission map:
        // the map has no group for it (it would read "unknown tool"), and
        // the honest unattended answer is per ACTION — the reads and the
        // enable/disable flag are fine, and the download/delete actions are
        // exactly what nobody is watching should not trigger. Reads fall
        // through to the delegate, which answers from the skills registry
        // when it was built with one and says so when it was not.
        if (name == AgentTools.SKILLS) {
            if (actionOf(argsJson) in SKILL_WRITE_ACTIONS) {
                return ToolResult(false, skillsUnattendedRefusal())
            }
        } else {
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
        // exactly; the page tools are all interactive by nature — and the
        // skills writes were refused outright above, so what remains of that
        // tool is its reads and its flag, which ask nobody.
        val needsTheUser = if (name in AgentAppActions.PROFILE_TOOLS) {
            AgentAppActions.isWrite(name, actionOf(argsJson))
        } else if (name == AgentTools.SKILLS) {
            false
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

        /** The skills actions that download or delete — the unattended refusals. */
        val SKILL_WRITE_ACTIONS = setOf("install", "update", "remove")

        /** The action a call names, or null when the arguments cannot be read. */
        fun actionOf(argsJson: String): String? = runCatching {
            (AgentJson.parseToJsonElement(argsJson) as? JsonObject)
                ?.get("action")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
        }.getOrNull()
    }
}

/**
 * The unattended refusal for a skills install, update or remove: a download
 * from GitHub (or a delete) with nobody to ask, for the same reason a
 * package install is refused to a scheduled run. It names the route that
 * works, the way every unattended refusal here does.
 */
internal fun skillsUnattendedRefusal(): String =
    "installing, updating or removing a skill reaches GitHub or deletes files, and a " +
        "scheduled run has nobody to confirm it with — run this as a chat, where the " +
        "user can approve it. Reading the installed skills (list, status) works here."
