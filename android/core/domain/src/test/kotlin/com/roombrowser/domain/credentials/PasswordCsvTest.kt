package com.roombrowser.domain.credentials

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The password-CSV reader, against the four browsers' real export shapes.
 *
 * WHAT THIS TEST IS FOR. An importer that is wrong in a quiet way is worse than
 * one that refuses: the user sees "412 passwords imported", deletes the export
 * from their Downloads folder, and finds out months later that a column was
 * shifted. So the cases below are the shapes real files actually have — a quoted
 * comma, a note containing a newline, CRLF, a BOM, a Firefox row whose password
 * is encrypted — and the assertions are about the *whole* record, not just that
 * parsing returned something.
 */
class PasswordCsvTest {

    @Test
    fun `chrome export maps name url username password`() {
        val csv = """
            name,url,username,password,note
            Example,https://example.com/login,alice@example.com,s3cret,
            Other,https://other.test,bob,hunter2,some note
        """.trimIndent()

        val result = parsed(csv)

        assertThat(result.rows).hasSize(2)
        assertThat(result.rows[0].domain).isEqualTo("example.com")
        assertThat(result.rows[0].username).isEqualTo("alice@example.com")
        assertThat(result.rows[0].password).isEqualTo("s3cret")
        assertThat(result.rows[0].title).isEqualTo("Example")
        assertThat(result.rows[1].domain).isEqualTo("other.test")
        assertThat(result.skippedTotal).isEqualTo(0)
    }

    @Test
    fun `brave writes the chrome shape and is read the same way`() {
        // Brave's export is Chrome's. Asserted separately because the owner
        // named Brave explicitly, and because a future Brave column would be
        // caught here rather than in Chrome's case.
        val csv = "name,url,username,password,note\n" +
            "Brave Login,https://brave.test/,carol,pw,note\n"

        val result = parsed(csv)

        assertThat(result.rows.single().domain).isEqualTo("brave.test")
        assertThat(result.rows.single().title).isEqualTo("Brave Login")
    }

    @Test
    fun `firefox export is read by name and httpRealm is not a domain`() {
        // Firefox's columns are positional strangers to Chrome's: here the
        // third value is the password and `httpRealm` follows it. A positional
        // reader would store `httpRealm`'s value as a site.
        val csv = "url,username,password,httpRealm,formActionOrigin,guid,timeCreated\n" +
            "https://mozilla.test,dave,pw123,Some Realm,https://mozilla.test,{guid},1700000000000\n"

        val result = parsed(csv)

        assertThat(result.rows).hasSize(1)
        assertThat(result.rows.single().domain).isEqualTo("mozilla.test")
        assertThat(result.rows.single().username).isEqualTo("dave")
        assertThat(result.rows.single().password).isEqualTo("pw123")
        // Firefox has no label column, so nothing is invented for the title.
        assertThat(result.rows.single().title).isNull()
    }

    @Test
    fun `formActionOrigin is used when the url column is empty`() {
        val csv = "url,username,password,formActionOrigin\n" +
            ",erin,pw,https://fallback.test/login\n"

        val result = parsed(csv)

        assertThat(result.rows.single().domain).isEqualTo("fallback.test")
    }

    @Test
    fun `a quoted comma is data not a separator`() {
        val csv = "name,url,username,password\n" +
            "\"Doe, Jane\",https://example.com,jane,\"a,b,c\"\n"

        val result = parsed(csv)

        assertThat(result.rows.single().title).isEqualTo("Doe, Jane")
        assertThat(result.rows.single().password).isEqualTo("a,b,c")
    }

    @Test
    fun `a newline inside a quoted field does not shift the following row`() {
        // Chrome's `note` column routinely holds a newline. Reading it as the
        // end of the record would offset every later row by a column.
        val csv = "name,url,username,password,note\n" +
            "Example,https://example.com,frank,pw1,\"line one\nline two\"\n" +
            "Second,https://second.test,grace,pw2,\n"

        val result = parsed(csv)

        assertThat(result.rows).hasSize(2)
        assertThat(result.rows[0].password).isEqualTo("pw1")
        assertThat(result.rows[1].domain).isEqualTo("second.test")
        assertThat(result.rows[1].username).isEqualTo("grace")
        assertThat(result.rows[1].password).isEqualTo("pw2")
    }

