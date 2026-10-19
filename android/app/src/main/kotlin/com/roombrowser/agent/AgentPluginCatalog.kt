package com.roombrowser.agent

import com.roombrowser.data.db.ChatPluginEntity
import com.roombrowser.data.db.PluginEntity
import com.roombrowser.data.repo.PluginRepository
import com.roombrowser.data.repo.decodePermissions
import com.roombrowser.data.repo.toManifest
import com.roombrowser.domain.agent.ToolDef
import com.roombrowser.domain.agent.ToolFunction
import com.roombrowser.domain.plugins.PluginCommandDecl
import com.roombrowser.domain.plugins.PluginFormat
import com.roombrowser.domain.plugins.PluginManifest
import com.roombrowser.domain.plugins.PluginPermissions
import com.roombrowser.domain.plugins.PluginSystemPrompt
import com.roombrowser.domain.plugins.PluginToolNaming
import com.roombrowser.node.PluginCallRequest
import com.roombrowser.node.PluginToolSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One enabled plugin of one chat, as a probe saw it.
 *
 * [plugin] and [binding] are the rows the catalogue joined to run the probe;
 * [manifest] is rebuilt from the row (the validated truth, not a re-read of
 * the plugin's files). [tools] is what the CODE exports — only a probe can
 * know a tool's JSON Schema, so it is the probe's answer or nothing — while
 * [commands] is what the MANIFEST declares, because command routing is a
 * consent question (the person enabled a plugin that promised `/greet`) and
 * must not depend on the plugin's code loading. [hooks] likewise follows the
 * probe: a plugin that declares the message hook but never exports
 * `onMessage` would otherwise error on every message this chat sends.
 *
 * [probeOk] carries the probe's own outcome; a plugin that failed to load
 * contributes no tools and no hooks — and its failure was already said out
 * loud by whoever enabled it — but it stays in the list so its binding and
 * grants remain visible and a retry (re-enable, reinstall) has something to
 * replace.
 */
class SessionPlugin(
    val plugin: PluginEntity,
    val binding: ChatPluginEntity,
    val manifest: PluginManifest,
    val tools: List<PluginToolSpec>,
    val commands: List<PluginCommandDecl>,
    val hooks: List<String>,
    val probeOk: Boolean,
    val probeError: String?
) {
    /** The set this chat's calls actually run with: declared ∩ granted. */
    val grants: List<String> = PluginPermissions.effectiveGrants(
        manifest.permissions,
        decodePermissions(binding.grantedPermissionsJson)
    )
}

/** A plugin tool the catalogue resolved to its owner: the rows plus the plugin's own tool name. */
class SessionPluginTool(
    val sessionPlugin: SessionPlugin,
    val toolName: String
)

/**
 * The per-chat plugin catalogue: which plugins this chat enabled, what tools
 * they export, what commands they answer to — in memory, rebuilt by probing
 * whenever it is missing.
 *
 * A probe is not free (it starts the Node runtime), so the answer is cached
 * per session and invalidated on every enable, disable and uninstall — the
 * three events that can change it. A miss re-probes ALL of the session's
 * enabled bindings at once rather than one plugin: the cache is the session's
 * shape, and filling it one row at a time would pay the runtime's start-up
 * once per plugin instead of once per rebuild.
 *
 * The tool names this hands out are the catalogue spellings
 * ([PluginToolNaming]) — the plugin's identity is in the name, so every gate
 * that must recognise plugin tools can do it by prefix and stay static.
 */
class AgentPluginCatalog(
    private val repo: PluginRepository,
    private val runtime: AgentPluginRuntime
) {

    private val mutex = Mutex()
    private val cache = ConcurrentHashMap<Long, List<SessionPlugin>>()

    /**
     * Raised by every [invalidate] and [invalidateAll]. A probe holds the
     * [mutex] for the whole of its run — a Node start-up plus the module's
     * load, seconds on a phone — and an invalidation cannot wait it out (its
     * own callers, flow collectors among them, are not suspending through
     * here), so the number a probe read when it STARTED is what tells it, when
     * the probe comes back, whether the answer it is about to store was
     * already overtaken by an invalidation that landed mid-probe: an install,
     * an uninstall, an editor's save. Storing anyway would hand every LATER
     * reader a catalogue the invalidation had already declared false, and the
     * next reader would keep being served it until the next invalidation
     * happened to clear it. The guard makes the invalidation win by leaving
     * the cache empty for the next reader to fill from the truth; the
     * overtaken probe's own caller still gets its answer (it asked before the
     * change and has its own runCatching and failure words for that), but
     * nobody after it inherits the stale set.
     *
     * One counter for every session rather than one per session on purpose:
     * an invalidation of chat A dropping an in-flight probe of chat B's store
     * only costs that chat a re-probe, which is the conservative direction —
     * the reverse shape (a stale store surviving an invalidation) is the one
     * that serves tools a chat never consented to.
     */
    private val generation = AtomicLong(0)

    /**
     * The session's enabled plugins, probing every one of them when the cache
     * holds no answer for [sessionId]. Ordered by the bindings' creation, so
     * the sheet, the command routing and the tool catalogue all list plugins
     * in the order the chat added them.
     *
     * CONCURRENCY: the probe runs INSIDE the mutex, so a second caller that
     * arrives while one is in flight waits for it and then finds the answer
     * already stored — one probe serves any number of overlapping readers.
     * That single-flight is what lets the turn start a probe it does not
     * itself need yet (the start-of-turn warm in
     * [BrowserAgentController.runTurn]): the readers that DO need it later
     * either find it stored or join it in flight, never start a second one,
     * and never see an answer older than the invalidations that have landed.
     */
    suspend fun sessionPlugins(sessionId: Long): List<SessionPlugin> = mutex.withLock {
        cache[sessionId]?.let { return it }
        val atGeneration = generation.get()
        val discovered = probeAll(sessionId)
        if (generation.get() == atGeneration) cache[sessionId] = discovered
        discovered
    }

    /**
     * The agent tool definitions this chat's plugins add to the catalogue:
     * enabled bindings granted `agent.tools`, each export re-spelled to
     * `plugin_<sanitized-id>_<tool>`. A tool whose name cannot survive the
     * re-spelling is dropped, not renamed — a tool the plugin never defined
     * is worse than a tool missing.
     */
    suspend fun pluginToolDefs(sessionId: Long): List<ToolDef> =
        sessionPlugins(sessionId)
            .filter { PluginPermissions.AGENT_TOOLS in it.grants }
            .flatMap { sessionPlugin ->
                sessionPlugin.tools.mapNotNull { tool ->
                    PluginToolNaming.pluginToolName(sessionPlugin.plugin.id, tool.name)?.let { name ->
                        ToolDef(
                            function = ToolFunction(
                                name = name,
                                description = catalogueDescription(sessionPlugin, tool),
                                parameters = parametersOf(tool)
                            )
                        )
                    }
                }
            }

    /**
     * The command names this chat's enabled plugins declare, each mapped to
     * the plugin NAMES that declare it — the shape PluginCommandParser
     * routes by, and the one a name two plugins share needs to say so.
     */
    suspend fun commandOwners(sessionId: Long): Map<String, List<String>> =
        sessionPlugins(sessionId)
            .flatMap { sessionPlugin ->
                sessionPlugin.commands.map { it.name to sessionPlugin.plugin.name }
            }
            .groupBy({ it.first }, { it.second })

    /**
     * The plugin and own tool name behind a catalogue tool name, or null when
     * no enabled, `agent.tools`-granted plugin of this chat owns it.
     */
    suspend fun toolOwner(sessionId: Long, toolName: String): SessionPluginTool? =
        sessionPlugins(sessionId)
            .asSequence()
            .filter { PluginPermissions.AGENT_TOOLS in it.grants }
            .flatMap { sessionPlugin ->
                sessionPlugin.tools.asSequence().map { sessionPlugin to it }
            }
            .firstOrNull { (sessionPlugin, tool) ->
                PluginToolNaming.pluginToolName(sessionPlugin.plugin.id, tool.name) == toolName
            }
            ?.let { (sessionPlugin, tool) -> SessionPluginTool(sessionPlugin, tool.name) }

    /**
     * Drops the cached answer for [sessionId]: the next read re-probes. Also
     * raises [generation], which retires any probe still in flight for ANY
     * session — see that field for why an invalidation must beat a probe that
     * is about to store, not just the one that already did.
     */
    fun invalidate(sessionId: Long) {
        cache.remove(sessionId)
        generation.incrementAndGet()
    }

    /**
     * Drops every cached answer — an install or uninstall can change them
     * all. Raises [generation] for the same reason [invalidate] does. The map
     * is a [ConcurrentHashMap] because these run on their caller's thread
     * (a flow collector, an enable path) while a probe's store runs on its
     * own under the mutex, and the two may touch the map in the same breath.
     */
    fun invalidateAll() {
        cache.clear()
        generation.incrementAndGet()
    }

    /** Probes each enabled binding of the session, once, in binding order. */
    private suspend fun probeAll(sessionId: Long): List<SessionPlugin> {
        val installed = repo.installedOnce().associateBy { it.id }
        val bindings = repo.chatPluginsOnce(sessionId).filter { it.enabled }
        return bindings.mapNotNull { binding ->
            val plugin = installed[binding.pluginId] ?: return@mapNotNull null
            val manifest = plugin.toManifest()
            val result = runtime.run(
                sessionId = sessionId,
                plugin = plugin,
                binding = binding,
                event = PluginCallRequest.EVENT_PROBE
            )
            SessionPlugin(
                plugin = plugin,
                binding = binding,
                manifest = manifest,
                tools = if (result.ok) result.tools else emptyList(),
                commands = manifest.commands,
                hooks = if (result.ok) result.hooks else emptyList(),
                probeOk = result.ok,
                probeError = if (result.ok) null else result.output.lineSequence().firstOrNull().orEmpty()
            )
        }
    }

    /**
     * The one-line provenance the model reads in a flat catalogue: the plugin
     * names itself at the front of the description, so a model choosing
     * between two word-counters can tell whose it is calling without decoding
     * the name's spelling.
     */
    private fun catalogueDescription(
        sessionPlugin: SessionPlugin,
        tool: PluginToolSpec
    ): String {
        val description = tool.description.trim()
        return "[${sessionPlugin.plugin.name}] " +
            description.ifEmpty { "a tool provided by this chat's plugin" }
    }

    /**
     * The schema the provider is offered: the plugin's own object when it
     * wrote one, and the minimal `{"type":"object"}` when it wrote nothing —
     * an empty parameters object is accepted by no provider this app speaks
     * to, while a plugin with no arguments is exactly the case that sends it.
     */
    private fun parametersOf(tool: PluginToolSpec): JsonObject =
        if (tool.parameters.isEmpty()) {
            JsonObject(mapOf("type" to JsonPrimitive("object")))
        } else {
            tool.parameters
        }

    companion object {
        /**
         * The system-prompt addition for a chat's enabled plugins: one
         * [PluginSystemPrompt.blockOf] per binding, joined by blank lines, in
         * the order the chat enabled them — or "" for a chat with none, so a
         * pluginless chat's prompt stays byte-identical to the one before
         * plugins existed.
         *
         * Every ENABLED plugin introduces itself, loaded or not: the model may
         * be asked ABOUT a plugin whose probe failed even when it cannot call
         * one, and the block is the model's only honest picture of what else
         * runs in the conversation. The version is the VERBATIM string — a
         * module's own `zagents_version` spelling, a plugin's semver column.
         * Pure, so the wording and the join are pinned without a database.
         */
        fun systemPromptAddition(sessionPlugins: List<SessionPlugin>): String =
            sessionPlugins.joinToString("\n\n") { sessionPlugin ->
                val plugin = sessionPlugin.plugin
                PluginSystemPrompt.blockOf(
                    displayName = plugin.name,
                    version = if (plugin.format == PluginFormat.ZAGENTS) {
                        plugin.zagentsVersion ?: plugin.version
                    } else {
                        plugin.version
                    },
                    description = plugin.description,
                    format = plugin.format
                )
            }
    }
}

/**
 * The wire payloads the plugin events carry. One place, because the shapes
 * are the contract with `plugin_host.js` and a payload built in a caller
 * cannot be checked against it.
 */
internal object PluginPayloads {
    fun command(name: String, args: String): JsonObject = buildJsonObject {
        put("name", name)
        put("args", args)
    }

    fun message(text: String): JsonObject = buildJsonObject {
        put("text", text)
    }

    fun deactivate(reason: String): JsonObject = buildJsonObject {
        put("reason", reason)
    }
}
