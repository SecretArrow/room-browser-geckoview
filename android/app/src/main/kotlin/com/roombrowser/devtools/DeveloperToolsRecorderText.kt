package com.roombrowser.devtools

import com.roombrowser.domain.agent.AgentTools
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val ACTION_NAVIGATE = "NAVIGATE"
internal const val ACTION_CLICK = "CLICK"
internal const val ACTION_INPUT = "INPUT"
internal const val ACTION_CHANGE = "CHANGE"

internal const val SELECTOR_ID = "ID"
internal const val SELECTOR_NAME = "NAME"
internal const val SELECTOR_STRUCTURAL = "STRUCTURAL"
internal const val SELECTOR_NONE = "NONE"

internal const val DEFAULT_TASK_NAME = "Recorded session"

private val RecorderJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * The Recorder's pure layer: how a step reads on screen, and what an export
 * carries.
 *
 * Everything the panel draws and everything a copy leaves with is built here so
 * the two cannot drift, and so the export is testable without a device -- it is
 * the piece that decides whether a recording can be replayed at all.
 */
internal fun recorderActionLabel(action: String): String = when (action) {
    ACTION_NAVIGATE -> "navigate"
    ACTION_CLICK -> "click"
    ACTION_INPUT -> "input"
    ACTION_CHANGE -> "change"
    else -> "unrecognised ($action)"
}

/**
 * How a step's selector reads on screen.
 *
 * The panel names the kind on every step because the three are not equally
 * durable: an id or a name survives a re-render, a structural path does not,
 * and a reader who is told only "selector" cannot tell the two apart.
 */
internal fun recorderSelectorLabel(kind: String): String = when (kind) {
    SELECTOR_ID -> "id"
    SELECTOR_NAME -> "name"
    SELECTOR_STRUCTURAL -> "structural path"
    else -> "no selector"
}

/** True for the selector kinds that can break when the page is re-rendered. */
internal fun recorderSelectorIsWeak(kind: String): Boolean = kind == SELECTOR_STRUCTURAL

/**
 * A moment, written as an offset from the first step so the rows read as a
 * sequence. Truncated to a tenth rather than rounded, so a recorded step never
 * reads as later than it happened.
 */
internal fun recorderOffsetText(offsetMs: Long): String {
    val tenths = (if (offsetMs < 0) 0L else offsetMs) / 100
    return "+${tenths / 10}.${tenths % 10} s"
}

/** How many steps used each selector kind, so the panel can say where a recording is fragile. */
internal fun recorderSelectorTally(steps: List<RecorderStep>): String {
    if (steps.isEmpty()) return "no steps"
    val counts = steps.groupingBy { it.selectorKind }.eachCount()
    return listOf(SELECTOR_ID, SELECTOR_NAME, SELECTOR_STRUCTURAL)
        .mapNotNull { kind -> counts[kind]?.let { "$it ${recorderSelectorLabel(kind)}" } }
        .joinToString(", ")
        .ifBlank { "no usable selectors" }
}

/**
 * Why a step cannot be replayed as an agent step, or null when it can.
 *
 * One function for the row AND the export, so a caveat the panel showed and a
 * step the export refused can never disagree about the same recorded step.
 * A withheld password value is unrepresentable on purpose: nothing here may
 * type a secret back into a page.
 */
internal fun recorderUnrepresentableReason(step: RecorderStep): String? = when (step.action) {
    ACTION_NAVIGATE ->
        if (step.url.isNullOrBlank()) "the step recorded no URL" else null
    ACTION_CLICK ->
        if (step.selector.isNullOrBlank()) "the step recorded no selector" else null
    ACTION_INPUT, ACTION_CHANGE -> when {
        step.selector.isNullOrBlank() -> "the step recorded no selector"
        step.value == null -> "the value was not recorded" + (step.note?.let { " ($it)" } ?: "")
        else -> null
    }
    else -> "this build has no agent step for '${step.action}'"
}

/** One agent tool invocation, ready to hand to the agent's own executor. */
internal data class RecorderToolCall(val tool: String, val argsJson: String, val label: String)

/**
 * The agent step a recorded step replays as, or null when nothing covers it.
 *
 * A click and a typed value become the agent's `run_js`, because the agent's
 * own `click`/`fill_input` address elements by the reference numbers
 * `read_page` hands out and a recording carries selectors, not references.
 * Navigation uses the real `navigate` tool.
 */
