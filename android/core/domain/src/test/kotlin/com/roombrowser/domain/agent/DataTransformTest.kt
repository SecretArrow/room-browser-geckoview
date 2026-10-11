package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The pure transforms behind `compute_js`.
 *
 * The negative controls matter more than the happy paths: a malformed CSV or
 * JSON, or a column that does not exist, must come back as a [DataTransform.Result.Failure]
 * rather than an empty success — a caller that cannot tell "nothing there" from
 * "could not read it" answers the user with the wrong thing. Truncation is the
 * other half: a capped answer has to say so.
 *
 * Every test ends on a VOID-returning Truth call (isEqualTo / contains /
 * isInstanceOf / isTrue), never on an `Ordered`-returning one, because a
 * non-void test method makes JUnit4 refuse the whole class.
 */
class DataTransformTest {

    private fun success(result: DataTransform.Result): DataTransform.Result.Success =
        result as? DataTransform.Result.Success ?: error("expected success, got $result")

    private fun failure(result: DataTransform.Result): DataTransform.Result.Failure =
        result as? DataTransform.Result.Failure ?: error("expected failure, got $result")

    // ----------------------------------------------------------------- JSON

    @Test
    fun json_path_walks_objects_and_array_indices() {
        val doc = """{"a":{"b":[10,20,{"c":"hi"}]}}"""
        assertThat(success(DataTransform.jsonPath(doc, "a.b.1", 100)).text).isEqualTo("20")
        assertThat(success(DataTransform.jsonPath(doc, "a.b.2.c", 100)).text).isEqualTo("hi")
    }

    @Test
    fun json_path_renders_containers_and_a_blank_path_reads_the_whole_document() {
        val doc = """{"a":{"b":[10,20]}}"""
        assertThat(success(DataTransform.jsonPath(doc, "a.b", 100)).text).isEqualTo("[10,20]")
        assertThat(success(DataTransform.jsonPath(doc, "", 100)).text).isEqualTo(doc)
    }

    @Test
    fun json_path_refuses_malformed_json_a_missing_key_and_a_bad_index() {
        // The parser is lenient, so the malformed input is unbalanced rather
        // than a bare word the lenient reader would accept as a string.
        assertThat(DataTransform.jsonPath("{invalid", "a", 100))
            .isInstanceOf(DataTransform.Result.Failure::class.java)
        assertThat(DataTransform.jsonPath("""{"a":1}""", "b", 100))
            .isInstanceOf(DataTransform.Result.Failure::class.java)
        assertThat(DataTransform.jsonPath("""{"a":[1]}""", "a.5", 100))
            .isInstanceOf(DataTransform.Result.Failure::class.java)
        assertThat(failure(DataTransform.jsonPath("""{"a":1}""", "b", 100)).reason).contains("no key")
    }

    // ------------------------------------------------------------------ CSV

    @Test
    fun csv_rows_honour_quoted_commas_and_embedded_newlines() {
        val csv = "name,note\n\"a,b\",\"line1\nline2\""
        val text = success(DataTransform.csvRows(csv, 500)).text
        assertThat(text).contains("name | note")
        assertThat(text).contains("a,b | line1\nline2")
    }

    @Test
    fun csv_rows_keep_an_empty_quoted_field_but_skip_blank_lines() {
        val text = success(DataTransform.csvRows("a,\"\"\n\nb,c", 500)).text
        assertThat(text).isEqualTo("a | \nb | c")
    }

    @Test
    fun csv_rows_refuse_an_unterminated_quote_and_an_empty_input() {
        assertThat(DataTransform.csvRows("a,\"unterminated", 500))
            .isInstanceOf(DataTransform.Result.Failure::class.java)
        assertThat(DataTransform.csvRows("", 500))
            .isInstanceOf(DataTransform.Result.Failure::class.java)
    }

    // ------------------------------------------------------------- aggregate

    @Test
    fun aggregate_sums_averages_and_counts_one_number_per_line() {
        val numbers = "1\n2\n3\n4"
        assertThat(success(DataTransform.aggregate(numbers, null, "sum")).text).contains("= 10")
        assertThat(success(DataTransform.aggregate(numbers, null, "avg")).text).contains("= 2.5")
        assertThat(success(DataTransform.aggregate(numbers, null, "min")).text).contains("= 1")
        assertThat(success(DataTransform.aggregate(numbers, null, "max")).text).contains("= 4")
        assertThat(success(DataTransform.aggregate(numbers, null, "count")).text).contains("= 4")
    }

    @Test
    fun aggregate_reads_a_named_column_out_of_csv() {
        val csv = "a,1\nb,2\nc,3"
        assertThat(success(DataTransform.aggregate(csv, 1, "sum")).text).contains("= 6")
        assertThat(success(DataTransform.aggregate(csv, 1, "count")).text).contains("= 3")
    }

    @Test
    fun aggregate_refuses_a_column_that_does_not_exist() {
        assertThat(DataTransform.aggregate("1,2\n3,4", 5, "sum"))
            .isInstanceOf(DataTransform.Result.Failure::class.java)
        assertThat(failure(DataTransform.aggregate("1,2\n3,4", 5, "sum")).reason).contains("does not exist")
    }

    @Test
    fun aggregate_refuses_a_non_numeric_value_an_unknown_op_and_malformed_csv() {
        assertThat(DataTransform.aggregate("1\nx", null, "sum"))
            .isInstanceOf(DataTransform.Result.Failure::class.java)
        assertThat(DataTransform.aggregate("1\n2", null, "median"))
            .isInstanceOf(DataTransform.Result.Failure::class.java)
        assertThat(DataTransform.aggregate("1,\"oops", null, "sum"))
            .isInstanceOf(DataTransform.Result.Failure::class.java)
    }

    // -------------------------------------------------------------- truncation

    @Test
    fun truncation_is_reported_not_silent() {
        val long = "a".repeat(5000)
        val cut = success(DataTransform.csvRows(long, 10))
        assertThat(cut.truncated).isTrue()
        assertThat(cut.text).contains("[truncated at 10 characters]")
        assertThat(cut.text.length).isEqualTo(10 + "\n…[truncated at 10 characters]".length)
    }

    @Test
    fun a_short_answer_is_not_marked_truncated() {
        assertThat(success(DataTransform.csvRows("a,b", 100)).truncated).isFalse()
        assertThat(success(DataTransform.diff("x", "y", 100)).truncated).isFalse()
    }

    // ------------------------------------------------------------------ diff

    @Test
    fun diff_marks_unchanged_removed_and_added_lines() {
        val text = success(DataTransform.diff("one\ntwo\nthree", "one\nTWO\nthree", 500)).text
        assertThat(text).contains("  one")
        assertThat(text).contains("- two")
        assertThat(text).contains("+ TWO")
        assertThat(text).contains("  three")
    }

    @Test
    fun diff_reports_truncation_for_a_large_change() {
        val big = (1..500).joinToString("\n") { "line $it" }
        val cut = success(DataTransform.diff("", big, 50))
        assertThat(cut.truncated).isTrue()
        assertThat(cut.text).contains("[truncated at 50 characters]")
    }
}
