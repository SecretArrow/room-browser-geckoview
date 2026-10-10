package com.roombrowser.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roombrowser.domain.paging.Paging
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect

/**
 * Shared design-system components (Room Browser 2026 look):
 * rounded cards, glass chrome, quiet rows, animated progress.
 * All colors/shapes come from the per-profile theme via LocalRoomExtras /
 * MaterialTheme, so every screen restyles itself when the profile theme
 * changes.
 */

/**
 * All modal bottom sheets share this shape — flat-ish minimal design; bottom
 * corners stay square (sheet sits at screen bottom).
 */
val RoomBottomSheetShape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)

/**
 * The app's one CARD radius: panels, error surfaces, onboarding cards, icon
 * tiles. 0.8 of the profile's radius — 16dp at the default 20 — against the
 * full radius those surfaces used to carry.
 *
 * WHY SMALLER THAN THE THEME RADIUS, when the radius is a user setting: a
 * card is a large surface, and a large corner on a large surface reads as a
 * bubble — the content inside appears to float rather than sit. The profile
 * radius still drives the scale ([MaterialTheme.shapes]), so this tracks it,
 * it just tracks it at a calmer point. Buttons and rows keep the fuller
 * radius, where the surface is small enough that a rounder corner is
 * proportionate rather than bulbous.
 */
val RoomCardShape: RoundedCornerShape
    @Composable get() = RoundedCornerShape((LocalRoomExtras.current.radius * 0.8f).dp)

/**
 * The single sheet drag handle. Every real sheet is a ModalBottomSheet, which
 * draws M3's own centered handle above its content — so sheets never call
 * this themselves. It exists for the sheet-like surfaces that are NOT
 * ModalBottomSheets (the expanded AI agent panel) and re-exports the exact
 * same treatment, so the whole app shows one identical handle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomSheetDragHandle() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BottomSheetDefaults.DragHandle()
    }
}

/**
 * The one sheet title block, shared by EVERY sheet in the app. The drag
 * handle itself comes from ModalBottomSheet's built-in centered handle
 * (rendered above the content) — the header only adds the title and its 8dp
 * gap, so no sheet ever draws two handles.
 */
@Composable
fun RoomSheetHeader(title: String) {
    val extras = LocalRoomExtras.current
    Text(title, style = MaterialTheme.typography.titleLarge, color = extras.textPrimary)
    Spacer(Modifier.height(8.dp))
}

/** A rounded, bordered card surface with optional theme gradient sheen. */
@Composable
fun RoomCard(
    modifier: Modifier = Modifier,
    withGradient: Boolean = true,
    content: @Composable () -> Unit
) {
    val extras = LocalRoomExtras.current
    val gradient = extras.gradient
    val shape = RoundedCornerShape(extras.radius.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(extras.surface)
            .border(0.5.dp, extras.border, shape)
    ) {
        if (withGradient && gradient != null) {
            Box(Modifier.matchParentSize().background(gradient))
        }
        content()
    }
}

/** Section container used by settings screens: soft group card. */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    RoomCard(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

/** A labeled settings row with a trailing switch (full-width touch target). */
@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val extras = LocalRoomExtras.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { contentDescription = "$title switch, ${if (checked) "on" else "off"}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = extras.textPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textSecondary
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = extras.primary,
                checkedThumbColor = extras.onButton,
                uncheckedTrackColor = extras.surfaceAlt,
                uncheckedThumbColor = extras.icon
            )
        )
    }
}

/** A clickable settings row with a trailing chevron/value. */
@Composable
fun SettingActionRow(
    title: String,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    value: String? = null,
    onClick: () -> Unit
) {
    val extras = LocalRoomExtras.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(extras.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(leadingIcon, contentDescription = null, tint = extras.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = extras.textPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.textSecondary
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                color = extras.textSecondary
            )
            Spacer(Modifier.width(6.dp))
        }
        Chevron()
    }
}

@Composable
private fun Chevron() {
    val extras = LocalRoomExtras.current
    Canvas(Modifier.size(width = 8.dp, height = 12.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0.4f * w, 0.12f * h)
            lineTo(0.86f * w, 0.5f * h)
            lineTo(0.4f * w, 0.88f * h)
        }
        drawPath(
            path,
            color = extras.icon.copy(alpha = 0.8f),
            style = Stroke(width = 2.2f, cap = StrokeCap.Round)
        )
    }
}

/** Profile avatar circle with emoji icon + profile color + soft ring. */
@Composable
fun ProfileAvatar(icon: String, colorArgb: Long, size: Int = 44) {
    val extras = LocalRoomExtras.current
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(colorArgb.toInt()),
                        mix(Color(colorArgb.toInt()), if (extras.dark) Color.Black else Color.White, 0.25f)
                    )
                )
            )
            .border(1.5.dp, extras.surface, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(icon, style = MaterialTheme.typography.titleMedium)
    }
}