internal fun recorderToolCall(step: RecorderStep): RecorderToolCall? {
    if (recorderUnrepresentableReason(step) != null) return null
    return when (step.action) {
        ACTION_NAVIGATE -> RecorderToolCall(
            tool = AgentTools.NAVIGATE,
            argsJson = buildJsonObject { put("url", step.url.orEmpty()) }.toString(),
            label = "navigate to ${step.url}"
        )
        ACTION_CLICK -> RecorderToolCall(
            tool = AgentTools.RUN_JS,
            argsJson = buildJsonObject { put("script", clickScript(step.selector.orEmpty())) }.toString(),
            label = "click ${step.selector}"
        )
        ACTION_INPUT, ACTION_CHANGE -> RecorderToolCall(
            tool = AgentTools.RUN_JS,
            argsJson = buildJsonObject { put("script", fillScript(step.selector.orEmpty(), step.value.orEmpty())) }
                .toString(),
            label = "set ${step.selector}"
        )
        else -> null
    }
}

/** What an export produced: a task that can be run, or the reasons none can be. */
internal sealed interface RecorderExport {

    data class Task(
        val name: String,
        val prompt: String,
        val json: String,
        val toolCalls: List<RecorderToolCall>,
        val warnings: List<String>
    ) : RecorderExport

    data class Refused(val reasons: List<String>) : RecorderExport
}

/**
 * Turns a recording into a Room agent task.
 *
 * Refused rather than exported-with-a-gap: a task built from SOME of the steps
 * would replay a different interaction than the one recorded, and nothing on
 * the receiving side could tell. So one unrepresentable step refuses the whole
 * export and names itself, and the row that carries it says the same thing.
 *
 * A weak selector is NOT a refusal -- the step still runs -- but it is carried
 * as a warning into the task's own instruction, because "this may not resolve
 * next time" is a fact the run needs, not a footnote for the panel.
 */
internal fun recorderExport(steps: List<RecorderStep>, taskName: String, startUrl: String?): RecorderExport {
    if (steps.isEmpty()) return RecorderExport.Refused(listOf("Nothing has been recorded yet."))
    val refusals = mutableListOf<String>()
    val toolCalls = mutableListOf<RecorderToolCall>()
    val warnings = mutableListOf<String>()
    steps.forEachIndexed { index, step ->
        val reason = recorderUnrepresentableReason(step)
        if (reason != null) {
            refusals += "Step ${index + 1} (${recorderActionLabel(step.action)}): $reason."
        } else {
            val call = recorderToolCall(step)
            if (call == null) refusals += "Step ${index + 1}: no agent step covers this action."
            else toolCalls += call
        }
        if (recorderSelectorIsWeak(step.selectorKind)) {
            warnings += "Step ${index + 1} used a structural selector; it may not resolve on a later visit."
        }
    }
    if (refusals.isNotEmpty()) return RecorderExport.Refused(refusals)
    val name = taskName.trim().ifBlank { DEFAULT_TASK_NAME }
    val prompt = recorderTaskPrompt(name, startUrl, steps, warnings)
    return RecorderExport.Task(
        name = name,
        prompt = prompt,
        json = recorderTaskJson(name, startUrl, prompt, steps),
        toolCalls = toolCalls,
        warnings = warnings
    )
}

/** The name a recording exports under, taken from where it started. */
internal fun recorderTaskName(startUrl: String?): String {
    val host = startUrl?.let(::urlHost)
    return if (host.isNullOrBlank()) DEFAULT_TASK_NAME else "$DEFAULT_TASK_NAME on $host"
}

/** The instruction the task carries: the same steps, in the words the agent reads. */
internal fun recorderTaskPrompt(
    name: String,
    startUrl: String?,
    steps: List<RecorderStep>,
    warnings: List<String>
): String {
    val lines = mutableListOf<String>()
    lines += "Replay the recorded session \"$name\" in this tab."
    startUrl?.takeIf { it.isNotBlank() }?.let { lines += "It was recorded on $it." }
    lines += "Do these steps in order, using the browser tools:"
    steps.forEachIndexed { index, step -> lines += "${index + 1}. ${recorderInstruction(step)}" }
    lines += "If a selector does not resolve, stop and say which step failed. Do not guess a different element."
    if (warnings.isNotEmpty()) {
        lines += ""
        lines += warnings
    }
    return lines.joinToString("\n")
}

private fun recorderInstruction(step: RecorderStep): String = when (step.action) {
    ACTION_NAVIGATE -> "Navigate to ${step.url}."
    ACTION_CLICK ->
        "Click the element matching the ${recorderSelectorLabel(step.selectorKind)} selector ${step.selector}."
    ACTION_INPUT ->
        "Type ${quoted(step.value)} into the element matching the " +
            "${recorderSelectorLabel(step.selectorKind)} selector ${step.selector}."
    ACTION_CHANGE ->
        "Choose ${quoted(step.value)} in the element matching the " +
            "${recorderSelectorLabel(step.selectorKind)} selector ${step.selector}."
    else -> "Unknown step ${step.action}."
}

