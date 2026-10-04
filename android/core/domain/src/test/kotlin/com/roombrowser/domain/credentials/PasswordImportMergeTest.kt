package com.roombrowser.domain.credentials

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The import merge: what an imported file is allowed to change, and what it is
 * not.
 *
 * The cases below are the ones where a wrong answer is invisible. Importing the
 * same export twice must not double every password; a re-import after a password
 * change must not destroy the old one; and a site with two logins must not have
 * them collapsed into one.
 */
class PasswordImportMergeTest {

    @Test
    fun `an empty profile takes every row`() {
        val plan = PasswordImportMerge.plan(
            existing = emptyList(),
            incoming = listOf(row("example.com", "alice", "s3cret"), row("other.test", "bob", "pw"))
        )

        assertThat(plan.fresh).hasSize(2)
        assertThat(plan.duplicates).isEqualTo(0)
        assertThat(plan.conflicts).isEqualTo(0)
    }

    @Test
    fun `importing the same file twice imports nothing the second time`() {
        // The case the feature exists for: a user re-imports last month's export
        // because they are not sure whether it went through.
        val credentials = listOf(row("example.com", "alice", "s3cret"), row("other.test", "bob", "pw"))
        val existing = credentials.map { it.toSaved() }

        val plan = PasswordImportMerge.plan(existing, credentials)

        assertThat(plan.fresh).isEmpty()
        assertThat(plan.duplicates).isEqualTo(2)
        assertThat(plan.conflicts).isEqualTo(0)
    }

    @Test
    fun `a login repeated inside one file is imported once`() {
        val plan = PasswordImportMerge.plan(
            existing = emptyList(),
            incoming = listOf(row("example.com", "alice", "pw"), row("example.com", "alice", "pw"))
        )

        assertThat(plan.fresh).hasSize(1)
        assertThat(plan.duplicates).isEqualTo(1)
    }

    @Test
    fun `a changed password is kept alongside the old one not overwritten`() {
        // Re-importing an export taken after a password change. Overwriting
        // would destroy the old password irreversibly; the incoming row is kept
        // and reported instead, so the user chooses.
        val plan = PasswordImportMerge.plan(
            existing = listOf(saved("example.com", "alice", "old-pw")),
            incoming = listOf(row("example.com", "alice", "new-pw"))
        )

        assertThat(plan.fresh.map { it.password }).containsExactly("new-pw")
        assertThat(plan.conflicts).isEqualTo(1)
        assertThat(plan.duplicates).isEqualTo(0)
    }

    @Test
    fun `same site and username with a different password is not a duplicate`() {
        // Guard for the conflict branch: a naive `if (key in saved) skip` would
        // report this as a duplicate and drop the newer password silently.
        val plan = PasswordImportMerge.plan(
            existing = listOf(saved("example.com", "alice", "one")),
            incoming = listOf(row("example.com", "alice", "two"))
        )

        assertThat(plan.duplicates).isEqualTo(0)
        assertThat(plan.fresh).hasSize(1)
    }

    @Test
    fun `two logins for one site are both kept`() {
        // A site with two accounts must not have them collapsed into one.
        val plan = PasswordImportMerge.plan(
            existing = emptyList(),
            incoming = listOf(row("example.com", "alice", "pw"), row("example.com", "bob", "pw"))
        )

        assertThat(plan.fresh).hasSize(2)
    }

    @Test
    fun `the same site saved over http and https with one password is one login`() {
        // Chrome writes two rows when a site was saved on both schemes. The
        // reader canonicalises both to one host, so this is the duplicate case
        // rather than a spurious second login.
        val plan = PasswordImportMerge.plan(
            existing = emptyList(),
            incoming = listOf(row("example.com", "alice", "pw"), row("example.com", "alice", "pw"))
        )

        assertThat(plan.fresh).hasSize(1)
    }

