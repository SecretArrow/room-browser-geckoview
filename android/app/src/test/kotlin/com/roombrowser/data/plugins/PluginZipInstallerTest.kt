package com.roombrowser.data.plugins

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The install door, guard by guard: every refusal names the rule it refused,
 * and a package that passes leaves an extracted, validated plugin directory
 * and nothing else behind. All fixtures are built in-test with
 * [ByteArrayInputStream] zips — no Android, no filesystem outside a temp dir.
 *
 * The door takes BOTH manifest formats: the `plugin.json` fixtures above and
 * the `module.json` ones below, the latter run through the ZAgents mapping
 * (id `zagents.<name>`, permission table, entry fallback chain) with this
 * app's own tightening — network without an allowlist never installs.
 */
class PluginZipInstallerTest {

    private val store: File = Files.createTempDirectory("plugins").toFile().also { it.deleteOnExit() }
    private val installer = PluginZipInstaller(store)

    @Test
    fun `a valid package installs and leaves nothing but itself behind`() {
        val result = installer.install(
            zip(
                "plugin.json" to MANIFEST.toByteArray(),
                "main.js" to "exports.activate = async () => {}".toByteArray(),
                "lib/util.js" to "module.exports = 1".toByteArray()
            )
        )
        val ok = result as PluginInstallResult.ManifestOk
        assertThat(ok.manifest.id).isEqualTo("com.example.hello")
        assertThat(ok.manifest.permissions).containsExactly("storage")
        assertThat(ok.dirName).isEqualTo("com_example_hello")
        val dir = File(store, "com_example_hello")
        assertThat(File(dir, "plugin.json").exists()).isTrue()
        assertThat(File(dir, "main.js").exists()).isTrue()
        assertThat(File(dir, "lib/util.js").exists()).isTrue()
        assertThat(store.listFiles()!!.map { it.name }).containsExactly("com_example_hello")
    }

    @Test
    fun `a wrapped package is rebased on its manifest's folder`() {
        val result = installer.install(
            zip(
                "wrap/plugin.json" to MANIFEST.toByteArray(),
                "wrap/main.js" to "1".toByteArray(),
                "wrap/node_modules/dep/index.js" to "2".toByteArray()
            )
        )
        val ok = result as PluginInstallResult.ManifestOk
        val dir = File(store, ok.dirName)
        assertThat(File(dir, "plugin.json").exists()).isTrue()
        assertThat(File(dir, "main.js").exists()).isTrue()
        assertThat(File(dir, "node_modules/dep/index.js").exists()).isTrue()
        assertThat(File(dir, "wrap").exists()).isFalse()
    }

    @Test
    fun `two top-level folders are not a package`() {
        val result = installer.install(
            zip(
                "one/plugin.json" to MANIFEST.toByteArray(),
                "two/main.js" to "1".toByteArray()
            )
        )
        assertThat((result as PluginInstallResult.Invalid).errors.single())
            .contains("plugin.json must be at the archive root")
    }

    @Test
    fun `an entry that leaves the package directory is refused`() {
        listOf("../evil.js", "/abs.js", "a/../../evil.js", "back\\slash.js").forEach { name ->
            val result = installer.install(zip(name to "x".toByteArray()))
            assertThat((result as PluginInstallResult.Invalid).errors).isNotEmpty()
        }
        val traversal = installer.install(
            zip(
                "plugin.json" to MANIFEST.toByteArray(),
                "../evil.js" to "x".toByteArray()
            )
        ) as PluginInstallResult.Invalid
        assertThat(traversal.errors.single()).contains("leaves the package directory")
        assertThat(store.listFiles()).isEmpty()
    }

    @Test
    fun `a bomb by entry count is refused before anything is extracted`() {
        val many = (1..513).map { "f$it.txt" to "x".toByteArray() }
        val result = installer.install(zip(*many.toTypedArray()))
        assertThat((result as PluginInstallResult.Invalid).errors.single())
            .contains("at most 512")
        assertThat(store.listFiles()).isEmpty()
    }

    @Test
    fun `a bomb by total size is refused by the declared sizes`() {
        val chunk = ByteArray((1.9 * 1024 * 1024).toInt()) // 11 x 1.9 MB > 20 MB
        val big = (1..11).map { "f$it.bin" to chunk }
        val result = installer.install(zip(*big.toTypedArray()))
        assertThat((result as PluginInstallResult.Invalid).errors.single())
            .contains("add up to")
    }