    @Test
    fun `a doubled quote inside a quoted field is one literal quote`() {
        val csv = "name,url,username,password\n" +
            "\"The \"\"Best\"\" Site\",https://example.com,heidi,pw\n"

        val result = parsed(csv)

        assertThat(result.rows.single().title).isEqualTo("The \"Best\" Site")
    }

    @Test
    fun `an unescaped quote inside an unquoted field stays data`() {
        // Some exports write a password containing a quote without escaping it.
        // Treating it as an opening quote would swallow the rest of the file
        // into one field; treating it as data keeps the row readable.
        val csv = "name,url,username,password\n" +
            "Example,https://example.com,ivan,pa\"ss\n" +
            "Second,https://second.test,judy,pw2\n"

        val result = parsed(csv)

        assertThat(result.rows).hasSize(2)
        assertThat(result.rows[0].password).isEqualTo("pa\"ss")
        assertThat(result.rows[1].username).isEqualTo("judy")
    }

    @Test
    fun `a password is never trimmed`() {
        // Leading and trailing spaces are part of the password. Trimming it
        // would fail the login later with no visible cause.
        val csv = "name,url,username,password\n" +
            "Example,https://example.com,ken,\"  spaced pw  \"\n"

        assertThat(parsed(csv).rows.single().password).isEqualTo("  spaced pw  ")
    }

    @Test
    fun `a byte order mark and CRLF line endings are accepted`() {
        val csv = "﻿name,url,username,password\r\n" +
            "Example,https://example.com,laura,pw\r\n"

        val result = parsed(csv)

        // The BOM must not survive into the first header cell, or `name` would
        // not be recognised as a column at all.
        assertThat(result.rows.single().title).isEqualTo("Example")
        assertThat(result.rows.single().password).isEqualTo("pw")
    }

    @Test
    fun `a file with no final newline still yields its last row`() {
        val csv = "name,url,username,password\nExample,https://example.com,mallory,pw"

        assertThat(parsed(csv).rows.single().username).isEqualTo("mallory")
    }

    @Test
    fun `blank lines are ignored rather than counted as skipped`() {
        val csv = "name,url,username,password\n" +
            "\n" +
            "Example,https://example.com,nina,pw\n" +
            "\n"

        val result = parsed(csv)

        assertThat(result.rows).hasSize(1)
        assertThat(result.skippedTotal).isEqualTo(0)
    }

    @Test
    fun `an encrypted firefox row is counted as encrypted not imported`() {
        // A Firefox export taken with a primary password set carries
        // `moz_ciphertext` and no plaintext. Those bytes are not a password,
        // and importing them would fill login forms with garbage.
        val csv = "url,username,password,httpRealm,moz_ciphertext\n" +
            "https://example.com,oscar,,,AAAAencryptedAAAA\n" +
            "https://other.test,peggy,pw,,\n"

        val result = parsed(csv)

        assertThat(result.rows).hasSize(1)
        assertThat(result.rows.single().username).isEqualTo("peggy")
        assertThat(result.skipped[PasswordCsv.SkipReason.ENCRYPTED]).isEqualTo(1)
    }

    @Test
    fun `a non-web origin is counted as unsupported`() {
        // Firefox exports rows for about: and moz-extension: origins, and
        // java.net.URI happily returns a host for some of them. Neither can
        // ever be matched by the in-page filler.
        val csv = "url,username,password\n" +
            "moz-extension://8f3a-uuid/panel.html,quinn,pw\n" +
            "about:config,rupert,pw\n" +
            "https://kept.test,sybil,pw\n"

        val result = parsed(csv)

        assertThat(result.rows).hasSize(1)
        assertThat(result.rows.single().domain).isEqualTo("kept.test")
        assertThat(result.skipped[PasswordCsv.SkipReason.UNSUPPORTED_URL]).isEqualTo(2)
    }

