package com.roombrowser.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.roombrowser.data.db.ChatPluginEntity
import com.roombrowser.data.db.PluginEntity
import com.roombrowser.data.plugins.PluginInstallResult
import com.roombrowser.data.repo.PluginRepository
import com.roombrowser.data.repo.decodePermissions
import com.roombrowser.domain.plugins.PluginCard
import com.roombrowser.domain.plugins.PluginCommand
import com.roombrowser.domain.plugins.PluginCommandParser
import com.roombrowser.domain.plugins.PluginPermissions
import com.roombrowser.node.PluginCallRequest
import com.roombrowser.node.PluginChatEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * One enable waiting on the person's consent: what the plugin asks for, in
 * words. The permission sentences are [PluginPermissions.labelOf] answers
 * (the network one already carries its hosts), and [networkHosts] rides
 * along so a dialog that wants to list them separately can — the hosts ARE
 * the scope of what is being agreed to, and a dialog that hid them would be
 * consenting to something wider than the allowlist the plugin can ever use.
 */
data class PendingPluginGrant(
    val pluginId: String,
    val pluginName: String,
    val permissionLabels: List<String>,
    val networkHosts: List<String>
)

/**
 * The plugin surface of the chat panel, controller-owned: the installed
 * plugins, this chat's bindings, the picker sheet's visibility, and the
 * enable-time grant dialog.
 *
 * STATE CONVENTION: Compose `mutableStateOf` exactly like every other surface
 * the controller exposes (`entries`, `running`, `approval`), so the panel and
 * the sheets read them the same way — recomposition is the notification. The
 * one deliberate difference from [BrowserAgentController]'s own fields is
 * that the WRITERS live here: the controller flips [pickerVisible] up (a
 * `/plugins`, a fresh chat with plugins installed) and the sheet flips it
 * down, and everything else is this class's own doing.
 *
 * WHY THE CHAT'S ROW MAY NOT EXIST YET. A brand-new chat has no session row
 * until its first message, but the picker opens on it anyway and `/greet`
 * never sends a turn at all — so the first plugin enable into such a chat
 * CREATES the row (through [createSession], which owns the provider/model
 * resolution that a first message would have done) and binds it to the tab
 * the panel is on. The alternative — refusing to enable until a message is
 * sent — would make the very first thing the plugin feature promises
 * ("choose a plugin when creating a chat") impossible.
 */
