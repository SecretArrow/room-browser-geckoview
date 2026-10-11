package com.roombrowser.agent

import com.roombrowser.data.db.AgentSiteMemoryDao
import com.roombrowser.data.db.AgentSiteMemoryEntity
import com.roombrowser.di.AppGraph
import com.roombrowser.domain.agent.SiteMemory
import com.roombrowser.domain.agent.SiteMemoryStore
import com.roombrowser.domain.model.ProfileId

/**
 * [SiteMemoryStore] over the app's database.
 *
 * The interface itself lives in `:core:domain` beside [SiteMemory], so
 * [com.roombrowser.domain.agent.SiteMemoryTool] is the SAME code in the visible
 * and headless executors and can be held to a fake store in core tests. Only
 * this Room-backed implementation is Android-side.
 */
class GraphSiteMemoryStore(
    private val graph: AppGraph,
    private val profileId: ProfileId
) : SiteMemoryStore {

    private val dao: AgentSiteMemoryDao get() = graph.database.agentSiteMemoryDao()

    override suspend fun entries(origin: String): List<SiteMemory.Entry> =
        dao.forOrigin(profileId.value, origin).map { SiteMemory.Entry(it.key, it.value) }

    override suspend fun count(origin: String): Int = dao.countForOrigin(profileId.value, origin)

    override suspend fun has(origin: String, key: String): Boolean =
        dao.get(profileId.value, origin, key) != null

    override suspend fun remember(origin: String, key: String, value: String) {
        dao.upsert(
            AgentSiteMemoryEntity(
                profileId = profileId.value,
                origin = origin,
                key = key,
                value = value,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    override suspend fun forget(origin: String, key: String): Boolean {
        if (!has(origin, key)) return false
        dao.deleteOne(profileId.value, origin, key)
        return true
    }
}
