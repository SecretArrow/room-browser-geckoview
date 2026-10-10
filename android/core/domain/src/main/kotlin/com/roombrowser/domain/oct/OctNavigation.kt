package com.roombrowser.domain.oct

/**
 * What a navigation address means to the circle reader. No engine renders `oct://`, so a
 * valid circle is served from its node and a malformed one is refused, never loaded.
 */
sealed interface OctNavigation {

    /** Serve [uri] from its circle, never from the engine. */
    data class Circle(val uri: OctUri) : OctNavigation

    /** An `oct://` address that names no circle: refused, not loaded. */
    object Malformed : OctNavigation

    /** Not a circle: the engine loads it as it always has. */
    object PassThrough : OctNavigation

    companion object {

        /** Pure, so the routing decision is proved without a device; [OctUri.PREFIX] is
         *  the same test the omnibox classifier uses. */
        fun classify(url: String?): OctNavigation {
            val trimmed = url?.trim() ?: return PassThrough
            if (!trimmed.lowercase().startsWith(OctUri.PREFIX)) return PassThrough
            return OctUri.parse(trimmed)?.let { Circle(it) } ?: Malformed
        }
    }
}
