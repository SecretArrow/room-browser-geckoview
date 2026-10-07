package com.roombrowser.agent

import com.roombrowser.domain.agent.PlanModePolicy
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult

/**
 * The gate a chat turn runs behind in Plan mode.
 *
 * It is applied OUTSIDE whatever executor the turn would otherwise use — the
 * headless gate included — because that gate hands the app's own tools
 * straight through, and `app_2fa action=fill` reaches the page by that route.
 */
class PlanModeToolGate(private val inner: ToolExecutor) : ToolExecutor {

    override suspend fun execute(name: String, argsJson: String): ToolResult =
        PlanModePolicy.refusal(name, HeadlessToolExecutor.actionOf(argsJson))
            ?.let { ToolResult(false, it) }
            ?: inner.execute(name, argsJson)
}
