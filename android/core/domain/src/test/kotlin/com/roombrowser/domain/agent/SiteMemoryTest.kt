package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.ProfileSettings
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The rules behind the agent's per-site memory.
 *
 * The negative controls carry the weight: the origin must come from the URL the
 * ENGINE reports and never from page text, a refused write must store nothing,
 * and a profile that has not opted in must be refused. Each of those, if wrong,
 * fails quietly — a note filed under a page-chosen bucket, a blank value stored
 * as if it meant something, or memory working for a user who never turned it on.
 *
 * Every test ends on a VOID-returning Truth call (isTrue / isNull / contains /
 * isEqualTo), never on an `Ordered`-returning one, because a non-void test
 * method makes JUnit4 refuse the whole class. Suspend calls run under
 * `runBlocking<Unit>`, which is itself void.
 */
class SiteMemoryTest {

    /** An in-memory [SiteMemoryStore], so the tool can be driven without Room. */
    private class FakeStore : SiteMemoryStore {
        private val rows = linkedMapOf<String, MutableMap<String, String>>()

        override suspend fun entries(origin: String): List<SiteMemory.Entry> =
            rows[origin].orEmpty().map { SiteMemory.Entry(it.key, it.value) }

        override suspend fun count(origin: String): Int = rows[origin].orEmpty().size

        override suspend fun has(origin: String, key: String): Boolean =
            rows[origin]?.containsKey(key) == true

        override suspend fun remember(origin: String, key: String, value: String) {
            rows.getOrPut(origin) { linkedMapOf() }[key] = value
        }

        override suspend fun forget(origin: String, key: String): Boolean =
            rows[origin]?.remove(key) != null
    }

    // ------------------------------------------------------------- origin

    @Test
    fun origin_comes_from_the_session_url_not_from_url_shaped_page_text() {
        // The page cannot choose its own bucket: the only input is the engine's
        // URL, and a URL-shaped string sitting in its path or query is part of
        // the path, not the origin.
        val engineUrl = "https://real.example.com/redirect?to=https://phish.test/login"
        val origin = SiteMemory.originOf(engineUrl)
        assertThat(origin).isEqualTo("https://real.example.com")
        assertThat(origin).doesNotContain("phish.test")
    }

    @Test
    fun a_page_with_no_usable_origin_cannot_be_filed_under() = runBlocking<Unit> {
        assertThat(SiteMemory.originOf(null)).isNull()
        assertThat(SiteMemory.originOf("")).isNull()
        assertThat(SiteMemory.originOf("about:blank")).isNull()
        assertThat(SiteMemory.originOf("data:text/html,<h1>hi</h1>")).isNull()
        assertThat(SiteMemory.originOf("blob:https://x.test/1")).isNull()
        assertThat(SiteMemory.originOf("not a url")).isNull()

        val store = FakeStore()
        val result = SiteMemoryTool.execute(store, null, "remember", "k", "v", null)
        assertThat(result.ok).isFalse()
        assertThat(result.output).contains("no origin")
        assertThat(store.entries("https://x.test")).isEmpty()
    }

    @Test
    fun a_default_port_is_dropped_so_one_site_is_one_bucket() {
        assertThat(SiteMemory.originOf("https://shop.example.com:443/cart"))
            .isEqualTo("https://shop.example.com")
        assertThat(SiteMemory.originOf("http://shop.example.com:80/"))
            .isEqualTo("http://shop.example.com")
        assertThat(SiteMemory.originOf("https://shop.example.com:8443/"))
            .isEqualTo("https://shop.example.com:8443")
    }

    // ------------------------------------------------------------ redaction

    @Test
    fun a_secret_shaped_value_is_redacted_before_it_is_stored() = runBlocking<Unit> {
        val store = FakeStore()
        val result = SiteMemoryTool.execute(
            store = store,
            origin = "https://shop.example.com",
            op = "remember",
            key = "session",
            value = "Authorization: Bearer abc123token",
            limit = null
        )
        assertThat(result.ok).isTrue()
        val stored = store.entries("https://shop.example.com").single().value
        assertThat(stored).contains(SecretRedaction.MASK)
        assertThat(stored).doesNotContain("abc123token")
    }

    // ---------------------------------------------------------------- bounds

