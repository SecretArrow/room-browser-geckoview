package com.roombrowser.agent

import com.roombrowser.domain.agent.ChatOnlyPolicy
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult

/**
 * The gate a chat turn runs behind while "Chat only" is on.
 *
 * It is applied OUTSIDE whatever executor the turn would otherwise use — the
 * headless gate included — because that gate hands the app's own tools
 * straight through, and `app_2fa action=fill` reaches the page by that route.
 */
class ChatOnlyToolGate(private val inner: ToolExecutor) : ToolExecutor {

    override suspend fun execute(name: String, argsJson: String): ToolResult =
        ChatOnlyPolicy.refusal(name, HeadlessToolExecutor.actionOf(argsJson))
            ?.let { ToolResult(false, it) }
            ?: inner.execute(name, argsJson)
}
