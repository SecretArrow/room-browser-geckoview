package com.roombrowser.agent

import com.roombrowser.domain.agent.AgentAppActions
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.domain.agent.AgentTools
import com.roombrowser.domain.agent.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The two tools that reach the profile's OWN content — the generated
 * authenticator codes and the notes — shared by every executor that can offer
 * them.
 *
 * It is one class rather than a branch in each executor because the same model
 * reads the same wording wherever a turn runs, and because the load-bearing
 * rule here ("the digits reach the model only when the setting says so") would
 * otherwise exist twice and drift.
 *
 * WHAT IT CAN NEVER DO: reach a seed, or a saved password, or a wallet key.
 * [AgentProfileData] hands out labels and generated digits, and there is no
 * action that reveals anything else — see [AgentAppActions.TOTP_ACTIONS].
 *
 * Gating is the CALLER's job: an interactive turn has already run the confirm
 * and destructive gates in `AgentAppTools.guard`, and the unattended gates
 * refuse a destructive action before they ever get here (see
 * [AgentAppActions.unattendedAllowsProfileAction]). This class assumes the
 * action was allowed and only carries it out.
 */
class AgentProfileTools(
    private val data: AgentProfileData,
    /** Whether the digits themselves may be returned to the model. */
    private val otpDigitsAllowed: suspend () -> Boolean,
    /**
     * Types text into a page field by `[ref]`. Null when this run has no page it
     * may type into, and `action=fill` then refuses rather than reporting a fill
     * that never happened.
     */
    private val fillField: (suspend (ref: Int, text: String) -> ToolResult)? = null
) {

    suspend fun execute(name: String, argsJson: String): ToolResult {
        val args = parseArgs(argsJson)
        return try {
            if (name == AgentTools.APP_2FA) twoFactor(args) else notes(args)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            ToolResult(false, t.message ?: t.javaClass.simpleName)
        }
    }

    // ---------------------------------------------------------- the codes

    private suspend fun twoFactor(args: JsonObject): ToolResult {
        val action = str(args, "action") ?: "list"
        if (action !in AgentAppActions.TOTP_ACTIONS) return unknownAction("2FA", action, AgentAppActions.TOTP_ACTIONS)

        if (action == "list") {
            val accounts = data.totpAccounts()
            if (accounts.isEmpty()) return ToolResult(true, "This profile has no authenticator accounts.")
            return ToolResult(
                true,
                "Authenticator accounts (id  name):\n" +
                    accounts.joinToString("\n") { "${it.id}  ${it.label()}" }
            )
        }

        val id = str(args, "id")
            ?: return ToolResult(false, "action=$action needs 'id' from app_2fa list")
        val account = data.totpAccounts().firstOrNull { it.id == id }
            ?: return ToolResult(false, "no authenticator account with id $id — call app_2fa list first")
        val code = data.totpCode(id)
            ?: return ToolResult(false, "no code could be generated for ${account.label()}")

        return when (action) {
            "code" -> {
                if (!otpDigitsAllowed()) {
                    // Honest about the switch instead of answering with nothing:
                    // the model has to learn that copy/fill are the routes here.
                    return ToolResult(
                        false,
                        "the current code for ${account.label()} is ready and valid for " +
                            "${code.secondsLeft} s, but this profile does not let the agent read the " +
                            "digits. Use action=copy or action=fill to put the code where it is " +
                            "needed, or turn on \"Let the agent read the code itself\" in AI Agent settings."
                    )
                }
                data.markTotpUsed(id)
                ToolResult(true, "${account.label()}: ${code.digits} (valid for ${code.secondsLeft} s)")
            }
            "copy" -> {
                data.copyToClipboard("2FA code for ${account.label()}", code.digits)
                data.markTotpUsed(id)
                ToolResult(
                    true,
                    "Copied the current code for ${account.label()} to the clipboard " +
                        "(valid for ${code.secondsLeft} s)."
                )
            }
            else -> {
                val ref = int(args, "ref")
                    ?: return ToolResult(false, "action=fill needs 'ref' — the input to type the code into, from read_page")
                val fill = fillField
                    ?: return ToolResult(false, "action=fill needs a page this turn may type into, and this one has none — use action=copy instead")
                val filled = fill(ref, code.digits)
                if (!filled.ok) return filled
                data.markTotpUsed(id)
                ToolResult(
                    true,
                    "Typed the current code for ${account.label()} into [$ref]" +
                        "${if (otpDigitsAllowed()) " (${code.digits})" else ""}. " +
                        "Press Enter or submit the form if it needs it; the code is valid for " +
                        "${code.secondsLeft} s."
                )
            }
        }
    }

    // ---------------------------------------------------------- the notes

    private suspend fun notes(args: JsonObject): ToolResult {
        val action = str(args, "action") ?: "list"
        if (action !in AgentAppActions.NOTE_ACTIONS) return unknownAction("notes", action, AgentAppActions.NOTE_ACTIONS)

        if (action == "list") {
            val rows = data.notes()
            if (rows.isEmpty()) return ToolResult(true, "This profile has no notes.")
            return ToolResult(
                true,
                "Notes (id  title):\n" + rows.joinToString("\n") {
                    "${it.id}  ${it.title.ifBlank { "(untitled)" }.take(80)}"
                }
            )
        }

        if (action == "add") {
            val title = str(args, "title")
                ?: return ToolResult(false, "action=add needs 'title'")
            val id = data.saveNote(title, str(args, "body").orEmpty(), null)
            return ToolResult(true, "Added the note \"$title\" (id $id).")
        }

        val id = str(args, "id") ?: return ToolResult(false, "action=$action needs 'id' from app_notes list")
        val existing = data.note(id)
            ?: return ToolResult(false, "no note with id $id — call app_notes list first")

        return when (action) {
            "get" -> ToolResult(true, "Note \"${existing.title}\" (id ${existing.id}):\n${existing.body}")
            "update" -> {
                // An omitted field is left alone rather than cleared: the model
                // that only wanted to append a line must not silently lose the
                // title on the way.
                val title = str(args, "title") ?: existing.title
                val body = str(args, "body") ?: existing.body
                data.saveNote(title, body, id)
                ToolResult(true, "Updated the note \"$title\" (id $id).")
            }
            else -> {
                data.deleteNote(id)
                ToolResult(true, "Deleted the note \"${existing.title}\" (id $id).")
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private fun unknownAction(what: String, action: String, allowed: Set<String>): ToolResult =
        ToolResult(false, "unknown $what action '$action'. The actions are: " + allowed.sorted().joinToString(", "))

    private fun parseArgs(argsJson: String): JsonObject =
        runCatching { AgentJson.parseToJsonElement(argsJson) as? JsonObject }
            .getOrNull() ?: JsonObject(emptyMap())

    private fun prim(args: JsonObject, key: String): JsonPrimitive? = args[key] as? JsonPrimitive

    private fun str(args: JsonObject, key: String): String? =
        prim(args, key)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun int(args: JsonObject, key: String): Int? = prim(args, key)?.intOrNull
}
