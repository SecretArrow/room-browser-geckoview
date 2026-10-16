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
import com.roombrowser.domain.plugins.PluginPermissions
import com.roombrowser.node.NodeRunner
import com.roombrowser.node.PluginCallRequest
import com.roombrowser.node.PluginCallResult
import com.roombrowser.node.PluginChatEntry
import com.roombrowser.node.PluginGenerateRequest
import com.roombrowser.node.PluginWireManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * One plugin event's route to the runtime — [NodeRunner.plugin] in production,
 * a fake in the tests. A seam of this shape rather than a NodeRunner parameter
 * because NodeRunner is a final class that owns a real Android context; every
 * other seam in this package ([AgentNodeRuntime]) hides its runner the same
 * way, and a plugin's failure must be testable without starting one.
 *
 * [generate] is the chat's own text generator, or null when this call has
 * none. The production route wraps it into the blocking reverse handler
 * ([reverseGenerateHandler]) that answers `room.agent.generate` while the
 * plugin runs; null carries no handler at all, and NodeClient then refuses
 * any reverse request in its own words — the honest answer for a chat with
 * no session, provider or model to generate with.
 */
fun interface PluginCall {
    suspend operator fun invoke(
        request: PluginCallRequest,
        generate: PluginGenerateCall?
    ): PluginCallResult

    companion object {
        /** The production route: the one process-wide runner, one call at a time. */
        fun throughRunner(context: Context): PluginCall {
            val runner = NodeRunner(context)
            return PluginCall { request, generate ->
                runner.plugin(request, generate?.let(::reverseGenerateHandler))
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
 * threads, and the NodeRunner lock is held by THIS thread, which the
 * generation never re-enters.
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
 * word itself. The identity rule is [AgentNodeRuntime]'s with one deliberate
 * difference: a plugin without the network permission is not refused for
 * lacking a user agent, because it can never make a request; the bridge
 * refuses an identity-less fetch honestly if it turns out to want one.
 */
class AgentPluginRuntime(
    private val context: Context,
    private val profile: Profile?,
    private val call: PluginCall = PluginCall.throughRunner(context)
) {

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
     */
    suspend fun run(
        sessionId: Long,
        plugin: PluginEntity,
        binding: ChatPluginEntity,
        event: String,
        payload: JsonObject = JsonObject(emptyMap()),
        history: List<PluginChatEntry> = emptyList(),
        budgetMs: Long = DEFAULT_BUDGET_MS,
        generate: PluginGenerateCall? = null
    ): PluginCallResult = withContext(Dispatchers.IO) {
        val manifest = plugin.toManifest()
        val effective = PluginPermissions.effectiveGrants(
            manifest.permissions,
            decodePermissions(binding.grantedPermissionsJson)
        )
        val request = PluginCallRequest(
            pluginRoot = File(File(context.filesDir, PLUGINS_DIR), plugin.dirName).absolutePath,
            dataRoot = File(
                File(File(context.filesDir, DATA_DIR), sessionId.toString()),
                plugin.dirName
            ).absolutePath,
            entry = plugin.entry,
            manifest = PluginWireManifest(
                id = plugin.id,
                name = plugin.name,
                version = plugin.version,
                permissions = effective,
                networkAllow = manifest.networkAllow
            ),
            event = event,
            payload = payload,
            config = mergedConfig(manifest.configSchema, binding.configJson),
            timeoutMs = budgetMs,
            userAgent = userAgent(),
            history = history.takeIf { it.isNotEmpty() && PluginPermissions.CHAT_CONTEXT in effective }
        )
        runCatching { call(request, generate) }.getOrElse { err ->
            PluginCallResult(
                ok = false,
                output = err.message ?: "the plugin call failed: ${err.javaClass.simpleName}"
            )
        }
    }

    /**
     * Runs one agent tool event and maps it to what the turn consumes: the
     * plugin's output FENCED AS DATA exactly the way a `node_run` result is
     * (the plugin's words are not trusted for having come from our own
     * runtime), a failure carried as its own words unfenced the way
     * [AgentNodeRuntime] carries one, and any cards it pushed for the user.
     *
     * [generate] is the chat's own generator for the plugin to ask while its
     * tool runs — the same one the turn or command path resolved.
     */
    suspend fun runTool(
        sessionId: Long,
        plugin: PluginEntity,
        binding: ChatPluginEntity,
        toolName: String,
        argsJson: String,
        history: List<PluginChatEntry> = emptyList(),
        generate: PluginGenerateCall? = null
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
            generate = generate
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
