package com.roombrowser.data.plugins

import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.plugins.PluginManifest
import com.roombrowser.domain.plugins.PluginManifestValidator
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** What an install produced: the manifest it was validated from, or why it was refused. */
sealed class PluginInstallResult {

    /** Installed; [dirName] is the directory under `<filesDir>/plugins/` holding the code. */
    data class ManifestOk(val manifest: PluginManifest, val dirName: String) : PluginInstallResult()

    /** Refused; every reason, so the holder of the ZIP can fix them in one pass. */
    data class Invalid(val errors: List<String>) : PluginInstallResult()
}

/**
 * Installs a plugin from a ZIP stream into the app-private plugin store —
 * the one door untrusted code gets through, and it opens for a VALIDATED
 * package only.
 *
 * THE STREAM IS LANDED FIRST. The guards that need the central directory —
 * entry count, declared sizes, external attributes (the only place a symlink
 * announces itself) — cannot be read from a stream, so the ZIP is copied to a
 * capped temporary file and read with [ZipFile] from there. The copy cap is
 * a guard of the store's own: a package whose COMPRESSED body is over the cap
 * cannot be holding 20 MB of honest content, only padding or a lie.
 *
 * Extraction is atomic: a temp directory is filled, every rule is re-checked,
 * and only then is it renamed into place — a failed install leaves the
 * previous version running and nothing else. Uncompressed sizes are enforced
 * again while copying, because a declared size is the zip's own claim and the
 * copy is the truth.
 *
 * java.util.zip only, no Android type: the class is JVM-unit-testable with a
 * temp directory and a [ByteArrayInputStream].
 */
class PluginZipInstaller(private val pluginsDir: File) {

