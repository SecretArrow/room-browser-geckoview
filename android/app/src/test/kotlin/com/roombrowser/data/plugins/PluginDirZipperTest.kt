package com.roombrowser.data.plugins

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The zipper, proven THROUGH the installer it feeds: every archive this
 * class writes — [PluginDirZipper.compose]'s two-entry package and
 * [PluginDirZipper.zipDir]'s walk of a real directory — is read back with
 * [ZipFile] for its deterministic layout (sorted, fixed date, DEFLATE, no
 * directory entries) and then pushed through the REAL [PluginZipInstaller],
 * because "the installer accepts it byte-for-behavior" is the only
 * round-trip guarantee that matters: an edit's save and an export ride the
 * same door a sideloaded ZIP enters, and these tests hold that door shut.
 *
 * The refusals are the walk's own: a planted symlink (file and directory
 * shapes), a forbidden-suffix file, the entry and size caps, and an override
 * that names no file — each with its exact sentence, each before any byte
 * of archive is written.
 */
class PluginDirZipperTest {

    private val store: File = Files.createTempDirectory("plugins").toFile().also { it.deleteOnExit() }
    private val installer = PluginZipInstaller(store)

    // ------------------------------------------------------------ compose

    @Test
    fun `compose is deterministic - two calls are byte-equal`() {
        val files = listOf(
            "plugin.json" to MANIFEST.toByteArray(),
            "main.js" to "exports.activate = async () => {}".toByteArray()
        )
        assertThat(PluginDirZipper.compose(files)).isEqualTo(PluginDirZipper.compose(files))
    }

    @Test
    fun `compose writes the sorted fixed-time deflate layout the installer reads`() {
        val bytes = PluginDirZipper.compose(
            listOf(
                // Deliberately unsorted input: the archive decides the order.
                "main.js" to "exports.activate = 1".toByteArray(),
                "plugin.json" to MANIFEST.toByteArray(),
                "lib/util.js" to "module.exports = 1".toByteArray()
            )
        )
        ZipFile(bytesToTempZip(bytes)).use { archive ->
            val entries = archive.entries().toList()
            assertThat(entries.map { it.name }).containsExactly(
                "lib/util.js", "main.js", "plugin.json"
            ).inOrder()
            entries.forEach { entry ->
                assertThat(entry.isDirectory).isFalse()
                assertThat(entry.method).isEqualTo(ZipEntry.DEFLATED)
                // ZipEntry's ms<->DOS conversions both run in the JVM's zone,
                // so the fixed date is asserted through the same round trip
                // the constant itself takes: what is pinned is the DOS date
                // 1980-01-01 00:00:00, not a zone-dependent instant.
                assertThat(entry.time).isEqualTo(
                    ZipEntry("probe").apply { time = PluginDirZipper.FIXED_TIME_MS }.time
                )
            }
        }
    }

    @Test
    fun `compose round-trips through the real installer`() {
        val bytes = PluginDirZipper.compose(
            listOf(
                "plugin.json" to MANIFEST.toByteArray(),
                "main.js" to "exports.activate = async () => {}".toByteArray()
            )
        )
        val ok = installer.install(ByteArrayInputStream(bytes)) as PluginInstallResult.ManifestOk
        assertThat(ok.manifest.id).isEqualTo("com.example.hello")
        assertThat(ok.manifest.name).isEqualTo("Hello World")
        assertThat(ok.manifest.version).isEqualTo("1.0.0")
        assertThat(ok.manifest.permissions).containsExactly("storage")
        assertThat(ok.manifest.entry).isEqualTo("main.js")
        assertThat(ok.dirName).isEqualTo("com_example_hello")
        assertThat(ok.zagents).isNull()
        val dir = File(store, "com_example_hello")
        assertThat(File(dir, "plugin.json").readText()).isEqualTo(MANIFEST)
        assertThat(File(dir, "main.js").readText()).isEqualTo("exports.activate = async () => {}")
        assertThat(store.listFiles()!!.map { it.name }).containsExactly("com_example_hello")
    }

