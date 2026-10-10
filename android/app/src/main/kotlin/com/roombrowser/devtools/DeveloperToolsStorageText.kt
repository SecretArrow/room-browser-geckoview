package com.roombrowser.devtools

/**
 * The text every Application-panel copy control writes.
 *
 * Pure functions, for the same reason the console and network ones are: what a
 * copy puts on the clipboard is what a bug report carries, so it has to be
 * testable without an emulator. The rules the panels and these texts share:
 *
 *  - A SECTION THE PAGE DID NOT ANSWER IS NOT AN EMPTY SECTION. `null` renders
 *    as a sentence saying so; `empty` renders as "none". A developer reading a
 *    pasted report has to be able to tell "this site stores nothing" from "this
 *    build cannot see what it stores", and an empty list reads as the first.
 *  - Values are marked absent in brackets -- `(none declared)`, `(not
 *    reported)` -- so a paste cannot be mistaken for the page having said the
 *    word.
 *  - Nothing here invents a number. A missing duration, count or size is the
 *    word for missing.
 */
internal object DeveloperToolsStorageText {

    /** The whole panel, as one paste: every section, each with the state it was found in. */
    fun applicationReport(probe: ApplicationProbe?, frames: FrameNode?, engineName: String): String {
        val header = if (probe == null) {
            "Application -- this build could not read the page's storage."
        } else if (!probe.done) {
            "Application -- the page was still working when reading stopped, so this is partial."
        } else {
            "Application -- engine: $engineName"
        }
        return listOf(
            header,
            "",
            estimateText(probe?.estimate),
            "",
            manifestText(probe?.hasManifestLink, probe?.manifest),
            "",
            serviceWorkersText(probe?.serviceWorkers),
            "",
            storageAreaText("localStorage", probe?.localStorage),
            "",
            storageAreaText("sessionStorage", probe?.sessionStorage),
            "",
            indexedDbText(probe?.databases),
            "",
            cacheText(probe?.caches),
            "",
            backgroundText(probe?.background, probe?.bfcache, probe?.reports),
            "",
            framesText(frames)
        ).joinToString("\n").trimEnd()
    }

    /** One key and the length of its value. The value itself is only read when a row is revealed. */
    fun storageKeyLine(area: String, entry: StorageKeyEntry): String =
        "$area  ${entry.key}  ${keyLength(entry)}"

    /** What one revealed row copies: the key, then the value it was hiding. */
    fun storageValueText(area: String, key: String, value: String?): String =
        if (value == null) {
            "$area  $key\n  (the value could not be read)"
        } else {
            "$area  $key\n$value"
        }

    fun storageAreaText(area: String, dump: StorageAreaDump?): String = when {
        dump == null -> "$area -- this build could not read it"
        dump.blocked == true -> "$area -- the page blocked access to it"
        else -> {
            val total = dump.total
            val head = when {
                total == null -> "$area -- (count not reported)"
                total == 0 -> "$area -- empty"
                else -> "$area -- $total ${if (total == 1) "key" else "keys"}"
            }
            val keys = dump.keys.orEmpty()
            if (keys.isEmpty()) {
                head
            } else {
                val listed = keys.joinToString("\n") { "  ${it.key}  ${keyLength(it)}" }
                val lost = if (dump.truncated == true) "\n  (the listing stops at ${keys.size} of $total)" else ""
                "$head\n$listed$lost"
            }
        }
    }

    fun manifestText(hasLink: Boolean?, manifest: ManifestReport?): String = when {
        manifest == null && hasLink == true -> "Manifest -- declared, but the page could not read it"
        manifest == null -> "Manifest -- none declared"
        manifest.status != null && manifest.status != 200 ->
            "Manifest -- ${manifest.href} answered HTTP ${manifest.status}"
        else -> listOf(
            "Manifest -- ${manifest.href}",
            "  name: ${orAbsent(manifest.name)}",
            "  short name: ${orAbsent(manifest.shortName)}",
            "  start url: ${orAbsent(manifest.startUrl)}",
            "  display: ${orAbsent(manifest.display)}",
            "  theme colour: ${orAbsent(manifest.themeColor)}",
            "  background colour: ${orAbsent(manifest.backgroundColor)}",
            "  icons: ${manifest.iconCount?.toString() ?: "(not reported)"}"
        ).joinToString("\n")
    }

    fun serviceWorkersText(report: ServiceWorkerReport?): String {
        if (report == null) return "Service workers -- this build cannot see them"
        val registrations = report.registrations
        if (registrations == null) return "Service workers -- (not reported)"
        if (registrations.isEmpty()) return "Service workers -- none registered"
        val lines = mutableListOf<String>()
        val controller = report.controller
        lines += if (controller == null) {
            "Service workers -- ${registrations.size} registered, and no page is controlled"
        } else {
            "Service workers -- ${registrations.size} registered, this page controlled by ${controller.scriptURL}"
        }
        registrations.forEach { registration ->
            lines += "  ${registration.scope}"
            val states = listOfNotNull(
                registration.active?.let { "active=${it.state} ${it.scriptURL}" },
                registration.waiting?.let { "waiting=${it.state} ${it.scriptURL}" },
                registration.installing?.let { "installing=${it.state} ${it.scriptURL}" }
            )
            if (states.isEmpty()) lines += "    (no worker in any state)"
            states.forEach { lines += "    $it" }
            if (registration.hasPush == true) lines += "    has a push manager"
        }
        return lines.joinToString("\n")
    }

