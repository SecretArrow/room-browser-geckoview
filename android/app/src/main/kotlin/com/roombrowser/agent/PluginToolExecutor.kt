package com.roombrowser.agent

import com.roombrowser.data.repo.PluginRepository
import com.roombrowser.data.repo.decodePermissions
import com.roombrowser.domain.agent.ToolExecutor
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.plugins.PluginCard
import com.roombrowser.domain.plugins.PluginToolNaming
import com.roombrowser.node.PluginChatEntry

/**
 * The innermost wrap of a chat turn's executor chain: a plugin's tools,
 * intercepted by name before anything else answers, and everything else passed
 * to the page executor unchanged.
 *
 * WHY INNERMOST. The chain is Plan mode → withheld permissions → action
 * budget → THIS → page tools, so every gate above it applies to plugin tools
 * exactly as it does to clicks and posts: a plugin tool is refused in Plan
 * mode, costs the turn's budget, and is held back by a withheld
 * `agent.plugins` permission. None of those gates knows a plugin tool by
 * list — the names carry the `plugin_` prefix, and the gates match by shape —
 * so the catalogue can grow and shrink per chat without any static set
 * moving.
 *
 * WHAT THE MODEL READS BACK is the plugin's own output, fenced as untrusted
 * data by [AgentPluginRuntime.runTool] — the same fence a page's words get,
 * because a plugin's words are equally not instructions. What the USER reads
 * is the cards the plugin pushed while its tool ran, which ride beside the
 * tool card rather than inside the model's result.
 *
 * WHAT THE PLUGIN MAY ASK WHILE ITS TOOL RUNS is the chat's own AI — see
 * [generate] — and, since forms exist, the person: see [form]. The generator
 * is the turn's own on a chat turn (same gateway, same model), so a plugin
 * tool that composes text spends exactly the tokens the chat was already
 * spending, and the gates above this wrap apply to the tool itself while the
 * generation rides its own 20 s budget inside it.
 *
 * THE MISSING-INPUTS GATE is the last gate before the tool's own event: a
 * plugin whose manifest declares required inputs runs only after they were
 * answered, and an unanswered set raises the form (the same dialog every
 * other gate raises, [PluginFormContext.TOOL_CALL]) and awaits it. The
 * refusals above — Plan mode, a withheld `agent.plugins`, the budget — stay
 * FIRST, because a tool that must not run at all must not be able to open a
 * dialog either; and a chain with no form host (a probe, a scheduled run, a
 * headless turn — nobody to ask) short-circuits to the instructive refusal
 * rather than holding the turn for a dialog that can never open.
 */
class PluginToolExecutor(
    private val inner: ToolExecutor,
    private val runtime: AgentPluginRuntime,
    private val catalog: AgentPluginCatalog,
    private val sessionId: Long,
    /**
     * The chat's transcript as the plugins are allowed to read it, read at
     * call time rather than turn time: a tool call can arrive several steps
     * into a turn, and the conversation it should see is the one that exists
     * then, not the one the turn started from.
     */
    private val history: suspend () -> List<PluginChatEntry> = { emptyList() },
    /** Cards a tool run pushed for the user; the turn appends them to the chat. */
    private val onCards: suspend (cards: List<PluginCard>, pluginName: String) -> Unit = { _, _ -> },
    /**
     * The chat's own text generator, resolved at CALL time rather than at
     * construction: a tool call can arrive several steps into a turn, and a
     * command can arrive before the session that owns it exists — the
     * generator the plugin should be able to ask is the one the chat has
     * then, not the one this wrapper was built under. Null (the default)
     * means generation is not available on this path at all.
     */
    private val generate: suspend () -> PluginGenerateCall? = { null },
    /**
     * The chat's form host — the interactive half of the missing-inputs gate
     * and the bridge for a mid-run `room.ui.form`. Null on every unattended
     * chain (the probe never builds one; scheduled runs and headless turns
     * are wired without): nobody to ask, so the gate answers the refusal
     * directly and a mid-run form gets the runtime's own no-dialog words.
     */
    private val form: PluginFormCall? = null,
    /**
     * The binding store the gate writes accepted answers to — and reads the
     * fresh binding back from, so the call carries what the person just
     * answered and not the probe's earlier snapshot. Null in the unattended
     * chains and the tests that do not exercise the gate; a stored answer
     * without a store still rides THIS call's request.
     */
    private val repo: PluginRepository? = null
) : ToolExecutor {

    override suspend fun execute(name: String, argsJson: String): ToolResult {
        if (!PluginToolNaming.isPluginTool(name)) return inner.execute(name, argsJson)
        val owner = catalog.toolOwner(sessionId, name)
            ?: return ToolResult(
                false,
                "tool '$name' is not available in this chat: the plugin that offered it is " +
                    "disabled, uninstalled, or not granted agent tools. Tell the user to check " +
                    "the chat's plugins rather than retrying the call."
            )
        val plugin = owner.sessionPlugin.plugin
        var binding = owner.sessionPlugin.binding
        // The gate reads the binding FRESH when a store is wired: the catalogue
        // snapshot it resolved the owner from can be a whole turn old, and an
        // answer an earlier call in THIS turn already stored would otherwise be
        // re-asked — the person's second "Allow and Run" for the same values.
        repo?.chatPluginsOnce(sessionId)?.firstOrNull { it.pluginId == plugin.id }
            ?.let { binding = it }
        // The missing-inputs gate, AFTER the gates above this wrap have had
        // their say: required inputs the binding never answered raise the
        // form and wait for it; anything but a completed form refuses the
        // call in words that tell the model what to do about it.
        val schema = PluginForms.schemaOf(plugin)
        val stored = PluginForms.storedValues(binding.inputsValuesJson)
        if (PluginForms.incomplete(schema, stored)) {
            val host = form ?: return ToolResult(false, INPUTS_REFUSAL)
            val outcome = host(
                PluginFormRequest(
                    pluginId = plugin.id,
                    pluginName = plugin.name,
                    context = PluginFormContext.TOOL_CALL,
                    fields = schema.fields,
                    existingValues = stored,
                    networkHosts = decodePermissions(plugin.networkAllowJson)
                )
            )
            val values = (outcome as? PluginFormOutcome.Submitted)?.values
                ?: return ToolResult(false, INPUTS_REFUSAL)
            repo?.setInputsValues(sessionId, plugin.id, values.toString())
            repo?.chatPluginsOnce(sessionId)?.firstOrNull { it.pluginId == plugin.id }
                ?.let { binding = it }
        }
        val run = runtime.runTool(
            sessionId = sessionId,
            plugin = plugin,
            binding = binding,
            toolName = owner.toolName,
            argsJson = argsJson,
            history = history(),
            generate = generate(),
            form = form
        )
        if (run.cards.isNotEmpty()) {
            runCatching { onCards(run.cards, owner.sessionPlugin.plugin.name) }
        }
        return run.result
    }

    private companion object {
        /**
         * The instructive refusal a tool call earns when its plugin's required
         * inputs were never answered — unfenced like every other plugin
         * failure, because these are the app's own words telling the MODEL
         * what to do next, not the plugin's output fenced as data.
         */
        const val INPUTS_REFUSAL =
            "the user did not complete the form for this plugin — ask them to fill it via /plugins"
    }
}
