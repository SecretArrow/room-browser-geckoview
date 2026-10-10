package com.roombrowser.devtools

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/**
 * The Application panel's copy texts, asserted without an emulator.
 *
 * WHAT THESE PIN. Every one of them is a place where the panel could state
 * something FALSE and still look right -- an empty list where the page refused
 * to answer, a zero where a size was hidden, a count where a number was never
 * reported. The panel's whole claim is that a reader can tell "this site stores
 * nothing" from "this build cannot see what it stores", and that claim lives in
 * these strings.
 *
 * The e2e suite can prove a real page's storage REACHES the panel; only this
 * file can prove what the panel says when it does not.
 */
class DevToolsApplicationTextTest {

    // ---------- Storage areas ------------------------------------------------

    @Test
    fun a_section_the_page_did_not_answer_is_not_an_empty_section() {
        assertThat(DeveloperToolsStorageText.storageAreaText("localStorage", null))
            .isEqualTo("localStorage -- this build could not read it")
        assertThat(DeveloperToolsStorageText.storageAreaText("localStorage", StorageAreaDump(total = 0, keys = emptyList())))
            .isEqualTo("localStorage -- empty")
        assertThat(DeveloperToolsStorageText.indexedDbText(null)).contains("cannot see it")
        assertThat(DeveloperToolsStorageText.indexedDbText(emptyList())).isEqualTo("IndexedDB -- no databases")
        assertThat(DeveloperToolsStorageText.cacheText(null)).contains("cannot see it")
        assertThat(DeveloperToolsStorageText.cacheText(emptyList())).isEqualTo("Cache storage -- no caches")
    }

    @Test
    fun a_blocked_area_is_reported_as_blocked_rather_than_as_empty() {
        val text = DeveloperToolsStorageText.storageAreaText(
            "sessionStorage",
            StorageAreaDump(blocked = true, keys = emptyList())
        )
        assertThat(text).isEqualTo("sessionStorage -- the page blocked access to it")
        assertThat(text).doesNotContain("empty")
    }

    @Test
    fun a_key_listing_states_its_own_bound_when_it_is_capped() {
        val dump = StorageAreaDump(
            total = 340,
            keys = listOf(StorageKeyEntry("a", 1), StorageKeyEntry("b", 2)),
            truncated = true
        )
        val text = DeveloperToolsStorageText.storageAreaText("localStorage", dump)
        val lines = text.lines()
        assertThat(lines[0]).isEqualTo("localStorage -- 340 keys")
        assertThat(lines[1]).isEqualTo("  a  1 chars")
        assertThat(lines).contains("  (the listing stops at 2 of 340)")
    }

    @Test
    fun a_count_the_page_did_not_report_is_neither_a_zero_nor_a_total() {
        val dump = StorageAreaDump(total = null, keys = listOf(StorageKeyEntry("only")))
        assertThat(DeveloperToolsStorageText.storageAreaText("localStorage", dump).lines()[0])
            .isEqualTo("localStorage -- (count not reported)")
    }

    @Test
    fun a_key_line_carries_the_length_or_says_it_was_not_reported() {
        assertThat(DeveloperToolsStorageText.storageKeyLine("localStorage", StorageKeyEntry("token", 128)))
            .isEqualTo("localStorage  token  128 chars")
        assertThat(DeveloperToolsStorageText.storageKeyLine("localStorage", StorageKeyEntry("token")))
            .isEqualTo("localStorage  token  (length not reported)")
    }

    @Test
    fun a_revealed_row_copies_the_key_and_its_value() {
        assertThat(DeveloperToolsStorageText.storageValueText("localStorage", "token", "abc"))
            .isEqualTo("localStorage  token\nabc")
        // A row that was opened but could not be read must not copy as an empty
        // value, which is what a store that legitimately holds "" looks like.
        assertThat(DeveloperToolsStorageText.storageValueText("localStorage", "token", null))
            .isEqualTo("localStorage  token\n  (the value could not be read)")
    }

    // ---------- Manifest -----------------------------------------------------

    @Test
    fun a_page_with_no_manifest_says_so_and_one_that_could_not_be_read_says_that_instead() {
        assertThat(DeveloperToolsStorageText.manifestText(hasLink = false, manifest = null))
            .isEqualTo("Manifest -- none declared")
        // The link is in the HTML but the fetch never answered: reporting that as
        // "none declared" would be wrong about the page.
        assertThat(DeveloperToolsStorageText.manifestText(hasLink = true, manifest = null))
            .isEqualTo("Manifest -- declared, but the page could not read it")
        assertThat(DeveloperToolsStorageText.manifestText(hasLink = null, manifest = null))
            .isEqualTo("Manifest -- none declared")
    }

