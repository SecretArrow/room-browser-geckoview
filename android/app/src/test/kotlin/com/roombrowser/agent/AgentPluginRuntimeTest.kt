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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
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
    private val call = PluginCall { request ->
        calls.add(request)
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
        // A profile that picked no user agent and an engine that has not
        // answered one: the field is left out, which is the honest spelling.
        assertThat(request.userAgent).isNull()
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
        val failing = PluginCall { _ -> PluginCallResult(ok = false, output = "boom: bad schema") }
        val runtime = AgentPluginRuntime(context, profile = null, call = failing)
        val run = runtime.runTool(7L, plugin(), binding(), "count_words", "{}")
        assertThat(run.result.ok).isFalse()
        assertThat(run.result.output).isEqualTo("boom: bad schema")
        assertThat(run.result.output).doesNotContain(UntrustedContent.BEGIN)
    }

    @Test
    fun `a seam failure is an answer, not an exception`() = runBlocking<Unit> {
        val throwing = PluginCall { _ -> error("the runtime is absent") }
        val runtime = AgentPluginRuntime(context, profile = null, call = throwing)
        val result = runtime.run(7L, plugin(), binding(), PluginCallRequest.EVENT_PROBE)
        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("the runtime is absent")
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
