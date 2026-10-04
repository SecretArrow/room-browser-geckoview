package com.roombrowser.domain.credentials

/**
 * Reads the password file that other browsers export.
 *
 * WHY A CSV READER AND NOT A LIBRARY. Chrome, Brave, Edge and Firefox can only
 * hand a user their passwords as a CSV: none of them will write our format, and
 * Android gives no way for one app to read another app's password store. So the
 * import path is a file the user exported themselves, and the only thing that
 * can be done with it is parse it. A CSV library would be a dependency for one
 * screen; the format is small enough to read here and the parsing rules that
 * matter (quoting, embedded newlines, embedded commas) are the ones written
 * down below rather than the ones a library happens to implement.
 *
 * THE COLUMNS ARE FOUND BY NAME, NEVER BY POSITION. This is the whole reason
 * one parser can serve four browsers: Chrome and Brave write
 * `name,url,username,password,note`, Firefox writes
 * `url,username,password,httpRealm,formActionOrigin,guid,timeCreated,...`, and
 * Edge writes Chrome's shape. A positional reader would import Firefox's
 * `httpRealm` as a password. Any column we do not know is ignored rather than
 * rejected, so a new browser adding a column does not break the import.
 *
 * WHAT IT REFUSES, LOUDLY. Two kinds of row cannot become a Room Browser
 * login and are counted instead of imported:
 *
 *  - a Firefox row whose password is encrypted (`moz_ciphertext`, written when
 *    the export was taken with a primary password set). Those bytes are not a
 *    password and cannot be decrypted without NSS's `key4.db` and the master
 *    password, so importing them would store a string that fills a login form
 *    with garbage. The user is told how many rows to re-export without a
 *    primary password.
 *  - a row with no usable web URL. This is not a corner case: Chrome's own
 *    export carries `android://<hash>@<package>/` rows for saved app
 *    credentials, Firefox exports `about:` and `moz-extension:` origins, and a
 *    URL cell can simply be blank. None of them is a site the in-page filler
 *    can ever match, so they are counted as unsupported rather than imported
 *    as a login that never appears.
 *
 * The counters exist so the screen can say "412 imported, 30 skipped because
 * they were encrypted" instead of quietly importing less than the file held.
 * A silent partial import is the failure mode that matters here: the user
 * concludes their passwords are safe in the app and deletes the CSV.
 */
object PasswordCsv {

    /** One row that can become a saved login. */
    data class Row(
        /** Canonical host ([CredentialDomainMatcher.normalize]); never blank. */
        val domain: String,
        val username: String,
        val password: String,
        /** The export's own label column (`name` in Chrome), when it had one. */
        val title: String?
    )

    /** Why a row was left out. Each is reported as a count, never silently. */
    enum class SkipReason {
        /** Firefox `moz_ciphertext`: the password is encrypted, not text. */
        ENCRYPTED,

        /** No URL column value at all. */
        NO_URL,

        /** A URL, but not http/https — `about:`, `moz-extension:`, `chrome:`. */
        UNSUPPORTED_URL,

        /** A URL and a username, but no password and no ciphertext. */
        INCOMPLETE
    }

    sealed interface Result {
        /**
         * The file was a password CSV. [rows] is what could be imported and
         * [skipped] is why the rest could not; both together account for every
         * data record in the file.
         */
        data class Parsed(
            val rows: List<Row>,
            val skipped: Map<SkipReason, Int>
        ) : Result {
            val skippedTotal: Int get() = skipped.values.sum()
        }

        /**
         * The header has no column this importer recognises, so the file is
         * not a password export at all — a wrong file, a different CSV, or a
         * spreadsheet the user re-saved. Kept distinct from "0 imported" on
         * purpose: those are different things to tell the user, and treating
         * this as an empty success would report a wrong file as "nothing to
         * import".
         */
        data object NotAPasswordCsv : Result
    }

    // Column names, lowercased and stripped of spaces/underscores. Each list is
    // in preference order: the first present column wins, so a file carrying
    // both `url` and `origin_url` takes `url`.
    private val URL_COLUMNS = listOf("url", "originurl", "formactionorigin", "loginuri")
    private val USERNAME_COLUMNS = listOf("username", "login", "user")
    private val PASSWORD_COLUMNS = listOf("password", "pass", "loginpassword")
    private val CIPHERTEXT_COLUMNS = listOf("mozciphertext")
    private val TITLE_COLUMNS = listOf("name", "title")

