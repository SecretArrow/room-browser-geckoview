package com.roombrowser.domain.agent

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** What the page probe reports about itself for a digest. */
@Serializable
data class PageDigestDto(
    val url: String = "",
    val textLength: Int = 0,
    val textHash: String = "",
    val elements: String = ""
)

/**
 * The result of measuring the page around a mutating action.
 *
 * [observed] is what the action itself reported; [changed] is whether the page
 * moved; [evidence] is the line the model is given, so "nothing happened" is a
 * fact it can read rather than an impression it has to form.
 */
data class VerificationResult(
    val observed: String,
    val changed: Boolean,
    val evidence: String
)

/**
 * Measures before and after a mutating action, so the agent can compare instead
 * of assuming.
 *
 * The digest covers the URL, the visible text and the interactive elements'
 * state — the three things an action is supposed to be able to change. It is
 * cheap by construction: the page hashes its own text, and this only combines
 * the part it is given.
 *
 * A digest that does not move after a click is the single most useful fact the
 * agent can have: the click did not do what the model thought it did, and the
 * model is told so at the moment it happens rather than three steps later.
 */
object AgentStepVerifier {

    /** A canonical, stable digest. Not a security hash — a change detector. */
    fun digest(page: PageDigestDto): String {
        val canonical = listOf(
            page.url.trim(),
            page.textLength.toString(),
            page.textHash.trim(),
            page.elements.trim()
        ).joinToString("\u0001")
        return sha256(canonical)
    }

    fun compare(before: String?, after: String?, observed: String): VerificationResult {
        val changed = before != null && after != null && before != after
        val evidence = when {
            before == null || after == null ->
                "verification: the page could not be measured before and after, so whether this " +
                    "changed anything is unknown."
            changed ->
                "verification: the page changed as a result of this action."
            else ->
                "verification: the page did NOT change. The action had no visible effect — do not " +
                    "repeat it; read the page again and choose another approach."
        }
        return VerificationResult(observed = observed, changed = changed, evidence = evidence)
    }

    /** The evidence line appended to a tool result, or an empty string. */
    fun evidenceFor(before: String?, after: String?): String =
        compare(before, after, "").evidence

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
