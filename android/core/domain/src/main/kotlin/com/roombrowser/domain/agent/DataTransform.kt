package com.roombrowser.domain.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The pure data transforms behind `compute_js`'s non-script half: walk JSON,
 * read CSV, aggregate a column, and diff two texts — all on data the agent
 * already holds, with no page and no engine involved.
 *
 * Every function is bounded and total. The result is capped at the caller's
 * `limit`, and a truncated answer says so in its own text rather than being cut
 * silently, so a huge blob cannot be pulled into the model's context. A
 * malformed input is a [Result.Failure] and never an empty success, because a
 * caller that cannot tell "no rows" from "not CSV" will answer the user with
 * the wrong thing.
 *
 * No Android, no engine, no I/O — the whole contract is covered by core tests.
 */
object DataTransform {

    /** What an operation produced: its text, or why the input could not be read. */
    sealed interface Result {
        /** The operation worked. [text] is capped at the caller's limit. */
        data class Success(val text: String, val truncated: Boolean) : Result

        /** The input could not be read, or the operation is not one this offers. */
        data class Failure(val reason: String) : Result
    }

    private const val TRUNCATION_NOTE = "\n…[truncated at "
    private const val TRUNCATION_END = " characters]"

    private val AGGREGATE_OPS = setOf("count", "sum", "min", "max", "avg")

    // ----------------------------------------------------------------- JSON

    /**
     * The value at a dotted [path] (`a.b.0.c`) in [text], rendered as text. A
     * blank path returns the whole document. An object or array is rendered as
     * compact JSON.
     */
    fun jsonPath(text: String, path: String, limit: Int): Result {
        val root = runCatching { AgentJson.parseToJsonElement(text) }.getOrNull()
            ?: return Result.Failure("input is not valid JSON")
        var current: JsonElement = root
        if (path.isNotBlank()) {
            for (segment in path.split('.')) {
                if (segment.isEmpty()) return Result.Failure("path '$path' has an empty segment")
                current = when (val node = current) {
                    is JsonObject -> node[segment]
                        ?: return Result.Failure("path '$path': no key '$segment'")
                    is JsonArray -> {
                        val index = segment.toIntOrNull()
                            ?: return Result.Failure("path '$path': '$segment' is not an array index")
                        node.getOrNull(index)
                            ?: return Result.Failure("path '$path': index $index is out of range")
                    }
                    else -> return Result.Failure("path '$path': cannot descend into ${kind(node)} at '$segment'")
                }
            }
        }
        return cap(render(current), limit)
    }

    private fun render(element: JsonElement): String = when (element) {
        is JsonObject, is JsonArray -> element.toString()
        is JsonPrimitive -> element.contentOrNull ?: "null"
    }

    private fun kind(element: JsonElement): String = when (element) {
        is JsonObject -> "an object"
        is JsonArray -> "an array"
        is JsonPrimitive -> "a value"
    }

    // ------------------------------------------------------------------ CSV

    /**
     * [text] parsed as CSV, one row per line, its fields joined by " | ".
     * Quoted fields may contain commas, quotes (`""`) and newlines.
     */
    fun csvRows(text: String, limit: Int): Result {
        val rows = parseCsv(text)
            ?: return Result.Failure("input is not valid CSV (an unterminated quoted field)")
        if (rows.isEmpty()) return Result.Failure("input has no rows")
        return cap(rows.joinToString("\n") { row -> row.joinToString(" | ") }, limit)
    }

