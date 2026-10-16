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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The chat panel's plugin surface, driven with the grant dialog in the
 * middle: enabling a plugin that declares permissions must ASK before
 * anything is stored, accepting enables it through the repository and runs
 * its activate, and cancelling leaves nothing behind — the consent the
 * person granted is the only thing that ever reaches the wire.
 *
 * The repository is fake through its DAO seam (mockk over the Room
 * interfaces, the pattern the data-layer tests use); the runtime runs real
 * against a [PluginCall] fake, so the events the UI flow fires are the
 * events the wire would carry. The scope is Unconfined so the state class's
 * own launches run to completion inline, which is what a UI-less test of
 * launch-based code can honestly observe.
 *
 * Every test ends on a VOID-returning Truth call: a Kotlin function whose
 * last expression has a value compiles to a non-void method, and JUnit4 then
 * refuses the whole class. `runBlocking<Unit>` is the other half of that rule.
 */
class PluginUiStateTest {

    private val root: File = Files.createTempDirectory("plugin-ui-state").toFile()
    private val context: Context = mockk<Context>().apply { every { filesDir } returns root }

    private val calls = mutableListOf<PluginCallRequest>()

    /** The generator each event's call was handed, event for event with [calls]. */
    private val generators = mutableListOf<PluginGenerateCall?>()

    private val call = PluginCall { request, generate ->
        calls.add(request)
        generators.add(generate)
        when (request.event) {
            PluginCallRequest.EVENT_PROBE -> PluginCallResult(
                ok = true,
                commands = listOf("greet"),
                hooks = emptyList()
            )
            PluginCallRequest.EVENT_ACTIVATE -> PluginCallResult(
                ok = true,
                cards = listOf(
                    buildJsonObject {
                        put("title", "Hello World")
                        put("text", "on for this chat")
                    }
                )
            )
            PluginCallRequest.EVENT_COMMAND -> PluginCallResult(
                ok = true,
                cards = listOf(buildJsonObject { put("text", "greeting done") })
            )
            else -> PluginCallResult(ok = true)
        }
    }

    private val plugin = PluginEntity(
        id = "com.example.hello",
        name = "Hello World",
        version = "1.0.0",
        description = "",
        authorName = "Jane",
        license = "MIT",
        entry = "main.js",
        apiVersion = 1,
        permissionsJson = """["storage"]""",
        networkAllowJson = "[]",
        configSchemaJson = "[]",
        commandsJson = """[{"name":"greet","title":"Greet","description":""}]""",
        hooksJson = "[]",
        dirName = "com_example_hello",
        installedAt = 1L,
        updatedAt = 1L
    )

    private var stored: ChatPluginEntity? = null

    private fun repo(): PluginRepository {
        val pluginDao = mockk<PluginDao> { coEvery { all() } returns listOf(plugin) }
        val chatDao = mockk<ChatPluginDao> {
            coEvery { forSession(any()) } answers { listOfNotNull(stored) }
            coEvery { upsert(any()) } answers {
                stored = firstArg<ChatPluginEntity>()
                Unit
            }
        }
        val db = mockk<AppDatabase> {
            every { pluginDao() } returns pluginDao
            every { chatPluginDao() } returns chatDao
        }
        return PluginRepository(db, File(root, "plugins"), File(root, "plugin-data"))
    }

    private fun ui(
        session: Long? = 7L,
        generate: suspend (sessionId: Long) -> PluginGenerateCall? = { null }
    ): PluginUiState {
        val repo = repo()
        val runtime = AgentPluginRuntime(context, profile = null, call = call)
        val catalog = AgentPluginCatalog(repo, runtime)
        return PluginUiState(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repo = repo,
            runtime = runtime,
            catalog = catalog,
            sessionId = { session },
            createSession = { null },
            onCard = { _, _, _ -> },
            onNotice = { _, _ -> },
            history = { emptyList() },
            generate = generate
        )
    }

    @Test
    fun `enabling with ungranted permissions asks first and stores nothing`() = runBlocking<Unit> {
        val state = ui()
        state.setEnabled(plugin.id, true)
        awaitState { state.pendingGrant != null }
        assertThat(state.pendingGrant).isNotNull()
        assertThat(state.pendingGrant!!.pluginName).isEqualTo("Hello World")
        assertThat(state.pendingGrant!!.permissionLabels).hasSize(1)
        // Nothing was enabled and nothing reached the runtime: consent is
        // the only route to a binding.
        assertThat(stored).isNull()
        assertThat(calls).isEmpty()
    }

    @Test
    fun `accepted grants enable the plugin and run its activate`() = runBlocking<Unit> {
        val state = ui()
        state.setEnabled(plugin.id, true)
        awaitState { state.pendingGrant != null }
        state.submitGrants(listOf("storage"))
        assertThat(state.pendingGrant).isNull()
        // The state class launches on its own scope and the repository hops
        // to Dispatchers.IO mid-flight, so the test WAITS for the events
        // rather than assuming the launch finished before it read.
        awaitEvent("probe")
        awaitEvent("activate")
        assertThat(stored).isNotNull()
        assertThat(stored!!.enabled).isTrue()
        assertThat(stored!!.grantedPermissionsJson).contains("storage")
        val activate = calls.single { it.event == PluginCallRequest.EVENT_ACTIVATE }
        assertThat(activate.manifest.permissions).containsExactly("storage")
    }

