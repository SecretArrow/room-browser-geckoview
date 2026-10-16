package com.roombrowser.domain.plugins

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The card parse: clamps over rejects where the design says clamp, drops what
 * has nothing to show, and never throws on a shape it does not recognise — a
 * plugin's bad card must not cost the chat the cards after it.
 */
class PluginCardTest {

    @Test
    fun `the design's own card parses field for field`() {
        val cards = PluginCard.parseList(
            AgentJson.parseToJsonElement(
                """[{"title":"T","text":"body","fields":[["k","v"]],
                    "link":{"label":"Open","url":"https://example.com"},"style":"warn"}]"""
            )
        )
        assertThat(cards).hasSize(1)
        val card = cards[0]
        assertThat(card.title).isEqualTo("T")
        assertThat(card.text).isEqualTo("body")
        assertThat(card.fields).containsExactly("k" to "v")
        assertThat(card.link?.label).isEqualTo("Open")
        assertThat(card.link?.url).isEqualTo("https://example.com")
        assertThat(card.style).isEqualTo(PluginCardStyle.WARN)
    }

    @Test
    fun `a missing style is info, and an unknown one is not a failure`() {
        val plain = parseCard("""{"text":"hi"}""")
        assertThat(plain?.style).isEqualTo(PluginCardStyle.INFO)
        val odd = parseCard("""{"text":"hi","style":"sparkly"}""")
        assertThat(odd?.style).isEqualTo(PluginCardStyle.INFO)
    }

    @Test
    fun `overlong content is clamped, not rejected`() {
        val card = parseCard(
            """{"title":"${"t".repeat(300)}","text":"${"b".repeat(5000)}",
                "fields":[["${"k".repeat(300)}","${"v".repeat(300)}"]],
                "link":{"label":"${"l".repeat(100)}","url":"https://example.com/${"u".repeat(600)}"}}"""
        )!!
        assertThat(card.title?.length).isEqualTo(120)
        assertThat(card.text?.length).isEqualTo(4000)
        assertThat(card.fields[0].first.length).isEqualTo(200)
        assertThat(card.fields[0].second.length).isEqualTo(200)
        // The URL over its cap is dropped whole rather than cut: a truncated
        // URL opens the wrong page, which is worse than no link row.
        assertThat(card.link).isNull()
    }

    @Test
    fun `a non-http link is dropped and the card survives`() {
        val card = parseCard("""{"text":"hi","link":{"label":"go","url":"javascript:alert(1)"}}""")!!
        assertThat(card.link).isNull()
        assertThat(card.text).isEqualTo("hi")
    }

    @Test
    fun `a card with nothing to show is dropped`() {
        assertThat(parseCard("""{"style":"info"}""")).isNull()
        assertThat(parseCard("""{"title":"   "}""")).isNull()
        assertThat(parseCard("""{"fields":[]}""")).isNull()
    }

    @Test
    fun `more fields than the cap are cut, not all kept`() {
        val fields = (1..20).joinToString(",") { """["k$it","v"]""" }
        val card = parseCard("""{"fields":[$fields]}""")!!
        assertThat(card.fields).hasSize(12)
    }

    @Test
    fun `more cards than the cap are dropped, and malformed ones cost nothing`() {
        val cards = PluginCard.parseList(
            AgentJson.parseToJsonElement(
                "[" + (1..7).joinToString(",") { """{"text":"c$it"}""" } + "]"
            )
        )
        assertThat(cards).hasSize(5)
        assertThat(cards.map { it.text }).containsExactly("c1", "c2", "c3", "c4", "c5").inOrder()

        val mixed = PluginCard.parseList(
            AgentJson.parseToJsonElement("""["a string", {"text":"ok"}, 7, null]""")
        )
        assertThat(mixed).hasSize(1)
        assertThat(mixed[0].text).isEqualTo("ok")
    }

    @Test
    fun `a non-array or absent cards element is an empty list`() {
        assertThat(PluginCard.parseList(null)).isEmpty()
        assertThat(PluginCard.parseList(AgentJson.parseToJsonElement("""{"text":"no"}"""))).isEmpty()
    }

    @Test
    fun `a malformed fields pair is skipped, its neighbours kept`() {
        val card = parseCard("""{"fields":[["one"],["k","v"],["a","b","c"]]}""")!!
        assertThat(card.fields).containsExactly("k" to "v")
    }

    private fun parseCard(body: String): PluginCard? =
        PluginCard.parse(AgentJson.parseToJsonElement(body).jsonObject as? JsonObject)
}
