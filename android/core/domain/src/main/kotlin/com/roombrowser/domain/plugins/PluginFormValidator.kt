package com.roombrowser.domain.plugins

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The single validator every input form answer goes through — the consent
 * dialog at enable time, the edit dialog, the agent tool's missing-inputs
 * gate and the runtime form request all call [validate] with the values in
 * hand, because "is this form complete?" must have exactly one answer in
 * the whole app: a map of per-key errors, and an EMPTY map is the only
 * thing that unlocks the Allow-and-Run button. Anything else — a required
 * field left blank, a pattern that does not match, a number out of range —
 * keeps execution stopped, which is the point of the form.
 *
 * [existing] is what the chat already stored for this plugin's inputs: a
 * blank secret answers "keep the saved value" rather than "empty", so it
 * validates as long as something is stored. That is the only rule that
 * reads it.
 */
object PluginFormValidator {

    /**
     * Every field that is wrong, keyed by the field's key with the exact
     * message its label shows. One error per field — the first thing wrong
     * with a value is the thing to fix — in schema order.
     */
    fun validate(
        schema: PluginInputsSchema,
        values: JsonObject,
        existing: JsonObject = JsonObject(emptyMap())
    ): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        schema.fields.forEach { field ->
            errorFor(field, values[field.key], existing[field.key])?.let { errors[field.key] = it }
        }
        return errors
    }

    /**
     * The values a completed form stores: what the person entered, with a
     * blank secret answered by the value already stored (the form showed
     * dots and "leave blank to keep", so blank means keep), and an
     * unanswered optional field filled from its declared default. Keys the
     * schema does not know are dropped — the stored object is exactly the
     * schema's shape, which is what the wire carries.
     */
    fun normalizedValues(
        schema: PluginInputsSchema,
        values: JsonObject,
        existing: JsonObject = JsonObject(emptyMap())
    ): JsonObject {
        val out = mutableMapOf<String, JsonElement>()
        schema.fields.forEach { field ->
            val entered = values[field.key]
            when {
                entered != null && field.type == "secret" && isBlank(entered) ->
                    storedValue(existing[field.key])?.let { out[field.key] = it }
                entered != null -> out[field.key] = entered
                field.default != null -> out[field.key] = field.default
                else -> {}
            }
        }
        return JsonObject(out)
    }

    /**
     * The message that keeps this field's row red, or null when the answer
     * is acceptable. [value] null means the form left the field out.
     */
    private fun errorFor(field: PluginInputField, value: JsonElement?, existing: JsonElement?): String? {
        val title = field.title.ifBlank { field.key }
        val blank = value == null || isBlank(value)

        // A blank secret is "keep the saved one" — valid whenever there is
        // one — and never a gap to report even when the field is required.
        if (field.type == "secret" && blank && storedValue(existing) != null) return null

        if (field.required && blank) return "$title is required"
        if (blank) return null

        // A value that is not a plain primitive is a broken wire, not an
        // answer — the editors the form builds send strings, numbers and
        // booleans, so anything else is refused rather than stringified.
        if (value !is JsonPrimitive) return "$title has an invalid format"

        return when (field.type) {
            "string", "secret" -> textError(field, title, text(value))
            "number" -> numberError(field, title, text(value))
            "boolean" -> booleanError(title, value)
            "choice" -> choiceError(field, title, text(value))
            else -> null
        }
    }

    /** Length bounds, then the pattern; the first failure is the message. */
    private fun textError(field: PluginInputField, title: String, text: String): String? {
        field.minLength?.let { min ->
            if (text.length < min) return "$title must be at least $min characters"
        }
        field.maxLength?.let { max ->
            if (text.length > max) return "$title must be at most $max characters"
        }
        if (field.pattern.isNotEmpty()) {
            val matched = runCatching { field.pattern.toRegex().matches(text) }.getOrDefault(false)
            if (!matched) {
                return field.patternError.ifBlank { "$title has an invalid format" }
            }
        }
        return null
    }

    /** Parses, refuses NaN and infinities, then checks the bounds. */
    private fun numberError(field: PluginInputField, title: String, text: String): String? {
        val number = text.toDoubleOrNull() ?: return "$title must be a number"
        if (!number.isFinite()) return "$title must be a number"
        field.min?.let { min ->
            if (number < min) return "$title must be at least ${render(min)}"
        }
        field.max?.let { max ->
            if (number > max) return "$title must be at most ${render(max)}"
        }
        return null
    }

    private fun booleanError(title: String, value: JsonElement): String? =
        if ((value as? JsonPrimitive)?.booleanOrNull != null) null else "$title must be true or false"

    private fun choiceError(field: PluginInputField, title: String, text: String): String? {
        if (field.options.any { it.value == text }) return null
        if (field.options.isEmpty()) return "$title must be one of the listed options"
        return "$title must be one of: " + field.options.joinToString(", ") { it.value }
    }

    /** A bound without its trailing ".0", so "1" reads as one and not as "1.0". */
    private fun render(number: Double): String =
        if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()

    /** The element's text, whatever primitive it arrived as. */
    private fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** Whether the element is absent, or a primitive that says nothing. */
    private fun isBlank(value: JsonElement): Boolean =
        (value as? JsonPrimitive)?.contentOrNull?.isBlank() ?: false

    /** The stored value a blank secret keeps, when there is a real one to keep. */
    private fun storedValue(existing: JsonElement?): JsonPrimitive? =
        (existing as? JsonPrimitive)?.takeIf { it.contentOrNull?.isNotBlank() == true }
}
