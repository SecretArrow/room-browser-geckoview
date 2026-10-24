package com.roombrowser.agent.skills

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The registry over `<skills>/skill.json`: the round-trip an install depends
 * on, the flag flip, the removal (including the plugin row a plugin-format
 * skill owns), and the recovery when a directory is half-deleted or its
 * metadata is corrupt — reported, never crashed on.
 */
class SkillRegistryTest {

    private fun newRoot() = Files.createTempDirectory("skill-registry").toFile()

    private fun newRegistry() = SkillRegistry(newRoot())

    // ---- round-trip ----

    @Test
    fun `an installed skill reads back through a fresh registry`() = runBlocking<Unit> {
        val root = newRoot()
        val record = SkillTestSupport.installDemoSkill(root)
        val reloaded = SkillRegistry(root)

        val snapshot = reloaded.list()
        assertThat(snapshot.skills).hasSize(1)
        assertThat(snapshot.broken).isEmpty()
        val read = snapshot.skills.single()
        assertThat(read.id).isEqualTo(record.id)
        assertThat(read.name).isEqualTo("demo-skill")
        assertThat(read.version).isEqualTo("1.0.0")
        assertThat(read.format).isEqualTo(SkillRecord.FORMAT_INSTRUCTION)
        assertThat(read.enabled).isTrue()
        assertThat(read.source).isEqualTo("owner/repo")
        assertThat(reloaded.instructionOf(read)).contains("Step one.")
    }

    @Test
    fun `get answers null for an unknown or malformed id`() = runBlocking<Unit> {
        val registry = newRegistry()
        SkillTestSupport.installDemoSkill(registry.root)
        assertThat(registry.get("demo")).isNotNull()
        assertThat(registry.get("nope")).isNull()
        // The id reaches File(root, id): anything outside the vocabulary is
        // refused before it can climb.
        assertThat(registry.get("../evil")).isNull()
        assertThat(registry.get("Demo")).isNull()
        assertThat(registry.get(".hidden")).isNull()
    }

    // ---- the enabled flag ----

    @Test
    fun `setEnabled persists across a new registry instance`() = runBlocking<Unit> {
        val root = newRoot()
        val record = SkillTestSupport.installDemoSkill(root)
        val registry = SkillRegistry(root)

        val disabled = registry.setEnabled(record.id, false)
        assertThat(disabled).isNotNull()
        assertThat(disabled!!.enabled).isFalse()

        val reloaded = SkillRegistry(root)
        assertThat(reloaded.get(record.id)!!.enabled).isFalse()

        val enabled = reloaded.setEnabled(record.id, true)
        assertThat(enabled!!.enabled).isTrue()
    }

    @Test
    fun `setEnabled is a no-op on the flag it already holds`() = runBlocking<Unit> {
        val root = newRoot()
        val record = SkillTestSupport.installDemoSkill(root)
        val registry = SkillRegistry(root)
        assertThat(registry.setEnabled(record.id, true)!!.enabled).isTrue()
    }

    @Test
    fun `setEnabled on a missing skill answers null`() = runBlocking<Unit> {
        val registry = newRegistry()
        assertThat(registry.setEnabled("nope", false)).isNull()
    }

    // ---- removal ----

    @Test
    fun `remove deletes the skill directory and nothing else`() = runBlocking<Unit> {
        val root = newRoot()
        val registry = SkillRegistry(root)
        val record = SkillTestSupport.installDemoSkill(root)
        File(root, "other").mkdirs()

        assertThat(registry.remove(record)).isNull()
        assertThat(File(root, record.id).exists()).isFalse()
        // The store's neighbours are untouched.
        assertThat(File(root, "other").isDirectory).isTrue()
        assertThat(registry.list().skills).isEmpty()
    }

