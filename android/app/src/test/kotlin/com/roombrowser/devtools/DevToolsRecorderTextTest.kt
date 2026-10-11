package com.roombrowser.devtools

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.agent.AgentTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * What a recorded step reads as, and what an export is allowed to produce.
 *
 * The negative controls are the point of this file: an exporter that accepted
 * any step at all would hand the agent a task that replays a different
 * interaction than the one recorded, and nothing downstream could tell.
 */
class DevToolsRecorderTextTest {

    private fun navigate(url: String, at: Long = 0L) =
        RecorderStep(action = ACTION_NAVIGATE, url = url, timestampMs = at)

    private fun click(selector: String, kind: String = SELECTOR_ID, at: Long = 0L) =
        RecorderStep(
            action = ACTION_CLICK,
            selectorKind = kind,
            selector = selector,
            tagName = "button",
            timestampMs = at
        )

    private fun type(selector: String, value: String?, note: String? = null, at: Long = 0L) =
        RecorderStep(
            action = ACTION_INPUT,
            selectorKind = SELECTOR_ID,
            selector = selector,
            value = value,
            note = note,
            timestampMs = at
        )

    @Test
    fun a_step_row_names_the_selector_kind_it_used() {
        assertThat(recorderSelectorLabel(SELECTOR_ID)).isEqualTo("id")
        assertThat(recorderSelectorLabel(SELECTOR_NAME)).isEqualTo("name")
        assertThat(recorderSelectorLabel(SELECTOR_STRUCTURAL)).isEqualTo("structural path")
        assertThat(recorderSelectorLabel(SELECTOR_NONE)).isEqualTo("no selector")
    }

    @Test
    fun an_id_selector_is_not_called_weak_but_a_structural_one_is() {
        assertThat(recorderSelectorIsWeak(SELECTOR_ID)).isFalse()
        assertThat(recorderSelectorIsWeak(SELECTOR_NAME)).isFalse()
        assertThat(recorderSelectorIsWeak(SELECTOR_STRUCTURAL)).isTrue()
    }

    @Test
    fun a_step_row_carries_its_selector_kind_and_calls_out_a_weak_selector() {
        val line = recorderStepLine(click("#buy"), offsetMs = 0L)
        assertThat(line).isEqualTo("+0.0 s  click  #buy  [id]")
        val weak = recorderStepLine(click("main > form > button:nth-of-type(2)", SELECTOR_STRUCTURAL), 0L)
        assertThat(weak).contains("weak selector")
    }

    @Test
    fun an_offset_is_written_relative_to_the_first_step() {
        assertThat(recorderOffsetText(0L)).isEqualTo("+0.0 s")
        assertThat(recorderOffsetText(1_250L)).isEqualTo("+1.2 s")
    }

    @Test
    fun a_withheld_value_says_so_on_the_row() {
        val line = recorderStepLine(type("#password", null, "value withheld: password field"), 0L)
        assertThat(line).contains("value withheld: password field")
        assertThat(line).contains("not replayable")
    }

    @Test
    fun an_empty_recording_exports_nothing() {
        val export = recorderExport(emptyList(), "Test", "https://example.com")
        assertThat(export).isInstanceOf(RecorderExport.Refused::class.java)
        assertThat((export as RecorderExport.Refused).reasons).hasSize(1)
    }

    @Test
    fun a_click_with_no_selector_is_refused_rather_than_exported() {
        val export = recorderExport(
            listOf(navigate("https://example.com"), click("#a").copy(selector = null, selectorKind = SELECTOR_NONE)),
            "Test",
            "https://example.com"
        )
        // The whole export is refused: a task built from only the steps that
        // happened to be expressible would replay a different interaction.
        assertThat(export).isInstanceOf(RecorderExport.Refused::class.java)
        val refused = export as RecorderExport.Refused
        assertThat(refused.reasons.joinToString("\n")).contains("Step 2")
        assertThat(refused.reasons.joinToString("\n")).contains("no selector")
    }

    @Test
    fun an_action_with_no_agent_step_is_refused_rather_than_dropped() {
        val unsupported = RecorderStep(action = "SCROLL", selectorKind = SELECTOR_ID, selector = "#list")
        val export = recorderExport(listOf(navigate("https://example.com"), unsupported), "Test", null)
        assertThat(export).isInstanceOf(RecorderExport.Refused::class.java)
        assertThat((export as RecorderExport.Refused).reasons.joinToString("\n"))
            .contains("no agent step")
    }

