package com.roombrowser.domain.agent

/**
 * The persistence the site-memory tool needs, decoupled from Room so the tool
 * is the same code in both executors and can be held to a fake in core tests.
 */
interface SiteMemoryStore {
    suspend fun entries(origin: String): List<SiteMemory.Entry>
    suspend fun count(origin: String): Int
    suspend fun has(origin: String, key: String): Boolean
    suspend fun remember(origin: String, key: String, value: String)

    /** True when an entry was removed; false when there was none to remove. */
    suspend fun forget(origin: String, key: String): Boolean
}

/**
 * The `site_memory` tool body, shared by the visible and headless executors.
 *
 * [origin] is the URL the ENGINE reports for the session — never text the page
 * supplied — and it is passed in already reduced to an origin, so this object
 * cannot be tricked into filing under a bucket the page chose.
 */
object SiteMemoryTool {

    suspend fun execute(
        store: SiteMemoryStore,
        origin: String?,
        op: String?,
        key: String?,
        value: String?,
        limit: Int?
    ): ToolResult {
        val site = origin ?: return ToolResult(
            false,
            "this page has no origin to remember things under — the start page, about:blank " +
                "and data: URLs have none. Open a real site first."
        )
        return when (op?.trim()?.lowercase() ?: "recall") {
            "remember" -> remember(store, site, key, value)
            "forget" -> forget(store, site, key)
            "recall" -> recall(store, site, limit)
            else -> ToolResult(false, "unknown op \"$op\" — use remember, recall or forget")
        }
    }

    private suspend fun remember(
        store: SiteMemoryStore,
        site: String,
        key: String?,
        value: String?
    ): ToolResult {
        val name = when (val check = SiteMemory.keyToStore(key)) {
            is SiteMemory.Check.Refused -> return ToolResult(false, check.reason)
            is SiteMemory.Check.Ok -> check.value
        }
        val stored = when (val check = SiteMemory.valueToStore(value)) {
            is SiteMemory.Check.Refused -> return ToolResult(false, check.reason)
            is SiteMemory.Check.Ok -> check.value
        }
        if (!SiteMemory.fitsUnderOrigin(store.count(site), store.has(site, name))) {
            return ToolResult(
                false,
                "$site already holds ${SiteMemory.MAX_KEYS_PER_ORIGIN} remembered keys — " +
                    "forget one before adding another"
            )
        }
        store.remember(site, name, stored)
        return ToolResult(true, "Remembered \"$name\" for $site.")
    }

    private suspend fun recall(store: SiteMemoryStore, site: String, limit: Int?): ToolResult {
        val entries = store.entries(site)
        return ToolResult(true, SiteMemory.renderRecall(site, entries, SiteMemory.recallLimit(limit)))
    }

    private suspend fun forget(store: SiteMemoryStore, site: String, key: String?): ToolResult {
        val name = when (val check = SiteMemory.keyToStore(key)) {
            is SiteMemory.Check.Refused -> return ToolResult(false, check.reason)
            is SiteMemory.Check.Ok -> check.value
        }
        return if (store.forget(site, name)) {
            ToolResult(true, "Forgot \"$name\" for $site.")
        } else {
            ToolResult(true, "Nothing was remembered under \"$name\" for $site.")
        }
    }
}
