package com.roombrowser.data.skills

import com.roombrowser.data.db.AgentSkillDao
import com.roombrowser.data.db.AgentSkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The skills system's one surface over the two places a skill exists: the
 * [AgentSkillDao] row (the metadata every list, gate and prompt reads) and
 * the `<filesDir>/skills/<skillId>/` directory (SKILL.md plus the reference
 * files skills_read serves).
 *
 * The row and the directory are written together by the installer and
 * removed together by [remove], because either alone is a lie — the same
 * rule PluginRepository states for plugins. Reads that leave this class are
 * either raw rows (list/observeAll) or CONTAINMENT-CHECKED text
 * ([readEntryFile]); fencing untrusted skill content as data is the agent
 * layer's job ([com.roombrowser.domain.agent.UntrustedContent]), so this
 * class stays free of Android and prompt vocabulary and remains
 * JVM-testable with a temp directory and a fake DAO.
 */
class SkillRepository(
    private val dao: AgentSkillDao,
    private val installer: SkillInstaller
) {

    /** The directory the installer commits skill trees into. */
    val skillsDir: File get() = installer.skillsDir

    /** The installed skills, as the manager screen and skills_list render them. */
    fun observeAll(): Flow<List<AgentSkillEntity>> = dao.observeAll()

    suspend fun list(): List<AgentSkillEntity> = dao.all()

    /** Delegates to [SkillInstaller]; the gates (confirm, permission, mode) live with the callers. */
    suspend fun install(spec: String): SkillInstallResult = installer.install(spec)

    /**
     * Deletes the row AND the directory, in that order of attempts: a row
     * gone while files remain is invisible weight; files gone while the row
     * stands would be a skill the model is told exists and cannot be read.
     * False when nothing by that id was installed.
     */
    suspend fun remove(skillId: String): Boolean = withContext(Dispatchers.IO) {
        val existing = dao.bySkillId(skillId) ?: return@withContext false
        File(skillsDir, skillId).deleteRecursively()
        dao.delete(skillId)
        true
    }

    suspend fun setEnabled(skillId: String, enabled: Boolean) =
        dao.setEnabled(skillId, enabled, System.currentTimeMillis())

    /**
     * One file from inside an installed skill — SKILL.md or a reference it
     * links to — as raw text, or null when the skill, the file or the PATH
     * itself is unacceptable.
     *
     * The path checks are the whole security story here and none of them is
     * optional: a skill's reference file is the model's request, i.e. input
     * written by whoever authored the skill, so `../../` (climb out),
     * absolute paths, backslash spellings and symlinks are all refusals.
     * Containment is judged on CANONICAL paths, which resolves a symlink
     * escape even when the lexical path stays inside; a symlink at any point
     * along the way is refused outright, because the installer never writes
     * one — a symlink under `<skillsDir>/` means the tree was tampered with
     * after install, and that is exactly when to stop reading.
     */
    suspend fun readEntryFile(skillId: String, relativePath: String?): String? =
        withContext(Dispatchers.IO) {
            val relative = relativePath?.trim().orEmpty()
            if (relative.isEmpty()) return@withContext null
            // Backslashes are Windows separators, not path characters here:
            // accepting one would let `..\\..` read as a harmless name.
            if (relative.startsWith("/") || relative.contains('\\') || relative.contains('\u0000')) {
                return@withContext null
            }
            if (relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
                return@withContext null
            }
            if (dao.bySkillId(skillId) == null) return@withContext null
            val dir = File(skillsDir, skillId)
            if (!dir.isDirectory) return@withContext null
            val canonicalDir = dir.canonicalFile
            val target = File(dir, relative)
            val canonicalTarget = target.canonicalFile
            if (canonicalTarget != canonicalDir &&
                !canonicalTarget.path.startsWith(canonicalDir.path + File.separator)
            ) {
                return@withContext null
            }
            // The chain is short (skill dir / sub folders / file) and each
            // link is checked: canonical containment proves where the file
            // RESOLVES, this proves nothing along the way IS a link. The file
            // itself is checked last, since the walk above stops at its parent.
            var cursor: File? = dir
            while (cursor != null && cursor.path.startsWith(canonicalDir.path)) {
                if (java.nio.file.Files.isSymbolicLink(cursor.toPath())) return@withContext null
                cursor = if (cursor.path == canonicalTarget.path) null else cursor.parentFile
            }
            if (java.nio.file.Files.isSymbolicLink(target.toPath())) return@withContext null
            if (!canonicalTarget.isFile) return@withContext null
            val bytes = canonicalTarget.inputStream().use { readCapped(it) } ?: return@withContext null
            String(bytes, Charsets.UTF_8)
        }

    /**
     * The system-prompt block for the chat's ENABLED skills, or null when
     * none are enabled — the turn's prompt stays byte-identical to the
     * pre-skills prompt, exactly as an empty plugin catalogue does.
     *
     * Each skill contributes its name, its provenance, its one-line
     * description and the SKILL.md BODY (front-matter stripped), capped per
     * skill and in total, with an explicit note when a cap bit. The block is
     * NOT fenced as page data: unlike page text, these instructions were
     * deliberately installed and enabled by the user — that choice IS the
     * consent — and the fence's "never instructions" promise would unmake
     * the feature. Arbitrary files are different: skills_read output is
     * fenced at the tool layer. The block also carries the one line that
     * tells the model skills_read exists, so reference files are a tool call
     * away without being force-fed into every prompt.
     */
    suspend fun skillsPromptBlock(): String? = withContext(Dispatchers.IO) {
        val enabled = dao.all().filter { it.enabled }.sortedBy { it.skillId }
        if (enabled.isEmpty()) return@withContext null

        val header = "INSTALLED SKILLS — the user installed these deliberately and their " +
            "instructions below are yours to follow when relevant to the task:\n"
        val parts = mutableListOf<String>()
        var total = header.length
        val dropped = mutableListOf<String>()
        for (skill in enabled) {
            val part = skillBlock(skill)
            if (total + part.length > MAX_PROMPT_CHARS && parts.isNotEmpty()) {
                dropped += skill.displayName
                continue
            }
            if (total + part.length > MAX_PROMPT_CHARS) continue
            total += part.length
            parts += part
        }
        val block = buildString {
            append(header)
            parts.forEach { append(it) }
            appendLine("Reference files a skill mentions are on disk: read them with " +
                "skills_read(skill_id, path) before guessing at their contents.")
            if (dropped.isNotEmpty()) {
                appendLine("[${dropped.size} further skill(s) omitted to keep this block within " +
                    "budget: ${dropped.joinToString(", ")}]")
            }
        }
        block
    }

    /** One skill's prompt chunk, capped at [MAX_SKILL_CHARS] with a visible truncation note. */
    private fun skillBlock(skill: AgentSkillEntity): String = buildString {
        appendLine("### ${skill.displayName} (skill id: ${skill.skillId})")
        appendLine("source: ${skill.sourceRepo}" +
            (if (skill.subPath.isEmpty()) "" else "/${skill.subPath}") +
            (if (skill.version.isEmpty()) "" else " · version ${skill.version}"))
        if (skill.description.isNotBlank()) {
            appendLine(skill.description.lineSequence().firstOrNull().orEmpty()
                .take(SkillManifest.MAX_DESCRIPTION))
        }
        val body = skillMdBody(skill.skillId)
        if (body.isEmpty()) {
            appendLine("[its SKILL.md could not be read on this device — the skill's files are " +
                "missing; reinstall it]")
        } else if (body.length > MAX_SKILL_CHARS) {
            appendLine(body.take(MAX_SKILL_CHARS))
            appendLine("[truncated — the skill's instructions continue; read SKILL.md in full with " +
                "skills_read(\"${skill.skillId}\", \"SKILL.md\")]")
        } else {
            appendLine(body)
        }
        appendLine()
    }

    /** The installed SKILL.md's body (front-matter stripped), "" when unreadable. */
    private fun skillMdBody(skillId: String): String {
        val file = File(skillsDir, "$skillId/${SkillInstaller.SKILL_MD}")
        if (!file.isFile) return ""
        return runCatching { SkillManifest.parse(file.readText())?.body.orEmpty() }.getOrDefault("")
    }

    private fun readCapped(stream: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_FILE_CHARS) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        /** The per-skill instruction cap (~a page and a half of prose). */
        const val MAX_SKILL_CHARS = 6000

        /** The whole block's cap — the same order the page snapshot's text cap keeps. */
        const val MAX_PROMPT_CHARS = 20000

        /** A reference file larger than this is data for a tool, not a prompt. */
        const val MAX_FILE_CHARS = 256 * 1024
    }
}
