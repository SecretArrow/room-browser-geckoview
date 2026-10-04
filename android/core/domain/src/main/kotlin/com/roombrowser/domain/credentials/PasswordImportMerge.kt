package com.roombrowser.domain.credentials

/**
 * Decides which rows of an imported password file are new, which are already
 * saved, and which disagree with what is saved.
 *
 * WHY THIS IS NOT LEFT TO THE DATABASE. `CredentialDao.upsert` is keyed by the
 * row id, and the import path mints a fresh id for every row on purpose (so a
 * re-import can never collide with an existing primary key). That is right for
 * an id, and wrong for a login: it makes importing the same file twice save
 * every password a second time. There is no uniqueness the schema could carry
 * either — a domain legitimately has several logins — so the decision has to be
 * made in Kotlin, against the profile's current rows, before the insert.
 *
 * WHAT COUNTS AS THE SAME LOGIN. The triple (domain, username, password), with
 * the domain canonicalised and the username compared case-insensitively. Each
 * half of that is a deliberate choice:
 *
 *  - The domain is canonicalised ([CredentialDomainMatcher.normalize]) because
 *    `Example.COM` and `example.com` are the same site and an import must not
 *    depend on which spelling the exporting browser happened to write.
 *  - The username is compared case-insensitively because `Alice@example.com`
 *    and `alice@example.com` are the same account to every site that matters,
 *    and the cost of calling them different is a visible duplicate row.
 *  - The password is compared EXACTLY, case included, because passwords are
 *    case-sensitive and two rows that differ only in password case really are
 *    two different logins.
 *
 * A SAME SITE, DIFFERENT PASSWORD IS KEPT, NOT OVERWRITTEN. This is the one
 * judgement call here, and it goes the way that is recoverable. Re-importing a
 * file the user exported before changing a password produces, per changed site,
 * one incoming row whose (domain, username) is already saved with a different
 * password. Overwriting would destroy the old password with no way back, and
 * silently skipping would drop the newer one; so the incoming row is imported
 * alongside the existing one and [Plan.conflicts] counts it so the screen can
 * say so. The user then has both and deletes the one they do not want. Two rows
 * for one site is untidy and sits in a list the user can see; a silently
 * destroyed password is neither.
 *
 * Note that this also handles a case that looks like a bug in the reader and is
 * not: Chrome exports the same site saved over both http and https as two rows,
 * which canonicalise to one host. If their passwords match, the second is a
 * duplicate; if they differ, both are kept. Either way nothing is lost.
 */
object PasswordImportMerge {

    /** What to insert, and what to tell the user about the rest. */
    data class Plan(
        /** Rows to insert, in file order. Includes rows counted in [conflicts]. */
        val fresh: List<PasswordCsv.Row>,
        /** Rows already saved with this exact login: skipped, nothing to do. */
        val duplicates: Int,
        /** Rows kept alongside another login for the same site and username. */
        val conflicts: Int
    ) {
        /** Every row of the file that could be imported, however it was resolved. */
        val total: Int get() = fresh.size + duplicates
    }

    /**
     * Match [incoming] against what the profile [existing] already holds.
     *
     * [existing] must be the profile's decrypted logins — the same list
     * `CredentialRepository.exportAll` returns. Its ids, timestamps and titles
     * are not consulted: only (domain, username, password) decides the outcome.
     */
    fun plan(
        existing: Collection<SavedCredential>,
        incoming: List<PasswordCsv.Row>
    ): Plan {
        // (domain, username-lowercased) -> every password saved for it. A set
        // of passwords rather than a single one because a site may hold several
        // logins under one username, and each of them has to be able to match.
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
                    // Nothing saved for this site and username yet; after this
                    // row, something is, so a second identical row in the same
                    // file is caught by the duplicate branch below.
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