    fun indexedDbText(databases: List<IndexedDbReport>?): String {
        if (databases == null) return "IndexedDB -- this build cannot see it"
        if (databases.isEmpty()) return "IndexedDB -- no databases"
        val lines = mutableListOf("IndexedDB -- ${databases.size} ${if (databases.size == 1) "database" else "databases"}")
        databases.forEach { database ->
            val version = database.version?.let { "version $it" } ?: "(version not reported)"
            lines += "  ${database.name} -- $version"
            val error = database.error
            if (error != null) {
                lines += "    (not enumerated: $error)"
            } else {
                val stores = database.stores.orEmpty()
                if (stores.isEmpty()) lines += "    (no object stores)"
                stores.forEach { store ->
                    val count = store.count?.let { "$it records" } ?: "(count not reported)"
                    lines += "    ${store.name} -- $count"
                }
            }
        }
        return lines.joinToString("\n")
    }

    fun cacheText(caches: List<CacheReport>?): String {
        if (caches == null) return "Cache storage -- this build cannot see it"
        if (caches.isEmpty()) return "Cache storage -- no caches"
        val lines = mutableListOf("Cache storage -- ${caches.size} ${if (caches.size == 1) "cache" else "caches"}")
        caches.forEach { cache ->
            val entries = cache.entries?.let { "$it ${if (it == 1) "entry" else "entries"}" } ?: "(size not reported)"
            lines += "  ${cache.name} -- $entries"
            val urls = cache.urls.orEmpty()
            val lost = (cache.entries ?: 0) - urls.size
            urls.forEach { lines += "    $it" }
            if (lost > 0) lines += "    (the listing stops ${urls.size} in; $lost more not shown)"
        }
        return lines.joinToString("\n")
    }

    fun estimateText(estimate: StorageEstimateReport?): String {
        if (estimate == null) return "Usage -- this build cannot read the storage estimate"
        val usage = estimate.usage?.let { formatBytes(it) } ?: "(usage not reported)"
        val quota = estimate.quota?.let { formatBytes(it) } ?: "(quota not reported)"
        val persisted = estimate.persisted?.let { if (it) "persisted" else "not persisted" } ?: "(persistence not reported)"
        return "Usage -- $usage of $quota, $persisted"
    }

    fun backgroundText(
        background: BackgroundServicesReport?,
        bfcache: BfcacheReport?,
        reports: List<ReportingEntry>?
    ): String {
        val lines = mutableListOf<String>()
        if (background == null) {
            lines += "Background services -- this build could not read them"
        } else {
            lines += "Background services"
            lines += "  push: ${flag(background.hasPushManager, "supported", "not supported")}"
            val subscription = background.subscription
            lines += when {
                subscription == null -> "  push subscription: (none)"
                else -> "  push subscription: ${orAbsent(subscription.endpoint)}"
            }
            lines += "  background sync: ${flag(background.sync, "supported", "not supported")}"
            lines += "  periodic background sync: ${flag(background.periodicSync, "supported", "not supported")}"
            lines += "  background fetch: ${flag(background.backgroundFetch, "supported", "not supported")}"
        }
        lines += when (bfcache?.supported) {
            true -> "  back/forward cache reasons: ${bfcache.notRestoredReasons?.toString() ?: "(none reported)"}"
            false -> "  back/forward cache reasons: (this engine does not report them)"
            null -> "  back/forward cache reasons: (not reported)"
        }
        lines += when {
            reports == null -> "Reporting API -- this build cannot see it"
            reports.isEmpty() -> "Reporting API -- nothing reported"
            else -> {
                val head = "Reporting API -- ${reports.size} ${if (reports.size == 1) "report" else "reports"}"
                (listOf(head) + reports.map { "  ${orAbsent(it.type)} ${orAbsent(it.message)} ${orAbsent(it.url)}" }).joinToString("\n")
            }
        }
        return lines.joinToString("\n")
    }

    fun framesText(root: FrameNode?): String {
        if (root == null) return "Frames -- the page did not answer"
        val lines = mutableListOf<String>()
        var readable = 0
        var opaque = 0
        fun walk(node: FrameNode, depth: Int) {
            if (node.readable == true) readable++ else opaque++
            val indent = "  ".repeat(depth + 1)
            val label = if (node.readable == true) {
                orAbsent(node.url)
            } else {
                "(cross-origin -- the same-origin policy does not allow reading it)"
            }
            val name = node.name?.takeIf { it.isNotEmpty() }?.let { " name=\"$it\"" }.orEmpty()
            lines += "$indent${node.path ?: "?"}$name  $label"
            node.children.orEmpty().forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        val summary = if (opaque == 0) {
            "Frames -- $readable readable"
        } else {
            "Frames -- $readable readable, $opaque cross-origin and not inspectable"
        }
        return (listOf(summary) + lines).joinToString("\n")
    }

    private fun orAbsent(value: String?): String =
        if (value.isNullOrBlank()) "(not reported)" else value

    private fun keyLength(entry: StorageKeyEntry): String =
        entry.length?.let { "$it chars" } ?: "(length not reported)"

    private fun flag(value: Boolean?, yes: String, no: String): String = when (value) {
        true -> yes
        false -> no
        null -> "(not reported)"
    }
}
