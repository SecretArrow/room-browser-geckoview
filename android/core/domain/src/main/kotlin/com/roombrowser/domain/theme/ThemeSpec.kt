package com.roombrowser.domain.theme

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Room Browser per-profile theme system.
 *
 * Every profile owns a COMPLETE, independent theme specification (snapshot):
 * changing one profile's theme can never affect another profile. The spec is
 * serialized to JSON on the profile row (profiles.theme_json) so it travels
 * with the profile, and custom themes can additionally be saved into a
 * shared local gallery (themes table) for quick re-use.
 */

/** How the theme chooses between its light and dark palette. */
@Serializable
enum class RoomThemeMode { LIGHT, DARK, AMOLED, AUTO }

/** Background gradient style applied to large surfaces. */
@Serializable
enum class GradientStyle { NONE, LINEAR, RADIAL }

/** Direction of the LINEAR gradient (ignored for NONE / RADIAL). */
@Serializable
enum class GradientDirection { TOP_BOTTOM, BOTTOM_TOP, LEFT_RIGHT, RIGHT_LEFT, TL_BR, TR_BL }

/**
 * One resolved palette (all colors are packed ARGB Longs, e.g. 0xFF0B0B0F).
 * Field meanings map 1:1 onto the Theme Editor controls.
 */
@Serializable
data class ThemeColors(
    val background: Long,
    val surface: Long,
    val surfaceAlt: Long,
    val primary: Long,
    val secondary: Long,
    val textPrimary: Long,
    val textSecondary: Long,
    val addressBar: Long,
    val tabBar: Long,
    val navBar: Long,
    val button: Long,
    val border: Long,
    val icon: Long,
    val selection: Long
)

/**
 * A full theme specification. [light] and [dark] are two hand-tuned
 * palettes; [mode] decides which one is active (AUTO follows the system).
 */
@Serializable
data class RoomThemeSpec(
    val id: String,
    val name: String,
    val mode: RoomThemeMode = RoomThemeMode.AUTO,
    val light: ThemeColors,
    val dark: ThemeColors,
    val gradientStyle: GradientStyle = GradientStyle.NONE,
    val gradientDirection: GradientDirection = GradientDirection.TOP_BOTTOM,
    /** Corner radius in dp for cards / bars (clamped 4..32). */
    val cornerRadius: Int = 20,
    /** UI transparency in percent (0..90) — glassy overlays. */
    val transparency: Int = 0,
    /** Blur/glass intensity in percent (0..100) — affects floating bars. */
    val blur: Int = 0,
    /** Contrast level in percent (50..150; 100 = neutral). */
    val contrast: Int = 100,
    /**
     * Turn off the app's own panel and sheet animations.
     *
     * It lives in the theme because it is an appearance choice, and because
     * this is the only place the app already resolves one per profile. It does
     * NOT reach Material's ripple or the system's animator scale — the settings
     * row says so where it is set.
     */
    val reducedMotion: Boolean = false
) {
    init {
        require(id.isNotBlank()) { "theme id must not be blank" }
        require(name.isNotBlank()) { "theme name must not be blank" }
    }

    /** Clamps user-editable numeric fields into sane ranges. */
    fun sanitized(): RoomThemeSpec = copy(
        cornerRadius = cornerRadius.coerceIn(4, 32),
        transparency = transparency.coerceIn(0, 90),
        blur = blur.coerceIn(0, 100),
        contrast = contrast.coerceIn(50, 150)
    )

    /**
     * Resolve the ACTIVE palette.
     * @param systemDark true when the OS is in dark mode (used by AUTO)
     */
    fun resolve(systemDark: Boolean): ThemeColors {
        val base = when (mode) {
            RoomThemeMode.LIGHT -> light
            RoomThemeMode.DARK, RoomThemeMode.AMOLED -> dark
            RoomThemeMode.AUTO -> if (systemDark) dark else light
        }
        if (mode != RoomThemeMode.AMOLED) return base
        // AMOLED: true black background + black chrome, accents kept.
        return base.copy(
            background = 0xFF000000L,
            surface = 0xFF060606L,
            addressBar = 0xFF000000L,
            tabBar = 0xFF000000L,
            navBar = 0xFF000000L
        )
    }

    /** Short descriptor of the active mode, for UI summaries. */
    fun modeLabel(): String = when (mode) {
        RoomThemeMode.LIGHT -> "Light"
        RoomThemeMode.DARK -> "Dark"
        RoomThemeMode.AMOLED -> "AMOLED"
        RoomThemeMode.AUTO -> "Auto"
    }

    fun withIdentity(newId: String, newName: String): RoomThemeSpec =
        copy(id = newId, name = newName)

    companion object {
        /** Prefix used for custom (user-saved) theme ids. */
        const val CUSTOM_PREFIX = "custom-"
    }
}

