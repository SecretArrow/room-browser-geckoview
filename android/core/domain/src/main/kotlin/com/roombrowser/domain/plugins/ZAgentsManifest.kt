package com.roombrowser.domain.plugins

import com.roombrowser.domain.agent.AgentJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A ZAgents module's manifest, as module.json declares it.
 *
 * ZAgents is LENIENT where plugin.json is strict: a wrong shape falls back
 * to its default rather than being refused, an unknown permission name is
 * dropped (a typo can never widen the sandbox), and unknown fields are
 * ignored. The refusals this format does have live in [validate], and they
 * are Room Browser's own security adjustments — the one that matters is
 * that a module which reaches for the network must NAME its hosts, because
 * "any host" is not a scope this app consents to even though the ZAgents
 * contract allows it.
 *
 * The model is also what the row is built from: [toPluginManifest] maps a
 * module onto this app's plugin vocabulary — the `zagents.` id prefix, the
 * permission table, the semver-coerced version — because a module and a
 * plugin share one table, one installer and one runtime. The format is a
 * column on the row, not a second system.
 */
data class ZAgentsManifest(
    /** The sanitized machine name — the module's identity everywhere. */
    val name: String,
    /** The version exactly as declared ("0.0.0" when absent); NOT semver. */
    val version: String,
    /** The display title; empty when the manifest left it out. */
    val title: String,
    /** What the manager, the sheet and the system prompt show. */
    val description: String,
    /** The entry the manifest declared, or "" when it did not. */
    val entry: String,
    /** The permissions the manifest declared — unknown names dropped, order kept. */
    val permissions: List<String>,
    /** The allowlist as the host checks it: trimmed, lowercased, empties dropped. */
    val allowedHosts: List<String>,
    /** The effective per-run timeout, clamped to 1..120 seconds. */
    val timeoutSeconds: Int,
    /** The interactive input form the module asks for, if any. */
    val inputs: PluginInputsSchema
) {

    /** `zagents.<name>` — the id the module installs under, and the row's key. */
    val pluginId: String get() = "$ID_PREFIX.$name"

    /** The display name: the title when the manifest set one, the machine name otherwise. */
    val displayName: String get() = title.ifBlank { name }

    /**
     * The version this app's semver column stores: the declared string when
     * it already is a plain x.y.z (an optional prerelease may follow), else
     * its leading x.y.z prefix, else "0.0.0". The declared string itself is
     * kept verbatim — a module's "v1.2-beta" is its own spelling, and the
     * sheet, the badge and the system prompt show it, not the coercion.
     */
    val semverVersion: String get() =
        if (SEMVER.matches(version)) version else LEADING_SEMVER.find(version)?.value ?: DEFAULT_VERSION

    /**
     * The entry this module actually runs: what module.json declared, else
     * the `main` a package.json in the same package names, else
     * `main.js`. The installer hands [packageMain] in because it is the
     * only side that can read the ZIP; the chain is one rule, so it lives
     * here where the manifest is.
     */
    fun resolveEntry(packageMain: String?): String =
        entry.ifBlank { packageMain?.takeIf { it.isNotBlank() } ?: DEFAULT_ENTRY }

    /**
     * The manifest a ZAgents module becomes in this app's plugin
     * vocabulary. [entry] is the RESOLVED entry ([resolveEntry] of the
     * package), because what the row must name is the file that runs, not
     * the chain that picked it.
     */
    fun toPluginManifest(entry: String): PluginManifest = PluginManifest(
        id = pluginId,
        name = displayName,
        version = semverVersion,
        description = description,
        author = null,
        license = "",
        api = API_VERSION,
        entry = entry,
        permissions = mappedPermissions(),
        networkAllow = allowedHosts,
        configSchema = emptyList(),
        commands = emptyList(),
        hooks = emptyList(),
        inputs = inputs
    )

    /**
     * The module's permissions in the row's vocabulary: `storage` stays
     * `storage`, `network` stays `network` over the same host allowlist,
     * `files` becomes `storage` AND the module-only `files` name, `chat`
     * becomes `chat.context`, `agent` becomes `agent.generate`. Duplicates
     * collapse; the declared order is kept for the consent dialog.
     */
    private fun mappedPermissions(): List<String> =
        permissions.flatMap { mapPermission(it) }.distinct()

    companion object {

        /** The id prefix that marks a row as coming from a module.json. */
        const val ID_PREFIX = "zagents"

        const val DEFAULT_ENTRY = "main.js"
        const val DEFAULT_VERSION = "0.0.0"
        const val DEFAULT_TIMEOUT_SECONDS = 30
        const val MAX_TIMEOUT_SECONDS = 120

        /** The sanitize cap, straight from the ZAgents registry. */
        const val MAX_NAME_CHARS = 40

        private const val TITLE_CHARS = 64
        private const val VERSION_CHARS = 64
        private const val DESCRIPTION_CHARS = 500
        private const val ENTRY_CHARS = 200

        /** The five names a module.json may declare; everything else is dropped. */
        private val PERMISSIONS = setOf("storage", "network", "files", "chat", "agent")

        private val SEMVER = Regex("""^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$""")
        private val LEADING_SEMVER = Regex("""^\d+\.\d+\.\d+""")

        /** The plugin id grammar the generated id must fit, not assume it fits. */
        private val PLUGIN_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9-]+){1,4}$")

        /**
         * The manifest inside [body], or null when [body] is not JSON or
         * not an object — tolerant on purpose, see the class KDoc.
         */
        fun parse(body: String): ZAgentsManifest? = runCatching {
            parse(AgentJson.parseToJsonElement(body.trim()) as? JsonObject)
        }.getOrNull()

        /** The manifest an object holds, or null when the object is not one. */
        fun parse(obj: JsonObject?): ZAgentsManifest? {
            if (obj == null) return null
            return ZAgentsManifest(
                name = sanitizeName(obj.string("name").orEmpty()),
                version = obj.string("version")?.takeIf { it.isNotBlank() }?.take(VERSION_CHARS)
                    ?: DEFAULT_VERSION,
                title = obj.string("title").orEmpty().take(TITLE_CHARS),
                description = obj.string("description").orEmpty().take(DESCRIPTION_CHARS),
                entry = obj.string("entry").orEmpty(),
                permissions = declaredPermissions(obj),
                allowedHosts = cleanAllowedHosts(obj),
                timeoutSeconds = ((obj["timeoutSeconds"] as? JsonPrimitive)?.intOrNull
                    ?: DEFAULT_TIMEOUT_SECONDS).coerceIn(1, MAX_TIMEOUT_SECONDS),
                inputs = PluginInputsSchema.parse(obj["inputs"])
            )
        }

        /**
         * Every hard refusal a module.json can earn, or an empty list when
         * the manifest is one this app can install. Only four rules refuse:
         * an entry that leaves the package, a name whose generated id cannot
         * be stored, the network permission without an allowlist (this
         * app's one deliberate tightening of the ZAgents contract), and an
         * `inputs` schema this build cannot render — [PluginInputsSchema]
         * owns that one's messages. Wrong shapes elsewhere are defaults,
         * not errors, exactly as ZAgents reads them.
         */
        fun validate(root: JsonObject): List<String> {
            val errors = mutableListOf<String>()

            val entry = root.string("entry").orEmpty()
            if (entry.isNotBlank() && (entry.startsWith("/") || entry.contains("..") ||
                entry.contains('\\') || entry.length > ENTRY_CHARS)
            ) {
                errors.add(
                    "module.json entry \"$entry\" must be a relative path inside the module " +
                        "(no \"..\", no absolute path, no backslash, at most $ENTRY_CHARS chars)"
                )
            }

            val name = sanitizeName(root.string("name").orEmpty())
            if (!PLUGIN_ID.matches("$ID_PREFIX.$name")) {
                errors.add(
                    "module.json name \"${root.string("name").orEmpty()}\" sanitizes to \"$name\", " +
                        "which does not fit this app's plugin id grammar — use at most four " +
                        "dot-separated segments"
                )
            }

            if ("network" in declaredPermissions(root) && cleanAllowedHosts(root).isEmpty()) {
                errors.add(
                    "module.json declares the network permission but no allowedHosts allowlist — " +
                        "Room Browser requires an explicit host allowlist; " +
                        "add \"allowedHosts\": [...] to module.json"
                )
            }

            errors.addAll(PluginInputsSchema.validate(root["inputs"]))
            return errors
        }

        /**
         * The name a module is known by, exactly as the ZAgents registry
         * sanitizes it: trimmed and lowercased, every run of characters the
         * grammar does not know collapsed to one `-`, leading and trailing
         * dots and dashes stripped, runs of dashes collapsed, capped at 40
         * characters, and the fallback `module` for everything that lands
         * empty (or on the two names that could only ever mean a directory).
         */
        fun sanitizeName(raw: String): String {
            val cleaned = raw.trim().lowercase()
                .replace(Regex("[^a-z0-9._-]+"), "-")
                .trim('.', '-')
                .replace(Regex("-{2,}"), "-")
                .take(MAX_NAME_CHARS)
            return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "module" else cleaned
        }

        /** The declared permissions, unknown names dropped, order preserved. */
        private fun declaredPermissions(root: JsonObject): List<String> =
            ((root["permissions"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                .orEmpty()
                .filter { it in PERMISSIONS }

        /** The allowlist, trimmed and lowercased with empty patterns skipped. */
        private fun cleanAllowedHosts(root: JsonObject): List<String> =
            ((root["allowedHosts"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                .orEmpty()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }

        /** One ZAgents permission as the row's permission names. */
        private fun mapPermission(permission: String): List<String> = when (permission) {
            "storage" -> listOf("storage")
            "network" -> listOf("network")
            "files" -> listOf("storage", "files")
            "chat" -> listOf(PluginPermissions.CHAT_CONTEXT)
            "agent" -> listOf(PluginPermissions.AGENT_GENERATE)
            else -> emptyList()
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
