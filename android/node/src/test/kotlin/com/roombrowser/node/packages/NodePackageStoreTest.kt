package com.roombrowser.node.packages

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

class NodePackageStoreTest {

    private val nodeDir: File = Files.createTempDirectory("node-packages").toFile()
    private val registry = FakeRegistry()
    private val store = NodePackageStore(nodeDir, registry)

    @Test
    fun install_writes_the_package_tree() {
        registry.add("left-pad", "1.3.0")
        val report = store.install("left-pad", null)
        assertThat(report.ok).isTrue()
        assertThat(report.version).isEqualTo("1.3.0")
        assertThat(report.installed.map { it.name }).containsExactly("left-pad")
        assertThat(File(nodeDir, "node_modules/left-pad/index.js").isFile).isTrue()
        assertThat(File(nodeDir, "node_modules/left-pad/package.json").isFile).isTrue()
    }

    @Test
    fun a_dependency_lands_under_its_parent() {
        registry.add("parent", "1.0.0", dependencies = mapOf("child" to "^2.0.0"))
        registry.add("child", "2.1.0")
        val report = store.install("parent", "1.0.0")
        assertThat(report.ok).isTrue()
        assertThat(File(nodeDir, "node_modules/parent/node_modules/child/index.js").isFile).isTrue()
        assertThat(File(nodeDir, "node_modules/child").exists()).isFalse()
        assertThat(report.installed.map { it.name }).containsExactly("parent", "child").inOrder()
    }

    @Test
    fun a_range_selects_the_highest_published_version() {
        registry.add("multi", "1.0.0")
        registry.add("multi", "1.4.2")
        registry.add("multi", "2.0.0")
        assertThat(store.install("multi", "^1.0.0").version).isEqualTo("1.4.2")
        assertThat(store.install("multi", "^3.0.0").ok).isFalse()
    }

    @Test
    fun a_tarball_that_does_not_match_its_integrity_leaves_nothing_behind() {
        registry.add("tampered", "1.0.0", corrupt = true)
        val report = store.install("tampered", null)
        assertThat(report.ok).isFalse()
        assertThat(report.error).contains("integrity")
        assertThat(File(nodeDir, "node_modules").exists()).isFalse()
        assertThat(nodeDir.list().orEmpty().filter { it.startsWith(".install-") }).isEmpty()
    }

    @Test
    fun a_name_that_could_be_a_path_is_refused() {
        for (name in listOf("../evil", "a/b", "@scope", "UPPER", "..", ".hidden")) {
            assertThat(store.install(name, null).ok).isFalse()
        }
        assertThat(store.remove("../evil")).isFalse()
        assertThat(File(nodeDir.parentFile, "evil").exists()).isFalse()
    }

    @Test
    fun a_scoped_package_installs_under_its_scope() {
        registry.add("@scope/pkg", "1.0.0")
        val report = store.install("@scope/pkg", null)
        assertThat(report.ok).isTrue()
        assertThat(File(nodeDir, "node_modules/@scope/pkg/package.json").isFile).isTrue()
        assertThat(store.installed().map { it.name }).containsExactly("@scope/pkg")
    }

    @Test
    fun remove_deletes_only_that_package() {
        registry.add("keep", "1.0.0")
        registry.add("drop", "1.0.0")
        store.install("keep", null)
        store.install("drop", null)
        assertThat(store.remove("drop")).isTrue()
        assertThat(File(nodeDir, "node_modules/drop").exists()).isFalse()
        assertThat(File(nodeDir, "node_modules/keep/package.json").isFile).isTrue()
        assertThat(store.installed().map { it.name }).containsExactly("keep")
        assertThat(store.remove("drop")).isFalse()
    }

    @Test
    fun installed_survives_a_manifest_it_cannot_read() {
        registry.add("good", "1.2.3")
        store.install("good", null)
        File(nodeDir, "node_modules/broken").mkdirs()
        File(nodeDir, "node_modules/broken/package.json").writeText("{ this is not json")
        File(nodeDir, "node_modules/missing").mkdirs()
        val found = store.installed()
        assertThat(found.map { it.name }).containsExactly("broken", "good", "missing")
        assertThat(found.first { it.name == "broken" }.version).isNull()
        assertThat(found.first { it.name == "missing" }.version).isNull()
        assertThat(found.first { it.name == "good" }.version).isEqualTo("1.2.3")
    }

    @Test
    fun dev_and_optional_dependencies_are_reported_as_skipped() {
        registry.add(
            "pkg",
            "1.0.0",
            devDependencies = mapOf("jest" to "^29.0.0"),
            optionalDependencies = mapOf("fsevents" to "^2.0.0")
        )
        val report = store.install("pkg", null)
        assertThat(report.ok).isTrue()
        assertThat(report.skipped).hasSize(1)
        assertThat(report.skipped.first()).contains("devDependencies")
        assertThat(report.skipped.first()).contains("optionalDependencies")
        assertThat(File(nodeDir, "node_modules/pkg/node_modules").exists()).isFalse()
    }

