package com.roombrowser.agent.skills

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The index over `<filesDir>/skills/<id>/skill.json` — the skills store's
 * whole persistence layer. It reads what [SkillInstaller] wrote, flips the
 * enabled flag, and deletes; it never invents a directory, and a skill.json
 * it cannot read is skipped and REPORTED rather than crashed on, because a
 * half-deleted directory is a state a file manager can produce and the tool
 * should say so in words.
 *
 * NO DATABASE. The skills store adds no schema and no row: a skill directory
 * carrying its own metadata is the whole of the model, which is what makes
 * the store's lifecycle trivially tied to its files — remove deletes the
 * directory, and there is nothing else to clean up.
 *
 * Writes go through temp+rename ([writeRecord]), the same discipline the
 * installer's commit uses, so a metadata read never sees a half-written
 * file. All calls that touch the disk run on [Dispatchers.IO].
 */
class SkillRegistry(val root: File) {

    /** Every installed skill, in id order. Unreadable directories are named in [broken]. */
    data class Snapshot(val skills: List<SkillRecord>, val broken: List<String>)

    suspend fun list(): Snapshot = withContext(Dispatchers.IO) {
        val skills = mutableListOf<SkillRecord>()
        val broken = mutableListOf<String>()
        root.mkdirs()
        for (dir in root.listFiles().orEmpty().sortedBy { it.name }) {
            if (!dir.isDirectory || dir.name.startsWith(".")) continue
            if (!validId(dir.name)) {
                broken.add(dir.name)
                continue
            }
            val record = SkillRecord.fromFile(File(dir, SkillRecord.METADATA_FILE))
            if (record == null) {
                broken.add(dir.name)
            } else {
                skills.add(record)
            }
        }
        Snapshot(skills, broken)
    }

    /** One skill by id, or null when the id is malformed or nothing is installed under it. */
    suspend fun get(id: String): SkillRecord? = withContext(Dispatchers.IO) {
        if (!validId(id)) return@withContext null
        val dir = dirOf(id)
        if (!dir.isDirectory) return@withContext null
        SkillRecord.fromFile(File(dir, SkillRecord.METADATA_FILE))
    }

    /** The stored instruction body of an instruction skill, or null. */
    suspend fun instructionOf(record: SkillRecord): String? = withContext(Dispatchers.IO) {
        if (record.format != SkillRecord.FORMAT_INSTRUCTION) return@withContext null
        val file = File(dirOf(record.id), SkillInstaller.INSTRUCTION_FILE)
        if (!file.isFile) return@withContext null
        runCatching { file.readText() }.getOrNull()
    }

    /** Flips the enabled flag on the record ON DISK; answers the updated record, or null. */
    suspend fun setEnabled(id: String, enabled: Boolean): SkillRecord? = withContext(Dispatchers.IO) {
        val record = get(id) ?: return@withContext null
        if (record.enabled == enabled) return@withContext record
        val updated = record.copy(enabled = enabled)
        writeRecord(updated)
        updated
    }

    /**
     * Deletes a skill. A plugin-format skill's code lives in the PLUGIN store
     * (and may be shared with a plugin installed some other way), so its row
     * is uninstalled first and its data directory is deleted with it; the
     * metadata directory goes either way. Answers what actually happened, so
     * the caller can report a partial removal honestly.
     */
    suspend fun remove(record: SkillRecord): String? = withContext(Dispatchers.IO) {
        var problem: String? = null
        if (record.format == SkillRecord.FORMAT_PLUGIN && record.pluginId.isNotBlank()) {
            try {
                pluginUninstaller?.invoke(record.pluginId)
            } catch (err: Exception) {
                problem = "the plugin row could not be removed: " +
                    (err.message ?: err.javaClass.simpleName)
            }
        }
        val dir = dirOf(record.id)
        if (dir.exists() && !dir.deleteRecursively() && problem == null) {
            problem = "the skill directory (${record.id}) could not be deleted"
        }
        problem
    }

    /**
     * The uninstall seam for plugin-format skills, set by the constructor's
     * caller — an app that has a [com.roombrowser.data.repo.PluginRepository]
     * hands in its `uninstall`, and a JVM test (which has no Room) hands in
     * a recording fake or nothing at all.
     */
    var pluginUninstaller: (suspend (String) -> Unit)? = null

    /**
     * Whether [id] may be used as a directory name: exactly the vocabulary
     * [SkillInstaller] and [SkillFrontmatter.sanitizeName] emit. A stricter
     * check than needed is the point — this string reaches `File(root, id)`.
     */
    fun validId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 96 && !id.startsWith(".") &&
            id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }

    private fun dirOf(id: String): File {
        require(validId(id)) { "invalid skill id: $id" }
        return File(root, id)
    }

    companion object {
        /**
         * The directory name under the app's filesDir that holds every skill.
         * THE ONE PLACE the store's location is named: the chat tool's
         * registry, the manager screen's registry and any future reader
         * resolve it from here, so a skill installed through either surface
         * is visible to both — two spellings of the path is how a store
         * becomes two stores that disagree.
         */
        const val STORE_DIR_NAME = "skills"
    }

    /** The metadata write: temp file in the SAME directory, then the rename. */
    private fun writeRecord(record: SkillRecord) {
        val dir = dirOf(record.id)
        dir.mkdirs()
        val temp = File(dir, SkillRecord.METADATA_FILE + ".tmp")
        temp.writeText(SkillRecord.toJsonString(record))
        if (!temp.renameTo(File(dir, SkillRecord.METADATA_FILE))) {
            temp.delete()
            throw java.io.IOException("could not update ${SkillRecord.METADATA_FILE} for ${record.id}")
        }
    }
}
