package com.roombrowser.agent

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.ChatPluginDao
import com.roombrowser.data.db.ChatPluginEntity
import com.roombrowser.data.db.PluginDao
import com.roombrowser.data.db.PluginEntity
import com.roombrowser.data.repo.PluginRepository
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.agent.UntrustedContent
import com.roombrowser.domain.plugins.PluginCard
import com.roombrowser.node.PluginCallRequest
import com.roombrowser.node.PluginCallResult
import com.roombrowser.node.PluginToolSpec
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The innermost wrap of a turn's executor chain, driven against a real
 * catalogue fed by fake DAOs and a real runtime fed by a fake [PluginCall] —
 * because what matters here is the ROUTING, not Node: a plugin tool name is
 * intercepted, resolved to its owner through the probe, and answered with the
 * plugin's fenced output; anything else falls to the page executor untouched;
 * and a plugin tool this chat does not own is a refusal that tells the model
 * what to do about it rather than an "unknown tool" dead end.
 *
 * Every test ends on a VOID-returning Truth call: a Kotlin function whose last
 * expression has a value compiles to a non-void method, and JUnit4 then
 * refuses the whole class. `runBlocking<Unit>` is the other half of that rule.
 */
class PluginToolExecutorTest {

    private val root: File = Files.createTempDirectory("plugin-tool-executor").toFile()
    private val context: Context = mockk<Context>().apply { every { filesDir } returns root }

    private val calls = mutableListOf<PluginCallRequest>()
    private val call = PluginCall { request ->
        calls.add(request)
        when (request.event) {
            PluginCallRequest.EVENT_PROBE -> PluginCallResult(
                ok = true,
                tools = listOf(
                    PluginToolSpec(
                        name = "count_words",
                        description = "Counts the words in a text.",
                        parameters = buildJsonObject {
                            put("type", "object")
                            put("text", buildJsonObject { put("type", "string") })
                        }
                    )
                ),
                commands = listOf("greet"),
                hooks = listOf("message")
            )
            PluginCallRequest.EVENT_TOOL -> PluginCallResult(
                ok = true,
                output = "4 words",
                cards = listOf(
                    buildJsonObject {
                        put("title", "Counted")
                        put("text", "4 words (lifetime total 12)")
                    }
                )
            )
            else -> PluginCallResult(ok = true)
        }
    }

    private val plugin = PluginEntity(
        id = "com.example.wordcount",
        name = "Word Count",
        version = "1.0.0",
        description = "",
        authorName = "Jane",
        license = "MIT",
        entry = "main.js",
        apiVersion = 1,
        permissionsJson = """["agent.tools","chat.context","storage"]""",
        networkAllowJson = "[]",
        configSchemaJson = "[]",
        commandsJson = """[{"name":"greet","title":"Greet","description":""}]""",
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
        grantedPermissionsJson = """["agent.tools","chat.context","storage"]""",
        configJson = "",
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun repoWith(plugins: List<PluginEntity>, bindings: List<ChatPluginEntity>): PluginRepository {
        val pluginDao = mockk<PluginDao> { coEvery { all() } returns plugins }
        val chatDao = mockk<ChatPluginDao> { coEvery { forSession(any()) } returns bindings }
        val db = mockk<AppDatabase> {
            every { pluginDao() } returns pluginDao
            every { chatPluginDao() } returns chatDao
        }
        return PluginRepository(db, File(root, "plugins"), File(root, "plugin-data"))
    }

    private fun executorWith(
        plugins: List<PluginEntity>,
        bindings: List<ChatPluginEntity>,
        inner: ToolExecutor
    ): PluginToolExecutor {
        val repo = repoWith(plugins, bindings)
        val runtime = AgentPluginRuntime(context, profile = null, call = call)
        return PluginToolExecutor(
            inner = inner,
            runtime = runtime,
            catalog = AgentPluginCatalog(repo, runtime),
            sessionId = 7L,
            onCards = { _, _ -> }
        )
    }

    /** An inner executor that records what it was asked and answers [answer]. */
    private fun recordingInner(
        passed: MutableList<Pair<String, String>>,
        answer: ToolResult = ToolResult(true, "page answered")
    ): ToolExecutor = object : ToolExecutor {
        override suspend fun execute(name: String, argsJson: String): ToolResult {
            passed.add(name to argsJson)
            return answer
        }
    }

    /** An inner executor whose answer means the routing test failed: plugin
     *  names must never reach the page tools. */
    private fun neverReachedInner(): ToolExecutor = object : ToolExecutor {
        override suspend fun execute(name: String, argsJson: String): ToolResult =
            ToolResult(false, "the page never sees this")
    }

    @Test
    fun `a non plugin name passes straight through to the page executor`() = runBlocking<Unit> {
        val passed = mutableListOf<Pair<String, String>>()
        val executor = executorWith(listOf(plugin), listOf(binding), recordingInner(passed))
        val result = executor.execute("navigate", """{"url":"https://example.com"}""")
        assertThat(result.output).isEqualTo("page answered")
        assertThat(passed).containsExactly("navigate" to """{"url":"https://example.com"}""")
        // Nothing reached the runtime: a page tool is not a plugin event.
        assertThat(calls).isEmpty()
    }

    @Test
    fun `a plugin tool runs its event and answers with the fenced output`() = runBlocking<Unit> {
        val executor = executorWith(listOf(plugin), listOf(binding), neverReachedInner())
        val result = executor.execute("plugin_com_example_wordcount_count_words", """{"text":"a b c d"}""")
        assertThat(result.ok).isTrue()
        assertThat(result.output).contains(UntrustedContent.BEGIN)
        assertThat(result.output).contains("Word Count plugin")
        assertThat(result.output).contains("4 words")
        // The event the plugin saw carries its own tool spelling, and the
        // model's arguments verbatim — no re-encoding on the way in.
        val toolCall = calls.single { it.event == PluginCallRequest.EVENT_TOOL }
        assertThat(toolCall.payload["name"]?.toString()).contains("count_words")
        assertThat(toolCall.payload["argsJson"]?.toString()).contains("a b c d")
    }

    @Test
    fun `cards a tool pushed reach the conversation, beside the tool result`() = runBlocking<Unit> {
        val received = mutableListOf<Pair<List<PluginCard>, String>>()
        val repo = repoWith(listOf(plugin), listOf(binding))
        val runtime = AgentPluginRuntime(context, profile = null, call = call)
        val executor = PluginToolExecutor(
            inner = neverReachedInner(),
            runtime = runtime,
            catalog = AgentPluginCatalog(repo, runtime),
            sessionId = 7L,
            onCards = { cards, name -> received.add(cards to name) }
        )
        executor.execute("plugin_com_example_wordcount_count_words", "{}")
        assertThat(received).hasSize(1)
        assertThat(received.single().second).isEqualTo("Word Count")
        assertThat(received.single().first.single().title).isEqualTo("Counted")
    }

    @Test
    fun `a plugin tool this chat does not own is a refusal that says what to do`() = runBlocking<Unit> {
        // The catalogue knows the plugin but the binding grants no
        // agent.tools, so the tool has no owner here: the refusal must tell
        // the model to stop retrying, not that the tool never existed.
        val ungranted = binding.copy(
            grantedPermissionsJson = """["storage"]"""
        )
        val executor = executorWith(listOf(plugin), listOf(ungranted), neverReachedInner())
        val result = executor.execute("plugin_com_example_wordcount_count_words", "{}")
        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("not available in this chat")
        assertThat(result.output).contains("plugin_com_example_wordcount_count_words")
        assertThat(result.output).doesNotContain("unknown tool")
    }
}
