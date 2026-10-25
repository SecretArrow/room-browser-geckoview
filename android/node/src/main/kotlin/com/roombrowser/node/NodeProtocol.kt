package com.roombrowser.node

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What the runtime reports about itself. */
@Serializable
data class NodeStatus(
    val node: String,
    val platform: String,
    val arch: String,
    val pid: Int,
    val workDir: String,
    val userAgent: String? = null,
    val userAgentEnforced: Boolean = false
)

/**
 * What one script produced.
 *
 * [ok] is the SCRIPT's outcome, not the runtime's: a script that throws is an
 * answer the model has to read and correct, not a broken runtime, so it arrives
 * with the same envelope as a success and the thrown words in [output].
 */
@Serializable
data class NodeRunResult(
    val ok: Boolean,
    val output: String,
    val ms: Long = 0
)

/**
 * The manifest slice a plugin call carries — the `manifest` object of the
 * plugin request. Only what the host gates capabilities on: identity and the
 * permissions THIS call grants. Kotlin computes the effective set (declared
 * ∩ granted by this chat) before building it, so the host never has to trust
 * a grant it was not told about.
 */
@Serializable
data class PluginWireManifest(
    val id: String,
    val name: String,
    val version: String,
    val permissions: List<String> = emptyList(),
    @SerialName("networkAllow") val networkAllow: List<String> = emptyList()
)

/**
 * One turn of the chat's transcript, as a plugin that was granted
 * `chat.context` reads it: a speaker and its words, tool entries omitted.
 *
 * The role vocabulary is the wire's ("user" | "assistant") rather than an
 * enum for the same reason [PluginCallRequest]'s event is a string: the host
 * reads it leniently and this side must not turn a forward-compatible host
 * into a decode failure here.
 */
@Serializable
data class PluginChatEntry(
    val role: String,
    val text: String
)

/**
 * One plugin event, as one line of JSON each way.
 *
 * The event vocabulary (design §5) is fixed and small, so it is a string the
 * companion names rather than an enum on the wire: the host reads `event`
 * leniently and answers an unknown one in words, and an enum here would turn
 * a forward-compatible host reply into a decode failure on this side.
 *
 * The module half of the request — everything from [format] down — is the
 * ZAgents compatibility surface: absent (null) on a plugin.json row, where
 * the host reads the absence as the plugin SDK and its own defaults, and
 * strictly typed on a module.json row, where the host refuses a wrong shape
 * rather than guessing what the caller meant.
 */
data class PluginCallRequest(
    /** `<filesDir>/plugins/<sanitized>` — the plugin's own code, read-only at run time. */
    val pluginRoot: String,
    /** `<filesDir>/plugin-data/<sessionId>/<sanitized>` — this chat's data for it. */
    val dataRoot: String,
    val entry: String,
    val manifest: PluginWireManifest,
    val event: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    /** The manifest's defaults merged with this chat's overrides. */
    val config: JsonObject = JsonObject(emptyMap()),
    val timeoutMs: Long = 30_000,
    /** Blank is left out of the line — the runtime refuses requests without one. */
    val userAgent: String? = null,
    /**
     * The row's manifest format: [FORMAT_ZAGENTS] for a module.json package, or
     * null (or "plugin") for the plugin.json spelling the wire has always
     * spoken. Null stays off the wire, which the host reads as the plugin SDK.
     */
    val format: String? = null,
    /** The FIRST message of a fresh chat a module was staged on — its activation prompt. */
    val prompt: String? = null,
    /** The module's own budget from `module.json` `timeoutSeconds` (1..120), not the caller's. */
    val timeoutSeconds: Int? = null,
    /** This chat's stored answers to the module's `inputs` form, as the manifest keyed them. */
    val inputsValues: JsonObject? = null,
    /** This chat's per-module state directory — the root of the module's `zagents.fs` sandbox. */
    val stateRoot: String? = null,
    /** The module's display title, verbatim from `module.json` (falls back to the name). */
    val title: String? = null,
    /** The module's version string, verbatim — module.json versions are not semver. */
    val zagentsVersion: String? = null,
    /** The module's sanitized name, verbatim from the row. */
    val zagentsName: String? = null,
    /**
     * The NodePackageStore's install root (`<nodeDir>/node_modules`), when
     * the caller has one, so a plugin whose npm dependencies were NOT bundled
     * in its ZIP can still resolve them — read-only, contained, and only
     * AFTER the plugin's own tree fails to answer. Null (or blank, which is
     * omitted from the line) keeps the shim exactly as it was: bundled
     * packages or built-ins, nothing else. WHY A FIELD AND NOT A CONSTANT:
     * the host is also run by the desktop harness, where the store's
     * location is not this app's to know — the caller that owns the store
     * passes its own truth.
     */
    val nodeModulesRoot: String? = null,
    /**
     * The CURRENT chat's transcript (user and assistant turns only), most
     * recent last, sent ONLY when `chat.context` is in the effective grant
     * set — otherwise the field is absent and the host answers the plugin an
     * empty chat. Null and empty are both omitted, like a blank [userAgent]:
     * a `history: []` line would read as "this chat has no turns" when the
     * truth is "this plugin may not read them", and the two are not the same
     * promise to the person who granted the permission.
     */
    val history: List<PluginChatEntry>? = null
) {
    companion object {
        const val EVENT_PROBE = "probe"
        const val EVENT_ACTIVATE = "activate"
        const val EVENT_DEACTIVATE = "deactivate"
        const val EVENT_COMMAND = "command"
        const val EVENT_MESSAGE = "message"
        const val EVENT_TOOL = "tool"
        const val EVENT_CONFIG = "config"

        /** The `format` a module.json row carries — the plugin.json spelling needs no constant. */
        const val FORMAT_ZAGENTS = "zagents"
    }
}