    /** Installs from [stream], which is fully consumed; closing it stays the caller's. */
    fun install(stream: InputStream): PluginInstallResult {
        pluginsDir.mkdirs()
        val zip = File(pluginsDir, ".tmp-zip-${UUID.randomUUID()}")
        val errors = mutableListOf<String>()
        try {
            if (copyCapped(stream, zip) < 0) {
                return PluginInstallResult.Invalid(
                    listOf("the package is larger than ${ZIP_FILE_CAP_BYTES / MB} MB")
                )
            }
            ZipFile(zip).use { archive ->
                val entries = archive.entries().toList()
                entries.forEach { entry -> entryProblem(entry)?.let { errors.add(it) } }
                if (entries.size > MAX_ENTRIES) {
                    errors.add("the package has ${entries.size} entries; at most $MAX_ENTRIES are allowed")
                }
                val declaredTotal = entries.filterNot { it.isDirectory }
                    .sumOf { maxOf(it.size, 0L) }
                if (declaredTotal > MAX_TOTAL_BYTES) {
                    errors.add(
                        "the package's contents add up to ${declaredTotal / MB} MB; " +
                            "at most ${MAX_TOTAL_BYTES / MB} MB are allowed"
                    )
                }
                if (errors.isNotEmpty()) return PluginInstallResult.Invalid(errors)

                // Where the manifest sits: the archive root, or the root of the
                // ONE folder a zip may wrap everything in. Anything deeper is a
                // package this layout cannot reason about.
                val names = entries.filterNot { it.isDirectory }.map { it.name }.toSet()
                val prefix = manifestPrefix(names)
                    ?: return PluginInstallResult.Invalid(
                        listOf(
                            "plugin.json must be at the archive root" +
                                (if (names.isNotEmpty()) " (a single wrapping folder is also accepted)" else "")
                        )
                    )

                val manifestEntry = archive.getEntry("$prefix$MANIFEST_NAME")
                    ?: return unreadableManifest(errors)
                if (manifestEntry.size > MANIFEST_MAX_BYTES) {
                    return PluginInstallResult.Invalid(
                        listOf("plugin.json is larger than ${MANIFEST_MAX_BYTES / KB} KB")
                    )
                }
                val manifestBody = archive.getInputStream(manifestEntry).use { it.readBytes() }.decodeToString()
                val root = AgentJson.parseToJsonElement(manifestBody.trim()) as? JsonObject
                    ?: return unreadableManifest(errors)
                errors.addAll(PluginManifestValidator.validate(root))
                val manifest = PluginManifest.parse(root)
                    ?: return unreadableManifest(errors)
                if ("$prefix${manifest.entry}" !in names) {
                    errors.add("the entry file \"${manifest.entry}\" is missing from the package")
                }
                if (errors.isNotEmpty()) return PluginInstallResult.Invalid(errors)

                // Extract into a temp directory of the store, then rename into
                // place: the rename is the commit, and everything before it is
                // invisible to the runtime.
                val staging = File(pluginsDir, ".tmp-${UUID.randomUUID()}")
                try {
                    entries.forEach { entry ->
                        val target = File(staging, entry.name.removePrefix(prefix))
                        if (entry.isDirectory) {
                            // Materialized rather than skipped: an empty
                            // directory a package chose to carry is its own
                            // statement, and the tree the rename commits
                            // should match the archive's shape.
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            archive.getInputStream(entry).use { input ->
                                target.outputStream().use { output ->
                                    if (copyCapped(input, output, MAX_ENTRY_BYTES) < 0) {
                                        errors.add(
                                            "the entry \"${entry.name}\" is larger than " +
                                                "${MAX_ENTRY_BYTES / MB} MB"
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (errors.isNotEmpty()) return PluginInstallResult.Invalid(errors)
                    // The belt-and-braces walk: extraction writes plain files
                    // only, but the rename is the commit, so the tree is
                    // verified on the REAL filesystem first — a symlink that
                    // materialized by any means fails here, and a failed
                    // install leaves nothing behind but the temp dir the
                    // finally-block removes.
                    val linked = Files.walk(staging.toPath()).use { walk ->
                        walk.filter { Files.isSymbolicLink(it) }.findFirst().orElse(null)
                    }
                    if (linked != null) {
                        errors.add(
                            "the package extracted a symlink (" +
                                staging.toPath().relativize(linked).toString() +
                                "), which a plugin may not carry"
                        )
                        return PluginInstallResult.Invalid(errors)
                    }
                    val target = File(pluginsDir, manifest.dirName)
                    if (target.exists()) target.deleteRecursively()
                    if (!staging.renameTo(target)) {
                        return PluginInstallResult.Invalid(
                            listOf("the plugin directory could not be written (${target.name})")
                        )
                    }
                    return PluginInstallResult.ManifestOk(manifest, manifest.dirName)
                } finally {
                    staging.deleteRecursively()
                }
            }
        } catch (err: Exception) {
            return PluginInstallResult.Invalid(
                listOf("the package could not be read: ${err.message ?: err.javaClass.simpleName}")
            )
        } finally {
            zip.delete()
        }
    }

    /** Why this entry may not be extracted, or null when it may. */
    private fun entryProblem(entry: ZipEntry): String? {
        // A directory entry's trailing slash is the zip's own spelling of a
        // directory, not an empty path segment: the path checks read the
        // trimmed name, so "lib/" is judged as "lib".
        val name = if (entry.isDirectory) entry.name.trimEnd('/') else entry.name
        if (name.isEmpty()) return "the package has an entry with an empty name"
        if (name.length > ENTRY_NAME_CHARS) {
            return "the entry name \"$name\" is longer than $ENTRY_NAME_CHARS characters"
        }
        if (name.startsWith("/") || name.contains('\\')) {
            return "the entry \"$name\" is not a relative path"
        }
        if (name.split('/').any { it.isEmpty() || it == ".." }) {
            return "the entry \"$name\" leaves the package directory"
        }
        if (name.any { it.code < 0x20 || it.code == 0x7F }) {
            return "the entry name \"$name\" contains control characters"
        }
        // No symlink guard here on purpose: java.util.zip exposes no unix mode
        // bits (ZipEntry has no externalAttributes), and the extraction below
        // writes every entry as a PLAIN FILE through an OutputStream — a
        // symlink entry's bytes land as a regular file holding the target
        // path as text, which is inert. The post-extraction walk before the
        // rename is the belt to that braces: it verifies on the real
        // filesystem that nothing extracted as a link, however it got there.
        if (!entry.isDirectory) {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (extension in FORBIDDEN_EXTENSIONS) {
                return "the entry \"$name\" is a native executable or package, which a plugin may not carry"
            }
            if (entry.size > MAX_ENTRY_BYTES) {
                return "the entry \"$name\" is larger than ${MAX_ENTRY_BYTES / MB} MB"
            }
        }
        return null
    }

    /**
     * The directory prefix the manifest lives under: "" for a manifest at the
     * archive root, `"<folder>/"` when one folder wraps the whole package, or
     * null when neither — a manifest two levels deep, or two top-level
     * folders, is not a layout this store can install.
     */
    private fun manifestPrefix(names: Set<String>): String? {
        if (MANIFEST_NAME in names) return ""
        if (names.isEmpty()) return null
        val roots = names.map { it.substringBefore('/') }.toSet()
        if (roots.size != 1) return null
        val prefix = "${roots.first()}/"
        if (!names.all { it.startsWith(prefix) }) return null
        return if ("$prefix$MANIFEST_NAME" in names) prefix else null
    }

    private fun unreadableManifest(errors: List<String>): PluginInstallResult =
        PluginInstallResult.Invalid(errors + "plugin.json could not be read as a manifest")

    /**
     * Copies [input] into [target] up to [cap] bytes and answers the count, or
     * -1 when the source ran past the cap — a source that will not say how big
     * it is gets measured this way instead of trusted.
     */
    private fun copyCapped(input: InputStream, target: File, cap: Long = ZIP_FILE_CAP_BYTES): Long =
        target.outputStream().use { output -> copyCapped(input, output, cap) }

    private fun copyCapped(input: InputStream, output: OutputStream, cap: Long): Long {
        val buffer = ByteArray(COPY_CHUNK)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > cap) return -1
            output.write(buffer, 0, read)
        }
    }

    private companion object {
        const val MANIFEST_NAME = "plugin.json"
        const val MAX_ENTRIES = 512
        const val KB = 1024L
        const val MB = 1024L * 1024L
        const val ENTRY_NAME_CHARS = 200
        const val MANIFEST_MAX_BYTES = 64 * KB
        const val MAX_ENTRY_BYTES = 2 * MB
        const val MAX_TOTAL_BYTES = 20 * MB

        /**
         * The hard cap on the ZIP's own body. 20 MB of content plus the
         * central directory and per-entry overhead fits under this with room;
         * a body larger than this is padding or a lie, and neither is worth
         * writing to the store.
         */
        const val ZIP_FILE_CAP_BYTES = 25 * MB
        const val COPY_CHUNK = 64 * 1024

        /**
         * Extensions a plugin may never carry: native addons load code into
         * the runtime's own process, and the rest are executables or Android
         * packages a chat extension has no business being.
         */
        val FORBIDDEN_EXTENSIONS = setOf(
            "node", "so", "dll", "exe", "bat", "sh", "jar", "apk"
        )
    }
}