    @Test
    fun a_manifest_that_answered_an_error_status_is_reported_by_its_status() {
        val text = DeveloperToolsStorageText.manifestText(
            hasLink = true,
            manifest = ManifestReport(href = "https://example.test/m.webmanifest", status = 404)
        )
        assertThat(text).isEqualTo("Manifest -- https://example.test/m.webmanifest answered HTTP 404")
    }

    @Test
    fun a_readable_manifest_names_every_field_and_brackets_the_missing_ones() {
        val text = DeveloperToolsStorageText.manifestText(
            hasLink = true,
            manifest = ManifestReport(href = "/m.webmanifest", name = "Room", iconCount = 2)
        )
        assertThat(text.lines()[0]).isEqualTo("Manifest -- /m.webmanifest")
        assertThat(text).contains("  name: Room")
        assertThat(text).contains("  short name: (not reported)")
        assertThat(text).contains("  icons: 2")
        assertThat(text).doesNotContain("icons: (not reported)")
    }

    // ---------- Service workers ----------------------------------------------

    @Test
    fun service_workers_list_each_state_of_each_registration() {
        val report = ServiceWorkerReport(
            controller = ServiceWorkerState(scriptURL = "/sw.js", state = "activated"),
            registrations = listOf(
                ServiceWorkerRegistrationReport(
                    scope = "https://example.test/",
                    active = ServiceWorkerState("/sw.js", "activated"),
                    waiting = ServiceWorkerState("/sw-v2.js", "installed"),
                    installing = null,
                    hasPush = true
                )
            )
        )
        val text = DeveloperToolsStorageText.serviceWorkersText(report)
        assertThat(text.lines()[0]).isEqualTo("Service workers -- 1 registered, this page controlled by /sw.js")
        assertThat(text).contains("  https://example.test/")
        assertThat(text).contains("    active=activated /sw.js")
        assertThat(text).contains("    waiting=installed /sw-v2.js")
        assertThat(text).doesNotContain("installing=")
        assertThat(text).contains("    has a push manager")
    }

    @Test
    fun a_registration_with_no_worker_in_any_state_says_that_rather_than_listing_nothing() {
        val report = ServiceWorkerReport(
            registrations = listOf(ServiceWorkerRegistrationReport(scope = "https://example.test/"))
        )
        val text = DeveloperToolsStorageText.serviceWorkersText(report)
        assertThat(text.lines()[0]).isEqualTo("Service workers -- 1 registered, and no page is controlled")
        assertThat(text).contains("    (no worker in any state)")
    }

    @Test
    fun service_workers_that_were_not_reported_are_not_reported_as_none_registered() {
        assertThat(DeveloperToolsStorageText.serviceWorkersText(ServiceWorkerReport(registrations = null)))
            .isEqualTo("Service workers -- (not reported)")
        assertThat(DeveloperToolsStorageText.serviceWorkersText(ServiceWorkerReport(registrations = emptyList())))
            .isEqualTo("Service workers -- none registered")
        assertThat(DeveloperToolsStorageText.serviceWorkersText(null))
            .isEqualTo("Service workers -- this build cannot see them")
    }

    // ---------- IndexedDB and caches -----------------------------------------

    @Test
    fun indexeddb_databases_are_listed_with_each_store_and_its_record_count() {
        val text = DeveloperToolsStorageText.indexedDbText(
            listOf(
                IndexedDbReport(
                    name = "app",
                    version = 3,
                    stores = listOf(IndexedDbStore("notes", 12), IndexedDbStore("blobs"))
                )
            )
        )
        assertThat(text.lines()[0]).isEqualTo("IndexedDB -- 1 database")
        assertThat(text).contains("  app -- version 3")
        assertThat(text).contains("    notes -- 12 records")
        assertThat(text).contains("    blobs -- (count not reported)")
    }

    @Test
    fun an_indexeddb_database_that_could_not_be_enumerated_states_why_instead_of_showing_no_stores() {
        val text = DeveloperToolsStorageText.indexedDbText(
            listOf(IndexedDbReport(name = "locked", version = 1, error = "blocked by another tab"))
        )
        assertThat(text).contains("    (not enumerated: blocked by another tab)")
        assertThat(text).doesNotContain("no object stores")
    }

