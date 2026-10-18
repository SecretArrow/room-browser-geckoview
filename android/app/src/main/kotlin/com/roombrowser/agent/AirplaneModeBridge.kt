package com.roombrowser.agent

/**
 * The airplane-mode surface the chat command and the agent tool share.
 *
 * ONE interface for both callers so the two cannot drift: `/airplane on` in
 * the composer and `airplane_mode action=set state=on` from the model are the
 * same promise made twice, and a bridge that answered them differently would
 * be two features wearing one name.
 *
 * WHY AN INTERFACE AT ALL. The real surface is Android system state (`su`,
 * [android.provider.Settings.Global], the settings screen), which a JVM test
 * cannot hold; the tests drive a fake over this seam instead, and the
 * controller's own wiring hands every caller the one real implementation.
 * Null — a chat or executor built without one — is the tool's and the
 * command's "not available in this chat" answer, never a silent no-op.
 *
 * The trust-but-verify contract: a switcher that reports success without
 * re-reading the state is guessing, so [setViaRoot] verifies by reading the
 * state back and the non-root paths poll the live state rather than trusting
 * the person's word for it.
 */
interface AirplaneModeBridge {

    /** Whether this device offers a su binary — memoized after the first read. */
    val rooted: Boolean

    /** The live state, or null when it cannot be read on this device. */
    fun currentState(): Boolean?

    /** The "Offer Airplane Mode shortcut" setting, read live. */
    suspend fun allowed(): Boolean

    /** Switches via root AND verifies by reading the state back. */
    suspend fun setViaRoot(on: Boolean): Boolean

    /** Opens the system Airplane Mode settings screen; false when this device has none. */
    fun openSettings(): Boolean

    /** Polls until the state is [on] or [timeoutMs] passes. */
    suspend fun waitUntil(on: Boolean, timeoutMs: Long): Boolean
}
