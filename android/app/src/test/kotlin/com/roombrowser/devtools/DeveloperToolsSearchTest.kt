package com.roombrowser.devtools

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The Search panel's pure halves, pinned without Compose and without an
 * engine: the app-side search over the two buffers this surface already holds,
 * and the probe script's shape.
 *
 * THE SCRIPT TESTS ARE THE SECURITY PROPERTY. The query a user types is
 * arbitrary text, and it arrives inside a JavaScript string literal the app
 * wrote -- so the only thing standing between a quote and a broken-out script
 * is the `jsString` escaping every injected query must go through. A test that
 * built its own escaping instead of asserting on the script's own text would
 * prove nothing, so the assertions are made against the script the panel will
 * actually send.
 */
class DeveloperToolsSearchTest {

    // ---------- consoleHits ------------------------------------------------

    @Test
    fun `a console entry matches on its text case-insensitively`() {
        val entries = listOf(ConsoleEntry(level = "log", text = "Room says HELLO"))
        assertThat(consoleHits(entries, "hello")).hasSize(1)
        assertThat(consoleHits(entries, "ROOM")).hasSize(1)
        assertThat(consoleHits(entries, "goodbye")).isEmpty()
    }

    @Test
    fun `a console entry matches on its source url too`() {
        val entries = listOf(
            ConsoleEntry(level = "error", text = "unrelated text", source = "https://example.com/app.js")
        )
        assertThat(consoleHits(entries, "app.js")).hasSize(1)
    }

    @Test
    fun `the console search keeps the newest hundred rows`() {
        val entries = (1..250).map { ConsoleEntry(level = "log", text = "needle $it") }
        val hits = consoleHits(entries, "needle")
        assertThat(hits).hasSize(100)
        assertThat(hits.first().text).isEqualTo("needle 151")
        assertThat(hits.last().text).isEqualTo("needle 250")
    }

    @Test
    fun `an empty query matches nothing rather than everything`() {
        assertThat(consoleHits(listOf(ConsoleEntry(level = "log", text = "anything")), "")).isEmpty()
        assertThat(networkHits(listOf(NetworkEntry(kind = "REQUEST", url = "https://a/")), "")).isEmpty()
    }

    // ---------- networkHits ------------------------------------------------

    @Test
    fun `a network row matches on its url method kind status and header names`() {
        val entry = NetworkEntry(
            kind = "REQUEST",
            url = "https://example.com/api/v1",
            method = "GET",
            status = 404,
            requestHeaders = listOf(HeaderEntry("X-Trace", "abc"))
        )
        assertThat(networkHits(listOf(entry), "example.com")).hasSize(1)
        assertThat(networkHits(listOf(entry), "get")).hasSize(1)
        assertThat(networkHits(listOf(entry), "request")).hasSize(1)
        assertThat(networkHits(listOf(entry), "40")).hasSize(1)
        assertThat(networkHits(listOf(entry), "x-trace")).hasSize(1)
        assertThat(networkHits(listOf(entry), "v2")).isEmpty()
    }

    @Test
    fun `a redacted header value never becomes searchable text`() {
        val entry = NetworkEntry(
            kind = "REQUEST",
            url = "https://example.com/api",
            requestHeaders = listOf(HeaderEntry("Authorization", REDACTED))
        )
        // The original secret is not in the buffer at all, and the placeholder
        // that replaced it is not search text either: header values are never
        // read, so no query can hit a row through one.
        assertThat(networkHits(listOf(entry), "Bearer")).isEmpty()
        assertThat(networkHits(listOf(entry), "<redacted>")).isEmpty()
        // The NAME is a fact the row genuinely carries, so it is found.
        assertThat(networkHits(listOf(entry), "authorization")).hasSize(1)
    }

    @Test
    fun `the redacted placeholder is findable only where it genuinely sits`() {
        // A URL with masked credentials keeps the placeholder in the URL itself,
        // and the URL is on the row -- so typing the literal finds that row and
        // nothing else, which is the one honest way that string can be a hit.
        val entry = NetworkEntry(
            kind = "REQUEST",
            url = redactUrl("https://alice:hunter2@example.com/private")
        )
        assertThat(networkHits(listOf(entry), "hunter2")).isEmpty()
        assertThat(networkHits(listOf(entry), "<redacted>")).hasSize(1)
    }

