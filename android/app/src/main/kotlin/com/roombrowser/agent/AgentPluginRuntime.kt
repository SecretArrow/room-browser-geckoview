package com.roombrowser.agent

import android.content.Context
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.data.db.AgentMessageEntity
import com.roombrowser.data.db.ChatPluginEntity
import com.roombrowser.data.db.PluginEntity
import com.roombrowser.data.repo.PluginRepository
import com.roombrowser.data.repo.decodePermissions
import com.roombrowser.data.repo.toManifest
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.agent.UntrustedContent
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.UserAgents
import com.roombrowser.domain.plugins.PluginCard
import com.roombrowser.domain.plugins.PluginConfigField
import com.roombrowser.domain.plugins.PluginFormat
import com.roombrowser.domain.plugins.PluginInputField
import com.roombrowser.domain.plugins.PluginInputsSchema
import com.roombrowser.domain.plugins.PluginPermissions
import com.roombrowser.domain.plugins.ZAgentsManifest
import com.roombrowser.node.NodeRunner
import com.roombrowser.node.PluginCallRequest
import com.roombrowser.node.PluginCallResult
import com.roombrowser.node.PluginChatEntry
import com.roombrowser.node.PluginGenerateRequest
import com.roombrowser.node.PluginWireManifest
import com.roombrowser.node.ReverseHandlers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Which moment of a plugin's life an input form is being asked at — the one
 * thing the dialog needs to know besides the fields themselves, because the
 * three gates title and footnote themselves differently (an enable-time form
 * is consent, a tool-call form is a missing piece the model was promised, a
 * runtime form is the plugin's own mid-run question).
 */
enum class PluginFormContext {
    /** Enabling (or re-running) a plugin: the form stands between consent and the activate run. */
    ACTIVATION,

    /** The agent called a tool whose required inputs the chat never answered. */
    TOOL_CALL,

    /** The plugin's own code asked, mid-run, over the reverse `user_input` channel. */
    RUNTIME_REQUEST,

    /**
     * The person's own message (or plugin command) is HELD until the inputs
     * are answered: this chat's enabled bindings declared required inputs the
     * chat never stored, so the turn does not write the user's row and does
     * not call the model until the form comes back complete — the chat is not
     * worked without the input its module was promised.
     */
    SEND
}

/**
 * One input form the gates raise: which plugin, which fields, at which
 * moment. [fields] is the schema as the row's `inputs_json` column (or the
 * runtime request's wire fields) parsed it — the same [PluginInputField]
 * model the validator reads, so the dialog that renders this request and
 * the gate that raised it agree on one shape.
 *
 * [title] and [description] are the RUNTIME request's own headline and
 * helper line, as the plugin's code wrote them; the app-raised contexts
 * leave them null and title themselves off [pluginName] and [context].
 * [existingValues] is what this chat already stored for these fields — the
 * keep-old secret rule's "old", and what an edit dialog pre-fills.
 * [networkHosts] is the row's allowlist as the gate read it — the dialog's
 * permission-context line when the plugin holds network, frozen at the
 * moment the form was raised so an install changing under a dialog cannot
 * change what the person is looking at.
 */
data class PluginFormRequest(
    val pluginId: String,
    val pluginName: String,
    val context: PluginFormContext,
    val fields: List<PluginInputField>,
    val title: String? = null,
    val description: String? = null,
    val existingValues: JsonObject = JsonObject(emptyMap()),
    val networkHosts: List<String> = emptyList()
) {
    companion object {
        /**
         * The request a reverse `user_input` line carries: its `request`
         * member's title, description and fields, bound to the plugin the
         * call belongs to. Parsed with [PluginInputsSchema.parse]'s lenient
         * rule — the host has already refused the shapes this cannot render,
         * so a field that reads as absent here is a broken line, not a form
         * the app should refuse to show. [networkHosts] is the CALL's own
         * allowlist, which the caller hands because the line itself does not
         * carry it.
         */
        fun of(
            line: JsonObject,
            pluginId: String,
            pluginName: String,
            networkHosts: List<String> = emptyList()
        ): PluginFormRequest {
            val request = line["request"] as? JsonObject ?: JsonObject(emptyMap())
            return PluginFormRequest(
                pluginId = pluginId,
                pluginName = pluginName,
                context = PluginFormContext.RUNTIME_REQUEST,
                fields = PluginInputsSchema.parse(request["fields"]).fields,
                title = request.string("title"),
                description = request.string("description"),
                networkHosts = networkHosts
            )
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

/**
 * What a raised form came back as — the one answer shape all three gates
 * read. [Submitted]'s values are the dialog's answers already validated and
 * normalized by the single validator, so a gate that sees [Submitted] may
 * store and run without re-asking anything; the other three are refusals
 * each gate words in its own context (a notice, a tool refusal, the
 * runtime's error envelope).
 */
sealed class PluginFormOutcome {

    /** The person completed the form: the values are valid and normalized. */
    data class Submitted(val values: JsonObject) : PluginFormOutcome()

    /** The person dismissed or cancelled the dialog. */
    object Cancelled : PluginFormOutcome()

    /** Nobody answered within the wait. */
    object TimedOut : PluginFormOutcome()

    /** No dialog for this call — a probe, a background run, a wrong context. */
    object NoUi : PluginFormOutcome()
}

/**
 * The host that raises the input form and awaits the person's answer — the
 * chat panel's own plugin surface ([PluginUiState]) in production, a fake in
 * the tests. The three gates call it with a [PluginFormRequest] and read a
 * [PluginFormOutcome]; a host that cannot ask (nobody on screen) answers
 * [PluginFormOutcome.NoUi] rather than throwing, because "no dialog here" is
 * an answer the caller has honest words for.
 *
 * The same shape as [PluginGenerateCall] on purpose: one suspend call, one
 * answer, no partial states — the runtime's reverse channel and the gates'
 * interactive waits are both just "ask and await".
 */
fun interface PluginFormCall {
    suspend operator fun invoke(request: PluginFormRequest): PluginFormOutcome
}

/** The wire's words for a form the person dismissed. */
internal const val FORM_CANCELLED_ERROR = "Form was cancelled"

/** The wire's words for a form nobody answered in time. */
internal const val FORM_TIMED_OUT_ERROR = "Form timed out"

/**
 * How long the app side waits for a raised form before it answers for the
 * person — 300 s, the same number the runtime's own `user_input` timeout
 * carries (`NodeProtocol.REVERSE_USER_INPUT_TIMEOUT_MS`, module-internal on
 * the node side, so this is the app's own spelling of the wire's constant).
 * A dialog awaiting a person is the one wait in this feature allowed to take
 * minutes, and the module's compute budget is PAUSED for every second of it.
 */
internal const val FORM_WAIT_MS = 300_000L

/**
 * The status line the contended-wait hook is for: what a chat should be
 * told when its call found the runtime's one-call gate held by another —
 * most often a plugin form dialog in another chat, which can hold it for
 * [FORM_WAIT_MS]. The turn is not stuck, it is QUEUED, and the person
 * deserves to be told which. Spelled here (rather than at whichever surface
 * wires the hook first) so every surface that adopts it says the same words;
 * the holder can be any run or plugin call, but "a plugin call" is the case
 * a person can act on — close the other chat's dialog.
 */
internal const val RUNTIME_CONTENDED_NOTICE = "another plugin call is running — waiting…"

/**
 * The reverse command names the app side registers when a call wires a form
 * host. The node module owns the canonical constants but keeps them
 * module-internal, so these are the app's spellings of the same two strings
 * the wire defines — the registry matches by value, which is all it ever
 * does, and a drift here is caught by the JVM tests that drive the handler.
 */
internal const val REVERSE_GENERATE_CMD = "agent_generate"

internal const val REVERSE_USER_INPUT_CMD = "user_input"

/**
 * One plugin event's route to the runtime — [NodeRunner.plugin] in production,
 * a fake in the tests. A seam of this shape rather than a NodeRunner parameter
 * because NodeRunner is a final class that owns a real Android context; every
 * other seam in this package ([AgentNodeRuntime]) hides its runner the same
 * way, and a plugin's failure must be testable without starting one.
 *
 * [generate] is the chat's own text generator, or null when this call has
 * none. [form] is the host a runtime form request (`room.ui.form`, a module's
 * `zagents.ui.form`) is bridged to while the event runs, or null when this
 * call has nobody to ask — the probe, the deactivates and every unattended
 * path pass none, and the runtime then answers the plugin in its own honest
 * no-dialog words. The production route wraps the two into the reverse
 * registry ([reverseHandlers]) that answers both commands while the plugin
 * runs; null on both carries no registry at all, exactly as a null generator
 * always has.
 *
 * [onContended] is the seam the honest queue notice rides: it fires once,
 * before the wait, whenever this call found another one already holding the
 * runtime's one-call gate — wire it to the chat's status line with
 * [RUNTIME_CONTENDED_NOTICE] (the controller's `setStatus`). Null (every
 * caller today) keeps the silent wait this route always had.
 */
fun interface PluginCall {
    suspend operator fun invoke(
        request: PluginCallRequest,
        generate: PluginGenerateCall?,
        form: PluginFormCall?
    ): PluginCallResult

    companion object {
        /**
         * The production route: the one process-wide runner, one call at a
         * time — and a wait behind a busy runner that is cancellable, so a
         * stopped turn stops instead of parking its thread until the holder
         * (a form dialog can hold the gate for minutes) resolves.
         */
        fun throughRunner(
            context: Context,
            onContended: (() -> Unit)? = null
        ): PluginCall {
            val runner = NodeRunner(context, onContended)
            return PluginCall { request, generate, form ->
                // The legacy shorthand when no form host is wired: byte-for-byte
                // today's route, so every call without a dialog behaves exactly
                // as it did before forms existed. The registry path lights up
                // only when there is someone to show one to.
                if (form == null) {
                    runner.plugin(request, generate?.let(::reverseGenerateHandler))
                } else {
                    runner.plugin(request, reverseHandlers(request, generate, form))
                }
            }
        }
    }
}

/**
 * One `room.agent.generate` call — the plugin's request for THIS chat's own
 * AI to write one piece of text, riding the reverse channel while its own
 * command or tool runs. Throws on failure; the thrown words surface in the
 * reverse line's error envelope, which is the only place the plugin can
 * read them. The [PluginGenerateRequest] it is handed is already clamped by
 * [PluginGenerateRequest.of], so a generator is never asked to police the
 * wire's caps itself.
 */
fun interface PluginGenerateCall {
    suspend operator fun invoke(request: PluginGenerateRequest): String
}

/**
 * The blocking reverse handler one [PluginGenerateCall] becomes on
 * NodeClient's read loop: parse the request out of the reverse line, run the
 * generator under [AgentPluginRuntime.GENERATE_BUDGET_MS], and hand back the
 * `{"text": …}` object the reply envelope carries as its result.
 *
 * THIS CATCHES NOTHING on purpose. A generator that fails, times out or
 * refuses is NodeClient's to answer — it turns any throw into the reverse
 * line's own error envelope with the thrown words — and a wrap that caught
 * here would have to invent words of its own instead. runBlocking is safe
 * because the call already sits on a Dispatchers.IO thread inside
 * [AgentPluginRuntime.run]; the generation suspends on the provider's own
 * threads, and the runtime's one-call gate is held by THIS thread, which
 * the generation never re-enters — and since the gate became a coroutine
 * mutex (8-f) that is a load-bearing rule rather than a footnote: a
 * re-entry would deadlock where the old ReentrantLock silently re-entered.
 * It holds because a generation only ever rides the gateway's own HTTP call
 * — no NodeRunner, no NodeClient, no catalogue read.
 *
 * Internal rather than private: [PluginCall.throughRunner] wires it in, and
 * the runtime's test wraps a fake generator with it — the wrap is part of
 * the seam's contract, so the test drives the production code rather than a
 * copy of it.
 */
internal fun reverseGenerateHandler(
    generate: PluginGenerateCall
): (JsonObject) -> JsonObject = { line ->
    runBlocking {
        val text = withTimeout(AgentPluginRuntime.GENERATE_BUDGET_MS) {
            generate(PluginGenerateRequest.of(line))
        }
        buildJsonObject { put("text", text) }
    }
}

/**
 * The reverse registry one call's two interactive halves become: the
 * generator under the generate command (as today), the form host under
 * `user_input`. Built only when a form host exists — a call with a generator
 * and no host keeps today's legacy route untouched — and registered by the
 * app's own spellings of the command names (see [REVERSE_GENERATE_CMD]),
 * which the node module's registry matches by value.
 *
 * The form handler is bound to THIS call's plugin identity: a reverse line
 * does not carry it, and the dialog it raises must say who is asking.
 */
internal fun reverseHandlers(
    request: PluginCallRequest,
    generate: PluginGenerateCall?,
    form: PluginFormCall
): ReverseHandlers {
    val handlers = ReverseHandlers()
    if (generate != null) {
        handlers.register(REVERSE_GENERATE_CMD, reverseGenerateHandler(generate))
    }
    handlers.register(
        REVERSE_USER_INPUT_CMD,
        reverseFormHandler(
            form,
            request.manifest.id,
            request.manifest.name,
            // The call's own allowlist — the dialog's network context line —
            // which the reverse line itself does not carry.
            request.manifest.networkAllow
        )
    )
    return handlers
}

/**
 * The blocking reverse handler one [PluginFormCall] becomes on NodeClient's
 * read loop: parse the `user_input` line into a [PluginFormRequest], hand it
 * to the host, and answer `{"values": …}` when the person completed it.
 *
 * EVERY OTHER OUTCOME RAISES, in the wire's exact words — cancelled, timed
 * out, no dialog — because the reverse line's error envelope is the only
 * channel those travel on, and a wrap that caught here would have to invent
 * words of its own instead. This is the same deliberate rule
 * [reverseGenerateHandler] follows: NodeClient turns any throw into the
 * envelope, so the throw IS the answer.
 *
 * runBlocking is safe for the same reason the generate handler's is: the call
 * already sits on a Dispatchers.IO thread inside [AgentPluginRuntime.run],
 * the dialog completes the deferred on the main thread, and the runtime's
 * one-call gate is held by THIS thread, which the dialog never re-enters —
 * load-bearing since the gate became a coroutine mutex (8-f), because a
 * re-entry would deadlock where the old ReentrantLock silently re-entered.
 * It holds because the form host only ever rides its pending form's
 * deferred: no NodeRunner, no NodeClient, no catalogue read.
 */
internal fun reverseFormHandler(
    form: PluginFormCall,
    pluginId: String,
    pluginName: String,
    networkHosts: List<String> = emptyList()
): (JsonObject) -> JsonObject = { line ->
    runBlocking {
        when (val outcome = form(PluginFormRequest.of(line, pluginId, pluginName, networkHosts))) {
            is PluginFormOutcome.Submitted -> buildJsonObject { put("values", outcome.values) }
            PluginFormOutcome.Cancelled -> error(FORM_CANCELLED_ERROR)
            PluginFormOutcome.TimedOut -> error(FORM_TIMED_OUT_ERROR)
            PluginFormOutcome.NoUi -> error(ReverseHandlers.NO_INTERACTIVE_UI)
        }
    }
}

/**
 * One tool event's whole outcome. The model's half is the fenced
 * [result]; the user's half is [cards] — a plugin may push cards with
 * `room.ui.card` while its tool runs, and those belong in the conversation
 * rather than in the tool result the model reads.
 */
data class PluginToolRun(
    val result: ToolResult,
    val cards: List<PluginCard>
)

/**
 * The app-side engine of chat plugins: it builds the design §6 request from
 * the installed row and the chat's binding, and runs it in the embedded Node
 * runtime — the same one-call-at-a-time lifecycle `node_run` uses.
 *
 * THE ROW IS THE MANIFEST. Everything a call is told — the entry, the declared
 * permissions, the network allowlist, the config schema — is read back from
 * the `plugins` row the installer validated into the database, never from a
 * file the plugin could have exchanged since. The one thing the row cannot
 * supply is the chat's own state, which the caller hands in: the binding's
 * grants, its config overrides, and (for a plugin granted `chat.context`) the
 * transcript.
 *
 * NEVER THROWS, for the same reason [NodeRunner.plugin] never throws: a plugin
 * that failed, timed out, or cannot run at all is an ANSWER the chat has to
 * read — a Notice, a failed tool result — not an exception the caller has to
 * word itself. The one deliberate exception is cancellation while WAITING
 * for the runtime's one-call gate: it travels, because swallowing it would
 * answer a stopped turn with a "failed plugin" it would then word, instead
 * of stopping the turn the person stopped. The identity rule is
 * [AgentNodeRuntime]'s with one deliberate difference: a plugin without the
 * network permission is not refused for lacking a user agent, because it
 * can never make a request; the bridge refuses an identity-less fetch
 * honestly if it turns out to want one.
 */
class AgentPluginRuntime(
    private val context: Context,
    private val profile: Profile?,
    /**
     * The honest queue notice's wire-free half: handed straight to the
     * production route ([PluginCall.throughRunner]) so a caller that builds
     * this runtime can surface "another plugin call is running — waiting…"
     * ([RUNTIME_CONTENDED_NOTICE]) on its status line without having to
     * construct its own [PluginCall]. Ignored whenever [call] is passed
     * explicitly — a caller that owns its route owns its notices too.
     */
    onContended: (() -> Unit)? = null,
    private val call: PluginCall = PluginCall.throughRunner(context, onContended)
) {

    /**
     * The app context this runtime was built with, re-exposed for the ONE
     * surface that needs the same application context the plugin tree lives
     * beside but reaches this class instead of a Context: the /skills chat
     * commands, which build their skill repository over the same filesDir.
     * The runtime OWNS a context by construction; the accessor exists so its
     * consumers need not carry a second one around. Nothing else reads it.
     */
    internal val appContext: Context get() = context

    /**
     * Runs one event and returns what it produced — the raw result envelope,
     * cards still JSON. [history] is the chat's transcript as
     * [historyOf] shapes it; it is put on the wire only when this call's
     * effective grants include `chat.context`.
     *
     * [generate] rides to the seam unchanged — the blocking wrap that answers
     * `room.agent.generate` is the production route's job
     * ([PluginCall.throughRunner] through [reverseGenerateHandler]), so a
     * fake seam observes the generator itself and the wrap lives in exactly
     * one place.
     *
     * A module.json row (`format='zagents'`) is built into the MODULE half of
     * the request here — its own budget, its state directory, its verbatim
     * spellings, the module permission vocabulary — read back from the same
     * row the installer validated, never from a file the module could have
     * exchanged since. [prompt] is the staged activation's message (the only
     * run that carries one); [form] is the host a mid-run form request is
     * bridged to, or null when this event runs unattended.
     */
    suspend fun run(
        sessionId: Long,
        plugin: PluginEntity,
        binding: ChatPluginEntity,
        event: String,
        payload: JsonObject = JsonObject(emptyMap()),
        history: List<PluginChatEntry> = emptyList(),
        budgetMs: Long = DEFAULT_BUDGET_MS,
        generate: PluginGenerateCall? = null,
        prompt: String? = null,
        form: PluginFormCall? = null
    ): PluginCallResult = withContext(Dispatchers.IO) {
        val manifest = plugin.toManifest()
        val effective = PluginPermissions.effectiveGrants(
            manifest.permissions,
            decodePermissions(binding.grantedPermissionsJson)
        )
        val module = plugin.format == PluginFormat.ZAGENTS
        val dataRoot = chatModuleDir(sessionId, plugin.dirName).absolutePath
        val timeoutSeconds = plugin.timeoutSeconds ?: ZAgentsManifest.DEFAULT_TIMEOUT_SECONDS
        val request = PluginCallRequest(
            pluginRoot = File(File(context.filesDir, PLUGINS_DIR), plugin.dirName).absolutePath,
            dataRoot = dataRoot,
            entry = plugin.entry,
            manifest = PluginWireManifest(
                id = plugin.id,
                name = plugin.name,
                version = plugin.version,
                // A module call speaks the MODULE vocabulary the host
                // re-validates against — the mapping of the row's own names,
                // applied to the effective set and never to the declared one,
                // so the consent's meaning does not change with the format.
                permissions = if (module) modulePermissions(effective) else effective,
                networkAllow = manifest.networkAllow
            ),
            event = event,
            payload = payload,
            config = mergedConfig(manifest.configSchema, binding.configJson),
            // A module's budget is the number its manifest asked for and it
            // wins host-side; the app's read timeout derives from timeoutMs,
            // so a module call hands the wire the module's number both ways.
            timeoutMs = if (module) timeoutSeconds * 1000L else budgetMs,
            userAgent = userAgent(),
            format = if (module) PluginCallRequest.FORMAT_ZAGENTS else null,
            prompt = prompt?.takeIf { module },
            timeoutSeconds = if (module) timeoutSeconds else null,
            // Inputs are a shared feature: whatever the binding stored for
            // the row's form rides for either format, and an absent or empty
            // store is simply omitted.
            inputsValues = PluginForms.storedValues(binding.inputsValuesJson)
                .takeIf { it.isNotEmpty() },
            stateRoot = if (module) File(dataRoot, STATE_DIR).absolutePath else null,
            title = if (module) plugin.name else null,
            zagentsVersion = if (module) plugin.zagentsVersion else null,
            zagentsName = if (module) plugin.zagentsName else null,
            history = history.takeIf { it.isNotEmpty() && PluginPermissions.CHAT_CONTEXT in effective }
        )
        runCatching { call(request, generate, form) }
            .getOrElse { err ->
                // The one failure that is not the plugin's: a caller cancelled
                // while it WAITED for the runtime's one-call gate (another
                // chat's form dialog can hold it for minutes). Swallowing it
                // here would run a stopped turn's coroutine on to a "failed
                // plugin" the person never sees — so the cancellation itself
                // travels, and every other failure stays an answer.
                if (err is CancellationException) throw err
                PluginCallResult(
                    ok = false,
                    output = err.message ?: "the plugin call failed: ${err.javaClass.simpleName}"
                )
            }
    }

    /**
     * The per-chat, per-module data folder this call's `dataRoot` points at:
     * `<chatsRoot>/<sessionId>/<dirName>`, on the app-specific external
     * storage a file manager can walk — every chat its OWN folder, so one
     * chat's `kv.json` and `state/` tree can never read, write, or name
     * another chat's. [chatsRoot] is the one spelling of the tree's root; a
     * device with no external volume falls back to the internal [DATA_DIR]
     * tree there, isolation intact, visibility lost honestly rather than
     * data forked between two roots.
     *
     * The pre-chat-folders releases kept these folders in the internal
     * `plugin-data/<sessionId>/<dirName>` tree; a legacy folder still there
     * is carried over by the call that would otherwise re-create its sibling
     * — copied, then the legacy tree removed only when the copy whole — so a
     * chat's data cannot fork between the roots (`renameTo` cannot cross the
     * internal/external volumes, and a copy interrupted mid-tree is resumed,
     * not half-deleted, by the next call).
     */
    private fun chatModuleDir(sessionId: Long, dirName: String): File {
        val current = File(File(chatsRoot(context), sessionId.toString()), dirName)
        if (current.isDirectory) return current
        val legacy = File(File(File(context.filesDir, DATA_DIR), sessionId.toString()), dirName)
        if (!legacy.isDirectory) return current
        runCatching {
            current.parentFile?.mkdirs()
            if (legacy.copyRecursively(current, overwrite = false)) {
                legacy.deleteRecursively()
            }
        }
        return current
    }

    /**
     * Runs one agent tool event and maps it to what the turn consumes: the
     * plugin's output FENCED AS DATA exactly the way a `node_run` result is
     * (the plugin's words are not trusted for having come from our own
     * runtime), a failure carried as its own words unfenced the way
     * [AgentNodeRuntime] carries one, and any cards it pushed for the user.
     *
     * [generate] is the chat's own generator for the plugin to ask while its
     * tool runs — the same one the turn or command path resolved. [form] is
     * the host a mid-run form request is bridged to — the missing-inputs
     * gate's caller wires the chat's own host, so a tool that asks for a
     * dialog mid-run gets the same one its gate did.
     */
    suspend fun runTool(
        sessionId: Long,
        plugin: PluginEntity,
        binding: ChatPluginEntity,
        toolName: String,
        argsJson: String,
        history: List<PluginChatEntry> = emptyList(),
        generate: PluginGenerateCall? = null,
        form: PluginFormCall? = null
    ): PluginToolRun {
        val payload = JsonObject(
            mapOf(
                "name" to JsonPrimitive(toolName),
                "argsJson" to JsonPrimitive(argsJson)
            )
        )
        val result = run(
            sessionId = sessionId,
            plugin = plugin,
            binding = binding,
            event = PluginCallRequest.EVENT_TOOL,
            payload = payload,
            history = history,
            generate = generate,
            form = form
        )
        return PluginToolRun(
            result = if (result.ok) {
                ToolResult(true, UntrustedContent.wrap(result.output, "the ${plugin.name} plugin"))
            } else {
                ToolResult(false, result.output)
            },
            // Parsed and clamped again here: the host already held the line,
            // and this second gate also covers a reply that arrives malformed
            // from anywhere else. Dropping a bad card beats rendering one.
            cards = PluginCard.parseList(JsonArray(result.cards))
        )
    }

    /**
     * The identity this profile's pages send — the same path
     * [AgentNodeRuntime] uses, so a plugin's requests leave the device as the
     * profile rather than as Node. The engine's default answers when the
     * profile picked none ("whatever the engine sends"), and null — meaning
     * the field is omitted — only when even that is unavailable.
     */
    private fun userAgent(): String? =
        profile?.settings?.let { UserAgents.effectiveUserAgent(it) }
            ?.takeIf { it.isNotBlank() }
            ?: ProfileEngine.defaultUserAgent(context)?.takeIf { it.isNotBlank() }

    /**
     * The manifest's config defaults under this chat's overrides. Only keys
     * the schema declared survive: a config value the manifest never asked for
     * is not input the plugin's author wrote code against, so it is dropped
     * rather than handed to `room.config` as a surprise.
     */
    private fun mergedConfig(schema: List<PluginConfigField>, overridesJson: String): JsonObject {
        val overrides = runCatching {
            AgentJson.parseToJsonElement(overridesJson.trim()) as? JsonObject
        }.getOrNull() ?: JsonObject(emptyMap())
        return JsonObject(
            buildMap {
                schema.forEach { field ->
                    val override = overrides[field.key]
                    put(field.key, if (override is JsonPrimitive) override else field.default)
                }
            }
        )
    }

    /**
     * The row's effective permissions in the MODULE vocabulary the host
     * re-validates a zagents call against: storage→storage, network→network,
     * files→files, chat.context→chat, agent.generate→agent. The intersection
     * is already computed (the consent's meaning never changes with the
     * format); this is only its spelling. Storage and files together collapse
     * to files — the row cannot tell a module that declared `files` from one
     * that declared `storage` and `files` (install maps `files` to both), and
     * the collapse is the direction that never widens: a module that asked
     * for files alone must not gain the kv store, and one that asked for both
     * still gets the path-addressed sandbox the pair was stored to mean.
     * `agent.tools` has no module spelling and a module never declares it, so
     * it maps to nothing.
     */
    private fun modulePermissions(effective: List<String>): List<String> {
        val mapped = effective.mapNotNull { permission ->
            when (permission) {
                PluginPermissions.STORAGE -> "storage"
                PluginPermissions.NETWORK -> "network"
                PluginPermissions.FILES -> "files"
                PluginPermissions.CHAT_CONTEXT -> "chat"
                PluginPermissions.AGENT_GENERATE -> "agent"
                else -> null
            }
        }
        return if ("files" in mapped) mapped.filter { it != "storage" } else mapped
    }

    companion object {
        /** probe/activate/command/config/tool — the design §5 default. */
        const val DEFAULT_BUDGET_MS = 30_000L

        /** deactivate — a goodbye that must not hold a deletion hostage. */
        const val DEACTIVATE_BUDGET_MS = 10_000L

        /**
         * The message hook, before the agent's own turn. Deliberately the
         * deactivate budget rather than §5's 30 s default: this hook runs in
         * front of EVERY message the user sends, so a plugin that wedges here
         * wedges the chat itself, and the tighter cap is the difference
         * between a slow reply and an app that looks hung.
         */
        const val MESSAGE_HOOK_BUDGET_MS = 10_000L

        /**
         * One `room.agent.generate` call's own budget, in milliseconds. Long
         * enough that a real model gets to answer, short enough that a plugin
         * waiting on one cannot hold its command's 30 s budget hostage — and
         * the 10 s message hook will usually not fit a generation at all,
         * which is honest arithmetic rather than a bug (the docs say so).
         */
        const val GENERATE_BUDGET_MS = 20_000L

        const val PLUGINS_DIR = "plugins"
        const val DATA_DIR = "plugin-data"

        /**
         * The per-chat folder tree the module data roots live in —
         * `<app-specific external files>/chats/<sessionId>/<dirName>`, the
         * location a file manager can walk: the app-specific external root
         * sits on shared storage, visible over USB and to file managers that
         * browse an app's own folders, and closed to other apps on Android
         * 11+ — a chat's folder the person can FIND without the data inside
         * becoming every app's to read. [DATA_DIR] stays as the internal tree
         * the pre-chat-folders releases used: the migration's source, and the
         * fallback for a device that hands back no external volume at all.
         */
        const val CHATS_DIR = "chats"

        /**
         * The one root every [CHATS_DIR] tree is built from — the
         * app-specific external files dir when the device has one, the
         * legacy internal [DATA_DIR] tree when it does not. One spelling,
         * because the runtime's data roots and the repository's orphan sweep
         * must agree on where a chat's folder lives or the sweep would guard
         * a tree nothing writes to.
         */
        fun chatsRoot(context: Context): File =
            File(context.getExternalFilesDir(null) ?: File(context.filesDir, DATA_DIR), CHATS_DIR)

        /**
         * The per-chat directory a module's `zagents.fs` sandbox is rooted at
         * — `<dataRoot>/state/`, the same spelling the host falls back to, so
         * a module's state lives beside its kv under the one data root this
         * call was already handed.
         */
        const val STATE_DIR = "state"

        /**
         * How much of the chat a `chat.context` grant buys: the last
         * [HISTORY_MAX_ENTRIES] user and assistant turns (tool rows are not
         * conversation), kept whole from the newest end until
         * [HISTORY_MAX_CHARS] is reached — the one entry left over when the
         * cap cannot fit two is clamped rather than dropped, because "this
         * chat exists but is long" is more truth than "this chat is empty".
         */
        const val HISTORY_MAX_ENTRIES = 60
        const val HISTORY_MAX_CHARS = 12_000

        /**
         * The transcript as the wire carries it, or empty when the chat has
         * no user or assistant turns to show. Pure, so the shaping — what is
         * a conversation entry, what the cap keeps — is testable without a
         * database.
         */
        fun historyOf(messages: List<AgentMessageEntity>): List<PluginChatEntry> {
            val recent = messages
                .filter { it.role == "user" || it.role == "assistant" }
                .takeLast(HISTORY_MAX_ENTRIES)
            var from = 0
            var total = recent.sumOf { it.content.length }
            while (from < recent.size - 1 && total > HISTORY_MAX_CHARS) {
                total -= recent[from].content.length
                from++
            }
            return recent.drop(from).map { row ->
                PluginChatEntry(role = row.role, text = row.content.take(HISTORY_MAX_CHARS))
            }
        }
    }
}

/**
 * The chat-deletion half of the plugin lifecycle: every plugin the chat had
 * enabled gets its `deactivate` event (best-effort — a plugin that throws or
 * hangs inside its 10 s budget is reported by nobody, because the chat is
 * going either way), and then the orphan sweep reclaims the data directories
 * the database's own cascade cannot. Shared by every session-deletion call
 * site, because each of them owes the plugin the same goodbye.
 */
internal suspend fun deactivateChatPlugins(
    repo: PluginRepository,
    runtime: AgentPluginRuntime,
    sessionId: Long,
    reason: String
) {
    runCatching {
        val installed = repo.installedOnce().associateBy { it.id }
        repo.chatPluginsOnce(sessionId).filter { it.enabled }.forEach { binding ->
            val plugin = installed[binding.pluginId] ?: return@forEach
            runtime.run(
                sessionId = sessionId,
                plugin = plugin,
                binding = binding,
                event = PluginCallRequest.EVENT_DEACTIVATE,
                payload = JsonObject(mapOf("reason" to JsonPrimitive(reason))),
                budgetMs = AgentPluginRuntime.DEACTIVATE_BUDGET_MS
            )
        }
    }
    runCatching { repo.sweepOrphanData() }
}
