package com.roombrowser.domain.download

/** The folder downloads land in when the profile has not named a usable one. */
const val DEFAULT_DOWNLOAD_SUBFOLDER = "RoomBrowser"

/** Long enough for a readable name, short enough to stay a path segment. */
private const val MAX_DOWNLOAD_SUBFOLDER_LENGTH = 64

/**
 * Turns the profile's download folder into a single safe path segment.
 *
 * The value is user text (and the agent can write it), and it is concatenated
 * into a path under Downloads, so anything but a plain name is dropped rather
 * than escaped: a separator or a `..` would let the folder the user picked be
 * left, and a leading dot would hide it. Sanitising here rather than at the
 * settings row is the point — the row is one of several writers.
 *
 * A value with nothing left in it falls back to [DEFAULT_DOWNLOAD_SUBFOLDER],
 * so a download is never refused over a folder name.
 */
fun sanitiseDownloadSubfolder(raw: String): String {
    val flattened = raw.replace('/', ' ').replace('\\', ' ')
    val named = flattened.replace("..", "").trim().trimStart('.').trim()
    if (named.isEmpty()) return DEFAULT_DOWNLOAD_SUBFOLDER
    val bounded = named.take(MAX_DOWNLOAD_SUBFOLDER_LENGTH).trim()
    return bounded.ifEmpty { DEFAULT_DOWNLOAD_SUBFOLDER }
}