private fun mix(a: Color, b: Color, t: Float): Color {
    val ar = a.toArgb(); val br = b.toArgb()
    fun ch(shift: Int): Int {
        val av = (ar shr shift) and 0xFF; val bv = (br shr shift) and 0xFF
        return (av + ((bv - av) * t).toInt()).coerceIn(0, 255)
    }
    return Color((ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
}

/** Simple horizontal section header. */
@Composable
fun SectionHeader(text: String, trailingContent: (@Composable () -> Unit)? = null) {
    val extras = LocalRoomExtras.current
    // Two branches rather than one Row: this header is used on 50-odd screens,
    // and a lone Text must keep measuring exactly as it did before.
    if (trailingContent == null) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = extras.primary,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp)
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = extras.primary,
            modifier = Modifier.weight(1f)
        )
        trailingContent()
    }
}

/** Statistic tile used by the privacy dashboard & homepage. */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    copyable: Boolean = false
) {
    val extras = LocalRoomExtras.current
    val context = LocalContext.current
    val copied = rememberCopiedFlag(value)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape((extras.radius * 0.7f).dp))
            .background(extras.surfaceAlt.copy(alpha = 0.75f))
            .let { base ->
                if (!copyable) {
                    base
                } else {
                    base.clickable {
                        // Copied with its label: a bare "1931" out of context
                        // says nothing.
                        copySensitive(context, label, "$label: $value")
                        copied.value = true
                    }
                }
            }
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = extras.textPrimary
        )
        if (copyable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = extras.textSecondary,
                    // One line, always: three tiles share the panel's width, so
                    // a label that wrapped would make one tile taller than its
                    // neighbours.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(3.dp))
                CopyStateIcon(copied.value, 11.dp, extras.textSecondary)
            }
        } else {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = extras.textSecondary
            )
        }
    }
}

/** Thin animated progress bar used while pages load. */
@Composable
fun LoadingBar(visible: Boolean, progress: Int) {
    val extras = LocalRoomExtras.current
    if (!visible) return
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(1, 100) / 100f,
        animationSpec = tween(durationMillis = 240),
        label = "pageProgress"
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.5.dp)
            .background(extras.border.copy(alpha = 0.3f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(2.5.dp)
                .background(Brush.horizontalGradient(listOf(extras.primary, extras.secondary)))
        )
    }
}

/** Empty-state message. */
@Composable
fun EmptyState(title: String, subtitle: String? = null) {
    val extras = LocalRoomExtras.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoomCardShape)
                .background(extras.surfaceAlt.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = null,
                tint = extras.icon.copy(alpha = 0.6f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = extras.textPrimary)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = extras.textSecondary
            )
        }
    }
}

/**
 * Glass chrome bar: the floating navigation-bar look. Uses the theme's
 * navBar color with its transparency level; on API 31+ a real blur softens
 * the decorative accent sheen (content is never blurred — only the soft
 * gradient layer behind it).
 */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val extras = LocalRoomExtras.current
    val barShape = RoundedCornerShape(extras.radius.dp)
    val useBlur = android.os.Build.VERSION.SDK_INT >= 31 && extras.blurRadiusPx > 0f
    val sheenEffect: ComposeRenderEffect? = if (useBlur) {
        android.graphics.RenderEffect.createBlurEffect(
            extras.blurRadiusPx, extras.blurRadiusPx, android.graphics.Shader.TileMode.CLAMP
        )?.asComposeRenderEffect()
    } else null
    Box(
        modifier = modifier
            .clip(barShape)
            .background(extras.navBar.copy(alpha = extras.chromeAlpha()))
            .border(0.5.dp, extras.border, barShape)
    ) {
        if (sheenEffect != null) {
            // Blurred accent sheen: the only layer the blur touches.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        clip = true
                        shape = barShape
                        renderEffect = sheenEffect
                    }
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                extras.primary.copy(alpha = 0.10f),
                                Color.Transparent,
                                extras.secondary.copy(alpha = 0.07f)
                            )
                        )
                    )
            )
        }
        content()
    }
}

/**
 * The page control under a long list of models.
 *
 * Renders NOTHING when everything fits on one page, so a picker with three
 * ids looks exactly as it did before paging existed. [semanticsPrefix] names
 * the three nodes the e2e suites drive: `<prefix>_prev`, `<prefix>_next` and
 * `<prefix>_window`.
 */
@Composable
fun PagingFooter(
    page: Int,
    total: Int,
    onPage: (Int) -> Unit,
    semanticsPrefix: String,
    modifier: Modifier = Modifier
) {
    val pages = Paging.pageCount(total)
    if (pages <= 1) return
    val current = Paging.clampPage(page, total)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        IconButton(
            onClick = { onPage(current - 1) },
            enabled = current > 0,
            modifier = Modifier.semantics { contentDescription = "${semanticsPrefix}_prev" }
        ) {
            Icon(
                Icons.AutoMirrored.Filled.NavigateBefore,
                contentDescription = "Previous page",
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            "Page ${current + 1} of $pages · ${Paging.windowLabel(current, total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .semantics { contentDescription = "${semanticsPrefix}_window" }
        )
        IconButton(
            onClick = { onPage(current + 1) },
            enabled = current < pages - 1,
            modifier = Modifier.semantics { contentDescription = "${semanticsPrefix}_next" }
        ) {
            Icon(
                Icons.AutoMirrored.Filled.NavigateNext,
                contentDescription = "Next page",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
