package com.roombrowser.domain.walletbackup

/** Which page of the delete flow is up. */
enum class WalletDeleteStage {
    /** Nothing shown. */
    IDLE,

    /** Never confirmed backed up: the stronger warning, with its own cancel. */
    BACKUP_WARNING,

    /** The confirmation every delete passes through, backed up or not. */
    CONFIRMATION
}

/**
 * The delete-wallet decision as pure state. No path reaches a delete without
 * passing through [WalletDeleteStage.CONFIRMATION].
 *
 * [backedUp] decides only WHICH page comes first, never whether one is shown:
 * a user who backed up still gets the confirmation, and one who did not gets
 * the warning and then the confirmation. A delete here is irreversible from
 * the app's side, so the ritual is not skipped on the strength of a claim the
 * app cannot verify.
 */
data class WalletDeleteState(
    val stage: WalletDeleteStage,
    val backedUp: Boolean
) {
    /** A delete press: the backup warning first when nothing was backed up. */
    fun requested(): WalletDeleteState = copy(
        stage = if (backedUp) WalletDeleteStage.CONFIRMATION else WalletDeleteStage.BACKUP_WARNING
    )

    /**
     * "Delete anyway" on the warning. It leads to the confirmation and never
     * straight to the delete, and it is inert anywhere else.
     */
    fun acceptedRisk(): WalletDeleteState =
        if (stage == WalletDeleteStage.BACKUP_WARNING) {
            copy(stage = WalletDeleteStage.CONFIRMATION)
        } else {
            this
        }

    /** Back out from either page. */
    fun cancelled(): WalletDeleteState = copy(stage = WalletDeleteStage.IDLE)

    /**
     * The single gate a delete may run behind. False in [IDLE] and, crucially,
     * false on the backup warning — "Delete anyway" only opens the
     * confirmation.
     */
    val deleteConfirmed: Boolean get() = stage == WalletDeleteStage.CONFIRMATION

    companion object {
        fun idle(backedUp: Boolean): WalletDeleteState =
            WalletDeleteState(WalletDeleteStage.IDLE, backedUp)
    }
}
