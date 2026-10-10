package com.roombrowser.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import java.util.Locale
import java.util.TimeZone

/** What the platform says about the connection the app is on right now. */
data class ConnectionFacts(
    val transport: String,
    val metered: Boolean,
    val vpn: Boolean,
    val localAddresses: List<String>,
    val systemDns: List<String>,
    val privateDns: String?,
    val operatorName: String?
)

/** The device and app identity the screen reports. None of it depends on the engine. */
data class DeviceFacts(
    val appVersion: String,
    val appVersionCode: Long,
    val packageName: String,
    val androidRelease: String,
    val apiLevel: Int,
    val model: String,
    val manufacturer: String,
    val primaryAbi: String,
    val locale: String,
    val timeZone: String,
    val screenPixels: String,
    val density: String
)

/**
 * One read of everything the platform knows about this device's network, for
 * the diagnostics screen.
 *
 * Every field is read defensively. Most of these come back empty on a device in
 * an unusual state (no active network, an OEM build that refuses a getter), and
 * a diagnostics screen that throws or crashes while reporting an oddity is worse
 * than one that says "unknown" — the oddity is what the user opened it to see.
 */
object SystemFacts {

    fun connection(context: Context): ConnectionFacts {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = runCatching { cm?.activeNetwork }.getOrNull()
        val caps = runCatching { network?.let { cm?.getNetworkCapabilities(it) } }.getOrNull()
        val lp = runCatching { network?.let { cm?.getLinkProperties(it) } }.getOrNull()
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        return ConnectionFacts(
            transport = transportLabel(context, caps, vpn),
            metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            vpn = vpn,
            localAddresses = runCatching {
                lp?.linkAddresses.orEmpty()
                    .mapNotNull { it.address?.hostAddress }
                    .filterNot { it.isBlank() || it == "127.0.0.1" || it == "::1" }
                    .distinct()
            }.getOrDefault(emptyList()),
            systemDns = runCatching {
                lp?.dnsServers.orEmpty().mapNotNull { it?.hostAddress }.distinct()
            }.getOrDefault(emptyList()),
            privateDns = runCatching {
                lp?.privateDnsServerName?.let { "on, $it" }
                    ?: if (lp?.isPrivateDnsActive == true) "on, opportunistic" else "off"
            }.getOrNull(),
            operatorName = runCatching {
                context.getSystemService(TelephonyManager::class.java)
                    ?.networkOperatorName?.takeIf { it.isNotBlank() }
            }.getOrNull()
        )
    }

    private fun transportLabel(
        context: Context,
        caps: NetworkCapabilities?,
        vpn: Boolean
    ): String {
        if (caps == null) return "No active network"
        val base = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                "Cellular" + cellularGeneration(context).let { if (it == null) "" else " ($it)" }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Other"
        }
        return if (vpn) "VPN over $base" else base
    }

    /**
     * The generation the radio reports, or null when the platform will not say.
     *
     * `dataNetworkType` is gated behind READ_PHONE_STATE on several Android
     * versions and this app holds no telephony permission, so the honest answer
     * there is "Cellular" with no generation rather than a guess.
     */
    private fun cellularGeneration(context: Context): String? = runCatching {
        when (context.getSystemService(TelephonyManager::class.java)?.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B -> "CDMA"
            else -> null
        }
    }.getOrNull()

    fun device(context: Context): DeviceFacts {
        val metrics = context.resources.displayMetrics
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionCode = runCatching {
            @Suppress("DEPRECATION")
            info?.longVersionCode ?: 0L
        }.getOrDefault(0L)
        return DeviceFacts(
            appVersion = info?.versionName.orEmpty().ifBlank { "unknown" },
            appVersionCode = versionCode,
            packageName = context.packageName,
            androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" },
            apiLevel = Build.VERSION.SDK_INT,
            model = Build.MODEL.orEmpty().ifBlank { "unknown" },
            manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "unknown" },
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },
            locale = Locale.getDefault().toLanguageTag(),
            timeZone = runCatching {
                val zone = TimeZone.getDefault()
                "${zone.id} (${zone.getDisplayName(false, TimeZone.SHORT)})"
            }.getOrDefault("unknown"),
            screenPixels = "${metrics.widthPixels} x ${metrics.heightPixels} px",
            density = "${metrics.densityDpi} dpi"
        )
    }
}
