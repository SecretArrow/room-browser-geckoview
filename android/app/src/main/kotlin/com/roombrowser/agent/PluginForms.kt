package com.roombrowser.agent

import com.roombrowser.data.db.PluginEntity
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.plugins.PluginFormValidator
import com.roombrowser.domain.plugins.PluginInputsSchema
import kotlinx.serialization.json.JsonObject

/**
 * The shared halves of the three input-form gates: the schema an installed
 * row declares, the values this chat stored for it, and the one question
 * every gate asks before a plugin runs — "are the required inputs answered
 * yet?".
 *
 * A tiny object rather than three private copies because the answer must be
 * ONE answer: the activation gate, the staged-module gate and the agent tool
 * gate all read the same row columns through the same validator, so a plugin
 * that was promised its inputs cannot find a path where they go unchecked.
 * All of it is read-only against the rows; the WRITES (storing accepted
 * answers) stay with the gates' own call sites, which know the chat.
 */
internal object PluginForms {

    /**
     * The `inputs` schema the row's `inputs_json` column carries, or no
     * schema when the column is empty or unreadable. Parsed with the same
     * lenient [PluginInputsSchema.parse] the runtime request uses — the
     * column was written by the installer's VALIDATED schema, so anything
     * that no longer parses is a row this build cannot render, and reading
     * it as no form is the direction that never widens what runs.
     */
    fun schemaOf(plugin: PluginEntity): PluginInputsSchema {
        val json = plugin.inputsJson ?: return PluginInputsSchema(emptyList())
        return runCatching { PluginInputsSchema.parse(AgentJson.parseToJsonElement(json)) }
            .getOrDefault(PluginInputsSchema(emptyList()))
    }

    /**
     * The values this chat's binding stored for a form, as an object — or
     * the empty object when nothing was stored or the column stopped parsing.
     * The stored string is the NORMALIZED object the form validator produced,
     * so this is already the canonical shape; anything else is an unread row,
     * not a form to answer.
     */
    fun storedValues(json: String?): JsonObject {
        if (json.isNullOrBlank()) return JsonObject(emptyMap())
        return runCatching { AgentJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
    }

    /**
     * Whether the stored values leave the schema unanswered: any validation
     * error over the stored set — a required field without a value, a value
     * the schema no longer accepts after a reinstall grew it — is a gate's
     * signal to raise the form. A schema with no required fields and a
     * binding that never answered is COMPLETE (optional inputs are optional),
     * and the empty schema of a plugin that declared no form is always
     * complete; both answer false and no dialog is ever raised for them.
     */
    fun incomplete(schema: PluginInputsSchema, stored: JsonObject): Boolean =
        PluginFormValidator.validate(schema, stored, stored).isNotEmpty()
}
