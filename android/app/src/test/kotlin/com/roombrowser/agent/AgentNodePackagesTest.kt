package com.roombrowser.agent

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.UntrustedContent
import com.roombrowser.node.packages.Packument
import com.roombrowser.node.packages.Registry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The app-side seam for `node_packages`, driven against a registry that always
 * fails and a package tree placed by hand.
 *
 * A successful install — the tarball fetched, verified and unpacked — is
 * [com.roombrowser.node.packages.NodePackageStoreTest]'s subject, with its own
 * fake registry; what matters HERE is that a registry failure and a bad name
 * arrive as readable sentences, that listing and removing touch only the
 * directory, and that everything a package supplies is fenced as data.
 *
 * Every test ends on a VOID-returning Truth call: a Kotlin function whose last
 * expression has a value compiles to a non-void method, and JUnit4 then refuses
 * the whole class. `runBlocking<Unit>` is the other half of that rule.
 */
class AgentNodePackagesTest {

    private val root: File = Files.createTempDirectory("agent-node-packages").toFile()
    private val nodeDir: File = File(root, "node")
    private val context: Context = mockk<Context>().apply { every { filesDir } returns root }
    private val registry = UnreachableRegistry()

    private fun packages() = AgentNodePackages(context, registry)

    /** Places a package the way an install would, so the directory reads back. */
    private fun installed(name: String, version: String) {
        val directory = File(nodeDir, "node_modules/$name")
        directory.mkdirs()
        File(directory, "package.json").writeText("""{"name":"$name","version":"$version"}""")
    }

    @Test
    fun `list reports an empty tree without starting the runtime`() = runBlocking<Unit> {
        val result = packages().list()
        assertThat(result.ok).isTrue()
        assertThat(result.output).contains("No npm packages are installed")
    }

    @Test
    fun `list names what is on disk and fences it as data`() = runBlocking<Unit> {
        installed("lodash", "4.17.21")
        val result = packages().list()
        assertThat(result.ok).isTrue()
        assertThat(result.output).contains("lodash@4.17.21")
        assertThat(result.output).contains(UntrustedContent.BEGIN)
        assertThat(result.output).contains(UntrustedContent.END)
    }

    @Test
    fun `remove deletes one package`() = runBlocking<Unit> {
        installed("lodash", "4.17.21")
        val result = packages().remove("lodash")
        assertThat(result.ok).isTrue()
        assertThat(File(nodeDir, "node_modules/lodash").exists()).isFalse()
    }

    @Test
    fun `removing what is not installed is a readable failure`() = runBlocking<Unit> {
        val result = packages().remove("lodash")
        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("lodash")
    }

    @Test
    fun `a registry failure is a sentence rather than a crash, and writes nothing`() = runBlocking<Unit> {
        val result = packages().install("lodash", null)
        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("lodash")
        assertThat(File(nodeDir, "node_modules").exists()).isFalse()
    }

    @Test
    fun `an unusable package name never reaches the registry`() = runBlocking<Unit> {
        val result = packages().install("../evil", null)
        assertThat(result.ok).isFalse()
        assertThat(registry.calls).isEqualTo(0)
    }
}

/** Fails every lookup, so a test never depends on the network. */
private class UnreachableRegistry : Registry {

    var calls = 0
        private set

    override fun packument(name: String): Packument {
        calls++
        throw IOException("the test registry has no $name")
    }

    override fun tarball(url: String): ByteArray {
        calls++
        throw IOException("the test registry has no tarball at $url")
    }
}
