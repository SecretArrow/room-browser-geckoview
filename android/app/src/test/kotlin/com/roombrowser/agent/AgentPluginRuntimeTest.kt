package com.roombrowser.agent

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AgentMessageEntity
import com.roombrowser.data.db.ChatPluginEntity
import com.roombrowser.data.db.PluginEntity
import com.roombrowser.domain.agent.UntrustedContent
import com.roombrowser.domain.plugins.PluginFormat
import com.roombrowser.node.PluginCallRequest
import com.roombrowser.node.PluginCallResult
import com.roombrowser.node.PluginChatEntry
import com.roombrowser.node.PluginGenerateRequest
import com.roombrowser.node.ReverseHandlers
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
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
 * The same driving covers the two halves a module.json row adds: the MODULE
 * half of the request (its own budget, state directory, verbatim spellings,
 * module permission vocabulary after the intersection) and the FORM bridge
 * (the reverse `user_input` handler the chat's form host becomes on the
 * read loop — its answer and its three refusal words).
 *
 * Every test ends on a VOID-returning Truth call: a Kotlin function whose
 * last expression has a value compiles to a non-void method, and JUnit4 then
 * refuses the whole class. `runBlocking<Unit>` is the other half of that
 * rule.
 */
class AgentPluginRuntimeTest {

    private val root: File = Files.createTempDirectory("agent-plugin-runtime").toFile()
    private val context: Context = mockk<Context>().apply {
        every { filesDir } returns root
        // The device's own spelling: a real app-specific external volume,
        // so the per-chat folder tree lands where a file manager walks it.
        every { getExternalFilesDir(null) } returns File(root, "ext")
    }

    private val calls = mutableListOf<PluginCallRequest>()

    /**
     * The reverse handler each call's generator became — wrapped with the
     * SAME production wrap [PluginCall.throughRunner] uses, so a test that
     * invokes one drives exactly the code the runner would hand NodeClient.
     */
    private val reverseHandlers = mutableListOf<((JsonObject) -> JsonObject)?>()

    private val call = PluginCall { request, generate, form ->
        calls.add(request)
        reverseHandlers.add(generate?.let(::reverseGenerateHandler))
        forms.add(form)
        PluginCallResult(ok = true, output = "ran ${request.event}")
    }

