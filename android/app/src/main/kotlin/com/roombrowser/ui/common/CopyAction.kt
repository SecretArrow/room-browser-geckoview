package com.roombrowser.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How long the check mark stands in for the copy icon after a copy. */
const val COPIED_FEEDBACK_MS = 1_800L

/**
 * The flag [CopyStateIcon] reads: set by a copy, cleared [COPIED_FEEDBACK_MS]
 * later. Keyed on [key] so a row whose text changed starts un-copied again.
 */
@Composable
fun rememberCopiedFlag(key: Any?): MutableState<Boolean> {
    val state = remember(key) { mutableStateOf(false) }
    LaunchedEffect(state.value) {
        if (state.value) {
            delay(COPIED_FEEDBACK_MS)
            state.value = false
        }
    }
    return state
}

/** The icon a copy affordance shows: a copy glyph, or a check while [copied]. */
@Composable
fun CopyStateIcon(
    copied: Boolean,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Icon(
        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
        contentDescription = null,
        modifier = modifier.size(size),
        tint = if (copied) LocalRoomExtras.current.primary else tint
    )
}

/**
 * A copy button that reports its own result, so a tap is never silent.
 *
 * The clip is written with [copySensitive]: what a Developer Tools panel
 * copies comes from the page, and a URL or a header can carry a token, so no
 * Android 13+ clipboard preview shows the value.
 *
 * No `IconButton`: its 48 dp minimum touch target is wider than a dense
 * fact row, and the metric row it sits in is the reason this is compact.
 */
@Composable
fun RoomCopyButton(
    text: String,
    label: String,
    desc: String,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp
) {
    val context = LocalContext.current
    val copied = rememberCopiedFlag(text)
    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable {
                copySensitive(context, label, text)
                copied.value = true
            }
            .padding(8.dp)
            .semantics {
                contentDescription = desc
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        CopyStateIcon(copied.value, size, LocalRoomExtras.current.icon)
    }
}
