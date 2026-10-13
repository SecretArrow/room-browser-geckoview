package com.roombrowser.node.packages

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class TarGzTest {

    @Test
    fun a_regular_entry_is_written_and_a_directory_entry_is_not() {
        val into = Files.createTempDirectory("targz").toFile()
        val written = TarGz.extract(
            gzipBytes(
                tarArchive(
                    tarEntry("package/", ByteArray(0), type = '5'),
                    tarEntry("package/index.js", "module.exports = 1".toByteArray())
                )
            ),
            into
        )
        assertThat(written).containsExactly("index.js")
        assertThat(File(into, "index.js").readText()).isEqualTo("module.exports = 1")
    }

    @Test
    fun the_top_level_package_directory_is_stripped() {
        val into = Files.createTempDirectory("targz").toFile()
        val written = TarGz.extract(
            gzipBytes(tarArchive(tarEntry("package/lib/deep.js", "x".toByteArray()))),
            into
        )
        assertThat(written).containsExactly("lib/deep.js")
        assertThat(File(into, "lib/deep.js").isFile).isTrue()
    }

    @Test
    fun the_prefix_field_is_joined_onto_the_name() {
        val into = Files.createTempDirectory("targz").toFile()
        val written = TarGz.extract(
            gzipBytes(tarArchive(tarEntry("index.js", "x".toByteArray(), prefix = "package/lib"))),
            into
        )
        assertThat(written).containsExactly("lib/index.js")
        assertThat(File(into, "lib/index.js").isFile).isTrue()
    }

    @Test
    fun a_path_that_climbs_out_of_the_destination_is_refused() {
        val into = Files.createTempDirectory("targz").toFile()
        val thrown = assertThrows(IOException::class.java) {
            TarGz.extract(
                gzipBytes(tarArchive(tarEntry("package/../../escaped.txt", "nope".toByteArray()))),
                into
            )
        }
        assertThat(thrown.message).contains("..")
        assertThat(File(into.parentFile, "escaped.txt").exists()).isFalse()
        assertThat(into.list().orEmpty()).isEmpty()
    }

    @Test
    fun an_absolute_path_is_refused() {
        val into = Files.createTempDirectory("targz").toFile()
        val thrown = assertThrows(IOException::class.java) {
            TarGz.extract(gzipBytes(tarArchive(tarEntry("/etc/passwd", "nope".toByteArray()))), into)
        }
        assertThat(thrown.message).contains("absolute")
        assertThat(into.list().orEmpty()).isEmpty()
    }

    @Test
    fun a_symlink_is_skipped_without_failing_the_archive() {
        val into = Files.createTempDirectory("targz").toFile()
        val written = TarGz.extract(
            gzipBytes(
                tarArchive(
                    tarEntry("package/link", "../../etc/passwd".toByteArray(), type = '2'),
                    tarEntry("package/real.js", "ok".toByteArray())
                )
            ),
            into
        )
        assertThat(written).containsExactly("real.js")
        assertThat(File(into, "link").exists()).isFalse()
    }

    @Test
    fun an_archive_that_stops_in_the_middle_of_a_file_is_refused() {
        val into = Files.createTempDirectory("targz").toFile()
        val archive = tarArchive(tarEntry("package/big.js", ByteArray(1000) { 'a'.code.toByte() }))
        val truncated = gzipBytes(archive.copyOf(512 + 200))
        assertThrows(IOException::class.java) { TarGz.extract(truncated, into) }
    }

    @Test
    fun an_archive_that_stops_in_the_middle_of_a_header_is_refused() {
        val into = Files.createTempDirectory("targz").toFile()
        val archive = tarArchive(tarEntry("package/empty.js", ByteArray(0)))
        val truncated = gzipBytes(archive.copyOf(512 + 100))
        assertThrows(IOException::class.java) { TarGz.extract(truncated, into) }
    }

    @Test
    fun an_archive_that_declares_too_much_is_refused_before_it_is_read() {
        val into = Files.createTempDirectory("targz").toFile()
        // The declared size alone trips the bound, so no payload is needed.
        val header = tarHeader("package/huge.bin", TarGz.MAX_TOTAL_BYTES + 1)
        assertThrows(IOException::class.java) { TarGz.extract(gzipBytes(header), into) }
        assertThat(into.list().orEmpty()).isEmpty()
    }
}

internal fun tarHeader(name: String, size: Long, type: Char = '0', prefix: String = ""): ByteArray {
    val block = ByteArray(512)
    name.toByteArray(Charsets.US_ASCII).copyInto(block, 0, 0, minOf(name.length, 100))
    prefix.toByteArray(Charsets.US_ASCII).copyInto(block, 345, 0, minOf(prefix.length, 155))
    val octal = size.toString(8).padStart(11, '0') + "\u0000"
    octal.toByteArray(Charsets.US_ASCII).copyInto(block, 124)
    block[156] = type.code.toByte()
    return block
}

internal fun tarEntry(
    name: String,
    content: ByteArray,
    type: Char = '0',
    prefix: String = ""
): ByteArray {
    val padded = ByteArray((content.size + 511) / 512 * 512)
    content.copyInto(padded)
    return tarHeader(name, content.size.toLong(), type, prefix) + padded
}

internal fun tarArchive(vararg entries: ByteArray): ByteArray =
    entries.fold(ByteArray(0)) { archive, entry -> archive + entry } + ByteArray(1024)

internal fun gzipBytes(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}
