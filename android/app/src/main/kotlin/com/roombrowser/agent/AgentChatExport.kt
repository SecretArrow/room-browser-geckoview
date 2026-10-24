package com.roombrowser.agent

import com.roombrowser.domain.agent.SecretRedaction
import com.roombrowser.domain.agent.TokenUsage
import com.roombrowser.domain.agent.UntrustedContent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a profile's agent chats as ONE plain-text document.
 *
 * Text, not JSON, because the point of this export is to be read: it is what
 * a user attaches to a bug report or keeps as a record of what the agent did
 * on their behalf. The tool calls are in it for the same reason — "the agent
 * clicked Delete" is the part of a transcript that explains an outcome, and a
 * file containing only the chat bubbles would omit it.
 *
 * Pure by construction: no Android, no database, no clock of its own (every
 * timestamp arrives in the input). That is what lets the format be pinned by
 * a JVM unit test rather than by running an emulator.
 *
 * ## On timestamps
 *
 * Formatted with [Locale.US] in a fixed `yyyy-MM-dd HH:mm:ss` shape, not the
 * device locale. An export is frequently read on a different machine than the
 * one that wrote it, and a date like `02/10/2026` is ambiguous across locales
 * in exactly the case where it matters. Local time is still the right value —
 * the user wants to recognise when they asked something — so it is the
 * FORMAT that is fixed, not the zone.
 */
object AgentChatExport {

    /** One message row, already detached from the database. */
    data class Message(
        val role: String,
        val at: Long,
        val content: String,
        val toolName: String? = null,
        val toolArgs: String? = null,
        val toolResult: String? = null
    )

    /** One chat, oldest message first. */
    data class Session(
        val title: String,
        val model: String,
        val updatedAt: Long,
        val messages: List<Message>,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0
    )

    /**
     * What the document says about itself. [profileLabel] is the profile
     * NAME, never its id: the file leaves the app, and a profile id is an
     * opaque identifier the user has never seen.
     */
    data class Header(
        val profileLabel: String,
        val exportedAt: Long,
        val providers: List<String>
    )

    private const val RULE = "================================================================"

    /**
     * The document, with every credential-shaped string masked.
     *
     * Redaction is applied to the FINISHED text rather than to each field, and
     * that is deliberate: this file leaves the app — it is attached to bug
     * reports and shared — and a field-by-field pass is only as complete as the
     * list of fields someone remembered. Masking the whole document cannot miss
     * a line added later.
     */
    fun render(header: Header, sessions: List<Session>): String =
        SecretRedaction.redact(buildDocument(header, sessions))

    private fun buildDocument(header: Header, sessions: List<Session>): String = buildString {
        appendLine("Room Browser — AI agent chat export")
        appendLine("Profile: ${header.profileLabel}")
        appendLine("Exported: ${stamp(header.exportedAt)}")
        appendLine("Chats: ${sessions.size}   Messages: ${sessions.sumOf { it.messages.size }}")
        // Document-level totals: the sum every per-chat line rolls up to.
        appendLine(
            "Tokens total: ${TokenUsage.count(sessions.sumOf { it.inputTokens })} in · " +
                "${TokenUsage.count(sessions.sumOf { it.outputTokens })} out"
        )
        appendLine(
            "Providers: " +
                (header.providers.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "(none configured)")
        )
        appendLine()
        appendLine("Chats are scoped to this profile; nothing from any other profile is in this file.")

        if (sessions.isEmpty()) {
            appendLine()
            appendLine("(no chats recorded)")
            return@buildString
        }

        sessions.forEach { session ->
            appendLine()
            appendLine(RULE)
            appendLine("${session.title}  ·  ${session.model}  ·  updated ${stamp(session.updatedAt)}")
            // Token cost rides the title line's block: it is a per-chat total
            // the user compares across chats, not a per-message detail.
            appendLine(
                "Tokens: ${TokenUsage.count(session.inputTokens)} in · " +
                    "${TokenUsage.count(session.outputTokens)} out"
            )
            appendLine(RULE)
            if (session.messages.isEmpty()) {
                appendLine("(no messages)")
                return@forEach
            }
            session.messages.forEach { message ->
                appendLine()
                appendLine("[${stamp(message.at)}] ${label(message.role)}")
                appendLine(indent(message.content))
                if (message.toolName != null) {
                    appendLine("    tool: ${message.toolName}")
                    message.toolArgs?.takeIf { it.isNotBlank() }?.let {
                        appendLine("    args: $it")
                    }
                    message.toolResult?.takeIf { it.isNotBlank() }?.let {
                        appendLine("    result: ${UntrustedContent.payload(it)}")
                    }
                }
            }
        }
    }

    /**
     * A filename that sorts by date and survives every filesystem: no spaces,
     * no colons (illegal on FAT and a path separator on classic Mac), ASCII
     * only. Seconds are included so two exports in the same minute do not
     * silently overwrite each other in a Downloads folder.
     */
    fun fileName(at: Long): String =
        "room-browser-agent-chats-${fileStamp(at)}.txt"

    /** Speaker label. Unknown roles are upper-cased rather than dropped. */
    private fun label(role: String): String = when (role.lowercase(Locale.US)) {
        "user" -> "YOU"
        "assistant" -> "AGENT"
        "tool" -> "TOOL"
        "system" -> "SYSTEM"
        else -> role.uppercase(Locale.US)
    }

    /** Two-space gutter so the speaker labels stand out from the body text. */
    private fun indent(content: String): String =
        content.lines().joinToString("\n") { "  $it" }

    private fun stamp(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at))

    private fun fileStamp(at: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
}
