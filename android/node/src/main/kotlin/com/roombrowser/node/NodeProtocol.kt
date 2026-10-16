package com.roombrowser.node

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
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
 * `id`, `ok`, `result` or `error` coming back.
 */
internal object NodeProtocol {

    private val json = Json { ignoreUnknownKeys = true }

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
    fun result(line: String, expectedId: Int): JsonObject {
        val reply = json.parseToJsonElement(line).jsonObject
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
