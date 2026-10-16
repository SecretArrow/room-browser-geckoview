package com.roombrowser.data.repo

import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.db.ChatPluginEntity
import com.roombrowser.data.db.PluginEntity
import com.roombrowser.data.plugins.PluginInstallResult
import com.roombrowser.data.plugins.PluginZipInstaller
import com.roombrowser.data.plugins.ZAgentsInstall
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.plugins.PluginAuthor
import com.roombrowser.domain.plugins.PluginFormat
import com.roombrowser.domain.plugins.PluginManifest
import com.roombrowser.domain.plugins.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.InputStream

/**
 * Plugin persistence: the installed-plugin rows, the per-chat bindings, and
 * the two directories they describe — `<filesDir>/plugins/` for code and
 * `<filesDir>/plugin-data/<sessionId>/` for per-chat data.
 *
 * The row and the code directory are written TOGETHER by [install] and removed
 * together by [uninstall], because either alone is a lie: a row with no code
 * is a plugin that fails at run time, and code with no row is invisible to
 * every chat. [sweepOrphanData] reclaims the one thing the database's own
 * cascades cannot — a chat's data directory whose session is gone.
 *
 * Reached through the app graph like [AgentRepository]; constructed in the
 * main and ':browser' processes alike, and Room's multi-instance invalidation
 * keeps the two in step.
 */
class PluginRepository(
    private val db: AppDatabase,
    private val pluginsDir: File,
    private val dataDir: File
) {

    private val pluginDao get() = db.pluginDao()
    private val chatDao get() = db.chatPluginDao()
    private val installer = PluginZipInstaller(pluginsDir)

    // ------------------------------------------------------------ installed

    fun observeInstalled(): Flow<List<PluginEntity>> = pluginDao.observeAll()

    suspend fun installedOnce(): List<PluginEntity> = pluginDao.all()

    /**
     * Validates and extracts [stream], then records the row — or refuses
     * before anything is written. A reinstall of the same id replaces the row
     * and the directory; the chats that had it enabled keep their bindings
     * (the foreign key survives the row's replacement because the id does not
     * change), and grants that no longer cover the new manifest's permissions
     * are re-consented at the chat, not silently widened here.
     *
     * A module.json package lands in the SAME row shape: the mapped manifest
     * fills the columns every plugin row carries, and [ZAgentsInstall] fills
     * the module-only ones (format, the module's own spellings, its timeout,
     * its `inputs` form). The staged-activation flag starts clear — a fresh
     * install has run nothing.
     */
    suspend fun install(stream: InputStream): PluginInstallResult = withContext(Dispatchers.IO) {
        when (val result = installer.install(stream)) {
            is PluginInstallResult.ManifestOk -> {
                val now = System.currentTimeMillis()
                val existing = pluginDao.byId(result.manifest.id)
                pluginDao.upsert(
                    result.manifest.toEntity(
                        dirName = result.dirName,
                        installedAt = existing?.installedAt ?: now,
                        updatedAt = now,
                        zagents = result.zagents
                    )
                )
                result
            }
            is PluginInstallResult.Invalid -> result
        }
    }

    /** Removes the row, the code directory, and every chat's data for it. */
    suspend fun uninstall(id: String): Unit = withContext(Dispatchers.IO) {
        val entity = pluginDao.byId(id) ?: return@withContext
        dataDir.listFiles()?.forEach { sessionDir ->
            runCatching { File(sessionDir, entity.dirName).deleteRecursively() }
        }
        chatDao.deleteForPlugin(id)
        pluginDao.delete(id)
        runCatching { File(pluginsDir, entity.dirName).deleteRecursively() }
    }

    /**
     * The code directory of an installed plugin — `<filesDir>/plugins/<dirName>`
     * — or null when no row for [pluginId] exists: the manager's files-missing
     * check, and the editor's and the export's dir source. Whether the
     * directory is still THERE is the caller's read (`isDirectory`): a row
     * whose dir was lost still answers here, so the manager can say so —
     * "Files missing — reinstall from a ZIP or remove." — instead of this
     * answer quietly inventing a directory that is not on disk.
     */
    suspend fun pluginDir(pluginId: String): File? = withContext(Dispatchers.IO) {
        pluginDao.byId(pluginId)?.let { File(pluginsDir, it.dirName) }
    }

    // ------------------------------------------------------------ per chat

    fun observeChatPlugins(sessionId: Long): Flow<List<ChatPluginEntity>> =
        chatDao.observeForSession(sessionId)

    suspend fun chatPluginsOnce(sessionId: Long): List<ChatPluginEntity> =
        chatDao.forSession(sessionId)

    /**
     * Switches one plugin on or off for one chat. [grantedPermissions] is what
     * the chat consented to — the caller's to have asked; it is stored as
     * given, and a disable that wants to KEEP the grants passes the row's own
     * set back (which [PluginUiState] does, so switching off and back on is
     * not a re-consent). The binding's config overrides and input values ride
     * the same keep-rule: a toggle is not an un-answering of the form.
     */
    suspend fun setEnabled(
        sessionId: Long,
        pluginId: String,
        enabled: Boolean,
        grantedPermissions: List<String>
    ): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = chatDao.forSession(sessionId).firstOrNull { it.pluginId == pluginId }
        chatDao.upsert(
            ChatPluginEntity(
                id = existing?.id ?: 0,
                sessionId = sessionId,
                pluginId = pluginId,
                enabled = enabled,
                grantedPermissionsJson = encodePermissions(grantedPermissions),
                configJson = existing?.configJson ?: "",
                inputsValuesJson = existing?.inputsValuesJson,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )
    }

    /** Replaces this chat's config overrides; a chat with no binding has nothing to configure. */
    suspend fun setConfig(sessionId: Long, pluginId: String, configJson: String): Unit =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val existing = chatDao.forSession(sessionId).firstOrNull { it.pluginId == pluginId }
                ?: return@withContext
            chatDao.upsert(
                existing.copy(configJson = configJson, updatedAt = now)
            )
        }

    // ------------------------------------------------------ inputs and staging

    /**
     * The values this chat last accepted for the plugin's `inputs` form, or
     * null when the binding has none stored — the missing-inputs gate's one
     * question. The string is the normalized object the form validator
     * produced, not the raw editor state.
     */
    suspend fun inputsValues(sessionId: Long, pluginId: String): String? = withContext(Dispatchers.IO) {
        chatDao.forSession(sessionId).firstOrNull { it.pluginId == pluginId }?.inputsValuesJson
    }

    /**
     * Replaces this chat's stored input values — the caller's to have run
     * the form to a valid, complete answer first. A chat with no binding
     * has nothing to store, same rule as [setConfig].
     */
    suspend fun setInputsValues(sessionId: Long, pluginId: String, valuesJson: String): Unit =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val existing = chatDao.forSession(sessionId).firstOrNull { it.pluginId == pluginId }
                ?: return@withContext
            chatDao.upsert(
                existing.copy(inputsValuesJson = valuesJson, updatedAt = now)
            )
        }

    /**
     * Sets or clears a module's staged-activation flag: 1 means a fresh chat
     * enabled it and its entry runs with the chat's FIRST message, not at
     * enable time. The flag lives on the plugin row, so it is a hint about
     * the most recent fresh-chat enable — the chat controller is the one
     * that knows which chat staged it, and the one that clears it once the
     * entry has run.
     */
    suspend fun setPromptStaged(pluginId: String, staged: Boolean): Unit = withContext(Dispatchers.IO) {
        pluginDao.setPromptStaged(pluginId, staged)
    }

    /** The installed ZAgents modules — the rows a module.json produced. */
    suspend fun zagentsModulesOnce(): List<PluginEntity> =
        pluginDao.byFormat(PluginFormat.ZAGENTS)

    /**
     * Reclaims `plugin-data/<sessionId>` directories whose session has no
     * binding row left. The session's own deletion cascades the ROWS away in
     * the database; the directories are files, and this sweep — run at start
     * and after chat deletions — is the only thing that knows they are gone.
     *
     * A directory whose name is not a session id is left alone: deleting a
     * directory this code cannot explain is a worse failure than leaving it.
     */
    suspend fun sweepOrphanData(): Unit = withContext(Dispatchers.IO) {
        dataDir.listFiles()?.forEach { dir ->
            val sessionId = dir.name.toLongOrNull() ?: return@forEach
            val bound = runCatching { chatDao.hasAny(sessionId) }.getOrDefault(true)
            if (!bound) runCatching { dir.deleteRecursively() }
        }
    }
}

