package com.roombrowser.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.roombrowser.data.db.ChatPluginEntity
import com.roombrowser.data.db.PluginEntity
import com.roombrowser.data.plugins.PluginDirZipper
import com.roombrowser.data.plugins.PluginInstallResult
import com.roombrowser.data.repo.PluginRepository
import com.roombrowser.data.repo.decodePermissions
import com.roombrowser.domain.plugins.PluginCard
import com.roombrowser.domain.plugins.PluginCommand
import com.roombrowser.domain.plugins.PluginCommandParser
import com.roombrowser.domain.plugins.PluginComposeParser
import com.roombrowser.domain.plugins.PluginComposePrompt
import com.roombrowser.domain.plugins.PluginFormValidator
import com.roombrowser.domain.plugins.PluginFormat
import com.roombrowser.domain.plugins.PluginInputField
import com.roombrowser.domain.plugins.PluginInputsSchema
import com.roombrowser.domain.plugins.PluginManifestValidator
import com.roombrowser.domain.plugins.PluginPermissions
import com.roombrowser.node.PluginCallRequest
import com.roombrowser.node.PluginChatEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * One enable waiting on the person's consent: what the plugin asks for, in
 * words. The permission sentences are [PluginPermissions.labelOf] answers
 * (the network one already carries its hosts), and [networkHosts] rides
 * along so a dialog that wants to list them separately can — the hosts ARE
 * the scope of what is being agreed to, and a dialog that hid them would be
 * consenting to something wider than the allowlist the plugin can ever use.
 *
 * The combined dialog's form half rides here too (design §3.2): [format] is
 * the row's manifest format (the title and the accept button spell by it),
 * [fields] is the manifest's `inputs` schema (an empty list is a plain
 * permission dialog — nothing to edit), and [existingValues] is what this
 * chat already stored (an edit's pre-fill, the keep-old secret rule's
 * "old"). [permissionNames] is the DECLARED set as names — the dialog's
 * bullets carry sentences, and mapping a sentence back to its name through
 * [PluginPermissions.labelOf] cannot recover the module-only `files`
 * permission, so the names ride along for the accept call to hand straight
 * through.
 */
data class PendingPluginGrant(
    val pluginId: String,
    val pluginName: String,
    val permissionLabels: List<String>,
    val networkHosts: List<String>,
    /** The row's manifest format — see [PluginFormat]; the combined dialog titles by it. */
    val format: String = PluginFormat.PLUGIN,
    /** The manifest's `inputs` fields; empty when the plugin declares no form. */
    val fields: List<PluginInputField> = emptyList(),
    /** What this chat already stored for those fields — the edit pre-fill and the keep-old rule's old. */
    val existingValues: JsonObject = JsonObject(emptyMap()),
    /** The declared permission NAMES, in the dialog's bullet order — what the accept hands back. */
    val permissionNames: List<String> = emptyList()
)

/**
 * One input form waiting on the person — the state every form gate raises,
 * exactly the shape the dialog layer renders: which plugin is asking, which
 * fields (the manifest's or the runtime request's), at which
 * [PluginFormContext] moment, and what the chat already stored for those
 * fields (the keep-old secret rule's "old", and an edit dialog's
 * pre-fill).
 *
 * [title] and [description] carry the RUNTIME request's own headline and
 * helper line; the activation and tool-call contexts leave them null and
 * title themselves off [pluginName] and [context]. [networkHosts] is the
 * row's allowlist frozen at raise time — the dialog's permission-context
 * line when the plugin holds network.
 *
 * The completion is the dialog's whole contract: [PluginUiState.submitForm]
 * completes it with the validated, normalized values; [PluginUiState.cancelForm]
 * completes it with null. Whoever raised the form awaits it (with the
 * 300 s cap [askForm] enforces) and reads the answer as a
 * [PluginFormOutcome].
 */
