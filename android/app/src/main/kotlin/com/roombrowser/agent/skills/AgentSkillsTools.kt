package com.roombrowser.agent.skills

import com.roombrowser.domain.agent.ActionVerdict
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.agent.UntrustedContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * The `skills` tool group — self-contained, exactly like
 * [com.roombrowser.agent.AgentNodePackages]: `execute` answers
 * [AgentTools.SKILLS] and returns null for every other name, so the executor
 * can give it a dispatch slot before its unknown-tool fallback without caring
 * what it is.
 *
 * The actions:
 *  - list / status: local reads of the registry, ungated;
 *  - enable / disable: a flag flip on an instruction skill (whether its text
 *    joins the system prompt). A plugin-format skill is enabled per CHAT in
 *    the plugin UI, and the answer says so instead of pretending this flag
 *    reaches it;
 *  - install / update / remove: reach GitHub or delete files, and go through
 *    the confirm gate — the same refuse(name, label) shape the executor's own
 *    NODE_PACKAGES branch uses, with the label naming the skill and source.
 *
 * Everything a skill supplies — its name, description, body, file inventory,
 * install errors — is fenced as DATA by [UntrustedContent], because none of
 * it is this app talking. Every failure is a [ToolResult] in words; nothing
 * here throws at its caller.
 */
