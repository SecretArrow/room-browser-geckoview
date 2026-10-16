package com.roombrowser.domain.plugins

import com.roombrowser.domain.agent.AgentTools

/**
 * How a plugin's tool is spelled in the agent's catalogue, so every gate that
 * must recognise plugin tools can do it by prefix and stay static.
 *
 * The wire name is `plugin_<sanitized-id>_<tool>` — the plugin's identity is
 * IN the name because two plugins may define the same tool name, and the
 * catalogue has one flat namespace. The sanitized id (dots to underscores) is
 * also the plugin's directory name on disk, so the name a tool arrives under
 * names the code that will run: a person reading the catalogue can tell whose
 * tool it is without a lookup table.
 */
object PluginToolNaming {

    /**
     * The longest tool name offered to the provider. A name longer than this
     * is rejected rather than truncated: a truncated name would still be
     * unique-looking while resolving to a different plugin or tool, which is
     * exactly the kind of silent misrouting the prefix exists to prevent.
     */
    private const val MAX_NAME_CHARS = 60

    /** The shape a plugin's own tool name must have to be re-spelled safely. */
    private val TOOL_NAME = Regex("^[a-z][a-z0-9_-]{0,23}$")

    /**
     * The catalogue spelling of [toolName] as declared by plugin [pluginId],
     * or null when the two cannot be spelled safely — a name over the cap, or
     * a tool name with characters the round trip cannot survive. Callers drop
     * such tools from the catalogue rather than renaming them: a tool the
     * plugin never defined is worse than a tool missing.
     */
    fun pluginToolName(pluginId: String, toolName: String): String? {
        if (!TOOL_NAME.matches(toolName)) return null
        val name = AgentTools.PLUGIN_TOOL_PREFIX + pluginId.replace('.', '_') + "_" + toolName
        return if (name.length <= MAX_NAME_CHARS) name else null
    }

    /**
     * Whether [name] is a plugin tool. Prefix-based on purpose: the static
     * tool sets (the confirm axis, the plan-mode policy, the unattended
     * gates) stay static while plugin tools come and go per chat, so
     * membership has to be decided by shape rather than by list.
     */
    fun isPluginTool(name: String): Boolean =
        name.length > AgentTools.PLUGIN_TOOL_PREFIX.length && name.startsWith(AgentTools.PLUGIN_TOOL_PREFIX)

    /**
     * The sanitized plugin id a plugin tool name belongs to, given the ids
     * this chat knows, or null.
     *
     * The id set has to be supplied because the split point between id and
     * tool name is genuinely ambiguous: both are lowercase and both may
     * contain dashes (a manifest id segment allows them), so
     * `plugin_com_example_hello-count` could be id `com.example.hello` tool
     * `count` or id `com.example.hello-count` tool anything. The callers that
     * already know the plugins resolve against their own list; a caller that
     * knows nothing would be guessing, and this refuses to.
     */
    fun pluginIdOf(name: String, sanitizedPluginIds: Collection<String>): String? =
        sanitizedPluginIds.firstOrNull { id ->
            // The separator must be IN the name with at least one tool
            // character after it: a name that ends exactly at the id is a
            // plugin spelling without a tool, and indexing it would read one
            // past the end (the exact crash the first branch's looser bound
            // let through).
            name.length > AgentTools.PLUGIN_TOOL_PREFIX.length + id.length + 1 &&
                name[AgentTools.PLUGIN_TOOL_PREFIX.length + id.length] == '_' &&
                name.startsWith(AgentTools.PLUGIN_TOOL_PREFIX + id)
        }
}
