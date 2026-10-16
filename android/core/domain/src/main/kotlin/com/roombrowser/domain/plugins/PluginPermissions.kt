package com.roombrowser.domain.plugins

/**
 * The permissions a plugin manifest can declare, and the human text each one
 * is consented with.
 *
 * All five are denied until the chat that enables the plugin grants them, and
 * the grant is stored per chat binding — a plugin is never consented once for
 * the whole app, because "this chat" is the whole isolation model. The names
 * are the wire spelling (what plugin.json declares and what the host gates
 * on), so they are strings rather than an enum: a stored grant naming
 * something this build no longer knows is an inert row, which is the safe
 * direction.
 *
 * What needs NO declaration — `room.meta`, `room.config`, `room.ui`, `room.log`,
 * `room.util`, console, timers — is deliberately absent here: none of those can
 * leave the call, so there is nothing to consent to.
 */
object PluginPermissions {

    const val STORAGE = "storage"
    const val NETWORK = "network"
    const val AGENT_TOOLS = "agent.tools"
    const val CHAT_CONTEXT = "chat.context"

    /**
     * The plugin may ask THIS chat's own AI to write one piece of text — the
     * session's provider and model, never the plugin's own. The grant is the
     * plugin's route to `room.agent.generate`, and the consent sentence says
     * whose AI is doing the writing, because "an AI wrote it" without the
     * whose would read as the plugin carrying its own model (and key) — the
     * one thing this permission must never let it look like.
     */
    const val AGENT_GENERATE = "agent.generate"

    /** Every permission this build knows, for the grant dialog and the tests. */
    val PERMISSIONS = setOf(STORAGE, NETWORK, AGENT_TOOLS, CHAT_CONTEXT, AGENT_GENERATE)

    /**
     * Where the host list is cut off in a label. Long enough that an honest
     * allowlist reads whole, short enough that a manifest declaring twenty
     * hosts cannot turn the dialog into a wall of domains.
     */
    private const val HOSTS_LABEL_CHARS = 120

    /**
     * The set a call actually runs with: what the manifest declared AND this
     * chat granted. Everything else is refused by the host even though the
     * `room` namespace exists, so this intersection — not the declared set,
     * not the stored grants alone — is the honest thing to put on the wire.
     * A grant naming something the manifest never declared is inert by
     * construction here, which is also what makes a stale stored grant safe.
     */
    fun effectiveGrants(declared: List<String>, granted: List<String>): List<String> {
        val consented = granted.toSet()
        return declared.filter { it in consented }
    }

    /**
     * The human sentence one permission is consented with.
     *
     * [networkHosts] is only read for [NETWORK]: the hosts are the actual
     * scope of what is being agreed to, and a dialog that said only "reach
     * the internet" would be consenting to something wider than the
     * allowlist the plugin is ever allowed to use.
     */
    fun labelOf(permission: String, networkHosts: List<String> = emptyList()): String =
        when (permission) {
            STORAGE -> "Store data for this chat"
            NETWORK -> {
                val joined = networkHosts.joinToString(", ")
                val hosts = if (joined.length > HOSTS_LABEL_CHARS) {
                    joined.take(HOSTS_LABEL_CHARS) + "…"
                } else {
                    joined
                }
                if (hosts.isEmpty()) "Reach the internet" else "Reach the internet (hosts: $hosts)"
            }
            AGENT_TOOLS -> "Add tools the AI agent can call"
            CHAT_CONTEXT -> "Read this chat's conversation"
            AGENT_GENERATE -> "Have this chat's AI write text for it"
            else -> permission
        }
}