/** JSON codec for import / export and profile persistence. */
object ThemeJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(spec: RoomThemeSpec): String =
        json.encodeToString(RoomThemeSpec.serializer(), spec.sanitized())

    /** Lenient decode: returns null for malformed input (never throws). */
    fun decode(raw: String?): RoomThemeSpec? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString(RoomThemeSpec.serializer(), raw).sanitized()
        }.getOrNull()
    }
}

// ---------------------------------------------------------------------------
// Built-in themes (18) — hand-tuned professional palettes, each with BOTH a
// light and a dark variant so AUTO mode always looks intentional.
// ---------------------------------------------------------------------------

object BuiltInThemes {

    val DEFAULT_ID = "obsidian"

    /** Compact palette builder with sensible derivations. */
    private fun tc(
        bg: Long, surface: Long, alt: Long, primary: Long, secondary: Long,
        text: Long, text2: Long,
        address: Long = surface,
        tab: Long = bg,
        nav: Long = bg,
        button: Long = primary,
        border: Long,
        icon: Long = text2,
        selection: Long = (primary and 0x00FFFFFFL) or 0x47000000L // 28% primary
    ) = ThemeColors(
        background = bg, surface = surface, surfaceAlt = alt,
        primary = primary, secondary = secondary,
        textPrimary = text, textSecondary = text2,
        addressBar = address, tabBar = tab, navBar = nav,
        button = button, border = border, icon = icon, selection = selection
    )

    private fun spec(
        id: String, name: String,
        dark: ThemeColors, light: ThemeColors,
        gradientStyle: GradientStyle = GradientStyle.NONE,
        gradientDirection: GradientDirection = GradientDirection.TOP_BOTTOM
    ) = RoomThemeSpec(
        id = id, name = name, mode = RoomThemeMode.AUTO,
        light = light, dark = dark,
        gradientStyle = gradientStyle, gradientDirection = gradientDirection
    )

    val obsidian = spec(
        "obsidian", "Obsidian",
        dark = tc(
            bg = 0xFF0B0B0F, surface = 0xFF14141B, alt = 0xFF1D1D27,
            primary = 0xFFA78BFA, secondary = 0xFFC4B5FD,
            text = 0xFFF1F0F7, text2 = 0xFFA5A3B4,
            address = 0xFF17171F, tab = 0xFF101017, nav = 0xFF0E0E14,
            border = 0xFF2A2A38
        ),
        light = tc(
            bg = 0xFFFAFAFC, surface = 0xFFFFFFFF, alt = 0xFFEEEDF4,
            primary = 0xFF6D28D9, secondary = 0xFF8B5CF6,
            text = 0xFF191822, text2 = 0xFF5A5870,
            address = 0xFFF2F1F8, tab = 0xFFFFFFFF, nav = 0xFFF7F6FB,
            border = 0xFFE3E1EC
        )
    )

