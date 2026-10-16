@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roombrowser.agent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.roombrowser.domain.plugins.PluginFormValidator
import com.roombrowser.domain.plugins.PluginInputField
import com.roombrowser.domain.plugins.PluginInputsSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The typed editors of one input form — the field-type-to-editor mapping both
 * form dialogs render (the consent dialog's form half and the standalone form
 * dialog), so an `email`, a `choice` and a `secret` are the SAME controls
 * wherever a plugin asks for them.
 *
 * THE DRAFTS ARE THE ONLY STATE THE EDITORS OWN, and they are keyed to the
 * fields and the stored values they were prefilled from: a form the state
 * REPLACED (a newer gate asking) or RETIRED (its 300 s timeout) renders the
 * new fields' prefill, never a stale local copy — the dialogs read the form
 * from state on every recomposition and hand it here, and [remember] resets
 * the drafts the moment the (fields, stored) pair is no longer the one being
 * edited.
 *
 * VALIDATION IS NOT THE EDITORS' BUSINESS. The drafts are mapped to a values
 * object ([pluginFormValuesOf]) and handed to the one validator
 * ([PluginFormValidator]) by the dialogs, which recompute the error map on
 * every recomposition — a keystroke IS a recomposition, so the message under
 * a row and the accept button's enabled state are live by construction, and
 * "an empty error map is the only thing that unlocks" stays true behind
 * every dialog that renders these rows.
 *
 * SECRETS NEVER ECHO. A stored secret prefills BLANK with the
 * "leave blank to keep the saved value" hint, because blank is the answer
 * that keeps the stored one (the validator's one rule that reads what the
 * chat already stored) — anything the editor displayed would be the stored
 * value itself, and write-only is the promise.
 */
@Composable
internal fun rememberPluginFormDrafts(
    fields: List<PluginInputField>,
    existingValues: JsonObject
): SnapshotStateMap<String, Any> = remember(fields, existingValues) {
    val drafts = mutableStateMapOf<String, Any>()
    fields.forEach { field ->
        if (field.type == "boolean") {
            drafts[field.key] = (existingValues[field.key] as? JsonPrimitive)?.booleanOrNull
                ?: (field.default as? JsonPrimitive)?.booleanOrNull
                ?: false
        } else {
            // Pre-fill order: the stored answer, then the declared default,
            // then empty — with the one deliberate exception above, a secret
            // (stored or not) starts blank because blank is what keeps the
            // saved value and the field must never display it back.
            val stored = (existingValues[field.key] as? JsonPrimitive)?.contentOrNull
            val default = (field.default as? JsonPrimitive)?.contentOrNull
            drafts[field.key] = when {
                field.type == "secret" -> ""
                stored != null -> stored
                default != null -> default
                else -> ""
            }
        }
    }
    drafts
}

/**
 * The drafts as the one values shape the validator and the submit read —
 * every field the schema declares, blanks included (a blank is an answer the
 * validator judges: "required" for a missing one, "keep" for a stored
 * secret), booleans as real boolean primitives, choices as the option VALUE
 * the person picked. A field the editor somehow left out is simply absent,
 * which the normalizer answers with the declared default.
 */
internal fun pluginFormValuesOf(
    fields: List<PluginInputField>,
    drafts: Map<String, Any>
): JsonObject = buildJsonObject {
    fields.forEach { field ->
        when (val draft = drafts[field.key]) {
            is Boolean -> put(field.key, draft)
            is String -> put(field.key, draft)
            else -> {}
        }
    }
}

/**
 * The live error map for the drafts — recomputed on every recomposition,
 * because reading the drafts IS the subscription. It is the dialogs' one
 * condition for the accept button: an EMPTY map is the only thing that
 * unlocks it. Pure and cheap, exactly as the single validator is.
 */
internal fun pluginFormErrorsOf(
    fields: List<PluginInputField>,
    drafts: Map<String, Any>,
    existingValues: JsonObject
): Map<String, String> = PluginFormValidator.validate(
    PluginInputsSchema(fields),
    pluginFormValuesOf(fields, drafts),
    existingValues
)