    @Test
    fun indexeddb_and_cache_counts_are_pluralised_from_the_number_not_from_the_list() {
        assertThat(DeveloperToolsStorageText.indexedDbText(listOf(IndexedDbReport(name = "one"))))
            .startsWith("IndexedDB -- 1 database\n")
        assertThat(
            DeveloperToolsStorageText.indexedDbText(listOf(IndexedDbReport(name = "a"), IndexedDbReport(name = "b")))
        ).startsWith("IndexedDB -- 2 databases\n")
        assertThat(DeveloperToolsStorageText.cacheText(listOf(CacheReport(name = "one", entries = 1))))
            .startsWith("Cache storage -- 1 cache\n")
        assertThat(
            DeveloperToolsStorageText.cacheText(listOf(CacheReport(name = "a"), CacheReport(name = "b")))
        ).startsWith("Cache storage -- 2 caches\n")
    }

    @Test
    fun a_capped_cache_listing_says_how_many_entries_it_did_not_show() {
        val text = DeveloperToolsStorageText.cacheText(
            listOf(
                CacheReport(
                    name = "shell",
                    entries = 40,
                    urls = listOf("https://example.test/a.js", "https://example.test/b.css")
                )
            )
        )
        assertThat(text).contains("  shell -- 40 entries")
        assertThat(text).contains("    https://example.test/a.js")
        assertThat(text).contains("    (the listing stops 2 in; 38 more not shown)")
    }

    @Test
    fun a_cache_whose_size_was_not_reported_never_claims_to_have_shown_everything() {
        val text = DeveloperToolsStorageText.cacheText(
            listOf(CacheReport(name = "shell", entries = null, urls = listOf("https://example.test/a.js")))
        )
        assertThat(text).contains("  shell -- (size not reported)")
        // Nothing was withheld that we know of, so there is no loss line -- but
        // the size line above already says the total is unknown.
        assertThat(text).doesNotContain("more not shown")
    }

    // ---------- Usage --------------------------------------------------------

    @Test
    fun the_storage_estimate_never_invents_a_number() {
        assertThat(DeveloperToolsStorageText.estimateText(null))
            .isEqualTo("Usage -- this build cannot read the storage estimate")
        assertThat(DeveloperToolsStorageText.estimateText(StorageEstimateReport()))
            .isEqualTo("Usage -- (usage not reported) of (quota not reported), (persistence not reported)")
        assertThat(
            DeveloperToolsStorageText.estimateText(
                StorageEstimateReport(usage = 4096.0, quota = 1048576.0, persisted = true)
            )
        ).isEqualTo("Usage -- 4 kB of 1 MB, persisted")
        assertThat(DeveloperToolsStorageText.estimateText(StorageEstimateReport(usage = 0.0, persisted = false)))
            .isEqualTo("Usage -- 0 B of (quota not reported), not persisted")
    }

    // ---------- Background services -----------------------------------------

    @Test
    fun background_services_separate_unsupported_from_unreported() {
        val none = DeveloperToolsStorageText.backgroundText(
            BackgroundServicesReport(),
            BfcacheReport(),
            null
        )
        assertThat(none).contains("  push: (not reported)")
        assertThat(none).contains("  background sync: (not reported)")
        assertThat(none).contains("  back/forward cache reasons: (not reported)")

        val unsupported = DeveloperToolsStorageText.backgroundText(
            BackgroundServicesReport(hasPushManager = false, sync = false, periodicSync = false, backgroundFetch = false),
            BfcacheReport(supported = false),
            null
        )
        assertThat(unsupported).contains("  push: not supported")
        assertThat(unsupported).contains("  background fetch: not supported")
        assertThat(unsupported).contains("  back/forward cache reasons: (this engine does not report them)")
    }

    @Test
    fun a_bfcache_reason_is_rendered_when_the_engine_reports_one() {
        val text = DeveloperToolsStorageText.backgroundText(
            BackgroundServicesReport(),
            BfcacheReport(supported = true, notRestoredReasons = JsonPrimitive("cache-control: no-store")),
            null
        )
        assertThat(text).contains("back/forward cache reasons: \"cache-control: no-store\"")
    }

    @Test
    fun the_reporting_api_distinguishes_nothing_reported_from_cannot_see() {
        assertThat(DeveloperToolsStorageText.backgroundText(null, null, null))
            .contains("Background services -- this build could not read them")
        assertThat(DeveloperToolsStorageText.backgroundText(BackgroundServicesReport(), null, emptyList()))
            .contains("Reporting API -- nothing reported")
        val one = DeveloperToolsStorageText.backgroundText(
            BackgroundServicesReport(),
            null,
            listOf(ReportingEntry(type = "csp-violation", url = "https://example.test/", message = "blocked"))
        )
        assertThat(one).contains("Reporting API -- 1 report")
        assertThat(one).contains("  csp-violation blocked https://example.test/")
    }

