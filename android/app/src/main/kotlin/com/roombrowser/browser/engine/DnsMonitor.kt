package com.roombrowser.browser.engine

import com.roombrowser.domain.engine.DnsValidator
import com.roombrowser.domain.model.BrowserGlobalSettings
import com.roombrowser.domain.model.DnsPresets
import com.roombrowser.domain.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * DNS privacy monitor.
 *
 * HONEST LIMITATION (documented in PRIVACY.md and in the DNS settings UI):
 * Android WebView resolves its own traffic through the OS network stack —
 * a normal third-party app cannot force WebView to use a custom resolver.
 * The configuration here protects the app's OWN connections (public-IP
 * check, search suggestions, downloads), and only ever does so through DoH:
 * OkHttp has no DNS-over-TLS transport, so a DoT hostname is validated and
 * surfaced as a recommendation (turn on OS Private DNS) but is NOT enforced
 * — see the DoT note on [buildDoh].
 * It does NOT claim to protect WebView page loads.
 */
class DnsMonitor {

    sealed interface DnsState {
        data object System : DnsState
        data class Protected(val protocol: String, val resolver: String) : DnsState

        /**
         * The selected transport was validated but is NOT in effect for the
         * app's own connections (currently only DoT). Reported separately from
         * [Protected] so the connection panel never claims protection that is
         * not actually happening.
         */
        data class NotEnforced(val protocol: String, val detail: String) : DnsState
        data class Misconfigured(val reason: String) : DnsState
    }

    private val _state = MutableStateFlow<DnsState>(DnsState.System)
    val state: StateFlow<DnsState> = _state

    private val mutex = Mutex()
    private var currentClient: OkHttpClient? = null

    /**
     * The clean, system-DNS client every rebuild starts from. It is never
     * mutated: [apply] builds a new client from it each time, so a mode change
     * genuinely drops the previous resolver. Sharing this one builder keeps the
     * connection pool and dispatcher alive across rebuilds.
     */
    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Resolve the effective DNS configuration for [profile] given [global]
     * and (re)build the app's OkHttpClient accordingly.
     */
    suspend fun apply(global: BrowserGlobalSettings, profile: Profile): OkHttpClient = mutex.withLock {
        val effective = DnsValidator.effective(
            globalMode = global.dnsMode,
            globalDohUrl = global.dohUrl,
            globalDotHostname = global.dotHostname,
            profileMode = profile.settings.dnsMode,
            profileDohUrl = profile.settings.dohUrl,
            profileDotHostname = profile.settings.dotHostname
        )
        when (effective.status) {
            DnsValidator.DnsStatus.SYSTEM ->
                _state.value = DnsState.System
            DnsValidator.DnsStatus.PROTECTED_DOH ->
                _state.value = DnsState.Protected("DoH", effective.dohUrl ?: "")
            DnsValidator.DnsStatus.PROTECTED_DOT ->
                // Validated only — see the DoT note on [buildDoh]. Nothing is
                // enforced for app traffic, so this must not read as protection.
                _state.value = DnsState.NotEnforced("DoT", effective.dotHostname ?: "")
            DnsValidator.DnsStatus.MISCONFIGURED ->
                _state.value = DnsState.Misconfigured("Check the DoH URL / DoT hostname")
        }

        // Rebuild from the clean base every time. Using the *previous* client
        // as the base was a bug: OkHttp's Builder.dns() only ever sets a
        // resolver and never unsets one, so once DoH had been applied it stayed
        // applied for the life of the process — selecting "Reset to system DNS"
        // reported System while every app connection still resolved through the
        // old DoH endpoint.
        val doh = when (effective.status) {
            DnsValidator.DnsStatus.PROTECTED_DOH -> effective.dohUrl?.let { buildDoh(it, base) }
            else -> null
        }
        val rebuilt = base.newBuilder()
            .apply { if (doh != null) dns(doh) }
            .build()
        currentClient = rebuilt
        return rebuilt
    }

    /**
     * Probe: can we resolve a known host with the current configuration?
     * Returns true when the DoH resolver answered.
     */
    suspend fun probe(client: OkHttpClient): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.dns.lookup("example.com").isNotEmpty()
        }.getOrDefault(false)
    }

    fun client(): OkHttpClient = currentClient ?: base

    /**
     * DoT note: OkHttp has no DNS-over-TLS transport, and Android WebView
     * resolves through the OS stack regardless. A DoT hostname is therefore
     * VALIDATED and surfaced as a recommendation only — this app generates no
     * DoT traffic. OS-level Private DNS (Settings → Network → Private DNS) is
     * the only way to actually get DoT, and it covers WebView too.
     * [DnsState.NotEnforced] reports that honestly instead of claiming
     * protection that is not in effect.
     *
     * [bootstrap] is the clean base client rather than the client this resolver
     * replaces, so the two are independent by construction. Be honest about how
     * much that buys: OkHttp 4.12's DnsOverHttps does NOT consult the passed
     * client's `dns` for its bootstrap lookup — it uses the builder's
     * `systemDns` (default `Dns.SYSTEM`) or the pinned addresses below. So this
     * is defence-in-depth and an explicit statement of intent, not the fix; the
     * real bootstrap change is [bootstrapAddresses].
     */
    private fun buildDoh(dohUrl: String, bootstrap: OkHttpClient): DnsOverHttps {
        val url = dohUrl.toHttpUrl()
        val builder = DnsOverHttps.Builder()
            .client(bootstrap)
            .url(url)
        // For a preset URL, pin the endpoint's bootstrap lookup to that
        // resolver's own published addresses: the DoH hostname is then resolved
        // by the resolver itself instead of leaking to the OS resolver. A
        // custom URL has no address to pin and falls back to the system
        // resolver.
        //
        // Trade-off, stated plainly: OkHttp's BootstrapDns answers ONLY the
        // endpoint host and never falls back, so if these exact addresses are
        // unreachable, DoH fails rather than re-resolving by name. In practice
        // these are the same addresses the OS resolver returns for the endpoint
        // hostname, so this removes a lookup without removing a reachable
        // address — but it is a pin, not a hint.
        bootstrapAddresses(dohUrl)?.let { builder.bootstrapDnsHosts(it) }
        return builder.build()
    }

    /**
     * The preset's published DNS server addresses as bootstrap hints, or null
     * for a URL that matches no preset. Parsing an address literal cannot hit
     * the network; an unparseable one is simply dropped.
     */
    private fun bootstrapAddresses(dohUrl: String): List<InetAddress>? {
        val preset = DnsPresets.all.firstOrNull { it.dohUrl == dohUrl } ?: return null
        val addresses = listOf(
            preset.primaryIpv4,
            preset.secondaryIpv4,
            preset.primaryIpv6,
            preset.secondaryIpv6
        ).mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        return if (addresses.isEmpty()) null else addresses
    }

    /** System DNS fallback implementation (used by tests and diagnostics). */
    object SystemDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname == "localhost") listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            else throw UnknownHostException(hostname)
    }
}
