package com.roombrowser.browser.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The whole omnibox as a selection, so the next keystroke replaces the URL
 * instead of prepending or appending to it. Pure, so the range is pinned by a
 * JVM test in the fast `quality` job.
 */
internal fun selectAllIn(value: TextFieldValue): TextFieldValue =
    value.copy(selection = TextRange(0, value.text.length))