/**
 * The style block of a [PluginGenerateRequest]: every member optional, so an
 * absent one reads as "no constraint" rather than as a default the plugin
 * never asked for. Booleans are true-when-set — `oneSentence = false` and an
 * absent `oneSentence` are the same instruction to the model.
 */
data class PluginGenerateStyle(
    val language: String? = null,
    val maxWords: Int? = null,
    val oneSentence: Boolean = false,
    val lowercase: Boolean = false,
    val forbiddenChars: String? = null,
    val tone: String? = null
) {
    /** The style block with only the members that are set — [PluginGenerateRequest.toJson]'s inner half. */
    internal fun toJson(): JsonObject = buildJsonObject {
        language?.let { put("language", it) }
        maxWords?.let { put("maxWords", it) }
        if (oneSentence) put("oneSentence", true)
        if (lowercase) put("lowercase", true)
        forbiddenChars?.let { put("forbiddenChars", it) }
        tone?.let { put("tone", it) }
    }
}

/**
 * One `agent.generate` request — the §4 object a plugin's reverse request
 * carries, as flat fields.
 *
 * The plugin supplies STRUCTURED fields, never a raw prompt; the prompt is
 * rendered by the app (domain's `PluginGeneratePrompt`) from [toJson], which
 * re-emits the CLAMPED fields — so a request that arrived over-long is
 * shortened before it reaches the model, and the prompt renderer's own caps
 * are a second gate rather than the only one. [of] is lenient on shapes
 * (unknown members ignored, non-strings read as absent) and strict on the two
 * fields a generation cannot stand without: a blank purpose or instruction
 * is a refusal in words, because the host already validated them and a
 * request that failed twice is the host's bug to read about.
 */
