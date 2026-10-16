package com.roombrowser.agent

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AgentMessageEntity
import com.roombrowser.data.db.ChatPluginEntity
import com.roombrowser.data.db.PluginEntity
import com.roombrowser.domain.agent.UntrustedContent
import com.roombrowser.node.PluginCallRequest
import com.roombrowser.node.PluginCallResult
import com.roombrowser.node.PluginChatEntry
import com.roombrowser.node.PluginGenerateRequest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The app-side engine of chat plugins, driven through the [PluginCall] seam —
 * the request it builds from the installed row and the chat's binding, and
 * the answers it maps tool outcomes to.
 *
 * What matters HERE is the contract with `plugin_host.js`, not Node itself:
 * the roots the host is allowed to see, the effective grant set (declared
 * intersect granted — the host enforces exactly what it is told), the merged
 * config, and the `chat.context` gate on the transcript. The fencing of tool
 * output is [AgentNodeRuntime]'s same rule, so it gets the same assertions:
 * the plugin's words are data to the model, and its failures are its own
 * words, unfenced, exactly like a `node_run` failure.
 *
 * Every test ends on a VOID-returning Truth call: a Kotlin function whose
 * last expression has a value compiles to a non-void method, and JUnit4 then
 * refuses the whole class. `runBlocking<Unit>` is the other half of that
 * rule.
 */
class AgentPluginRuntimeTest {

    private val root: File = Files.createTempDirectory("agent-plugin-runtime").toFile()
    private val context: Context = mockk<Context>().apply { every { filesDir } returns root }

    private val calls = mutableListOf<PluginCallRequest>()

    /**
     * The reverse handler each call's generator became — wrapped with the
     * SAME production wrap [PluginCall.throughRunner] uses, so a test that
     * invokes one drives exactly the code the runner would hand NodeClient.
     */
    private val reverseHandlers = mutableListOf<((JsonObject) -> JsonObject)?>()

    private val call = PluginCall { request, generate ->
        calls.add(request)
        reverseHandlers.add(generate?.let(::reverseGenerateHandler))
        PluginCallResult(ok = true, output = "ran ${request.event}")
    }

    private fun plugin(
        permissions: String = """["agent.tools","chat.context","storage"]""",
        configSchema: String = """[{"key":"ignore_case","type":"boolean","default":true,"title":"Ignore case","description":""}]"""
    ) = PluginEntity(
        id = "com.example.wordcount",
        name = "Word Count",
        version = "1.0.0",
        description = "",
        authorName = "Jane",
        license = "MIT",
        entry = "main.js",
        apiVersion = 1,
        permissionsJson = permissions,
        networkAllowJson = "[]",
        configSchemaJson = configSchema,
        commandsJson = "[]",
        hooksJson = "[]",
        dirName = "com_example_wordcount",
        installedAt = 1L,
        updatedAt = 1L
    )

