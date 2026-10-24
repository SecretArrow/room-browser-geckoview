package com.roombrowser.agent.skills

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one installed skill records about itself — the contents of the
 * `skill.json` each skill directory carries, and the whole of what the
 * registry, the tool and the manager screen know. Deliberately a FILE and not
 * a database row: the skills store must add no schema, and a directory that
 * carries its own metadata is deletable, copyable and inspectable with
 * nothing but a file manager.
 */
@Serializable
data class SkillRecord(
    /** The directory name under `<filesDir>/skills/`, and the tool's `id`. */
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    /** The source string the user gave, verbatim. */
    val source: String,
    val owner: String = "",
    val repo: String = "",
    val ref: String = "",
    val subPath: String = "",
    val installedAt: Long,
    val updatedAt: Long,
    /** [FORMAT_INSTRUCTION] or [FORMAT_PLUGIN]. */
    val format: String,
    /** Whether an instruction skill's text joins the agent's system prompt. */
    val enabled: Boolean = true,
    /** SHA-256 of the stored instruction body, hex — the "is it still what I installed" check. */
    val bodySha256: String = "",
    /** The stored body's size in bytes (the capped instruction text, not the repo's). */
    val size: Long = 0,
    /** True when the SKILL.md body ran past [SkillInstaller.BODY_MAX_BYTES] and was cut. */
    val truncated: Boolean = false,
    /** [RUNTIME_FULL] or [RUNTIME_PARTIAL] — can this app run the skill's scripts? */
    val runtimeSupport: String = RUNTIME_FULL,
    /**
     * The plain-language note that goes with [RUNTIME_PARTIAL]: which runtime
     * the scripts want, and what still works without it. NEVER a promise
     * that a Python or shell script can run — this app runs JavaScript only.
     */
    val runtimeNote: String = "",
    /** What the skill shipped that LOOKS like code: file and the language its extension names. */
    val scripts: List<ScriptFile> = emptyList(),
    /** For [FORMAT_PLUGIN]: the id of the row the plugin landed in. */
    val pluginId: String = ""
) {
    /** One script-shaped file the skill source carried, and the language its extension names. */
    @Serializable
    data class ScriptFile(val file: String, val language: String)

    companion object {
        const val FORMAT_INSTRUCTION = "instruction"
        const val FORMAT_PLUGIN = "plugin"
        const val RUNTIME_FULL = "full"
        const val RUNTIME_PARTIAL = "partial"

        /** The file every skill directory carries. */
        const val METADATA_FILE = "skill.json"

        /** The Anthropic-style skill document an instruction install is read from. */
        const val SKILL_DOC = "SKILL.md"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Reads a skill.json, or null when it is absent or unreadable — a broken row is skipped, never crashed on. */
        fun fromFile(file: java.io.File): SkillRecord? = runCatching {
            json.decodeFromString(serializer(), file.readText())
        }.getOrNull()

        /** The exact bytes a skill.json is written with; [SkillRegistry] writes it via temp+rename. */
        fun toJsonString(record: SkillRecord): String = json.encodeToString(serializer(), record)
    }
}

/**
 * The parsed frontmatter and body of a SKILL.md — the Anthropic-style agent
 * skill document. Parsed by hand, without a YAML dependency, because the
 * shape an instruction skill needs is six flat `key: value` lines and a
 * markdown body; a general YAML parser would be a new dependency for the
 * one case this store has.
 *
 * MALFORMED IS A REFUSAL, NOT A GUESS: a document with no frontmatter, no
 * closing fence or no `name` comes back as [Failure] with the reason, because
 * an instruction skill whose name or description could not be read is a
 * different failure from "the file was not there", and the two want
 * different sentences.
 */
sealed class SkillFrontmatter {

    data class Parsed(
        val name: String,
        val description: String,
        val version: String,
        /** The `allowed-tools` line, verbatim and unparsed — it is reported, never acted on. */
        val allowedTools: String,
        /** Every other frontmatter line the parser did not consume, as written. */
        val extra: Map<String, String>,
        val body: String
    ) : SkillFrontmatter()

    data class Failure(val reason: String) : SkillFrontmatter()

    companion object {

        /** The frontmatter block is capped: a header this big is not a header. */
        internal const val FRONTMATTER_MAX_BYTES = 8 * 1024

        /**
         * Parses [text]. Field values are single-line `key: value` pairs;
         * nested YAML (a line indented under a key) is collected under the
         * parent key verbatim so an unknown-but-legal document still parses,
         * and nothing in it is ever executed.
         */
        fun parse(text: String): SkillFrontmatter {
            val lines = text.lines()
            if (lines.firstOrNull()?.trimEnd() != "---") {
                return Failure("SKILL.md does not start with a '---' frontmatter fence — " +
                    "it is not an instruction skill in the SKILL.md format")
            }
            var close = -1
            var frontmatterBytes = 0
            for (i in 1 until lines.size) {
                frontmatterBytes += lines[i].length + 1
                if (frontmatterBytes > FRONTMATTER_MAX_BYTES) {
                    return Failure("SKILL.md's frontmatter is larger than " +
                        "$FRONTMATTER_MAX_BYTES bytes — not a skill document this store will read")
                }
                if (lines[i].trimEnd() == "---") {
                    close = i
                    break
                }
            }
            if (close < 0) {
                return Failure("SKILL.md's frontmatter fence is never closed with a second '---'")
            }

            val fields = linkedMapOf<String, String>()
            var lastKey: String? = null
            for (line in lines.subList(1, close)) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val colon = trimmed.indexOf(':')
                if (colon <= 0) {
                    // A continuation of the previous key (nested YAML) — kept
                    // verbatim so nothing is silently dropped, and nothing in
                    // it is read as structure.
                    lastKey?.let { key -> fields[key] = fields[key].orEmpty() + "\n" + line.trimEnd() }
                    continue
                }
                val key = trimmed.substring(0, colon).trim()
                val value = trimmed.substring(colon + 1).trim()
                    .trim('"', '\'')
                if (key.isEmpty()) continue
                fields[key] = value
                lastKey = key
            }

            val name = fields["name"]?.trim().orEmpty()
            if (name.isEmpty()) {
                return Failure("SKILL.md's frontmatter names no 'name' — the field is what the " +
                    "skill is registered as")
            }
            if (name.length > NAME_MAX_CHARS) {
                return Failure("SKILL.md's name is longer than $NAME_MAX_CHARS characters")
            }
            return Parsed(
                name = name,
                description = fields["description"].orEmpty(),
                version = fields["version"].orEmpty(),
                allowedTools = fields["allowed-tools"].orEmpty(),
                extra = fields
                    .filterKeys { it !in KNOWN_FIELDS }
                    .filterValues { it.isNotBlank() },
                body = lines.drop(close + 1).joinToString("\n").trim()
            )
        }

        /**
         * The directory name a skill's frontmatter name becomes: lowercase,
         * `[a-z0-9-]`, clamped — the vocabulary that is safe as a path segment
         * on every filesystem this app can meet. Empty input answers empty so
         * the caller can fall back to the source-derived id.
         */
        fun sanitizeName(raw: String): String {
            val folded = raw.lowercase()
                .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
                .joinToString("")
                .trim('-')
            val collapsed = folded.replace(Regex("-{2,}"), "-")
            return collapsed.take(NAME_MAX_CHARS).trim('-')
        }

        const val NAME_MAX_CHARS = 48

        private val KNOWN_FIELDS = setOf("name", "description", "version", "allowed-tools")
    }
}
