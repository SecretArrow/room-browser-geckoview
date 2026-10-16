package com.roombrowser.domain.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One option of a `choice` input: the value that is stored when picked, and
 * the label the form shows. An option with no label renders as its value.
 */
data class PluginInputOption(
    val value: String,
    val label: String
)

/**
 * One field of a manifest's `inputs` array — the interactive form a plugin
 * (or a ZAgents module) can ask its chatter to fill before it is allowed to
 * run. The model carries only what survived [PluginInputsSchema.validate]:
 * a form a person is shown is a form this build fully understands.
 *
 * [type] is the wire spelling (`string` | `number` | `boolean` | `choice` |
 * `secret`); the numeric bounds and the text bounds are nullable because
 * absence is meaningful (no bound), the string-shaped members are `""`
 * because that is absence for them, and [default] stays the JsonElement it
 * arrived as so the stored value and the wire never disagree about a
 * number's own spelling.
 */
data class PluginInputField(
    val key: String,
    val type: String,
    val title: String,
    val description: String,
    val required: Boolean,
    val default: JsonElement?,
    val placeholder: String,
    val pattern: String,
    val patternError: String,
    val minLength: Int?,
    val maxLength: Int?,
    val min: Double?,
    val max: Double?,
    val options: List<PluginInputOption>
)

/**
 * The `inputs` schema a manifest declares: the list of fields its input
 * form is built from, at most [MAX_FIELDS] of them. Both manifest formats
 * may declare one; the dialog, the tool gate and the runtime form request
 * all validate through the same [PluginFormValidator] so "valid" means one
 * thing everywhere.
 */
data class PluginInputsSchema(val fields: List<PluginInputField>) {

    fun fieldOf(key: String): PluginInputField? = fields.firstOrNull { it.key == key }

    /** The schema as the JSON array the row's `inputs_json` column stores. */
    fun toJson(): JsonArray = JsonArray(fields.map { it.toJson() })

    /**
     * The schema as the row stores it, or null when it declares no fields —
     * a manifest without `inputs` and a manifest with an empty one are the
     * same thing to the database: no form.
     */
    fun toJsonOrNull(): String? = fields.takeIf { it.isNotEmpty() }?.let { toJson().toString() }

