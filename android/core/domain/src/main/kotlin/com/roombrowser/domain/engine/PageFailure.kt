package com.roombrowser.domain.engine

/**
 * Why a main-frame load failed, decided once for both editions.
 *
 * Each engine reports failures in its own vocabulary — WebView's ERROR_* ints,
 * GeckoView's WebRequestError.Error enum — so each edition maps its own codes
 * onto this, and the one call that needs a fact the engine does NOT have lives
 * here: whether the device itself is online. Without it a socket that never
 * connected over a perfectly good connection is reported as "No Internet",
 * which is the most common way a browser blames the user's network for a
 * server's silence.
 */
enum class PageFailure {
    /** The device has no working connection; nothing on the network is reachable. */
    OFFLINE,

    /** The device is online and this site's name resolved, but it never answered. */
    UNREACHABLE,

    /** The device is online, but this site's name did not resolve. */
    DNS;

    companion object {
        /**
         * @param hostLookup the engine failed to RESOLVE the name
         * @param deviceOnline the device currently holds a connection that
         *   reports itself as reaching the internet
         */
        fun of(hostLookup: Boolean, deviceOnline: Boolean): PageFailure = when {
            !deviceOnline -> OFFLINE
            hostLookup -> DNS
            else -> UNREACHABLE
        }
    }
}
