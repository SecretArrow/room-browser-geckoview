package com.roombrowser.agent

import com.roombrowser.domain.agent.AgentPermissions
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult

/**
 * Puts this profile's withheld agent permissions in front of another executor.
 *
 * A DECORATOR, for the same reason as [ActionBudgetGate]: the permissions are a
 * property of the PROFILE, and every route into the tools — the visible tab, a
 * hidden page, a scheduled run — has to pass it. Wrapping the executor means a
 * tool whose permission is withheld is refused HERE, before any executor can
 * reach a page, rather than depending on each executor to remember.
 *
 * A withheld permission returns a refusal that NAMES the tool and the setting,
 * so the model can say what is missing instead of probing for another route: a
 * silent "unknown tool" sends it looking for one.
 *
 * The withheld set is read live, never snapshotted at construction, so the user
 * can grant a permission mid-turn and the next call sees it.
 */
class AgentPermissionGate(
    private val inner: ToolExecutor,
    private val withheld: () -> Set<String>
) : ToolExecutor {

    override suspend fun execute(name: String, argsJson: String): ToolResult {
        AgentPermissions.refusal(name, withheld())?.let { return ToolResult(false, it) }
        return inner.execute(name, argsJson)
    }
}