    @Test
    fun `compose refuses a bad entry path loudly`() {
        // The installer's entry-name rules, one by one — a bug in the app's
        // own caller should be an exception, not a dialog.
        listOf("../evil.js", "/abs.js", "a/../../evil.js", "back\\slash.js").forEach { path ->
            val error = runCatching {
                PluginDirZipper.compose(listOf(path to "x".toByteArray()))
            }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(
            runCatching {
                PluginDirZipper.compose(listOf("native.node" to ByteArray(4)))
            }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
        val long = "x".repeat(201)
        assertThat(
            runCatching {
                PluginDirZipper.compose(listOf(long to ByteArray(1)))
            }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    // ------------------------------------------------------------ zipDir

    @Test
    fun `zipDir of a real dir produces an installer-acceptable zip`() {
        val dir = Files.createTempDirectory("export").toFile().also { it.deleteOnExit() }
        write(dir, "plugin.json", MANIFEST)
        write(dir, "main.js", "exports.activate = 1")
        write(dir, "lib/util.js", "module.exports = 1")

        val out = PluginDirZipper.zipDir(dir)
        val bytes = (out as PluginZipOut.Ok).bytes
        // Determinism: a second walk of an unmodified dir is byte-equal —
        // two exports of the same plugin diff clean.
        assertThat(PluginDirZipper.zipDir(dir)).isEqualTo(PluginZipOut.Ok(bytes))

        val ok = installer.install(ByteArrayInputStream(bytes)) as PluginInstallResult.ManifestOk
        assertThat(ok.manifest.id).isEqualTo("com.example.hello")
        assertThat(ok.dirName).isEqualTo("com_example_hello")
        val installed = File(store, "com_example_hello")
        assertThat(File(installed, "plugin.json").readText()).isEqualTo(MANIFEST)
        assertThat(File(installed, "main.js").readText()).isEqualTo("exports.activate = 1")
        assertThat(File(installed, "lib/util.js").readText()).isEqualTo("module.exports = 1")
    }

    @Test
    fun `zipDir of an installed dir reinstalls it identically`() {
        val first = installer.install(
            zipOf("plugin.json" to MANIFEST.toByteArray(), "main.js" to "v1".toByteArray())
        ) as PluginInstallResult.ManifestOk
        val dir = File(store, first.dirName)

        val out = PluginDirZipper.zipDir(dir)
        val ok = installer.install(ByteArrayInputStream((out as PluginZipOut.Ok).bytes))
        assertThat(ok).isInstanceOf(PluginInstallResult.ManifestOk::class.java)
        // The tree is exactly what the walk carried, and the store holds
        // nothing but the reinstalled plugin.
        assertThat(File(dir, "plugin.json").readText()).isEqualTo(MANIFEST)
        assertThat(File(dir, "main.js").readText()).isEqualTo("v1")
        assertThat(store.listFiles()!!.map { it.name }).containsExactly("com_example_hello")
    }

    @Test
    fun `zipDir substitutes an override and the reinstall carries it`() {
        installer.install(
            zipOf("plugin.json" to CONFIGURED.toByteArray(), "main.js" to "v1".toByteArray())
        )
        val dir = File(store, "com_example_hello")
        val edited = CONFIGURED.replace("\"default\":\"Hello\"", "\"default\":\"Howdy\"")

        val out = PluginDirZipper.zipDir(
            dir,
            override = mapOf(
                "plugin.json" to edited.toByteArray(),
                "main.js" to "v2".toByteArray()
            )
        )
        val ok = installer.install(ByteArrayInputStream((out as PluginZipOut.Ok).bytes))
            as PluginInstallResult.ManifestOk
        // The reinstalled file carries the edited bytes, and the row the
        // installer rebuilt reflects the edited manifest's config schema.
        assertThat(File(dir, "plugin.json").readText()).isEqualTo(edited)
        assertThat(File(dir, "main.js").readText()).isEqualTo("v2")
        assertThat(ok.manifest.configSchema.single().let { (it.default as JsonPrimitive).content })
            .isEqualTo("Howdy")
    }

    @Test
    fun `an override that names no file is refused`() {
        val dir = Files.createTempDirectory("override").toFile().also { it.deleteOnExit() }
        write(dir, "plugin.json", MANIFEST)
        write(dir, "main.js", "exports.activate = 1")

        val out = PluginDirZipper.zipDir(dir, override = mapOf("nope.js" to "x".toByteArray()))
        assertThat((out as PluginZipOut.Refused).errors).containsExactly(
            "the file \"nope.js\" is not part of this plugin"
        )
    }

    @Test
    fun `a symlink anywhere under the dir is refused with the exact sentence`() {
        val dir = Files.createTempDirectory("link").toFile().also { it.deleteOnExit() }
        write(dir, "plugin.json", MANIFEST)
        write(dir, "main.js", "exports.activate = 1")
        val created = runCatching {
            Files.createSymbolicLink(File(dir, "link.js").toPath(), File(dir, "main.js").toPath())
        }.isSuccess
        assumeTrue("symlinks are not creatable in this tmpdir", created)

        val out = PluginDirZipper.zipDir(dir)
        assertThat((out as PluginZipOut.Refused).errors).containsExactly(
            "the plugin directory contains a symlink (link.js), which export refuses to carry"
        )
    }

    @Test
    fun `a directory symlink is refused without being followed`() {
        // A link that closes a cycle must be refused BEFORE the recursion
        // decision — following it would walk forever.
        val dir = Files.createTempDirectory("loop").toFile().also { it.deleteOnExit() }
        write(dir, "plugin.json", MANIFEST)
        write(dir, "main.js", "exports.activate = 1")
        val created = runCatching {
            Files.createSymbolicLink(File(dir, "loop").toPath(), dir.toPath())
        }.isSuccess
        assumeTrue("symlinks are not creatable in this tmpdir", created)

        val out = PluginDirZipper.zipDir(dir)
        assertThat((out as PluginZipOut.Refused).errors).containsExactly(
            "the plugin directory contains a symlink (loop), which export refuses to carry"
        )
    }

    @Test
    fun `a forbidden-suffix file is refused with the exact sentence`() {
        val dir = Files.createTempDirectory("native").toFile().also { it.deleteOnExit() }
        write(dir, "plugin.json", MANIFEST)
        write(dir, "main.js", "exports.activate = 1")
        write(dir, "native.node", "native addon bytes")

        val out = PluginDirZipper.zipDir(dir)
        assertThat((out as PluginZipOut.Refused).errors).containsExactly(
            "the plugin directory contains \"native.node\", which a plugin may not carry"
        )
    }

    @Test
    fun `more than 512 entries are refused with the exact sentence`() {
        val dir = Files.createTempDirectory("many").toFile().also { it.deleteOnExit() }
        (1..513).forEach { index -> write(dir, "f$index.txt", "x") }
        assertThat(dir.listFiles()).hasLength(513)

        val out = PluginDirZipper.zipDir(dir)
        assertThat((out as PluginZipOut.Refused).errors).containsExactly(
            "the plugin directory has 513 entries; at most 512 are allowed"
        )
    }

    @Test
    fun `more than 20 MB is refused with the exact sentence`() {
        val dir = Files.createTempDirectory("big").toFile().also { it.deleteOnExit() }
        val chunk = ByteArray(1024 * 1024) // 21 x 1 MB > 20 MB
        (1..21).forEach { index -> File(dir, "m$index.bin").writeBytes(chunk) }

        val out = PluginDirZipper.zipDir(dir)
        assertThat((out as PluginZipOut.Refused).errors).containsExactly(
            "the plugin directory holds 21 MB; at most 20 MB are allowed"
        )
    }

    // ------------------------------------------------------------ names, editability

    @Test
    fun `exportFileName folds hostile characters and keeps spaces`() {
        assertThat(PluginDirZipper.exportFileName("Hello World", "1.0.0"))
            .isEqualTo("Hello World-1.0.0.zip")
        assertThat(PluginDirZipper.exportFileName("Greeter Module", "1.2-beta"))
            .isEqualTo("Greeter Module-1.2-beta.zip")
        assertThat(PluginDirZipper.exportFileName("a/b\\c:d*e?f\"g<h>i|j", "1.0.0"))
            .isEqualTo("a-b-c-d-e-f-g-h-i-j-1.0.0.zip")
    }

    @Test
    fun `isEditable holds the size extension and utf-8 rules`() {
        val dir = Files.createTempDirectory("editable").toFile().also { it.deleteOnExit() }

        val js = write(dir, "main.js", "exports.activate = 1")
        assertThat(PluginDirZipper.isEditable(js)).isTrue()

        val png = write(dir, "icon.png", "png")
        assertThat(PluginDirZipper.isEditable(png)).isFalse()

        val empty = write(dir, "notes.md", "")
        assertThat(PluginDirZipper.isEditable(empty)).isTrue()

        // The ceiling itself is editable; one byte past it is not.
        val atCap = File(dir, "cap.txt").apply { writeBytes(ByteArray(PluginDirZipper.EDITABLE_MAX_BYTES.toInt())) }
        assertThat(PluginDirZipper.isEditable(atCap)).isTrue()
        val pastCap = File(dir, "past.txt").apply {
            writeBytes(ByteArray(PluginDirZipper.EDITABLE_MAX_BYTES.toInt() + 1))
        }
        assertThat(PluginDirZipper.isEditable(pastCap)).isFalse()

        // Invalid UTF-8 stays binary: the strict round-trip fails, so the
        // editor never offers a field that would corrupt it on save.
        val binary = File(dir, "data.json").apply { writeBytes(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertThat(PluginDirZipper.isEditable(binary)).isFalse()
    }

    // ------------------------------------------------------------ fixtures

    private val MANIFEST =
        """{"id":"com.example.hello","name":"Hello World","version":"1.0.0",""" +
            """"description":"A tiny example.","api":1,"entry":"main.js","permissions":["storage"]}"""

    /** The same manifest carrying a config schema — the edit path's row test needs one. */
    private val CONFIGURED =
        """{"id":"com.example.hello","name":"Hello World","version":"1.0.0",""" +
            """"description":"A tiny example.","api":1,"entry":"main.js","permissions":["storage"],
            "config":{"schema":[{"key":"greeting","type":"string","default":"Hello",
            "title":"Greeting word","description":"Word to greet with"}]}}"""

    private fun write(dir: File, relPath: String, text: String): File {
        val target = File(dir, relPath)
        target.parentFile?.mkdirs()
        target.writeText(text)
        return target
    }

    /** A zip built with the installer test's own recipe — the sideloaded-ZIP side of the round trips. */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { stream ->
            entries.forEach { (name, bytes) ->
                stream.putNextEntry(ZipEntry(name))
                stream.write(bytes)
                stream.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    /**
     * [ZipFile] reads from a file, not a stream — the installer's own
     * land-first rule, in miniature: the composed bytes go to a temp file so
     * the layout assertions read the archive exactly as an installer would.
     */
    private fun bytesToTempZip(bytes: ByteArray): File {
        val zip = File.createTempFile("composed", ".zip", null)
        zip.deleteOnExit()
        zip.writeBytes(bytes)
        return zip
    }
}
