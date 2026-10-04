package com.roombrowser.domain.credentials

/**
 * Reads the password file other browsers export.
 *
 * Chrome, Brave, Edge and Firefox only ever hand a user their passwords as a
 * CSV, and Android gives no way to read another app's store, so the import path
 * is a file the user exported themselves.
 *
 * Columns are found by NAME, never by position: Chrome writes
 * `name,url,username,password,note` where Firefox writes
 * `url,username,password,httpRealm,...`, and a positional reader would import
 * `httpRealm` as a password. Unknown columns are ignored, not rejected.
 *
 * Rows that cannot become a login are counted as skipped, never dropped
 * silently: a Firefox `moz_ciphertext` row (encrypted with a primary password,
 * so those bytes are not a password) and a row with no usable http/https URL
 * (Chrome's `android://` app rows, Firefox's `about:`/`moz-extension:`
 * origins). The screen reports the counts so the user does not delete the CSV
 * believing everything was imported.
 */
object PasswordCsv {

    data class Row(
        /** Canonical host ([CredentialDomainMatcher.normalize]); never blank. */
        val domain: String,
        val username: String,
        val password: String,
        val title: String?
    )

    enum class SkipReason {
        /** Firefox `moz_ciphertext`: the password is encrypted, not text. */
        ENCRYPTED,

        NO_URL,

        /** A URL, but not http/https: `about:`, `moz-extension:`, `chrome:`. */
        UNSUPPORTED_URL,

        INCOMPLETE
    }

    sealed interface Result {
        /** The file was a password CSV; [rows] plus [skipped] account for every data record. */
        data class Parsed(
            val rows: List<Row>,
            val skipped: Map<SkipReason, Int>
        ) : Result {
            val skippedTotal: Int get() = skipped.values.sum()
        }

        /**
         * No column this importer recognises — a wrong file, kept distinct from
         * "0 imported" so it is not reported as an empty success.
         */
        data object NotAPasswordCsv : Result
    }

    // Column names, lowercased and stripped of spaces/underscores; first
    // present column wins.
    private val URL_COLUMNS = listOf("url", "originurl", "formactionorigin", "loginuri")
    private val USERNAME_COLUMNS = listOf("username", "login", "user")
    private val PASSWORD_COLUMNS = listOf("password", "pass", "loginpassword")
    private val CIPHERTEXT_COLUMNS = listOf("mozciphertext")
    private val TITLE_COLUMNS = listOf("name", "title")

    /**
     * Parse [text] as a password CSV. A UTF-8 BOM, CRLF or LF endings and a
     * missing final newline are all accepted.
     */
    fun parse(text: String): Result {
        val records = readRecords(text)
        if (records.isEmpty()) return Result.NotAPasswordCsv

        val header = records.first().map { normaliseHeader(it) }
        // Every URL-ish column, in preference order: a Firefox login can have an
        // empty `url` and only `formActionOrigin`, so the host is resolved per row.
        val urlColumns = URL_COLUMNS.map { name -> header.indexOf(name) }.filter { it >= 0 }
        val passwordColumn = header.indexOfFirst { it in PASSWORD_COLUMNS }
        val ciphertextColumn = header.indexOfFirst { it in CIPHERTEXT_COLUMNS }
        // No URL column, or neither a password nor a ciphertext column: not a
        // password file. Everything else is optional.
        if (urlColumns.isEmpty() || (passwordColumn < 0 && ciphertextColumn < 0)) {
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
            // A trailing newline and a blank line both produce an empty record;
            // neither is a row, so neither is counted as skipped.
            if (record.all { it.isBlank() }) continue

            val host = hostOf(record, urlColumns)
            if (host == null) {
                // NO_URL only when every URL column is empty: a row holding a
                // `moz-extension:` origin did say where it goes, just not to
                // the web.
                val heldSomething = urlColumns.any { record.getOrNull(it).orEmpty().isNotBlank() }
                skip(if (heldSomething) SkipReason.UNSUPPORTED_URL else SkipReason.NO_URL)
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
                domain = host,
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
     * The first usable host among [record]'s URL columns, or null when none
     * holds an http/https URL.
     */
    private fun hostOf(record: List<String>, urlColumns: List<Int>): String? {
        for (column in urlColumns) {
            val host = webHostOf(record.getOrNull(column).orEmpty().trim())
            if (host != null) return host
        }
        return null
    }

    /**
     * The host of [raw] when it is an http/https URL, else null. `URI` returns a
     * host even for `about:` and `moz-extension:` origins, which no page can match.
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
     * Split [text] into records of fields, RFC 4180 style: a quoted field may
     * hold commas, doubled quotes and newlines (a Chrome `note` has them, and
     * misreading one shifts every following row by a column). An unterminated
     * final quote takes the rest as one field rather than rejecting the file.
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
                    // field; a password with an unescaped quote is data, or it
                    // would swallow every following record into one field.
                    '"' -> if (field.isEmpty()) quoted = true else field.append('"')
                    ',' -> endField()
                    '\r' -> {
                        // CRLF and a lone CR both end the record; the \n of a
                        // pair is consumed here so it cannot become an empty
                        // record of its own.
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