    /**
     * Parse [text] as a password CSV.
     *
     * A UTF-8 BOM, CRLF or LF line endings and a missing final newline are all
     * accepted, because which of those a file has depends on the browser and
     * the platform that wrote it and none of them is a reason to fail.
     */
    fun parse(text: String): Result {
        val records = readRecords(text)
        if (records.isEmpty()) return Result.NotAPasswordCsv

        val header = records.first().map { normaliseHeader(it) }
        val urlColumn = header.indexOfFirst { it in URL_COLUMNS }
        val passwordColumn = header.indexOfFirst { it in PASSWORD_COLUMNS }
        val ciphertextColumn = header.indexOfFirst { it in CIPHERTEXT_COLUMNS }
        // A password export without a URL column cannot be matched to a site,
        // and one with neither a password nor a ciphertext column is not a
        // password file. Everything else is optional.
        if (urlColumn < 0 || (passwordColumn < 0 && ciphertextColumn < 0)) {
            return Result.NotAPasswordCsv
        }

        val usernameColumn = header.indexOfFirst { it in USERNAME_COLUMNS }
        val titleColumn = header.indexOfFirst { it in TITLE_COLUMNS }

        val rows = ArrayList<Row>(records.size)
        val skipped = LinkedHashMap<SkipReason, Int>()

        fun skip(reason: SkipReason) {
            skipped[reason] = (skipped[reason] ?: 0) + 1
        }

        for (index in 1 until records.size) {
            val record = records[index]
            // A trailing newline produces one final empty record; a blank line
            // in the middle of the file is the same thing to RFC 4180. Neither
            // is a row, so neither is counted as a skipped one.
            if (record.all { it.isBlank() }) continue

            val rawUrl = record.getOrNull(urlColumn).orEmpty().trim()
            val url = webHostOf(rawUrl)
            if (url == null) {
                skip(if (rawUrl.isEmpty()) SkipReason.NO_URL else SkipReason.UNSUPPORTED_URL)
                continue
            }

            val password = passwordColumn.takeIf { it >= 0 }
                ?.let { record.getOrNull(it) }
                .orEmpty()
            val ciphertext = ciphertextColumn.takeIf { it >= 0 }
                ?.let { record.getOrNull(it) }
                .orEmpty()
            if (password.isEmpty()) {
                skip(if (ciphertext.isNotEmpty()) SkipReason.ENCRYPTED else SkipReason.INCOMPLETE)
                continue
            }

            rows += Row(
                domain = url,
                username = usernameColumn.takeIf { it >= 0 }
                    ?.let { record.getOrNull(it) }
                    .orEmpty()
                    .trim(),
                password = password,
                title = titleColumn.takeIf { it >= 0 }
                    ?.let { record.getOrNull(it) }
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            )
        }

        return Result.Parsed(rows = rows, skipped = skipped)
    }

    /**
     * The host of [raw] when it is an http/https URL, else null.
     *
     * The scheme check is a real filter, not pedantry: Firefox exports logins
     * for `about:` and `moz-extension:` origins, and `java.net.URI` happily
     * returns a host for some of them (an extension's UUID, for instance).
     * Storing that as a domain would create a login that no page can ever
     * match and that the user cannot explain.
     */
    private fun webHostOf(raw: String): String? {
        if (raw.isEmpty()) return null
        val lower = raw.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val host = com.roombrowser.domain.engine.UrlIntelligence.hostOf(raw) ?: return null
        return CredentialDomainMatcher.normalize(host).takeIf { it.isNotEmpty() }
    }

    /** `"Origin_URL"` / `"moz_ciphertext"` / `"form action origin"` all fold. */
    private fun normaliseHeader(raw: String): String =
        raw.trim().lowercase().filter { it.isLetterOrDigit() }

    /**
     * Split [text] into records of fields, RFC 4180 style.
     *
     * The four rules that matter, and the reason each is here rather than left
     * to a `split(",")`:
     *
     *  - a field may be quoted, and inside quotes a comma is data;
     *  - inside quotes a doubled quote (`""`) is one literal quote;
     *  - inside quotes a newline is data — a Chrome `note` routinely contains
     *    one, and treating it as the end of the record shifts every following
     *    row by a column;
     *  - a quote appearing mid-field outside quotes is data (lenient), because
     *    passwords are arbitrary text and some exports do not escape them.
     *
     * An unterminated quote at the end of the file takes the rest of the text
     * as one field rather than failing: the alternative is rejecting a file
     * whose only defect is a truncation, when every complete row before it is
     * still importable.
     */
    private fun readRecords(text: String): List<List<String>> {
        val input = text.removePrefix("﻿")
        if (input.isBlank()) return emptyList()

        val records = ArrayList<List<String>>()
        var record = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        fun endField() {
            record.add(field.toString())
            field.setLength(0)
        }

        fun endRecord() {
            endField()
            records.add(record)
            record = ArrayList()
        }

        while (index < input.length) {
            val c = input[index]
            if (quoted) {
                when {
                    c == '"' && index + 1 < input.length && input[index + 1] == '"' -> {
                        field.append('"')
                        index++
                    }
                    c == '"' -> quoted = false
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    // Only a quote at the START of a field opens a quoted
                    // field. A quote further in is data — some exports write a
                    // password containing a quote without escaping it, and
                    // treating that as an opening quote would swallow every
                    // following record into one field.
                    '"' -> if (field.isEmpty()) quoted = true else field.append('"')
                    ',' -> endField()
                    '\r' -> {
                        // CRLF and a lone CR both end the record; the \n of a
                        // CRLF pair is consumed here so it cannot become an
                        // empty record of its own.
                        endRecord()
                        if (index + 1 < input.length && input[index + 1] == '\n') index++
                    }
                    '\n' -> endRecord()
                    else -> field.append(c)
                }
            }
            index++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) endRecord()
        return records
    }
}