class PluginUiState(
    private val scope: CoroutineScope,
    private val repo: PluginRepository,
    private val runtime: AgentPluginRuntime,
    private val catalog: AgentPluginCatalog,
    /** The chat the panel is showing, read live; null before the first message. */
    private val sessionId: () -> Long?,
    /** Creates the session row a plugin is being enabled into when the chat has none yet. */
    private val createSession: suspend (title: String) -> Long?,
    /** Appends one plugin card to the chat the panel is showing (and persists it). */
    private val onCard: suspend (sessionId: Long, card: PluginCard, pluginName: String) -> Unit,
    /** Appends a plain notice line to the chat. */
    private val onNotice: (text: String, error: Boolean) -> Unit,
    /** The chat's transcript as a `chat.context` grant reads it. */
    private val history: suspend (sessionId: Long) -> List<PluginChatEntry>
) {

    var installed by mutableStateOf<List<PluginEntity>>(emptyList())
        private set
    var chatPlugins by mutableStateOf<List<ChatPluginEntity>>(emptyList())
        private set
    var pickerVisible by mutableStateOf(false)
        private set
    var pendingGrant by mutableStateOf<PendingPluginGrant?>(null)
        private set

    private var chatPluginsJob: Job? = null

    /**
     * Starts the collectors. Called once from the controller's own start, on
     * the same scope — the installed list flows from the database (the
     * manager activity's installs arrive by themselves), and the bindings
     * re-subscribe whenever the panel moves to another chat.
     */
    fun start() {
        scope.launch { repo.observeInstalled().collect { installed = it } }
        scope.launch {
            snapshotFlow { sessionId() }.collect { id ->
                chatPluginsJob?.cancel()
                if (id == null) {
                    chatPlugins = emptyList()
                } else {
                    chatPluginsJob = scope.launch {
                        repo.observeChatPlugins(id).collect { chatPlugins = it }
                    }
                }
            }
        }
    }

    /** The sheet asks to be shown — `/plugins`, or a fresh chat with plugins installed. */
    fun openPicker() {
        pickerVisible = true
    }

    /** The sheet's own dismissal; the only writer besides [openPicker]. */
    fun dismissPicker() {
        pickerVisible = false
    }

    /**
     * Parses [text] as a plugin `/`-command against the chat's enabled
     * plugins and handles it — sheet, toggle, plugin command, ambiguity
     * notice — answering TRUE when the text is consumed and no agent turn
     * should run. [sessionAtTap] is the chat the message was sent into, read
     * on the user's own tap: a tab resolve that lands while the command is
     * being handled must not reroute it into another chat's bindings.
     */
    suspend fun interceptChatCommand(text: String, sessionAtTap: Long?): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return false
        val commands = sessionAtTap
            ?.let { id -> runCatching { catalog.commandOwners(id) }.getOrElse { emptyMap() } }
            ?: emptyMap()
        return when (val command = PluginCommandParser.parse(trimmed, commands)) {
            PluginCommand.ListPlugins -> {
                openPicker()
                true
            }
            is PluginCommand.Toggle -> {
                resolveToggle(command.query, sessionAtTap)
                true
            }
            is PluginCommand.Plugin -> {
                dispatchCommand(command.name, command.args, sessionAtTap)
                true
            }
            is PluginCommand.Ambiguous -> {
                onNotice(
                    "\"/${command.name}\" is declared by ${command.owners.joinToString(" and ")} — " +
                        "enable only one of them, or switch the other off in the chat plugins sheet.",
                    false
                )
                true
            }
            PluginCommand.NotACommand -> false
        }
    }

    /**
     * The sheet's switch: turns [pluginId] on or off for the chat the panel
     * is showing. Turning on may first raise the grant dialog — see
     * [pendingGrant]; nothing is enabled until it is answered.
     */
    fun setEnabled(pluginId: String, enabled: Boolean) {
        scope.launch {
            val plugin = repo.installedOnce().firstOrNull { it.id == pluginId } ?: return@launch
            if (enabled) {
                val session = sessionId() ?: createSession(plugin.name) ?: return@launch
                enablePlugin(plugin, session)
            } else {
                val session = sessionId() ?: return@launch
                disablePlugin(plugin, session)
            }
        }
    }

    /**
     * The grant dialog's Accept: [granted] is what the person agreed to
     * (the whole declared set, as the dialog presents it), stored as given.
     */
    fun submitGrants(granted: List<String>) {
        val request = pendingGrant ?: return
        pendingGrant = null
        scope.launch {
            val plugin = repo.installedOnce().firstOrNull { it.id == request.pluginId } ?: return@launch
            val session = sessionId() ?: createSession(plugin.name) ?: return@launch
            val declared = decodePermissions(plugin.permissionsJson)
            enableNow(plugin, session, granted.filter { it in declared })
        }
    }

    /** The grant dialog's Cancel: nothing is enabled, nothing is stored. */
    fun cancelGrant() {
        val request = pendingGrant ?: return
        pendingGrant = null
        onNotice("${request.pluginName} was not enabled — no permissions were granted.", false)
    }

    /**
     * Re-reads everything from the database and drops the catalogue's cache.
     * The flows deliver ordinary edits by themselves; this is for the edits a
     * flow cannot see (another process's uninstall) and for a caller that
     * wants the truth now.
     */
    fun refresh() {
        scope.launch {
            runCatching { installed = repo.installedOnce() }
            sessionId()?.let { id -> runCatching { chatPlugins = repo.chatPluginsOnce(id) } }
            catalog.invalidateAll()
        }
    }

    /**
     * Runs a plugin command by name in the chat the panel is showing — the
     * sheet's shortcut for a plugin's own command row, the same event
     * `/<name>` dispatches.
     */
    fun runCommand(name: String, args: String) {
        scope.launch {
            val session = sessionId() ?: return@launch
            dispatchCommand(name, args, session)
        }
    }

    /**
     * The install hook: the picker (or the manager) reports what an install
     * produced, and the chat says so — a refusal lists every reason, because
     * a manifest fixed one error at a time is fixed three times slower.
     */
    fun onInstallResult(result: PluginInstallResult) {
        scope.launch {
            when (result) {
                is PluginInstallResult.ManifestOk -> {
                    catalog.invalidateAll()
                    onNotice(
                        "${result.manifest.name} ${result.manifest.version} installed. Turn it " +
                            "on for this chat here, or with /plugins.",
                        false
                    )
                }
                is PluginInstallResult.Invalid -> onNotice(
                    "The plugin was not installed:\n" + result.errors.joinToString("\n") { "— $it" },
                    true
                )
            }
        }
    }

    // ------------------------------------------------------------- internals

    /** Resolves a `/plugins <query>` against the installed list and flips the binding. */
    private suspend fun resolveToggle(query: String, sessionAtTap: Long?) {
        val installed = repo.installedOnce()
        val plugin = installed.firstOrNull { it.id == query }
            ?: installed.firstOrNull { it.name == query }
        if (plugin == null) {
            onNotice(
                if (installed.isEmpty()) {
                    "No plugins are installed. Install one from a ZIP with the puzzle button in " +
                        "the composer."
                } else {
                    "No installed plugin matches \"$query\". Installed: " +
                        installed.joinToString(", ") { it.name } + "."
                },
                false
            )
            return
        }
        val session = sessionAtTap ?: createSession(plugin.name) ?: return
        val enabled = repo.chatPluginsOnce(session).any { it.pluginId == plugin.id && it.enabled }
        if (enabled) disablePlugin(plugin, session) else enablePlugin(plugin, session)
    }

    /**
     * Enables [plugin] into [session], raising the grant dialog first when
     * the manifest declares permissions the chat has not consented to yet.
     * A binding that already covers them all is re-enabled silently —
     * switching off and back on is not a re-consent.
     */
    private suspend fun enablePlugin(plugin: PluginEntity, session: Long) {
        val declared = decodePermissions(plugin.permissionsJson)
        val granted = repo.chatPluginsOnce(session)
            .firstOrNull { it.pluginId == plugin.id }
            ?.let { decodePermissions(it.grantedPermissionsJson) }
            .orEmpty()
        if (declared.isNotEmpty() && !declared.all { it in granted }) {
            val hosts = decodePermissions(plugin.networkAllowJson)
            pendingGrant = PendingPluginGrant(
                pluginId = plugin.id,
                pluginName = plugin.name,
                permissionLabels = declared.map { PluginPermissions.labelOf(it, hosts) },
                networkHosts = hosts
            )
            return
        }
        enableNow(plugin, session, if (declared.all { it in granted }) granted else declared)
    }

    private suspend fun enableNow(plugin: PluginEntity, session: Long, granted: List<String>) {
        repo.setEnabled(session, plugin.id, enabled = true, grantedPermissions = granted)
        catalog.invalidate(session)
        val binding = repo.chatPluginsOnce(session).firstOrNull { it.pluginId == plugin.id }
            ?: return
        // The probe is the catalogue's own (invalidate + read re-probes every
        // binding); its failure is said here because the enable is the moment
        // the person chose to trust this code, and a plugin that cannot even
        // load should not fail silently behind a switch that says it is on.
        catalog.sessionPlugins(session)
            .firstOrNull { it.plugin.id == plugin.id && !it.probeOk }
            ?.let { failed ->
                onNotice(
                    "${plugin.name} is on, but it could not be loaded: " +
                        firstLine(failed.probeError),
                    true
                )
            }
        val activated = runtime.run(
            sessionId = session,
            plugin = plugin,
            binding = binding,
            event = PluginCallRequest.EVENT_ACTIVATE,
            history = history(session)
        )
        if (activated.ok) {
            appendCards(session, activated.cards, plugin.name)
            onNotice("${plugin.name} is on for this chat.", false)
        } else {
            onNotice(
                "${plugin.name} is on, but its start-up failed: " + firstLine(activated.output),
                true
            )
        }
    }

    private suspend fun disablePlugin(plugin: PluginEntity, session: Long) {
        val binding = repo.chatPluginsOnce(session).firstOrNull { it.pluginId == plugin.id }
        if (binding != null) {
            // The grants travel with the disable: switching off is not a
            // re-consent, so the row keeps what the person already agreed to.
            repo.setEnabled(
                session,
                plugin.id,
                enabled = false,
                grantedPermissions = decodePermissions(binding.grantedPermissionsJson)
            )
            runCatching {
                runtime.run(
                    sessionId = session,
                    plugin = plugin,
                    binding = binding,
                    event = PluginCallRequest.EVENT_DEACTIVATE,
                    payload = PluginPayloads.deactivate("disabled"),
                    history = history(session),
                    budgetMs = AgentPluginRuntime.DEACTIVATE_BUDGET_MS
                )
            }
        }
        catalog.invalidate(session)
        onNotice("${plugin.name} is off for this chat.", false)
    }

    /** Runs one plugin command event and shows what it answered. */
    private suspend fun dispatchCommand(name: String, args: String, session: Long?) {
        val chat = session ?: createSession("Plugins") ?: return
        val owner = catalog.sessionPlugins(chat)
            .firstOrNull { plugin -> plugin.manifest.commands.any { it.name == name } }
        if (owner == null) {
            onNotice("\"/$name\" is no longer available in this chat.", true)
            return
        }
        val result = runtime.run(
            sessionId = chat,
            plugin = owner.plugin,
            binding = owner.binding,
            event = PluginCallRequest.EVENT_COMMAND,
            payload = PluginPayloads.command(name, args),
            history = history(chat)
        )
        if (result.ok) {
            appendCards(chat, result.cards, owner.plugin.name)
            if (result.cards.isEmpty()) {
                onNotice("${owner.plugin.name} ran \"/$name\" and showed nothing.", false)
            }
        } else {
            onNotice(
                "${owner.plugin.name} failed on \"/$name\": " + firstLine(result.output),
                true
            )
        }
    }

    private suspend fun appendCards(
        session: Long,
        cards: List<JsonObject>,
        pluginName: String
    ) {
        PluginCard.parseList(JsonArray(cards)).forEach { card ->
            runCatching { onCard(session, card, pluginName) }
        }
    }

    private fun firstLine(text: String?): String =
        text.orEmpty().lineSequence().firstOrNull().orEmpty()
}
