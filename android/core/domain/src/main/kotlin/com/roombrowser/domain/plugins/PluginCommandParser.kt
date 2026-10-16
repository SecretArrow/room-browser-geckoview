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

    /** The command the app owns; a manifest may not declare it (see the validator). */
    private const val RESERVED = "plugins"

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
        if (name == RESERVED) {
            return if (args.isEmpty()) PluginCommand.ListPlugins else PluginCommand.Toggle(args)
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

    /** `/<name> <args>` that exactly one enabled plugin declares. */
    data class Plugin(val name: String, val args: String) : PluginCommand

    /** `/<name>` that two or more enabled plugins declare; the owners are named for the user. */
    data class Ambiguous(val name: String, val owners: List<String>) : PluginCommand

    /** Anything else — it goes to the agent as ordinary chat. */
    object NotACommand : PluginCommand
}
