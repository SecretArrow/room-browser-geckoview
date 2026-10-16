package com.roombrowser.domain.plugins

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The `/`-grammar: what is a plugin command, and what belongs to the agent. */
class PluginCommandParserTest {

    @Test
    fun `bare plugins opens the sheet`() {
        assertThat(PluginCommandParser.parse("/plugins", COMMANDS))
            .isEqualTo(PluginCommand.ListPlugins)
        assertThat(PluginCommandParser.parse("  /plugins  ", COMMANDS))
            .isEqualTo(PluginCommand.ListPlugins)
    }

    @Test
    fun `plugins with a query is a toggle`() {
        assertThat(PluginCommandParser.parse("/plugins hello", COMMANDS))
            .isEqualTo(PluginCommand.Toggle("hello"))
        assertThat(PluginCommandParser.parse("/plugins com.example.hello", COMMANDS))
            .isEqualTo(PluginCommand.Toggle("com.example.hello"))
        assertThat(PluginCommandParser.parse("/plugins Hello World", COMMANDS))
            .isEqualTo(PluginCommand.Toggle("Hello World"))
    }

    @Test
    fun `a declared command carries its args verbatim`() {
        assertThat(PluginCommandParser.parse("/greet", COMMANDS))
            .isEqualTo(PluginCommand.Plugin("greet", ""))
        assertThat(PluginCommandParser.parse("/greet Room", COMMANDS))
            .isEqualTo(PluginCommand.Plugin("greet", "Room"))
        assertThat(PluginCommandParser.parse("/greet Room and more", COMMANDS))
            .isEqualTo(PluginCommand.Plugin("greet", "Room and more"))
    }

    @Test
    fun `a command two plugins declare is ambiguous and names them`() {
        val command = PluginCommandParser.parse("/count", AMBIGUOUS)
        assertThat(command).isEqualTo(PluginCommand.Ambiguous("count", listOf("A", "B")))
    }

    @Test
    fun `anything else is not a command`() {
        assertThat(PluginCommandParser.parse("hello there", COMMANDS))
            .isEqualTo(PluginCommand.NotACommand)
        assertThat(PluginCommandParser.parse("/unknown thing", COMMANDS))
            .isEqualTo(PluginCommand.NotACommand)
        assertThat(PluginCommandParser.parse("/", COMMANDS))
            .isEqualTo(PluginCommand.NotACommand)
        assertThat(PluginCommandParser.parse("//", COMMANDS))
            .isEqualTo(PluginCommand.NotACommand)
        assertThat(PluginCommandParser.parse("", COMMANDS))
            .isEqualTo(PluginCommand.NotACommand)
    }

    @Test
    fun `a command nobody enabled is not a command even though someone declares it`() {
        // Routing follows what THIS chat enabled, so the same text falls
        // through to the agent in a chat that switched the plugin off.
        assertThat(PluginCommandParser.parse("/greet", emptyMap()))
            .isEqualTo(PluginCommand.NotACommand)
    }

    private companion object {
        val COMMANDS = mapOf("greet" to listOf("Hello World"))
        val AMBIGUOUS = mapOf("count" to listOf("A", "B"))
    }
}
