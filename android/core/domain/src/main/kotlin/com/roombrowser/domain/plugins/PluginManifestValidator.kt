package com.roombrowser.domain.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Every rule a plugin.json must pass, as one pass over the raw object that
 * returns ALL of its findings rather than throwing at the first.
 *
 * The install path shows the whole list to the person holding the ZIP: a
 * manifest that fails three rules and is told about one is fixed three times
 * slower, and the strictness (unknown fields are errors, not surprises) only
 * works when the message says what to remove. The node host re-checks the
 * pieces that gate capabilities independently — the two validators agreeing
 * is the defence, not this one being the only one.
 *
 * The rules are design §3.2 verbatim; the lengths are caps the app and the
 * host both enforce, so nothing here is advisory.
 */
object PluginManifestValidator {

    private val ID = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9-]+){1,4}$")
    private val VERSION = Regex("""^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$""")
    private val CONFIG_KEY = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val COMMAND_NAME = Regex("^[a-z][a-z0-9_-]{0,23}$")

    /** The one command name this app owns; `/plugins` is never a plugin's. */
    private const val RESERVED_COMMAND = "plugins"

    private val CONFIG_TYPES = setOf("string", "number", "boolean")
    private val HOOKS = setOf("message")

    private const val ID_CHARS = 64
    private const val NAME_CHARS = 64
    private const val DESCRIPTION_CHARS = 500
    private const val AUTHOR_FIELD_CHARS = 200
    private const val LICENSE_CHARS = 64
    private const val ENTRY_CHARS = 200
    private const val MAX_PERMISSIONS = 5
    private const val MAX_NETWORK_HOSTS = 20
    private const val MAX_CONFIG_FIELDS = 12
    private const val CONFIG_TITLE_CHARS = 64
    private const val CONFIG_DESCRIPTION_CHARS = 200
    private const val MAX_COMMANDS = 12
    private const val COMMAND_TITLE_CHARS = 64
    private const val COMMAND_DESCRIPTION_CHARS = 200

    /**
     * Every violation, or an empty list when the manifest is valid. Unknown
     * top-level keys are errors on purpose: this API is versioned by [API_VERSION],
     * and a manifest carrying a field this build never heard of must fail at
     * install, not silently do something its author did not write.
     */
    fun validate(root: JsonObject): List<String> {
        val errors = mutableListOf<String>()

        // ---- identity ----
        val id = root.string("id")
        if (id == null) {
            errors.add("id is required")
        } else if (id.length !in 3..ID_CHARS || !ID.matches(id)) {
            errors.add(
                "id \"$id\" is not valid: 3..64 chars, lowercase, at least one dot, " +
                    "segments like com.example.hello"
            )
        }

        val name = root.string("name")
        if (name == null) {
            errors.add("name is required")
        } else if (name.isEmpty() || name.length > NAME_CHARS || name.any { it.isISOControl() || it == '\u007F' }) {
            errors.add("name must be 1..$NAME_CHARS printable characters")
        }

        val version = root.string("version")
        if (version == null) {
            errors.add("version is required")
        } else if (!VERSION.matches(version)) {
            errors.add("version \"$version\" is not a plain x.y.z (an optional -prerelease may follow)")
        }

        val description = root.string("description").orEmpty()
        if (description.length > DESCRIPTION_CHARS) {
            errors.add("description is longer than $DESCRIPTION_CHARS characters")
        }

        root["author"]?.let { author ->
            val obj = author as? JsonObject
            if (obj == null) {
                errors.add("author must be an object with name, email and url")
            } else {
                val authorName = obj.string("name")
                if (authorName == null || authorName.isEmpty() || authorName.length > NAME_CHARS) {
                    errors.add("author.name must be 1..$NAME_CHARS characters")
                }
                listOf("email", "url").forEach { field ->
                    val value = obj.string(field).orEmpty()
                    if (value.length > AUTHOR_FIELD_CHARS) {
                        errors.add("author.$field is longer than $AUTHOR_FIELD_CHARS characters")
                    }
                }
                val url = obj.string("url").orEmpty()
                if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                    errors.add("author.url must start with http:// or https://")
                }
            }
        }

        val license = root.string("license").orEmpty()
        if (license.length > LICENSE_CHARS) {
            errors.add("license is longer than $LICENSE_CHARS characters")
        }

        val api = (root["api"] as? JsonPrimitive)?.intOrNull
        if (api == null) {
            errors.add("api is required and must be the number ${API_VERSION}")
        } else if (api != API_VERSION) {
            errors.add("api $api is not supported: this build speaks API version $API_VERSION only")
        }

        val entry = root.string("entry")
        if (entry == null) {
            errors.add("entry is required")
        } else if (!entry.endsWith(".js") || entry.isBlank() ||
            entry.startsWith("/") || entry.contains("..") || entry.contains('\\') ||
            entry.length > ENTRY_CHARS
        ) {
            errors.add(
                "entry \"$entry\" must be a relative .js path inside the package " +
                    "(no \"..\", no absolute path, no backslash, at most $ENTRY_CHARS chars)"
            )
        }

        // ---- permissions and the network allowlist ----
        val permissions = root.strings("permissions")
        if (permissions.size > MAX_PERMISSIONS) {
            errors.add("permissions lists ${permissions.size} entries; at most $MAX_PERMISSIONS exist")
        }
        if (permissions.toSet().size != permissions.size) {
            errors.add("permissions repeats an entry")
        }
        permissions.forEach { permission ->
            if (permission == PluginPermissions.FILES) {
                errors.add("files is a module.json-only permission")
            } else if (permission !in PluginPermissions.PERMISSIONS) {
                errors.add("permission \"$permission\" is not one of ${PluginPermissions.PERMISSIONS.sorted()}")
            }
        }
        val declaresNetwork = PluginPermissions.NETWORK in permissions

        val networkAllow = (root["network"] as? JsonObject)?.strings("allow").orEmpty()
        if (declaresNetwork) {
            if (networkAllow.isEmpty()) {
                errors.add(
                    "network.allow is required when the network permission is declared: " +
                        "a plugin may reach only the hosts it lists"
                )
            }
        } else if (networkAllow.isNotEmpty()) {
            errors.add("network.allow is declared without the network permission")
        }
        if (networkAllow.toSet().size != networkAllow.size) {
            errors.add("network.allow repeats a host")
        }
        if (networkAllow.size > MAX_NETWORK_HOSTS) {
            errors.add("network.allow lists ${networkAllow.size} hosts; at most $MAX_NETWORK_HOSTS are allowed")
        }
        networkAllow.forEach { host -> hostProblem(host)?.let { errors.add(it) } }

        // ---- config schema ----
        val schema = (root["config"] as? JsonObject)?.get("schema") as? JsonArray
        val fields = schema?.mapNotNull { it as? JsonObject }.orEmpty()
        if ((schema?.size ?: 0) > MAX_CONFIG_FIELDS) {
            errors.add("config.schema lists ${schema?.size} entries; at most $MAX_CONFIG_FIELDS are allowed")
        }
        val keys = fields.map { it.string("key").orEmpty() }
        if (keys.toSet().size != keys.size) {
            errors.add("config.schema repeats a key")
        }
        fields.forEach { field ->
            val key = field.string("key").orEmpty()
            if (!CONFIG_KEY.matches(key)) {
                errors.add("config.schema key \"$key\" is not a lowercase identifier (a..z, 0..9, _, at most 32 chars)")
            }
            val type = field.string("type").orEmpty()
            if (type !in CONFIG_TYPES) {
                errors.add("config.schema key \"$key\" has type \"$type\"; one of $CONFIG_TYPES is required")
            }
            val default = field["default"]
            if (default != null && default !is JsonPrimitive) {
                errors.add("config.schema key \"$key\" has a default that is not a plain value")
            } else if (default is JsonPrimitive && type in CONFIG_TYPES && !defaultMatches(type, default)) {
                errors.add("config.schema key \"$key\" has a default that is not a $type")
            }
            if (field.string("title").orEmpty().length > CONFIG_TITLE_CHARS) {
                errors.add("config.schema key \"$key\" has a title longer than $CONFIG_TITLE_CHARS characters")
            }
            if (field.string("description").orEmpty().length > CONFIG_DESCRIPTION_CHARS) {
                errors.add("config.schema key \"$key\" has a description longer than $CONFIG_DESCRIPTION_CHARS characters")
            }
        }

        // ---- commands ----
        val commands = (root["commands"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        if ((root["commands"] as? JsonArray)?.size ?: 0 > MAX_COMMANDS) {
            errors.add("commands lists more than $MAX_COMMANDS entries")
        }
        val commandNames = commands.map { it.string("name").orEmpty() }
        if (commandNames.toSet().size != commandNames.size) {
            errors.add("commands repeats a name")
        }
        commands.forEach { command ->
            val commandName = command.string("name").orEmpty()
            if (!COMMAND_NAME.matches(commandName)) {
                errors.add("command name \"$commandName\" is not a lowercase identifier (a..z, 0..9, _, -, at most 24 chars)")
            }
            if (commandName == RESERVED_COMMAND) {
                errors.add("command name \"$RESERVED_COMMAND\" is reserved by the app")
            }
            if (command.string("title").orEmpty().length > COMMAND_TITLE_CHARS) {
                errors.add("command \"$commandName\" has a title longer than $COMMAND_TITLE_CHARS characters")
            }
            if (command.string("description").orEmpty().length > COMMAND_DESCRIPTION_CHARS) {
                errors.add("command \"$commandName\" has a description longer than $COMMAND_DESCRIPTION_CHARS characters")
            }
        }

        // ---- hooks ----
        val hooks = root.strings("hooks")
        if (hooks.toSet().size != hooks.size) {
            errors.add("hooks repeats an entry")
        }
        if (hooks.size > 1) {
            errors.add("hooks may name at most one kind; only \"message\" exists")
        }
        hooks.filterNot { it in HOOKS }.forEach { hook ->
            errors.add("hook \"$hook\" is not one of $HOOKS")
        }

        // ---- inputs (the interactive form; both formats may declare one) ----
        errors.addAll(PluginInputsSchema.validate(root["inputs"]))

        // ---- strictness ----
        val known = setOf(
            "id", "name", "version", "description", "author", "license", "api",
            "entry", "permissions", "network", "config", "commands", "hooks", "inputs"
        )
        root.keys.forEach { key ->
            if (key !in known) {
                errors.add("unknown field \"$key\": this API version does not read it, so it is refused rather than ignored")
            }
        }

        return errors
    }

    /**
     * Why [host] is not an allowlist pattern, or null when it is one.
     *
     * Lowercase hostnames only, at least two labels (localhost and every
     * other single-label name are refused — the allowlist is for reaching the
     * internet, and an intranet literal is not what a plugin consent dialog
     * should be naming), no address literals because hostname matching says
     * nothing about an IP, and a `*.domain` only as a whole leading label.
     */
    private fun hostProblem(host: String): String? {
        if (host.isEmpty()) return "network.allow contains an empty host"
        if (host != host.lowercase()) return "network.allow host \"$host\" must be lowercase"
        if (host.any { it == '/' || it == ':' || it == ' ' || it == '\\' }) {
            return "network.allow host \"$host\" must be a hostname, not a URL or a list"
        }
        val pattern = if (host.startsWith("*.")) host.removePrefix("*.") else host
        if (pattern.isEmpty() || pattern.startsWith(".") || pattern.endsWith(".") || pattern.contains("..")) {
            return "network.allow host \"$host\" is not a valid hostname"
        }
        val labels = pattern.split('.')
        if (labels.size < 2) {
            return "network.allow host \"$host\" is a single-label name; a real domain is required (localhost included)"
        }
        if (labels.all { label -> label.isNotEmpty() && label.all { it.isDigit() } }) {
            return "network.allow host \"$host\" is an address literal; v1 allows hostnames only"
        }
        labels.forEach { label ->
            if (label.isEmpty() ||
                !label.first().isLetterOrDigit() || !label.last().isLetterOrDigit() ||
                label.any { !it.isLetterOrDigit() && it != '-' }
            ) {
                return "network.allow host \"$host\" is not a valid hostname"
            }
        }
        return null
    }

    /** Whether [value] is a plain [type] — the schema's default must match its own type. */
    private fun defaultMatches(type: String, value: JsonPrimitive): Boolean = when (type) {
        "string" -> value.isString
        "number" -> !value.isString && value.contentOrNull?.toDoubleOrNull() != null
        "boolean" -> !value.isString && (value.contentOrNull == "true" || value.contentOrNull == "false")
        else -> true
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.strings(key: String): List<String> =
        ((this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
            .orEmpty()
}
