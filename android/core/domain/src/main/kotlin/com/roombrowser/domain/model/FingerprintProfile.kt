package com.roombrowser.domain.model

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The fingerprint surfaces derived from a profile's seed. A null seed yields
 * [legacy], which is what the shim reported before seeds existed.
 */
data class FingerprintProfile private constructor(
    val platform: String,
    val colorDepth: Int?,
    val pixelDepth: Int?,
    val maxTouchPoints: Int?,
    val devicePixelRatio: Double?,
    val plugins: List<Plugin>,
    val mimeTypes: List<MimeType>,
    val isSeeded: Boolean
) {
    data class Plugin(
        val name: String,
        val description: String,
        val filename: String,
        val mimeTypes: List<String>
    )

    data class MimeType(
        val type: String,
        val suffixes: String,
        val description: String
    )

    companion object {
        const val LEGACY_PLATFORM = "Linux armv8l"

        private const val MAC_ALGORITHM = "HmacSHA256"

        // Only values a real Android Chrome could report.
        private val PLATFORMS = listOf("Linux armv8l", "Linux armv7l", "Linux aarch64")
        private val COLOR_DEPTHS = listOf(16, 24)
        private val TOUCH_POINTS = listOf(5, 10)
        private val PIXEL_RATIOS = listOf(1.5, 2.0, 2.625, 3.0, 3.5, 4.0)
        private val PLUGIN_NAMES = listOf(
            "PDF Viewer",
            "Chrome PDF Viewer",
            "Chromium PDF Viewer",
            "Microsoft Edge PDF Viewer",
            "WebKit built-in PDF"
        )
        private val MIME_TYPE_NAMES = listOf("application/pdf", "text/pdf")

        fun legacy(): FingerprintProfile = FingerprintProfile(
            platform = LEGACY_PLATFORM,
            colorDepth = null,
            pixelDepth = null,
            maxTouchPoints = null,
            devicePixelRatio = null,
            plugins = emptyList(),
            mimeTypes = emptyList(),
            isSeeded = false
        )

        fun from(seed: String?, device: Device?): FingerprintProfile =
            if (seed.isNullOrBlank()) legacy() else seeded(seed, device)

        private fun seeded(seed: String, device: Device?): FingerprintProfile {
            // The device joins the platform label so switching handset moves it.
            val platform =
                PLATFORMS[draw(seed, "platform:" + (device?.id ?: ""), PLATFORMS.size)]
            val depth = COLOR_DEPTHS[draw(seed, "colorDepth", COLOR_DEPTHS.size)]
            val touchPoints = TOUCH_POINTS[draw(seed, "maxTouchPoints", TOUCH_POINTS.size)]
            val ratio = PIXEL_RATIOS[draw(seed, "devicePixelRatio", PIXEL_RATIOS.size)]
            val pluginCount = 3 + draw(seed, "plugins", 3)
            val mimeCount = 1 + draw(seed, "mimeTypes", 2)
            val mimeNames = MIME_TYPE_NAMES.take(mimeCount)
            return FingerprintProfile(
                platform = platform,
                colorDepth = depth,
                pixelDepth = depth,
                maxTouchPoints = touchPoints,
                devicePixelRatio = ratio,
                plugins = PLUGIN_NAMES.take(pluginCount).map { name ->
                    Plugin(
                        name = name,
                        description = "Portable Document Format",
                        filename = "internal-pdf-viewer",
                        mimeTypes = mimeNames
                    )
                },
                mimeTypes = mimeNames.map { type ->
                    MimeType(
                        type = type,
                        suffixes = "pdf",
                        description = "Portable Document Format"
                    )
                },
                isSeeded = true
            )
        }

        /** Two bytes of HMAC-SHA256(seed, label), reduced to a pool index. */
        private fun draw(seed: String, label: String, bound: Int): Int {
            val mac = Mac.getInstance(MAC_ALGORITHM)
            mac.init(SecretKeySpec(seed.toByteArray(Charsets.UTF_8), MAC_ALGORITHM))
            val digest = mac.doFinal(label.toByteArray(Charsets.UTF_8))
            val pair = ((digest[0].toInt() and 0xFF) shl 8) or (digest[1].toInt() and 0xFF)
            return pair % bound
        }
    }
}
