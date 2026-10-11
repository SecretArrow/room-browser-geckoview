package com.roombrowser.ui.common

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.roombrowser.domain.theme.BuiltInThemes
import com.roombrowser.domain.theme.GradientDirection
import com.roombrowser.domain.theme.GradientStyle
import com.roombrowser.domain.theme.RoomThemeSpec

/**
 * Room Browser 2026 design system.
 *
 * The whole app is themed from ONE per-profile [RoomThemeSpec]: the Material 3
 * color scheme, shapes (corner radius), typography AND the browser-specific
 * extras (address bar / tab bar / navigation bar / button / border / icon /
 * selection colors, gradient, transparency, blur, contrast) exposed through
 * [LocalRoomExtras].
 */
data class RoomExtras(
    // Browser chrome colors (resolve()d from the active palette + contrast)
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val primary: Color,
    val secondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val addressBar: Color,
    val tabBar: Color,
    val navBar: Color,
    val button: Color,
    val border: Color,
    val icon: Color,
    val selection: Color,
    val onButton: Color,
    // Shape / effect tokens
    val radius: Int, // dp
    val transparency: Int, // 0..90 %
    val blur: Int, // 0..100 (glass intensity)
    val surfaceAlpha: Float, // derived from transparency
    val blurRadiusPx: Float, // derived from blur
    val dark: Boolean,
    val gradientStyle: GradientStyle,
    val gradientDirection: GradientDirection,
    val gradient: Brush?
)

val LocalRoomExtras = staticCompositionLocalOf {
    RoomExtras(
        background = Color(0xFF0B0B0F), surface = Color(0xFF14141B),
        surfaceAlt = Color(0xFF1D1D27), primary = Color(0xFFA78BFA),
        secondary = Color(0xFFC4B5FD), textPrimary = Color(0xFFF1F0F7),
        textSecondary = Color(0xFFA5A3B4), addressBar = Color(0xFF17171F),
        tabBar = Color(0xFF101017), navBar = Color(0xFF0E0E14),
        button = Color(0xFFA78BFA), border = Color(0xFF2A2A38),
        icon = Color(0xFFA5A3B4), selection = Color(0x47A78BFA),
        onButton = Color(0xFF0B0B0F), radius = 20, transparency = 0, blur = 0,
        surfaceAlpha = 1f, blurRadiusPx = 0f, dark = true,
        gradientStyle = GradientStyle.NONE, gradientDirection = GradientDirection.TOP_BOTTOM,
        gradient = null
    )
}

// ---------------------------------------------------------------------------
// Color math
// ---------------------------------------------------------------------------

/** Lerp toward the "maximum contrast extreme" for the given background. */
private fun boosted(color: Color, bg: Color, amount: Float): Color {
    val target = if (bg.luminance() > 0.5f) Color.Black else Color.White
    return lerp(color, target, amount.coerceIn(0f, 1f))
}

