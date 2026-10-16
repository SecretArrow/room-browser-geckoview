package com.roombrowser.data.repo

import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.PluginDao
import com.roombrowser.data.db.PluginEntity
import com.roombrowser.data.plugins.PluginDirZipper
import com.roombrowser.data.plugins.PluginInstallResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * The plugin repository's dir accessor, against a mockk-backed plugin DAO
 * and a real temp-dir store — the shape the plugin-tool and UI-state tests
 * already use, because what is under test here is the REPOSITORY's own
 * behaviour: a row answers its code directory, no row answers null, and a
 * row whose dir is gone still answers (the files-missing check is the
 * CALLER's read — design §2.4 — so this accessor never invents a directory
 * the disk does not hold, and never hides a row the manager must report).
 *
 * Every test ends on a VOID-returning Truth call: a Kotlin function whose
 * last expression has a value compiles to a non-void method, and JUnit4
 * then refuses the whole class. `runBlocking<Unit>` is the other half of
 * that rule.
 */
class PluginRepositoryTest {

    private val root: File = Files.createTempDirectory("plugin-repo").toFile().also { it.deleteOnExit() }
    private val pluginsDir = File(root, "plugins")

    @Test
    fun `pluginDir resolves a row to its code directory`() = runBlocking<Unit> {
        val repo = repoWith(row("com.example.hello", "com_example_hello"))
        assertThat(repo.pluginDir("com.example.hello"))
            .isEqualTo(File(pluginsDir, "com_example_hello"))
    }

    @Test
    fun `pluginDir is null when no row exists`() = runBlocking<Unit> {
        val repo = repoWith(null)
        assertThat(repo.pluginDir("com.example.gone")).isNull()
    }

    @Test
    fun `pluginDir answers for a row whose dir is gone - isDirectory is the caller's read`() =
        runBlocking<Unit> {
            // The manager's files-missing row: the row exists, the directory
            // does not, and the accessor says exactly that — the path under
            // the store, still not a directory on disk.
            val repo = repoWith(row("com.example.ghost", "com_example_ghost"))
            val dir = repo.pluginDir("com.example.ghost")
            assertThat(dir).isEqualTo(File(pluginsDir, "com_example_ghost"))
            assertThat(dir!!.isDirectory).isFalse()
        }

    @Test
    fun `installing a native manifest that declares inputs carries its form onto the row`() =
        runBlocking<Unit> {
            // The native half of the shared `inputs` feature: a plugin.json
            // manifest is validated against the same schema rules a
            // module.json one is, and the row the gates read
            // (PluginForms.schemaOf) must carry what was validated — a form
            // dropped here installs as a plugin nothing would ever ask about.
            val upserts = mutableListOf<PluginEntity>()
            val pluginDao = mockk<PluginDao> {
                coEvery { byId(any()) } returns null
                coEvery { upsert(any()) } answers { upserts += firstArg() }
            }
            val db = mockk<AppDatabase> { every { pluginDao() } returns pluginDao }
            val repo = PluginRepository(db, pluginsDir, File(root, "plugin-data"))

            val bytes = PluginDirZipper.compose(
                listOf(
                    "plugin.json" to INPUTS_MANIFEST.toByteArray(),
                    "main.js" to "exports.activate = async () => {}".toByteArray()
                )
            )
            val ok = repo.install(ByteArrayInputStream(bytes)) as PluginInstallResult.ManifestOk
            assertThat(ok.manifest.id).isEqualTo("com.example.inputgreeter")
            assertThat(upserts).hasSize(1)
            assertThat(upserts.single().inputsJson).contains("\"key\":\"email\"")
            assertThat(upserts.single().inputsJson).contains("\"type\":\"secret\"")
            assertThat(upserts.single().inputsJson).contains("\"key\":\"choice\"")
        }

    // ------------------------------------------------------------ fixtures

    /** The input-form example's manifest — three required fields, the three editor shapes. */
    private val INPUTS_MANIFEST = """{"id":"com.example.inputgreeter","name":"Input Greeter",""" +
        """"version":"1.0.0","description":"The input-form example.","api":1,"entry":"main.js",""" +
        """"permissions":[],"inputs":[{"key":"email","type":"string","title":"Email",""" +
        """"required":true,"pattern":"^[^@]+@[^@]+$","patternError":"Enter a valid email"},""" +
        """{"key":"choice","type":"choice","title":"Cadence","required":true,""" +
        """"options":[{"value":"daily","label":"Daily"},{"value":"weekly","label":"Weekly"},""" +
        """{"value":"monthly","label":"Monthly"}]},{"key":"secret","type":"secret",""" +
        """"title":"Token","required":true,"minLength":4}]}"""

    /** The repository over one plugin DAO answer: [row], or no row at all. */
    private fun repoWith(row: PluginEntity?): PluginRepository {
        val pluginDao = mockk<PluginDao> { coEvery { byId(any()) } returns row }
        val db = mockk<AppDatabase> { every { pluginDao() } returns pluginDao }
        return PluginRepository(db, pluginsDir, File(root, "plugin-data"))
    }

    private fun row(id: String, dirName: String): PluginEntity = PluginEntity(
        id = id,
        name = "Hello World",
        version = "1.0.0",
        description = "A tiny example.",
        authorName = "Jane",
        license = "MIT",
        entry = "main.js",
        apiVersion = 1,
        permissionsJson = """["storage"]""",
        networkAllowJson = "[]",
        configSchemaJson = "[]",
        commandsJson = "[]",
        hooksJson = "[]",
        dirName = dirName,
        installedAt = 1L,
        updatedAt = 1L
    )
}
