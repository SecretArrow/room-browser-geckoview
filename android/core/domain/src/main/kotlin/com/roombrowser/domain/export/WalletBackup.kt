package com.roombrowser.domain.export

import com.roombrowser.domain.credentials.PasswordVaultCrypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The file is not a wallet-keys export, or is a version this build cannot read. */
class WalletBackupFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Wallet keys export — what a user saves so their coins survive losing the
 * phone.
 *
 * ## Why a separate file, and not a field in [ProfileBackup]
 *
 * A profile export is a shareable thing: users send one to themselves, or to
 * a friend, to move bookmarks and settings around. Putting the seed in it
 * would mean every one of those files silently carries the keys to every
 * coin in the wallet. Keeping the two apart means the blast radius of a
 * carelessly shared profile backup is a set of bookmarks, and the file that
 * holds the money is one the user deliberately chose to write.
 *
 * ## Why the seed is never written in the clear
 *
 * The obvious reading of "export my seed phrase to a text file" is a
 * plaintext `.txt`. That file then lands in Downloads, gets picked up by
 * cloud backup, is readable by every app holding legacy storage permission,
 * and survives deletion on flash storage — for a secret that is
 * unrecoverable once seen. So the file this class writes is a small JSON
 * envelope whose ONLY payload is a [ProfileBackup.VaultBackup] blob, sealed
 * by [PasswordVaultCrypto] under a passphrase the user picks at export time
 * (PBKDF2-HMAC-SHA256, 210 000 iterations, then AES-256-GCM — authenticated,
 * so a wrong passphrase is a clean error rather than garbage).
 *
 * What the user gets after decrypting is exactly the readable keys document
 * they asked for: [render] is the plaintext, and it is the plaintext and
 * nothing else that goes inside the cipher.
 *
 * ## What is inside
 *
 * The recovery phrase restores every DERIVED account — they are re-derived
 * from it, so the phrase alone is a complete backup of them. Imported
 * private keys are not derived from anything, so they are carried
 * explicitly; a backup that omitted them would restore a wallet that looks
 * right and has no access to those funds.
 *
 * ## On [open]
 *
 * The read half exists now, before there is any import UI, because a backup
 * format that has never been read back has never been shown to work. The
 * export tests decrypt through this method rather than through the raw
 * cipher, so what they prove is that THIS file can be opened by THIS code.
 */
object WalletBackup {

    /** Set of a plaintext [Contents]; the plaintext is the artifact the user reads. */
    @Serializable
    data class KeyEntry(
        val chain: String,
        val label: String,
        val address: String,
        /** BIP44 path for derived accounts; empty for imports. */
        val path: String,
        /** Present only for imported accounts — the phrase cannot re-derive these. */
        val privateKey: String? = null
    )

    /**
     * Everything a restore needs, already detached from the database.
     *
     * [mnemonic] is null for a wallet that was created by importing single
     * keys rather than by a phrase; such a wallet's only backup is its
     * imported keys.
     */
    data class Contents(
        val walletLabel: String,
        val createdAt: Long,
        val mnemonic: String?,
        val accounts: List<KeyEntry>
    ) {
        /** Nothing here could restore anything — sealing it would write a decoy. */
        val isEmpty: Boolean
            get() = mnemonic.isNullOrBlank() && accounts.none { !it.privateKey.isNullOrBlank() }
    }

    /** What the document says about itself. [profileLabel] is a NAME, never a profile id. */
    data class Header(val profileLabel: String, val exportedAt: Long)

    /**
     * The machine-readable half of a v2 document — what an IMPORT consumes.
     *
     * It is [Contents] plus the wallet label, which [Contents] already
     * carries, so this is exactly the same shape: keeping one type means a
     * restore cannot drift from an export, and the round-trip test that pins
     * `render`/`parseRendered` covers the structured path too.
     */
    @Serializable
    data class Payload(
        val walletLabel: String,
        val createdAt: Long,
        val mnemonic: String? = null,
        val accounts: List<KeyEntry> = emptyList()
    ) {
        fun toContents(): Contents = Contents(walletLabel, createdAt, mnemonic, accounts)

        companion object {
            fun from(contents: Contents): Payload =
                Payload(contents.walletLabel, contents.createdAt, contents.mnemonic, contents.accounts)
        }
    }

