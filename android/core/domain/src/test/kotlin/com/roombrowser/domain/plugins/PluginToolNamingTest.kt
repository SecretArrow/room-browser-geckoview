package com.roombrowser.domain.plugins

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentPermissions
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.PlanModePolicy
import com.roombrowser.domain.task.AiTaskPermissions
import com.roombrowser.domain.task.ToolGroup
import org.junit.Test

/**
 * The spelling of plugin tools, and the gates that spelling lets stay static.
 *
 * Every gate that must recognise plugin tools does it by prefix, because the
 * tools themselves are per-chat and unknowable at build time. These tests pin
 * that contract from both sides: the name is spellable and reversible, and
 * each static policy answers a plugin name without a list of them.
 */
class PluginToolNamingTest {

    @Test
    fun `a plugin tool name carries the plugin's own directory spelling`() {
        assertThat(PluginToolNaming.pluginToolName("com.example.hello", "count_words"))
            .isEqualTo("plugin_com_example_hello_count_words")
        assertThat(PluginToolNaming.pluginToolName("a.b", "greet"))
            .isEqualTo("plugin_a_b_greet")
    }

    @Test
    fun `a tool name outside its own grammar is refused, not mangled`() {
        assertThat(PluginToolNaming.pluginToolName("com.example.hello", "Count")).isNull()
        assertThat(PluginToolNaming.pluginToolName("com.example.hello", "1count")).isNull()
        assertThat(PluginToolNaming.pluginToolName("com.example.hello", "has space")).isNull()
        assertThat(PluginToolNaming.pluginToolName("com.example.hello", "")).isNull()
    }

    @Test
    fun `a name over the cap is refused rather than truncated`() {
        val longTool = "t".repeat(40)
        assertThat(PluginToolNaming.pluginToolName("com.example.hello", longTool)).isNull()
        // Exactly at the cap is fine: the cap is a ceiling, not a wall short of it.
        val fitting = "n".repeat(24)
        assertThat(
            PluginToolNaming.pluginToolName("a.b", fitting)
        ).isEqualTo("plugin_a_b_$fitting")
    }

    @Test
    fun `membership is by prefix and the bare prefix is not a tool`() {
        assertThat(PluginToolNaming.isPluginTool("plugin_a_b_greet")).isTrue()
        assertThat(PluginToolNaming.isPluginTool("click")).isFalse()
        assertThat(PluginToolNaming.isPluginTool(AgentTools.PLUGIN_TOOL_PREFIX)).isFalse()
        assertThat(PluginToolNaming.isPluginTool("")).isFalse()
    }

    @Test
    fun `the plugin of a tool name is resolved against the ids this chat knows`() {
        val known = setOf("com_example_hello", "a_b_word_count")
        assertThat(PluginToolNaming.pluginIdOf("plugin_com_example_hello_count_words", known))
            .isEqualTo("com_example_hello")
        assertThat(PluginToolNaming.pluginIdOf("plugin_a_b_word_count_total", known))
            .isEqualTo("a_b_word_count")
        // And a name whose id-part is not itself a known id resolves to
        // nobody: "a_b" is not in the set, and "a_b_count" does not start
        // with any longer known id either.
        assertThat(PluginToolNaming.pluginIdOf("plugin_a_b_count", known)).isNull()
        assertThat(PluginToolNaming.pluginIdOf("plugin_com_example_other_tool", known)).isNull()
        assertThat(PluginToolNaming.pluginIdOf("plugin_com_example_hello", known)).isNull()
    }

    @Test
    fun `plan mode refuses plugin tools by prefix while the static set stays static`() {
        assertThat(PlanModePolicy.REFUSED).doesNotContain("plugin_anything")
        assertThat(PlanModePolicy.refusal("plugin_a_b_greet", null)).isNotNull()
        // A plugin tool never appears in the static catalogue, so the
        // catalogue test in the app module must keep accounting for it by
        // prefix too — see HeadlessToolCatalogTest.
        assertThat(AgentTools.toolDefs().map { it.function.name })
            .containsNoneIn(listOf("plugin_a_b_greet"))
    }

    @Test
    fun `the profile's withheld permission axis knows plugin tools`() {
        assertThat(AgentPermissions.required("plugin_a_b_greet"))
            .containsExactly(AgentPermissions.AGENT_PLUGINS)
        assertThat(AgentPermissions.AGENT_PLUGINS).isIn(AgentPermissions.ALL)
        assertThat(AgentPermissions.label(AgentPermissions.AGENT_PLUGINS)).isEqualTo("Plugin tools")
        assertThat(AgentPermissions.description(AgentPermissions.AGENT_PLUGINS)).isNotEmpty()

        // Withheld, the tool is refused by name and setting, like every other.
        val withheld = setOf(AgentPermissions.AGENT_PLUGINS)
        assertThat(AgentPermissions.refusal("plugin_a_b_greet", withheld)).isNotNull()
        assertThat(AgentPermissions.granted("plugin_a_b_greet", withheld)).isFalse()
        assertThat(AgentPermissions.granted("plugin_a_b_greet", emptySet())).isTrue()
    }

    @Test
    fun `an unattended run refuses plugin tools with the node group's own words`() {
        val permissions = AiTaskPermissions()
        assertThat(permissions.groupOf("plugin_a_b_greet")).isEqualTo(ToolGroup.NODE)
        assertThat(permissions.allows("plugin_a_b_greet")).isFalse()
        assertThat(permissions.refusal("plugin_a_b_greet")).isNotNull()
        // The static groups are untouched by the dynamic names.
        assertThat(permissions.groupOf("click")).isEqualTo(ToolGroup.INTERACT)
    }
}