    @Test
    fun `cancelling the dialog enables nothing`() = runBlocking<Unit> {
        val state = ui()
        state.setEnabled(plugin.id, true)
        state.cancelGrant()
        assertThat(state.pendingGrant).isNull()
        assertThat(stored).isNull()
        assertThat(calls).isEmpty()
    }

    @Test
    fun `a plugin command runs against the chat that sent it`() = runBlocking<Unit> {
        val state = ui()
        // The binding already exists with its grants, the way an earlier
        // enable left it.
        stored = ChatPluginEntity(
            id = 1L,
            sessionId = 7L,
            pluginId = plugin.id,
            enabled = true,
            grantedPermissionsJson = """["storage"]""",
            configJson = "",
            createdAt = 1L,
            updatedAt = 1L
        )
        val consumed = state.interceptChatCommand("/greet Room", sessionAtTap = 7L)
        assertThat(consumed).isTrue()
        val command = calls.single { it.event == PluginCallRequest.EVENT_COMMAND }
        assertThat(command.payload["name"].toString()).contains("greet")
        assertThat(command.payload["args"].toString()).contains("Room")
        // The command found its owner through the probe the catalogue ran.
        assertThat(calls.map { it.event }).contains("probe")
    }

    @Test
    fun `a slash word no enabled plugin owns is not consumed`() = runBlocking<Unit> {
        val state = ui()
        val consumed = state.interceptChatCommand("/definitely_not_a_plugin", sessionAtTap = 7L)
        assertThat(consumed).isFalse()
        // Probes ride ENABLED BINDINGS, and this chat has none: the honest no
        // costs two database reads and not one runtime start.
        assertThat(calls).isEmpty()
    }

    @Test
    fun `plain text is not a command and goes to the agent`() = runBlocking<Unit> {
        val state = ui()
        val consumed = state.interceptChatCommand("hello there", sessionAtTap = 7L)
        assertThat(consumed).isFalse()
        assertThat(calls).isEmpty()
    }

    @Test
    fun `plugins list opens the picker sheet`() = runBlocking<Unit> {
        val state = ui()
        val consumed = state.interceptChatCommand("/plugins", sessionAtTap = 7L)
        assertThat(consumed).isTrue()
        assertThat(state.pickerVisible).isTrue()
        state.dismissPicker()
        assertThat(state.pickerVisible).isFalse()
        // Listing a chat with no bindings asks the runtime nothing at all:
        // the catalogue's answer came from the database alone.
        assertThat(calls).isEmpty()
    }

    @Test
    fun `a plugin command with no chat to read is not a command yet`() = runBlocking<Unit> {
        val state = ui(session = null)
        stored = ChatPluginEntity(
            id = 1L,
            sessionId = 7L,
            pluginId = plugin.id,
            enabled = true,
            grantedPermissionsJson = """["storage"]""",
            configJson = "",
            createdAt = 1L,
            updatedAt = 1L
        )
        // No session at the tap means the command owners are unknown, so
        // "/greet" is not recognisable as a plugin command and the text
        // falls to the agent — the honest outcome on a chat that has no
        // bindings to consult.
        val consumed = state.interceptChatCommand("/greet Room", sessionAtTap = null)
        assertThat(consumed).isFalse()
        assertThat(calls).isEmpty()
    }

    @Test
    fun `a command's generator is the chat the command ran in`() = runBlocking<Unit> {
        // The generator is resolved per EVENT against the session the command
        // actually dispatched into — the wiring the controller's
        // pluginGeneratorFor supplies — so the test asserts the event carried
        // exactly the generator its chat answered with.
        val generator = PluginGenerateCall { _ -> "composed" }
        val state = ui(generate = { session ->
            if (session == 7L) generator else null
        })
        stored = ChatPluginEntity(
            id = 1L,
            sessionId = 7L,
            pluginId = plugin.id,
            enabled = true,
            grantedPermissionsJson = """["storage"]""",
            configJson = "",
            createdAt = 1L,
            updatedAt = 1L
        )
        val consumed = state.interceptChatCommand("/greet Room", sessionAtTap = 7L)
        assertThat(consumed).isTrue()
        awaitEvent("command")
        val commandIndex = calls.indexOfFirst { it.event == PluginCallRequest.EVENT_COMMAND }
        assertThat(commandIndex).isAtLeast(0)
        assertThat(generators[commandIndex]).isEqualTo(generator)
    }

    @Test
    fun `an activation can compose through the chat's own generator`() = runBlocking<Unit> {
        // A welcome message composed by the chat's AI is a legitimate
        // activate use, which is why enableNow passes the generator too.
        val generator = PluginGenerateCall { _ -> "welcome text" }
        val state = ui(generate = { _ -> generator })
        state.setEnabled(plugin.id, true)
        awaitState { state.pendingGrant != null }
        state.submitGrants(listOf("storage"))
        awaitEvent("activate")
        val activateIndex = calls.indexOfFirst { it.event == PluginCallRequest.EVENT_ACTIVATE }
        assertThat(activateIndex).isAtLeast(0)
        assertThat(generators[activateIndex]).isEqualTo(generator)
    }

    /** Waits up to 5 s for [condition], then lets the caller assert honestly. */
    private fun awaitState(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    /**
     * Waits up to 5 s for one [event] to reach the fake runtime. The state
     * class launches on its own scope and the repository hops to
     * Dispatchers.IO mid-flight, so a launched enable's events land
     * asynchronously to the test thread.
     */
    private fun awaitEvent(event: String) {
        awaitState { calls.any { it.event == event } }
        assertThat(calls.map { it.event }).contains(event)
    }
}
