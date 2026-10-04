package com.roombrowser.domain.credentials

/**
 * Decides which rows of an imported password file are new, which are already
 * saved, and which disagree with what is saved.
 *
 * This cannot be left to the database: `CredentialDao.upsert` is keyed by the
 * row id, and the import path mints a fresh id per row so a re-import can never
 * collide with an existing key — which would save every password a second time.
 * The schema cannot carry the uniqueness either (a domain legitimately has
 * several logins), so the decision is made here against the profile's current
 * rows, before the insert.
 *
 * A login is the same when (domain, username, password) match, with the domain
 * canonicalised ([CredentialDomainMatcher.normalize]) and the username compared
 * case-insensitively; the password is compared exactly, because passwords are
 * case-sensitive. A same site, same username, different password is KEPT
 * alongside the saved one and counted in [Plan.conflicts] rather than
 * overwritten: overwriting would destroy the old password with no way back.
 * (This also covers Chrome exporting one site over http and https as two rows,
 * which canonicalise to one host.)
 */
object PasswordImportMerge {

    data class Plan(
        /** Rows to insert, in file order. Includes rows counted in [conflicts]. */
        val fresh: List<PasswordCsv.Row>,
        val duplicates: Int,
        val conflicts: Int
    ) {
        val total: Int get() = fresh.size + duplicates
    }

    /**
     * Match [incoming] against what the profile [existing] already holds; only
     * (domain, username, password) decides the outcome, not ids or titles.
     */
    fun plan(
        existing: Collection<SavedCredential>,
        incoming: List<PasswordCsv.Row>
    ): Plan {
        // (domain, lowercase username) -> every password saved for it; a set
        // because a site may hold several logins under one username.
        val saved = HashMap<Pair<String, String>, MutableSet<String>>()
        for (credential in existing) {
            saved.getOrPut(keyOf(credential.domain, credential.username)) { HashSet() }
                .add(credential.password)
        }

        val fresh = ArrayList<PasswordCsv.Row>(incoming.size)
        var duplicates = 0
        var conflicts = 0

        for (row in incoming) {
            val key = keyOf(row.domain, row.username)
            val passwords = saved[key]
            when {
                passwords == null -> {
                    // After this row something is saved, so a second identical
                    // row in the same file is caught by the duplicate branch.
                    saved[key] = hashSetOf(row.password)
                    fresh += row
                }
                row.password in passwords -> duplicates++
                else -> {
                    passwords += row.password
                    conflicts++
                    fresh += row
                }
            }
        }

        return Plan(fresh = fresh, duplicates = duplicates, conflicts = conflicts)
    }

    private fun keyOf(domain: String, username: String): Pair<String, String> =
        CredentialDomainMatcher.normalize(domain) to username.trim().lowercase()
}