    val arctic = spec(
        "arctic", "Arctic",
        dark = tc(
            bg = 0xFF0E1622, surface = 0xFF16202F, alt = 0xFF1E2A3C,
            primary = 0xFF7DD3FC, secondary = 0xFFBAE6FD,
            text = 0xFFEFF6FC, text2 = 0xFF9DB2C6,
            address = 0xFF182335, tab = 0xFF111A27, nav = 0xFF101826,
            border = 0xFF27374B
        ),
        light = tc(
            bg = 0xFFF4F8FC, surface = 0xFFFFFFFF, alt = 0xFFE8F1F8,
            primary = 0xFF0284C7, secondary = 0xFF38BDF8,
            text = 0xFF12202C, text2 = 0xFF516878,
            address = 0xFFEDF4FA, tab = 0xFFFFFFFF, nav = 0xFFF0F6FB,
            border = 0xFFD8E6F0
        )
    )

    val ocean = spec(
        "ocean", "Ocean",
        dark = tc(
            bg = 0xFF0A1929, surface = 0xFF10233A, alt = 0xFF16304C,
            primary = 0xFF38BDF8, secondary = 0xFF60A5FA,
            text = 0xFFEDF5FC, text2 = 0xFF93AAC2,
            address = 0xFF12283F, tab = 0xFF0D1F33, nav = 0xFF0C1C2E,
            border = 0xFF1F3A57
        ),
        light = tc(
            bg = 0xFFF2F7FC, surface = 0xFFFFFFFF, alt = 0xFFE4EFF8,
            primary = 0xFF0277BD, secondary = 0xFF26C6DA,
            text = 0xFF10222F, text2 = 0xFF4F6878,
            address = 0xFFEBF3F9, tab = 0xFFFFFFFF, nav = 0xFFEFF6FB,
            border = 0xFFD6E5F0
        )
    )

    val emerald = spec(
        "emerald", "Emerald",
        dark = tc(
            bg = 0xFF07130D, surface = 0xFF0E1F16, alt = 0xFF142A1E,
            primary = 0xFF34D399, secondary = 0xFFA7F3D0,
            text = 0xFFEDF9F2, text2 = 0xFF8FAF9E,
            address = 0xFF102419, tab = 0xFF0A1A12, nav = 0xFF091710,
            border = 0xFF1D3A2A
        ),
        light = tc(
            bg = 0xFFF3FAF6, surface = 0xFFFFFFFF, alt = 0xFFE5F3EB,
            primary = 0xFF047857, secondary = 0xFF10B981,
            text = 0xFF122119, text2 = 0xFF54705F,
            address = 0xFFEBF6F0, tab = 0xFFFFFFFF, nav = 0xFFEFF8F3,
            border = 0xFFD5E9DC
        )
    )

    val midnight = spec(
        "midnight", "Midnight",
        dark = tc(
            bg = 0xFF050B18, surface = 0xFF0B1526, alt = 0xFF111F36,
            primary = 0xFF93C5FD, secondary = 0xFF60A5FA,
            text = 0xFFECF3FC, text2 = 0xFF8CA1BD,
            address = 0xFF0D1A2E, tab = 0xFF081222, nav = 0xFF071020,
            border = 0xFF1A2C49
        ),
        light = tc(
            bg = 0xFFF1F5FB, surface = 0xFFFFFFFF, alt = 0xFFE3ECF7,
            primary = 0xFF1E3A8A, secondary = 0xFF3B82F6,
            text = 0xFF101B2A, text2 = 0xFF4E5F76,
            address = 0xFFEAF1F9, tab = 0xFFFFFFFF, nav = 0xFFEDF3FA,
            border = 0xFFD5DFEC
        )
    )

    val aurora = spec(
        "aurora", "Aurora",
        dark = tc(
            bg = 0xFF0B0F1E, surface = 0xFF121829, alt = 0xFF192134,
            primary = 0xFF818CF8, secondary = 0xFF34D399,
            text = 0xFFEEF1FB, text2 = 0xFF96A0BE,
            address = 0xFF141B30, tab = 0xFF0E1322, nav = 0xFF0D1120,
            border = 0xFF222B47
        ),
        light = tc(
            bg = 0xFFF5F6FD, surface = 0xFFFFFFFF, alt = 0xFFE9EBFA,
            primary = 0xFF6366F1, secondary = 0xFF10B981,
            text = 0xFF15172A, text2 = 0xFF585D7E,
            address = 0xFFEEF0FB, tab = 0xFFFFFFFF, nav = 0xFFF1F3FC,
            border = 0xFFDDE0F0
        ),
        gradientStyle = GradientStyle.LINEAR,
        gradientDirection = GradientDirection.TL_BR
    )

