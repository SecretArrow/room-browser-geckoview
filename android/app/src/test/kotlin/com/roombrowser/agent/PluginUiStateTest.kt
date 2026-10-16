package com.roombrowser.agent

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.ChatPluginDao
import com.roombrowser.data.db.ChatPluginEntity
import com.roombrowser.data.db.PluginDao
import com.roombrowser.data.db.PluginEntity
import com.roombrowser.data.repo.PluginRepository
import com.roombrowser.domain.plugins.PluginCard
import com.roombrowser.domain.plugins.PluginFormat
import com.roombrowser.node.PluginCallRequest
import com.roombrowser.node.PluginCallResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * The same driving covers the form gates and the staged activation: the
 * missing-inputs form stands between consent and the activate run (and
 * between a staged module and its first message), and a module enabled on a
 * fresh chat runs with the chat's first message rather than at the picker —
 * the state machine the dialog layer will render, tested here through the
 * state it raises and the events it fires.
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

    /** The notices the flows fired, so the cancel paths are asserted on their own words. */
    private val notices = mutableListOf<String>()

    /** The cards the flows appended: (plugin name, card) in arrival order. */
    private val appendedCards = mutableListOf<Pair<String, PluginCard>>()

    /** The generator each event's call was handed, event for event with [calls]. */
    private val generators = mutableListOf<PluginGenerateCall?>()

    private val call = PluginCall { request, generate, form ->
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
                output = "module message body",
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

    // Mutable for the one edit test that needs the plugin row to declare a
    // form; every other test reads it as the fixed plain row.
    private var plugin = PluginEntity(
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

    /** The module row the staged/gate tests drive — mutable because staging is. */
    private var moduleRow = module()

    /** The staged-flag writes the flows made, so "never staged" is assertable. */
    private val stagedWrites = mutableListOf<Pair<String, Boolean>>()

    /** The rows the flows upserted through the repository's install door. */
    private val upserts = mutableListOf<PluginEntity>()

    /** The canned VALID reply a composer returns — a draft the parser accepts. */
    private val composedReply = """Here is your plugin:

```json
{"id":"com.example.made","name":"Made Plugin","version":"0.1.0","description":"Composed draft.","api":1,"entry":"main.js","permissions":["storage","network"],"network":{"allow":["api.example.com"]},"commands":[{"name":"greet","title":"Greet","description":""}]}
```

```js
exports.commands = { greet: { run: () => 'hello' } };
```"""

    private fun module(
        permissions: String = """["storage"]""",
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

    private var stored: ChatPluginEntity? = null

    private fun repo(): PluginRepository {
        val pluginDao = mockk<PluginDao> {
            coEvery { all() } returns listOf(plugin, moduleRow)
            coEvery { byFormat(any()) } answers { listOfNotNull(moduleRow) }
            coEvery { setPromptStaged(any(), any()) } answers {
                stagedWrites.add(firstArg<String>() to secondArg<Boolean>())
                moduleRow = moduleRow.copy(promptStaged = secondArg<Boolean>())
                Unit
            }
            // The install door's row writer: a composed plugin's upsert is
            // the proof an install happened, and byId answers what upsert
            // wrote (null before that) — the repository's own replace rule.
            coEvery { byId(any()) } answers {
                upserts.lastOrNull { it.id == firstArg<String>() }
            }
            coEvery { upsert(any()) } answers {
                upserts.add(firstArg<PluginEntity>())
                Unit
            }
        }
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
        generate: suspend (sessionId: Long) -> PluginGenerateCall? = { null },
        compose: suspend (sessionId: Long) -> PluginComposeCall? = { null },
        freshChat: Boolean = false
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
            onCard = { _, card, name -> appendedCards.add(name to card) },
            onNotice = { text, _ -> notices.add(text) },
            history = { emptyList() },
            freshChat = { freshChat },
            generate = generate,
            compose = compose
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

    // ------------------------------------------------------- the plugin maker

    @Test
    fun `a bare make_plugins command answers with instructions`() = runBlocking<Unit> {
        val state = ui()
        val consumed = state.interceptChatCommand("/make_plugins", sessionAtTap = 7L)
        assertThat(consumed).isTrue()
        awaitState { notices.isNotEmpty() }
        // The instructive notice, verbatim shape: what the command asks for,
        // what gets reviewed, and a working example — and nothing composed.
        assertThat(notices.single()).startsWith(
            "/make_plugins <description> asks this chat's own AI to draft a plugin from " +
                "your description."
        )
        assertThat(notices.single()).contains("Example: /make_plugins a tiny plugin")
        assertThat(state.pendingCreation).isNull()
        assertThat(calls).isEmpty()
    }

    @Test
    fun `make_plugins composes through the chat's own composer and raises the creation form`() =
        runBlocking<Unit> {
            val composer = PluginComposeCall { _, _ -> composedReply }
            val state = ui(compose = { _ -> composer })
            val consumed = state.interceptChatCommand(
                "/make_plugins a tiny plugin that greets",
                sessionAtTap = 7L
            )
            assertThat(consumed).isTrue()
            awaitState { state.pendingCreation != null }
            assertThat(notices.any { it.contains("Composing a plugin from your description") })
                .isTrue()
            // The draft that rose is the parsed, validator-clean manifest —
            // the composer's raw reply never reaches state unvalidated.
            val pending = state.pendingCreation!!
            assertThat(pending.idText).isEqualTo("com.example.made")
            assertThat(pending.displayName).isEqualTo("Made Plugin")
            assertThat(pending.versionText).isEqualTo("0.1.0")
            assertThat(pending.entryPath).isEqualTo("main.js")
            assertThat(pending.declaredPermissions).containsExactly("storage", "network")
            assertThat(pending.declaredHosts).containsExactly("api.example.com")
            assertThat(pending.entrySource).contains("greet")
            // Nothing installed yet: the review is the only route to a row.
            assertThat(upserts).isEmpty()
        }

    @Test
    fun `an invalid first draft earns exactly one corrective retry`() = runBlocking<Unit> {
        val notes = mutableListOf<String?>()
        val composer = PluginComposeCall { _, retryNote ->
            notes.add(retryNote)
            if (retryNote == null) "no code blocks in here at all" else composedReply
        }
        val state = ui(compose = { _ -> composer })
        val consumed = state.interceptChatCommand("/make_plugins a plugin", sessionAtTap = 7L)
        assertThat(consumed).isTrue()
        awaitState { state.pendingCreation != null }
        // Exactly two attempts: the first, then the one retry whose input is
        // the parser's own error list — never a third.
        assertThat(notes).hasSize(2)
        assertThat(notes[0]).isNull()
        assertThat(notes[1]).startsWith("Your previous attempt was refused for these reasons:")
        assertThat(notes[1]).contains("- no fenced code blocks were found")
        assertThat(notices.any { it.contains("The first draft broke the plugin rules") }).isTrue()
        assertThat(state.pendingCreation).isNotNull()
    }

    @Test
    fun `a second invalid draft is the definitive notice`() = runBlocking<Unit> {
        val composer = PluginComposeCall { _, _ -> "still not a plugin" }
        val state = ui(compose = { _ -> composer })
        val consumed = state.interceptChatCommand("/make_plugins a plugin", sessionAtTap = 7L)
        assertThat(consumed).isTrue()
        awaitState { notices.any { it.startsWith("The chat's AI could not produce a valid plugin:") } }
        val definitive = notices.single { it.startsWith("The chat's AI could not produce a valid plugin:") }
        assertThat(definitive).contains("— no fenced code blocks were found")
        // Definitive: nothing pending, nothing installed, no third attempt.
        assertThat(state.pendingCreation).isNull()
        assertThat(upserts).isEmpty()
    }

    @Test
    fun `a chat with no composer is told so`() = runBlocking<Unit> {
        val state = ui(compose = { null })
        val consumed = state.interceptChatCommand("/make_plugins a plugin", sessionAtTap = 7L)
        assertThat(consumed).isTrue()
        awaitState {
            notices.any { it.startsWith("This chat has no AI to compose with") }
        }
        assertThat(notices.single { it.startsWith("This chat has no AI to compose with") })
            .contains("configure the chat's provider and model, then try /make_plugins again")
        assertThat(state.pendingCreation).isNull()
        assertThat(calls).isEmpty()
    }

    @Test
    fun `a description over the cap is refused before composing`() = runBlocking<Unit> {
        var resolutions = 0
        val state = ui(compose = { _ ->
            resolutions++
            PluginComposeCall { _, _ -> composedReply }
        })
        val consumed = state.interceptChatCommand(
            "/make_plugins ${"x".repeat(1001)}",
            sessionAtTap = 7L
        )
        assertThat(consumed).isTrue()
        assertThat(resolutions).isEqualTo(0)
        assertThat(notices.single())
            .isEqualTo("/make_plugins needs a description of at most 1000 characters.")
        assertThat(state.pendingCreation).isNull()
    }

    @Test
    fun `submitting the creation form installs the plugin and says so`() = runBlocking<Unit> {
        val state = ui(compose = { _ -> PluginComposeCall { _, _ -> composedReply } })
        state.interceptChatCommand("/make_plugins a tiny plugin that greets", sessionAtTap = 7L)
        awaitState { state.pendingCreation != null }
        // The form's answer: storage only — network UNCHECKED — so the row
        // installs with the reviewed set, not the draft's asks.
        state.submitCreation(
            name = "Reviewed",
            description = "Reviewed and narrowed.",
            permissions = listOf("storage"),
            hosts = emptyList()
        )
        awaitState { upserts.isNotEmpty() }
        val row = upserts.single()
        assertThat(row.id).isEqualTo("com.example.made")
        assertThat(row.name).isEqualTo("Reviewed")
        assertThat(row.description).isEqualTo("Reviewed and narrowed.")
        assertThat(row.version).isEqualTo("0.1.0")
        assertThat(row.permissionsJson).isEqualTo("""["storage"]""")
        assertThat(row.networkAllowJson).isEqualTo("[]")
        assertThat(row.entry).isEqualTo("main.js")
        // The install went through the REAL zipper and the REAL installer:
        // the directory materialized with both composed files on disk.
        assertThat(File(File(root, "plugins"), "com_example_made").isDirectory).isTrue()
        assertThat(File(File(root, "plugins"), "com_example_made/main.js").readText())
            .contains("greet")
        assertThat(File(File(root, "plugins"), "com_example_made/plugin.json").readText())
            .contains("\"name\":\"Reviewed\"")
        assertThat(state.pendingCreation).isNull()
        awaitState {
            notices.any { it.contains("installed. Review or edit its files from Manage plugins") }
        }
        assertThat(
            notices.any {
                it.contains(
                    "Reviewed 0.1.0 installed. Review or edit its files from Manage plugins, " +
                        "then turn it on for this chat with /plugins."
                )
            }
        ).isTrue()
    }

    @Test
    fun `cancelling the creation installs nothing`() = runBlocking<Unit> {
        val state = ui(compose = { _ -> PluginComposeCall { _, _ -> composedReply } })
        state.interceptChatCommand("/make_plugins a tiny plugin that greets", sessionAtTap = 7L)
        awaitState { state.pendingCreation != null }
        state.cancelCreation()
        assertThat(state.pendingCreation).isNull()
        assertThat(upserts).isEmpty()
        assertThat(notices.single { it.contains("discarded") })
            .isEqualTo("The plugin draft was discarded — nothing was installed.")
        // The install door was never opened: no directory was materialized.
        assertThat(File(root, "plugins").listFiles().orEmpty().map { it.name })
            .doesNotContain("com_example_made")
    }

    // ---------------------------------------------------- the activation gate

    @Test
    fun `the consent request carries the form half a combined dialog renders`() = runBlocking<Unit> {
        // A module's consent request carries everything the combined dialog
        // needs: the format the title and button spell by, the declared names
        // (the bullets' sentences cannot be mapped back past the module-only
        // `files`), the form fields, and the stored answers as the starting
        // state.
        val state = ui()
        state.setEnabled(moduleRow.id, true)
        awaitState { state.pendingGrant != null }
        val pending = state.pendingGrant!!
        assertThat(pending.format).isEqualTo(PluginFormat.ZAGENTS)
        assertThat(pending.permissionNames).containsExactly("storage")
        assertThat(pending.fields.single().key).isEqualTo("email")
        assertThat(pending.existingValues).isEmpty()
        state.cancelGrant()
        // A plugin.json row's request is the plain permission dialog this
        // build has always had: the default format, no fields to edit.
        state.setEnabled(plugin.id, true)
        awaitState { state.pendingGrant?.pluginId == plugin.id }
        val plain = state.pendingGrant!!
        assertThat(plain.format).isEqualTo(PluginFormat.PLUGIN)
        assertThat(plain.fields).isEmpty()
        assertThat(plain.permissionNames).containsExactly("storage")
    }

    @Test
    fun `enabling a plugin with required inputs raises the form before the activation`() = runBlocking<Unit> {
        moduleRow = module(permissions = """["storage","network"]""")
            .copy(networkAllowJson = """["api.example.com"]""")
        val state = ui()
        state.setEnabled(moduleRow.id, true)
        awaitState { state.pendingGrant != null }
        state.submitGrants(listOf("storage"))
        awaitState { state.pendingForm != null }
        // The form stands between the consent and the run: the plugin is
        // enabled and bound, but nothing has activated yet.
        assertThat(state.pendingForm!!.pluginName).isEqualTo("Greeter Module")
        assertThat(state.pendingForm!!.context).isEqualTo(PluginFormContext.ACTIVATION)
        assertThat(state.pendingForm!!.fields.single().key).isEqualTo("email")
        assertThat(state.pendingForm!!.fields.single().required).isTrue()
        // The row's allowlist rides the raised form — the dialog's network
        // context line, frozen at the moment the gate raised it.
        assertThat(state.pendingForm!!.networkHosts).containsExactly("api.example.com")
        assertThat(calls.none { it.event == PluginCallRequest.EVENT_ACTIVATE }).isTrue()
    }

    @Test
    fun `a completed activation form stores its values and runs the activation`() = runBlocking<Unit> {
        val state = ui()
        state.setEnabled(moduleRow.id, true)
        awaitState { state.pendingGrant != null }
        state.submitGrants(listOf("storage"))
        awaitState { state.pendingForm != null }
        state.submitForm(buildJsonObject { put("email", "a@b.c") })
        awaitEvent("activate")
        assertThat(state.pendingForm).isNull()
        val activate = calls.single { it.event == PluginCallRequest.EVENT_ACTIVATE }
        assertThat(activate.format).isEqualTo(PluginCallRequest.FORMAT_ZAGENTS)
        assertThat(activate.inputsValues?.get("email")?.jsonPrimitive?.content).isEqualTo("a@b.c")
        // The answers were stored on the binding, so the next run asks nobody.
        assertThat(stored?.inputsValuesJson).contains("a@b.c")
        // An immediate activation was never staged.
        assertThat(stagedWrites).doesNotContain(moduleRow.id to true)
    }

    @Test
    fun `cancelling the activation form activates nothing and says so`() = runBlocking<Unit> {
        // A stale staging from an earlier fresh-chat enable rides along: the
        // cancel clears it, so the module cannot wake up mid-chat unasked.
        moduleRow = module().copy(promptStaged = true)
        val state = ui()
        state.setEnabled(moduleRow.id, true)
        awaitState { state.pendingGrant != null }
        state.submitGrants(listOf("storage"))
        awaitState { state.pendingForm != null }
        state.cancelForm()
        awaitState { notices.any { it.contains("was not run — its input form was not completed") } }
        assertThat(calls.none { it.event == PluginCallRequest.EVENT_ACTIVATE }).isTrue()
        assertThat(moduleRow.promptStaged).isFalse()
    }

    // ---------------------------------------------------- the staged activation

    @Test
    fun `a module enabled on a fresh chat stages instead of activating`() = runBlocking<Unit> {
        val state = ui(freshChat = true)
        state.setEnabled(moduleRow.id, true)
        awaitState { state.pendingGrant != null }
        // The combined consent dialog: the grants AND the form's answers in
        // one sheet, stored together at the moment of consent.
        state.submitGrants(listOf("storage"), buildJsonObject { put("email", "a@b.c") })
        awaitState { stagedWrites.contains(moduleRow.id to true) }
        assertThat(calls.none { it.event == PluginCallRequest.EVENT_ACTIVATE }).isTrue()
        assertThat(stored?.inputsValuesJson).contains("a@b.c")
        assertThat(notices.any { it.contains("runs with this chat's first message") }).isTrue()
    }

    @Test
    fun `the first message runs the staged module with the message as its prompt`() = runBlocking<Unit> {
        stored = moduleBinding()
        moduleRow = module().copy(promptStaged = true)
        val state = ui()
        state.runStagedActivations(7L, "hello there")
        val activate = calls.single { it.event == PluginCallRequest.EVENT_ACTIVATE }
        assertThat(activate.prompt).isEqualTo("hello there")
        assertThat(activate.format).isEqualTo(PluginCallRequest.FORMAT_ZAGENTS)
        assertThat(activate.inputsValues?.get("email")?.jsonPrimitive?.content).isEqualTo("a@b.c")
        // The flag cleared with the run: once per activation.
        assertThat(stagedWrites).contains(moduleRow.id to false)
        // The module's composed message body joined the conversation as its
        // own card, beside the cards its activate pushed — the message order
        // the controller writes around this run is user → module → assistant.
        assertThat(appendedCards.map { it.first }).contains("Greeter Module")
        assertThat(appendedCards.mapNotNull { it.second.text }).contains("module message body")
    }

    @Test
    fun `a staged module with unanswered inputs asks at the first message`() = runBlocking<Unit> {
        stored = moduleBinding(inputs = null)
        moduleRow = module(permissions = """["storage","network"]""")
            .copy(promptStaged = true, networkAllowJson = """["api.example.com"]""")
        val state = ui()
        // Unconfined — the file's own scope idiom — because the staged run
        // suspends on IO mid-flight and must keep going while THIS coroutine
        // busy-waits for the dialog state; a plain launch on runBlocking's
        // event loop would sit queued behind the wait.
        val run = CoroutineScope(Dispatchers.Unconfined).launch {
            state.runStagedActivations(7L, "hello there")
        }
        awaitState { state.pendingForm != null }
        assertThat(state.pendingForm!!.context).isEqualTo(PluginFormContext.ACTIVATION)
        assertThat(state.pendingForm!!.networkHosts).containsExactly("api.example.com")
        state.submitForm(buildJsonObject { put("email", "a@b.c") })
        run.join()
        val activate = calls.single { it.event == PluginCallRequest.EVENT_ACTIVATE }
        assertThat(activate.prompt).isEqualTo("hello there")
        assertThat(stored?.inputsValuesJson).contains("a@b.c")
        assertThat(moduleRow.promptStaged).isFalse()
    }

    @Test
    fun `later messages do not re-run a module that already ran`() = runBlocking<Unit> {
        stored = moduleBinding()
        moduleRow = module().copy(promptStaged = true)
        val state = ui()
        state.runStagedActivations(7L, "the first message")
        assertThat(calls.count { it.event == PluginCallRequest.EVENT_ACTIVATE }).isEqualTo(1)
        // The flag the first run cleared keeps the second message from paying
        // the module's once again.
        state.runStagedActivations(7L, "the second message")
        assertThat(calls.count { it.event == PluginCallRequest.EVENT_ACTIVATE }).isEqualTo(1)
    }

    @Test
    fun `a module staged for another chat does not run on this one`() = runBlocking<Unit> {
        moduleRow = module().copy(promptStaged = true)
        val state = ui()
        state.runStagedActivations(7L, "hello there")
        // No binding for THIS chat means the flag belongs to another chat's
        // staging — left for that chat's own first message.
        assertThat(calls.none { it.event == PluginCallRequest.EVENT_ACTIVATE }).isTrue()
        assertThat(moduleRow.promptStaged).isTrue()
    }

    @Test
    fun `a plugin row is never staged, even on a fresh chat`() = runBlocking<Unit> {
        val state = ui(freshChat = true)
        state.setEnabled(plugin.id, true)
        awaitState { state.pendingGrant != null }
        state.submitGrants(listOf("storage"))
        awaitEvent("activate")
        // Staging is the module format's own behavior; the plugin.json row
        // activated immediately, exactly as it always has.
        assertThat(stagedWrites).isEmpty()
    }

    // -------------------------------------------------------- the form state

    @Test
    fun `a form nobody answers in time retires itself`() = runBlocking<Unit> {
        val state = ui()
        val outcome = state.askForm(
            PluginFormRequest(
                pluginId = moduleRow.id,
                pluginName = moduleRow.name,
                context = PluginFormContext.RUNTIME_REQUEST,
                fields = emptyList()
            ),
            timeoutMs = 50
        )
        assertThat(outcome).isEqualTo(PluginFormOutcome.TimedOut)
        // No dialog outlives its raiser.
        assertThat(state.pendingForm).isNull()
    }

    @Test
    fun `a host with no chat on screen answers no-UI`() = runBlocking<Unit> {
        val state = ui(session = null)
        val outcome = state.askForm(
            PluginFormRequest(
                pluginId = moduleRow.id,
                pluginName = moduleRow.name,
                context = PluginFormContext.RUNTIME_REQUEST,
                fields = emptyList()
            )
        )
        assertThat(outcome).isEqualTo(PluginFormOutcome.NoUi)
    }

    @Test
    fun `an invalid submit keeps the form up and the raiser waiting`() = runBlocking<Unit> {
        stored = moduleBinding()
        val state = ui()
        val run = CoroutineScope(Dispatchers.Unconfined).launch { state.askForm(activationRequest()) }
        awaitState { state.pendingForm != null }
        // A submit that does not pass the single validator changes nothing: the
        // form stays up — the backstop behind a dialog that forgot to disable
        // its own button.
        state.submitForm(buildJsonObject { put("email", "") })
        assertThat(state.pendingForm).isNotNull()
        state.submitForm(buildJsonObject { put("email", "a@b.c") })
        run.join()
        assertThat(state.pendingForm).isNull()
    }

    @Test
    fun `a blank secret submits as the value already stored`() = runBlocking<Unit> {
        // The keep-old rule, through the state's own submit: the form showed
        // dots and "leave blank to keep", so blank means keep — the normalized
        // value the raiser reads is the stored one, not an empty string.
        moduleRow = module(
            inputs = """[{"key":"token","type":"secret","title":"Token","description":"","required":true}]"""
        )
        stored = moduleBinding(inputs = """{"token":"kept-token"}""")
        val state = ui()
        var outcome: PluginFormOutcome? = null
        val run = CoroutineScope(Dispatchers.Unconfined).launch { outcome = state.askForm(activationRequest()) }
        awaitState { state.pendingForm != null }
        state.submitForm(buildJsonObject { put("token", "") })
        run.join()
        val asked = outcome as PluginFormOutcome.Submitted
        assertThat(asked.values["token"]?.jsonPrimitive?.content).isEqualTo("kept-token")
    }

    // ----------------------------------------------------------- edit inputs

    @Test
    fun `edit inputs re-asks the stored answers and runs the module again`() = runBlocking<Unit> {
        moduleRow = module(permissions = """["storage","network"]""")
            .copy(networkAllowJson = """["api.example.com"]""")
        stored = moduleBinding()
        val state = ui()
        state.editInputs(moduleRow.id)
        awaitState { state.pendingForm != null }
        // The edit starts from what the chat already stored — the keep-old
        // rule's "old", and the dialog's pre-fill — and carries the row's
        // allowlist for the network context line.
        assertThat(state.pendingForm!!.context).isEqualTo(PluginFormContext.ACTIVATION)
        assertThat(state.pendingForm!!.existingValues["email"]?.jsonPrimitive?.content)
            .isEqualTo("a@b.c")
        assertThat(state.pendingForm!!.networkHosts).containsExactly("api.example.com")
        state.submitForm(buildJsonObject { put("email", "new@x.y") })
        awaitEvent("activate")
        // The new answers were stored and rode the re-run — "Save and run".
        val activate = calls.single { it.event == PluginCallRequest.EVENT_ACTIVATE }
        assertThat(activate.inputsValues?.get("email")?.jsonPrimitive?.content).isEqualTo("new@x.y")
        assertThat(stored?.inputsValuesJson).contains("new@x.y")
        assertThat(notices.any { it.contains("ran again with the new inputs") }).isTrue()
    }

    @Test
    fun `edit inputs saves a native plugin's answers without running it`() = runBlocking<Unit> {
        plugin = plugin.copy(
            inputsJson = """[{"key":"email","type":"string","title":"Email","description":"","required":true}]"""
        )
        stored = ChatPluginEntity(
            id = 1L,
            sessionId = 7L,
            pluginId = plugin.id,
            enabled = true,
            grantedPermissionsJson = """["storage"]""",
            configJson = "",
            inputsValuesJson = """{"email":"a@b.c"}""",
            createdAt = 1L,
            updatedAt = 1L
        )
        val state = ui()
        state.editInputs(plugin.id)
        awaitState { state.pendingForm != null }
        state.submitForm(buildJsonObject { put("email", "new@x.y") })
        awaitState { stored?.inputsValuesJson?.contains("new@x.y") == true }
        // A native plugin's edit only saves — its activations are not
        // per-answer, so "Save" is the whole promise.
        assertThat(calls.none { it.event == PluginCallRequest.EVENT_ACTIVATE }).isTrue()
        assertThat(notices.any { it.contains("inputs were saved") }).isTrue()
    }

    @Test
    fun `cancelling an edit changes nothing`() = runBlocking<Unit> {
        stored = moduleBinding()
        val state = ui()
        state.editInputs(moduleRow.id)
        awaitState { state.pendingForm != null }
        state.cancelForm()
        awaitState { notices.any { it.contains("inputs were not changed") } }
        assertThat(stored?.inputsValuesJson).contains("a@b.c")
        assertThat(calls.none { it.event == PluginCallRequest.EVENT_ACTIVATE }).isTrue()
    }

    /** One activation-gate request against the module row the test configured. */
    private fun activationRequest(): PluginFormRequest {
        val schema = PluginForms.schemaOf(moduleRow)
        return PluginFormRequest(
            pluginId = moduleRow.id,
            pluginName = moduleRow.name,
            context = PluginFormContext.ACTIVATION,
            fields = schema.fields,
            existingValues = PluginForms.storedValues(stored?.inputsValuesJson)
        )
    }

    /** The module's binding: enabled for this chat, with the form answers it stored. */
    private fun moduleBinding(inputs: String? = """{"email":"a@b.c"}""") = ChatPluginEntity(
        id = 2L,
        sessionId = 7L,
        pluginId = "zagents.greeter",
        enabled = true,
        grantedPermissionsJson = """["storage"]""",
        configJson = "",
        inputsValuesJson = inputs,
        createdAt = 1L,
        updatedAt = 1L
    )

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