    @Test
    fun `a row with no url at all is counted separately from an unsupported one`() {
        val csv = "url,username,password\n" +
            ",trent,pw\n"

        val result = parsed(csv)

        assertThat(result.rows).isEmpty()
        assertThat(result.skipped[PasswordCsv.SkipReason.NO_URL]).isEqualTo(1)
        assertThat(result.skipped[PasswordCsv.SkipReason.UNSUPPORTED_URL]).isNull()
    }

    @Test
    fun `a row with neither password nor ciphertext is counted as incomplete`() {
        val csv = "url,username,password\n" +
            "https://example.com,uma,\n"

        val result = parsed(csv)

        assertThat(result.rows).isEmpty()
        assertThat(result.skipped[PasswordCsv.SkipReason.INCOMPLETE]).isEqualTo(1)
    }

    @Test
    fun `a short row does not throw and is counted`() {
        // A truncated last line is a real thing in a file copied off a phone.
        val csv = "name,url,username,password\n" +
            "Example,https://example.com\n"

        val result = parsed(csv)

        assertThat(result.rows).isEmpty()
        assertThat(result.skippedTotal).isEqualTo(1)
    }

    @Test
    fun `hosts are canonicalised`() {
        // Deliberately only the case this test owns: an uppercase host with a
        // port and a path resolves to the lowercase bare host. The trailing-dot
        // rule belongs to CredentialDomainMatcher.normalize and is proven by
        // CredentialDomainMatcherTest, so it is not re-proven here through
        // java.net.URI's parser, whose behaviour on a trailing dot is not this
        // reader's contract.
        val csv = "name,url,username,password\n" +
            "Upper,https://Example.COM:8443/Login,vic,pw\n"

        assertThat(parsed(csv).rows.single().domain).isEqualTo("example.com")
    }

    @Test
    fun `header names are matched ignoring case spaces and underscores`() {
        val csv = "Name,Origin_URL,User,Pass\n" +
            "Example,https://example.com,xavier,pw\n"

        assertThat(parsed(csv).rows.single().username).isEqualTo("xavier")
    }

    @Test
    fun `a file that is not a password csv is reported as such`() {
        // Distinct from "0 imported": telling the user a wrong file was
        // "nothing to import" would hide the mistake.
        val csv = "date,description,amount\n2026-01-01,coffee,3.50\n"

        assertThat(PasswordCsv.parse(csv)).isEqualTo(PasswordCsv.Result.NotAPasswordCsv)
    }

    @Test
    fun `a csv with no url column is not a password csv`() {
        // A password with nowhere to be used is not importable, so this is the
        // wrong file rather than an empty one.
        val csv = "username,password\nalice,pw\n"

        assertThat(PasswordCsv.parse(csv)).isEqualTo(PasswordCsv.Result.NotAPasswordCsv)
    }

    @Test
    fun `an empty file is not a password csv`() {
        assertThat(PasswordCsv.parse("")).isEqualTo(PasswordCsv.Result.NotAPasswordCsv)
        assertThat(PasswordCsv.parse("   \n  ")).isEqualTo(PasswordCsv.Result.NotAPasswordCsv)
    }

    @Test
    fun `a header with no data rows parses as an empty result`() {
        // A genuine password export with nothing in it is not a wrong file.
        val result = parsed("name,url,username,password\n")

        assertThat(result.rows).isEmpty()
        assertThat(result.skippedTotal).isEqualTo(0)
    }

    private fun parsed(csv: String): PasswordCsv.Result.Parsed {
        val result = PasswordCsv.parse(csv)
        assertThat(result).isInstanceOf(PasswordCsv.Result.Parsed::class.java)
        return result as PasswordCsv.Result.Parsed
    }
}
