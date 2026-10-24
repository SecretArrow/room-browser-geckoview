package com.roombrowser.agent

import com.roombrowser.domain.agent.SecretRedaction
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
        val messages: List<Message>
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

    /** One chat's line of the token-usage section. */
    data class UsageRow(
        val title: String,
        val promptTokens: Long,
        val completionTokens: Long,
        val turns: Int
    )

    /**
     * The usage section's input, already detached from the database and
     * already filtered by the caller (zero-usage chats omitted — a table of
     * zeros is absence dressed as data). Null when the caller has no usage
     * reader at all; the section is also skipped when nothing was reported.
     */
    data class UsageSection(
        val rows: List<UsageRow>,
        val totalPrompt: Long,
        val totalCompletion: Long,
        val totalTurns: Int
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
    fun render(header: Header, sessions: List<Session>, usage: UsageSection? = null): String =
        SecretRedaction.redact(buildDocument(header, sessions, usage))

    private fun buildDocument(header: Header, sessions: List<Session>, usage: UsageSection?): String = buildString {
        appendLine("Room Browser — AI agent chat export")
        appendLine("Profile: ${header.profileLabel}")
        appendLine("Exported: ${stamp(header.exportedAt)}")
        appendLine("Chats: ${sessions.size}   Messages: ${sessions.sumOf { it.messages.size }}")
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

        // The usage section closes the document, after the transcripts it
        // summarises. Skipped entirely when nothing was reported anywhere —
        // a section that says "0" everywhere is not information.
        if (usage != null && (usage.rows.isNotEmpty() ||
                usage.totalPrompt > 0 || usage.totalCompletion > 0)
        ) {
            appendLine()
            appendLine(RULE)
            appendLine("Token usage")
            appendLine(RULE)
            appendLine("(as reported by the provider; counts it did not send are not shown)")
            usage.rows.forEach { row ->
                appendLine()
                appendLine(row.title)
                appendLine(
                    "    prompt ${num(row.promptTokens)}   completion ${num(row.completionTokens)}   " +
                        "total ${num(row.promptTokens + row.completionTokens)}   turns ${row.turns}"
                )
            }
            appendLine()
            appendLine("All chats")
            appendLine(
                "    prompt ${num(usage.totalPrompt)}   completion ${num(usage.totalCompletion)}   " +
                    "total ${num(usage.totalPrompt + usage.totalCompletion)}   turns ${usage.totalTurns}"
            )
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

    /** Grouped thousands, US form — an export is read away from the device. */
    private fun num(value: Long): String = String.format(Locale.US, "%,d", value)
}