    @Test
    fun a_password_field_is_never_replayed_as_a_filled_value() {
        // Even if a page somehow handed us the value back, the recorder withholds
        // it; an export must not invent one.
        val export = recorderExport(listOf(type("#password", null, "value withheld: password field")), "Test", null)
        assertThat(export).isInstanceOf(RecorderExport.Refused::class.java)
        assertThat((export as RecorderExport.Refused).reasons.joinToString("\n"))
            .contains("the value was not recorded")
    }

    @Test
    fun a_clean_recording_exports_one_agent_step_per_step() {
        val steps = listOf(
            navigate("https://example.com/", at = 1_000L),
            click("#open", at = 2_000L),
            type("#q", "hello", at = 3_000L)
        )
        val export = recorderExport(steps, "Checkout", "https://example.com/")
        assertThat(export).isInstanceOf(RecorderExport.Task::class.java)
        val task = export as RecorderExport.Task
        assertThat(task.name).isEqualTo("Checkout")
        assertThat(task.toolCalls).hasSize(3)
        assertThat(task.toolCalls[0].tool).isEqualTo(AgentTools.NAVIGATE)
        assertThat(task.toolCalls[1].tool).isEqualTo(AgentTools.RUN_JS)
        assertThat(task.toolCalls[1].argsJson).contains("document.querySelector")
        assertThat(task.toolCalls[1].argsJson).contains("#open")
        assertThat(task.prompt).contains("1. Navigate to https://example.com/.")
    }

    @Test
    fun the_exported_task_json_keeps_every_step() {
        val steps = listOf(navigate("https://example.com/", 1_000L), click("#open", at = 2_000L))
        val export = recorderExport(steps, "Test", "https://example.com/") as RecorderExport.Task
        val root = Json.parseToJsonElement(export.json).jsonObject
        assertThat(root["name"]?.jsonPrimitive?.content).isEqualTo("Test")
        assertThat(root["prompt"]?.jsonPrimitive?.content).isEqualTo(export.prompt)
        assertThat(root["steps"]?.jsonArray).hasSize(2)
        assertThat(root["steps"]!!.jsonArray[1].jsonObject["selector"]?.jsonPrimitive?.content)
            .isEqualTo("#open")
    }

    @Test
    fun a_weak_selector_is_carried_into_the_task_as_a_warning_not_dropped() {
        val export = recorderExport(
            listOf(click("main > form > button:nth-of-type(2)", SELECTOR_STRUCTURAL)),
            "Test",
            null
        ) as RecorderExport.Task
        assertThat(export.warnings).hasSize(1)
        assertThat(export.prompt).contains("may not resolve")
    }

    @Test
    fun the_task_name_is_taken_from_where_the_recording_started() {
        assertThat(recorderTaskName("https://example.com/checkout?step=2"))
            .isEqualTo("Recorded session on example.com")
        assertThat(recorderTaskName(null)).isEqualTo(DEFAULT_TASK_NAME)
    }

    @Test
    fun the_page_buffer_decodes_and_a_quoted_result_is_unwrapped_first() {
        val quoted = "\"{\\\"active\\\":true,\\\"dropped\\\":3,\\\"steps\\\":[" +
            "{\\\"action\\\":\\\"CLICK\\\",\\\"selectorKind\\\":\\\"ID\\\",\\\"selector\\\":\\\"#a\\\"}]}\""
        val snapshot = decodeRecorderSnapshot(quoted)
        assertThat(snapshot).isNotNull()
        val decoded = snapshot!!
        assertThat(decoded.active).isTrue()
        assertThat(decoded.dropped).isEqualTo(3)
        assertThat(decoded.steps).hasSize(1)
        assertThat(decoded.steps[0].action).isEqualTo(ACTION_CLICK)
    }

    @Test
    fun an_unanswered_probe_decodes_to_null_rather_than_an_empty_recording() {
        assertThat(decodeRecorderSnapshot("null")).isNull()
        assertThat(decodeRecorderSnapshot("")).isNull()
        assertThat(decodeRecorderSnapshot("undefined")).isNull()
    }

    @Test
    fun the_report_states_its_bound_and_the_selector_kinds_in_use() {
        val steps = listOf(navigate("https://example.com/", 0L), click("#open", at = 500L))
        val report = recorderReport(steps, cap = 500, dropped = 4)
        assertThat(report).contains("Recorder — 2 entries")
        assertThat(report).contains("feed keeps 500")
        assertThat(report).contains("4 older entries were dropped")
        assertThat(recorderSelectorTally(steps)).isEqualTo("1 id")
    }
}
