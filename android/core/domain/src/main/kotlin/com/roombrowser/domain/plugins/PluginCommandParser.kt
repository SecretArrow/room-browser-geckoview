package com.roombrowser.domain.plugins

/**
 * The `/`-commands a chat understands because plugins are installed in it.
 *
 * This is a PURE function over the text and the command names the chat's
 * enabled plugins declare — the manifest is where command names live, so
 * routing needs no runtime probe and costs nothing to re-run. Everything the
 * grammar cannot decide (which plugin a `/plugins <query>` names, whether a
 * toggle means on or off) is left to the caller, because those answers come
 * from the chat's own bindings, not from the text.
 *
 * A `/<name>` that no enabled plugin declares is [NotACommand] rather than an
 * error: the user may simply be talking to the agent with a leading slash,
 * and that text belongs to the model, not to a refusal nobody asked for.
 */
object PluginCommandParser {

    /**
     * The command words the app owns, decided BEFORE any plugin-command
     * lookup — a chat whose own enabled plugin declares one of these names
     * still routes to the app's command, never the plugin's. A manifest may
     * not declare any of them: [PluginManifestValidator] refuses both words
     * with the same sentence, so this set is the single spelling of the
     * reserved vocabulary.
     */
    val RESERVED = setOf("plugins", "make_plugins")

    /**
     * Parses [text] against [commands], the command names this chat's enabled
     * plugins declare, each mapped to the plugin NAMES that declare it (a
     * name two plugins share is the ambiguous case the caller has to report).
     */
    fun parse(text: String, commands: Map<String, List<String>>): PluginCommand {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return PluginCommand.NotACommand
        val body = trimmed.removePrefix("/").trim()
        if (body.isEmpty()) return PluginCommand.NotACommand
        val name = body.substringBefore(' ').trim()
        if (name.isEmpty()) return PluginCommand.NotACommand
        val args = body.substringAfter(' ', "").trim()
        if (name == "plugins") {
            return if (args.isEmpty()) PluginCommand.ListPlugins else PluginCommand.Toggle(args)
        }
        if (name == "make_plugins") {
            // The bare form carries an empty description — the instructive
            // notice and the 1000-character cap are the interceptor's answers
            // (design §5.1/§5.2), because they are notices, and this function
            // only routes text.
            return PluginCommand.MakePlugins(args)
        }
        val owners = commands[name].orEmpty()
        return when {
            owners.isEmpty() -> PluginCommand.NotACommand
            owners.size == 1 -> PluginCommand.Plugin(name, args)
            else -> PluginCommand.Ambiguous(name, owners)
        }
    }
}

/** What the parser decided a line of chat is. */
sealed interface PluginCommand {

    /** `/plugins` on its own: open the chat-plugins sheet, run no turn. */
    object ListPlugins : PluginCommand

    /**
     * `/plugins <query>`: switch the plugin it names on or off in this chat.
     * Which plugin — by exact id or exact name — and which way, is the
     * caller's to resolve against the installed list and the chat's bindings.
     */
    data class Toggle(val query: String) : PluginCommand

    /**
     * `/make_plugins <description>`: the plugin maker — this chat's own AI
     * drafts a plugin from the description and the person reviews it before
     * anything installs. The bare form is the empty description; the grammar
     * is the same one `/plugins` rides (word boundary, case-sensitive token),
     * so `/make_pluginsx` is NOT the command and falls through to the agent.
     */
    data class MakePlugins(val description: String) : PluginCommand

    /** `/<name> <args>` that exactly one enabled plugin declares. */
    data class Plugin(val name: String, val args: String) : PluginCommand

    /** `/<name>` that two or more enabled plugins declare; the owners are named for the user. */
    data class Ambiguous(val name: String, val owners: List<String>) : PluginCommand

    /** Anything else — it goes to the agent as ordinary chat. */
    object NotACommand : PluginCommand
}