    private fun binding(
        granted: String = """["agent.tools","chat.context","storage"]""",
        config: String = """{"ignore_case":false,"surprise":"dropped"}"""
    ) = ChatPluginEntity(
        id = 1L,
        sessionId = 7L,
        pluginId = "com.example.wordcount",
        enabled = true,
        grantedPermissionsJson = granted,
        configJson = config,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun runtime() = AgentPluginRuntime(context, profile = null, call = call)

    /** One reverse request line, in the shape bridge.js writes (design §3). */
    private fun reverseLine(): JsonObject = buildJsonObject {
        put("id", "gen-1")
        put("cmd", "agent_generate")
        put("token", "0123456789abcdef0123456789abcdef0123456789abcdef")
        put(
            "request",
            buildJsonObject {
                put("purpose", "chat-reply")
                put("instruction", "Reply to the last message")
                put("style", buildJsonObject { put("maxWords", 24) })
            }
        )
    }

    @Test
    fun `the request carries the row and the binding, roots scoped per design`() = runBlocking<Unit> {
        runtime().run(7L, plugin(), binding(), PluginCallRequest.EVENT_PROBE)
        assertThat(calls).hasSize(1)
        val request = calls.single()
        assertThat(request.pluginRoot)
            .isEqualTo(File(File(root, "plugins"), "com_example_wordcount").absolutePath)
        assertThat(request.dataRoot)
            .isEqualTo(File(File(File(root, "plugin-data"), "7"), "com_example_wordcount").absolutePath)
        assertThat(request.entry).isEqualTo("main.js")
        assertThat(request.event).isEqualTo("probe")
        // A profile that picked no user agent falls back to the engine's
        // own default, and the two editions answer differently under the JVM
        // stub (WebView: nothing; GeckoView: its bundled constant) — so the
        // edition-neutral contract is the one the wire actually promises:
        // the field is absent or a real string, never blank. The "blank is
        // omitted" rule itself is the protocol test's subject.
        val ua = request.userAgent
        assertThat(ua == null || ua.isNotBlank()).isTrue()
    }

    @Test
    fun `the manifest carries the effective grants, declared intersect granted`() = runBlocking<Unit> {
        runtime().run(
            7L,
            plugin(permissions = """["storage","network","agent.tools"]"""),
            binding(granted = """["storage"]"""),
            PluginCallRequest.EVENT_PROBE
        )
        assertThat(calls.single().manifest.permissions).containsExactly("storage")
        assertThat(calls.single().manifest.permissions).containsNoDuplicates()
    }

    @Test
    fun `the config is the schema defaults under the chat's overrides, and nothing else`() = runBlocking<Unit> {
        runtime().run(7L, plugin(), binding(), PluginCallRequest.EVENT_PROBE)
        val config = calls.single().config
        // The declared override wins over the manifest default...
        assertThat(config["ignore_case"]?.jsonPrimitive?.booleanOrNull).isFalse()
        // ...and a key the schema never declared is dropped, because it is
        // not input the plugin's author wrote code against.
        assertThat(config["surprise"]).isNull()
        assertThat(config).hasSize(1)
    }

    @Test
    fun `the transcript rides only a chat context grant`() = runBlocking<Unit> {
        val history = listOf(PluginChatEntry(role = "user", text = "hi"))
        runtime().run(
            7L,
            plugin(permissions = """["storage"]"""),
            binding(granted = """["storage"]"""),
            PluginCallRequest.EVENT_ACTIVATE,
            history = history
        )
        assertThat(calls.single().history).isNull()

        calls.clear()
        runtime().run(
            7L,
            plugin(permissions = """["storage","chat.context"]"""),
            binding(granted = """["storage","chat.context"]"""),
            PluginCallRequest.EVENT_ACTIVATE,
            history = history
        )
        assertThat(calls.single().history).isEqualTo(history)
    }

    @Test
    fun `a tool's success is fenced as the plugin's data`() = runBlocking<Unit> {
        val run = runtime().runTool(7L, plugin(), binding(), "count_words", """{"text":"a b c"}""")
        assertThat(run.result.ok).isTrue()
        assertThat(run.result.output).contains(UntrustedContent.BEGIN)
        assertThat(run.result.output).contains(UntrustedContent.END)
        assertThat(run.result.output).contains("ran tool")
        assertThat(run.result.output).contains("Word Count plugin")
    }

    @Test
    fun `a tool's failure is the plugin's own words, unfenced`() = runBlocking<Unit> {
        val failing = PluginCall { _, _ -> PluginCallResult(ok = false, output = "boom: bad schema") }
        val runtime = AgentPluginRuntime(context, profile = null, call = failing)
        val run = runtime.runTool(7L, plugin(), binding(), "count_words", "{}")
        assertThat(run.result.ok).isFalse()
        assertThat(run.result.output).isEqualTo("boom: bad schema")
        assertThat(run.result.output).doesNotContain(UntrustedContent.BEGIN)
    }

    @Test
    fun `a seam failure is an answer, not an exception`() = runBlocking<Unit> {
        val throwing = PluginCall { _, _ -> error("the runtime is absent") }
        val runtime = AgentPluginRuntime(context, profile = null, call = throwing)
        val result = runtime.run(7L, plugin(), binding(), PluginCallRequest.EVENT_PROBE)
        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("the runtime is absent")
    }

    // ------------------------------------------------------- the generate seam

    @Test
    fun `a generator rides the seam and answers a reverse line as text`() = runBlocking<Unit> {
        val seen = mutableListOf<PluginGenerateRequest>()
        val generator = PluginGenerateCall { request ->
            seen.add(request)
            "the puzzle app is honestly addictive"
        }
        runtime().run(
            7L, plugin(), binding(), PluginCallRequest.EVENT_ACTIVATE, generate = generator
        )
        val handler = requireNotNull(reverseHandlers.single())
        val answer = handler(reverseLine())
        assertThat(answer.toString())
            .isEqualTo("""{"text":"the puzzle app is honestly addictive"}""")
        // The generator saw the request the reverse line carried — parsed and
        // clamped, not the raw line — and the wrap answered its text.
        assertThat(seen.single().purpose).isEqualTo("chat-reply")
        assertThat(seen.single().style.maxWords).isEqualTo(24)
    }

    @Test
    fun `a throwing generator surfaces its own words`() = runBlocking<Unit> {
        val throwing = PluginGenerateCall { _ -> error("the chat's model wrote no text") }
        runtime().run(
            7L, plugin(), binding(), PluginCallRequest.EVENT_ACTIVATE, generate = throwing
        )
        val handler = requireNotNull(reverseHandlers.single())
        // The wrap catches NOTHING — NodeClient is the one that turns a throw
        // into the reverse line's error envelope — so the handler raises the
        // generator's own words for it to carry.
        val thrown = assertThrows(IllegalStateException::class.java) { handler(reverseLine()) }
        assertThat(thrown.message).isEqualTo("the chat's model wrote no text")
    }

    @Test
    fun `no generator means no handler on the seam`() = runBlocking<Unit> {
        runtime().run(7L, plugin(), binding(), PluginCallRequest.EVENT_PROBE)
        assertThat(reverseHandlers.single()).isNull()
    }

    @Test
    fun `runTool hands the generator to the tool event`() = runBlocking<Unit> {
        val generator = PluginGenerateCall { _ -> "text" }
        runtime().runTool(7L, plugin(), binding(), "count_words", "{}", generate = generator)
        assertThat(reverseHandlers.single()).isNotNull()
    }

    @Test
    fun `historyOf keeps the conversation rows and caps the entries`() {
        val messages = (1..80).flatMap { n ->
            listOf(
                AgentMessageEntity(sessionId = 7, role = "user", content = "u$n", createdAt = n * 3L),
                AgentMessageEntity(sessionId = 7, role = "tool", content = "t$n", createdAt = n * 3L + 1),
                AgentMessageEntity(sessionId = 7, role = "assistant", content = "a$n", createdAt = n * 3L + 2)
            )
        }
        val history = AgentPluginRuntime.historyOf(messages)
        // Sixty conversation entries from the newest end, tool rows never.
        assertThat(history).hasSize(60)
        assertThat(history.none { it.role == "tool" }).isTrue()
        assertThat(history.first().text).isEqualTo("u51")
        assertThat(history.last().text).isEqualTo("a80")
    }

    @Test
    fun `historyOf trims the oldest rows at the character cap`() {
        val long = "x".repeat(500)
        val messages = (1..40).map { n ->
            AgentMessageEntity(sessionId = 7, role = "user", content = "$long$n", createdAt = n * 2L)
        }
        val history = AgentPluginRuntime.historyOf(messages)
        // The cap keeps whole entries from the newest end until 12 000 chars;
        // 40 entries of ~500 chars is ~20 000, so the oldest third goes.
        assertThat(history.sumOf { it.text.length }).isAtMost(12_000)
        assertThat(history.last().text).endsWith("40")
        assertThat(history.size).isLessThan(40)
    }
}
