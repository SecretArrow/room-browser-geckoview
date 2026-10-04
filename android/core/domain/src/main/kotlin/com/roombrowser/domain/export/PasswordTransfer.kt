package com.roombrowser.domain.export

import com.roombrowser.domain.credentials.PasswordCsv
import com.roombrowser.domain.credentials.PasswordVaultCrypto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The file is not a password export, or is a version this build cannot read. */
class PasswordTransferFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Passwords export — the file a user writes before deleting a profile, or to
 * carry their logins to another phone.
 *
 * The sealed file is a small JSON envelope around a [ProfileBackup.VaultBackup];
 * once opened, the document is an ordinary `name,url,username,password` CSV —
 * the shape Chrome, Brave and Edge export and [PasswordCsv] already reads. So a
 * user who decrypts it by hand gets something useful, and "import from Room
 * Browser" and "import from Chrome" are one code path rather than two parsers
 * that can drift.
 *
 * [seal] always encrypts and this class never writes the plaintext CSV: a plain
 * `.csv` in Downloads is readable by any app with legacy storage permission and
 * is picked up by cloud backup.
 *
 * Only the host is stored for a login
 * ([com.roombrowser.domain.credentials.SavedCredential.domain]), so the exported
 * URL is `https://<host>` and the login page's path is not preserved.
 */
object PasswordTransfer {

    /**
     * One saved login, detached from the database. [password] is plaintext:
     * it lives in this shape only inside the encryption and in memory.
     */
    @Serializable
    data class Entry(
        val domain: String,
        val username: String,
        val password: String,
        val title: String? = null
    )

    data class Contents(val entries: List<Entry>) {
        /** True when [carryable] drops everything — sealing that would restore nothing. */
        val isEmpty: Boolean get() = carryable(entries).isEmpty()
    }

    /**
     * The entries this format can actually carry: a login with an empty password
     * is not one, because the CSV cell would be empty and [PasswordCsv] counts
     * that as incomplete on the way back in, making the file return fewer logins
     * than the export reported. `CredentialRepository.save` permits a domain
     * without a password, so the state is reachable.
     */
    fun carryable(entries: List<Entry>): List<Entry> = entries.filter { it.password.isNotEmpty() }

    const val KIND = "room-browser-passwords"

    /** 1 — this format's first version. No legacy reader: that format never shipped. */
    const val FORMAT_VERSION = 1

    /**
     * The file as written to disk. [kind] has no default because a profile
     * export also carries a `vault` block, so the discriminator is the only
     * thing separating the formats.
     */
    @Serializable
    data class PasswordFile(
        val kind: String,
        val formatVersion: Int = FORMAT_VERSION,
        val vault: ProfileBackup.VaultBackup
    )

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * The decrypted document: the export as a CSV this app and the other
     * browsers both read. Usernames and titles are trimmed because [PasswordCsv]
     * trims them on the way back in; passwords never are, and every value is
     * quoted when it holds a comma, a quote or a line break.
     */
    fun document(contents: Contents): String = buildString {
        appendLine("name,url,username,password")
        carryable(contents.entries).forEach { entry ->
            append(cell(entry.title?.trim().orEmpty()))
            append(',')
            append(cell("https://${entry.domain}"))
            append(',')
            append(cell(entry.username.trim()))
            append(',')
            append(cell(entry.password))
            appendLine()
        }
    }

    /**
     * Seals [contents] under [passphrase]; throws when nothing is carryable (see
     * [carryable]) so a file that restores nothing is never written.
     */
    fun seal(contents: Contents, passphrase: CharArray): String {
        require(passphrase.isNotEmpty()) { "A password export needs a passphrase" }
        require(!contents.isEmpty) { "This profile has no saved passwords to export" }
        val sealed = PasswordVaultCrypto.encrypt(document(contents), passphrase)
        return json.encodeToString(
            PasswordFile.serializer(),
            PasswordFile(kind = KIND, vault = ProfileBackup.VaultBackup.from(sealed))
        )
    }

    /**
     * Decrypts a file written by [seal] and reads the logins back out. The
     * document goes to [PasswordCsv], so our own reader is Chrome's reader.
     *
     * @throws PasswordTransferFormatException when [text] is not one of our files.
     * @throws com.roombrowser.domain.credentials.VaultAuthException when the
     *   passphrase is wrong or the ciphertext was tampered with.
     */
    fun open(text: String, passphrase: CharArray): Contents {
        val file = parseEnvelope(text) ?: throw PasswordTransferFormatException(
            if (text.isBlank()) "the file is empty" else "not a Room Browser password file"
        )
        val document = PasswordVaultCrypto.decrypt(file.vault.toCipherData(), passphrase)
        // document() wrote this, so it is a password CSV by construction; a
        // NotAPasswordCsv here means the file was forged. A format error, not an
        // empty profile.
        val parsed = PasswordCsv.parse(document) as? PasswordCsv.Result.Parsed
            ?: throw PasswordTransferFormatException("the decrypted contents are not a password list")
        return Contents(
            entries = parsed.rows.map {
                Entry(domain = it.domain, username = it.username, password = it.password, title = it.title)
            }
        )
    }

    /**
     * True when [text] is one of our sealed password files, so the import screen
     * asks for the passphrase before trying to read anything. A file of another
     * kind (a wallet backup) is not claimed here and falls through to the CSV
     * reader, which reports it as not a password file.
     */
    fun isSealedFile(text: String): Boolean = parseEnvelope(text) != null

    /**
     * The envelope when [text] is one of ours; null otherwise. One parser, so
     * `open` and `isSealedFile` cannot disagree about what our file is.
     */
    private fun parseEnvelope(text: String): PasswordFile? {
        if (!text.trimStart().startsWith("{")) return null
        val file = try {
            json.decodeFromString(PasswordFile.serializer(), text)
        } catch (e: SerializationException) {
            return null
        }
        if (file.kind != KIND) return null
        if (file.formatVersion > FORMAT_VERSION) {
            throw PasswordTransferFormatException(
                "this file was written by a newer Room Browser (format ${file.formatVersion})"
            )
        }
        return file
    }

    /**
     * One CSV cell, quoted when it holds a comma, quote or line break; an
     * unquoted `"abc` would open a quoted field.
     */
    private fun cell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /**
     * A filename that sorts by date and survives every filesystem: ASCII only,
     * no spaces or colons. Carries the profile label and the time so two
     * exports do not collide in a Downloads folder.
     */
    fun fileName(profileLabel: String, at: Long): String {
        val slug = profileLabel.lowercase(Locale.US)
            .map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .take(24)
        val stem = if (slug.isEmpty()) "passwords" else slug
        return "room-browser-passwords-$stem-${fileStamp(at)}.txt"
    }

    private fun fileStamp(at: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
}