class PendingPluginForm(
    val pluginId: String,
    val pluginName: String,
    val context: PluginFormContext,
    val fields: List<PluginInputField>,
    val title: String? = null,
    val description: String? = null,
    val existingValues: JsonObject = JsonObject(emptyMap()),
    val networkHosts: List<String> = emptyList()
) {
    private val completion = CompletableDeferred<JsonObject?>()

    /** The values the person submitted, or null when they did not. */
    internal suspend fun await(): JsonObject? = completion.await()

    internal fun complete(values: JsonObject?) {
        completion.complete(values)
    }
}

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
    private val history: suspend (sessionId: Long) -> List<PluginChatEntry>,
    /**
     * Whether the chat has sent no user message yet — the fresh-chat question
     * staged activation turns on. The controller owns the answer (it is the
     * one that can read the rows); the default says "not fresh", so a caller
     * that wires nothing keeps today's immediate activation.
     */
    private val freshChat: suspend (sessionId: Long) -> Boolean = { false },
    /**
     * The chat's own text generator, resolved per event: the session's own
     * provider and model (the controller's [pluginGeneratorFor]-shape
     * wiring), or null when the chat has none — in which case the plugin's
     * askAgent is answered by the runtime's own "not available" words
     * rather than by this class inventing an error of its own. Read per
     * event and not once at construction, because a command or activation
     * can be the first thing that ever creates the session it names.
     */
    private val generate: suspend (sessionId: Long) -> PluginGenerateCall? = { null },
    /**
     * The chat's own plugin composer, resolved per command: the session's
     * own provider and model, or null when the chat has none. Read per
     * command and not once at construction, for the same reason [generate]
     * is — a `/make_plugins` on a brand-new chat can be the first thing that
     * ever creates the session it names, and the composer must resolve
     * against what the chat became, not what it was.
     */
    private val compose: suspend (sessionId: Long) -> PluginComposeCall? = { null }
) {

    var installed by mutableStateOf<List<PluginEntity>>(emptyList())
        private set
    var chatPlugins by mutableStateOf<List<ChatPluginEntity>>(emptyList())
        private set
    var pickerVisible by mutableStateOf(false)
        private set
    var pendingGrant by mutableStateOf<PendingPluginGrant?>(null)
        private set

    /**
     * The input form a gate raised and is waiting on, or null. Same state
     * convention as [pendingGrant]: the dialog layer reads it, recomposition
     * is the notification, and only [submitForm] and [cancelForm] retire it.
     */
    var pendingForm by mutableStateOf<PendingPluginForm?>(null)
        private set

    /**
     * One composed plugin waiting on the person's Allow-and-Create answer, or
     * null — the `pendingGrant`/`pendingForm` convention again: the dialog
     * layer reads it, recomposition is the notification, and only
     * [submitCreation] and [cancelCreation] retire it. Nothing is installed
     * while it is up; the manifest it carries is a validated DRAFT, and the
     * form's rewritten manifest is the only thing that ever installs.
     */
    var pendingCreation by mutableStateOf<PendingPluginCreation?>(null)
        private set

    /**
     * This surface as a [PluginFormCall] — the host every event IT runs
     * (activations, commands, the staged module runs) wires, and the host the
     * controller hands the turn's executor and hooks. One instance so every
     * ask lands on the same state.
     */
    val formHost: PluginFormCall = PluginFormCall { request -> askForm(request) }

    private var chatPluginsJob: Job? = null

    /**
     * Starts the collectors. Called once from the controller's own start, on
     * the same scope — the installed list flows from the database (the
     * manager activity's installs arrive by themselves), and the bindings
     * re-subscribe whenever the panel moves to another chat.
     */
    fun start() {
        scope.launch {
            // Catalogue freshness across processes (design §2.5): the probe
            // cache is invalidated ONLY when an emission actually changed
            // some row's updated_at — the fingerprint an install upsert moves
            // and a prompt_staged toggle re-emits unchanged. Without this, an
            // editor or manager save in the DEFAULT process would leave this
            // ':browser' process serving stale tools/hooks until the plugin's
            // next disable/enable; with it, a toggle costs no re-probe (the
            // first emission's clear lands on an empty cache, a no-op).
            var stamps: Map<String, Long>? = null
            repo.observeInstalled().collect { list ->
                installed = list
                val fresh = list.associate { it.id to it.updatedAt }
                if (stamps != fresh) catalog.invalidateAll()
                stamps = fresh
            }
        }
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
            is PluginCommand.MakePlugins -> {
                makePlugins(command.description, sessionAtTap)
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
     * [inputsValues] is the form half of a COMBINED consent dialog — the
     * manifest's `inputs` answers collected in the same dialog as the
     * permissions, stored with the grants so the activation (immediate or
     * staged) finds them already answered. Null is the plain permission
     * dialog this build has always had.
     */
    fun submitGrants(granted: List<String>, inputsValues: JsonObject? = null) {
        val request = pendingGrant ?: return
        pendingGrant = null
        scope.launch {
            val plugin = repo.installedOnce().firstOrNull { it.id == request.pluginId } ?: return@launch
            val session = sessionId() ?: createSession(plugin.name) ?: return@launch
            val declared = decodePermissions(plugin.permissionsJson)
            enableNow(plugin, session, granted.filter { it in declared }, inputsValues)
        }
    }

    /** The grant dialog's Cancel: nothing is enabled, nothing is stored. */
    fun cancelGrant() {
        val request = pendingGrant ?: return
        pendingGrant = null
        onNotice("${request.pluginName} was not enabled — no permissions were granted.", false)
    }

    /**
     * The form dialog's submit: [values] is the whole editor state. The
     * single validator is the backstop here — a submit that does not pass it
     * changes nothing (the form stays up, the raiser keeps waiting) because
     * "an empty error map is the only thing that unlocks" has to be true
     * even behind a dialog that forgot to disable its own button. A valid
     * submit retires the form and completes it with the NORMALIZED values —
     * keep-old secrets resolved, defaults filled, unknown keys dropped —
     * which is the one shape anything downstream stores or runs with.
     */
    fun submitForm(values: JsonObject) {
        val form = pendingForm ?: return
        val schema = PluginInputsSchema(form.fields)
        if (PluginFormValidator.validate(schema, values, form.existingValues).isNotEmpty()) return
        val normalized = PluginFormValidator.normalizedValues(schema, values, form.existingValues)
        pendingForm = null
        form.complete(normalized)
    }

    /** The form dialog's Cancel: the form retires unanswered, and nothing runs on it. */
    fun cancelForm() {
        val form = pendingForm ?: return
        pendingForm = null
        form.complete(null)
    }

    /**
     * The sheet's "Edit inputs" pencil (design §3.2): re-asks the plugin's
     * form with the values this chat already stored as the starting state —
     * editing is the ONLY way to change stored values besides a reinstall.
     * A completed edit stores the new answers and, for a module this chat
     * has enabled, runs its activate again with them ("Save and run"); a
     * native plugin only saves — its activations are not per-answer — and a
     * plugin whose manifest declares no inputs has nothing to edit, so the
     * pencil never appears and this returns without a dialog.
     */
    fun editInputs(pluginId: String) {
        scope.launch {
            val session = sessionId() ?: return@launch
            val plugin = repo.installedOnce().firstOrNull { it.id == pluginId } ?: return@launch
            val binding = repo.chatPluginsOnce(session).firstOrNull { it.pluginId == pluginId }
                ?: return@launch
            val schema = PluginForms.schemaOf(plugin)
            if (schema.fields.isEmpty()) return@launch
            val stored = PluginForms.storedValues(binding.inputsValuesJson)
            val outcome = askForm(
                PluginFormRequest(
                    pluginId = plugin.id,
                    pluginName = plugin.name,
                    context = PluginFormContext.ACTIVATION,
                    fields = schema.fields,
                    existingValues = stored,
                    networkHosts = decodePermissions(plugin.networkAllowJson)
                )
            )
            if (outcome !is PluginFormOutcome.Submitted) {
                onNotice("${plugin.name}'s inputs were not changed.", false)
                return@launch
            }
            repo.setInputsValues(session, plugin.id, outcome.values.toString())
            if (plugin.format != PluginFormat.ZAGENTS || !binding.enabled) {
                onNotice("${plugin.name}'s inputs were saved.", false)
                return@launch
            }
            // A module this chat has on runs again with the new answers — its
            // whole point is the entry run, and the person just changed what
            // the entry will read.
            val fresh = repo.chatPluginsOnce(session).firstOrNull { it.pluginId == plugin.id }
                ?: return@launch
            runActivation(
                session = session,
                plugin = plugin,
                binding = fresh,
                successNotice = "${plugin.name} ran again with the new inputs."
            )
        }
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

    // ---------------------------------------------------------- the plugin maker

    /**
     * The `/make_plugins <description>` flow (design §5.2): the chat's OWN
     * AI composes a plugin from the description, ONE non-streaming
     * generation per attempt, with exactly ONE corrective retry whose input
     * is the parser's own error list — a slow model that wanders costs the
     * user two notices and no loop, and a model that cannot produce a valid
     * plugin is told so definitively rather than retried forever.
     *
     * A `Draft` raises [pendingCreation] and stops — the person's review is
     * the only thing that installs anything. The model's reply is untrusted
     * input end to end: the parser holds it to the install validator before
     * a draft exists, and the form's REWRITTEN manifest (not the draft) is
     * what a later submit installs.
     */
    private suspend fun makePlugins(description: String, sessionAtTap: Long?) {
        // The cap is checked before anything else composes: a description
        // over the cap is refused before a single provider byte is spent.
        if (description.length > PluginComposePrompt.DESCRIPTION_CHARS) {
            onNotice(
                "/make_plugins needs a description of at most " +
                    "${PluginComposePrompt.DESCRIPTION_CHARS} characters.",
                false
            )
            return
        }
        if (description.isBlank()) {
            onNotice(
                "/make_plugins <description> asks this chat's own AI to draft a plugin from " +
                    "your description. You review the draft — name, description, permissions, " +
                    "network hosts, and the full code — before anything installs. Example: " +
                    "/make_plugins a tiny plugin that counts greetings per chat and answers " +
                    "with a card.",
                false
            )
            return
        }
        val session = sessionAtTap ?: createSession("Plugin maker") ?: return
        val composer = compose(session) ?: run {
            onNotice(
                "This chat has no AI to compose with — configure the chat's provider and " +
                    "model, then try /make_plugins again.",
                true
            )
            return
        }
        onNotice("Composing a plugin from your description with this chat's AI…", false)
        when (val first = composeAttempt(composer, description, retryNote = null)) {
            is PluginComposeParser.Parsed.Draft -> {
                pendingCreation = PendingPluginCreation(first.manifest, first.entrySource)
                return
            }
            is PluginComposeParser.Parsed.Invalid -> {
                onNotice(
                    "The first draft broke the plugin rules — asking the chat's AI to correct it…",
                    false
                )
                when (
                    val second = composeAttempt(
                        composer,
                        description,
                        PluginComposeParser.retryNote(first.errors)
                    )
                ) {
                    is PluginComposeParser.Parsed.Draft ->
                        pendingCreation = PendingPluginCreation(second.manifest, second.entrySource)
                    is PluginComposeParser.Parsed.Invalid -> onNotice(
                        "The chat's AI could not produce a valid plugin:\n" +
                            second.errors.joinToString("\n") { "— $it" },
                        true
                    )
                }
            }
        }
    }

    /**
     * One composition attempt: the composer's raw reply parsed, or the
     * failure's own words as the error list — a provider error, the budget
     * timeout and "the chat's model wrote no text" travel the same list the
     * parse refusals do, so the retry and the definitive refusal read them
     * identically.
     */
    private suspend fun composeAttempt(
        composer: PluginComposeCall,
        description: String,
        retryNote: String?
    ): PluginComposeParser.Parsed {
        val reply = runCatching { composer(description, retryNote) }.getOrElse { err ->
            return PluginComposeParser.Parsed.Invalid(
                listOf(err.message ?: err.javaClass.simpleName)
            )
        }
        return PluginComposeParser.parse(reply)
    }

    /**
     * The creation dialog's Allow and Create: the form's answer rewritten
     * through [PendingPluginCreation.candidate], the single validator as the
     * backstop (the dialog gated on this same answer; a submit that somehow
     * slips past it changes nothing and the form stays up — the
     * Allow-and-Run rule), and the install built in memory — `plugin.json`
     * plus the composed entry at the manifest's own declared path — and fed
     * through the SAME [PluginRepository.install] door a sideloaded ZIP
     * enters. Nothing was installed before this; nothing auto-enables after.
     */
    fun submitCreation(
        name: String,
        description: String,
        permissions: List<String>,
        hosts: List<String>
    ) {
        val pending = pendingCreation ?: return
        scope.launch {
            val manifest = pending.candidate(name, description, permissions, hosts)
            if (PluginManifestValidator.validate(manifest).isNotEmpty()) return@launch
            val zip = PluginDirZipper.compose(
                listOf(
                    "plugin.json" to manifest.toString().toByteArray(Charsets.UTF_8),
                    pending.entryPath to pending.entrySource.toByteArray(Charsets.UTF_8)
                )
            )
            when (val result = repo.install(zip.inputStream())) {
                is PluginInstallResult.ManifestOk -> {
                    catalog.invalidateAll()
                    pendingCreation = null
                    onNotice(
                        "${result.manifest.name} ${result.manifest.version} installed. Review or " +
                            "edit its files from Manage plugins, then turn it on for this chat " +
                            "with /plugins.",
                        false
                    )
                }
                is PluginInstallResult.Invalid ->
                    // Near-impossible (the same validator passed twice); still
                    // the honest shape: every reason, and the form stays up to
                    // fix them.
                    onNotice(
                        "The plugin was not installed:\n" +
                            result.errors.joinToString("\n") { "— $it" },
                        true
                    )
            }
        }
    }

    /** The creation dialog's Cancel: the draft is discarded, nothing was installed. */
    fun cancelCreation() {
        if (pendingCreation == null) return
        pendingCreation = null
        onNotice("The plugin draft was discarded — nothing was installed.", false)
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
        val existing = repo.chatPluginsOnce(session).firstOrNull { it.pluginId == plugin.id }
        val granted = existing
            ?.let { decodePermissions(it.grantedPermissionsJson) }
            .orEmpty()
        if (declared.isNotEmpty() && !declared.all { it in granted }) {
            val hosts = decodePermissions(plugin.networkAllowJson)
            pendingGrant = PendingPluginGrant(
                pluginId = plugin.id,
                pluginName = plugin.name,
                permissionLabels = declared.map { PluginPermissions.labelOf(it, hosts) },
                networkHosts = hosts,
                format = plugin.format,
                fields = PluginForms.schemaOf(plugin).fields,
                existingValues = PluginForms.storedValues(existing?.inputsValuesJson),
                permissionNames = declared
            )
            return
        }
        enableNow(plugin, session, if (declared.all { it in granted }) granted else declared)
    }

    private suspend fun enableNow(
        plugin: PluginEntity,
        session: Long,
        granted: List<String>,
        inputsValues: JsonObject? = null
    ) {
        repo.setEnabled(session, plugin.id, enabled = true, grantedPermissions = granted)
        // The combined consent dialog's form half, when the manifest asks for
        // one and the dialog carried it: validated and normalized HERE, so the
        // stored shape is the canonical one even behind a dialog that skipped
        // its own normalization — and an INVALID set is not stored at all, the
        // gate below re-raising it instead of running on garbage.
        inputsValues?.let { values ->
            val schema = PluginForms.schemaOf(plugin)
            val existing = PluginForms.storedValues(repo.inputsValues(session, plugin.id))
            if (PluginFormValidator.validate(schema, values, existing).isEmpty()) {
                repo.setInputsValues(
                    session,
                    plugin.id,
                    PluginFormValidator.normalizedValues(schema, values, existing).toString()
                )
            }
        }
        catalog.invalidate(session)
        var binding = repo.chatPluginsOnce(session).firstOrNull { it.pluginId == plugin.id }
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
        // A module enabled on a chat that has sent nothing yet does NOT run
        // now — it stages: the chat's FIRST user message becomes the entry's
        // prompt (design §1.3), which is the message order every ZAgents
        // module was written against. The plugin.json format keeps its
        // immediate activation below, unchanged.
        if (plugin.format == PluginFormat.ZAGENTS && freshChat(session)) {
            repo.setPromptStaged(plugin.id, true)
            onNotice(
                "${plugin.name} is on for this chat. It runs with this chat's first message.",
                false
            )
            return
        }
        // The activation gate: a plugin whose manifest declares required
        // inputs runs only after they were answered — the backstop behind the
        // consent dialog's own form half, and the only gate on the re-run and
        // edit-inputs paths. A form the person does not complete activates
        // nothing; the binding keeps what it had, and the next run asks again.
        val schema = PluginForms.schemaOf(plugin)
        val stored = PluginForms.storedValues(binding.inputsValuesJson)
        if (PluginForms.incomplete(schema, stored)) {
            val outcome = askForm(
                PluginFormRequest(
                    pluginId = plugin.id,
                    pluginName = plugin.name,
                    context = PluginFormContext.ACTIVATION,
                    fields = schema.fields,
                    existingValues = stored,
                    networkHosts = decodePermissions(plugin.networkAllowJson)
                )
            )
            when (outcome) {
                is PluginFormOutcome.Submitted -> {
                    // Stored before anything re-reads the binding, so the
                    // activation below — and the catalogue's next probe — see
                    // the answers the person just gave.
                    repo.setInputsValues(session, plugin.id, outcome.values.toString())
                    repo.chatPluginsOnce(session).firstOrNull { it.pluginId == plugin.id }
                        ?.let { binding = it }
                }
                else -> {
                    // An immediate activation cancels with nothing run; a stale
                    // staging from an earlier fresh-chat enable clears with it,
                    // so the module cannot wake up mid-chat unasked.
                    if (plugin.format == PluginFormat.ZAGENTS) repo.setPromptStaged(plugin.id, false)
                    onNotice("${plugin.name} was not run — its input form was not completed.", false)
                    return
                }
            }
        }
        // The immediate activation spends a module's once — any staging left
        // over from a fresh-chat enable that never ran clears with it. A
        // plugin.json row was never stageable, so it writes nothing here.
        runActivation(
            session = session,
            plugin = plugin,
            binding = binding,
            successNotice = "${plugin.name} is on for this chat."
        )
    }

    /**
     * One activate run and its reporting — the shared tail of an enable and an
     * edit's "Save and run". The success wording is the caller's ("is on" and
     * "ran again" are different promises); the failure wording is one
     * sentence, because a start-up failure reads the same wherever it
     * started. The staged first-message run reports differently (the module's
     * message body is the point there) and keeps its own tail.
     */
    private suspend fun runActivation(
        session: Long,
        plugin: PluginEntity,
        binding: ChatPluginEntity,
        successNotice: String
    ) {
        // A module's activation spends its once — any staging left over from a
        // fresh-chat enable that never ran clears with it. A plugin.json row
        // was never stageable, so it writes nothing here.
        if (plugin.format == PluginFormat.ZAGENTS) repo.setPromptStaged(plugin.id, false)
        val activated = runtime.run(
            sessionId = session,
            plugin = plugin,
            binding = binding,
            event = PluginCallRequest.EVENT_ACTIVATE,
            history = history(session),
            generate = generate(session),
            form = formHost
        )
        if (activated.ok) {
            appendCards(session, activated.cards, plugin.name)
            // A module's activation speaks through its composed output —
            // emits and console lines — the same body the staged
            // first-message run renders, so a re-enable's run is exactly as
            // visible as the run it reprises. A plugin.json row was never a
            // module-message shape: its cards-and-notice half is unchanged,
            // and a module that only pushed cards keeps them ahead of the
            // notice.
            if (plugin.format == PluginFormat.ZAGENTS && activated.output.isNotBlank()) {
                runCatching {
                    onCard(
                        session,
                        PluginCard(text = activated.output.take(PLUGIN_MESSAGE_CHARS)),
                        plugin.name
                    )
                }
            }
            onNotice(successNotice, false)
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
        // A module a chat switches off is not staged either: the flag is the
        // enable's promise to run on the first message, and a disable is the
        // chat taking that promise back.
        if (plugin.format == PluginFormat.ZAGENTS) {
            repo.setPromptStaged(plugin.id, false)
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
        // The person's own command is the send gate's promise again: a module
        // whose required inputs this chat never answered asks HERE — the
        // command is the chat being worked, and it is not worked without the
        // input. A cancelled form means the command does not run at all.
        var binding = owner.binding
        val schema = PluginForms.schemaOf(owner.plugin)
        val stored = PluginForms.storedValues(owner.binding.inputsValuesJson)
        if (PluginForms.incomplete(schema, stored)) {
            val outcome = askForm(
                PluginFormRequest(
                    pluginId = owner.plugin.id,
                    pluginName = owner.plugin.name,
                    context = PluginFormContext.SEND,
                    fields = schema.fields,
                    existingValues = stored,
                    networkHosts = decodePermissions(owner.plugin.networkAllowJson)
                )
            )
            val values = (outcome as? PluginFormOutcome.Submitted)?.values
            if (values == null) {
                onNotice("${owner.plugin.name} needs its input before \"/$name\" can run.", true)
                return
            }
            repo.setInputsValues(chat, owner.plugin.id, values.toString())
            // The binding the run reads must be the one the answers were just
            // stored on — the catalog's snapshot is older than this form.
            runCatching { repo.chatPluginsOnce(chat) }.getOrDefault(emptyList())
                .firstOrNull { it.pluginId == owner.plugin.id }
                ?.let { binding = it }
        }
        val result = runtime.run(
            sessionId = chat,
            plugin = owner.plugin,
            binding = binding,
            event = PluginCallRequest.EVENT_COMMAND,
            payload = PluginPayloads.command(name, args),
            history = history(chat),
            generate = generate(chat),
            form = formHost
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

    // -------------------------------------------------------- the form state

    /**
     * Raises [request]'s form and waits for the person, with the wire's own
     * [FORM_WAIT_MS] cap — every gate's one await, so the timeout, the cancel
     * and the no-dialog answers mean the same thing wherever a form was
     * raised.
     *
     * A form still up from an interrupted gate is CANCELLED rather than
     * orphaned: its raiser is gone, the dialog would never be answered, and
     * the state slot must be free for whoever is asking now.
     *
     * The state write may arrive off the main thread — the one caller that
     * does is the runtime's reverse bridge, whose runBlocking sits on an IO
     * thread — and the Compose snapshot system applies a write from any
     * thread safely, so the dialog still appears and the raiser still waits.
     * No chat on screen is [PluginFormOutcome.NoUi]: a host with nowhere to
     * put a dialog answers the caller in words it can pass on.
     */
    suspend fun askForm(
        request: PluginFormRequest,
        timeoutMs: Long = FORM_WAIT_MS
    ): PluginFormOutcome {
        if (sessionId() == null) return PluginFormOutcome.NoUi
        pendingForm?.let { cancelForm() }
        val form = PendingPluginForm(
            pluginId = request.pluginId,
            pluginName = request.pluginName,
            context = request.context,
            fields = request.fields,
            title = request.title,
            description = request.description,
            existingValues = request.existingValues,
            networkHosts = request.networkHosts
        )
        pendingForm = form
        return try {
            when (val values = withTimeout(timeoutMs) { form.await() }) {
                null -> PluginFormOutcome.Cancelled
                else -> PluginFormOutcome.Submitted(values)
            }
        } catch (err: TimeoutCancellationException) {
            // The wait outlived the person's patience: retire the form so no
            // dialog outlives its raiser, and answer the caller in the
            // outcome its own context words for a timeout.
            if (pendingForm === form) pendingForm = null
            PluginFormOutcome.TimedOut
        } catch (err: CancellationException) {
            // The raiser's own job was cancelled — a stopped turn, a dismissed
            // controller. Retire the form so no dialog outlives the coroutine
            // that raised it, then let the cancellation travel: this gate did
            // not answer, it ended.
            if (pendingForm === form) pendingForm = null
            throw err
        }
    }

    // ------------------------------------------------ the send-time input gate

    /**
     * The chat's own gate: every ENABLED binding this chat holds whose
     * plugin declared required inputs the chat never answered gets its form
     * raised HERE — before the turn writes the user's row or calls the model
     * — and the message is held until every one of them comes back complete.
     *
     * The person's requirement this gate serves is one sentence: a chat that
     * carries a module needing input is NOT worked until that input is
     * given. The staged-activation and tool-call gates stay as they are —
     * the staged path finds its values already answered when its own moment
     * comes (this gate stored them), and the tool gate is the depth of the
     * stack's defense — but neither of them lets the model start first on a
     * plain message again: a chat with an unanswered binding stops HERE.
     *
     * Answered values are stored on the binding the next run reads, so the
     * gate asks once per plugin per chat and never again while the answers
     * still validate — a reinstall that grew the schema re-arms it, which is
     * exactly when a chat SHOULD be asked again. [PluginForms.incomplete] is
     * the one question, the same one every other gate asks.
     *
     * Returns true when the chat may run (nothing to ask, or every form
     * submitted), false when a form was cancelled or timed out — the caller's
     * message is not the chat's to work, and the notice the gate fires says
     * so in the plugin's own name.
     */
    suspend fun awaitChatInputs(sessionId: Long): Boolean {
        val bindings = runCatching { repo.chatPluginsOnce(sessionId) }.getOrDefault(emptyList())
            .filter { it.enabled }
        if (bindings.isEmpty()) return true
        val rows = runCatching { repo.installedOnce() }.getOrDefault(emptyList())
            .associateBy { it.id }
        bindings.mapNotNull { binding -> rows[binding.pluginId]?.let { binding to it } }
            .filter { (_, plugin) -> PluginForms.schemaOf(plugin).fields.isNotEmpty() }
            // Name order, so the asking order a person experiences with two
            // plugins needing input is stable across restarts.
            .sortedBy { (_, plugin) -> plugin.name.lowercase() }
            .forEach { (binding, plugin) ->
                val schema = PluginForms.schemaOf(plugin)
                val stored = PluginForms.storedValues(binding.inputsValuesJson)
                if (!PluginForms.incomplete(schema, stored)) return@forEach
                val outcome = askForm(
                    PluginFormRequest(
                        pluginId = plugin.id,
                        pluginName = plugin.name,
                        context = PluginFormContext.SEND,
                        fields = schema.fields,
                        existingValues = stored,
                        networkHosts = decodePermissions(plugin.networkAllowJson)
                    )
                )
                val values = (outcome as? PluginFormOutcome.Submitted)?.values
                if (values == null) {
                    onNotice(
                        "${plugin.name} needs its input before this chat can run — the message was not sent.",
                        true
                    )
                    return false
                }
                repo.setInputsValues(sessionId, plugin.id, values.toString())
            }
        return true
    }

    // -------------------------------------------------- the staged activation

    /**
     * Runs the modules this chat staged, with the message that unstaged them.
     *
     * Called by the turn between the user row and the agent's own model call,
     * so the transcript's order is the one every ZAgents module was written
     * against: user → module message → assistant reply. For every
     * zagents-format binding still carrying the staged flag — the fresh-chat
     * enable set it, and nothing has run the entry since — the module runs
     * ONCE with [message] as its prompt, its composed message body joins the
     * conversation as the module's card (beside any cards it pushed), and the
     * flag clears whether it worked or not, because "once per activation" is
     * the promise and a module that failed must not silently retry on the
     * next message.
     *
     * A module whose required inputs are still unanswered asks for them HERE
     * — the staged path's activation gate, raised right before the run — and
     * a form the person does not complete clears the flag too: the module
     * goes dormant rather than asking again on every message, and the re-run
     * paths (a toggle off and on, the sheet's edit-inputs) are how it comes
     * back.
     */
    suspend fun runStagedActivations(sessionId: Long, message: String) {
        val staged = runCatching { repo.zagentsModulesOnce().filter { it.promptStaged } }
            .getOrDefault(emptyList())
        if (staged.isEmpty()) return
        val bindings = runCatching { repo.chatPluginsOnce(sessionId) }.getOrDefault(emptyList())
        staged.forEach { module ->
            // Not enabled in THIS chat means the flag belongs to another
            // chat's staging — leave it for that chat's own first message.
            val binding = bindings.firstOrNull { it.pluginId == module.id && it.enabled }
                ?: return@forEach
            var fresh = binding
            val schema = PluginForms.schemaOf(module)
            val stored = PluginForms.storedValues(binding.inputsValuesJson)
            if (PluginForms.incomplete(schema, stored)) {
                val outcome = askForm(
                    PluginFormRequest(
                        pluginId = module.id,
                        pluginName = module.name,
                        context = PluginFormContext.ACTIVATION,
                        fields = schema.fields,
                        existingValues = stored,
                        networkHosts = decodePermissions(module.networkAllowJson)
                    )
                )
                val values = (outcome as? PluginFormOutcome.Submitted)?.values
                if (values == null) {
                    repo.setPromptStaged(module.id, false)
                    onNotice("${module.name} was not run — its input form was not completed.", false)
                    return@forEach
                }
                repo.setInputsValues(sessionId, module.id, values.toString())
                runCatching { repo.chatPluginsOnce(sessionId) }.getOrDefault(emptyList())
                    .firstOrNull { it.pluginId == module.id }
                    ?.let { fresh = it }
            }
            val result = runtime.run(
                sessionId = sessionId,
                plugin = module,
                binding = fresh,
                event = PluginCallRequest.EVENT_ACTIVATE,
                history = history(sessionId),
                generate = generate(sessionId),
                prompt = message,
                form = formHost
            )
            repo.setPromptStaged(module.id, false)
            if (result.ok) {
                appendCards(sessionId, result.cards, module.name)
                // The module's message body IS its message — the emits and
                // console lines the host composed — rendered as the card the
                // conversation keeps (the row's `result` value never renders,
                // the upstream divergence).
                if (result.output.isNotBlank()) {
                    runCatching {
                        onCard(
                            sessionId,
                            PluginCard(text = result.output.take(PLUGIN_MESSAGE_CHARS)),
                            module.name
                        )
                    }
                } else if (result.cards.isEmpty()) {
                    onNotice("${module.name} ran on this chat's first message and showed nothing.", false)
                }
            } else {
                onNotice(
                    "${module.name} is on, but its start-up failed: " + firstLine(result.output),
                    true
                )
            }
        }
    }

    private companion object {
        /** The card text cap — the same number [PluginCard.parse] clamps to. */
        const val PLUGIN_MESSAGE_CHARS = 4000
    }
}