private fun quoted(value: String?): String = "\"${value.orEmpty()}\""

/**
 * The exported task as one JSON document.
 *
 * Room has no task file format to write, so what this carries is the task's own
 * fields -- its name, where the recording started, the instruction -- plus every
 * step, which is what makes it a task rather than a script.
 */
internal fun recorderTaskJson(name: String, startUrl: String?, prompt: String, steps: List<RecorderStep>): String =
    buildJsonObject {
        put("name", name)
        startUrl?.takeIf { it.isNotBlank() }?.let { put("startUrl", it) }
        put("prompt", prompt)
        put(
            "steps",
            buildJsonArray {
                steps.forEach { step ->
                    add(
                        buildJsonObject {
                            put("action", step.action)
                            put("selectorKind", step.selectorKind)
                            step.selector?.let { put("selector", it) }
                            step.value?.let { put("value", it) }
                            step.url?.let { put("url", it) }
                            put("offsetMs", step.timestampMs)
                        }
                    )
                }
            }
        )
    }.toString()

/**
 * One recorded step as the panel draws it and as a copy carries it.
 *
 * Every caveat is on the line: a weak selector, a value that was withheld, a
 * step no agent step can replay. One that lived only on screen would vanish the
 * moment the row was pasted into a report, which is exactly when it matters.
 */
internal fun recorderStepLine(step: RecorderStep, offsetMs: Long): String {
    val target = step.selector ?: step.url ?: "(no target)"
    val head = "${recorderOffsetText(offsetMs)}  ${recorderActionLabel(step.action)}  " +
        "$target  [${recorderSelectorLabel(step.selectorKind)}]"
    val caveats = mutableListOf<String>()
    step.value?.let { caveats += "value \"$it\"" }
    step.note?.takeIf { it.isNotBlank() }?.let { caveats += it }
    if (recorderSelectorIsWeak(step.selectorKind)) {
        caveats += "weak selector: a structural path breaks when the page changes"
    }
    recorderUnrepresentableReason(step)?.let { caveats += "not replayable: $it" }
    return if (caveats.isEmpty()) head else head + "\n    " + caveats.joinToString(" · ")
}

/** A whole recording as one pasteable block: the bound first, then the steps. */
internal fun recorderReport(steps: List<RecorderStep>, cap: Int, dropped: Int): String {
    val head = feedHeaderText("Recorder", "all actions", steps.size, cap, dropped)
    val body = if (steps.isEmpty()) {
        "(nothing recorded)"
    } else {
        val base = steps.first().timestampMs
        steps.joinToString("\n") { recorderStepLine(it, it.timestampMs - base) }
    }
    return "$head\n$body"
}

/**
 * Undoes the engine's JSON-encoding of an evaluated string result.
 *
 * The engine answers a script that returned a string with `"..."`, so the
 * Recorder's own JSON arrives quoted. This is the same rule [InspectorSession]
 * applies to its probes, kept pure here so it is covered by a plain JVM test.
 */
internal fun recorderUnquote(jsResult: String): String =
    runCatching {
        if (jsResult.length >= 2 && jsResult.startsWith("\"") && jsResult.endsWith("\"")) {
            RecorderJson.decodeFromString(String.serializer(), jsResult)
        } else {
            jsResult
        }
    }.getOrDefault(jsResult)

/** The page buffer the last read returned, or null when no reading arrived or it did not decode. */
internal fun decodeRecorderSnapshot(jsResult: String): RecorderSnapshot? {
    val text = recorderUnquote(jsResult)
    if (text.isBlank() || text == "null" || text == "undefined") return null
    return runCatching { RecorderJson.decodeFromString(RecorderSnapshot.serializer(), text) }.getOrNull()
}

/** A JSON string literal that is also a JavaScript one, since JSON strings are a subset of JS strings. */
private fun jsQuote(text: String): String = JsonPrimitive(text).toString()

private fun clickScript(selector: String): String {
    val quoted = jsQuote(selector)
    return "(function(){var el=document.querySelector($quoted);" +
        "if(!el)throw new Error('selector not found: '+$quoted);" +
        "el.click();return 'clicked';})()"
}

private fun fillScript(selector: String, value: String): String {
    val quoted = jsQuote(selector)
    return "(function(){var el=document.querySelector($quoted);" +
        "if(!el)throw new Error('selector not found: '+$quoted);" +
        "el.focus();el.value=${jsQuote(value)};" +
        "el.dispatchEvent(new Event('input',{bubbles:true}));" +
        "el.dispatchEvent(new Event('change',{bubbles:true}));return 'set';})()"
}
