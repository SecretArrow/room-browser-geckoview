package com.roombrowser.agent

import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.task.AiTaskPermissions
import com.roombrowser.domain.task.unattendedRefusal

/**
 * The grants a task was saved with, applied in front of the executor a visible
 * run has to use.
 *
 * A run that drives a real tab goes through [AgentToolExecutor] — the chat's
 * executor, which is the only one built for a live tab and therefore has no
 * permission model of its own. Without this gate the same task would silently
 * gain the app-control and wallet tools it is denied when it runs hidden, so
 * the surface a task chose would change what the task may do.
 */
class ScheduledRunToolGate(
    private val delegate: ToolExecutor,
    private val permissions: AiTaskPermissions,
    private val confirmActions: Boolean
) : ToolExecutor {

    override suspend fun execute(name: String, argsJson: String): ToolResult {
        permissions.refusal(name)?.let { return ToolResult(false, it) }
        if (name in TAB_TOOLS) {
            return ToolResult(
                false,
                "tool '$name' is not available to a scheduled run: it keeps to the one page it " +
                    "was given. Use navigate and read_page instead."
            )
        }
        if (confirmActions && name in AgentTools.INTERACTIVE_TOOLS) {
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
    }
}