    @Test
    fun a_push_subscription_is_named_by_its_endpoint_or_absent() {
        val none = DeveloperToolsStorageText.backgroundText(
            BackgroundServicesReport(hasPushManager = true, subscription = null),
            null,
            null
        )
        assertThat(none).contains("  push subscription: (none)")
        val one = DeveloperToolsStorageText.backgroundText(
            BackgroundServicesReport(hasPushManager = true, subscription = PushSubscriptionReport(endpoint = "https://push.test/x")),
            null,
            null
        )
        assertThat(one).contains("  push subscription: https://push.test/x")
    }

    // ---------- Frames -------------------------------------------------------

    @Test
    fun frames_are_counted_readable_and_cross_origin_separately() {
        val tree = FrameNode(
            path = "0",
            url = "https://example.test/",
            readable = true,
            children = listOf(
                FrameNode(path = "0.0", url = "https://example.test/inner", name = "inner", readable = true),
                FrameNode(path = "0.1", url = null, readable = false)
            )
        )
        val text = DeveloperToolsStorageText.framesText(tree)
        assertThat(text.lines()[0]).isEqualTo("Frames -- 2 readable, 1 cross-origin and not inspectable")
        assertThat(text).contains("0  https://example.test/")
        assertThat(text).contains("  0.0 name=\"inner\"  https://example.test/inner")
        // The cross-origin child never shows a URL it could not read.
        assertThat(text).contains("(cross-origin -- the same-origin policy does not allow reading it)")
    }

    @Test
    fun a_frame_tree_the_page_did_not_answer_is_not_an_empty_tree() {
        assertThat(DeveloperToolsStorageText.framesText(null)).isEqualTo("Frames -- the page did not answer")
    }

    @Test
    fun a_tree_with_nothing_hidden_says_only_that_much() {
        val text = DeveloperToolsStorageText.framesText(FrameNode(path = "0", url = "https://x.test/", readable = true))
        assertThat(text.lines()[0]).isEqualTo("Frames -- 1 readable")
        assertThat(text).doesNotContain("cross-origin")
    }

    // ---------- The whole report --------------------------------------------

    @Test
    fun a_probe_that_never_finished_says_the_report_is_partial() {
        val partial = DeveloperToolsStorageText.applicationReport(ApplicationProbe(done = false), null, "WebView")
        assertThat(partial.lines()[0])
            .isEqualTo("Application -- the page was still working when reading stopped, so this is partial.")
        val failed = DeveloperToolsStorageText.applicationReport(null, null, "WebView")
        assertThat(failed.lines()[0])
            .isEqualTo("Application -- this build could not read the page's storage.")
    }

    @Test
    fun the_whole_report_names_the_engine_and_carries_every_section() {
        val text = DeveloperToolsStorageText.applicationReport(
            ApplicationProbe(
                done = true,
                failures = listOf("indexedDB: blocked"),
                estimate = StorageEstimateReport(usage = 1024.0, quota = 2048.0, persisted = false),
                localStorage = StorageAreaDump(total = 1, keys = listOf(StorageKeyEntry("k", 3))),
                databases = emptyList(),
                caches = emptyList(),
                background = BackgroundServicesReport(hasPushManager = true),
                reports = emptyList()
            ),
            FrameNode(path = "0", url = "https://example.test/", readable = true),
            "WebView 141"
        )
        assertThat(text.lines()[0]).isEqualTo("Application -- engine: WebView 141")
        listOf(
            "Usage --", "Manifest --", "Service workers --", "localStorage --",
            "sessionStorage --", "IndexedDB --", "Cache storage --",
            "Background services", "Reporting API --", "back/forward cache reasons:", "Frames --"
        ).forEach { section ->
            assertThat(text).contains(section)
        }
    }

    @Test
    fun a_partial_report_still_carries_the_sections_that_did_answer() {
        // A probe can finish with one read failed; the report must not throw the
        // rest away and must not present the whole thing as complete either.
        val text = DeveloperToolsStorageText.applicationReport(
            ApplicationProbe(
                done = true,
                failures = listOf("caches: timed out"),
                localStorage = StorageAreaDump(total = 1, keys = listOf(StorageKeyEntry("k", 1)))
            ),
            null,
            "GeckoView"
        )
        assertThat(text.lines()[0]).isEqualTo("Application -- engine: GeckoView")
        assertThat(text).contains("localStorage -- 1 key")
        assertThat(text).contains("Frames -- the page did not answer")
    }
}
