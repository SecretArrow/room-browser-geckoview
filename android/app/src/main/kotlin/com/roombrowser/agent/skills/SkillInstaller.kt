package com.roombrowser.agent.skills

import com.roombrowser.data.plugins.PluginDirZipper
import com.roombrowser.data.plugins.PluginInstallResult
import com.roombrowser.data.plugins.PluginZipOut
import com.roombrowser.data.repo.PluginRepository
import com.roombrowser.node.packages.TarGz
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * The one door a skill comes through: download the GitHub tarball, extract it
 * into a staging directory, locate the requested subdirectory, and install
 * what that directory turns out to be — a PLUGIN (it carries plugin.json or
 * module.json, and enters through [PluginZipInstaller]'s full validation by
 * way of the deterministic re-zip) or an INSTRUCTION SKILL (it carries a
 * SKILL.md, and only its frontmatter, body and a scripts inventory are kept).
 * Anything else is refused with the list of what WAS found, so the model can
 * react to the real directory instead of guessing.
 *
 * THE SECURITY SHAPE, in one place:
 *  - the download is TLS-only, from codeload.github.com only, body-capped at
 *    [DOWNLOAD_MAX_BYTES] before the bytes are handed to anything;
 *  - extraction is [TarGz], which refuses absolute paths and `..` climbs and
 *    writes regular files only — a tar symlink entry is skipped, never
 *    materialized;
 *  - the belt-and-braces symlink walk (PluginZipInstaller's own post-extract
 *    rule) runs over the skill directory before anything is committed;
 *  - the committed tree is held to PluginZipInstaller's caps: 512 entries,
 *    2 MB per file, 20 MB total;
 *  - NOTHING DOWNLOADED IS EXECUTED, at install time or ever: an instruction
 *    skill is stored as text, and a plugin-format skill runs only through the
 *    per-chat plugin path, which is consented to separately.
 *
 * Only the instruction body, the metadata and the inventory are kept for an
 * instruction skill — its scripts/ and references/ files are DESCRIBED, not
 * copied, because this app has no runtime for them and a file nobody can run
 * or read is stored weight pretending to be functionality.
 */
