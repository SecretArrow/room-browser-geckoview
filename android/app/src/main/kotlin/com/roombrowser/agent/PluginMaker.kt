package com.roombrowser.agent

import com.roombrowser.domain.plugins.PluginPermissions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One `/make_plugins` composition: returns the model's raw reply text, or
 * throws on failure — the thrown words are what the retry (and then the
 * definitive refusal) reads, so a provider error, a 120 s timeout and "the
 * chat's model wrote no text" all travel the same channel the parse errors
 * do. [retryNote] rides the SECOND attempt only; null is the first.
 *
 * The same shape as [PluginGenerateCall] on purpose: one suspend call, one
 * answer, resolved per command against the chat's OWN session provider and
 * model (the controller's `pluginComposerFor` wiring) — never the panel's
 * current selection, because the promise of `/make_plugins` is "this chat's
 * AI", not "whatever the header happens to show".
 */
fun interface PluginComposeCall {
    suspend operator fun invoke(description: String, retryNote: String? = null): String
}

/**
 * One composed plugin waiting on the person's approval. [manifest] is the
 * VALIDATED draft ([com.roombrowser.domain.plugins.PluginComposeParser.Parsed.Draft]'s
 * manifest object — it already passed the same
 * [com.roombrowser.domain.plugins.PluginManifestValidator] that gates
 * installs); [entrySource] the composed code, verbatim from its fenced
 * block. Nothing is installed until [PluginUiState.submitCreation] runs the
 * form's REWRITTEN manifest through the installer: the manifest here is a
 * proposal the person edits, never a package the app trusts.
 *
 * The read-only helpers ([displayName], [declaredDescription], [versionText],
 * [idText], [declaredCommands], [declaredPermissions], [declaredHosts]) are
 * the draft as the AI wrote it — what the dialog pre-fills from and what the
 * person's answer is judged against. The identity fields (id, version,
 * entry, api) have no editors: a review that could re-point `entry` or
 * re-spell the id would be a code review the form cannot honestly perform,
 * and a draft whose identity the person cannot trust is refused, not edited.
 */
class PendingPluginCreation(
    val manifest: JsonObject,
    val entrySource: String
) {

    /** The manifest's declared entry path (validator-guaranteed relative `.js`). */
    val entryPath: String = manifest.string("entry").orEmpty()

    /** The draft's name — the dialog's pre-fill and its title. */
    val displayName: String = manifest.string("name").orEmpty()

    /** The draft's description — the description field's pre-fill. */
    val declaredDescription: String = manifest.string("description").orEmpty()

    /** The draft's version, shown read-only (drafts are `0.1.0` by prompt). */
    val versionText: String = manifest.string("version").orEmpty()

    /** The draft's id, shown read-only — the row the install will write. */
    val idText: String = manifest.string("id").orEmpty()

    /** The command names the draft declares — shown so nothing rides unseen. */
    val declaredCommands: List<String> = manifest.commandNames()

    /** The permissions the draft declared — the checkboxes' pre-checked set. */
    val declaredPermissions: List<String> = manifest.strings("permissions")

    /** The hosts the draft's network allowlist named — the hosts field's pre-fill. */
    val declaredHosts: List<String> = manifest.networkAllow()

    /**
     * The manifest the form's answer becomes: the draft with name,
     * description, permissions and the network allowlist REPLACED by what
     * the person chose. Pure — the dialog recomputes it on every keystroke
     * (its one gate is "the rewritten manifest passes the install
     * validator"), and [PluginUiState.submitCreation] re-runs it as its own
     * backstop.
     *
     * Permissions and hosts come ONLY from the form: the model's asks are
     * displayed, never installed. `network` is written **only when the
     * network permission is checked** — the validator refuses a `network`
     * object without the permission, and keeping that refusal live (rather
     * than pre-dropping the object) is what makes an unchecked box honestly
     * drop the allowlist and a re-checked one honestly demand its review.
     */
    fun candidate(
        name: String,
        description: String,
        permissions: List<String>,
        hosts: List<String>
    ): JsonObject = JsonObject(
        buildMap {
            manifest.forEach { (key, value) ->
                if (key !in REPLACED) put(key, value)
            }
            put("name", JsonPrimitive(name))
            put("description", JsonPrimitive(description))
            val checked = CANONICAL_PERMISSIONS.filter { it in permissions }
            if (checked.isNotEmpty()) {
                put("permissions", JsonArray(checked.map { JsonPrimitive(it) }))
            }
            if (PluginPermissions.NETWORK in checked) {
                put(
                    "network",
                    JsonObject(mapOf("allow" to JsonArray(hosts.map { JsonPrimitive(it) })))
                )
            }
        }
    )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.strings(key: String): List<String> =
        ((this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
            .orEmpty()

    /** The `commands` array's entries are objects — the names are what a review shows. */
    private fun JsonObject.commandNames(): List<String> =
        ((this["commands"] as? JsonArray)?.mapNotNull { entry ->
            (entry as? JsonObject)?.string("name")
        }).orEmpty()

    private fun JsonObject.networkAllow(): List<String> =
        ((this["network"] as? JsonObject)?.strings("allow")).orEmpty()

    companion object {

        /** The four members the form replaces; every other draft field rides through. */
        private val REPLACED = setOf("name", "description", "permissions", "network")

        /**
         * The row vocabulary in its canonical order — the checkbox order the
         * dialog renders and the order [candidate] writes, so a manifest's
         * permission array is spelled one way no matter which boxes were
         * toggled in which order. [PluginPermissions.PERMISSIONS]' own
         * declaration order, made explicit here because a set's iteration
         * order is an implementation detail a wire spelling must not ride on.
         */
        val CANONICAL_PERMISSIONS = listOf(
            PluginPermissions.STORAGE,
            PluginPermissions.NETWORK,
            PluginPermissions.AGENT_TOOLS,
            PluginPermissions.CHAT_CONTEXT,
            PluginPermissions.AGENT_GENERATE
        )
    }
}