    val sunset = spec(
        "sunset", "Sunset",
        dark = tc(
            bg = 0xFF1A0E0A, surface = 0xFF251510, alt = 0xFF321D15,
            primary = 0xFFFB923C, secondary = 0xFFF87171,
            text = 0xFFFDF2EA, text2 = 0xFFC0A18E,
            address = 0xFF2A1811, tab = 0xFF20110D, nav = 0xFF1D0F0B,
            border = 0xFF3C241A
        ),
        light = tc(
            bg = 0xFFFDF7F2, surface = 0xFFFFFFFF, alt = 0xFFFAEDE2,
            primary = 0xFFEA580C, secondary = 0xFFEF4444,
            text = 0xFF271710, text2 = 0xFF7A5C4B,
            address = 0xFFFBF0E7, tab = 0xFFFFFFFF, nav = 0xFFFCF5EE,
            border = 0xFFF0DECF
        ),
        gradientStyle = GradientStyle.LINEAR,
        gradientDirection = GradientDirection.TOP_BOTTOM
    )

    val cyber = spec(
        "cyber", "Cyber",
        dark = tc(
            bg = 0xFF05070D, surface = 0xFF0A0F1A, alt = 0xFF101726,
            primary = 0xFF00E5FF, secondary = 0xFFF472B6,
            text = 0xFFEAFBFF, text2 = 0xFF7E93A6,
            address = 0xFF0C1220, tab = 0xFF070C15, nav = 0xFF060A12,
            border = 0xFF12303A
        ),
        light = tc(
            bg = 0xFFEAFBF9, surface = 0xFFFFFFFF, alt = 0xFFDCF5F1,
            primary = 0xFF0891B2, secondary = 0xFFC026D3,
            text = 0xFF0A1A1E, text2 = 0xFF4E6A6E,
            address = 0xFFE2F6F2, tab = 0xFFFFFFFF, nav = 0xFFE8FAF7,
            border = 0xFFC4E8E1
        ),
        gradientStyle = GradientStyle.RADIAL
    )

    val royal = spec(
        "royal", "Royal",
        dark = tc(
            bg = 0xFF0E0A1A, surface = 0xFF160F28, alt = 0xFF1E1536,
            primary = 0xFFC084FC, secondary = 0xFF818CF8,
            text = 0xFFF3EFFB, text2 = 0xFFA79BC0,
            address = 0xFF191231, tab = 0xFF110C22, nav = 0xFF100B20,
            border = 0xFF2A1F4A
        ),
        light = tc(
            bg = 0xFFF8F5FD, surface = 0xFFFFFFFF, alt = 0xFFF0EAFB,
            primary = 0xFF7C3AED, secondary = 0xFF4F46E5,
            text = 0xFF1B152A, text2 = 0xFF5D5478,
            address = 0xFFF3EEFB, tab = 0xFFFFFFFF, nav = 0xFFF5F1FC,
            border = 0xFFE4DCF2
        )
    )

    val sakura = spec(
        "sakura", "Sakura",
        dark = tc(
            bg = 0xFF170F13, surface = 0xFF211620, alt = 0xFF2C1E2A,
            primary = 0xFFF9A8D4, secondary = 0xFFFBCFE8,
            text = 0xFFFDF0F6, text2 = 0xFFC4A3B4,
            address = 0xFF251925, tab = 0xFF1C121B, nav = 0xFF1A1018,
            border = 0xFF382433
        ),
        light = tc(
            bg = 0xFFFDF5F8, surface = 0xFFFFFFFF, alt = 0xFFFAE8F0,
            primary = 0xFFDB2777, secondary = 0xFFF472B6,
            text = 0xFF27141D, text2 = 0xFF7A5B6A,
            address = 0xFFFBEDF3, tab = 0xFFFFFFFF, nav = 0xFFFCF2F6,
            border = 0xFFF2DCE5
        )
    )

