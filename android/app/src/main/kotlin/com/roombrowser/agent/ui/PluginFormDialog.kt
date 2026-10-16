package com.roombrowser.agent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roombrowser.agent.PendingPluginForm
import com.roombrowser.agent.PluginFormContext
import kotlinx.serialization.json.JsonObject

/**
 * The input form a gate raised — ONE dialog for all three contexts, because
 * the editors, the validator and the "an empty error map is the only thing
 * that unlocks" rule are the same whether the form stands between a consent
 * and an activate ([PluginFormContext.ACTIVATION]), behind a tool the model
 * just called ([PluginFormContext.TOOL_CALL]), or in the middle of a run the
 * plugin itself paused to ask ([PluginFormContext.RUNTIME_REQUEST]). Only
 * the chrome differs: who is asking, in the title; how sensitive, in the
 * footer.
 *
 * THE FORM IS READ FROM STATE ON EVERY RECOMPOSITION — never a local copy.
 * [PendingPluginForm] may be REPLACED by a newer gate (the old raiser is
 * cancelled; the new fields render with their own prefill) or RETIRED by its
 * 300 s timeout mid-composition; both are ordinary state transitions here,
 * the drafts being keyed to the (fields, stored) pair they were prefilled
 * from (see [rememberPluginFormDrafts]).
 *
 * DISMISSING IS NEVER ANSWERING. Cancel and the dialog's own dismiss request
 * both call [onCancel] — the raiser is a suspended coroutine waiting on the
 * completion, and dropping the state without an answer would wedge it until
 * its timeout; the runtime's JS reads "Form was cancelled", the tool path
 * reads the instructive refusal, the activation path reads its notice.
 *
 * The accept button is disabled while any required input is missing or
 * invalid — the same live re-validation, against the same single validator,
 * that the consent dialog's form half runs; the state's own check is the
 * backstop that makes an invalid submit a no-op rather than a stored set.
 */
@Composable
fun PluginFormDialog(
    form: PendingPluginForm,
    isEdit: Boolean,
    onSubmit: (values: JsonObject) -> Unit,
    onCancel: () -> Unit
) {
    val drafts = rememberPluginFormDrafts(form.fields, form.existingValues)
    val values = pluginFormValuesOf(form.fields, drafts)
    val errors = pluginFormErrorsOf(form.fields, drafts, form.existingValues)
    val runtime = form.context == PluginFormContext.RUNTIME_REQUEST
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                when {
                    // The sheet's "Edit inputs" pencil: the stored answers as
                    // the starting state, and a button that says what an edit
                    // of an enabled module does — runs again with them.
                    isEdit -> "Edit inputs for \"${form.pluginName}\""
                    // The plugin's own headline for its mid-run question.
                    runtime -> form.title?.takeIf { it.isNotBlank() } ?: form.pluginName
                    form.context == PluginFormContext.TOOL_CALL ->
                        "\"${form.pluginName}\" was called and needs its inputs"
                    else -> "\"${form.pluginName}\" is waiting for its inputs"
                }
            )
        },
        text = {
            // The same cap and the same reason as the consent dialog's body.
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // The runtime request's own helper line, under the title
                // where the plugin's code asked it to be.
                if (runtime && !form.description.isNullOrBlank()) {
                    Text(
                        form.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                PluginFormFields(
                    fields = form.fields,
                    drafts = drafts,
                    existingValues = form.existingValues,
                    errors = errors
                )
                // The runtime request's sensitivity footer — the one line the
                // design pins for this context — with the network context
                // under it whenever the plugin holds an allowlist, so the
                // person sees what the answer unlocks at approval time. The
                // hosts line shows for the app-raised contexts too: the data
                // rides on the form for exactly that.
                if (runtime) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${form.pluginName} is asking for this information — it runs only in " +
                            "this chat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (form.networkHosts.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "It can reach the internet only at: " + form.networkHosts.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(values) },
                enabled = errors.isEmpty(),
                modifier = Modifier.semantics { contentDescription = "plugin_form_accept" }
            ) {
                Text(if (isEdit) "Save and run" else "Allow and Run")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}
