package com.roombrowser.agent

import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.plugins.PluginCard
import com.roombrowser.domain.plugins.PluginToolNaming
import com.roombrowser.node.PluginChatEntry

/**
 * The innermost wrap of a chat turn's executor chain: a plugin's tools,
 * intercepted by name before anything else answers, and everything else passed
 * to the page executor unchanged.
 *
 * WHY INNERMOST. The chain is Plan mode → withheld permissions → action
 * budget → THIS → page tools, so every gate above it applies to plugin tools
 * exactly as it does to clicks and posts: a plugin tool is refused in Plan
 * mode, costs the turn's budget, and is held back by a withheld
 * `agent.plugins` permission. None of those gates knows a plugin tool by
 * list — the names carry the `plugin_` prefix, and the gates match by shape —
 * so the catalogue can grow and shrink per chat without any static set
 * moving.
 *
 * WHAT THE MODEL READS BACK is the plugin's own output, fenced as untrusted
 * data by [AgentPluginRuntime.runTool] — the same fence a page's words get,
 * because a plugin's words are equally not instructions. What the USER reads
 * is the cards the plugin pushed while its tool ran, which ride beside the
 * tool card rather than inside the model's result.
 */
class PluginToolExecutor(
    private val inner: ToolExecutor,
    private val runtime: AgentPluginRuntime,
    private val catalog: AgentPluginCatalog,
    private val sessionId: Long,
    /**
     * The chat's transcript as the plugins are allowed to read it, read at
     * call time rather than turn time: a tool call can arrive several steps
     * into a turn, and the conversation it should see is the one that exists
     * then, not the one the turn started from.
     */
    private val history: suspend () -> List<PluginChatEntry> = { emptyList() },
    /** Cards a tool run pushed for the user; the turn appends them to the chat. */
    private val onCards: suspend (cards: List<PluginCard>, pluginName: String) -> Unit = { _, _ -> }
) : ToolExecutor {

    override suspend fun execute(name: String, argsJson: String): ToolResult {
        if (!PluginToolNaming.isPluginTool(name)) return inner.execute(name, argsJson)
        val owner = catalog.toolOwner(sessionId, name)
            ?: return ToolResult(
                false,
                "tool '$name' is not available in this chat: the plugin that offered it is " +
                    "disabled, uninstalled, or not granted agent tools. Tell the user to check " +
                    "the chat's plugins rather than retrying the call."
            )
        val run = runtime.runTool(
            sessionId = sessionId,
            plugin = owner.sessionPlugin.plugin,
            binding = owner.sessionPlugin.binding,
            toolName = owner.toolName,
            argsJson = argsJson,
            history = history()
        )
        if (run.cards.isNotEmpty()) {
            runCatching { onCards(run.cards, owner.sessionPlugin.plugin.name) }
        }
        return run.result
    }
}