    @Test
    fun `the same site saved over http and https with different passwords keeps both`() {
        val plan = PasswordImportMerge.plan(
            existing = emptyList(),
            incoming = listOf(row("example.com", "alice", "pw-http"), row("example.com", "alice", "pw-https"))
        )

        assertThat(plan.fresh.map { it.password }).containsExactly("pw-http", "pw-https")
        assertThat(plan.conflicts).isEqualTo(1)
    }

    @Test
    fun `the username is compared case-insensitively`() {
        val plan = PasswordImportMerge.plan(
            existing = listOf(saved("example.com", "Alice@Example.com", "pw")),
            incoming = listOf(row("example.com", "alice@example.com", "pw"))
        )

        assertThat(plan.duplicates).isEqualTo(1)
        assertThat(plan.fresh).isEmpty()
    }

    @Test
    fun `the password is compared case-sensitively`() {
        // Passwords are case-sensitive: two rows differing only in password case
        // are two different logins, not a duplicate of one.
        val plan = PasswordImportMerge.plan(
            existing = listOf(saved("example.com", "alice", "Secret")),
            incoming = listOf(row("example.com", "alice", "secret"))
        )

        assertThat(plan.duplicates).isEqualTo(0)
        assertThat(plan.conflicts).isEqualTo(1)
    }

    @Test
    fun `a saved domain is canonicalised before it is compared`() {
        // Saved rows are written canonical, but a capitalised or FQDN-dot form
        // must still match rather than become a second login for one site.
        val plan = PasswordImportMerge.plan(
            existing = listOf(saved("Example.COM.", "alice", "pw")),
            incoming = listOf(row("example.com", "alice", "pw"))
        )

        assertThat(plan.duplicates).isEqualTo(1)
    }

    @Test
    fun `a saved username is trimmed before it is compared`() {
        val plan = PasswordImportMerge.plan(
            existing = listOf(saved("example.com", "  alice  ", "pw")),
            incoming = listOf(row("example.com", "alice", "pw"))
        )

        assertThat(plan.duplicates).isEqualTo(1)
    }

    @Test
    fun `rows with no username match each other on the domain alone`() {
        // An export with no username column produces blank usernames; two rows
        // for one site with the same password are then the same login.
        val plan = PasswordImportMerge.plan(
            existing = listOf(saved("example.com", "", "pw")),
            incoming = listOf(row("example.com", "", "pw"))
        )

        assertThat(plan.duplicates).isEqualTo(1)
    }

    @Test
    fun `a subdomain is a different site from its parent`() {
        // matches() is parent-domain aware for filling; importing is not. A
        // login saved on example.com must not absorb one exported for
        // accounts.example.com, which is frequently a different account.
        val plan = PasswordImportMerge.plan(
            existing = listOf(saved("example.com", "alice", "pw")),
            incoming = listOf(row("accounts.example.com", "alice", "pw"))
        )

        assertThat(plan.duplicates).isEqualTo(0)
        assertThat(plan.fresh).hasSize(1)
    }

    @Test
    fun `every imported row is accounted for`() {
        // The screen reports "N imported, M already saved"; the two counts must
        // add up to the file, or the user is being told something untrue.
        val plan = PasswordImportMerge.plan(
            existing = listOf(saved("example.com", "alice", "pw")),
            incoming = listOf(
                row("example.com", "alice", "pw"), // duplicate
                row("other.test", "bob", "new"), // fresh
                row("third.test", "carol", "new") // fresh
            )
        )

        assertThat(plan.total).isEqualTo(3)
        assertThat(plan.fresh.size + plan.duplicates).isEqualTo(3)
    }

    private fun row(domain: String, username: String, password: String) =
        PasswordCsv.Row(domain = domain, username = username, password = password, title = null)

    private fun saved(domain: String, username: String, password: String) =
        SavedCredential(
            id = "id-$domain-$username-$password",
            profileId = "profile",
            domain = domain,
            username = username,
            password = password,
            title = null,
            createdAt = 0L,
            updatedAt = 0L
        )

    private fun PasswordCsv.Row.toSaved() = saved(domain, username, password)
}