    @Test
    fun `a single oversized entry is refused by its own size`() {
        val huge = ByteArray(2 * 1024 * 1024 + 1)
        val result = installer.install(zip("big.bin" to huge))
        assertThat((result as PluginInstallResult.Invalid).errors.single())
            .contains("larger than 2 MB")
    }

    @Test
    fun `a native addon is refused by extension`() {
        val result = installer.install(
            zip(
                "plugin.json" to MANIFEST.toByteArray(),
                "native.node" to ByteArray(4)
            )
        )
        assertThat((result as PluginInstallResult.Invalid).errors.single())
            .contains("native executable")
        // The rest of the forbidden set, one line each.
        listOf("x.so", "x.dll", "x.exe", "x.bat", "x.sh", "x.jar", "x.apk").forEach { name ->
            assertThat(
                (installer.install(zip(name to ByteArray(4))) as PluginInstallResult.Invalid).errors
            ).isNotEmpty()
        }
    }

    @Test
    fun `a link-shaped entry unpacks as an inert plain file`() {
        // java.util.zip cannot WRITE a symlink entry (no external attributes
        // on its ZipEntry), so one cannot arrive in a stream this JVM made —
        // and the extractor writes plain files regardless, so a "link" whose
        // bytes are a target path lands as text on disk. The install succeeds
        // and the file reads back as that text: inert, and the post-extract
        // walk would catch anything that somehow materialized otherwise.
        val result = installer.install(
            zip(
                "plugin.json" to MANIFEST.toByteArray(),
                "main.js" to "exports.activate = async () => {}".toByteArray(),
                "link" to "../../etc/passwd".toByteArray()
            )
        )
        assertThat(result as PluginInstallResult.ManifestOk).isNotNull()
        val link = File(File(store, "com_example_hello"), "link")
        assertThat(link.exists()).isTrue()
        assertThat(link.readText()).isEqualTo("../../etc/passwd")
    }