    /**
     * What [open] hands back: the wallet's contents, the readable document
     * they were read from, and how they were read.
     *
     * [document] is the decrypted plaintext exactly as the user would see it
     * if they opened the file themselves — the keys in a form they can read
     * off the screen. It is returned rather than discarded because it is what
     * the format promises, and because an import screen that can show the user
     * what it just read is an import screen whose user can tell a wrong
     * passphrase from a wrong file. It is NOT kept anywhere after the caller
     * is done with it.
     *
     * [legacy] says the file predates the structured block and was recovered
     * by parsing the readable document. The import UI reports that, because a
     * legacy file can lose information this build would have kept (see
     * [parseRendered]) and the user deserves to know before the restore runs,
     * not after.
     */
    data class Restored(
        val payload: Payload,
        val legacy: Boolean,
        val document: String
    )

    const val KIND = "room-browser-wallet-keys"

    /**
     * 2 since the structured block was added. [open] still reads 1.
     *
     * The bump is not a breaking change: a v1 file opens here, and a v2 file
     * is REFUSED by an older build with the "written by a newer Room Browser"
     * message below — which is the correct outcome, since silently ignoring
     * the structured block would import a wallet that is missing its imported
     * keys.
     */
    const val FORMAT_VERSION = 2

    /**
     * The file, as written to disk. [vault] is non-null by construction: an
     * export with nothing to seal is refused by [seal] rather than written
     * as a file that restores nothing.
     *
     * [kind] deliberately has NO default. A profile export carries a `vault`
     * block too, so `kind` is the only thing separating the two formats — and
     * a defaulted discriminator is no discriminator at all: a file that
     * simply omits the field would decode to the default and be accepted,
     * which for an import means reading a bookmark list as a seed phrase.
     */
    @Serializable
    data class KeyFile(
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
     * Fence markers around the machine-readable block.
     *
     * WHY A FENCE INSIDE THE READABLE DOCUMENT, rather than replacing the
     * document with JSON: the promise this format makes to the user is that
     * decrypting the file gives them the keys they asked for, in a form they
     * can read off the screen and type into another wallet. Sealing a JSON
     * blob instead would keep that promise only for people who are happy
     * reading JSON. So the document stays exactly what it was, and the block
     * that an import consumes is appended below it — a restore reads the
     * block, a human reads the text, and neither has to care about the other.
     *
     * The markers are PEM's shape on purpose: base64/JSON line blocks fenced
     * this way are a convention every tool already knows to leave alone, and
     * the `BEGIN`/`END` pair makes a truncated file detectable rather than
     * silently parsed as if it were complete.
     */
    private const val DATA_BEGIN = "-----BEGIN ROOM BROWSER WALLET DATA-----"
    private const val DATA_END = "-----END ROOM BROWSER WALLET DATA-----"

    /**
     * The decrypted document: what the user reads once they open the file
     * with the passphrase they set, followed by the block an import reads.
     */
    fun document(contents: Contents, header: Header): String {
        val payload = json.encodeToString(Payload.serializer(), Payload.from(contents))
        return buildString {
            append(render(contents, header))
            appendLine()
            appendLine(DATA_BEGIN)
            appendLine("The block below is what Room Browser reads when you import this file.")
            appendLine("Everything above it is for you. Both are inside the same encryption.")
            payload.lineSequence().forEach { appendLine(it) }
            appendLine(DATA_END)
        }
    }

    /**
     * The decrypted document: what the user reads once they open the file
     * with the passphrase they set.
     */
    fun render(contents: Contents, header: Header): String = buildString {
        appendLine("Room Browser - wallet keys")
        appendLine("Wallet: ${contents.walletLabel}")
        appendLine("Profile: ${header.profileLabel}")
        appendLine("Created: ${stamp(contents.createdAt)}")
        appendLine("Exported: ${stamp(header.exportedAt)}")
        appendLine()
        appendLine("KEEP THIS FILE OFFLINE. Anyone who can read it can take every coin")
        appendLine("in this wallet. Room Browser cannot recover it for you and cannot")
        appendLine("reset the password it is sealed with.")

        val words = contents.mnemonic?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
        if (words.isNullOrEmpty()) {
            appendLine()
            appendLine("Recovery phrase: none - this wallet was created by importing keys,")
            appendLine("so the imported keys below are its only backup.")
        } else {
            appendLine()
            appendLine("Recovery phrase (${words.size} words) - restores every derived account")
            words.forEachIndexed { index, word ->
                appendLine("  ${(index + 1).toString().padStart(2)}. $word")
            }
        }

        val derived = contents.accounts.filter { it.privateKey.isNullOrBlank() }
        val imported = contents.accounts.filter { !it.privateKey.isNullOrBlank() }

        appendLine()
        if (derived.isEmpty()) {
            appendLine("Derived accounts: none")
        } else {
            appendLine("Derived accounts - re-derived from the phrase above, listed for reference")
            derived.forEach { entry ->
                appendLine("  ${entry.chain}  ${entry.label}  ${entry.address}  ${entry.path}")
            }
        }

        if (imported.isNotEmpty()) {
            appendLine()
            appendLine("Imported keys - NOT restored by the recovery phrase")
            imported.forEach { entry ->
                appendLine("  ${entry.chain}  ${entry.label}  ${entry.address}")
                appendLine("    private key: ${entry.privateKey}")
            }
        }
    }

    /**
     * Seals [contents] under [passphrase] and returns the envelope alone.
     *
     * This is what a profile export embeds as its `wallet` block: a profile
     * backup has a format of its own, so the keys ride inside it as ciphertext
     * rather than as a second file the user has to keep paired with the first.
     *
     * @throws IllegalArgumentException when [contents] holds nothing that
     *   could restore a wallet — see [Contents.isEmpty].
     */
    fun sealBlock(contents: Contents, header: Header, passphrase: CharArray): ProfileBackup.VaultBackup {
        require(passphrase.isNotEmpty()) { "A wallet backup needs a passphrase" }
        require(!contents.isEmpty) {
            "This wallet has no recovery phrase and no imported keys to back up"
        }
        return ProfileBackup.VaultBackup.from(
            PasswordVaultCrypto.encrypt(document(contents, header), passphrase)
        )
    }

    /** Seals [contents] under [passphrase] and returns the standalone file text. */
    fun seal(contents: Contents, header: Header, passphrase: CharArray): String =
        json.encodeToString(
            KeyFile.serializer(),
            KeyFile(kind = KIND, vault = sealBlock(contents, header, passphrase))
        )

    /**
     * Decrypts a block written by [sealBlock].
     *
     * @throws com.roombrowser.domain.credentials.VaultAuthException when the
     *   passphrase is wrong or the ciphertext was tampered with.
     */
    fun openBlock(block: ProfileBackup.VaultBackup, passphrase: CharArray): Restored =
        readPlaintext(PasswordVaultCrypto.decrypt(block.toCipherData(), passphrase))

    /**
     * Decrypts a file written by [seal] or by any earlier build.
     *
     * @throws WalletBackupFormatException when [text] is not one of our files.
     * @throws com.roombrowser.domain.credentials.VaultAuthException when the
     *   passphrase is wrong or the ciphertext was tampered with.
     */
    fun open(text: String, passphrase: CharArray): Restored {
        if (text.isBlank()) throw WalletBackupFormatException("the file is empty")
        val file = try {
            json.decodeFromString(KeyFile.serializer(), text)
        } catch (e: SerializationException) {
            // kotlinx messages can be multi-line; the first line is the useful one.
            throw WalletBackupFormatException(
                e.message?.lineSequence()?.firstOrNull() ?: "not a wallet keys file",
                e
            )
        }
        if (file.kind != KIND) {
            throw WalletBackupFormatException("not a wallet keys file (kind=${file.kind})")
        }
        if (file.formatVersion > FORMAT_VERSION) {
            throw WalletBackupFormatException(
                "this file was written by a newer Room Browser (format ${file.formatVersion})"
            )
        }
        val plaintext = PasswordVaultCrypto.decrypt(file.vault.toCipherData(), passphrase)
        return readPlaintext(plaintext)
    }

    /**
     * Splits a decrypted document into the structured payload an import
     * consumes — the second half of the v1/v2 story, and the half a test can
     * pin without a passphrase or a cipher.
     *
     * A file this build wrote carries the fenced block; anything else is a
     * v1 document and goes through [parseRendered]. The two are told apart by
     * the begin marker alone, so a document whose block was truncated by hand
     * is caught as a format error rather than half-parsed.
     */
    fun readPlaintext(plaintext: String): Restored {
        val begin = plaintext.indexOf(DATA_BEGIN)
        if (begin < 0) {
            return Restored(parseRendered(plaintext), legacy = true, document = plaintext)
        }
        val end = plaintext.indexOf(DATA_END, startIndex = begin)
            .takeIf { it >= 0 }
            ?: throw WalletBackupFormatException(
                "the readable part is intact but the data block below it was cut off"
            )
        val body = plaintext
            .substring(begin + DATA_BEGIN.length, end)
            .lineSequence()
            // Skip the two prose lines between the marker and the JSON: they
            // are addressed to the reader, and JSON cannot hold them.
            .dropWhile { !it.trimStart().startsWith("{") }
            .joinToString("\n")
            .trim()
        val payload = try {
            json.decodeFromString(Payload.serializer(), body)
        } catch (e: SerializationException) {
            throw WalletBackupFormatException(
                "the data block is damaged (${e.message?.lineSequence()?.firstOrNull() ?: "unreadable"})",
                e
            )
        }
        return Restored(payload, legacy = false, document = plaintext)
    }

    /**
     * The human-readable half of a decrypted document: everything above the
     * fenced data block.
     *
     * For a screen that shows the user what it just read, the block is noise
     * — it is the same keys again, in JSON, addressed to the importer. The
     * fence markers stay private to this file so no caller has to know what
     * they are to show the part a person reads.
     */
    fun readablePart(document: String): String {
        val begin = document.indexOf(DATA_BEGIN)
        return if (begin < 0) document else document.substring(0, begin).trimEnd()
    }

    /**
     * Recovers contents from a v1 document by reading the text the user
     * reads.
     *
     * WHY THIS EXISTS AT ALL: exports shipped before the fenced block, and
     * those files are the only backup some users have. Refusing them would
     * turn a format improvement into data loss, so the old shape is parsed
     * rather than dropped.
     *
     * WHY IT IS EXACT RATHER THAN HEURISTIC: this parses the output of
     * [render] and nothing else, and [render]'s grammar is ours — a label
     * line per field, a numbered word list, and `chain  label  address` rows
     * where the address is the last whitespace-free field. The round-trip
     * test in WalletBackupTest renders a Contents and parses it back to the
     * same value, which is the only claim being made here.
     *
     * A document that carries no recognisable phrase and no imported keys is
     * rejected: rendering a restore from nothing would create an empty wallet
     * and report success.
     */
    fun parseRendered(plaintext: String): Payload {
        val lines = plaintext.lines()

        fun field(name: String): String? = lines
            .firstOrNull { it.startsWith("$name: ") }
            ?.removePrefix("$name: ")

        // The word list is the numbered block under "Recovery phrase (N
        // words)"; the index prefix is what makes it unambiguous, so a stray
        // "12. something" in a label cannot be mistaken for a word.
        val mnemonic = run {
            val header = lines.indexOfFirst { it.startsWith("Recovery phrase (") }
            if (header < 0) null else lines
                .drop(header + 1)
                .takeWhile { it.startsWith("  ") }
                .mapNotNull { line ->
                    line.trim().substringAfter(". ", "").takeIf { it.isNotBlank() }
                }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
        }

        val accounts = mutableListOf<KeyEntry>()
        lines.forEachIndexed { index, line ->
            val key = line.removePrefix("    private key: ").takeIf { line.startsWith("    private key: ") }
                ?: return@forEachIndexed
            // The row above carries chain, label and address; the address is
            // the last field and holds no spaces, so splitting from the right
            // survives a label that does.
            val row = lines.getOrNull(index - 1)?.trim().orEmpty()
            val address = row.substringAfterLast("  ", "").trim()
            val head = row.substringBeforeLast("  ", "").trim()
            val chain = head.substringBefore(' ').trim()
            val label = head.substringAfter(' ', "").trim()
            accounts.add(
                KeyEntry(
                    chain = chain,
                    label = label.ifBlank { chain },
                    address = address,
                    path = "",
                    privateKey = key.trim()
                )
            )
        }

        // Derived accounts are recomputed from the phrase, so they are listed
        // for the reader only — carrying them into a restore would duplicate
        // rows the derivation is about to create. Only imported keys, which
        // nothing can re-derive, are taken from a v1 document.
        val walletLabel = field("Wallet") ?: "Wallet"
        if (mnemonic.isNullOrBlank() && accounts.isEmpty()) {
            throw WalletBackupFormatException(
                "this file holds no recovery phrase and no imported keys"
            )
        }
        return Payload(
            walletLabel = walletLabel,
            createdAt = System.currentTimeMillis(),
            mnemonic = mnemonic,
            accounts = accounts
        )
    }

    /**
     * A filename that sorts by date and survives every filesystem: no spaces,
     * no colons, ASCII only. Carries the wallet's own label so two wallets
     * backed up on the same day do not collide in a Downloads folder, and the
     * time so two exports of one wallet do not either.
     */
    fun fileName(walletLabel: String, at: Long): String {
        val slug = walletLabel.lowercase(Locale.US)
            .map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .take(24)
        val stem = if (slug.isEmpty()) "wallet" else slug
        return "room-browser-wallet-keys-$stem-${fileStamp(at)}.txt"
    }

    /** Fixed shape and locale: the file is read on a machine that is not this phone. */
    private fun stamp(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at))

    private fun fileStamp(at: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
}