/**
 * The form's rows, in schema order, spaced to read as one form. Each row
 * carries the addressable `plugin_form_field_<key>` description so a person
 * (and a test) can find a field by its key whatever its label says.
 */
@Composable
internal fun PluginFormFields(
    fields: List<PluginInputField>,
    drafts: SnapshotStateMap<String, Any>,
    existingValues: JsonObject,
    errors: Map<String, String>
) {
    fields.forEachIndexed { index, field ->
        if (index > 0) Spacer(Modifier.height(8.dp))
        when (field.type) {
            "boolean" -> BooleanFieldRow(field, drafts, errors[field.key])
            "choice" -> ChoiceFieldRow(field, drafts, errors[field.key])
            else -> TextEntryFieldRow(field, drafts, existingValues, errors[field.key])
        }
    }
}

/**
 * A `string`, `number` or `secret` row: one outlined text field with the
 * label, the placeholder, the inline error and the helper line under it. A
 * number offers the decimal keyboard; a secret masks its input and carries
 * the visibility toggle plus the keep-hint when the chat already stored a
 * value for it.
 */
@Composable
private fun TextEntryFieldRow(
    field: PluginInputField,
    drafts: SnapshotStateMap<String, Any>,
    existingValues: JsonObject,
    error: String?
) {
    val secret = field.type == "secret"
    val stored = (existingValues[field.key] as? JsonPrimitive)?.contentOrNull
    var visible by remember(field.key) { mutableStateOf(false) }
    OutlinedTextField(
        value = drafts[field.key] as? String ?: "",
        onValueChange = { drafts[field.key] = it },
        label = { Text(field.title) },
        placeholder = if (field.placeholder.isNotEmpty()) {
            { Text(field.placeholder) }
        } else {
            null
        },
        isError = error != null,
        singleLine = true,
        visualTransformation = if (secret && !visible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = if (field.type == "number") {
            KeyboardOptions(keyboardType = KeyboardType.Decimal)
        } else {
            KeyboardOptions.Default
        },
        supportingText = {
            when {
                error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                secret && !stored.isNullOrBlank() -> Text("leave blank to keep the saved value")
                field.description.isNotEmpty() -> Text(field.description)
                else -> {}
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "plugin_form_field_${field.key}" }
    )
}

/**
 * A `choice` row: the read-only field the current selection labels, opening
 * the options the schema declared. The option's LABEL is what the person
 * reads; its VALUE is what the draft (and so the submitted object) holds.
 * An option with no label renders as its value, exactly as the model says.
 */
@Composable
private fun ChoiceFieldRow(
    field: PluginInputField,
    drafts: SnapshotStateMap<String, Any>,
    error: String?
) {
    var expanded by remember(field.key) { mutableStateOf(false) }
    val current = drafts[field.key] as? String ?: ""
    val currentLabel = field.options.firstOrNull { it.value == current }
        ?.let { option -> option.label.ifBlank { option.value } }
        ?: current
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = { },
            readOnly = true,
            singleLine = true,
            label = { Text(field.title) },
            isError = error != null,
            trailingIcon = {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            },
            supportingText = {
                when {
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                    field.description.isNotEmpty() -> Text(field.description)
                    else -> {}
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .semantics { contentDescription = "plugin_form_field_${field.key}" }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            field.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label.ifBlank { option.value }) },
                    onClick = {
                        drafts[field.key] = option.value
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * A `boolean` row: the title (with its helper and error lines) beside the
 * switch. The switch state is the draft's boolean, so the submitted object
 * carries a real boolean primitive — never the string a text field would
 * have produced.
 */
@Composable
private fun BooleanFieldRow(
    field: PluginInputField,
    drafts: SnapshotStateMap<String, Any>,
    error: String?
) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "plugin_form_field_${field.key}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(field.title, style = MaterialTheme.typography.bodyMedium)
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (field.description.isNotEmpty()) {
                Text(
                    field.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = drafts[field.key] as? Boolean ?: false,
            onCheckedChange = { checked -> drafts[field.key] = checked }
        )
    }
}