    @Test
    fun `an honest directory entry with no attributes is fine`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { stream ->
            stream.putNextEntry(ZipEntry("plugin.json"))
            stream.write(MANIFEST.toByteArray())
            stream.closeEntry()
            stream.putNextEntry(ZipEntry("main.js"))
            stream.write("1".toByteArray())
            stream.closeEntry()
            stream.putNextEntry(ZipEntry("lib/"))
            stream.closeEntry()
        }
        val result = installer.install(ByteArrayInputStream(out.toByteArray()))
        assertThat(result).isInstanceOf(PluginInstallResult.ManifestOk::class.java)
    }

    @Test
    fun `a manifest the validator refuses arrives with its errors`() {
        val bad = """{"id":"com.example.hello","name":"Hello","version":"1.0","api":2,"entry":"main.js","extra":1}"""
        val result = installer.install(
            zip("plugin.json" to bad.toByteArray(), "main.js" to "1".toByteArray())
        )
        val errors = (result as PluginInstallResult.Invalid).errors
        assertThat(errors.any { it.contains("API version 1") }).isTrue()
        assertThat(errors.any { it.contains("unknown field") }).isTrue()
        assertThat(errors.any { it.contains("version") }).isTrue()
    }

    @Test
    fun `a manifest whose entry file is missing says so`() {
        val result = installer.install(zip("plugin.json" to MANIFEST.toByteArray()))
        assertThat((result as PluginInstallResult.Invalid).errors.single())
            .contains("entry file \"main.js\" is missing")
    }

    @Test
    fun `a stream that is not a zip is refused, not thrown`() {
        val result = installer.install(ByteArrayInputStream("not a zip".toByteArray()))
        assertThat(result).isInstanceOf(PluginInstallResult.Invalid::class.java)
        assertThat((result as PluginInstallResult.Invalid).errors.single()).contains("could not be read")
    }

    @Test
    fun `reinstalling replaces the code directory`() {
        installer.install(zip("plugin.json" to MANIFEST.toByteArray(), "main.js" to "v1".toByteArray()))
        installer.install(zip("plugin.json" to MANIFEST.toByteArray(), "main.js" to "v2".toByteArray()))
        assertThat(File(File(store, "com_example_hello"), "main.js").readText()).isEqualTo("v2")
        assertThat(store.listFiles()!!.map { it.name }).containsExactly("com_example_hello")
    }

    // ------------------------------------------------------------ module.json

    @Test
    fun `a module json installs as a zagents row`() {
        val result = installer.install(
            zip(
                "module.json" to MODULE.toByteArray(),
                "main.js" to "module.exports = main()".toByteArray(),
                "lib/util.js" to "module.exports = 1".toByteArray()
            )
        )
        val ok = result as PluginInstallResult.ManifestOk
        // The module-only columns the row carries.
        val zagents = ok.zagents!!
        assertThat(zagents.name).isEqualTo("daily")
        assertThat(zagents.version).isEqualTo("1.2.3.4")
        assertThat(zagents.entry).isEqualTo("main.js")
        assertThat(zagents.timeoutSeconds).isEqualTo(45)
        assertThat(zagents.inputsJson).isNull()
        // …and the mapped manifest: the same columns every plugin row has.
        assertThat(ok.manifest.id).isEqualTo("zagents.daily")
        assertThat(ok.manifest.name).isEqualTo("Daily Digest")
        assertThat(ok.manifest.version).isEqualTo("1.2.3")
        assertThat(ok.manifest.entry).isEqualTo("main.js")
        assertThat(ok.manifest.permissions)
            .containsExactly("storage", "network", "chat.context", "agent.generate")
            .inOrder()
        assertThat(ok.manifest.networkAllow).containsExactly("api.onhomeroom.com")
        assertThat(ok.dirName).isEqualTo("zagents_daily")
        val dir = File(store, "zagents_daily")
        assertThat(File(dir, "module.json").exists()).isTrue()
        assertThat(File(dir, "main.js").exists()).isTrue()
        assertThat(File(dir, "lib/util.js").exists()).isTrue()
        assertThat(store.listFiles()!!.map { it.name }).containsExactly("zagents_daily")
    }

    @Test
    fun `a wrapped module folder is rebased on its manifest's folder`() {
        val result = installer.install(
            zip(
                "counter/module.json" to """{"name":"counter"}""".toByteArray(),
                "counter/main.js" to "1".toByteArray()
            )
        )
        val ok = result as PluginInstallResult.ManifestOk
        assertThat(ok.manifest.id).isEqualTo("zagents.counter")
        // A titleless module is known by its machine name; defaults fill in.
        assertThat(ok.manifest.name).isEqualTo("counter")
        assertThat(ok.manifest.version).isEqualTo("0.0.0")
        assertThat(ok.zagents!!.timeoutSeconds).isEqualTo(30)
        val dir = File(store, ok.dirName)
        assertThat(File(dir, "module.json").exists()).isTrue()
        assertThat(File(dir, "main.js").exists()).isTrue()
        assertThat(File(dir, "counter").exists()).isFalse()
    }

    @Test
    fun `a package carrying both manifests is refused outright`() {
        val result = installer.install(
            zip(
                "plugin.json" to MANIFEST.toByteArray(),
                "module.json" to MODULE.toByteArray(),
                "main.js" to "1".toByteArray()
            )
        )
        assertThat((result as PluginInstallResult.Invalid).errors.single()).isEqualTo(
            "plugin.json and module.json cannot both be present — pick one format"
        )
        assertThat(store.listFiles()).isEmpty()
        // The wrapped spelling of the same mistake refuses identically.
        val wrapped = installer.install(
            zip(
                "wrap/plugin.json" to MANIFEST.toByteArray(),
                "wrap/module.json" to MODULE.toByteArray(),
                "wrap/main.js" to "1".toByteArray()
            )
        )
        assertThat((wrapped as PluginInstallResult.Invalid).errors.single()).isEqualTo(
            "plugin.json and module.json cannot both be present — pick one format"
        )
    }

    @Test
    fun `a module whose resolved entry is missing names the resolved entry`() {
        val result = installer.install(zip("module.json" to MODULE.toByteArray()))
        assertThat((result as PluginInstallResult.Invalid).errors.single())
            .isEqualTo("Entry point \"main.js\" not found in the ZIP")

        // The fallback chain resolved through package.json's main — the
        // message names where the chain landed, not the manifest's absence.
        val viaPackage = installer.install(
            zip(
                "module.json" to """{"name":"m"}""".toByteArray(),
                "package.json" to """{"name":"m","main":"lib/index.js"}""".toByteArray()
            )
        )
        assertThat((viaPackage as PluginInstallResult.Invalid).errors.single())
            .isEqualTo("Entry point \"lib/index.js\" not found in the ZIP")
    }

    @Test
    fun `a module entry resolves through package main when the manifest is silent`() {
        val result = installer.install(
            zip(
                "module.json" to """{"name":"m","title":"M"}""".toByteArray(),
                "package.json" to """{"name":"m","main":"lib/start.js"}""".toByteArray(),
                "lib/start.js" to "module.exports = 1".toByteArray()
            )
        )
        val ok = result as PluginInstallResult.ManifestOk
        assertThat(ok.zagents!!.entry).isEqualTo("lib/start.js")
        assertThat(ok.manifest.entry).isEqualTo("lib/start.js")
        assertThat(File(File(store, ok.dirName), "lib/start.js").exists()).isTrue()
    }

    @Test
    fun `a module declaring network without an allowlist is refused with the design's message`() {
        // The package is otherwise complete — the allowlist is the ONE thing
        // wrong with it, and the one refusal it earns.
        listOf(
            """{"name":"m","permissions":["network"]}""",
            """{"name":"m","permissions":["network"],"allowedHosts":[]}""",
            """{"name":"m","permissions":["network","storage"],"allowedHosts":["  "]}"""
        ).forEach { body ->
            val result = installer.install(
                zip("module.json" to body.toByteArray(), "main.js" to "1".toByteArray())
            )
            assertThat((result as PluginInstallResult.Invalid).errors.single()).isEqualTo(
                "module.json declares the network permission but no allowedHosts allowlist — " +
                    "Room Browser requires an explicit host allowlist; " +
                    "add \"allowedHosts\": [...] to module.json"
            )
        }
        // The allowlist present, the package complete: no refusal at all.
        assertThat(
            installer.install(
                zip(
                    "module.json" to
                        """{"name":"m","permissions":["network"],"allowedHosts":["api.example.com"]}"""
                            .toByteArray(),
                    "main.js" to "1".toByteArray()
                )
            )
        ).isInstanceOf(PluginInstallResult.ManifestOk::class.java)
    }

    @Test
    fun `a module whose inputs schema is broken has the schema's errors relayed`() {
        val result = installer.install(
            zip(
                "module.json" to
                    """{"name":"m","inputs":[{"key":"E","type":"string","title":"E"}]}"""
                        .toByteArray(),
                "main.js" to "1".toByteArray()
            )
        )
        val errors = (result as PluginInstallResult.Invalid).errors
        assertThat(errors).hasSize(1)
        assertThat(errors.single()).contains("inputs")
    }

    @Test
    fun `a module with an inputs schema carries it onto the install`() {
        val result = installer.install(
            zip(
                "module.json" to
                    """{"name":"m","inputs":[{"key":"email","type":"string","title":"Email",
                        "required":true,"pattern":"^[^@]+@[^@]+$","patternError":"Enter a valid email",
                        "minLength":3,"maxLength":200}]}"""
                        .toByteArray(),
                "main.js" to "1".toByteArray()
            )
        )
        val ok = result as PluginInstallResult.ManifestOk
        assertThat(ok.zagents!!.inputsJson).isNotNull()
        assertThat(ok.zagents.inputsJson).contains("\"key\":\"email\"")
        assertThat(ok.zagents.inputsJson).contains("\"type\":\"string\"")
    }

    @Test
    fun `reinstalling a module replaces its code directory`() {
        installer.install(
            zip("module.json" to MODULE.toByteArray(), "main.js" to "v1".toByteArray())
        )
        installer.install(
            zip("module.json" to MODULE.toByteArray(), "main.js" to "v2".toByteArray())
        )
        assertThat(File(File(store, "zagents_daily"), "main.js").readText()).isEqualTo("v2")
        assertThat(store.listFiles()!!.map { it.name }).containsExactly("zagents_daily")
    }

    // ------------------------------------------------------------ fixtures

    private val MANIFEST =
        """{"id":"com.example.hello","name":"Hello World","version":"1.0.0",""" +
            """"description":"A tiny example.","api":1,"entry":"main.js","permissions":["storage"]}"""

    /** A module.json shaped like the ZAgents samples: name, hosts, timeout. */
    private val MODULE =
        """{"name":"daily","version":"1.2.3.4","title":"Daily Digest",""" +
            """"description":"Composes a daily summary.","entry":"main.js",
            "permissions":["storage","network","chat","agent"],
            "allowedHosts":["api.onhomeroom.com"],"timeoutSeconds":45}"""

    private fun zip(vararg entries: Pair<String, ByteArray?>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { stream ->
            entries.forEach { (name, bytes) ->
                stream.putNextEntry(ZipEntry(name))
                bytes?.let { stream.write(it) }
                stream.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    /** A zip built from a prepared entry, so its attributes are the test's own. */
    private fun zip(entry: ZipEntry, bytes: ByteArray?): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { stream ->
            stream.putNextEntry(entry)
            bytes?.let { stream.write(it) }
            stream.closeEntry()
        }
        return ByteArrayInputStream(out.toByteArray())
    }
}