    val forest = spec(
        "forest", "Forest",
        dark = tc(
            bg = 0xFF0A120A, surface = 0xFF111C11, alt = 0xFF182718,
            primary = 0xFF7FB069, secondary = 0xFFA3B18A,
            text = 0xFFEFF7EA, text2 = 0xFF9BAA90,
            address = 0xFF142114, tab = 0xFF0D170D, nav = 0xFF0C150C,
            border = 0xFF223322
        ),
        light = tc(
            bg = 0xFFF4F8F1, surface = 0xFFFFFFFF, alt = 0xFFE9F1E4,
            primary = 0xFF3A5A40, secondary = 0xFF588157,
            text = 0xFF142014, text2 = 0xFF5A6E5C,
            address = 0xFFEEF4EA, tab = 0xFFFFFFFF, nav = 0xFFF0F6ED,
            border = 0xFFD9E5D3
        )
    )

    val aqua = spec(
        "aqua", "Aqua",
        dark = tc(
            bg = 0xFF061418, surface = 0xFF0A1E23, alt = 0xFF0F2930,
            primary = 0xFF2DD4BF, secondary = 0xFF22D3EE,
            text = 0xFFEAFBF9, text2 = 0xFF8AA9AB,
            address = 0xFF0C232A, tab = 0xFF081A1F, nav = 0xFF07171C,
            border = 0xFF16393F
        ),
        light = tc(
            bg = 0xFFF0FBF9, surface = 0xFFFFFFFF, alt = 0xFFE1F5F1,
            primary = 0xFF0D9488, secondary = 0xFF06B6D4,
            text = 0xFF0C2020, text2 = 0xFF4E6B68,
            address = 0xFFE8F7F3, tab = 0xFFFFFFFF, nav = 0xFFECFAF7,
            border = 0xFFCBE9E2
        )
    )

    val crimson = spec(
        "crimson", "Crimson",
        dark = tc(
            bg = 0xFF140607, surface = 0xFF1E0B0C, alt = 0xFF281011,
            primary = 0xFFF87171, secondary = 0xFFDC2626,
            text = 0xFFFCF0F0, text2 = 0xFFB98D8D,
            address = 0xFF22100F, tab = 0xFF180909, nav = 0xFF160808,
            border = 0xFF351718
        ),
        light = tc(
            bg = 0xFFFBF4F4, surface = 0xFFFFFFFF, alt = 0xFFF7E9E9,
            primary = 0xFFB91C1C, secondary = 0xFFEF4444,
            text = 0xFF231111, text2 = 0xFF775656,
            address = 0xFFF8EEEE, tab = 0xFFFFFFFF, nav = 0xFFFAF2F2,
            border = 0xFFF0DCDC
        )
    )

    val golden = spec(
        "golden", "Golden",
        dark = tc(
            bg = 0xFF0C0A04, surface = 0xFF151107, alt = 0xFF1F1809,
            primary = 0xFFF5C542, secondary = 0xFFD4AF37,
            text = 0xFFFBF6E7, text2 = 0xFFB3A579,
            address = 0xFF181307, tab = 0xFF100D05, nav = 0xFF0F0C04,
            border = 0xFF2B2310
        ),
        light = tc(
            bg = 0xFFFBF8EE, surface = 0xFFFFFFFF, alt = 0xFFF6EFDA,
            primary = 0xFFB8860B, secondary = 0xFFD4AF37,
            text = 0xFF211C0B, text2 = 0xFF776C48,
            address = 0xFFF8F2E1, tab = 0xFFFFFFFF, nav = 0xFFFAF5E9,
            border = 0xFFEBE2C4
        )
    )

