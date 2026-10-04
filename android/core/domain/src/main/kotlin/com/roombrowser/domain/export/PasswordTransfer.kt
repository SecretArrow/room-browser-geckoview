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
 * ## The decrypted document is a plain CSV, on purpose
 *
 * The sealed file is a small JSON envelope whose only payload is a
 * [ProfileBackup.VaultBackup] blob; what that blob holds, once the user's
 * passphrase opens it, is an ordinary `name,url,username,password` CSV — the
 * shape Chrome, Brave and Edge export and the shape [PasswordCsv] already reads.
 *
 * Two things fall out of that choice, and they are why it is worth the
 * indirection of having a payload format rather than a bespoke one:
 *
 *  - **A user who decrypts this file by hand gets something useful.** They can
 *    read it, and they can feed it straight to Chrome's or Brave's importer.
 *    A file that only Room Browser could read would be a worse promise for the
 *    same encryption.
 *  - **"Import from Room Browser" and "import from Chrome" are one code path.**
 *    The only difference between them is whether the text needed a passphrase
 *    first. There is no second parser to keep correct, and no way for the two
 *    to drift.
 *
 * It also means the document needs no fence markers or companion prose the way
 * [WalletBackup]'s does: a CSV is already both the human-readable form and the
 * machine-readable one, so the two halves [WalletBackup] has to reconcile are
 * here the same bytes.
 *
 * ## Why it is sealed rather than written in the clear
 *
 * [seal] always encrypts, and the plaintext CSV is never written to disk by
 * this class. A plain `.csv` in Downloads is readable by every app holding
 * legacy storage permission, is picked up by cloud backup, and survives
 * deletion on flash — for a file that is, by construction, a list of every
 * password the user has. The screen offers the plaintext form as well, because
 * the user may genuinely want it, but it is a separate deliberate act with its
 * own warning rather than the default.
 *
 * ## What an export cannot carry
 *
 * Only the host is stored for a login
 * ([com.roombrowser.domain.credentials.SavedCredential.domain]), so the
 * exported URL is `https://<host>` and the login page's path is not preserved.
 * A round trip through another browser lands the login on the site rather than
 * on the exact page it was saved from, which is where autofill would have
 * offered it anyway.
 */
object PasswordTransfer {

    /**
     * One saved login, detached from the database.
     *
     * [password] is plaintext: it exists in this shape only inside the
     * encryption, and in memory for the moment an export or an import runs.
     */
    @Serializable
    data class Entry(
        val domain: String,
        val username: String,
        val password: String,
        val title: String? = null
    )

    /** Everything a restore needs. */
    data class Contents(val entries: List<Entry>) {
        /** Nothing to export — sealing this would write a file that restores nothing. */
        val isEmpty: Boolean get() = carryable(entries).isEmpty()
    }

    /**
     * The entries this format can actually carry.
     *
     * A login with an empty password is NOT one. The CSV cell for it would be
     * empty, [PasswordCsv] counts such a row as incomplete on the way back in
     * (rightly — nothing can match a login with no password), and so writing it
     * would produce a file whose re-import returns fewer logins than the export
     * said it wrote. That is the silent partial transfer this whole feature is
     * designed against, and the export is the last point at which the count is
     * still visible to the user, so the decision belongs here rather than in the
     * reader.
     *
     * This is not a hypothetical state: `CredentialRepository.save` requires a
     * domain but not a password, so a row with an empty one can be in the store.
     * Callers compare the size of the result against the size of what they asked
     * to export, and report the difference.
     */
    fun carryable(entries: List<Entry>): List<Entry> = entries.filter { it.password.isNotEmpty() }

    const val KIND = "room-browser-passwords"

    /**
     * 1 — this format's first version.
     *
     * There is deliberately no legacy-reading branch the way [WalletBackup] has
     * one: that format had shipped before it changed shape, and this one has not.
     * Building a reader for a past that does not exist would be untested code
     * guarding nothing.
     */
    const val FORMAT_VERSION = 1

    /**
     * The file as written to disk. [vault] is non-null by construction: [seal]
     * refuses an empty export rather than writing a file that restores nothing.
     *
     * [kind] has no default, for the reason [WalletBackup.KeyFile] gives: a
     * profile export also carries a `vault` block, so `kind` is the only thing
     * separating the formats, and a defaulted discriminator is no discriminator
     * — a file that omitted the field would decode to the default and be
     * accepted, which for an import means reading a bookmark list as a list of
     * passwords and offering to save them.
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
     * The decrypted document: the export as a CSV that this app and the other
     * browsers can both read.
     *
     * Usernames and titles are written trimmed because [PasswordCsv] trims them
     * on the way back in — for our own file exactly as for Chrome's — so writing
     * them trimmed is what makes the round trip exact rather than
     * approximately exact. Passwords are never trimmed: a leading or trailing
     * space is part of a password, and every value is quoted when it holds a
     * comma, a quote or a line break, so nothing in a password can escape its
     * cell.
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
     * Seals [contents] under [passphrase] and returns the file text.
     *
     * @throws IllegalArgumentException when there is nothing carryable — see
     *   [carryable] — so a file that restores nothing is never written.
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
     * Decrypts a file written by [seal] and reads the logins back out of it.
     *
     * The document is handed to [PasswordCsv], so the reader for our own file is
     * the reader for Chrome's — if this ever fails to read something [document]
     * wrote, that is a bug in one of those two and it shows up here first.
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
        // The document was written by document(), so it is a password CSV by
        // construction; a NotAPasswordCsv here means the file was tampered with
        // or forged, and GCM has already rejected that. Treated as a format
        // error rather than an empty success so a broken file cannot look like
        // an empty profile.
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
     * knows to ask for a passphrase before it tries to read anything.
     *
     * Decided on the raw text, before any passphrase exists — which is the point:
     * the screen's first question ("this file is encrypted; what is the
     * passphrase?") has to be asked before it can ask the second one.
     *
     * A CSV never begins with `{`, so the fast path rejects the common case
     * without parsing. A file that is one of our envelopes but a different kind
     * — a wallet backup, say — is NOT claimed here; it falls through to the CSV
     * reader, which reports it as not a password file, which is true and is the
     * right thing to tell the user.
     */
    fun isSealedFile(text: String): Boolean = parseEnvelope(text) != null

    /**
     * The envelope, when [text] is one of ours; null otherwise. One place parses
     * it, so `open` and `isSealedFile` cannot disagree about what our file is.
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
     * One CSV cell: quoted when it holds anything that would otherwise be read
     * as structure.
     *
     * A quote ANYWHERE forces quoting, not just at the start. `PasswordCsv`
     * treats a quote at the start of a field as opening a quoted field, so an
     * unquoted password like `"abc` would swallow the rest of the file — the
     * value has to be wrapped and its own quotes doubled.
     */
    private fun cell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /**
     * A filename that sorts by date and survives every filesystem: no spaces,
     * no colons, ASCII only. Carries the profile's label so two profiles
     * exported on the same day do not collide in a Downloads folder, and the
     * time so two exports of one profile do not either.
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