    /**
     * `count`, `sum`, `min`, `max` or `avg` over one column of numbers, where
     * [text] is CSV-ish lines or one number per line. [column] is a zero-based
     * index and defaults to the first column. A column that does not exist in
     * every row, or a value that is not a number, is a failure — never a
     * quietly smaller sum.
     */
    fun aggregate(text: String, column: Int?, op: String): Result {
        val operation = op.trim().lowercase()
        if (operation !in AGGREGATE_OPS) {
            return Result.Failure("unsupported operation '${op.trim()}': use count, sum, min, max or avg")
        }
        val rows = parseCsv(text)
            ?: return Result.Failure("input is not valid CSV (an unterminated quoted field)")
        if (rows.isEmpty()) return Result.Failure("input has no rows to aggregate")
        val col = column ?: 0
        if (col < 0) return Result.Failure("column must not be negative")
        rows.forEachIndexed { index, row ->
            if (col >= row.size) {
                return Result.Failure(
                    "column $col does not exist in row ${index + 1} (that row has ${row.size} field(s))"
                )
            }
        }
        val cells = rows.map { it[col] }
        if (operation == "count") {
            return Result.Success("count of column $col = ${cells.count { it.isNotBlank() }}", truncated = false)
        }
        val numbers = ArrayList<Double>(cells.size)
        cells.forEachIndexed { index, cell ->
            val value = cell.trim().toDoubleOrNull()
                ?: return Result.Failure("'${cell.take(40)}' in column $col (row ${index + 1}) is not a number")
            numbers.add(value)
        }
        if (numbers.isEmpty()) return Result.Failure("column $col has no values")
        val value = when (operation) {
            "sum" -> numbers.sum()
            "min" -> numbers.min()
            "max" -> numbers.max()
            "avg" -> numbers.sum() / numbers.size
            else -> return Result.Failure("unsupported operation '$operation'")
        }
        return Result.Success(
            "$operation of column $col = ${formatNumber(value)} (over ${numbers.size} value(s))",
            truncated = false
        )
    }

    /**
     * A line diff of [a] and [b]: "  " for a line both share, "- " for one only
     * in [a], "+ " for one only in [b]. Not a minimal diff — it trims the common
     * prefix and suffix and marks the middle — which keeps it linear and total.
     */
    fun diff(a: String, b: String, limit: Int): Result {
        val left = toLines(a)
        val right = toLines(b)
        var start = 0
        while (start < left.size && start < right.size && left[start] == right[start]) start++
        var endLeft = left.size
        var endRight = right.size
        while (endLeft > start && endRight > start && left[endLeft - 1] == right[endRight - 1]) {
            endLeft--
            endRight--
        }
        val out = StringBuilder()
        for (i in 0 until start) out.append("  ").append(left[i]).append('\n')
        for (i in start until endLeft) out.append("- ").append(left[i]).append('\n')
        for (i in start until endRight) out.append("+ ").append(right[i]).append('\n')
        for (i in endLeft until left.size) out.append("  ").append(left[i]).append('\n')
        return cap(out.toString().trimEnd('\n'), limit)
    }

    private fun toLines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.lines()

    // ------------------------------------------------------------- internals

    private fun cap(text: String, limit: Int): Result {
        val max = limit.coerceAtLeast(0)
        if (text.length <= max) return Result.Success(text, truncated = false)
        return Result.Success(text.take(max) + TRUNCATION_NOTE + max + TRUNCATION_END, truncated = true)
    }

    private fun formatNumber(value: Double): String =
        if (value.isFinite() && value == Math.floor(value) && Math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    /**
     * The CSV state machine. Returns null for an unterminated quoted field,
     * the one input it cannot read; blank lines are skipped and `""` is a real
     * empty field, so the two are not confused.
     */
    private fun parseCsv(text: String): List<List<String>>? {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var rowTouched = false
        var i = 0

        fun endField() {
            row.add(field.toString())
            field.clear()
        }

        fun endRow() {
            endField()
            if (rowTouched || row.size > 1 || row[0].isNotEmpty()) rows.add(row)
            row = mutableListOf()
            rowTouched = false
        }

        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    '"' -> {
                        inQuotes = true
                        rowTouched = true
                    }
                    ',' -> {
                        endField()
                        rowTouched = true
                    }
                    '\r' -> Unit
                    '\n' -> endRow()
                    else -> {
                        field.append(c)
                        rowTouched = true
                    }
                }
            }
            i++
        }
        if (inQuotes) return null
        if (rowTouched || field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