    /** The form host each event's call was handed, event for event with [calls]. */
    private val forms = mutableListOf<PluginFormCall?>()

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
        config: String = """{"ignore_case":false,"surprise":"dropped"}""",
        inputs: String? = null
    ) = ChatPluginEntity(
        id = 1L,
        sessionId = 7L,
        pluginId = "com.example.wordcount",
        enabled = true,
        grantedPermissionsJson = granted,
        configJson = config,
        inputsValuesJson = inputs,
        createdAt = 1L,
        updatedAt = 1L
    )

    /**
     * A module.json row, as the installer writes it: the format marker, the
     * module's own spellings, its own budget, and its `inputs` schema — the
     * columns a zagents-format call is built from.
     */
    private fun module(
        permissions: String = """["storage","files","network","chat.context","agent.generate"]""",
        inputs: String? = """[{"key":"email","type":"string","title":"Email","description":"","required":true}]"""
    ) = PluginEntity(
        id = "zagents.greeter",
        name = "Greeter Module",
        version = "0.0.0",
        description = "Greets.",
        authorName = "",
        license = "",
        entry = "main.js",
        apiVersion = 1,
        permissionsJson = permissions,
        networkAllowJson = "[]",
        configSchemaJson = "[]",
        commandsJson = "[]",
        hooksJson = "[]",
        dirName = "zagents_greeter",
        installedAt = 1L,
        updatedAt = 1L,
        format = PluginFormat.ZAGENTS,
        zagentsName = "greeter",
        zagentsVersion = "1.2-beta",
        zagentsEntry = "main.js",
        timeoutSeconds = 45,
        inputsJson = inputs,
        promptStaged = false
    )

    /** The module's binding: the row vocabulary's grants, and this chat's form answers. */
    private fun moduleBinding(
        granted: String = """["storage","files","network","chat.context","agent.generate"]""",
        inputs: String? = """{"email":"a@b.c"}"""
    ) = ChatPluginEntity(
        id = 2L,
        sessionId = 7L,
        pluginId = "zagents.greeter",
        enabled = true,
        grantedPermissionsJson = granted,
        configJson = "",
        inputsValuesJson = inputs,
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
            .isEqualTo(
                File(File(File(File(root, "ext"), "chats"), "7"), "com_example_wordcount").absolutePath
            )
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
    fun `a legacy internal data folder moves to the chat folder before the call`() = runBlocking<Unit> {
        // The pre-chat-folders releases kept per-chat data under the internal
        // `plugin-data/<sessionId>/<dirName>` tree; the first call for that
        // chat and plugin carries the folder over — moved, not forked.
        val legacy = File(File(File(root, "plugin-data"), "7"), "com_example_wordcount")
        legacy.mkdirs()
        File(legacy, "kv.json").writeText("""{"count":2}""")
        File(legacy, "state").mkdirs()
        File(legacy, "notes.txt").writeText("kept")
        runtime().run(7L, plugin(), binding(), PluginCallRequest.EVENT_PROBE)
        val request = calls.single()
        val moved = File(
            File(File(File(root, "ext"), "chats"), "7"),
            "com_example_wordcount"
        )
        assertThat(request.dataRoot).isEqualTo(moved.absolutePath)
        assertThat(File(moved, "kv.json").exists()).isTrue()
        assertThat(File(moved, "notes.txt").exists()).isTrue()
        assertThat(legacy.exists()).isFalse()
    }

    @Test
    fun `no external volume keeps the chats tree internal`() = runBlocking<Unit> {
        val internal = mockk<Context>().apply {
            every { filesDir } returns root
            every { getExternalFilesDir(null) } returns null
        }
        AgentPluginRuntime(internal, profile = null, call = call)
            .run(7L, plugin(), binding(), PluginCallRequest.EVENT_PROBE)
        assertThat(calls.single().dataRoot).isEqualTo(
            File(
                File(File(File(root, "plugin-data"), "chats"), "7"),
                "com_example_wordcount"
            ).absolutePath
        )
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
        val failing = PluginCall { _, _, _ -> PluginCallResult(ok = false, output = "boom: bad schema") }
        val runtime = AgentPluginRuntime(context, profile = null, call = failing)
        val run = runtime.runTool(7L, plugin(), binding(), "count_words", "{}")
        assertThat(run.result.ok).isFalse()
        assertThat(run.result.output).isEqualTo("boom: bad schema")
        assertThat(run.result.output).doesNotContain(UntrustedContent.BEGIN)
    }

    @Test
    fun `a seam failure is an answer, not an exception`() = runBlocking<Unit> {
        val throwing = PluginCall { _, _, _ -> error("the runtime is absent") }
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

    // ------------------------------------------------- the module half of a call

    @Test
    fun `a module call carries the module half of the request`() = runBlocking<Unit> {
        val host = PluginFormCall { _ -> PluginFormOutcome.Cancelled }
        runtime().run(
            7L, module(), moduleBinding(), PluginCallRequest.EVENT_ACTIVATE,
            prompt = "hello there", form = host
        )
        val request = calls.single()
        assertThat(request.format).isEqualTo(PluginCallRequest.FORMAT_ZAGENTS)
        assertThat(request.prompt).isEqualTo("hello there")
        // The module's own budget wins both ways: the wire's timeoutSeconds
        // and the read timeout's timeoutMs are the manifest's number.
        assertThat(request.timeoutSeconds).isEqualTo(45)
        assertThat(request.timeoutMs).isEqualTo(45_000L)
        assertThat(request.stateRoot).isEqualTo(
            File(
                File(File(File(File(root, "ext"), "chats"), "7"), "zagents_greeter"),
                "state"
            ).absolutePath
        )
        assertThat(request.title).isEqualTo("Greeter Module")
        assertThat(request.zagentsVersion).isEqualTo("1.2-beta")
        assertThat(request.zagentsName).isEqualTo("greeter")
        assertThat(request.inputsValues?.get("email")?.jsonPrimitive?.content).isEqualTo("a@b.c")
        // The form host rode the seam: a mid-run room.ui.form has someone to ask.
        assertThat(forms.single()).isEqualTo(host)
    }

    @Test
    fun `a module's grants intersect before the vocabulary mapping`() = runBlocking<Unit> {
        // The row declares the full set; the chat granted storage+files+network
        // only — so chat and agent are absent from the wire BEFORE the mapping
        // is even asked, because the consent's meaning must not change with the
        // format. Storage collapses away under files (the row's files spelling
        // is install's storage+files pair), leaving the path-addressed sandbox.
        runtime().run(
            7L,
            module(),
            moduleBinding(granted = """["storage","files","network"]"""),
            PluginCallRequest.EVENT_PROBE
        )
        assertThat(calls.single().manifest.permissions).containsExactly("files", "network")
    }

    @Test
    fun `the module vocabulary maps every row name it has a spelling for`() = runBlocking<Unit> {
        // With everything granted, each row name finds its module spelling —
        // and the one that cannot (agent.tools, which a module never declares)
        // maps to nothing rather than to a name the host would refuse.
        runtime().run(
            7L,
            module(permissions = """["storage","files","network","chat.context","agent.generate","agent.tools"]"""),
            moduleBinding(granted = """["storage","files","network","chat.context","agent.generate","agent.tools"]"""),
            PluginCallRequest.EVENT_PROBE
        )
        assertThat(calls.single().manifest.permissions)
            .containsExactly("files", "network", "chat", "agent")
    }

    @Test
    fun `a module with only storage keeps the kv store, no files granted`() = runBlocking<Unit> {
        runtime().run(
            7L,
            module(permissions = """["storage"]"""),
            moduleBinding(granted = """["storage"]"""),
            PluginCallRequest.EVENT_PROBE
        )
        assertThat(calls.single().manifest.permissions).containsExactly("storage")
    }

    @Test
    fun `a plugin row keeps the row vocabulary and sends none of the module fields`() = runBlocking<Unit> {
        runtime().run(
            7L,
            plugin(),
            binding(inputs = """{"email":"a@b.c"}"""),
            PluginCallRequest.EVENT_ACTIVATE,
            // A caller that passes a prompt anyway: a plugin.json row was never
            // staged, so the field stays off the wire whatever it was handed.
            prompt = "hello there"
        )
        val request = calls.single()
        assertThat(request.format).isNull()
        assertThat(request.prompt).isNull()
        assertThat(request.timeoutSeconds).isNull()
        assertThat(request.stateRoot).isNull()
        assertThat(request.title).isNull()
        assertThat(request.zagentsVersion).isNull()
        assertThat(request.zagentsName).isNull()
        // Inputs are a shared feature: a plugin-format binding's stored answers
        // ride its calls exactly the same way a module's do.
        assertThat(request.inputsValues?.get("email")?.jsonPrimitive?.content).isEqualTo("a@b.c")
    }

    // -------------------------------------------------------- the form bridge

    /** One reverse `user_input` line, in the shape bridge.js writes. */
    private fun formLine(): JsonObject = buildJsonObject {
        put("id", "form-1")
        put("cmd", "user_input")
        put("token", "0123456789abcdef0123456789abcdef0123456789abcdef")
        put(
            "request",
            buildJsonObject {
                put("title", "Your account")
                put("description", "Needed for this chat")
                put(
                    "fields",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("key", "email")
                                put("type", "string")
                                put("title", "Email")
                                put("required", true)
                            }
                        )
                    }
                )
            }
        )
    }

    @Test
    fun `a completed form answers the values object the host normalized`() {
        val seen = mutableListOf<PluginFormRequest>()
        val host = PluginFormCall { request ->
            seen.add(request)
            PluginFormOutcome.Submitted(buildJsonObject { put("email", "a@b.c") })
        }
        // The call's own allowlist rides the handler — the dialog's network
        // context line, which the reverse line itself does not carry.
        val handler = reverseFormHandler(
            host,
            "zagents.greeter",
            "Greeter Module",
            listOf("api.github.com")
        )
        val answer = handler(formLine())
        // The reply the wire carries is the result member's shape exactly.
        assertThat(answer.toString()).isEqualTo("""{"values":{"email":"a@b.c"}}""")
        // The host saw the request the line carried — title, description and
        // fields parsed — bound to THIS call's plugin identity, because the
        // reverse line itself cannot say who is asking.
        val request = seen.single()
        assertThat(request.pluginId).isEqualTo("zagents.greeter")
        assertThat(request.pluginName).isEqualTo("Greeter Module")
        assertThat(request.context).isEqualTo(PluginFormContext.RUNTIME_REQUEST)
        assertThat(request.title).isEqualTo("Your account")
        assertThat(request.description).isEqualTo("Needed for this chat")
        assertThat(request.fields.single().key).isEqualTo("email")
        assertThat(request.fields.single().required).isTrue()
        assertThat(request.networkHosts).containsExactly("api.github.com")
    }

    @Test
    fun `a dismissed form raises the wire's own cancelled words`() {
        val handler = reverseFormHandler(
            PluginFormCall { _ -> PluginFormOutcome.Cancelled },
            "zagents.greeter",
            "Greeter Module"
        )
        val thrown = assertThrows(IllegalStateException::class.java) { handler(formLine()) }
        assertThat(thrown.message).isEqualTo(FORM_CANCELLED_ERROR)
    }

    @Test
    fun `an unanswered form raises the wire's own timeout words`() {
        val handler = reverseFormHandler(
            PluginFormCall { _ -> PluginFormOutcome.TimedOut },
            "zagents.greeter",
            "Greeter Module"
        )
        val thrown = assertThrows(IllegalStateException::class.java) { handler(formLine()) }
        assertThat(thrown.message).isEqualTo(FORM_TIMED_OUT_ERROR)
    }

    @Test
    fun `a host with no dialog raises the no-UI words`() {
        val handler = reverseFormHandler(
            PluginFormCall { _ -> PluginFormOutcome.NoUi },
            "zagents.greeter",
            "Greeter Module"
        )
        val thrown = assertThrows(IllegalStateException::class.java) { handler(formLine()) }
        assertThat(thrown.message).isEqualTo(ReverseHandlers.NO_INTERACTIVE_UI)
    }

    @Test
    fun `the app's reverse spellings are the wire's own words`() {
        // The node module keeps the canonical constants module-internal, so
        // these app-side spellings are pinned HERE to the same literals the
        // node protocol tests pin theirs to — a drift between the two sides
        // of the registry fails one of the two suites, not the wire silently.
        assertThat(REVERSE_GENERATE_CMD).isEqualTo("agent_generate")
        assertThat(REVERSE_USER_INPUT_CMD).isEqualTo("user_input")
        assertThat(FORM_WAIT_MS).isEqualTo(300_000L)
        // The wait the app-side form gate allows itself is the same number the
        // runtime's own user_input timeout carries — one patience, both sides.
        assertThat(ReverseHandlers.NO_INTERACTIVE_UI)
            .isEqualTo("No interactive UI is available for this call")
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
