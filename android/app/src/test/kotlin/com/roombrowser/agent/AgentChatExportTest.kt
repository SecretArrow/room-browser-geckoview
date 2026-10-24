package com.roombrowser.agent

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.UntrustedContent
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * JVM tests for the export format. The document is what a user attaches to a
 * bug report, so its shape is a contract: pinned here rather than discovered
 * by reading a file off a device.
 */
class AgentChatExportTest {

    private lateinit var previousZone: TimeZone

    @Before
    fun fixTimeZone() {
        // Fixed so the formatted timestamps are asserted exactly. The zone is
        // the user's in production; only the FORMAT is fixed there.
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(previousZone)
    }

    private val at = 1_759_400_000_000L // 2025-10-02 10:13:20 UTC

    private fun header(providers: List<String> = listOf("MockLLM (OPENAI)")) =
        AgentChatExport.Header(
            profileLabel = "Work",
            exportedAt = at,
            providers = providers
        )

    @Test
    fun `the header names the profile, never its id, and says what is scoped`() {
        val text = AgentChatExport.render(header(), emptyList())

        assertThat(text).contains("Room Browser — AI agent chat export")
        assertThat(text).contains("Profile: Work")
        assertThat(text).contains("Exported: 2025-10-02 10:13:20")
        assertThat(text).contains("Chats: 0   Messages: 0")
        assertThat(text).contains("Providers: MockLLM (OPENAI)")
        assertThat(text).contains("scoped to this profile")
    }

    @Test
    fun `a profile with no chats still renders a readable document`() {
        val text = AgentChatExport.render(header(), emptyList())

        assertThat(text).contains("(no chats recorded)")
        assertThat(text).doesNotContain("================")
    }

    @Test
    fun `providers are reported as none rather than an empty label`() {
        assertThat(AgentChatExport.render(header(providers = emptyList()), emptyList()))
            .contains("Providers: (none configured)")
    }

    @Test
    fun `a chat renders its messages oldest first, with speaker labels`() {
        val text = AgentChatExport.render(
            header(),
            listOf(
                AgentChatExport.Session(
                    title = "Find a laptop",
                    model = "mock-model-a",
                    updatedAt = at,
                    messages = listOf(
                        AgentChatExport.Message("user", at, "find me a laptop"),
                        AgentChatExport.Message("assistant", at + 5_000, "looking now")
                    )
                )
            )
        )

        assertThat(text).contains("Find a laptop  ·  mock-model-a")
        assertThat(text).contains("[2025-10-02 10:13:20] YOU")
        assertThat(text).contains("  find me a laptop")
        assertThat(text).contains("[2025-10-02 10:13:25] AGENT")
        assertThat(text.indexOf("find me a laptop"))
            .isLessThan(text.indexOf("looking now"))
    }

    @Test
    fun `tool activity is part of the transcript`() {
        // "The agent clicked Delete" is the part of a transcript that
        // explains an outcome; a file with only the chat bubbles omits it.
        val text = AgentChatExport.render(
            header(),
            listOf(
                AgentChatExport.Session(
                    title = "Post it",
                    model = "m",
                    updatedAt = at,
                    messages = listOf(
                        AgentChatExport.Message(
                            role = "assistant",
                            at = at,
                            content = "Posting now",
                            toolName = "click",
                            toolArgs = """{"selector":"#submit"}""",
                            toolResult = "ok"
                        )
                    )
                )
            )
        )

        assertThat(text).contains("    tool: click")
        assertThat(text).contains("""    args: {"selector":"#submit"}""")
        assertThat(text).contains("    result: ok")
    }

    @Test
    fun `blank tool fields are omitted rather than printed empty`() {
        val text = AgentChatExport.render(
            header(),
            listOf(
                AgentChatExport.Session(
                    title = "t",
                    model = "m",
                    updatedAt = at,
                    messages = listOf(
                        AgentChatExport.Message("assistant", at, "hi", toolName = "read")
                    )
                )
            )
        )

        assertThat(text).contains("    tool: read")
        assertThat(text).doesNotContain("args:")
        assertThat(text).doesNotContain("result:")
    }

    @Test
    fun `multi-line content stays inside the transcript gutter`() {
        val text = AgentChatExport.render(
            header(),
            listOf(
                AgentChatExport.Session(
                    title = "t",
                    model = "m",
                    updatedAt = at,
                    messages = listOf(
                        AgentChatExport.Message("user", at, "line one\nline two")
                    )
                )
            )
        )

        assertThat(text).contains("  line one\n  line two")
    }

    @Test
    fun `a tool result is exported without the fence`() {
        val text = AgentChatExport.render(
            header(),
            listOf(
                AgentChatExport.Session(
                    title = "Read it",
                    model = "m",
                    updatedAt = at,
                    messages = listOf(
                        AgentChatExport.Message(
                            role = "tool",
                            at = at,
                            content = "Sign in to continue.",
                            toolName = "read_page",
                            toolResult = UntrustedContent.wrap("Sign in to continue.")
                        )
                    )
                )
            )
        )

        assertThat(text).contains("    result: Sign in to continue.")
        assertThat(text).doesNotContain(UntrustedContent.BEGIN)
    }

    @Test
    fun `an unknown role is labelled, not dropped`() {
        val text = AgentChatExport.render(
            header(),
            listOf(
                AgentChatExport.Session(
                    title = "t",
                    model = "m",
                    updatedAt = at,
                    messages = listOf(AgentChatExport.Message("system", at, "seed"))
                )
            )
        )

        assertThat(text).contains("] SYSTEM")
    }

    @Test
    fun `the suggested filename sorts by date and is safe on every filesystem`() {
        val name = AgentChatExport.fileName(at)

        assertThat(name).isEqualTo("room-browser-agent-chats-20251002-101320.txt")
        // No spaces, no colons (illegal on FAT, a separator on classic Mac).
        assertThat(name).doesNotContain(" ")
        assertThat(name).doesNotContain(":")
        assertThat(name.all { it.code < 128 }).isTrue()
    }

    @Test
    fun `each chat carries its token totals and the header sums them`() {
        val text = AgentChatExport.render(
            header(),
            listOf(
                AgentChatExport.Session(
                    title = "wallet check",
                    model = "mock-model-a",
                    updatedAt = at,
                    messages = listOf(AgentChatExport.Message("user", at, "balance?")),
                    inputTokens = 1_234,
                    outputTokens = 5_678
                ),
                AgentChatExport.Session(
                    title = "news run",
                    model = "mock-model-a",
                    updatedAt = at,
                    messages = listOf(AgentChatExport.Message("user", at, "headlines")),
                    inputTokens = 10,
                    outputTokens = 22
                )
            )
        )

        // Per-chat line, grouped exactly as TokenUsage renders it.
        assertThat(text).contains("Tokens: 1,234 in · 5,678 out")
        // Document-level rollup: the sum across every chat block.
        assertThat(text).contains("Tokens total: 1,244 in · 5,700 out")
    }

    @Test
    fun `a chat with no recorded usage still renders a zeroed token line`() {
        val text = AgentChatExport.render(
            header(),
            listOf(
                AgentChatExport.Session(
                    title = "t",
                    model = "m",
                    updatedAt = at,
                    messages = listOf(AgentChatExport.Message("user", at, "hi"))
                )
            )
        )

        assertThat(text).contains("Tokens: 0 in · 0 out")
    }
}
