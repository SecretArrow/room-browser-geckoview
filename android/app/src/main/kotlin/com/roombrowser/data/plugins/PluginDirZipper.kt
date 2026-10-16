package com.roombrowser.data.plugins

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What a zip attempt produced: the archive, or every reason it was refused. */
sealed class PluginZipOut {
    data class Ok(val bytes: ByteArray) : PluginZipOut()
    data class Refused(val errors: List<String>) : PluginZipOut()
}

/**
 * The deterministic ZIP composer for the two directions a plugin's files
 * travel OUT of the store: an edit's save (the whole dir re-zipped with the
 * one edited file's bytes substituted, so the save can ride the installer's
 * full re-validation and atomic swap) and an export (the dir as a shareable
 * `.zip` that reinstalls anywhere).
 *
 * THE LAYOUT IS THE CONTRACT: every entry a regular file at the archive
 * root's relative path, sorted by name, dated [FIXED_TIME_MS], DEFLATE, no
 * directory entries, no extra fields — the packer's byte discipline, in
 * Kotlin. Determinism is what makes the guarantees cheap: two exports of an
 * unmodified plugin are byte-equal, an exported-and-rebuilt tree diffs
 * clean, and the archive is exactly the shape [PluginZipInstaller] extracts
 * and re-validates, so both directions enter through the one door.
 *
 * The WALK never leaves `<filesDir>/plugins/<dirName>`: per-chat runtime
 * data lives under `plugin-data/` and is structurally outside it — an
 * export can never carry another chat's kv, files, or state because there
 * is no path from the walk to them, not because anything filters them out.
 *
 * java.util.zip only, no Android type — the same JVM-unit-testability rule
 * [PluginZipInstaller] follows.
 */
object PluginDirZipper {

    /** An edit's or an export's editable ceiling. */
    const val EDITABLE_MAX_BYTES = 256L * 1024L

    /** The text vocabulary a plugin legitimately carries; see [isEditable]. */
    val EDITABLE_EXTENSIONS = setOf(
        "js", "mjs", "cjs", "json", "txt", "md",
        "css", "html", "htm", "xml", "yml", "yaml", "csv"
    )

    /** 1980-01-01T00:00:00Z in ms — the packer's fixed entry date. */
    const val FIXED_TIME_MS = 315532800000L

    /**
     * The deterministic archive of [dir]'s files, [override] replacing edited
     * bytes (an edit's save substitutes exactly the file it edited). Refused
     * — with every reason, before a byte of archive is written — when the
     * walk meets a symlink or a forbidden-suffix file, or the dir passes
     * neither cap, or an [override] key names a file the dir does not hold.
     *
     * A [dir] that is not a directory answers the empty archive, which the
     * installer then refuses in its own words: the manager's row check
     * (`pluginDir(id)?.isDirectory != true`) keeps that from being reachable,
     * and refusing loudly here would crash a save race rather than decline it.
     */
    fun zipDir(dir: File, override: Map<String, ByteArray> = emptyMap()): PluginZipOut {
        val errors = mutableListOf<String>()
        val files = mutableListOf<Pair<String, ByteArray>>()
        val seen = mutableSetOf<String>()
        if (dir.isDirectory) {
            walk(dir, "", override, errors, files, seen)
        }
        override.keys.forEach { rel ->
            if (rel !in seen) {
                errors.add("the file \"$rel\" is not part of this plugin")
            }
        }
        if (errors.isNotEmpty()) return PluginZipOut.Refused(errors)
        if (files.size > MAX_ENTRIES) {
            errors.add("the plugin directory has ${files.size} entries; at most $MAX_ENTRIES are allowed")
        }
        // The total as the archive will carry it — an override's bytes count,
        // because the cap is on what is written, not on what was there.
        val total = files.sumOf { it.second.size.toLong() }
        if (total > MAX_TOTAL_BYTES) {
            errors.add("the plugin directory holds ${total / MB} MB; at most ${MAX_TOTAL_BYTES / MB} MB are allowed")
        }
        if (errors.isNotEmpty()) return PluginZipOut.Refused(errors)
        return PluginZipOut.Ok(writeZip(files))
    }

    /**
     * The deterministic archive of files that exist only in memory ([path]
     * to [bytes]) — the `/make_plugins` install path's byte source: the
     * composed `plugin.json` and entry file enter through [PluginZipInstaller]
     * exactly as a sideloaded ZIP does.
     *
     * The installer's entry-name rules run over the paths first and a bad
     * one throws [IllegalArgumentException]: the caller is the app itself,
     * and a bug there should be loud, not dialogued.
     */
    fun compose(files: List<Pair<String, ByteArray>>): ByteArray {
        files.forEach { (name, _) -> requireEntryName(name) }
        return writeZip(files)
    }

