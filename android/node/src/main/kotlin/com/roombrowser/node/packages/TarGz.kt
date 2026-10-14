package com.roombrowser.node.packages

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Unpacks an npm tarball (gzipped ustar) without pulling in a tar library.
 *
 * The archive is treated as hostile. A path that resolves outside [extract]'s
 * destination is refused rather than sanitised, because a tar entry is exactly
 * the shape the tar-slip attack takes, and the work is bounded by declared size
 * and entry count before a single byte of an entry is read, because a package
 * arrives over the network from whoever published it.
 */
object TarGz {

    /** A package's unpacked size, over which an install is refused. */
    internal const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    /** How many entries an archive may declare. */
    internal const val MAX_ENTRIES = 10_000

    private const val BLOCK = 512
    private const val NAME_OFFSET = 0
    private const val NAME_LENGTH = 100
    private const val SIZE_OFFSET = 124
    private const val SIZE_LENGTH = 12
    private const val TYPE_OFFSET = 156
    private const val PREFIX_OFFSET = 345
    private const val PREFIX_LENGTH = 155
    private const val COPY_BUFFER = 8 * 1024

    /** npm tarballs wrap everything in one directory named `package`. */
    private const val ROOT_SEGMENT = "package"

    private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:")

    /**
     * Writes every regular file in [gzip] under [into] and returns the relative
     * paths written. Directory, link, GNU long-name and pax entries are skipped.
     */
    fun extract(gzip: ByteArray, into: File): List<String> {
        into.mkdirs()
        val written = mutableListOf<String>()
        var consumed = 0L
        var entries = 0
        GZIPInputStream(ByteArrayInputStream(gzip)).use { raw ->
            val input = BufferedInputStream(raw)
            val header = ByteArray(BLOCK)
            while (true) {
                val read = readHeader(input, header)
                if (read == 0) break
                if (header.all { it == 0.toByte() }) break
                entries++
                if (entries > MAX_ENTRIES) {
                    throw IOException("the archive declares more than $MAX_ENTRIES entries")
                }
                val size = declaredSize(header)
                val padded = (size + BLOCK - 1) / BLOCK * BLOCK
                consumed += BLOCK + padded
                if (consumed > MAX_TOTAL_BYTES) {
                    throw IOException("the archive unpacks to more than $MAX_TOTAL_BYTES bytes")
                }
                val name = entryName(header)
                if (isRegular(header)) {
                    val relative = relativePath(name, into)
                    val target = File(into, relative)
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> copy(input, out, size) }
                    written += relative
                } else {
                    skip(input, size)
                }
                skip(input, padded - size)
            }
        }
        return written
    }

    /** 0 at a clean end, 512 for a whole block, a refusal for a torn one. */
    private fun readHeader(input: InputStream, header: ByteArray): Int {
        var filled = 0
        while (filled < header.size) {
            val read = input.read(header, filled, header.size - filled)
            if (read < 0) {
                if (filled == 0) return 0
                throw IOException("the archive ends in the middle of a tar header")
            }
            filled += read
        }
        return filled
    }

    private fun declaredSize(header: ByteArray): Long {
        val text = field(header, SIZE_OFFSET, SIZE_LENGTH).trim { it == '\u0000' || it == ' ' }
        if (text.isEmpty()) return 0
        return text.toLongOrNull(8) ?: throw IOException("unreadable size field in a tar header")
    }

    private fun entryName(header: ByteArray): String {
        val name = field(header, NAME_OFFSET, NAME_LENGTH)
        val prefix = field(header, PREFIX_OFFSET, PREFIX_LENGTH)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun isRegular(header: ByteArray): Boolean {
        val flag = header[TYPE_OFFSET].toInt().toChar()
        return flag == '0' || flag == '\u0000'
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.US_ASCII)
    }

    private fun relativePath(name: String, into: File): String {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith("/") || WINDOWS_ABSOLUTE.containsMatchIn(normalized)) {
            throw IOException("refused an archive entry with an absolute path: $name")
        }
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) {
            throw IOException("refused an archive entry that climbs out of the package: $name")
        }
        val stripped = if (segments.firstOrNull() == ROOT_SEGMENT) segments.drop(1) else segments
        if (stripped.isEmpty()) throw IOException("refused an archive entry with no file name: $name")
        val relative = stripped.joinToString("/")
        val root = into.canonicalFile
        val target = File(into, relative).canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw IOException("refused an archive entry that escapes the destination: $name")
        }
        return relative
    }

    private fun copy(input: InputStream, out: FileOutputStream, size: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var remaining = size
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("the archive ends in the middle of a file")
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skip(input: InputStream, count: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var remaining = count
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("the archive ends in the middle of an entry")
            remaining -= read
        }
    }
}