    @Test
    fun `the network search keeps the newest hundred rows`() {
        val entries = (1..250).map { NetworkEntry(kind = "REQUEST", url = "https://a/$it-needle") }
        val hits = networkHits(entries, "needle")
        assertThat(hits).hasSize(100)
        assertThat(hits.first().url).isEqualTo("https://a/151-needle")
        assertThat(hits.last().url).isEqualTo("https://a/250-needle")
    }

    // ---------- searchJs ---------------------------------------------------

    @Test
    fun `a hostile query stays inside the string literal it arrives in`() {
        val script = DeveloperToolsSearchScripts.searchJs("a\"b\\c\nd")
        // The escaped form: the quote backslashed, the backslash doubled, the
        // newline as the two characters \ n -- all inside the literal.
        assertThat(script).contains("\"a\\\"b\\\\c\\nd\"")
        // The raw characters never appear: a real newline inside a JS string
        // literal is a syntax error, and an unescaped quote would end it.
        assertThat(script).doesNotContain("a\"b\\c\nd")
    }

    @Test
    fun `the probe script is one balanced iife that answers null on failure`() {
        val script = DeveloperToolsSearchScripts.searchJs("needle")
        assertThat(script).startsWith("(function () {")
        assertThat(script).endsWith("})()")
        // The answer convention the other probes keep: one JSON.stringify of
        // the whole answer, and null rather than a thrown error.
        assertThat(script).contains("return JSON.stringify({")
        assertThat(script).contains("} catch (e) { return null; }")
        // Balanced as written: every bracket this script opens, it closes --
        // and the query above deliberately contains none, so the counts say
        // something about the script and not about the needle.
        assertThat(script.count { it == '(' }).isEqualTo(script.count { it == ')' })
        assertThat(script.count { it == '{' }).isEqualTo(script.count { it == '}' })
        assertThat(script.count { it == '[' }).isEqualTo(script.count { it == ']' })
    }

    @Test
    fun `a two character query still builds a whole probe`() {
        val script = DeveloperToolsSearchScripts.searchJs("ab")
        assertThat(script).isNotEmpty()
        assertThat(script).contains("\"ab\"")
    }

    // ---------- the probe type ---------------------------------------------

    @Test
    fun `the probe decodes the field names the script emits`() {
        val json = """
            {
              "variables": [{"name": "roomSearchGlobal", "kind": "string", "preview": "abc"}],
              "storage": [{"area": "local", "key": "room-search-key", "matchedIn": "value", "valueLength": 9}],
              "cookies": [{"name": "session", "matchedIn": "name"}],
              "dom": [{"tag": "h1", "id": "main", "classes": "title", "snippet": "Hello room"}],
              "variablesTruncated": true,
              "storageTruncated": false,
              "domTruncated": true
            }
        """.trimIndent()
        val probe = Json.decodeFromString(SearchProbe.serializer(), json)
        assertThat(probe.variables).hasSize(1)
        assertThat(probe.variables[0].name).isEqualTo("roomSearchGlobal")
        assertThat(probe.variables[0].kind).isEqualTo("string")
        assertThat(probe.variables[0].preview).isEqualTo("abc")
        assertThat(probe.storage).hasSize(1)
        assertThat(probe.storage[0].area).isEqualTo("local")
        assertThat(probe.storage[0].key).isEqualTo("room-search-key")
        assertThat(probe.storage[0].matchedIn).isEqualTo("value")
        assertThat(probe.storage[0].valueLength).isEqualTo(9)
        assertThat(probe.cookies).hasSize(1)
        assertThat(probe.cookies[0].name).isEqualTo("session")
        assertThat(probe.cookies[0].matchedIn).isEqualTo("name")
        assertThat(probe.dom).hasSize(1)
        assertThat(probe.dom[0].tag).isEqualTo("h1")
        assertThat(probe.dom[0].id).isEqualTo("main")
        assertThat(probe.dom[0].classes).isEqualTo("title")
        assertThat(probe.dom[0].snippet).isEqualTo("Hello room")
        assertThat(probe.variablesTruncated).isTrue()
        assertThat(probe.storageTruncated).isFalse()
        assertThat(probe.domTruncated).isTrue()
    }

    @Test
    fun `an answer that carried no hits decodes to empty lists not failures`() {
        val probe = Json.decodeFromString(SearchProbe.serializer(), "{}")
        assertThat(probe.variables).isEmpty()
        assertThat(probe.storage).isEmpty()
        assertThat(probe.cookies).isEmpty()
        assertThat(probe.dom).isEmpty()
        assertThat(probe.variablesTruncated).isFalse()
        assertThat(probe.storageTruncated).isFalse()
        assertThat(probe.domTruncated).isFalse()
    }
}