    /** `<name>-<version>.zip` with filename-hostile characters folded to '-'. */
    fun exportFileName(name: String, version: String): String =
        "${name.foldHostile()}-${version.foldHostile()}.zip"

    /**
     * §2.2's editable rule, for the editor's list: size within
     * [EDITABLE_MAX_BYTES], an extension in [EDITABLE_EXTENSIONS], and a
     * strict UTF-8 round-trip — text-only edits, binary stays binary. A file
     * that cannot be read answers false; the install-time walk is what
     * guarantees regular files, and the save re-runs every guard anyway.
     */
    fun isEditable(file: File): Boolean = runCatching {
        if (file.name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in EDITABLE_EXTENSIONS) {
            return@runCatching false
        }
        if (file.length() > EDITABLE_MAX_BYTES) {
            return@runCatching false
        }
        val bytes = file.readBytes()
        String(bytes, Charsets.UTF_8).toByteArray(Charsets.UTF_8).contentEquals(bytes)
    }.getOrDefault(false)

    /**
     * The walk itself: children in name order (deterministic refusals),
     * every symlink refused and never followed — a directory link is
     * refused BEFORE the recursion decision, so a cycle cannot walk forever —
     * forbidden suffixes refused by file, everything else read. Only regular
     * files are collected; anything else (a fifo cannot be in an
     * installer-written tree) is skipped rather than carried or hung on.
     */
    private fun walk(
        dir: File,
        prefix: String,
        override: Map<String, ByteArray>,
        errors: MutableList<String>,
        files: MutableList<Pair<String, ByteArray>>,
        seen: MutableSet<String>
    ) {
        val children = dir.listFiles() ?: return
        children.sortedBy { it.name }.forEach { child ->
            val rel = "$prefix${child.name}"
            if (Files.isSymbolicLink(child.toPath())) {
                errors.add("the plugin directory contains a symlink ($rel), which export refuses to carry")
                seen.add(rel)
                return@forEach
            }
            if (child.isDirectory) {
                walk(child, "$rel/", override, errors, files, seen)
                return@forEach
            }
            seen.add(rel)
            if (!child.isFile) return@forEach
            val name = child.name
            if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) in FORBIDDEN_EXTENSIONS) {
                errors.add("the plugin directory contains \"$name\", which a plugin may not carry")
            }
            files.add(rel to (override[rel] ?: child.readBytes()))
        }
    }

    /**
     * The deterministic archive itself: entries sorted by name, every one
     * dated [FIXED_TIME_MS], DEFLATE at the default level, no directory
     * entries (the installer materializes parents), no extra fields.
     */
    private fun writeZip(files: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { stream ->
            files.sortedBy { it.first }.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                entry.time = FIXED_TIME_MS
                stream.putNextEntry(entry)
                stream.write(bytes)
                stream.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * The installer's entry-name rules, one by one — the same sentences, so a
     * thrown [IllegalArgumentException] reads like the refusal the same path
     * would have earned had it arrived in a ZIP.
     */
    private fun requireEntryName(name: String) {
        require(name.isNotEmpty()) { "the package has an entry with an empty name" }
        require(name.length <= ENTRY_NAME_CHARS) {
            "the entry name \"$name\" is longer than $ENTRY_NAME_CHARS characters"
        }
        require(!name.startsWith("/") && !name.contains('\\')) {
            "the entry \"$name\" is not a relative path"
        }
        require(name.split('/').none { it.isEmpty() || it == ".." }) {
            "the entry \"$name\" leaves the package directory"
        }
        require(name.none { it.code < 0x20 || it.code == 0x7F }) {
            "the entry name \"$name\" contains control characters"
        }
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension !in FORBIDDEN_EXTENSIONS) {
            "the entry \"$name\" is a native executable or package, which a plugin may not carry"
        }
    }

    /** The filename-hostile characters — backslash, slash, colon, star, question mark, quotes, angles, bar — fold to '-'. Spaces stay: SAF handles them. */
    private fun String.foldHostile(): String = replace(HOSTILE, "-")

    private companion object {
        const val MB = 1024L * 1024L

        /**
         * The installer's own caps, mirrored here because its copy is private:
         * both sides refusing the same numbers is the two-direction guarantee
         * (§7.7) — export refuses what import would refuse anyway.
         */
        const val MAX_ENTRIES = 512
        const val MAX_TOTAL_BYTES = 20 * MB
        const val ENTRY_NAME_CHARS = 200

        /**
         * The installer's forbidden set, mirrored for the same reason: native
         * addons load code into the runtime's own process; the rest are
         * executables or packages a chat extension has no business being.
         */
        val FORBIDDEN_EXTENSIONS = setOf(
            "node", "so", "dll", "exe", "bat", "sh", "jar", "apk"
        )

        /** The characters a default export filename may not carry. */
        val HOSTILE = Regex("""[\\/:*?"<>|]""")
    }
}
