package com.roombrowser.data.repo

import com.roombrowser.data.db.AgentDao
import com.roombrowser.data.db.AgentMessageEntity
import com.roombrowser.data.db.AgentProviderEntity
import com.roombrowser.data.db.AgentSessionEntity
import com.roombrowser.data.db.AppDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Agent persistence: providers (API keys stay encrypted at rest — see
 * com.roombrowser.agent.KeyStoreCrypto), chat sessions and messages.
 *
 * Sessions are profile-scoped AND, since v10, tab-scoped: a tab shows the
 * chat bound to it, so switching tabs switches conversations. Providers are
 * app-global (they are credentials/infrastructure, not browsing data).
 */
class AgentRepository(private val db: AppDatabase) {

    private val dao: AgentDao get() = db.agentDao()

    // ---------- Providers ----------

    val providers: Flow<List<AgentProviderEntity>> = dao.observeProviders()

    suspend fun providers(): List<AgentProviderEntity> = dao.providers()

    suspend fun provider(id: Long): AgentProviderEntity? = dao.provider(id)

    suspend fun saveProvider(entity: AgentProviderEntity): Long = dao.upsertProvider(entity)

    suspend fun deleteProvider(id: Long) = dao.deleteProvider(id)

    // ---------- Sessions ----------

    fun observeSessions(profileId: String): Flow<List<AgentSessionEntity>> =
        dao.observeSessions(profileId)

    suspend fun sessions(profileId: String): List<AgentSessionEntity> = dao.sessions(profileId)

    suspend fun session(id: Long): AgentSessionEntity? = dao.session(id)

    /**
     * The conversation of one tab, or null when that tab has none yet — the
     * normal state of a freshly opened tab, and what makes it start a new
     * chat instead of continuing someone else's.
     *
     * A blank [tabId] deliberately answers null and not the unbound rows:
     * "" is the id of EVERY chat that is not bound to a tab, and a caller
     * holding no tab must not be handed one of those as if it owned it.
     */
    suspend fun sessionForTab(profileId: String, tabId: String): AgentSessionEntity? =
        if (tabId.isBlank()) null else dao.sessionForTab(profileId, tabId)

    /**
     * Binds [id] to [tabId], or detaches it with "" — after which no tab
     * resolves to it and it lives on only in the history list. Detaching is
     * how "start a new chat here" gives the tab up without deleting anything.
     */
    suspend fun bindSessionToTab(id: Long, tabId: String) = dao.setTab(id, tabId)

    /** Stores the step list this chat last wrote, or "" to clear it. */
    suspend fun setSessionPlan(id: Long, planJson: String) = dao.setPlan(id, planJson)

    suspend fun createSession(
        profileId: String,
        title: String,
        providerId: Long,
        model: String,
        tabId: String = ""
    ): Long {
        val now = System.currentTimeMillis()
        return dao.insertSession(
            AgentSessionEntity(
                profileId = profileId,
                tabId = tabId,
                title = title.ifBlank { "Agent task" },
                providerId = providerId,
                model = model,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun touchSession(id: Long) = dao.touch(id, System.currentTimeMillis())

    suspend fun deleteSession(id: Long) = dao.deleteMessagesFor(id).let { dao.deleteSession(id) }

    suspend fun deleteSessionsForProfile(profileId: String): Int {
        val ids = dao.sessions(profileId).map { it.id }
        ids.forEach { dao.deleteMessagesFor(it) }
        dao.deleteSessionsFor(profileId)
        return ids.size
    }

    /** Deletes EVERY session + message across all profiles (providers kept). */
    suspend fun deleteAllSessions() {
        dao.deleteAllMessages()
        dao.deleteAllSessions()
    }

    // ---------- Messages ----------

    suspend fun messages(sessionId: Long): List<AgentMessageEntity> = dao.messages(sessionId)

    /**
     * One page of one chat's transcript, plus whether older rows exist below
     * it. [rows] is oldest-first — the order a transcript renders and the
     * model reads — and [hasOlder] is exact rather than guessed from a
     * full-page read: the page below fetches [limit] + 1 rows and drops the
     * extra, so a chat of exactly [limit] rows answers `hasOlder = false`
     * instead of offering a "load older" that would come back empty.
     *
     * [beforeId] is the oldest row id the caller already holds
     * ([Long.MAX_VALUE] for the chat's first window). The cap is the whole
     * point: a page costs one bounded query no matter how long the chat has
     * grown, where [messages] re-reads and re-materializes every row of it.
     */
    suspend fun messagePage(sessionId: Long, beforeId: Long, limit: Int): AgentMessagePage {
        val fetched = dao.messagesBefore(sessionId, beforeId, limit + 1)
        val hasOlder = fetched.size > limit
        val rows = (if (hasOlder) fetched.dropLast(1) else fetched).asReversed()
        return AgentMessagePage(rows, hasOlder)
    }

    /**
     * Every row of one chat, oldest-first — [messages] with the same bounded
     * queries. The export path is the one consumer that honestly needs full
     * fidelity at any length, and a chat long enough to matter here would be
     * a single multi-megabyte query there; paging it trades one huge
     * materialization for a few bounded ones without losing a row.
     */
    suspend fun allMessages(sessionId: Long, pageSize: Int = 500): List<AgentMessageEntity> {
        val pages = mutableListOf<List<AgentMessageEntity>>()
        var beforeId = Long.MAX_VALUE
        while (true) {
            val page = messagePage(sessionId, beforeId, pageSize)
            if (page.rows.isEmpty()) break
            pages += page.rows
            if (!page.hasOlder) break
            beforeId = page.rows.first().id
        }
        // Pages arrive newest-window-first, each already oldest-first; the
        // reversal below is the only step that turns them into one ascending
        // transcript.
        return pages.asReversed().flatten()
    }

    /**
     * Staged activation's fresh-chat question, at any chat length — see
     * AgentDao.hasUserMessage for why this is not a window read.
     */
    suspend fun hasUserMessage(sessionId: Long): Boolean = dao.hasUserMessage(sessionId)

    suspend fun addMessage(
        sessionId: Long,
        role: String,
        content: String,
        toolName: String? = null,
        toolArgs: String? = null,
        toolResult: String? = null
    ): Long = dao.insertMessage(
        AgentMessageEntity(
            sessionId = sessionId,
            role = role,
            content = content,
            toolName = toolName,
            toolArgs = toolArgs,
            toolResult = toolResult,
            createdAt = System.currentTimeMillis()
        )
    )

    suspend fun messageCount(sessionId: Long): Int = dao.messageCount(sessionId)
}

/**
 * What [AgentRepository.messagePage] answers: one oldest-first window of a
 * chat's rows, and whether anything older exists below it — the exact flag a
 * "load older" affordance needs, bought with the same query rather than a
 * second one.
 */
class AgentMessagePage(val rows: List<AgentMessageEntity>, val hasOlder: Boolean)
