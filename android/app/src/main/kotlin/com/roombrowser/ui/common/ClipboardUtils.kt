package com.roombrowser.ui.common

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Places [value] on the clipboard as a clip flagged sensitive (no Android 13+
 * clipboard preview). Whatever snackbar or toast follows must announce only
 * WHAT was copied — never the value.
 *
 * No auto-clear timer on purpose: Android 13+ already clears the clipboard
 * after a while, and a delayed `clearPrimaryClip()` can wipe a newer, unrelated
 * clip the user copied in between.
 */
internal fun copySensitive(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, value)
    // EXTRA_IS_SENSITIVE is an inlined String constant — on API < 33 the
    // (to the platform unknown) bundle key is simply ignored.
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    clipboard.setPrimaryClip(clip)
}
