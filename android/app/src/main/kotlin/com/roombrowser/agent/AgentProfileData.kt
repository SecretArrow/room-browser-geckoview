package com.roombrowser.agent

import android.content.Context
import com.roombrowser.data.db.NoteEntity
import com.roombrowser.di.AppGraph
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.totp.TotpGenerator
import com.roombrowser.ui.common.copySensitive

/**
 * One authenticator account as the AGENT may see it: the labels and the period,
 * never the seed and never a code. This type is the whole of what the agent
 * tools below can hold about an account, so "the agent cannot reach the setup
 * key" is a fact about a type rather than a rule someone has to remember.
 */
data class AgentTotpAccount(val id: String, val issuer: String, val account: String, val period: Int) {
    /** How the account is named in a result, e.g. "Acme (ada@example.com)". */
    fun label(): String = when {
        issuer.isBlank() -> account.ifBlank { "the account" }
        account.isBlank() -> issuer
        else -> "$issuer ($account)"
    }
}

/**
 * A generated code and how long it stays valid, read from ONE clock sample so
 * the digits and the countdown can never describe different periods.
 */
data class AgentTotpCode(val digits: String, val secondsLeft: Int)

/**
 * The agent's door to the profile's own stored content — the notes and the
 * authenticator accounts — bound to ONE profile, which is the profile the turn
 * is running as. Every method is scoped to it, so a tool cannot name another
 * profile's row even if the model guesses an id.
 *
 * An interface rather than a direct dependency on [AppGraph] because the two
 * executors live in different worlds: a chat turn has a ViewModel, a scheduled
 * run has a graph, and [AgentProfileTools] must be the SAME code in both so the
 * wording and the refusals cannot drift apart.
 */
interface AgentProfileData {

    /** Labels only — no seed, no code. */
    suspend fun totpAccounts(): List<AgentTotpAccount>

    /**
     * The current code for one account, generated ON DEVICE, or null when the
     * id is unknown or belongs to another profile. Deliberately not gated on
     * the 2FA lock: the owner decided the agent may always retrieve a code, so
     * the lock protects the screen and not the codes.
     */
    suspend fun totpCode(id: String): AgentTotpCode?

    /** Bumps the account's "recently used" order after a copy or a fill. */
    suspend fun markTotpUsed(id: String)

    /** Puts a value on the clipboard, flagged sensitive. Never announces it. */
    fun copyToClipboard(label: String, value: String)

    suspend fun notes(): List<NoteEntity>

    /** This profile's note, or null when the id is unknown or another profile's. */
    suspend fun note(id: String): NoteEntity?

    /** Creates (id null) or updates one note, and returns its id. */
    suspend fun saveNote(title: String, body: String, id: String?): String

    /** Deletes one note of this profile. Another profile's id is a no-op. */
    suspend fun deleteNote(id: String)
}

/** [AgentProfileData] over the app's repositories. */
class GraphAgentProfileData(
    private val graph: AppGraph,
    private val profileId: ProfileId,
    private val context: Context
) : AgentProfileData {

    override suspend fun totpAccounts(): List<AgentTotpAccount> =
        graph.totpRepo.labelsForAgent(profileId).map {
            AgentTotpAccount(id = it.id, issuer = it.issuer, account = it.account, period = it.period)
        }

    override suspend fun totpCode(id: String): AgentTotpCode? {
        val account = totpAccounts().firstOrNull { it.id == id } ?: return null
        val now = System.currentTimeMillis()
        val digits = graph.totpRepo.codeForAgent(profileId, id, now) ?: return null
        return AgentTotpCode(digits, TotpGenerator.secondsRemaining(now, account.period))
    }

    override suspend fun markTotpUsed(id: String) = graph.totpRepo.markUsed(id)

    override fun copyToClipboard(label: String, value: String) =
        copySensitive(context, label, value)

    override suspend fun notes(): List<NoteEntity> = graph.browserRepo.notes(profileId)

    override suspend fun note(id: String): NoteEntity? = graph.browserRepo.note(profileId, id)

    override suspend fun saveNote(title: String, body: String, id: String?): String =
        graph.browserRepo.saveNote(profileId, title, body, id)

    override suspend fun deleteNote(id: String) = graph.browserRepo.deleteNoteFor(profileId, id)
}