    val slate = spec(
        "slate", "Slate",
        dark = tc(
            bg = 0xFF0F1114, surface = 0xFF171A1F, alt = 0xFF1F232A,
            primary = 0xFFCBD5E1, secondary = 0xFF94A3B8,
            text = 0xFFF1F5F9, text2 = 0xFF9AA4B2,
            address = 0xFF191D23, tab = 0xFF121519, nav = 0xFF111418,
            border = 0xFF272C34
        ),
        light = tc(
            bg = 0xFFF6F7F9, surface = 0xFFFFFFFF, alt = 0xFFEDEFF3,
            primary = 0xFF475569, secondary = 0xFF64748B,
            text = 0xFF151A21, text2 = 0xFF5B6572,
            address = 0xFFF0F2F5, tab = 0xFFFFFFFF, nav = 0xFFF2F4F7,
            border = 0xFFDFE3E9
        )
    )

    val lavender = spec(
        "lavender", "Lavender",
        dark = tc(
            bg = 0xFF14121C, surface = 0xFF1C1928, alt = 0xFF252136,
            primary = 0xFFC4B5FD, secondary = 0xFFE9D5FF,
            text = 0xFFF4F1FB, text2 = 0xFFABA2C2,
            address = 0xFF1F1C2D, tab = 0xFF171523, nav = 0xFF161420,
            border = 0xFF2E2944
        ),
        light = tc(
            bg = 0xFFF8F6FD, surface = 0xFFFFFFFF, alt = 0xFFF1EDFB,
            primary = 0xFF8B5CF6, secondary = 0xFFA78BFA,
            text = 0xFF1B172A, text2 = 0xFF5E5578,
            address = 0xFFF4F0FC, tab = 0xFFFFFFFF, nav = 0xFFF5F2FD,
            border = 0xFFE7E0F2
        )
    )

    val coffee = spec(
        "coffee", "Coffee",
        dark = tc(
            bg = 0xFF15100B, surface = 0xFF1F1811, alt = 0xFF2A2118,
            primary = 0xFFD7A86E, secondary = 0xFFB08968,
            text = 0xFFFAF3E9, text2 = 0xFFB5A389,
            address = 0xFF231B13, tab = 0xFF191309, nav = 0xFF171108,
            border = 0xFF362B1D
        ),
        light = tc(
            bg = 0xFFFAF6F0, surface = 0xFFFFFFFF, alt = 0xFFF3ECE1,
            primary = 0xFF8B5E34, secondary = 0xFFA47148,
            text = 0xFF231A10, text2 = 0xFF7A6A57,
            address = 0xFFF7F0E6, tab = 0xFFFFFFFF, nav = 0xFFF8F3EB,
            border = 0xFFEAE0CF
        )
    )

    val rose = spec(
        "rose", "Rose",
        dark = tc(
            bg = 0xFF150C10, surface = 0xFF1F1218, alt = 0xFF2A1820,
            primary = 0xFFFDA4AF, secondary = 0xFFFB7185,
            text = 0xFFFCF1F3, text2 = 0xFFBE9AA4,
            address = 0xFF231519, tab = 0xFF180E13, nav = 0xFF160D11,
            border = 0xFF361F27
        ),
        light = tc(
            bg = 0xFFFDF5F6, surface = 0xFFFFFFFF, alt = 0xFFFAE9EC,
            primary = 0xFFBE123C, secondary = 0xFFE11D48,
            text = 0xFF231217, text2 = 0xFF78565E,
            address = 0xFFFBEDF0, tab = 0xFFFFFFFF, nav = 0xFFFCF1F3,
            border = 0xFFF1DCE1
        )
    )

    val all: List<RoomThemeSpec> = listOf(
        obsidian, arctic, ocean, emerald, midnight, aurora, sunset, cyber,
        royal, sakura, forest, aqua, crimson, golden, slate, lavender,
        coffee, rose
    )

    fun byId(id: String): RoomThemeSpec? = all.firstOrNull { it.id == id }

    fun default(): RoomThemeSpec = obsidian

    /**
     * Resolve a profile's persisted theme JSON (a full spec snapshot).
     * Falls back to the default theme when blank / malformed. Never null.
     */
    fun resolveOrDefault(themeJson: String): RoomThemeSpec =
        ThemeJson.decode(themeJson) ?: default()
}
