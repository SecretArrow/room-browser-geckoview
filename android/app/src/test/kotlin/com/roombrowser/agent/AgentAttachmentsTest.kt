package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM tests for the agent attachment pipeline's pure rendering logic:
 * [AgentAttachment] metadata + [renderAttachments] prompt-block behavior
 * (inlining, binary handling, inline budget, joining).
 *
 * The size in the header comes from
 * [com.roombrowser.domain.download.DownloadFormat], whose own test pins the
 * arithmetic; these pin that the header carries it.
 */
class AgentAttachmentsTest {

    @Test
    fun `empty attachment list renders empty string`() {
        assertThat(renderAttachments(emptyList())).isEmpty()
    }

    @Test
    fun `text file is inlined with a header containing name and size`() {
        val rendered = renderAttachments(
            listOf(AgentAttachment(name = "notes.txt", mime = "text/plain", sizeBytes = 1234L, text = "hello notes"))
        )
        assertThat(rendered).contains("[Attached file: notes.txt — text/plain, 1.2 KB]")
        assertThat(rendered).contains("hello notes")
    }

    @Test
    fun `binary file renders metadata only without content`() {
        val rendered = renderAttachments(
            listOf(AgentAttachment(name = "photo.jpg", mime = "image/jpeg", sizeBytes = 348160L, text = null))
        )
        assertThat(rendered)
            .contains("[Attached file: photo.jpg — image/jpeg, 340 KB] — binary file, content not inlined")
        assertThat(rendered).doesNotContain("photo.jpg\n") // no content block after the header line
        assertThat(rendered.lines()).hasSize(1)
    }

    @Test
    fun `renderer does not truncate a single long attachment`() {
        // Extraction caps each file at 20 000 chars on the caller side; the
        // renderer itself must pass a longer (already-extracted) blob through.
        val content = "x".repeat(25_000)
        val rendered = renderAttachments(
            listOf(AgentAttachment(name = "big.txt", mime = "text/plain", sizeBytes = 25_000L, text = content))
        )
        assertThat(rendered).contains(content)
        assertThat(rendered).doesNotContain("truncated")
    }

    @Test
    fun `inline budget degrades later text attachments`() {
        // 4 × 20k chars = 80k > ~60k budget: the first three are inlined, the
        // fourth degrades to a metadata line with the skip reason.
        val content = "y".repeat(20_000)
        val attachments = (1..4).map { index ->
            AgentAttachment(name = "f$index.txt", mime = "text/plain", sizeBytes = 20_000L, text = content)
        }
        val rendered = renderAttachments(attachments)
        val sections = rendered.split("\n\n")
        assertThat(sections).hasSize(4)
        assertThat(sections.count { it.contains(content) }).isEqualTo(3)
        assertThat(sections[3]).contains("(skipped: attachment budget exceeded)")
        assertThat(sections[3]).doesNotContain(content)
    }

    @Test
    fun `multiple attachments are joined with a blank line`() {
        val rendered = renderAttachments(
            listOf(
                AgentAttachment(name = "a.txt", mime = "text/plain", sizeBytes = 10L, text = "AAA"),
                AgentAttachment(name = "b.json", mime = "application/json", sizeBytes = 20L, text = """{"b":1}""")
            )
        )
        val sections = rendered.split("\n\n")
        assertThat(sections).hasSize(2)
        assertThat(sections[0]).contains("[Attached file: a.txt")
        assertThat(sections[0]).contains("AAA")
        assertThat(sections[1]).contains("[Attached file: b.json")
        assertThat(sections[1]).contains("""{"b":1}""")
    }

    @Test
    fun `empty text content renders as an empty inline section`() {
        // A text-like file whose extracted text is empty (e.g. blank file)
        // still gets the header + empty content block.
        val rendered = renderAttachments(
            listOf(AgentAttachment(name = "empty.txt", mime = "text/plain", sizeBytes = 0L, text = ""))
        )
        assertThat(rendered).contains("[Attached file: empty.txt — text/plain, 0 B]")
        assertThat(rendered).doesNotContain("binary file")
    }
}
