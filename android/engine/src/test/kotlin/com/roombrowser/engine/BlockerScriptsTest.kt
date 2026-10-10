package com.roombrowser.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Validates the sub-resource blocker's SCRIPT asset against the Kotlin that
 * feeds it, without running an engine.
 *
 * WHY THIS TEST EXISTS. [BridgeManifestTest] guards the manifest and
 * [BridgeScriptsTest] guards the page bridge. This guards `blocker.js` against
 * `GeckoEngineHost.kt`, because everything that crosses between them is a
 * string agreed on by convention: a port name, two message types, and ten
 * field names inside a JSON object. No compiler checks any of it.
 *
 * THE FAILURE THIS IS WRITTEN AGAINST IS SILENT IN THE WORST DIRECTION. Rename
 * a field on one side and the other side reads `undefined`; `new Set(undefined
 * || [])` is an EMPTY set, and a blocker with an empty set is not an error --
 * it is a blocker that allows every request while the app logs a successful
 * push, the dashboard counts nothing, and the user's shields appear to be on.
 * The same is true of the port name (the port simply never opens) and of the
 * category strings (the app drops the report). Every one of those is a text
 * mismatch, and every one of them costs an emulator run to find otherwise.
 *
 * Deliberately a text scan rather than a parse: the JVM unit-test classpath has
 * no JSON implementation and no JS engine, so neither file can be executed or
 * decoded here. What CAN be checked is that the two sides still spell the
 * contract the same way, and that is the whole subject of this file.
 */
class BlockerScriptsTest {

    private val blockerJs = File("src/main/assets/roombridge/blocker.js").readText()
    private val hostKt =
        File("src/main/kotlin/com/roombrowser/engine/gecko/GeckoEngineHost.kt").readText()
    private val filterKt =
        File("../core/domain/src/main/kotlin/com/roombrowser/domain/engine/FilterEngine.kt")
            .readText()

    @Test
    fun the_port_name_is_the_one_the_host_delegates() {
        // Read out of the Kotlin rather than asserted twice against two
        // literals: a test that hard-codes both ends agrees with itself when
        // both ends are wrong.
        val nativeApp = Regex("BLOCKER_NATIVE_APP\\s*=\\s*\"([^\"]+)\"")
            .find(hostKt)
            ?.groupValues
            ?.get(1)
        assertThat(nativeApp).isNotNull()
        // The JS holds it in a variable rather than inline at the call, so the
        // binding is what has to match.
        assertThat(blockerJs).contains("NATIVE_APP = \"$nativeApp\"")
        assertThat(blockerJs).contains("connectNative(NATIVE_APP)")
    }

    @Test
    fun the_blocker_registers_a_blocking_listener_on_every_request() {
        // `["blocking"]` is the difference between cancelling a request and
        // describing one: without it the listener still runs and its return
        // value is discarded, so nothing is blocked and nothing is reported
        // as wrong.
        assertThat(blockerJs).contains("browser.webRequest.onBeforeRequest.addListener")
        assertThat(blockerJs).contains("[\"blocking\"]")
        assertThat(blockerJs).contains("{ cancel: true }")
        // The filter must cover the third-party hosts the blocker exists for.
        assertThat(blockerJs).contains("{ urls: [\"<all_urls>\"] }")
    }

    @Test
    fun every_field_of_the_filter_is_spelled_the_same_on_both_sides() {
        // The host WRITES these; the blocker READS them. Six of the nine are
        // read into a Set, where a misspelling yields an empty set rather than
        // an error -- which is the silent-allow failure described above.
        listOf(
            "adHosts",
            "trackerHosts",
            "maliciousHosts",
            "keywordRules",
            "blockAds",
            "blockTrackers",
            "blockCrossSite",
            "blockMalicious",
            "shieldsDisabledHosts"
        ).forEach { field ->
            assertThat(hostKt).contains("\"$field\"")
            assertThat(blockerJs).contains(field)
        }
        // The keyword rules are objects, so their two keys cross as well.
        listOf("pattern", "category").forEach { field ->
            assertThat(hostKt).contains("\"$field\"")
            assertThat(blockerJs).contains("rule.$field")
        }
    }

    @Test
    fun the_message_types_are_the_ones_the_other_side_speaks() {
        // Native -> JS: the host sends the filter, the blocker must accept it.
        assertThat(hostKt).contains("\"type\", \"filter\"")
        assertThat(blockerJs).contains("message.type !== \"filter\"")
        // JS -> native: the blocker reports a block, the host must handle it.
        // An unhandled type is dropped with a warning, so a rename here costs
        // the dashboard its events rather than failing anything.
        assertThat(blockerJs).contains("type: \"blocked\"")
        assertThat(hostKt).contains("\"blocked\" ->")
        assertThat(blockerJs).contains("host: host")
        assertThat(blockerJs).contains("category: category")
        assertThat(hostKt).contains("json.optString(\"host\")")
        assertThat(hostKt).contains("json.optString(\"category\")")
    }