class AgentSkillsTools(
    private val registry: SkillRegistry,
    private val installer: SkillInstaller,
    /**
     * The same gate the executor's other write actions use. Injected rather
     * than reached around, so the confirm UI, YOLO and the unattended
     * refusals all behave for a skill install exactly as they do for a
     * package install.
     */
    private val confirmGate: suspend (name: String, label: String) -> ActionVerdict
) {

    /** The dispatch slot: null for any name that is not this group's tool. */
    suspend fun execute(name: String, argsJson: String): ToolResult? {
        if (name != AgentTools.SKILLS) return null
        return try {
            withContext(Dispatchers.IO) { dispatch(argsJson) }
        } catch (ce: CancellationException) {
            // A cancelled turn is not a failed action: the answer below is for
            // a failure the model can read, and a cancellation is the caller
            // taking its turn back (the executor's own catch does the same).
            throw ce
        } catch (err: Exception) {
            ToolResult(false, "the skills action failed: ${err.message ?: err.javaClass.simpleName}")
        }
    }

    private suspend fun dispatch(argsJson: String): ToolResult {
        val args = parseArgs(argsJson)
        return when (val action = text(args, "action")?.lowercase()) {
            "list" -> list()
            "status" -> status(text(args, "id"))
            "install" -> install(text(args, "source"))
            "update" -> update(text(args, "id"), text(args, "source"))
            "remove" -> remove(text(args, "id"))
            "enable" -> setEnabled(text(args, "id"), true)
            "disable" -> setEnabled(text(args, "id"), false)
            null -> ToolResult(false, "missing 'action' argument")
            else -> ToolResult(
                false,
                "unknown skills action: $action — use list, status, install, update, " +
                    "remove, enable or disable"
            )
        }
    }

    // ------------------------------------------------------------ actions

    private suspend fun list(): ToolResult {
        val snapshot = registry.list()
        val body = if (snapshot.skills.isEmpty()) {
            "No skills are installed. Use action=install with a GitHub source, e.g. " +
                "action=install source=\"owner/repo/path/to/skill\"."
        } else {
            "${snapshot.skills.size} skill(s) installed:\n" + snapshot.skills.joinToString("\n") { s ->
                "  ${s.id} — ${s.name}${versionOf(s)} · ${s.format} · " +
                    // A plugin-format row's enabled flag says nothing (its
                    // enablement is per chat), so the honest word is the
                    // truth about where it is switched on, not a flag value.
                    (if (s.format == SkillRecord.FORMAT_PLUGIN) "enabled per chat" else if (s.enabled) "enabled" else "disabled") +
                    (if (s.source.isNotBlank()) " · from ${s.source}" else "")
            }
        }
        val broken = if (snapshot.broken.isEmpty()) {
            ""
        } else {
            "\nUnreadable skill directories (excluded from this listing): " +
                snapshot.broken.joinToString(", ")
        }
        return ToolResult(
            true,
            UntrustedContent.wrap(body + broken + promptStateHint(snapshot), "the skills registry")
        )
    }

    private suspend fun status(id: String?): ToolResult {
        val record = registry.get(nonBlank(id))
            ?: return ToolResult(
                false,
                "No installed skill with id '${id.orEmpty()}'. Call action=list for the ids."
            )
        val body = buildString {
            appendLine("id: ${record.id}")
            appendLine("name: ${record.name}")
            if (record.version.isNotBlank()) appendLine("version: ${record.version}")
            appendLine("format: ${record.format}")
            appendLine("enabled: ${record.enabled}")
            appendLine("source: ${record.source}")
            appendLine("stored: ${record.size} bytes · body sha256 ${record.bodySha256.take(16)}…")
            if (record.format == SkillRecord.FORMAT_PLUGIN) {
                appendLine("plugin id: ${record.pluginId}")
                appendLine(
                    "This skill is a PLUGIN package. Its tools register per chat like any other " +
                        "plugin's: enable it for a chat with /plugins in the agent panel — the " +
                        "skills enabled flag does not reach it. Its credentials, if it asks for " +
                        "any, are entered in that chat's plugin screen, never here."
                )
            }
            if (record.scripts.isNotEmpty()) {
                appendLine("scripts it ships (described at install, never copied or run):")
                record.scripts.forEach { appendLine("  - ${it.file} (${it.language})") }
            }
            if (record.runtimeNote.isNotBlank()) appendLine("runtime note: ${record.runtimeNote}")
            if (record.truncated) {
                appendLine(
                    "note: the instruction body was longer than the cap and was stored truncated; " +
                        "the source repository has the rest"
                )
            }
            if (record.format == SkillRecord.FORMAT_INSTRUCTION) {
                val instruction = registry.instructionOf(record)
                if (instruction == null) {
                    appendLine("instruction body: MISSING on disk — remove and install again")
                } else {
                    appendLine(
                        "instruction body (first $EXCERPT_CHARS of ${instruction.length} characters):"
                    )
                    append(instruction.take(EXCERPT_CHARS))
                }
            }
        }.trimEnd()
        return ToolResult(true, UntrustedContent.wrap(body, "the skill \"${record.name}\""))
    }

    private suspend fun install(source: String?): ToolResult {
        val raw = nonBlank(source)
            ?: return ToolResult(
                false,
                "action=install needs a 'source' argument — e.g. \"owner/repo/path/to/skill\", " +
                    "an '@branch' suffix, or a https://github.com URL"
            )
        val parsed = SkillSource.parse(SkillSource.stripCliSpelling(raw)).getOrElse { err ->
            return ToolResult(false, "that source could not be used: ${err.message}")
        }
        // A re-install of a source that is already here keeps its id (the
        // manifest may have renamed itself); a NEW source installs under its
        // own id and may legitimately replace an old skill of the same id —
        // reported, never silent, in the answer below.
        val before = registry.list()
        val sameSource = before.skills.firstOrNull { it.source == parsed.raw }
        val label = "install skill from ${parsed.shortForm}"
        gateRefusal(label)?.let { return it }

        val outcome = installer.install(parsed, wantedId = sameSource?.id)
        val record = when (outcome) {
            is SkillInstaller.Outcome.Refused -> return ToolResult(
                false,
                "the skill was not installed:\n" +
                    outcome.errors.joinToString("\n") { "— $it" }
            )
            is SkillInstaller.Outcome.Installed -> outcome.record
        }
        // VERIFY: the report names what is actually on disk, re-read through
        // the registry — an install that reports success without the registry
        // seeing it is a lie waiting for the next turn.
        val verified = registry.get(record.id)
            ?: return ToolResult(
                false,
                "the skill was written but is not readable through the registry — " +
                    "remove and retry"
            )
        val dir = File(registry.root, record.id)
        val present = when (verified.format) {
            SkillRecord.FORMAT_INSTRUCTION ->
                File(dir, SkillInstaller.INSTRUCTION_FILE).isFile
            else ->
                File(dir, SkillRecord.METADATA_FILE).isFile
        }
        if (!present) {
            return ToolResult(
                false,
                "the skill's files did not land where they should have (${dir.absolutePath}) — " +
                    "remove and retry"
            )
        }
        val replaced = before.skills.firstOrNull {
            it.id == verified.id && it.source != verified.source
        }
        val summary = buildString {
            append("Installed \"${verified.name}\" as skill id \"${verified.id}\" (${verified.format}).")
            if (replaced != null) {
                append(" This replaced the skill previously installed there from \"${replaced.source}\".")
            }
            if (verified.format == SkillRecord.FORMAT_PLUGIN) {
                append(
                    " Its tools are available once the plugin is enabled for a chat with /plugins."
                )
            } else if (verified.enabled) {
                append(" Its instructions join this agent's prompt from the next turn.")
            } else {
                append(" It is disabled; action=enable turns it on.")
            }
            append(" Directory: ${dir.absolutePath} · ${verified.size} bytes.")
            if (verified.scripts.isNotEmpty()) {
                append(" It ships ${verified.scripts.size} script-shaped file(s); none was copied " +
                    "or executed.")
            }
            if (verified.runtimeNote.isNotBlank()) append(" ${verified.runtimeNote}")
        }
        return ToolResult(true, UntrustedContent.wrap(summary, "the installed skill"))
    }

    private suspend fun update(id: String?, source: String?): ToolResult {
        val skillId = nonBlank(id)
            ?: return ToolResult(false, "action=update needs the 'id' of an installed skill")
        val record = registry.get(skillId)
            ?: return ToolResult(
                false,
                "No installed skill with id '$skillId'. Call action=list for the ids."
            )
        // The source comes from the RECORD unless the caller overrides it —
        // re-downloading what was there before is what an update IS.
        val raw = nonBlank(source) ?: record.source
        val parsed = SkillSource.parse(SkillSource.stripCliSpelling(raw)).getOrElse { err ->
            return ToolResult(false, "that source could not be used: ${err.message}")
        }
        val label = "update skill \"${record.name}\" from ${parsed.shortForm}"
        gateRefusal(label)?.let { return it }

        val outcome = installer.install(parsed, wantedId = record.id)
        return when (outcome) {
            is SkillInstaller.Outcome.Refused -> ToolResult(
                false,
                "the update did not happen (the previous version is untouched):\n" +
                    outcome.errors.joinToString("\n") { "— $it" }
            )
            is SkillInstaller.Outcome.Installed -> {
                val verified = registry.get(record.id)
                    ?: return ToolResult(
                        false,
                        "the skill was written but is not readable through the registry — " +
                            "check action=status"
                    )
                ToolResult(
                    true,
                    UntrustedContent.wrap(
                        "Updated \"${verified.name}\" (id \"${verified.id}\") from " +
                            "${parsed.shortForm}. Version: " +
                            "${verified.version.ifBlank { "(none declared)" }}. " +
                            "${verified.size} bytes stored.",
                        "the updated skill"
                    )
                )
            }
        }
    }

    private suspend fun remove(id: String?): ToolResult {
        val skillId = nonBlank(id)
            ?: return ToolResult(false, "action=remove needs the 'id' of an installed skill")
        val record = registry.get(skillId)
            ?: return ToolResult(
                false,
                "No installed skill with id '$skillId'. Call action=list for the ids."
            )
        gateRefusal("remove skill \"${record.name}\"")?.let { return it }
        val problem = registry.remove(record)
        if (problem != null) return ToolResult(false, "the skill was not fully removed: $problem")
        // Fenced: the record's name was written by whoever published the skill,
        // so the answer it lands in is data like every other skill-supplied
        // string — the class comment's rule, applied to the last two answers.
        return ToolResult(
            true,
            UntrustedContent.wrap(
                "Removed \"${record.name}\" (id \"${record.id}\")." +
                    if (record.format == SkillRecord.FORMAT_PLUGIN) {
                        " Its plugin row and every chat's stored data for it were removed too."
                    } else {
                        " Its instructions no longer join the agent's prompt."
                    },
                "the skills registry"
            )
        )
    }

    private suspend fun setEnabled(id: String?, enabled: Boolean): ToolResult {
        val skillId = nonBlank(id)
            ?: return ToolResult(
                false,
                "action=${if (enabled) "enable" else "disable"} needs the 'id' of an " +
                    "installed skill"
            )
        val record = registry.get(skillId)
            ?: return ToolResult(
                false,
                "No installed skill with id '$skillId'. Call action=list for the ids."
            )
        if (record.format == SkillRecord.FORMAT_PLUGIN) {
            return ToolResult(
                false,
                "\"${record.name}\" is a PLUGIN-format skill: it is enabled per chat with /plugins " +
                    "in the agent panel, and each chat grants its permissions there. The skills " +
                    "enabled flag does not control it."
            )
        }
        val updated = registry.setEnabled(skillId, enabled)
            ?: return ToolResult(false, "the skill's metadata could not be updated")
        // Fenced for the same reason remove() is: the name is the skill's own.
        return ToolResult(
            true,
            UntrustedContent.wrap(
                (if (enabled) "Enabled" else "Disabled") + " \"${updated.name}\". " +
                    (if (enabled) {
                        "Its instructions join the agent's prompt from the next turn."
                    } else {
                        "Its instructions no longer join the agent's prompt."
                    }),
                "the skills registry"
            )
        )
    }

    // -------------------------------------------------------------- seams

    /**
     * The confirm gate's refusal, worded the way the executor's own refuse()
     * answers. Allow answers null; anything else becomes the tool failure the
     * model reads.
     */
    private suspend fun gateRefusal(label: String): ToolResult? =
        when (val verdict = confirmGate(AgentTools.SKILLS, label)) {
            is ActionVerdict.Allow -> null
            is ActionVerdict.Deny -> ToolResult(false, verdict.reason)
            is ActionVerdict.Ask -> ToolResult(false, "the user denied this action")
        }

    private fun promptStateHint(snapshot: SkillRegistry.Snapshot): String {
        val enabled = snapshot.skills.count {
            it.format == SkillRecord.FORMAT_INSTRUCTION && it.enabled
        }
        if (enabled == 0) {
            return if (snapshot.skills.any { it.format == SkillRecord.FORMAT_INSTRUCTION }) {
                "\nNo instruction skills are enabled, so none of them joins the agent's prompt."
            } else {
                ""
            }
        }
        return "\n$enabled instruction skill(s) enabled; their instructions join the agent's prompt."
    }

    private fun versionOf(record: SkillRecord): String =
        if (record.version.isBlank()) "" else " v${record.version}"

    private fun parseArgs(argsJson: String): JsonObject = runCatching {
        AgentJson.parseToJsonElement(argsJson) as? JsonObject
    }.getOrNull() ?: JsonObject(emptyMap())

    private fun text(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun nonBlank(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val EXCERPT_CHARS = 1200
    }
}