    companion object {

        const val MAX_FIELDS = 12
        const val MAX_OPTIONS = 12

        /** The five field types, in the order the reference table lists them. */
        val TYPES = setOf("string", "number", "boolean", "choice", "secret")

        private val KEY = Regex("^[a-z][a-z0-9_]{0,31}$")

        /** The body of a `{n}`, `{n,}` or `{n,m}` quantifier — and nothing else's. */
        private val BOUNDS = Regex("\\d+,?\\d*|\\d*,\\d+")

        private val TEXT_TYPES = setOf("string", "secret")
        private val PLACEHOLDER_TYPES = setOf("string", "number", "secret")

        private const val TITLE_CHARS = 64
        private const val DESCRIPTION_CHARS = 200
        private const val PLACEHOLDER_CHARS = 100
        private const val PATTERN_CHARS = 200
        private const val PATTERN_ERROR_CHARS = 200
        private const val OPTION_VALUE_CHARS = 64
        private const val OPTION_LABEL_CHARS = 64

        /**
         * Every member a field object may carry. Unknown members are refused
         * in BOTH formats — the strictness a plugin.json author already
         * expects, applied to the one schema whose whole job is to describe
         * a dialog this build will render.
         */
        private val KNOWN_FIELDS = setOf(
            "key", "type", "title", "description", "required", "default",
            "placeholder", "pattern", "patternError", "minLength", "maxLength",
            "min", "max", "options"
        )

        /**
         * The schema an `inputs` element holds — tolerant on purpose, the
         * same split the manifest itself uses: wrong shapes read as absent,
         * because the strictness (and its messages) live in [validate].
         * Absent, a non-array, or an empty array all read as no schema.
         */
        fun parse(element: JsonElement?): PluginInputsSchema {
            val array = element as? JsonArray ?: return PluginInputsSchema(emptyList())
            return PluginInputsSchema(
                array.mapNotNull { item ->
                    val field = item as? JsonObject ?: return@mapNotNull null
                    PluginInputField(
                        key = field.string("key").orEmpty(),
                        type = field.string("type") ?: "string",
                        title = field.string("title").orEmpty(),
                        description = field.string("description").orEmpty(),
                        required = (field["required"] as? JsonPrimitive)?.booleanOrNull ?: false,
                        default = field["default"] as? JsonPrimitive,
                        placeholder = field.string("placeholder").orEmpty(),
                        pattern = field.string("pattern").orEmpty(),
                        patternError = field.string("patternError").orEmpty(),
                        minLength = (field["minLength"] as? JsonPrimitive)?.intOrNull,
                        maxLength = (field["maxLength"] as? JsonPrimitive)?.intOrNull,
                        min = (field["min"] as? JsonPrimitive)?.doubleOrNull,
                        max = (field["max"] as? JsonPrimitive)?.doubleOrNull,
                        options = parseOptions(field["options"])
                    )
                }
            )
        }

        /**
         * Every rule an `inputs` array must pass, or an empty list when it
         * is a schema this build can render. The refusals name the field
         * they refused, so a manifest that breaks three rules is fixed in
         * one pass. Unknown members inside a field are errors, exactly as
         * unknown top-level manifest fields are.
         */
        fun validate(element: JsonElement?): List<String> {
            if (element == null) return emptyList()
            val array = element as? JsonArray
                ?: return listOf("inputs must be an array of field objects")
            val errors = mutableListOf<String>()
            if (array.size > MAX_FIELDS) {
                errors.add("inputs lists ${array.size} fields; at most $MAX_FIELDS are allowed")
            }
            val seenKeys = mutableSetOf<String>()
            array.forEachIndexed { index, item ->
                val field = item as? JsonObject
                if (field == null) {
                    errors.add("inputs entry ${index + 1} is not an object")
                    return@forEachIndexed
                }
                val key = field.string("key").orEmpty()
                val who = if (key.isBlank()) "entry ${index + 1}" else "key \"$key\""
                if (!KEY.matches(key)) {
                    errors.add("inputs $who has a key that is not a lowercase identifier (a..z, 0..9, _, at most 32 chars)")
                }
                if (!seenKeys.add(key)) {
                    errors.add("inputs repeats the key \"$key\"")
                }
                val type = field.string("type").orEmpty()
                if (type !in TYPES) {
                    errors.add("inputs $who has type \"$type\"; one of ${TYPES.sorted()} is required")
                }
                val title = field.string("title").orEmpty()
                if (title.isEmpty() || title.length > TITLE_CHARS) {
                    errors.add("inputs $who must have a title of 1..$TITLE_CHARS characters")
                }
                if (field.string("description").orEmpty().length > DESCRIPTION_CHARS) {
                    errors.add("inputs $who has a description longer than $DESCRIPTION_CHARS characters")
                }
                (field["required"] as? JsonPrimitive)?.takeIf { it.booleanOrNull == null }?.let {
                    errors.add("inputs $who has a required that is not a boolean")
                }

                // Options first: the default of a choice is checked against
                // the list, so the list is read before it is used.
                val options = (field["options"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    .orEmpty()
                if (field["options"] != null && field["options"] !is JsonArray) {
                    errors.add("inputs $who has options that is not an array")
                }
                if (field["options"] != null && type != "choice") {
                    errors.add("inputs $who cannot have options; only choice fields can")
                }
                if (type == "choice") {
                    if (options.isEmpty()) {
                        errors.add("inputs $who must list between 1 and $MAX_OPTIONS options")
                    } else if (options.size > MAX_OPTIONS) {
                        errors.add("inputs $who lists ${options.size} options; at most $MAX_OPTIONS are allowed")
                    }
                }
                val optionValues = mutableSetOf<String>()
                options.forEach { option ->
                    val value = option.string("value").orEmpty()
                    if (value.isEmpty() || value.length > OPTION_VALUE_CHARS) {
                        errors.add("inputs $who has an option value that is not 1..$OPTION_VALUE_CHARS characters")
                    }
                    if (!optionValues.add(value)) {
                        errors.add("inputs $who repeats the option value \"$value\"")
                    }
                    if (option.string("label").orEmpty().length > OPTION_LABEL_CHARS) {
                        errors.add("inputs $who has an option label longer than $OPTION_LABEL_CHARS characters")
                    }
                }

                field["default"]?.let { default ->
                    val primitive = default as? JsonPrimitive
                    when {
                        primitive == null ->
                            errors.add("inputs $who has a default that is not a plain value")
                        type == "choice" && primitive.contentOrNull !in optionValues ->
                            errors.add("inputs $who has a default that is not one of the listed options")
                        type != "choice" && !defaultMatches(type, primitive) ->
                            errors.add("inputs $who has a default that is not a $type")
                        else -> {}
                    }
                }

                val placeholder = field.string("placeholder").orEmpty()
                if (placeholder.isNotEmpty() && type !in PLACEHOLDER_TYPES) {
                    errors.add("inputs $who cannot have a placeholder; only string, number and secret fields can")
                }
                if (placeholder.length > PLACEHOLDER_CHARS) {
                    errors.add("inputs $who has a placeholder longer than $PLACEHOLDER_CHARS characters")
                }

                val pattern = field.string("pattern").orEmpty()
                if (pattern.isNotEmpty() && type !in TEXT_TYPES) {
                    errors.add("inputs $who cannot have a pattern; only string and secret fields can")
                }
                if (pattern.length > PATTERN_CHARS) {
                    errors.add("inputs $who has a pattern longer than $PATTERN_CHARS characters")
                }
                if (pattern.isNotEmpty() && type in TEXT_TYPES) {
                    patternProblem(pattern)?.let { why -> errors.add("inputs $who has a pattern $why") }
                }
                if (field.string("patternError").orEmpty().length > PATTERN_ERROR_CHARS) {
                    errors.add("inputs $who has a patternError longer than $PATTERN_ERROR_CHARS characters")
                }

                (field["minLength"] as? JsonPrimitive)
                    ?.takeIf { it.isString || it.intOrNull == null }?.let {
                    errors.add("inputs $who has a minLength that is not a plain integer")
                }
                (field["maxLength"] as? JsonPrimitive)
                    ?.takeIf { it.isString || it.intOrNull == null }?.let {
                    errors.add("inputs $who has a maxLength that is not a plain integer")
                }
                field["minLength"]?.takeIf { type !in TEXT_TYPES }?.let {
                    errors.add("inputs $who cannot have a minLength; only string and secret fields can")
                }
                field["maxLength"]?.takeIf { type !in TEXT_TYPES }?.let {
                    errors.add("inputs $who cannot have a maxLength; only string and secret fields can")
                }
                (field["minLength"] as? JsonPrimitive)?.intOrNull?.takeIf { it < 0 }?.let {
                    errors.add("inputs $who has a negative minLength")
                }
                (field["maxLength"] as? JsonPrimitive)?.intOrNull?.takeIf { it < 0 }?.let {
                    errors.add("inputs $who has a negative maxLength")
                }
                val minLength = (field["minLength"] as? JsonPrimitive)?.intOrNull
                val maxLength = (field["maxLength"] as? JsonPrimitive)?.intOrNull
                if (minLength != null && maxLength != null && minLength > maxLength) {
                    errors.add("inputs $who has a minLength greater than its maxLength")
                }

                (field["min"] as? JsonPrimitive)
                    ?.takeIf { it.isString || it.doubleOrNull == null }?.let {
                    errors.add("inputs $who has a min that is not a number")
                }
                (field["max"] as? JsonPrimitive)
                    ?.takeIf { it.isString || it.doubleOrNull == null }?.let {
                    errors.add("inputs $who has a max that is not a number")
                }
                field["min"]?.takeIf { type != "number" }?.let {
                    errors.add("inputs $who cannot have a min; only number fields can")
                }
                field["max"]?.takeIf { type != "number" }?.let {
                    errors.add("inputs $who cannot have a max; only number fields can")
                }
                val min = (field["min"] as? JsonPrimitive)?.doubleOrNull
                val max = (field["max"] as? JsonPrimitive)?.doubleOrNull
                if (min != null && max != null && min > max) {
                    errors.add("inputs $who has a min greater than its max")
                }

                field.keys.forEach { name ->
                    if (name !in KNOWN_FIELDS) {
                        errors.add(
                            "inputs $who has an unknown field \"$name\": " +
                                "this API version does not read it, so it is refused rather than ignored"
                        )
                    }
                }
            }
            return errors
        }

        /**
         * Why [pattern] may not run against a person's input, or null when
         * it may. Two gates: it must compile at all, and it must not nest a
         * quantifier inside a quantified group — the shape that makes a
         * backtracking engine take geological time on hostile input. The
         * second check is deliberately conservative (it refuses some
         * harmless patterns along with the harmful ones) because a form
         * that never finishes validating is worse than a pattern an author
         * has to simplify.
         */
        fun patternProblem(pattern: String): String? = try {
            Regex(pattern)
            if (hasNestedQuantifier(pattern)) "that nests quantifiers, which this app refuses to run" else null
        } catch (err: Exception) {
            "that is not a valid regular expression"
        }

        /**
         * Whether a quantifier (`*`, `+`, `?`, `{n,m}`) is applied to a
         * group that itself contains one. The scan skips escaped characters
         * and character classes (a `+` inside `[...]` is a literal), treats
         * `(?:`/lookaround markers as part of the group's opening rather
         * than a quantifier of nothing, and stops at the first closing
         * bracket whose group both contains a quantifier and carries one —
         * malformed patterns are the compiler's problem, not this check's.
         */
        private fun hasNestedQuantifier(pattern: String): Boolean {
            val bodies = ArrayDeque<Boolean>()
            var quantifiable = false
            var i = 0
            while (i < pattern.length) {
                when (val c = pattern[i]) {
                    '\\' -> {
                        i += 2
                        quantifiable = true
                    }
                    '[' -> {
                        var j = i + 1
                        if (j < pattern.length && pattern[j] == '^') j++
                        if (j < pattern.length && pattern[j] == ']') j++
                        while (j < pattern.length && pattern[j] != ']') {
                            if (pattern[j] == '\\') j++
                            j++
                        }
                        i = j + 1
                        quantifiable = true
                    }
                    '(' -> {
                        bodies.addLast(false)
                        i++
                        if (i < pattern.length && pattern[i] == '?') {
                            // A group flag — non-capturing, lookaround — not
                            // a quantifier, and never one.
                            i++
                            if (i < pattern.length && pattern[i] in "=!<>:") i++
                            quantifiable = false
                        } else {
                            quantifiable = false
                        }
                    }
                    ')' -> {
                        if (bodies.isEmpty()) return false
                        val bodyHasQuantifier = bodies.removeLast()
                        if (bodyHasQuantifier && i + 1 < pattern.length &&
                            isQuantifier(pattern, i + 1)
                        ) {
                            return true
                        }
                        i++
                        quantifiable = true
                    }
                    '*', '+', '?' -> {
                        if (quantifiable && bodies.isNotEmpty()) bodies[bodies.lastIndex] = true
                        quantifiable = false
                        i++
                    }
                    '{' -> {
                        val close = pattern.indexOf('}', i)
                        val body = if (close > i) pattern.substring(i + 1, close) else ""
                        if (quantifiable && close > i && body.matches(BOUNDS)) {
                            if (bodies.isNotEmpty()) bodies[bodies.lastIndex] = true
                            quantifiable = false
                            i = close + 1
                        } else {
                            i++
                            quantifiable = true
                        }
                    }
                    else -> {
                        i++
                        quantifiable = true
                    }
                }
            }
            return false
        }

        /** Whether [index] starts a quantifier (a lazy/possessive marker after it is part of it). */
        private fun isQuantifier(pattern: String, index: Int): Boolean = when (pattern[index]) {
            '*', '+' -> true
            '?' -> true
            '{' -> {
                val close = pattern.indexOf('}', index)
                val body = if (close > index) pattern.substring(index + 1, close) else ""
                close > index && body.matches(BOUNDS)
            }
            else -> false
        }

        /** Whether [value] is a plain [type] — a field's default must match its own type. */
        private fun defaultMatches(type: String, value: JsonPrimitive): Boolean = when (type) {
            "string", "secret" -> value.isString
            "number" -> !value.isString && value.contentOrNull?.toDoubleOrNull() != null
            "boolean" -> !value.isString && (value.contentOrNull == "true" || value.contentOrNull == "false")
            else -> true
        }

        private fun parseOptions(element: JsonElement?): List<PluginInputOption> {
            val array = element as? JsonArray ?: return emptyList()
            return array.mapNotNull { item ->
                val option = item as? JsonObject ?: return@mapNotNull null
                PluginInputOption(
                    value = option.string("value").orEmpty(),
                    label = option.string("label").orEmpty()
                )
            }
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull
    }
}

/**
 * One field as its JSON object — the spelling the stored row and the wire
 * share, so what the runtime reads back is what the validator read.
 */
fun PluginInputField.toJson(): JsonObject = JsonObject(
    buildMap {
        put("key", JsonPrimitive(key))
        put("type", JsonPrimitive(type))
        put("title", JsonPrimitive(title))
        put("description", JsonPrimitive(description))
        put("required", JsonPrimitive(required))
        default?.let { put("default", it) }
        if (placeholder.isNotEmpty()) put("placeholder", JsonPrimitive(placeholder))
        if (pattern.isNotEmpty()) put("pattern", JsonPrimitive(pattern))
        if (patternError.isNotEmpty()) put("patternError", JsonPrimitive(patternError))
        minLength?.let { put("minLength", JsonPrimitive(it)) }
        maxLength?.let { put("maxLength", JsonPrimitive(it)) }
        min?.let { put("min", JsonPrimitive(it)) }
        max?.let { put("max", JsonPrimitive(it)) }
        if (options.isNotEmpty()) put("options", JsonArray(options.map { it.toJson() }))
    }
)

/** One option as its JSON object; see [PluginInputField.toJson]. */
fun PluginInputOption.toJson(): JsonObject = JsonObject(
    buildMap {
        put("value", JsonPrimitive(value))
        put("label", JsonPrimitive(label))
    }
)