    @Test
    fun recall_is_bounded_and_says_how_much_it_left_out() {
        val entries = (1..30).map { SiteMemory.Entry("key$it", "value$it") }
        val rendered = SiteMemory.renderRecall("https://x.test", entries, SiteMemory.recallLimit(5))
        assertThat(rendered).contains("- key5: value5")
        assertThat(rendered).doesNotContain("key6")
        assertThat(rendered).contains("25 more not shown")
        assertThat(SiteMemory.recallLimit(null)).isEqualTo(SiteMemory.DEFAULT_RECALL_LIMIT)
        assertThat(SiteMemory.recallLimit(9_999)).isEqualTo(SiteMemory.MAX_RECALL_LIMIT)
        assertThat(SiteMemory.recallLimit(0)).isEqualTo(1)
    }

    @Test
    fun the_tools_recall_honours_its_limit() = runBlocking<Unit> {
        val store = FakeStore()
        repeat(30) { i -> store.remember("https://x.test", "k$i", "v$i") }
        val result = SiteMemoryTool.execute(store, "https://x.test", "recall", null, null, 5)
        assertThat(result.ok).isTrue()
        assertThat(result.output).contains("25 more not shown")
    }

    @Test
    fun a_site_at_its_key_cap_refuses_a_new_key_but_still_accepts_an_update() =
        runBlocking<Unit> {
            val store = FakeStore()
            repeat(SiteMemory.MAX_KEYS_PER_ORIGIN) { i -> store.remember("https://x.test", "k$i", "v$i") }

            val fresh = SiteMemoryTool.execute(store, "https://x.test", "remember", "one-more", "v", null)
            assertThat(fresh.ok).isFalse()
            assertThat(fresh.output).contains("forget one")

            val update = SiteMemoryTool.execute(store, "https://x.test", "remember", "k0", "v2", null)
            assertThat(update.ok).isTrue()
            assertThat(store.count("https://x.test")).isEqualTo(SiteMemory.MAX_KEYS_PER_ORIGIN)
        }

    // -------------------------------------------------- refused write stores nothing

    @Test
    fun a_refused_write_stores_nothing() = runBlocking<Unit> {
        val store = FakeStore()

        val blank = SiteMemoryTool.execute(store, "https://x.test", "remember", "k", "   ", null)
        assertThat(blank.ok).isFalse()

        val long = SiteMemoryTool.execute(
            store,
            "https://x.test",
            "remember",
            "k",
            "x".repeat(SiteMemory.MAX_VALUE_CHARS + 1),
            null
        )
        assertThat(long.ok).isFalse()
        assertThat(long.output).contains("too long")

        val noKey = SiteMemoryTool.execute(store, "https://x.test", "remember", null, "v", null)
        assertThat(noKey.ok).isFalse()

        assertThat(store.entries("https://x.test")).isEmpty()
    }

    @Test
    fun forget_reports_honestly_when_there_was_nothing_to_remove() = runBlocking<Unit> {
        val store = FakeStore()
        val result = SiteMemoryTool.execute(store, "https://x.test", "forget", "absent", null, null)
        assertThat(result.ok).isTrue()
        assertThat(result.output).contains("Nothing was remembered")
    }

    // ----------------------------------------------------------------- gate

    @Test
    fun site_memory_is_off_by_default_and_a_profile_must_opt_in() = runBlocking<Unit> {
        val store = FakeStore()
        val defaults = ProfileSettings()
        assertThat(defaults.agentSiteMemoryEnabled).isFalse()

        // The executor asks the gate first and returns before touching the
        // store, so this mirrors its ordering exactly.
        val refusal = SiteMemory.refusalWhenDisabled(defaults.agentSiteMemoryEnabled)
        assertThat(refusal).isNotNull()
        assertThat(refusal).contains("Let the agent remember this site")
        if (refusal == null) {
            SiteMemoryTool.execute(store, "https://x.test", "remember", "k", "v", null)
        }
        assertThat(store.entries("https://x.test")).isEmpty()

        val optedIn = defaults.copy(agentSiteMemoryEnabled = true)
        assertThat(SiteMemory.refusalWhenDisabled(optedIn.agentSiteMemoryEnabled)).isNull()
        val allowed = SiteMemoryTool.execute(store, "https://x.test", "remember", "k", "v", null)
        assertThat(allowed.ok).isTrue()
        assertThat(store.entries("https://x.test")).hasSize(1)
    }
}