class SkillInstaller(
    private val skillsDir: File,
    /** The plugin store a plugin-format skill lands in; null leaves that format refused. */
    private val pluginRepo: PluginRepository?,
    private val tarballSource: TarballSource = HttpTarballSource()
) {

    /**
     * The uninstall seam for the plugin row a REPLACED install leaves behind
     * (a skill whose manifest renamed its plugin id between updates). Set by
     * the constructor's caller — an app that has a [PluginRepository] hands in
     * its `uninstall`, and a JVM test hands in a recording fake or nothing —
     * without touching the constructor's signature, which the controller's
     * wiring depends on staying stable.
     */
    var pluginUninstaller: (suspend (String) -> Unit)? = null

    /** Where the tarball bytes come from — the network seam every test drives with a fake. */
    fun interface TarballSource {
        /** Fetches [url], refusing anything past [HttpTarballSource.MAX_BYTES]; throws on failure. */
        fun fetch(url: String): ByteArray
    }

    sealed class Outcome {
        /** Installed and committed; [dir] is the real directory under `<filesDir>/skills/`. */
        data class Installed(val record: SkillRecord, val dir: File) : Outcome()

        /** Refused before anything was committed; every reason, so one retry can fix them all. */
        data class Refused(val errors: List<String>) : Outcome()
    }

    /**
     * Downloads, validates and commits the skill at [source]. When [wantedId]
     * is set (an update), the installed id is kept even if the skill renamed
     * itself, and whatever was there before is replaced.
     */
    suspend fun install(source: SkillSource, wantedId: String? = null): Outcome =
        withContext(Dispatchers.IO) {
            skillsDir.mkdirs()
            val bytes = try {
                download(source)
            } catch (ce: CancellationException) {
                // A cancelled turn is not a refused install: the caller is
                // taking its turn back, and the refusal below would report a
                // failure the model was never allowed to finish reading.
                throw ce
            } catch (err: DownloadFailed) {
                return@withContext Outcome.Refused(listOf(err.reason))
            } catch (err: Exception) {
                return@withContext Outcome.Refused(
                    listOf("could not download '${source.shortForm}': " +
                        (err.message ?: err.javaClass.simpleName))
                )
            }

            val work = Files.createTempDirectory(skillsDir.toPath(), ".dl-").toFile()
            try {
                val extracted = try {
                    TarGz.extract(bytes, work)
                } catch (err: IOException) {
                    return@withContext Outcome.Refused(
                        listOf("the archive was refused while unpacking: ${err.message}")
                    )
                }
                if (extracted.isEmpty()) {
                    return@withContext Outcome.Refused(
                        listOf("the archive held no files — '${source.shortForm}' is empty or not a repository snapshot")
                    )
                }
                val root = archiveRoot(work)
                    ?: return@withContext Outcome.Refused(
                        listOf("the archive's layout could not be read (no single top-level directory)")
                    )
                val skillDir = locate(source, root)
                    ?: return@withContext Outcome.Refused(
                        listOf(
                            "the source path '${source.subPath}' does not exist in " +
                                "'${source.owner}/${source.repo}' at ref '${source.ref}'. The archive " +
                                "root contains: " + root.list().orEmpty().take(10).joinToString(", ")
                        )
                    )
                if (!skillDir.isDirectory) {
                    return@withContext Outcome.Refused(
                        listOf("the source path names a file, not a directory — a skill is a directory " +
                            "with a SKILL.md (or a plugin manifest) in it")
                    )
                }

                // The belt-and-braces walk, PluginZipInstaller's rule mirrored:
                // extraction writes plain files, and this verifies the REAL
                // tree before the rename makes any of it visible.
                linkedDir(skillDir)?.let { link ->
                    return@withContext Outcome.Refused(
                        listOf("the skill carries a symlink ($link), which a skill may not")
                    )
                }
                capsProblems(skillDir).let { problems ->
                    if (problems.isNotEmpty()) return@withContext Outcome.Refused(problems)
                }

                return@withContext when {
                    File(skillDir, PLUGIN_MANIFEST).isFile || File(skillDir, MODULE_MANIFEST).isFile ->
                        installPlugin(source, skillDir, wantedId)
                    File(skillDir, SkillRecord.SKILL_DOC).isFile ->
                        installInstruction(source, skillDir, wantedId)
                    else -> Outcome.Refused(
                        listOf(
                            "no skill was found at that path: it has no SKILL.md and no plugin.json " +
                                "or module.json. It does contain: " +
                                skillDir.list().orEmpty().take(10).joinToString(", ")
                        )
                    )
                }
            } finally {
                work.deleteRecursively()
            }
        }

    // ------------------------------------------------------------ plugin

    /**
     * Re-zips the directory deterministically and hands it to the plugin
     * store, so a plugin-format skill is validated by EXACTLY the code a
     * sideloaded ZIP is validated by — one door, one rulebook, and the row
     * it produces registers its tools with every chat like any other plugin.
     */
    private suspend fun installPlugin(
        source: SkillSource,
        skillDir: File,
        wantedId: String?
    ): Outcome {
        val repo = pluginRepo
            ?: return Outcome.Refused(
                listOf("this skill is a plugin package, and the plugin store is not available " +
                    "in this run — install it from the app's own screens")
            )
        val zipped = PluginDirZipper.zipDir(skillDir)
        if (zipped is PluginZipOut.Refused) return Outcome.Refused(zipped.errors)
        val bytes = (zipped as PluginZipOut.Ok).bytes
        val result = repo.install(ByteArrayInputStream(bytes))
        val manifest = when (result) {
            is PluginInstallResult.ManifestOk -> result.manifest
            is PluginInstallResult.Invalid -> return Outcome.Refused(
                result.errors.map { "the plugin manifest refused it: $it" }
            )
        }
        val now = System.currentTimeMillis()
        val id = wantedId ?: ("plugin-" + SkillFrontmatter.sanitizeName(manifest.id)
            .ifEmpty { SkillFrontmatter.sanitizeName("${source.owner}-${source.repo}") })
        val record = SkillRecord(
            id = id,
            name = manifest.name,
            description = manifest.description,
            version = manifest.version,
            source = source.raw,
            owner = source.owner,
            repo = source.repo,
            ref = source.ref,
            subPath = source.subPath,
            installedAt = now,
            updatedAt = now,
            format = SkillRecord.FORMAT_PLUGIN,
            enabled = true,
            bodySha256 = sha256Hex(bytes),
            size = bytes.size.toLong(),
            runtimeSupport = SkillRecord.RUNTIME_FULL,
            runtimeNote = "",
            scripts = scriptInventory(skillDir),
            pluginId = manifest.id
        )
        // The replaced install's plugin row, when the row the new manifest
        // makes is a DIFFERENT id: the store would otherwise hold a row whose
        // skill pointer has moved on — installed, enabled per chat, and
        // unreachable from this skill forever. Best effort, and honestly so:
        // the update itself has already succeeded, the stale row stays
        // removable from the plugins screen, and a failed cleanup must not be
        // reported as a failed update.
        val previous = wantedId?.let {
            SkillRecord.fromFile(File(File(skillsDir, it), SkillRecord.METADATA_FILE))
        }
        val stale = previous?.pluginId?.takeIf { it.isNotBlank() && it != manifest.id }
        if (stale != null) {
            try {
                pluginUninstaller?.invoke(stale)
            } catch (err: Exception) {
                // The stale row survives; the skill record does not point at
                // it either way.
            }
        }
        return commit(record, emptyMap())
    }

    // ------------------------------------------------------- instruction

    /**
     * Reads the SKILL.md, keeps the frontmatter's fields and the capped body,
     * inventories the scripts the source carries, and commits two files:
     * `skill.json` and `instruction.md`. Nothing else from the archive is
     * copied — see the class comment for why.
     */
    private fun installInstruction(
        source: SkillSource,
        skillDir: File,
        wantedId: String?
    ): Outcome {
        val doc = File(skillDir, SkillRecord.SKILL_DOC)
        if (doc.length() > DOC_MAX_BYTES) {
            return Outcome.Refused(
                listOf("SKILL.md is ${(doc.length() / 1024)} KB; at most ${DOC_MAX_BYTES / 1024} KB is read")
            )
        }
        val text = runCatching { doc.readText() }.getOrElse {
            return Outcome.Refused(listOf("SKILL.md could not be read: ${it.message}"))
        }
        val front = SkillFrontmatter.parse(text)
        if (front is SkillFrontmatter.Failure) return Outcome.Refused(listOf(front.reason))
        val parsed = front as SkillFrontmatter.Parsed

        val bodyTooLong = parsed.body.length > BODY_MAX_BYTES
        val body = if (bodyTooLong) parsed.body.take(BODY_MAX_BYTES) else parsed.body
        val scripts = scriptInventory(skillDir) + fencedScripts(parsed.body)
        val nonJs = scripts.filter { it.language !in RUNNABLE_LANGUAGES }.distinctBy { it.language }
        val partial = nonJs.isNotEmpty()

        val id = wantedId
            ?: SkillFrontmatter.sanitizeName(parsed.name).ifEmpty {
                SkillFrontmatter.sanitizeName(
                    source.subPath.substringAfterLast('/').ifEmpty { "${source.owner}-${source.repo}" }
                )
            }.ifEmpty { "skill" }
        val now = System.currentTimeMillis()
        val record = SkillRecord(
            id = id,
            name = parsed.name,
            description = parsed.description.take(DESCRIPTION_MAX_CHARS),
            version = parsed.version.take(64),
            source = source.raw,
            owner = source.owner,
            repo = source.repo,
            ref = source.ref,
            subPath = source.subPath,
            installedAt = now,
            updatedAt = now,
            format = SkillRecord.FORMAT_INSTRUCTION,
            enabled = true,
            bodySha256 = sha256Hex(body),
            size = body.length.toLong(),
            truncated = bodyTooLong,
            runtimeSupport = if (partial) SkillRecord.RUNTIME_PARTIAL else SkillRecord.RUNTIME_FULL,
            runtimeNote = if (partial) {
                val languages = nonJs.joinToString(", ") { it.language }
                "scripts require $languages; this app runs JavaScript only, via its embedded " +
                    "Node runtime — the skill's browsing steps still work, and no script of " +
                    "theirs was copied or executed"
            } else {
                ""
            },
            scripts = scripts
        )
        val files = mapOf(
            INSTRUCTION_FILE to body.toByteArray(Charsets.UTF_8)
        )
        return commit(record, files)
    }

    /**
     * The commit: a staging directory beside the target, filled with
     * [skill.json] and [files], renamed into place. The ORDER IS THE
     * ATOMICITY: the previous version is deleted only AFTER the staging tree
     * is complete, so a failure while writing staging leaves the old skill
     * exactly as it was, and the only window in which it is gone is the one
     * between that delete and the rename — which is why the tree is fully
     * validated before this runs.
     */
    private fun commit(record: SkillRecord, files: Map<String, ByteArray>): Outcome {
        val target = File(skillsDir, record.id)
        val staging = File(skillsDir, ".staging-${UUID.randomUUID()}")
        try {
            staging.mkdirs()
            File(staging, SkillRecord.METADATA_FILE)
                .writeText(SkillRecord.toJsonString(record))
            files.forEach { (name, bytes) ->
                File(staging, name).writeBytes(bytes)
            }
            // Now — and only now — retire what was there. A rename onto a
            // non-empty directory fails on every filesystem this app meets,
            // so the replacement needs the delete; the staging tree being
            // complete is what makes the delete safe to have done.
            if (target.exists()) target.deleteRecursively()
            if (!staging.renameTo(target)) {
                return Outcome.Refused(
                    listOf("the skill directory could not be written (${record.id})")
                )
            }
            return Outcome.Installed(record, target)
        } catch (err: Exception) {
            return Outcome.Refused(
                listOf("the skill could not be written: ${err.message ?: err.javaClass.simpleName}")
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    // ------------------------------------------------------------- reads

    /**
     * The directory the source's [SkillSource.subPath] names inside the
     * extracted archive, or null when it is not there. Every segment is
     * re-checked here against the path rules — [SkillSource.parse] rejects
     * climbs too, but this function is the one whose argument becomes a
     * [File], so it does not take another layer's word for it.
     */
    private fun locate(source: SkillSource, root: File): File? {
        if (source.subPath.isEmpty()) return root
        if (source.subPath.startsWith("/") || source.subPath.contains('\\')) return null
        val segments = source.subPath.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (segments.any { it.any { c -> c.code < 0x20 || c.code == 0x7F } }) return null
        return File(root, segments.joinToString("/"))
    }

    /** The one top-level directory a GitHub snapshot wraps everything in, or null for anything else. */
    private fun archiveRoot(work: File): File? {
        val children = work.listFiles().orEmpty().filter { it.name != "pax_global_header" }
        val directories = children.filter { it.isDirectory }
        return when {
            directories.size == 1 && children.size == 1 -> directories[0]
            directories.isEmpty() -> work // a flat archive: the work dir IS the root
            else -> null
        }
    }

    /** The first symlink under [dir], relative, or null — the extraction walk's belt to TarGz's braces. */
    private fun linkedDir(dir: File): String? {
        val root = dir.toPath()
        return Files.walk(root).use { walk ->
            walk.filter { Files.isSymbolicLink(it) }
                .findFirst()
                .map { root.relativize(it).toString() }
                .orElse(null)
        }
    }

    /** PluginZipInstaller's caps, restated for the directory that is about to become a skill. */
    private fun capsProblems(dir: File): List<String> {
        val problems = mutableListOf<String>()
        var files = 0
        var total = 0L
        val queue = ArrayDeque<File>().apply { add(dir) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (child in current.listFiles().orEmpty()) {
                if (child.isDirectory) {
                    queue.add(child)
                } else {
                    files++
                    total += child.length()
                    if (child.length() > MAX_ENTRY_BYTES) {
                        problems.add(
                            "the file \"${child.name}\" is ${child.length() / MB} MB; at most " +
                                "${MAX_ENTRY_BYTES / MB} MB is kept per file"
                        )
                    }
                }
            }
        }
        if (files > MAX_ENTRIES) {
            problems.add("the skill has $files files; at most $MAX_ENTRIES are kept")
        }
        if (total > MAX_TOTAL_BYTES) {
            problems.add("the skill's contents add up to ${total / MB} MB; at most " +
                "${MAX_TOTAL_BYTES / MB} MB are kept")
        }
        return problems
    }

    /** What the source carries that LOOKS like a program, by extension, capped and sorted. */
    private fun scriptInventory(dir: File): List<SkillRecord.ScriptFile> {
        val found = mutableListOf<SkillRecord.ScriptFile>()
        val queue = ArrayDeque<File>().apply { add(dir) }
        while (queue.isNotEmpty() && found.size < SCRIPTS_MAX) {
            val current = queue.removeFirst()
            for (child in current.listFiles().orEmpty().sortedBy { it.name }) {
                if (found.size >= SCRIPTS_MAX) break
                if (child.isDirectory) {
                    queue.add(child)
                    continue
                }
                if (Files.isSymbolicLink(child.toPath())) continue
                val language = SCRIPT_LANGUAGES[
                    child.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                ] ?: continue
                val relative = dir.toPath().relativize(child.toPath()).toString()
                found.add(SkillRecord.ScriptFile(relative, language))
            }
        }
        return found
    }

    /**
     * The fenced code blocks in the instruction body, by their info string —
     * a skill whose only "script" is a ```python block still deserves the
     * honest note, because the model will read that block as something to run.
     */
    private fun fencedScripts(body: String): List<SkillRecord.ScriptFile> {
        val languages = FENCE.findAll(body)
            .mapNotNull { match ->
                val info = match.groupValues[1].trim().lowercase(Locale.ROOT)
                FENCE_LANGUAGES[info]
            }
            .distinct()
            .take(SCRIPTS_MAX)
        return languages.map { SkillRecord.ScriptFile("SKILL.md fenced code block", it) }
    }

    /** Thrown by [TarballSource] adapters; its message is the model-readable failure. */
    private class DownloadFailed(val reason: String) : IOException(reason)

    /**
     * The download: tries every URL the source offers (the refs/heads form,
     * then the bare form that resolves tags), and refuses with the LAST
     * failure's words when none answers. A source that came from
     * [SkillSource.parse] cannot name another host, so the only thing this
     * loop can reach is codeload.
     */
    private fun download(source: SkillSource): ByteArray {
        var last: String? = null
        for (url in source.tarballUrls) {
            try {
                return tarballSource.fetch(url)
            } catch (err: Exception) {
                last = err.message ?: err.javaClass.simpleName
            }
        }
        throw DownloadFailed(
            "could not download '${source.shortForm}' from codeload.github.com: " +
                (last ?: "the download failed") +
                " — check the owner/repo spelling and that the ref exists"
        )
    }

    companion object {

        // ---- PluginZipInstaller's caps, mirrored: both sides refusing the
        // same numbers is what keeps a skill and a plugin the same shape.
        internal const val MAX_ENTRIES = 512
        internal const val MB = 1024L * 1024L
        internal const val MAX_ENTRY_BYTES = 2 * MB
        internal const val MAX_TOTAL_BYTES = 20 * MB

        /** The tarball body cap — the download is refused past this, before extraction. */
        internal const val DOWNLOAD_MAX_BYTES = 25L * MB

        /** The instruction body cap; a longer body is truncated with an honest flag. */
        internal const val BODY_MAX_BYTES = 32 * 1024

        /** The SKILL.md file size beyond which the document is not read at all. */
        internal const val DOC_MAX_BYTES = 256L * 1024L

        internal const val DESCRIPTION_MAX_CHARS = 500
        internal const val SCRIPTS_MAX = 50
        internal const val PLUGIN_MANIFEST = "plugin.json"
        internal const val MODULE_MANIFEST = "module.json"

        /** The stored body's file name inside the skill directory. */
        const val INSTRUCTION_FILE = "instruction.md"

        /** Extensions the store recognises as scripts, and the language each names. */
        internal val SCRIPT_LANGUAGES = mapOf(
            "py" to "Python",
            "sh" to "shell",
            "bash" to "shell",
            "rb" to "Ruby",
            "pl" to "Perl",
            "php" to "PHP",
            "ps1" to "PowerShell",
            "lua" to "Lua",
            "ts" to "TypeScript",
            "tsx" to "TypeScript",
            "js" to "JavaScript",
            "mjs" to "JavaScript",
            "cjs" to "JavaScript"
        )

        /** Fenced-code info strings that name a language, mapped to the same vocabulary. */
        internal val FENCE_LANGUAGES = mapOf(
            "python" to "Python", "py" to "Python",
            "shell" to "shell", "sh" to "shell", "bash" to "shell", "zsh" to "shell",
            "ruby" to "Ruby", "perl" to "Perl", "php" to "PHP",
            "powershell" to "PowerShell", "lua" to "Lua",
            "typescript" to "TypeScript", "ts" to "TypeScript",
            "javascript" to "JavaScript", "js" to "JavaScript", "node" to "JavaScript"
        )

        /**
         * The languages this app can actually run: plain JavaScript, through
         * the embedded Node runtime. Everything else a skill ships is
         * described honestly rather than pretended runnable.
         */
        internal val RUNNABLE_LANGUAGES = setOf("JavaScript")

        private val FENCE = Regex("(?m)^```[ \\t]*([A-Za-z0-9+#_-]*)")

        /** SHA-256 of [bytes], hex — the metadata's content check. */
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        /**
         * The default source: codeload over HTTPS, redirect-free, body-capped.
         * codeload does not redirect, and a redirect would leave the host this
         * class just checked — so a 3xx is refused like any other non-200.
         */
        class HttpTarballSource(
            private val connectTimeoutMillis: Int = 15_000,
            private val readTimeoutMillis: Int = 60_000
        ) : TarballSource {

            override fun fetch(url: String): ByteArray {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                try {
                    connection.connectTimeout = connectTimeoutMillis
                    connection.readTimeout = readTimeoutMillis
                    // A redirect would leave the host the URL was checked
                    // against; codeload never needs one.
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept", "application/x-gzip")
                    val code = connection.responseCode
                    if (code != java.net.HttpURLConnection.HTTP_OK) {
                        throw IOException("codeload answered HTTP $code for the ref")
                    }
                    val output = java.io.ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > DOWNLOAD_MAX_BYTES) {
                                throw IOException(
                                    "the tarball is larger than ${DOWNLOAD_MAX_BYTES / MB} MB"
                                )
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                    return output.toByteArray()
                } finally {
                    connection.disconnect()
                }
            }
        }
    }
}
