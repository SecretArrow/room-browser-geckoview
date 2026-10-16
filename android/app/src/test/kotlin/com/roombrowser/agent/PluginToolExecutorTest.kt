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
import kotlinx.serialization.json.jsonPrimitive
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
 * The same driving covers the missing-inputs gate that sits AFTER those
 * refusals: a tool whose manifest declares required inputs runs only when the
 * binding answered them — an unanswered set raises the chat's form host and
 * waits, a completed form's values are stored and ride the call, and a form
 * nobody completes (or a chain with no host at all — a probe, a scheduled run,
 * a headless turn) refuses in the instructive words that tell the model to
 * send the person to /plugins.
 *
 * Every test ends on a VOID-returning Truth call: a Kotlin function whose last
 * expression has a value compiles to a non-void method, and JUnit4 then
 * refuses the whole class. `runBlocking<Unit>` is the other half of that rule.
 */
class PluginToolExecutorTest {

    private val root: File = Files.createTempDirectory("plugin-tool-executor").toFile()
    private val context: Context = mockk<Context>().apply { every { filesDir } returns root }

    private val calls = mutableListOf<PluginCallRequest>()

    /** The generator each event's call was handed, event for event with [calls]. */
    private val generators = mutableListOf<PluginGenerateCall?>()

    private val call = PluginCall { request, generate, form ->
        calls.add(request)
        generators.add(generate)
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

    /** The same plugin, with the required-inputs form its manifest declares — and a network allowlist, so the gate's form carries the hosts. */
    private val pluginWithInputs = plugin.copy(
        inputsJson = """[{"key":"email","type":"string","title":"Email","description":"","required":true}]""",
        permissionsJson = """["agent.tools","chat.context","storage","network"]""",
        networkAllowJson = """["api.example.com"]"""
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
        val rows = bindings.toMutableList()
        return repoWith(plugins) { rows }
    }

    /**
     * The repository fake the gate needs: bindings as MUTABLE state, so the
     * gate's own write (the accepted form's values) is what its re-read sees —
     * the store-then-reread contract the call's inputs ride on.
     */
    private fun repoWith(
        plugins: List<PluginEntity>,
        bindings: () -> MutableList<ChatPluginEntity>
    ): PluginRepository {
        val pluginDao = mockk<PluginDao> { coEvery { all() } returns plugins }
        val chatDao = mockk<ChatPluginDao> {
            coEvery { forSession(any()) } answers { bindings().toList() }
            coEvery { upsert(any()) } answers {
                val row = firstArg<ChatPluginEntity>()
                bindings().removeAll {
                    it.id == row.id || (it.sessionId == row.sessionId && it.pluginId == row.pluginId)
                }
                bindings().add(row)
                Unit
            }
        }
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

    @Test
    fun `the executor's generator flows to the tool event`() = runBlocking<Unit> {
        // Resolved at EXECUTE time, not construction — the test hands the
        // wrapper a supplier and asserts the tool event ran with what it
        // answered, which is the whole contract the turn path relies on.
        val generator = PluginGenerateCall { _ -> "composed" }
        val repo = repoWith(listOf(plugin), listOf(binding))
        val runtime = AgentPluginRuntime(context, profile = null, call = call)
        val executor = PluginToolExecutor(
            inner = neverReachedInner(),
            runtime = runtime,
            catalog = AgentPluginCatalog(repo, runtime),
            sessionId = 7L,
            onCards = { _, _ -> },
            generate = { generator }
        )
        executor.execute("plugin_com_example_wordcount_count_words", "{}")
        val toolEvent = calls.indexOfFirst { it.event == PluginCallRequest.EVENT_TOOL }
        assertThat(toolEvent).isAtLeast(0)
        assertThat(generators[toolEvent]).isEqualTo(generator)
    }

    @Test
    fun `without a generator the tool event runs with none`() = runBlocking<Unit> {
        val executor = executorWith(listOf(plugin), listOf(binding), neverReachedInner())
        executor.execute("plugin_com_example_wordcount_count_words", "{}")
        // Probe and tool alike: the default is no handler, and the runtime
        // answers any reverse ask with its own not-available words.
        assertThat(generators).containsExactly(null, null)
    }

    // ---------------------------------------------------- the missing-inputs gate

    @Test
    fun `a tool with unanswered required inputs waits for the form and runs with the answers`() = runBlocking<Unit> {
        val asked = mutableListOf<PluginFormRequest>()
        val host = PluginFormCall { request ->
            asked.add(request)
            PluginFormOutcome.Submitted(buildJsonObject { put("email", "a@b.c") })
        }
        val rows = mutableListOf(binding)
        val repo = repoWith(listOf(pluginWithInputs)) { rows }
        val runtime = AgentPluginRuntime(context, profile = null, call = call)
        val executor = PluginToolExecutor(
            inner = neverReachedInner(),
            runtime = runtime,
            catalog = AgentPluginCatalog(repo, runtime),
            sessionId = 7L,
            onCards = { _, _ -> },
            form = host,
            repo = repo
        )
        val result = executor.execute("plugin_com_example_wordcount_count_words", "{}")
        // The tool ran, and the answers the person gave rode its call.
        assertThat(result.ok).isTrue()
        val toolCall = calls.single { it.event == PluginCallRequest.EVENT_TOOL }
        assertThat(toolCall.inputsValues?.get("email")?.jsonPrimitive?.content).isEqualTo("a@b.c")
        // The gate asked with the tool-call context: the plugin's own fields,
        // under the plugin's identity, at the moment the model called.
        val request = asked.single()
        assertThat(request.context).isEqualTo(PluginFormContext.TOOL_CALL)
        assertThat(request.pluginId).isEqualTo("com.example.wordcount")
        assertThat(request.pluginName).isEqualTo("Word Count")
        assertThat(request.fields.single().key).isEqualTo("email")
        assertThat(request.fields.single().required).isTrue()
        // The row's allowlist rides the request — the dialog's network
        // context line, frozen at the moment the gate raised it.
        assertThat(request.networkHosts).containsExactly("api.example.com")
        // And the answers were STORED — the binding the tool re-read carries
        // them, so the next call needs nobody's form.
        assertThat(rows.single().inputsValuesJson).contains("a@b.c")
    }

    @Test
    fun `a cancelled form refuses the tool call in the instructive words`() = runBlocking<Unit> {
        val repo = repoWith(listOf(pluginWithInputs), listOf(binding))
        val runtime = AgentPluginRuntime(context, profile = null, call = call)
        val executor = PluginToolExecutor(
            inner = neverReachedInner(),
            runtime = runtime,
            catalog = AgentPluginCatalog(repo, runtime),
            sessionId = 7L,
            onCards = { _, _ -> },
            form = PluginFormCall { _ -> PluginFormOutcome.Cancelled },
            repo = repo
        )
        val result = executor.execute("plugin_com_example_wordcount_count_words", "{}")
        // The refusal is the design's exact instructive sentence, unfenced —
        // the app's own words telling the MODEL what to do about it, not the
        // plugin's output fenced as data.
        assertThat(result.ok).isFalse()
        assertThat(result.output).isEqualTo(
            "the user did not complete the form for this plugin — ask them to fill it via /plugins"
        )
        assertThat(result.output).doesNotContain(UntrustedContent.BEGIN)
        // And nothing ran: the tool event never reached the runtime.
        assertThat(calls.none { it.event == PluginCallRequest.EVENT_TOOL }).isTrue()
    }

    @Test
    fun `a chain with no form host refuses rather than holding the turn`() = runBlocking<Unit> {
        // The probe, the scheduled run, the headless turn: nobody wired a host,
        // so the gate short-circuits to the refusal instead of waiting 300 s
        // for a dialog that can never open.
        val repo = repoWith(listOf(pluginWithInputs), listOf(binding))
        val runtime = AgentPluginRuntime(context, profile = null, call = call)
        val executor = PluginToolExecutor(
            inner = neverReachedInner(),
            runtime = runtime,
            catalog = AgentPluginCatalog(repo, runtime),
            sessionId = 7L,
            onCards = { _, _ -> },
            form = null,
            repo = repo
        )
        val result = executor.execute("plugin_com_example_wordcount_count_words", "{}")
        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("fill it via /plugins")
        assertThat(calls.none { it.event == PluginCallRequest.EVENT_TOOL }).isTrue()
    }

    @Test
    fun `a tool whose inputs are already answered runs without asking`() = runBlocking<Unit> {
        val answered = binding.copy(inputsValuesJson = """{"email":"a@b.c"}""")
        val repo = repoWith(listOf(pluginWithInputs), listOf(answered))
        val runtime = AgentPluginRuntime(context, profile = null, call = call)
        val executor = PluginToolExecutor(
            inner = neverReachedInner(),
            runtime = runtime,
            catalog = AgentPluginCatalog(repo, runtime),
            sessionId = 7L,
            onCards = { _, _ -> },
            form = PluginFormCall { _ -> error("the form host must not be asked") },
            repo = repo
        )
        val result = executor.execute("plugin_com_example_wordcount_count_words", "{}")
        // Stored answers are the gate's satisfaction: the tool ran with them,
        // and no dialog was ever raised.
        assertThat(result.ok).isTrue()
        val toolCall = calls.single { it.event == PluginCallRequest.EVENT_TOOL }
        assertThat(toolCall.inputsValues?.get("email")?.jsonPrimitive?.content).isEqualTo("a@b.c")
    }

    @Test
    fun `a second call in the same turn reads the stored answers and does not re-ask`() = runBlocking<Unit> {
        // The catalogue's cached probe holds the binding as it was BEFORE the
        // first call stored its answers — the gate reads the binding FRESH,
        // or the model's second tool call in one turn would raise the same
        // dialog again for values this chat already answered.
        val asked = mutableListOf<PluginFormRequest>()
        val host = PluginFormCall { request ->
            asked.add(request)
            PluginFormOutcome.Submitted(buildJsonObject { put("email", "a@b.c") })
        }
        val rows = mutableListOf(binding)
        val repo = repoWith(listOf(pluginWithInputs)) { rows }
        val runtime = AgentPluginRuntime(context, profile = null, call = call)
        val executor = PluginToolExecutor(
            inner = neverReachedInner(),
            runtime = runtime,
            catalog = AgentPluginCatalog(repo, runtime),
            sessionId = 7L,
            onCards = { _, _ -> },
            form = host,
            repo = repo
        )
        val first = executor.execute("plugin_com_example_wordcount_count_words", "{}")
        val second = executor.execute("plugin_com_example_wordcount_count_words", "{}")
        assertThat(first.ok).isTrue()
        assertThat(second.ok).isTrue()
        // One form for the whole turn: the second call found the stored answers.
        assertThat(asked).hasSize(1)
        assertThat(calls.filter { it.event == PluginCallRequest.EVENT_TOOL }).hasSize(2)
        assertThat(rows.single().inputsValuesJson).contains("a@b.c")
    }

    @Test
    fun `a plugin with no declared form runs whatever the binding stored`() = runBlocking<Unit> {
        // No schema means no gate: the plain plugin of every other test enables
        // and runs exactly as it did before forms existed.
        val repo = repoWith(listOf(plugin), listOf(binding))
        val runtime = AgentPluginRuntime(context, profile = null, call = call)
        val executor = PluginToolExecutor(
            inner = neverReachedInner(),
            runtime = runtime,
            catalog = AgentPluginCatalog(repo, runtime),
            sessionId = 7L,
            onCards = { _, _ -> },
            form = PluginFormCall { _ -> error("the form host must not be asked") },
            repo = repo
        )
        val result = executor.execute("plugin_com_example_wordcount_count_words", "{}")
        assertThat(result.ok).isTrue()
        assertThat(calls.single { it.event == PluginCallRequest.EVENT_TOOL }.inputsValues).isNull()
    }
}