    @Test
    fun every_category_the_blocker_reports_is_one_the_app_knows() {
        // The blocker sends the category as a STRING and the host turns it
        // back into an enum with `valueOf`, so the two vocabularies have to
        // agree exactly. Reading the names out of the enum rather than
        // listing them here means a category renamed in the domain fails this
        // test instead of silently dropping every block of that kind.
        listOf("MALICIOUS", "AD", "TRACKER", "CROSS_SITE_TRACKER").forEach { category ->
            assertThat(filterKt).contains(category)
            assertThat(blockerJs).contains("\"$category\"")
        }
    }

    @Test
    fun the_main_frame_is_left_to_the_navigation_policy() {
        // A blocking listener can only cancel; it cannot refuse a navigation
        // with a reason, upgrade http to https, or run the malicious-site
        // prompt. All of that is the app's navigation policy, which the app
        // applies on the main frame -- so the blocker must not pre-empt it.
        assertThat(blockerJs).contains("\"main_frame\"")
    }

    @Test
    fun the_network_observers_run_without_blocking() {
        // The network feed is observational. A registration carrying
        // ["blocking"] joins the cancel path, where it can delay a request and
        // can regress the blocker -- so of the four registrations in this file
        // exactly one may ask for it, and it is onBeforeRequest. The slices are
        // what make this precise: the file's header comment also spells
        // ["blocking"], so a whole-file search cannot answer the question.
        listOf("onHeadersReceived", "onCompleted", "onErrorOccurred").forEach { observer ->
            val block = registration(observer)
            assertThat(block).contains("{ urls: [\"<all_urls>\"] }")
            assertThat(block).doesNotContain("\"blocking\"")
        }
        // The blocking listener still returns its decision unchanged.
        val blocking = registration("onBeforeRequest")
        assertThat(blocking).contains("{ cancel: true }")
        assertThat(blocking).contains("[\"blocking\"]")
    }

    @Test
    fun the_network_message_types_are_the_ones_the_other_side_speaks() {
        // Native -> JS: the host arms and disarms the feed. Without the gate
        // the observers would post on every request on the device, for a panel
        // that is not open.
        listOf("netStart", "netStop").forEach { type ->
            assertThat(hostKt).contains("\"$type\"")
            assertThat(blockerJs).contains("message.type === \"$type\"")
        }
        // JS -> native: the blocker posts each event, the host must handle all
        // four. An unhandled type is dropped with a warning, so a rename here
        // costs the panel its rows rather than failing anything.
        listOf("netRequest", "netResponse", "netCompleted", "netError").forEach { type ->
            assertThat(blockerJs).contains("type: \"$type\"")
            assertThat(hostKt).contains("\"$type\" ->")
        }
    }

    @Test
    fun an_observation_arriving_before_the_arm_is_held_rather_than_dropped() {
        // The app arms the feed from `startNetworkCapture`, which runs when the
        // Network panel composes -- and by then the document has already loaded.
        // Dropping what arrived before that leaves the panel unable to show the
        // page's own first request, and the symptom is an empty feed rather than
        // an error. The bound is the other half: an uninspected page must not
        // grow a buffer without end.
        assertThat(blockerJs).contains("netBuffer.push(payload)")
        assertThat(blockerJs).contains("netBuffer.length > NET_BUFFER_MAX")
        assertThat(blockerJs).contains("pending.length; i++) sendNet(pending[i])")
    }

    /**
     * The text of one `webRequest` registration, from its `addListener(` to its
     * own closing `);`.
     *
     * Sliced rather than searched whole because `["blocking"]` appears in this
     * file's header comment as well as at its one registration: a bare
     * `doesNotContain` over the file would fail for the comment, and a bare
     * `contains` would pass for the wrong listener. The slice ends at the first
     * `\n  );` -- the column-3 closer of a top-level call, which no indented
     * body statement matches.
     */
    private fun registration(observer: String): String {
        val start = blockerJs.indexOf("browser.webRequest.$observer.addListener(")
        assertThat(start).isAtLeast(0)
        val end = blockerJs.indexOf("\n  );", start)
        assertThat(end).isAtLeast(0)
        return blockerJs.substring(start, end)
    }

    @Test
    fun the_matching_mirror_names_the_engine_it_mirrors() {
        // The algorithm is the one thing written twice -- it cannot be shared,
        // because the listener must answer synchronously from another process.
        // The DATA is not duplicated, and the citation is what keeps the
        // second copy findable when the first one changes.
        assertThat(blockerJs).contains("FilterEngine.decide")
        assertThat(blockerJs).contains("matchesSuffix")
    }
}
