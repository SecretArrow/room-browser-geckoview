package com.roombrowser.domain.plugins

import com.roombrowser.domain.agent.AgentJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Who wrote the plugin, as the manifest declares it. All fields may be blank. */
data class PluginAuthor(
    val name: String,
    val email: String,
    val url: String
)

/**
 * One entry of the manifest's config schema. [type] is the wire spelling
 * ("string" | "number" | "boolean"); [default] is a JsonPrimitive carrying the
 * value the plugin ships — the zero value of [type] when the manifest left it
 * out — because a config value rides the wire as JSON and keeping it as JSON
 * means no second representation to drift from.
 */
data class PluginConfigField(
    val key: String,
    val type: String,
    val default: JsonElement,
    val title: String,
    val description: String
)

/** One command a manifest declares: `/name`, a title, a description. */
data class PluginCommandDecl(
    val name: String,
    val title: String,
    val description: String
)

/**
 * A plugin's manifest, as plugin.json declares it.
 *
 * The parse is TOLERANT by itself — missing fields become defaults, wrong
 * shapes become empty — because the strictness lives in
 * [PluginManifestValidator], which reads the same JSON object and names every
 * rule it breaks. A caller that wants only to label or display what arrived
 * can parse freely; a caller that wants to trust it validates first. That is
 * the split the installer uses, and it keeps the "why was this rejected"
 * messages in one place instead of smeared over exceptions.
 *
 * The model is also what survives installation: the app stores the manifest's
 * pieces in the `plugins` row and rebuilds this class from them, so what the
 * host is told at run time is what was validated at install time, not a
 * re-read of a file the plugin could have exchanged since.
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: PluginAuthor?,
    val license: String,
    val api: Int,
    /** The entry file's path relative to the plugin root. */
    val entry: String,
    val permissions: List<String>,
    val networkAllow: List<String>,
    val configSchema: List<PluginConfigField>,
    val commands: List<PluginCommandDecl>,
    val hooks: List<String>
) {
    /**
     * The plugin's directory name on disk: the id with `.` → `_`. The id's own
     * grammar allows only lowercase letters, digits, dots and dashes, so the
     * result needs no further sanitising — and the same name is embedded in
     * the plugin's agent tool names, so a plugin's code, data root and tools
     * all answer to one spelling.
     */
    val dirName: String get() = id.replace('.', '_')

    companion object {
        /**
         * The manifest inside [body], or null when [body] is not JSON or not
         * an object. Tolerant on purpose — see the class KDoc.
         */
        fun parse(body: String): PluginManifest? = runCatching {
            parse(AgentJson.parseToJsonElement(body.trim()) as? JsonObject)
        }.getOrNull()

        /** The manifest an object holds, or null when the object is not one. */
        fun parse(obj: JsonObject?): PluginManifest? {
            if (obj == null) return null
            return PluginManifest(
                id = obj.string("id").orEmpty(),
                name = obj.string("name").orEmpty(),
                version = obj.string("version").orEmpty(),
                description = obj.string("description").orEmpty(),
                author = (obj["author"] as? JsonObject)?.let {
                    PluginAuthor(
                        name = it.string("name").orEmpty(),
                        email = it.string("email").orEmpty(),
                        url = it.string("url").orEmpty()
                    )
                },
                license = obj.string("license").orEmpty(),
                api = (obj["api"] as? JsonPrimitive)?.intOrNull ?: 0,
                entry = obj.string("entry").orEmpty(),
                permissions = obj.strings("permissions"),
                networkAllow = (obj["network"] as? JsonObject)?.let { it.strings("allow") }.orEmpty(),
                configSchema = parseConfigSchema(obj["config"]),
                commands = parseCommands(obj["commands"]),
                hooks = obj.strings("hooks")
            )
        }

        /**
         * The config schema a `config.schema` element holds — the piece the
         * app rebuilds the model from when it reconstitutes an installed
         * plugin. Accepts the manifest's own `config` object or the bare
         * schema array (the shape the stored row carries); absent, wrong
         * shape or empty all read as no schema.
         */
        fun parseConfigSchema(element: JsonElement?): List<PluginConfigField> {
            val schema = (element as? JsonObject)?.get("schema") ?: element ?: return emptyList()
            val array = schema as? JsonArray ?: return emptyList()
            return array.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val type = obj.string("type") ?: "string"
                PluginConfigField(
                    key = obj.string("key").orEmpty(),
                    type = type,
                    // Absent default is the type's zero value, so the merged
                    // config a call carries is always complete without the
                    // runtime having to know each type's zero.
                    default = obj["default"] ?: zeroOf(type),
                    title = obj.string("title").orEmpty(),
                    description = obj.string("description").orEmpty()
                )
            }
        }

        /** The commands a `commands` element holds; see [parseConfigSchema]. */
        fun parseCommands(element: JsonElement?): List<PluginCommandDecl> {
            val array = element as? JsonArray ?: return emptyList()
            return array.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                PluginCommandDecl(
                    name = obj.string("name").orEmpty(),
                    title = obj.string("title").orEmpty(),
                    description = obj.string("description").orEmpty()
                )
            }
        }

        private fun zeroOf(type: String): JsonPrimitive = when (type) {
            "number" -> JsonPrimitive(0)
            "boolean" -> JsonPrimitive(false)
            else -> JsonPrimitive("")
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.strings(key: String): List<String> =
            ((this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                .orEmpty()
    }
}

/**
 * One config schema entry as its JSON object, so the stored row and the wire
 * agree on the spelling — the app rebuilds the model from exactly this shape.
 */
fun PluginConfigField.toJson(): JsonObject = JsonObject(
    buildMap {
        put("key", JsonPrimitive(key))
        put("type", JsonPrimitive(type))
        put("default", if (default is JsonPrimitive) default else JsonPrimitive(""))
        put("title", JsonPrimitive(title))
        put("description", JsonPrimitive(description))
    }
)

/** One command declaration as its JSON object; see [PluginConfigField.toJson]. */
fun PluginCommandDecl.toJson(): JsonObject = JsonObject(
    buildMap {
        put("name", JsonPrimitive(name))
        put("title", JsonPrimitive(title))
        put("description", JsonPrimitive(description))
    }
)
