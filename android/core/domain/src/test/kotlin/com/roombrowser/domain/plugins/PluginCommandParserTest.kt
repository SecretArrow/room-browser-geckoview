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

    // ------------------------------------------------------------ make_plugins

    @Test
    fun `bare make_plugins is the maker command with no description`() {
        assertThat(PluginCommandParser.parse("/make_plugins", COMMANDS))
            .isEqualTo(PluginCommand.MakePlugins(""))
        assertThat(PluginCommandParser.parse("  /make_plugins  ", COMMANDS))
            .isEqualTo(PluginCommand.MakePlugins(""))
        // The reserved vocabulary the parser owns on the app's behalf.
        assertThat(PluginCommandParser.RESERVED).containsExactly("plugins", "make_plugins", "airplane")
    }

    @Test
    fun `make_plugins carries its description verbatim`() {
        assertThat(PluginCommandParser.parse("/make_plugins a tiny counter plugin", COMMANDS))
            .isEqualTo(PluginCommand.MakePlugins("a tiny counter plugin"))
        // Outer whitespace is trimmed, inner spacing is the user's own.
        assertThat(PluginCommandParser.parse("/make_plugins   counts   greetings  ", COMMANDS))
            .isEqualTo(PluginCommand.MakePlugins("counts   greetings"))
    }

    @Test
    fun `a long description is carried whole - the cap is the interceptor's notice`() {
        // The 1000-character cap is a NOTICE (design §5.1), and notices are
        // the interceptor's business: the parser only routes text, so the
        // description arrives whole and the cap decision is made where it
        // can be answered.
        val long = "x".repeat(1001)
        assertThat(PluginCommandParser.parse("/make_plugins $long", COMMANDS))
            .isEqualTo(PluginCommand.MakePlugins(long))
    }

    @Test
    fun `make_plugins without a whitespace boundary is not the command`() {
        assertThat(PluginCommandParser.parse("/make_pluginsx", COMMANDS))
            .isEqualTo(PluginCommand.NotACommand)
        assertThat(PluginCommandParser.parse("/make_plugins:hi", COMMANDS))
            .isEqualTo(PluginCommand.NotACommand)
    }

    @Test
    fun `make_plugins is matched exactly, not case-insensitively`() {
        // Command names are lowercase by grammar; a mixed-case spelling is a
        // different word, and it falls through exactly as today.
        assertThat(PluginCommandParser.parse("/Make_Plugins hi", COMMANDS))
            .isEqualTo(PluginCommand.NotACommand)
        assertThat(PluginCommandParser.parse("/MAKE_PLUGINS", COMMANDS))
            .isEqualTo(PluginCommand.NotACommand)
    }

    @Test
    fun `a plugin declaring make_plugins does not own the word`() {
        // Reserved-before-plugin-command: the app's word wins even in a chat
        // whose own enabled plugin declares it — routing to the plugin here
        // would hand the maker flow to untrusted manifest code.
        val owners = mapOf("make_plugins" to listOf("Evil Plugin"))
        assertThat(PluginCommandParser.parse("/make_plugins hi", owners))
            .isEqualTo(PluginCommand.MakePlugins("hi"))
        assertThat(PluginCommandParser.parse("/make_plugins", owners))
            .isEqualTo(PluginCommand.MakePlugins(""))
    }

    // ------------------------------------------------------------- airplane

    @Test
    fun `airplane on and off carry the direction`() {
        assertThat(PluginCommandParser.parse("/airplane on", COMMANDS))
            .isEqualTo(PluginCommand.Airplane(true))
        assertThat(PluginCommandParser.parse("/airplane off", COMMANDS))
            .isEqualTo(PluginCommand.Airplane(false))
        assertThat(PluginCommandParser.parse("  /airplane on  ", COMMANDS))
            .isEqualTo(PluginCommand.Airplane(true))
    }

    @Test
    fun `a bare airplane or a word that is neither on nor off carries null`() {
        // Null is the interceptor's cue for the usage notice: the parser only
        // routes text, and "which way" is a question the notice answers, not
        // a routing decision this function should invent an error for.
        assertThat(PluginCommandParser.parse("/airplane", COMMANDS))
            .isEqualTo(PluginCommand.Airplane(null))
        assertThat(PluginCommandParser.parse("/airplane fly", COMMANDS))
            .isEqualTo(PluginCommand.Airplane(null))
        assertThat(PluginCommandParser.parse("/airplane on extra", COMMANDS))
            .isEqualTo(PluginCommand.Airplane(null))
    }

    @Test
    fun `airplane without a whitespace boundary is not the command`() {
        assertThat(PluginCommandParser.parse("/airplanex", COMMANDS))
            .isEqualTo(PluginCommand.NotACommand)
        assertThat(PluginCommandParser.parse("/airplane:hi", COMMANDS))
            .isEqualTo(PluginCommand.NotACommand)
        assertThat(PluginCommandParser.parse("/Airplane on", COMMANDS))
            .isEqualTo(PluginCommand.NotACommand)
    }

    @Test
    fun `a plugin declaring airplane does not own the word`() {
        // The third reserved word follows the same rule as the first two:
        // the app's command wins even in a chat whose own enabled plugin
        // declares it — a manifest declaring it is refused at install.
        val owners = mapOf("airplane" to listOf("Evil Plugin"))
        assertThat(PluginCommandParser.parse("/airplane on", owners))
            .isEqualTo(PluginCommand.Airplane(true))
        assertThat(PluginCommandParser.parse("/airplane", owners))
            .isEqualTo(PluginCommand.Airplane(null))
    }

    private companion object {
        val COMMANDS = mapOf("greet" to listOf("Hello World"))
        val AMBIGUOUS = mapOf("count" to listOf("A", "B"))
    }
}
