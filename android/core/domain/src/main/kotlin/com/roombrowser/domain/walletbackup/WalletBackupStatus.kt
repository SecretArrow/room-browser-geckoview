package com.roombrowser.domain.walletbackup

/**
 * How far the user has got with backing up the wallet's secrets.
 *
 * SESSION-SCOPED ON PURPOSE: nothing here is persisted, so backing up is
 * never remembered past the process. A status that survived a restart would
 * be a claim this app cannot check — the file it names may have been deleted
 * since — and the delete flow has to be able to say "this was never backed
 * up" when it cannot prove otherwise.
 */
enum class WalletBackupStatus {
    /** Nothing has been written down or exported. */
    NOT_BACKED_UP,

    /** The user stated the phrase is recorded ("I wrote it down"). */
    WRITTEN_DOWN,

    /** The keys were sealed into a file AND the file was written successfully. */
    EXPORTED;

    /**
     * True once the user has taken a copy of the secrets out of the flow.
     * Opening the export dialog is deliberately NOT enough: only [EXPORTED],
     * which is set after the file has actually been written, counts as one.
     */
    val isBackedUp: Boolean get() = this != NOT_BACKED_UP
}
