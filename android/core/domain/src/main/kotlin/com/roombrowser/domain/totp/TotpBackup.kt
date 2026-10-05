package com.roombrowser.domain.totp

import com.roombrowser.domain.credentials.PasswordVaultCrypto
import com.roombrowser.domain.export.ProfileBackup
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The file is not a two-factor export, or is a version this build cannot read. */
class TotpBackupFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Two-factor export — the file a user writes to carry their authenticator
 * accounts to another phone, or before deleting a profile.
 *
 * The envelope and its crypto are [com.roombrowser.domain.export.PasswordTransfer]'s:
 * a small JSON file whose only payload is a [ProfileBackup.VaultBackup] blob
 * sealed by [PasswordVaultCrypto] (PBKDF2-HMAC-SHA256, then AES-256-GCM). `kind`
 * is the only discriminator between the three content-routed formats, all of
 * which carry a `vault` block.
 *
 * The decrypted document lists each account — issuer, account, algorithm,
 * digits, period — but never its secret, which exists ONLY inside the structured
 * block at the bottom. That is a deliberate divergence from
 * [com.roombrowser.domain.export.WalletBackup], which prints the recovery phrase:
 * a TOTP import needs no hand-typed recovery, so a document that cannot leak
 * seed material is strictly safer to have on screen.
 *
 * Entries only: no profile id or name (the [Header] carries the profile label),
 * no `last_used_at`, no auth policy and no app state — those are the profile
 * export's business, not this file's.
 */
object TotpBackup {

    /** One account as the file carries it — detached from any profile or row id. */
    @Serializable
    data class Entry(
        val issuer: String,
        val account: String,
        val secret: String,
        val algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
        val digits: Int = 6,
        val period: Int = 30
    )

    data class Contents(val entries: List<Entry>) {
        /** Nothing here would restore anything — sealing it would write a decoy. */
        val isEmpty: Boolean get() = entries.isEmpty()
    }

    /** What the document says about itself. [profileLabel] is a NAME, never a profile id. */
    data class Header(val profileLabel: String, val exportedAt: Long)

    /** The structured half of the decrypted document; what [open] consumes. */
    @Serializable
    data class Payload(val entries: List<Entry> = emptyList())

    const val KIND = "room-browser-totp"

    /** 1 — this format's first version. */
    const val FORMAT_VERSION = 1

    /**
     * The file as written to disk. [kind] has no default because a profile export
     * also carries a `vault` block, so it is the only thing separating formats.
     */
    @Serializable
    data class TotpFile(
        val kind: String,
        val formatVersion: Int = FORMAT_VERSION,
        val vault: ProfileBackup.VaultBackup
    )

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private const val DATA_BEGIN = "-----BEGIN ROOM BROWSER TOTP DATA-----"
    private const val DATA_END = "-----END ROOM BROWSER TOTP DATA-----"

    /**
     * The decrypted document: a readable list of the accounts for a person, then
     * the block [open] reads. The secrets are in the block only.
     */
    fun document(contents: Contents, header: Header): String {
        val payload = json.encodeToString(Payload.serializer(), Payload(contents.entries))
        return buildString {
            appendLine("Room Browser - two-factor accounts")
            appendLine("Profile: ${header.profileLabel}")
            appendLine("Exported: ${stamp(header.exportedAt)}")
            appendLine("Accounts: ${contents.entries.size}")
            appendLine()
            // Deliberate divergence from WalletBackup: no secret is printed above
            // the block, so a screenshot of a decrypted document leaks none.
            appendLine("The secrets are inside the encrypted block below and are never")
            appendLine("shown in the clear. Room Browser needs the file to restore them,")
            appendLine("not a typed copy of the codes.")
            appendLine()
            if (contents.entries.isEmpty()) {
                appendLine("  (no accounts)")
            } else {
                contents.entries.forEach { entry ->
                    appendLine(
                        "  ${entry.issuer}  ${entry.account}  " +
                            "${entry.algorithm.uriValue}  ${entry.digits} digits  ${entry.period}s"
                    )
                }
            }
            appendLine()
            appendLine(DATA_BEGIN)
            payload.lineSequence().forEach { appendLine(it) }
            appendLine(DATA_END)
        }
    }

    /**
     * Seals [contents] under [passphrase]; throws when nothing is carryable so a
     * file that restores nothing is never written.
     */
    fun seal(contents: Contents, header: Header, passphrase: CharArray): String {
        require(passphrase.isNotEmpty()) { "A two-factor export needs a passphrase" }
        require(!contents.isEmpty) { "This profile has no authenticator accounts to export" }
        val sealed = PasswordVaultCrypto.encrypt(document(contents, header), passphrase)
        return json.encodeToString(
            TotpFile.serializer(),
            TotpFile(kind = KIND, vault = ProfileBackup.VaultBackup.from(sealed))
        )
    }

    /**
     * Decrypts a file written by [seal] and reads the accounts back out.
     *
     * @throws TotpBackupFormatException when [text] is not one of our files.
     * @throws com.roombrowser.domain.credentials.VaultAuthException when the
     *   passphrase is wrong or the ciphertext was tampered with.
     */
    fun open(text: String, passphrase: CharArray): Contents {
        val file = parseEnvelope(text) ?: throw TotpBackupFormatException(
            if (text.isBlank()) "the file is empty" else "not a Room Browser two-factor file"
        )
        val document = PasswordVaultCrypto.decrypt(file.vault.toCipherData(), passphrase)
        val begin = document.indexOf(DATA_BEGIN)
        val end = if (begin < 0) -1 else document.indexOf(DATA_END, startIndex = begin)
        if (begin < 0 || end < 0) {
            throw TotpBackupFormatException("the decrypted file has no account data")
        }
        val body = document.substring(begin + DATA_BEGIN.length, end).trim()
        val payload = try {
            json.decodeFromString(Payload.serializer(), body)
        } catch (e: SerializationException) {
            throw TotpBackupFormatException("the account data is damaged", e)
        }
        return Contents(payload.entries)
    }

    /**
     * True when [text] is one of our sealed files, so the import screen asks for
     * the passphrase before trying to read anything. A password or wallet file
     * carries another `kind` and is not claimed here.
     */
    fun isSealedFile(text: String): Boolean = parseEnvelope(text) != null

    /**
     * The envelope when [text] is one of ours; null otherwise. One parser, so
     * [open] and [isSealedFile] cannot disagree about what our file is.
     */
    private fun parseEnvelope(text: String): TotpFile? {
        if (!text.trimStart().startsWith("{")) return null
        val file = try {
            json.decodeFromString(TotpFile.serializer(), text)
        } catch (e: SerializationException) {
            return null
        }
        if (file.kind != KIND) return null
        if (file.formatVersion > FORMAT_VERSION) {
            throw TotpBackupFormatException(
                "this file was written by a newer Room Browser (format ${file.formatVersion})"
            )
        }
        return file
    }

    /**
     * A filename that sorts by date and survives every filesystem: ASCII only,
     * no spaces or colons. Carries the profile label and the time so two exports
     * do not collide in a Downloads folder.
     */
    fun fileName(profileLabel: String, at: Long): String {
        val slug = profileLabel.lowercase(Locale.US)
            .map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .take(24)
        val stem = if (slug.isEmpty()) "totp" else slug
        return "room-browser-totp-$stem-${fileStamp(at)}.txt"
    }

    /** Fixed shape and locale: the file is read on a machine that is not this phone. */
    private fun stamp(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at))

    private fun fileStamp(at: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
}
