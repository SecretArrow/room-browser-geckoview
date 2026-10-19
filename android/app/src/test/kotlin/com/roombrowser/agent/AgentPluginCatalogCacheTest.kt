package com.roombrowser.agent

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.ChatPluginDao
import com.roombrowser.data.db.ChatPluginEntity
import com.roombrowser.data.db.PluginDao
import com.roombrowser.data.db.PluginEntity
import com.roombrowser.data.repo.PluginRepository
import com.roombrowser.node.PluginCallRequest
import com.roombrowser.node.PluginCallResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * The catalogue's CACHED half — the seams the pure [AgentPluginCatalogTest]
 * does not touch: a probe is expensive (it boots the Node runtime once per
 * enabled binding), so who pays one, and when, is behaviour worth pinning.
 *
 * WHAT IS PINNED HERE, and why each rule exists:
 *
 *  - A warm cache serves reads without another probe. This is the whole point
 *    of the cache: everything after the first read of a session (the message
 *    hooks, the plugin prompt blocks, the tool definitions) must be free.
 *
 *  - An invalidation makes the NEXT read re-probe. The invalidation events
 *    (enable, disable, install, uninstall) are the only things that can change
 *    the answer, and a stale tool list would offer the model calls a chat
 *    never consented to.
 *
 *  - Overlapping readers of a cold session share ONE probe. The turn starts a
 *    probe it does not itself need yet (the start-of-turn warm in
 *    BrowserAgentController.runTurn, which moves the probes off the
 *    submit-to-progress window); this single-flight rule is what makes that
 *    warm a pure win rather than a second probe the first reader also pays.
 *
 *  - An invalidation that lands WHILE a probe runs is not overwritten by that
 *    probe. The probe holds the catalogue's mutex for seconds (the Node
 *    start-up), so an install landing inside that window would have its
 *    invalidation silently undone by the probe's store — every later reader
 *    would keep being served the pre-install answer until the next
 *    invalidation happened to clear it. The generation guard makes the
 *    invalidation win; the probe's own caller still gets its answer, but
 *    nobody after it inherits it.
 *
 * The fake seams are the suite's own: mock DAOs under a real repository, a
 * real runtime over a fake [PluginCall] that only counts probes (the probe's
 * wire work is irrelevant here — only whether one STARTED), and a mutable row
 * store so a test can play an install landing mid-probe.
 *
 * Every test ends on a VOID-returning Truth call: a Kotlin function whose
 * last expression has a value compiles to a non-void method, and JUnit4 then
 * refuses the whole class. `runBlocking<Unit>` is the other half of that rule.
 */
class AgentPluginCatalogCacheTest {

    private val root: File = Files.createTempDirectory("plugin-catalog-cache").toFile()
    private val context: Context = mockk<Context>().apply {
        every { filesDir } returns root
        every { getExternalFilesDir(null) } returns File(root, "ext-files")
    }

    /** How many Node probes have run — the cost every assertion here is about. */
    private val probes = AtomicInteger(0)

    /**
     * What the fake probe runs before answering — the hook a test uses to
     * play an invalidation landing while the probe is in flight, the exact
     * race the generation guard exists for. Runs on the runtime's IO thread,
     * as the real thing would.
     */
    private var onProbe: (() -> Unit)? = null

    private val call = PluginCall { request, _, _ ->
        if (request.event == PluginCallRequest.EVENT_PROBE) {
            probes.incrementAndGet()
            onProbe?.invoke()
        }
        PluginCallResult(ok = true)
    }

    private val plugin = PluginEntity(
        id = "com.example.wordcount",
        name = "Word Count",
        version = "1.0.0",
        description = "Counts words.",
        authorName = "Jane",
        license = "MIT",
        entry = "main.js",
        apiVersion = 1,
        permissionsJson = """["agent.tools","storage"]""",
        networkAllowJson = "[]",
        configSchemaJson = "[]",
        commandsJson = "[]",
        hooksJson = "[]",
        dirName = "com_example_wordcount",
        installedAt = 1L,
        updatedAt = 1L
    )

    private val binding = ChatPluginEntity(
        id = 1L,
        sessionId = 7L,
        pluginId = "com.example.wordcount",
        enabled = true,
        grantedPermissionsJson = """["agent.tools","storage"]""",
        configJson = "",
        createdAt = 1L,
        updatedAt = 1L
    )

    /** The rows the repository answers with, mutable so a test can play a change landing. */
    private val pluginRows = mutableListOf(plugin)
    private val bindingRows = mutableListOf(binding)

    private val catalog: AgentPluginCatalog

    init {
        val pluginDao = mockk<PluginDao> {
            coEvery { all() } answers { pluginRows.toList() }
        }
        val chatDao = mockk<ChatPluginDao> {
            coEvery { forSession(any()) } answers { bindingRows.toList() }
        }
        val db = mockk<AppDatabase> {
            every { pluginDao() } returns pluginDao
            every { chatPluginDao() } returns chatDao
        }
        val repo = PluginRepository(
            db,
            File(root, "plugins"),
            File(root, "plugin-data"),
            AgentPluginRuntime.chatsRoot(context)
        )
        catalog = AgentPluginCatalog(repo, AgentPluginRuntime(context, profile = null, call = call))
    }

    @Test
    fun `a second read of the same session is served from the cache`() = runBlocking<Unit> {
        catalog.sessionPlugins(7L)
        catalog.sessionPlugins(7L)
        // One probe for two reads: everything after the first is free.
        assertThat(probes.get()).isEqualTo(1)
    }

    @Test
    fun `invalidate makes the next read re-probe`() = runBlocking<Unit> {
        catalog.sessionPlugins(7L)
        catalog.invalidate(7L)
        catalog.sessionPlugins(7L)
        // The invalidation event said the answer changed; the next reader pays
        // for the truth instead of being served the stale set.
        assertThat(probes.get()).isEqualTo(2)
    }

    @Test
    fun `overlapping readers of a cold session share one probe`() = runBlocking<Unit> {
        val first = async { catalog.sessionPlugins(7L) }
        val second = async { catalog.sessionPlugins(7L) }
        // Both readers got the real answer...
        assertThat(first.await()).hasSize(1)
        assertThat(second.await()).hasSize(1)
        // ...and paid one probe between them: the second joined the first's
        // probe through the catalogue's mutex instead of starting its own.
        assertThat(probes.get()).isEqualTo(1)
    }

    @Test
    fun `an invalidation that lands mid-probe is not overwritten by that probe`() {
        // The install lands while the probe is still running — inside the
        // catalogue's mutex, which the invalidation (a non-suspending call
        // from wherever it landed) does not and cannot take.
        onProbe = { catalog.invalidateAll() }
        runBlocking<Unit> {
            val overtaken = catalog.sessionPlugins(7L)
            // The caller that asked BEFORE the invalidation still gets its
            // answer: it has its own failure words for a changed world, and
            // holding it until a fresh probe existed would be a second wait
            // nobody asked it to pay.
            assertThat(overtaken).hasSize(1)
        }
        onProbe = null
        runBlocking<Unit> {
            catalog.sessionPlugins(7L)
        }
        // The mid-probe invalidation was not silently undone: the next reader
        // found the cache empty (a fresh probe, not the overtaken one), which
        // is the whole difference between the guard and the store-it-anyway
        // it replaced.
        assertThat(probes.get()).isEqualTo(2)
    }
}