data class PluginGenerateRequest(
    val purpose: String,
    val instruction: String,
    val context: String? = null,
    val hint: String? = null,
    val style: PluginGenerateStyle = PluginGenerateStyle(),
    val avoid: List<String> = emptyList()
) {
    companion object {
        /**
         * Parses one request leniently and clamps every field to its §4 cap.
         *
         * Accepts the §4 object bare OR wrapped in a reverse request line's
         * `request` member: the read loop hands whole lines here, while a
         * caller that already peeled the object (or a test) hands the inner
         * one — and a `request` member is not a §4 field, so the two shapes
         * cannot collide.
         */
        fun of(json: JsonObject): PluginGenerateRequest {
            val root = (json["request"] as? JsonObject) ?: json
            val purpose = root.text("purpose", PURPOSE_CHARS)
            require(!purpose.isNullOrEmpty()) { "the generate request carried no purpose" }
            val instruction = root.text("instruction", INSTRUCTION_CHARS)
            require(!instruction.isNullOrEmpty()) { "the generate request carried no instruction" }
            val style = root["style"] as? JsonObject
            return PluginGenerateRequest(
                purpose = purpose,
                instruction = instruction,
                context = root.text("context", CONTEXT_CHARS),
                hint = root.text("hint", HINT_CHARS),
                style = PluginGenerateStyle(
                    language = style?.text("language", LANGUAGE_CHARS),
                    maxWords = (style?.get("maxWords") as? JsonPrimitive)?.intOrNull
                        ?.coerceIn(1, MAX_WORDS),
                    oneSentence = style?.flag("oneSentence") ?: false,
                    lowercase = style?.flag("lowercase") ?: false,
                    forbiddenChars = style?.text("forbiddenChars", FORBIDDEN_CHARS),
                    tone = style?.text("tone", TONE_CHARS)
                ),
                avoid = avoidTexts(root)
            )
        }

        /** The texts not to repeat: strings only, blanks dropped, count and length clamped. */
        private fun avoidTexts(root: JsonObject): List<String> =
            ((root["avoid"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                .orEmpty()
                .filter { it.isNotBlank() }
                .take(MAX_AVOID_ENTRIES)
                .map { it.take(AVOID_ENTRY_CHARS) }

        /** The member as a trimmed, non-blank string clamped to [cap], or null when it says nothing. */
        private fun JsonObject.text(key: String, cap: Int): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull
                ?.trim()
                ?.take(cap)
                ?.takeIf { it.isNotEmpty() }

        /** The member as a flag: absent, non-boolean and false all read as false. */
        private fun JsonObject.flag(key: String): Boolean =
            (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

        private const val PURPOSE_CHARS = 64
        private const val INSTRUCTION_CHARS = 500
        private const val CONTEXT_CHARS = 4_000
        private const val HINT_CHARS = 200
        private const val LANGUAGE_CHARS = 24
        private const val MAX_WORDS = 200
        private const val FORBIDDEN_CHARS = 64
        private const val TONE_CHARS = 64
        private const val MAX_AVOID_ENTRIES = 50
        private const val AVOID_ENTRY_CHARS = 200
    }

    /** The clamped §4 object, with only the members that carry a value — the prompt renderer's input. */
    fun toJson(): JsonObject = buildJsonObject {
        put("purpose", purpose)
        put("instruction", instruction)
        context?.let { put("context", it) }
        hint?.let { put("hint", it) }
        if (style != PluginGenerateStyle()) put("style", style.toJson())
        if (avoid.isNotEmpty()) put("avoid", JsonArray(avoid.map { JsonPrimitive(it) }))
    }
}

/** One tool a plugin's probe or activate reported, as the agent catalogue wants it. */
@Serializable
data class PluginToolSpec(
    val name: String,
    val description: String = "",
    val parameters: JsonObject = JsonObject(emptyMap())
)

/**
 * What one plugin event produced.
 *
 * Same split as [NodeRunResult]: [ok] is the PLUGIN's outcome, so a handler
 * that threw arrives as a readable answer with the words in [output]. The
 * cards stay raw JSON objects here — the app-side model (domain's PluginCard)
 * belongs to the layer that renders them, and this module names no domain
 * type. [tools], [commands] and [h[hooks] are filled by probe and activate
 * only; every other event answers them empty.
 */
@Serializable
data class PluginCallResult(
    val ok: Boolean,
    val output: String = "",
    val cards: List<JsonObject> = emptyList(),
    val logs: List<String> = emptyList(),
    val tools: List<PluginToolSpec> = emptyList(),
    val commands: List<String> = emptyList(),
    val hooks: List<String> = emptyList(),
    val ms: Long = 0
)

/**
 * One line of JSON each way. The other end is `assets/node/bridge.js`, and the
 * two spellings are one protocol: `id`, `cmd`, `token`, `userAgent` going out;
 * `id`, `ok`, `result` or `error` coming back — plus, while a plugin call is
 * executing, REVERSE REQUESTS: lines the runtime writes mid-call to ask the
 * app something (design §3). The family has two commands — `agent_generate`
 * and `user_input` — and extends through the registry, not through protocol
 * surgery: a new command is an entry in bridge.js's table, a [ReverseHandlers]
 * registration here, and nothing else.
 */
internal object NodeProtocol {

    /** The reverse command a plugin's `room.agent.generate` (and a module's compose) rides. */
    const val REVERSE_GENERATE = "agent_generate"

    /** The reverse command the runtime form (`room.ui.form` / `zagents.ui.form`) rides. */
    const val REVERSE_USER_INPUT = "user_input"

    /**
     * How long the runtime waits for the app to answer ONE `user_input`
     * request before it gives up on the form — the wire's own number, the
     * same 300 s bridge.js arms, kept here so the app side and the doc read
     * one constant instead of two spellings of a timeout neither owns alone.
     * A dialog awaiting a person is the one wait in the protocol that is
     * allowed to take minutes, and the module's own budget is PAUSED for
     * every second of it (plugin_host.js pauses the deadline while a reverse
     * request is outstanding).
     */
    const val REVERSE_USER_INPUT_TIMEOUT_MS = 300_000L

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(line: String): JsonObject = json.parseToJsonElement(line).jsonObject

    fun request(
        id: Int,
        cmd: String,
        token: String,
        userAgent: String?,
        script: String? = null,
        timeoutMs: Long? = null
    ): String =
        buildJsonObject {
            put("id", id)
            put("cmd", cmd)
            put("token", token)
            // Blank is left out rather than sent as "": the runtime reads a
            // missing identity as a refusal on every request, never as a
            // fallback to its own.
            if (!userAgent.isNullOrBlank()) put("userAgent", userAgent)
            if (script != null) put("script", script)
            if (timeoutMs != null) put("timeoutMs", timeoutMs)
        }.toString()

    /** The reply's `result`, or the runtime's own error as an exception. */
    fun result(line: String, expectedId: Int): JsonObject = resultOf(parse(line), expectedId)

    /**
     * The same read over an already-parsed reply. The read loop parses every
     * line once — a reverse request and the plugin reply it precedes are one
     * parse, not two.
     */
    fun resultOf(reply: JsonObject, expectedId: Int): JsonObject {
        val id = reply["id"]?.jsonPrimitive?.intOrNull
        require(id == expectedId) { "the runtime answered request $id, not $expectedId" }
        val ok = reply["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!ok) {
            throw IllegalStateException(
                reply["error"]?.jsonPrimitive?.content ?: "the runtime refused the request"
            )
        }
        return reply["result"]!!.jsonObject
    }

    /**
     * Whether a parsed line is a REVERSE REQUEST rather than the reply an
     * exchange is waiting for. Discrimination is safe in both directions
     * because the wire never mixes the two: a command (forward or reverse)
     * always carries `cmd`, and a reply never does — so a `cmd` member while
     * waiting can only be the runtime asking something of this side.
     */
    fun isReverse(line: JsonObject): Boolean = "cmd" in line

    /** The reverse reply line: the requesting line's own id, the answer as its `result`. */
    fun reverseReply(id: String, result: JsonObject): String = buildJsonObject {
        put("id", id)
        put("ok", true)
        put("result", result)
    }.toString()

    /** The reverse reply line for a request this side could not or would not answer. */
    fun reverseError(id: String, error: String): String = buildJsonObject {
        put("id", id)
        put("ok", false)
        put("error", error)
    }.toString()

    /**
     * One reverse request's answer as the line to write back — the whole of
     * the reverse policy in one pure function, so the token rule, the
     * per-command defaults and the handler dispatch are testable without a
     * socket. The envelope borrows the runtime's own reply shape and carries
     * the REVERSE line's id (the bridge settles its pending promise by id, so
     * answering with anything else would leave it waiting on a line it cannot
     * match), and every refusal keeps the id and carries its words: a failed
     * generation or form is an answer the plugin reads, not a broken call.
     *
     * The handler is BLOCKING and budgeted by whoever registered it; anything
     * it raises is the request's honest outcome and its words are the most
     * useful thing the plugin can be told.
     */
    fun reverseAnswer(line: JsonObject, token: String, handlers: ReverseHandlers): String {
        val id = (line["id"] as? JsonPrimitive)?.contentOrNull ?: ""
        val carried = (line["token"] as? JsonPrimitive)?.contentOrNull
        if (carried != token) {
            return reverseError(id, "the reverse request did not carry this run's token")
        }
        val cmd = (line["cmd"] as? JsonPrimitive)?.contentOrNull
        val handler = handlers.handlerFor(cmd)
            ?: return reverseError(id, reverseDefaultError(cmd))
        return try {
            reverseReply(id, handler(line))
        } catch (err: Exception) {
            reverseError(
                id,
                err.message ?: when (cmd) {
                    REVERSE_GENERATE -> "the generation failed: ${err.javaClass.simpleName}"
                    else -> "the reverse request failed: ${err.javaClass.simpleName}"
                }
            )
        }
    }

    /**
     * The words for a reverse command nobody registered to answer. A
     * generation is "not available" (the call has no model wired); a form is
     * answered with the exact sentence the runtime form's own refusal uses,
     * so a probe or a legacy call site — nobody registered a dialog — behaves
     * the same as a host that wired none: the plugin reads the refusal and the
     * call carries on. Anything else is a command this build does not know.
     */
    internal fun reverseDefaultError(cmd: String?): String = when (cmd) {
        REVERSE_GENERATE -> "agent generation is not available"
        REVERSE_USER_INPUT -> ReverseHandlers.NO_INTERACTIVE_UI
        else -> "unknown reverse command: $cmd"
    }

    fun statusOf(result: JsonObject): NodeStatus =
        json.decodeFromJsonElement(NodeStatus.serializer(), result)

    fun runOf(result: JsonObject): NodeRunResult =
        json.decodeFromJsonElement(NodeRunResult.serializer(), result)

    /** The plugin request as the one line the bridge reads (design §6, exactly). */
    fun pluginRequest(id: Int, token: String, call: PluginCallRequest): String =
        buildJsonObject {
            put("id", id)
            put("cmd", "plugin")
            put("token", token)
            // Same rule as `run`: blank is left out rather than sent as "", so
            // the runtime reads a missing identity as a refusal, never as a
            // fallback to its own.
            if (!call.userAgent.isNullOrBlank()) put("userAgent", call.userAgent)
            put("pluginRoot", call.pluginRoot)
            put("dataRoot", call.dataRoot)
            put("entry", call.entry)
            put(
                "manifest",
                buildJsonObject {
                    put("id", call.manifest.id)
                    put("name", call.manifest.name)
                    put("version", call.manifest.version)
                    put("permissions", JsonArray(call.manifest.permissions.map { JsonPrimitive(it) }))
                    put("networkAllow", JsonArray(call.manifest.networkAllow.map { JsonPrimitive(it) }))
                }
            )
            put("event", call.event)
            put("payload", call.payload)
            put("config", call.config)
            put("timeoutMs", call.timeoutMs)
            // The module half: same omit-when-absent rule as userAgent and
            // history — a plugin.json row sends none of it, and the host reads
            // each absence as its own default rather than as an empty value.
            if (!call.format.isNullOrBlank()) put("format", call.format)
            if (!call.prompt.isNullOrBlank()) put("prompt", call.prompt)
            call.timeoutSeconds?.let { put("timeoutSeconds", it) }
            call.inputsValues?.let { put("inputsValues", it) }
            if (!call.stateRoot.isNullOrBlank()) put("stateRoot", call.stateRoot)
            if (!call.title.isNullOrBlank()) put("title", call.title)
            if (!call.zagentsVersion.isNullOrBlank()) put("zagentsVersion", call.zagentsVersion)
            if (!call.zagentsName.isNullOrBlank()) put("zagentsName", call.zagentsName)
            // The npm store's root, for the require shim's guarded fallback.
            // Same omit-when-absent rule as every optional field: a null
            // member is read by the host as "no store", never as an empty one.
            if (!call.nodeModulesRoot.isNullOrBlank()) put("nodeModulesRoot", call.nodeModulesRoot)
            // Same omit-when-empty rule as userAgent above: absent means "not
            // granted", never "granted and empty".
            if (!call.history.isNullOrEmpty()) {
                put(
                    "history",
                    JsonArray(
                        call.history.map { entry ->
                            buildJsonObject {
                                put("role", entry.role)
                                put("text", entry.text)
                            }
                        }
                    )
                )
            }
        }.toString()

    fun pluginOf(result: JsonObject): PluginCallResult =
        json.decodeFromJsonElement(PluginCallResult.serializer(), result)
}