    @Test
    fun `removing a plugin-format skill uninstalls its plugin row first`() = runBlocking<Unit> {
        val root = newRoot()
        val registry = SkillRegistry(root)
        val uninstalled = mutableListOf<String>()
        registry.pluginUninstaller = { id -> uninstalled.add(id) }

        val record = SkillRecord(
            id = "plugin-demo",
            name = "Demo Plugin Skill",
            source = "owner/repo",
            installedAt = 1,
            updatedAt = 1,
            format = SkillRecord.FORMAT_PLUGIN,
            pluginId = "demo-plugin"
        )
        // The directory a plugin-format install commits: metadata only.
        File(root, "plugin-demo").mkdirs()
        File(root, "plugin-demo/${SkillRecord.METADATA_FILE}")
            .writeText(SkillRecord.toJsonString(record))

        assertThat(registry.remove(record)).isNull()
        // The plugin row went first, so a crash between the two steps can
        // never leave a metadata dir pointing at live plugin code.
        assertThat(uninstalled).containsExactly("demo-plugin")
        assertThat(File(root, "plugin-demo").exists()).isFalse()
    }

    @Test
    fun `a failing plugin uninstall is reported, not swallowed`() = runBlocking<Unit> {
        val root = newRoot()
        val registry = SkillRegistry(root)
        registry.pluginUninstaller = { throw java.io.IOException("the row is stuck") }
        val record = SkillRecord(
            id = "plugin-demo",
            name = "Demo Plugin Skill",
            source = "owner/repo",
            installedAt = 1,
            updatedAt = 1,
            format = SkillRecord.FORMAT_PLUGIN,
            pluginId = "demo-plugin"
        )
        File(root, "plugin-demo").mkdirs()
        File(root, "plugin-demo/${SkillRecord.METADATA_FILE}")
            .writeText(SkillRecord.toJsonString(record))

        val problem = registry.remove(record)
        assertThat(problem).contains("the row is stuck")
    }

    // ---- corrupt-state recovery ----

    @Test
    fun `a corrupt skill json is reported in broken, not crashed on`() = runBlocking<Unit> {
        val root = newRoot()
        SkillTestSupport.installDemoSkill(root)
        // A half-written metadata file is a state a file manager can produce.
        val corrupt = File(root, "corrupt")
        corrupt.mkdirs()
        File(corrupt, SkillRecord.METADATA_FILE).writeText("{ not json")

        val snapshot = SkillRegistry(root).list()
        assertThat(snapshot.skills.map { it.id }).containsExactly("demo")
        assertThat(snapshot.broken).containsExactly("corrupt")
    }

    @Test
    fun `a directory with no metadata is broken too`() = runBlocking<Unit> {
        val root = newRoot()
        File(root, "empty-dir").mkdirs()
        val snapshot = SkillRegistry(root).list()
        assertThat(snapshot.broken).containsExactly("empty-dir")
        assertThat(snapshot.skills).isEmpty()
    }

    @Test
    fun `dot directories and files at the root are ignored, not reported`() = runBlocking<Unit> {
        val root = newRoot()
        SkillTestSupport.installDemoSkill(root)
        File(root, ".staging-abc").mkdirs()
        File(root, "loose.txt").writeText("not a skill")
        val snapshot = SkillRegistry(root).list()
        assertThat(snapshot.skills).hasSize(1)
        assertThat(snapshot.broken).isEmpty()
    }

    // ---- the id vocabulary ----

    @Test
    fun `validId is exactly the vocabulary the installer emits`() {
        val registry = newRegistry()
        assertThat(registry.validId("demo")).isTrue()
        assertThat(registry.validId("binance-agentic-wallet")).isTrue()
        assertThat(registry.validId("a_b-9")).isTrue()
        assertThat(registry.validId("")).isFalse()
        assertThat(registry.validId("Demo")).isFalse()
        assertThat(registry.validId("../evil")).isFalse()
        assertThat(registry.validId("a/b")).isFalse()
        assertThat(registry.validId(".hidden")).isFalse()
        assertThat(registry.validId("has space")).isFalse()
        assertThat(registry.validId("x".repeat(97))).isFalse()
        assertThat(registry.validId("x".repeat(96))).isTrue()
    }
}
