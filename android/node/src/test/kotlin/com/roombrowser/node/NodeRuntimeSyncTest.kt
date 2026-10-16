package com.roombrowser.node

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The recursive asset sync: the whole `assets/node/` tree lands in the node
 * directory byte-for-byte, stale copies retire, and the runtime's own state
 * plus the user's installed packages are never the sync's to touch.
 */
class NodeRuntimeSyncTest {

    @Test
    fun `the whole packaged tree lands where the runtime resolves it`() {
        val dir = tempDir()
        NodeRuntime.syncPackaged(
            dir,
            mapOf(
                "bridge.js" to "bridge".toByteArray(),
                "plugin_host.js" to "host".toByteArray(),
                "plugins/hello-world.zip" to "zip".toByteArray()
            )
        )
        assertThat(File(dir, "bridge.js").readText()).isEqualTo("bridge")
        assertThat(File(dir, "plugin_host.js").readText()).isEqualTo("host")
        assertThat(File(dir, "plugins/hello-world.zip").readText()).isEqualTo("zip")
    }

    @Test
    fun `a file the apk no longer carries is retired, its directory with it`() {
        val dir = tempDir()
        NodeRuntime.syncPackaged(
            dir,
            mapOf("bridge.js" to "v1".toByteArray(), "plugins/old.zip" to "old".toByteArray())
        )
        NodeRuntime.syncPackaged(dir, mapOf("bridge.js" to "v2".toByteArray()))
        assertThat(File(dir, "bridge.js").readText()).isEqualTo("v2")
        assertThat(File(dir, "plugins/old.zip").exists()).isFalse()
        assertThat(File(dir, "plugins").exists()).isFalse()
    }

    @Test
    fun `a changed file is rewritten and an unchanged one is not`() {
        val dir = tempDir()
        NodeRuntime.syncPackaged(dir, mapOf("bridge.js" to "v1".toByteArray()))
        val first = File(dir, "bridge.js")
        val stamp = first.lastModified()
        // A same-content sync leaves the file alone: the directory is read
        // while the runtime runs, and a pointless rewrite would churn it.
        NodeRuntime.syncPackaged(dir, mapOf("bridge.js" to "v1".toByteArray()))
        assertThat(first.lastModified()).isEqualTo(stamp)
        NodeRuntime.syncPackaged(dir, mapOf("bridge.js" to "v2".toByteArray()))
        assertThat(first.readText()).isEqualTo("v2")
    }

    @Test
    fun `the runtime's own files and the user's packages are never touched`() {
        val dir = tempDir()
        File(dir, "bridge.js").writeText("bridge")
        File(dir, "status.json").writeText("the runtime's last report")
        File(dir, "roombrowser-node-1-1.sock").writeText("a socket that a dead run left")
        val modules = File(dir, "node_modules/lodash")
        modules.mkdirs()
        File(modules, "index.js").writeText("a package the user installed")
        NodeRuntime.syncPackaged(dir, mapOf("bridge.js" to "bridge".toByteArray()))
        assertThat(File(dir, "status.json").exists()).isTrue()
        assertThat(File(dir, "roombrowser-node-1-1.sock").exists()).isTrue()
        assertThat(File(modules, "index.js").exists()).isTrue()
        // But they are also not deleted when the sync carries nothing of them.
        NodeRuntime.syncPackaged(
            dir,
            mapOf("bridge.js" to "bridge".toByteArray(), "plugin_host.js" to "host".toByteArray())
        )
        assertThat(File(dir, "status.json").exists()).isTrue()
        assertThat(File(modules, "index.js").exists()).isTrue()
    }

    @Test
    fun `a stale directory the apk stopped carrying goes whole`() {
        val dir = tempDir()
        NodeRuntime.syncPackaged(
            dir,
            mapOf("bridge.js" to "b".toByteArray(), "plugins/a.zip" to "a".toByteArray())
        )
        NodeRuntime.syncPackaged(dir, mapOf("bridge.js" to "b".toByteArray()))
        assertThat(File(dir, "plugins").exists()).isFalse()
    }

    private fun tempDir(): File =
        Files.createTempDirectory("node-sync").toFile().also { it.deleteOnExit() }
}