/**
 * The manifest an installed row carries, rebuilt from its columns. This is
 * what the agent layer hands the runtime, so what runs is what was validated
 * at install — not a re-read of a file the plugin could have exchanged since.
 */
fun PluginEntity.toManifest(): PluginManifest = PluginManifest(
    id = id,
    name = name,
    version = version,
    description = description,
    author = authorName.takeIf { it.isNotBlank() }?.let { PluginAuthor(name = it, email = "", url = "") },
    license = license,
    api = apiVersion,
    entry = entry,
    permissions = decodeStrings(permissionsJson),
    networkAllow = decodeStrings(networkAllowJson),
    configSchema = PluginManifest.parseConfigSchema(AgentJson.parseToJsonElement(configSchemaJson)),
    commands = PluginManifest.parseCommands(AgentJson.parseToJsonElement(commandsJson)),
    hooks = decodeStrings(hooksJson)
)

/** The row a manifest becomes when it is installed into [dirName]. */
internal fun PluginManifest.toEntity(
    dirName: String,
    installedAt: Long,
    updatedAt: Long,
    zagents: ZAgentsInstall? = null
): PluginEntity =
    PluginEntity(
        id = id,
        name = name,
        version = version,
        description = description,
        authorName = author?.name.orEmpty(),
        license = license,
        entry = entry,
        apiVersion = api,
        permissionsJson = encodePermissions(permissions),
        networkAllowJson = JsonArray(networkAllow.map { JsonPrimitive(it) }).toString(),
        configSchemaJson = JsonArray(configSchema.map { it.toJson() }).toString(),
        commandsJson = JsonArray(commands.map { it.toJson() }).toString(),
        hooksJson = JsonArray(hooks.map { JsonPrimitive(it) }).toString(),
        dirName = dirName,
        installedAt = installedAt,
        updatedAt = updatedAt,
        format = if (zagents != null) PluginFormat.ZAGENTS else PluginFormat.PLUGIN,
        zagentsName = zagents?.name,
        zagentsVersion = zagents?.version,
        zagentsEntry = zagents?.entry,
        timeoutSeconds = zagents?.timeoutSeconds,
        // BOTH formats' form: the module's own spelling when the install was
        // one, else the plugin.json manifest's — the gates read this column
        // through PluginForms.schemaOf, and a native manifest that declared
        // `inputs` was validated against the same schema rules, so dropping
        // it here would install a form nothing would ever raise.
        inputsJson = zagents?.inputsJson ?: manifest.inputs.toJsonOrNull(),
        promptStaged = false
    )

/** A permission list as its stored JSON array; the one spelling both sides use. */
internal fun encodePermissions(permissions: List<String>): String =
    JsonArray(permissions.map { JsonPrimitive(it) }).toString()

internal fun decodePermissions(json: String): List<String> = decodeStrings(json)

private fun decodeStrings(json: String): List<String> =
    ((AgentJson.parseToJsonElement(json) as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
        .orEmpty()