private fun lerp(a: Color, b: Color, t: Float): Color {
    val ar = a.toArgb(); val br = b.toArgb()
    fun ch(shift: Int): Int {
        val av = (ar shr shift) and 0xFF; val bv = (br shr shift) and 0xFF
        return (av + ((bv - av) * t).toInt()).coerceIn(0, 255)
    }
    return Color((ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
}

/** Readable foreground for any accent (used on filled buttons etc.). */
private fun onColorOf(color: Color): Color =
    if (color.luminance() > 0.55f) Color(0xFF12101A) else Color(0xFFFCFCFF)

// The real contrast application lives in resolveExtras (needs the level).

// ---------------------------------------------------------------------------
// Palette → Material scheme + extras
// ---------------------------------------------------------------------------

private fun resolveExtras(spec: RoomThemeSpec, systemDark: Boolean): RoomExtras {
    val c = spec.resolve(systemDark)
    val bg = Color(c.background)
    val amount = ((spec.contrast - 100) / 50f).coerceIn(-1f, 1f) * 0.6f
    val text = if (amount >= 0) boosted(Color(c.textPrimary), bg, amount)
    else lerp(Color(c.textPrimary), bg, -amount)
    val text2 = if (amount >= 0) boosted(Color(c.textSecondary), bg, amount * 0.8f)
    else lerp(Color(c.textSecondary), bg, -amount * 0.8f)
    val icon = if (amount >= 0) boosted(Color(c.icon), bg, amount * 0.8f)
    else lerp(Color(c.icon), bg, -amount * 0.8f)
    val border = if (amount >= 0) boosted(Color(c.border), bg, amount * 0.5f)
    else lerp(Color(c.border), bg, -amount * 0.5f)

    val gradient = when (spec.gradientStyle) {
        GradientStyle.NONE -> null
        GradientStyle.LINEAR -> {
            val (from, to) = listOf(Color(c.primary), Color(c.secondary))
            when (spec.gradientDirection) {
                GradientDirection.TOP_BOTTOM -> Brush.verticalGradient(listOf(from.copy(alpha = 0.14f), to.copy(alpha = 0.05f), Color.Transparent))
                GradientDirection.BOTTOM_TOP -> Brush.verticalGradient(listOf(Color.Transparent, to.copy(alpha = 0.05f), from.copy(alpha = 0.14f)))
                GradientDirection.LEFT_RIGHT -> Brush.horizontalGradient(listOf(from.copy(alpha = 0.14f), to.copy(alpha = 0.05f), Color.Transparent))
                GradientDirection.RIGHT_LEFT -> Brush.horizontalGradient(listOf(Color.Transparent, to.copy(alpha = 0.05f), from.copy(alpha = 0.14f)))
                GradientDirection.TL_BR -> Brush.linearGradient(listOf(from.copy(alpha = 0.16f), to.copy(alpha = 0.06f), Color.Transparent))
                GradientDirection.TR_BL -> Brush.linearGradient(listOf(Color.Transparent, to.copy(alpha = 0.06f), from.copy(alpha = 0.16f)), start = androidx.compose.ui.geometry.Offset(1000f, 0f), end = androidx.compose.ui.geometry.Offset(0f, 1000f))
            }
        }
        GradientStyle.RADIAL -> Brush.radialGradient(
            listOf(Color(c.primary).copy(alpha = 0.18f), Color(c.secondary).copy(alpha = 0.07f), Color.Transparent)
        )
    }

    return RoomExtras(
        background = bg,
        surface = Color(c.surface),
        surfaceAlt = Color(c.surfaceAlt),
        primary = Color(c.primary),
        secondary = Color(c.secondary),
        textPrimary = text,
        textSecondary = text2,
        addressBar = Color(c.addressBar),
        tabBar = Color(c.tabBar),
        navBar = Color(c.navBar),
        button = Color(c.button),
        border = border,
        icon = icon,
        selection = Color(c.selection),
        onButton = onColorOf(Color(c.button)),
        radius = spec.cornerRadius,
        transparency = spec.transparency,
        blur = spec.blur,
        surfaceAlpha = (1f - spec.transparency / 100f).coerceIn(0.10f, 1f),
        blurRadiusPx = (spec.blur / 100f) * 22f,
        dark = when (spec.mode) {
            com.roombrowser.domain.theme.RoomThemeMode.LIGHT -> false
            else -> spec.mode != com.roombrowser.domain.theme.RoomThemeMode.AUTO || systemDark
        },
        gradientStyle = spec.gradientStyle,
        gradientDirection = spec.gradientDirection,
        gradient = gradient
    )
}

private fun scheme(extras: RoomExtras): ColorScheme {
    val base = if (extras.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = extras.primary,
        onPrimary = onColorOf(extras.primary),
        primaryContainer = lerp(extras.primary, extras.surface, 0.80f),
        onPrimaryContainer = extras.primary,
        secondary = extras.secondary,
        onSecondary = onColorOf(extras.secondary),
        secondaryContainer = lerp(extras.secondary, extras.surface, 0.84f),
        onSecondaryContainer = extras.secondary,
        background = extras.background,
        onBackground = extras.textPrimary,
        surface = extras.surface,
        onSurface = extras.textPrimary,
        surfaceVariant = extras.surfaceAlt,
        onSurfaceVariant = extras.textSecondary,
        surfaceContainer = extras.surfaceAlt,
        surfaceContainerHigh = lerp(extras.surfaceAlt, extras.textPrimary, 0.04f),
        surfaceContainerHighest = lerp(extras.surfaceAlt, extras.textPrimary, 0.08f),
        surfaceContainerLow = extras.surface,
        surfaceContainerLowest = extras.background,
        surfaceTint = extras.primary,
        outline = extras.border,
        outlineVariant = extras.border.copy(alpha = 0.55f),
        inverseSurface = if (extras.dark) Color(0xFFF4F2F8) else Color(0xFF16151C),
        inverseOnSurface = if (extras.dark) Color(0xFF16151C) else Color(0xFFF4F2F8)
    )
}

// ---------------------------------------------------------------------------
// Typography & motion — modern, tight, quiet
// ---------------------------------------------------------------------------

private val RoomTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.25).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.25).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp, letterSpacing = (-0.15).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 15.5.sp, lineHeight = 23.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 20.sp, letterSpacing = 0.15.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp)
)