    @Test
    fun a_dependency_loop_is_stopped_at_the_depth_cap() {
        registry.add("loop", "1.0.0", dependencies = mapOf("loop" to "^1.0.0"))
        val report = store.install("loop", null)
        assertThat(report.ok).isFalse()
        assertThat(report.error).contains("levels")
        assertThat(File(nodeDir, "node_modules/loop").exists()).isFalse()
    }

    @Test
    fun a_registry_document_parses_into_versions_and_dist_tags() {
        val packument = PackumentJson.parse(
            "left-pad",
            """
            {
              "dist-tags": {"latest": "1.3.0", "next": "2.0.0-rc.1"},
              "versions": {
                "1.3.0": {
                  "version": "1.3.0",
                  "dependencies": {"dep": "^1.0.0"},
                  "dist": {
                    "tarball": "https://registry.npmjs.org/left-pad/-/left-pad-1.3.0.tgz",
                    "integrity": "sha512-abc"
                  }
                },
                "2.0.0-rc.1": {
                  "version": "2.0.0-rc.1",
                  "dist": {
                    "tarball": "https://registry.npmjs.org/left-pad/-/left-pad-2.0.0-rc.1.tgz",
                    "shasum": "deadbeef"
                  }
                }
              }
            }
            """.trimIndent()
        )
        assertThat(packument.name).isEqualTo("left-pad")
        assertThat(packument.distTags["latest"]).isEqualTo("1.3.0")
        assertThat(packument.distTags["next"]).isEqualTo("2.0.0-rc.1")
        assertThat(packument.versions.map { it.version }).containsExactly("1.3.0", "2.0.0-rc.1")
        assertThat(packument.versions.first().dependencies).containsEntry("dep", "^1.0.0")
        assertThat(packument.versions.first().integrity).isEqualTo("sha512-abc")
        assertThat(packument.versions.last().shasum).isEqualTo("deadbeef")
    }
}

private class FakeRegistry : Registry {

    private val packuments = mutableMapOf<String, Packument>()
    private val tarballs = mutableMapOf<String, ByteArray>()

    fun add(
        name: String,
        version: String,
        dependencies: Map<String, String> = emptyMap(),
        devDependencies: Map<String, String> = emptyMap(),
        optionalDependencies: Map<String, String> = emptyMap(),
        files: Map<String, String> = mapOf("index.js" to "module.exports = 1;"),
        corrupt: Boolean = false
    ) {
        val url = "https://registry.npmjs.org/$name/-/$name-$version.tgz"
        val bytes = npmTarball(name, version, dependencies, devDependencies, optionalDependencies, files)
        tarballs[url] = bytes
        val record = VersionRecord(
            version = version,
            dependencies = dependencies,
            tarballUrl = url,
            integrity = sha512(if (corrupt) "$name-$version-tampered".toByteArray() else bytes)
        )
        val existing = packuments[name]
        packuments[name] = Packument(
            name = name,
            distTags = mapOf("latest" to version),
            versions = existing?.versions.orEmpty() + record
        )
    }

    override fun packument(name: String): Packument =
        packuments[name] ?: throw IOException("the fake registry has no $name")

    override fun tarball(url: String): ByteArray =
        tarballs[url] ?: throw IOException("the fake registry has no tarball at $url")
}

private fun npmTarball(
    name: String,
    version: String,
    dependencies: Map<String, String>,
    devDependencies: Map<String, String>,
    optionalDependencies: Map<String, String>,
    files: Map<String, String>
): ByteArray {
    val manifest = manifestJson(name, version, dependencies, devDependencies, optionalDependencies)
    val entries = mutableListOf(tarEntry("package/package.json", manifest.toByteArray()))
    for ((path, content) in files) {
        entries += tarEntry("package/$path", content.toByteArray())
    }
    return gzipBytes(tarArchive(*entries.toTypedArray()))
}

private fun manifestJson(
    name: String,
    version: String,
    dependencies: Map<String, String>,
    devDependencies: Map<String, String>,
    optionalDependencies: Map<String, String>
): String {
    val blocks = mutableListOf(jsonBlock("dependencies", dependencies))
    if (devDependencies.isNotEmpty()) blocks += jsonBlock("devDependencies", devDependencies)
    if (optionalDependencies.isNotEmpty()) blocks += jsonBlock("optionalDependencies", optionalDependencies)
    return "{\"name\": \"$name\", \"version\": \"$version\", " + blocks.joinToString(", ") + "}"
}

private fun jsonBlock(label: String, entries: Map<String, String>): String {
    val pairs = entries.entries.joinToString(", ") { "\"${it.key}\": \"${it.value}\"" }
    return "\"$label\": {$pairs}"
}

private fun sha512(bytes: ByteArray): String =
    "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes))
