package com.roombrowser.agent

import android.content.Context
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.AgentSkillEntity
import com.roombrowser.data.skills.DaoSkillStore
import com.roombrowser.data.skills.HttpSkillFetch
import com.roombrowser.data.skills.SkillInstallResult
import com.roombrowser.data.skills.SkillInstaller
import com.roombrowser.data.skills.SkillRepository
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.agent.UntrustedContent
import com.roombrowser.node.NodeRuntime
import com.roombrowser.node.packages.HttpRegistry
import com.roombrowser.node.packages.NodePackageStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * The narrow seam the four skills tools (and the /skills chat commands and
 * the manager screen's tool-shaped calls) act through — the same shape
 * [AgentWalletAccess] cuts for the wallet: an interface the executor and the
 * chat surfaces program against, wired to the real repository in exactly one
 * place, so every route (a chat turn, a /skills command, the skills screen)
 * answers with the same words and runs through the same code.
 *
 * The GATES are deliberately not here: the executor applies the confirm
 * gate, the profile's agent.skills permission and the Plan-mode refusal to
 * the TOOLS; the /skills commands and the screen are the user acting
 * directly, which is its own consent, the same way a /plugins toggle is.
 */
interface AgentSkillsAccess {

    /** The installed skills with their state, as skills_list reads them. */
    suspend fun list(): ToolResult

    /** Installs from a spec string (CLI shape, bare path, URL or sentence). */
    suspend fun install(spec: String): ToolResult

    /** Deletes one skill's row and directory. */
    suspend fun remove(skillId: String): ToolResult

    /** Flips a skill's enabled flag; the prompt block follows it immediately. */
    suspend fun setEnabled(skillId: String, enabled: Boolean): ToolResult

    /** One containment-checked file from inside a skill, fenced as data. */
    suspend fun readEntry(skillId: String, path: String): ToolResult

    /** The system-prompt block for the enabled skills, or null when none are. */
    suspend fun promptBlock(): String?
}

/**
 * The real access: [SkillRepository] for the state, the node package store
 * for the one fact a skill row cannot know — whether the npm packages its
 * install hint declares are already installed beside the runtime. Reading
 * that store is a directory listing, never a runtime start, exactly as the
 * packages screen reads it.
 */
class SkillRepositoryAccess(
    private val repo: SkillRepository,
    private val installedPackageNames: suspend () -> Set<String> = { emptySet() }
) : AgentSkillsAccess {

    override suspend fun list(): ToolResult {
        val skills = repo.list()
        if (skills.isEmpty()) {
            return ToolResult(
                true,
                "No skills are installed. Install one with skills_install — e.g. " +
                    "spec \"owner/repo/path/to/skill\" for a GitHub skill."
            )
        }
        val installed = installedPackageNames()
        val body = skills.joinToString("\n") { skill ->
            val deps = declaredDeps(skill)
            val depLine = if (deps.isEmpty()) {
                "  npm deps: none declared"
            } else {
                deps.joinToString("\n") { dep ->
                    val state = if (dep in installed) "installed" else "not installed"
                    "  npm dep: $dep ($state) — node_packages(action=install, name=\"$dep\") installs it"
                }
            }
            "- \"${skill.displayName}\" [${skill.skillId}] · " +
                (if (skill.enabled) "enabled" else "disabled") +
                (if (skill.version.isEmpty()) "" else " · v${skill.version}") +
                "\n  source: ${skill.sourceRepo}" +
                (if (skill.subPath.isEmpty()) "" else "/${skill.subPath}") +
                "\n$depLine"
        }
        val enabledCount = skills.count { it.enabled }
        return ToolResult(
            true,
            UntrustedContent.wrap(
                "${skills.size} skill(s) installed, $enabledCount enabled. Remove with " +
                    "skills_remove, enable/disable with the skills manager, and read a " +
                    "skill's files with skills_read.\n$body",
                "the installed skills"
            )
        )
    }

    override suspend fun install(spec: String): ToolResult =
        when (val result = repo.install(spec)) {
            is SkillInstallResult.Refused -> ToolResult(false, result.reason)
            is SkillInstallResult.Installed -> ToolResult(
                true,
                UntrustedContent.wrap(installSummary(result), "the installed skill's repository")
            )
        }

    /**
     * The install report. The dependency sentence is the whole npm policy in
     * one place: a skill's declared packages are REPORTED here and installed
     * only through node_packages, which asks the user — a skill install is
     * never also a package install.
     */
    private fun installSummary(result: SkillInstallResult.Installed): String {
        val entity = result.entity
        val where = entity.sourceRepo + (if (entity.subPath.isEmpty()) "" else "/${entity.subPath}")
        val version = if (entity.version.isEmpty()) "" else " (version ${entity.version})"
        val deps = result.npmDeps
        val depSentence = when {
            deps.isEmpty() -> "It declares no npm dependencies."
            else -> "It DECLARES these npm dependencies, which are NOT installed by this: " +
                deps.joinToString(", ") { "\"$it\"" } +
                " — install any of them with node_packages(action=install, name=...) if the skill " +
                "needs its tooling, and say so when you do."
        }
        return buildString {
            appendLine(if (result.updated) "Updated skill \"${entity.displayName}\"$version from $where — " +
                "the previous install of \"${entity.skillId}\" was replaced."
                else "Installed skill \"${entity.displayName}\"$version from $where.")
            appendLine("Its SKILL.md instructions join the system prompt of chats where the skill " +
                "is enabled (this one included), and its reference files are readable with " +
                "skills_read(\"${entity.skillId}\", path).")
            appendLine(depSentence)
            append("The skill is " + (if (entity.enabled) "enabled" else "disabled") + ".")
        }
    }

    override suspend fun remove(skillId: String): ToolResult {
        val removed = repo.remove(skillId)
        return if (removed) {
            ToolResult(true, "Removed skill \"$skillId\" — its files and its prompt entry are gone.")
        } else {
            ToolResult(false, "no installed skill has id \"$skillId\" — skills_list names the ids.")
        }
    }

    override suspend fun setEnabled(skillId: String, enabled: Boolean): ToolResult {
        val skill = repo.list().firstOrNull { it.skillId == skillId }
            ?: return ToolResult(false, "no installed skill has id \"$skillId\" — skills_list names the ids.")
        repo.setEnabled(skillId, enabled)
        return ToolResult(
            true,
            "Skill \"${skill.displayName}\" is now " +
                (if (enabled) "ENABLED — its instructions join new system prompts." else "DISABLED — its instructions no longer join system prompts.")
        )
    }

    override suspend fun readEntry(skillId: String, path: String): ToolResult {
        val text = repo.readEntryFile(skillId, path)
            ?: return ToolResult(
                false,
                "could not read \"$path\" in skill \"$skillId\": the file does not exist, or the " +
                    "path was refused (only relative paths inside the skill's own directory are " +
                    "read). skills_list shows each skill; SKILL.md is the usual entry point."
            )
        return ToolResult(true, UntrustedContent.wrap(text, "the installed skill \"$skillId\""))
    }

    override suspend fun promptBlock(): String? = repo.skillsPromptBlock()

    /** The row's declared npm deps, or an empty list when none or unreadable. */
    private fun declaredDeps(skill: AgentSkillEntity): List<String> = runCatching {
        (AgentJson.parseToJsonElement(skill.npmDepsJson) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
    }.getOrDefault(emptyList())
}

/**
 * The skills tools' assembly point: the one place the repository, the
 * installer and the fetch transport are built, and the factory the executor
 * construction, the /skills chat commands and (through the repository) the
 * skills screen share. One instance per process — the OkHttp client and the
 * Room singleton are the expensive parts, and the skills directory is shared
 * by definition (like the npm store, it is app-wide, not per profile).
 */
object AgentSkillsTools {

    @Volatile
    private var shared: AgentSkillsAccess? = null

    fun access(context: Context): AgentSkillsAccess =
        shared ?: synchronized(this) {
            shared ?: build(context.applicationContext).also { shared = it }
        }

    private fun build(context: Context): AgentSkillsAccess {
        val repo = repository(context)
        return SkillRepositoryAccess(
            repo = repo,
            installedPackageNames = {
                // A directory listing beside the runtime, never a runtime
                // start — the packages screen reads it the same way.
                runCatching {
                    NodePackageStore(NodeRuntime.directory(context), HttpRegistry())
                        .installed()
                        .map { it.name }
                        .toSet()
                }.getOrDefault(emptySet())
            }
        )
    }

    /** The repository over the schema's skill DAO and `<filesDir>/skills/`. */
    fun repository(context: Context): SkillRepository {
        val dao = AppDatabase.get(context).agentSkillDao()
        return SkillRepository(
            dao = dao,
            installer = SkillInstaller(
                skillsDir = File(context.filesDir, "skills"),
                fetch = HttpSkillFetch(),
                store = DaoSkillStore(dao)
            )
        )
    }
}
