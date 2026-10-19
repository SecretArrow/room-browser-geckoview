package com.roombrowser.agent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The markdown-lite renderer, pinned to its exact output.
 *
 * The streaming path now re-parses on a coarse heartbeat instead of per token,
 * and the completed message must land byte-identical to what the per-token
 * renderer produced — these tests are that contract: the plain text (what the
 * e2e suite reads through semantics) and the span ranges (what the eye reads)
 * are pinned for every branch at once. The line-list hoist inside must never
 * be able to change a single byte.
 */
class AgentMarkdownRenderTest {

    @Test
    fun `plain multi-line text survives byte for byte`() {
        // A blank line in the middle and a trailing newline: both exercise the
        // newline-joining branch against lastIndex.
        val text = "one\n\ntwo\nthree\n"
        assertThat(renderMarkdown(text).toPlainText()).isEqualTo(text)
    }

    @Test
    fun `empty text renders empty`() {
        assertThat(renderMarkdown("").toPlainText()).isEqualTo("")
    }

    @Test
    fun `headings drop their markers and go bold`() {
        val out = renderMarkdown("### Title\n## Sub\n# Top")
        assertThat(out.toPlainText()).isEqualTo("Title\nSub\nTop")
        assertThat(out.spanStyles).hasSize(3)
        out.spanStyles.forEach { range ->
            assertThat(range.item).isEqualTo(SpanStyle(fontWeight = FontWeight.Bold))
        }
    }

    @Test
    fun `bullets render with the bullet glyph in both spellings`() {
        val out = renderMarkdown("- plain\n* starred\n- **bold** item")
        assertThat(out.toPlainText()).isEqualTo("• plain\n• starred\n• bold item")
    }

    @Test
    fun `a bold span carries its exact range`() {
        val out = renderMarkdown("Hello **bold** world")
        assertThat(out.toPlainText()).isEqualTo("Hello bold world")
        assertThat(out.spanStyles).hasSize(1)
        val span = out.spanStyles[0]
        assertThat(span.item).isEqualTo(SpanStyle(fontWeight = FontWeight.Bold))
        assertThat(span.start).isEqualTo(6)
        assertThat(span.end).isEqualTo(10)
    }

    @Test
    fun `an inline code span carries the monospace style with the given background`() {
        val background = Color(0x11223344)
        val out = renderMarkdown("a `code` b", background)
        assertThat(out.toPlainText()).isEqualTo("a code b")
        assertThat(out.spanStyles).hasSize(1)
        val span = out.spanStyles[0]
        assertThat(span.item)
            .isEqualTo(SpanStyle(fontFamily = FontFamily.Monospace, background = background))
        assertThat(span.start).isEqualTo(2)
        assertThat(span.end).isEqualTo(6)
    }

    @Test
    fun `a code span before a bold span keeps the document order`() {
        val out = renderMarkdown("`x` then **y**")
        assertThat(out.toPlainText()).isEqualTo("x then y")
        assertThat(out.spanStyles).hasSize(2)
        assertThat(out.spanStyles[0].start).isEqualTo(0)
        assertThat(out.spanStyles[1].item).isEqualTo(SpanStyle(fontWeight = FontWeight.Bold))
    }

    @Test
    fun `a whole answer renders to the pinned byte layout`() {
        // Every branch at once — heading, prose with both span kinds, a blank
        // line, both bullet spellings, a second heading, plain tail — plus the
        // newline joins between them. This is the completed-message layout the
        // streaming cadence must converge on exactly. Built with explicit
        // escapes on purpose: the pin is about bytes, not about what a raw
        // string's indentation happens to leave behind.
        val text =
            "## Summary\n" +
                "The page lists **three** points and one `snippet`:\n" +
                "\n" +
                "- first point\n" +
                "- second **bold** point\n" +
                "### Tail\n" +
                "done"
        val out = renderMarkdown(text)
        assertThat(out.toPlainText()).isEqualTo(
            "Summary\n" +
                "The page lists three points and one snippet:\n" +
                "\n" +
                "• first point\n" +
                "• second bold point\n" +
                "Tail\n" +
                "done"
        )
        assertThat(out.spanStyles).hasSize(5)
    }

    @Test
    fun `the same answer renders identically twice`() {
        // The streaming path hands a cached AnnotatedString back on a cache
        // hit; determinism is what makes "cached" and "freshly parsed" the
        // same string, so a repeat parse must equal itself.
        val text = "## H\n- a **b**\nc `d`"
        assertThat(renderMarkdown(text)).isEqualTo(renderMarkdown(text))
    }
}