/** Motion tokens (spring-forward, 2026 feel). */
object RoomMotion {
    val pressScale = 0.96f
    val press = androidx.compose.animation.core.SpringSpec<Float>(
        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
    )
}

/**
 * The shape scale, derived from the profile's one radius setting.
 *
 * [Shapes.extraLarge] is what Material 3 hands to `AlertDialog`, `ModalBottomSheet`
 * and the large surface components, and it is the tier that made dialogs look
 * oversized: M3's spec value is 28dp, and the scale here pushed it to
 * `radius + 8` — 28dp at the default radius, i.e. a corner almost a third of
 * the way down a 100dp dialog. It is deliberately the SMALLEST tier relative
 * to its own size now, because a dialog is a small panel: the bigger the
 * surface, the less of it a big corner can afford to eat before the content
 * starts looking like it is floating in a bubble. Sheets keep their own,
 * even flatter shape ([RoomBottomSheetShape]) since they are anchored to the
 * screen edge.
 */
private fun shapesFor(radius: Int): Shapes {
    val r = radius.coerceIn(4, 32)
    return Shapes(
        extraSmall = RoundedCornerShape((r * 0.4f).toInt().coerceAtLeast(4)),
        small = RoundedCornerShape((r * 0.6f).toInt().coerceAtLeast(6)),
        medium = RoundedCornerShape((r * 0.75f).toInt().coerceAtLeast(8)),
        large = RoundedCornerShape(r),
        extraLarge = RoundedCornerShape((r * 0.8f).toInt().coerceIn(10, 16))
    )
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

/**
 * Theme the whole app (or one screen) from a per-profile [RoomThemeSpec].
 *
 * @param spec full theme snapshot; defaults to the built-in Obsidian.
 */
@Composable
fun RoomBrowserTheme(
    spec: RoomThemeSpec = BuiltInThemes.default(),
    content: @Composable () -> Unit
) {
    // Keep system-bar icon contrast in sync with the APP's effective theme.
    // enableEdgeToEdge() only knows the SYSTEM dark mode — when the app runs
    // dark while the system is light (or vice versa) the clock/battery icons
    // and the 3 navigation buttons would otherwise become invisible.
    val systemDark0 = isSystemInDarkTheme()
    val extras0 = resolveExtras(spec.sanitized(), systemDark0)
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(extras0.dark) {
            val window = (view.context as? Activity)?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            // SAVE/RESTORE, not fire-and-forget — the same idiom
            // FullscreenMediaHost uses for systemBarsBehavior. The old
            // onDispose restored nothing, so this mutation outlived the
            // composition that made it: a nested theme scope, or a dark→light
            // spec swap, left the WINDOW carrying the departed composition's
            // bar-icon contrast (light icons over a light status bar = an
            // invisible clock, battery and navigation buttons) until something
            // else happened to set it again.
            val previousLightStatusBars = controller?.isAppearanceLightStatusBars
            val previousLightNavigationBars = controller?.isAppearanceLightNavigationBars
            if (controller != null) {
                controller.isAppearanceLightStatusBars = !extras0.dark
                controller.isAppearanceLightNavigationBars = !extras0.dark
            }
            onDispose {
                if (controller != null &&
                    previousLightStatusBars != null &&
                    previousLightNavigationBars != null
                ) {
                    controller.isAppearanceLightStatusBars = previousLightStatusBars
                    controller.isAppearanceLightNavigationBars = previousLightNavigationBars
                }
            }
        }
    }
    RoomPreviewTheme(spec, content)
}

/**
 * Pure nested theming (MaterialTheme + LocalRoomExtras) WITHOUT touching the
 * window's system-bar appearance — used by the Theme Studio live preview,
 * which may render a dark mock browser inside a light activity.
 */
@Composable
fun RoomPreviewTheme(
    spec: RoomThemeSpec,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val extras = resolveExtras(spec.sanitized(), systemDark)

    CompositionLocalProvider(LocalRoomExtras provides extras) {
        MaterialTheme(
            colorScheme = scheme(extras),
            typography = RoomTypography,
            shapes = shapesFor(extras.radius),
            content = content
        )
    }
}

/**
 * Themed surface alpha helper: chrome bars overlaying page content use the
 * theme's transparency level to let the page shine through (glass effect).
 */
fun RoomExtras.chromeAlpha(): Float = surfaceAlpha

/** Convenience: current extras (theme chrome colors, radius, glass settings). */
object RoomTheme {
    val extras: RoomExtras
        @Composable get() = LocalRoomExtras.current
}
