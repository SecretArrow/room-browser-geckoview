package com.roombrowser.domain.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The agent's own step list, and the only tool in the catalogue that changes
 * nothing but what the user sees.
 *
 * STATELESS ON PURPOSE. Every call carries the WHOLE list, so there is no
 * server-side copy to drift out of step with what the model believes: the
 * list the user is looking at is always the list the model last wrote, and a
 * call that fails leaves the previous one standing rather than half-applied.
 * That also means the tool has no store, no profile data and nothing to clean
 * up — which is why a scheduled run may use it exactly as a chat turn does.
 *
 * A step's text is the model's own sentence about the task, so nothing here is
 * page-derived and nothing is fenced; the list is shown as the plan the model
 * is working to, not as something read off a site.
 */
object AgentPlan {

    /** More steps than this is a list nobody reads, and a context cost per call. */
    const val MAX_ITEMS = 24

    /** One step is a line on a card, not a paragraph. */
    const val MAX_TEXT_CHARS = 200

    enum class Status(val id: String, val mark: String) {
        PENDING("pending", "[ ]"),
        IN_PROGRESS("in_progress", "[>]"),
        DONE("done", "[x]");

        companion object {
            val ids: List<String> = entries.map { it.id }

            /** Lenient about case and spacing, strict about the word. */
            fun of(raw: String?): Status? {
                val id = raw?.trim()?.lowercase() ?: return null
                return entries.firstOrNull { it.id == id }
            }
        }
    }

    data class Item(val text: String, val status: Status)

    sealed interface Parsed {
        data class Ok(val items: List<Item>) : Parsed
        data class Failure(val reason: String) : Parsed
    }

    /**
     * The list as the model sent it, or the sentence to hand back when it
     * cannot be read. A rejection is a real answer rather than a silent
     * substitute: replacing a bad list with an empty one would erase the
     * progress the user is watching.
     */
    fun parse(argsJson: String): Parsed {
        val root = runCatching { AgentJson.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
            ?: return Parsed.Failure("plan: the arguments were not a JSON object")
        val array = root["items"] as? JsonArray
            ?: return Parsed.Failure("plan: 'items' must be a list of steps, each with 'text' and 'status'")
        if (array.isEmpty()) {
            return Parsed.Failure(
                "plan: 'items' was empty. Send the whole list every time, including the steps " +
                    "already done — it replaces the previous one rather than adding to it."
            )
        }
        if (array.size > MAX_ITEMS) {
            return Parsed.Failure("plan: at most $MAX_ITEMS steps, and this call sent ${array.size}")
        }
        val items = ArrayList<Item>(array.size)
        array.forEachIndexed { index, element ->
            val step = element as? JsonObject
                ?: return Parsed.Failure("plan: step ${index + 1} is not an object with 'text' and 'status'")
            val text = step["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (text.isBlank()) return Parsed.Failure("plan: step ${index + 1} has no text")
            val raw = step["status"]?.jsonPrimitive?.contentOrNull
            val status = Status.of(raw)
                ?: return Parsed.Failure(
                    "plan: step ${index + 1} has status '${raw.orEmpty()}' — use ${Status.ids.joinToString(", ")}"
                )
            items += Item(text.take(MAX_TEXT_CHARS), status)
        }
        return Parsed.Ok(items)
    }

    /** The list as the model reads it back: one line per step, marked. */
    fun render(items: List<Item>): String {
        if (items.isEmpty()) return "The plan is empty."
        val done = items.count { it.status == Status.DONE }
        val head = "Plan — $done of ${items.size} done:"
        return (listOf(head) + items.map { "${it.status.mark} ${it.text}" }).joinToString("\n")
    }

    /** What the model is handed after a successful call. */
    fun confirmation(items: List<Item>): String = buildString {
        appendLine(render(items))
        append(
            "The user is looking at this list. Keep it up to date with the same tool: send the " +
                "whole list again with the current step marked in_progress and the finished ones done."
        )
    }

    /**
     * The tool call, whole. Here rather than in each executor because both
     * need it and the answer must be the same sentence in both: a chat turn
     * and a scheduled run read their results with the same eyes.
     */
    fun execute(argsJson: String): ToolResult = when (val parsed = parse(argsJson)) {
        is Parsed.Ok -> ToolResult(true, confirmation(parsed.items), plan = parsed.items)
        is Parsed.Failure -> ToolResult(false, parsed.reason)
    }
}
