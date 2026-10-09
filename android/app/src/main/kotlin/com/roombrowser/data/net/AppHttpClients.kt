package com.roombrowser.data.net

import com.roombrowser.domain.proxy.OutboundProxy
import com.roombrowser.domain.proxy.ProxyScope
import okhttp3.OkHttpClient

/**
 * The app's own OkHttp clients, with the bound profile's outbound proxy applied.
 *
 * The engine's page traffic is NOT here — it takes the facade's `setProxy`, which is a
 * different mechanism with a different blast radius. What is here is the traffic the app
 * itself originates, tagged with the scope its settings screen controls.
 *
 * Two clients deliberately never come through here: `NetworkIdentity`'s and `DnsMonitor`'s
 * probes. Those are how a proxy is VERIFIED — sending them through the proxy would confirm
 * it by trusting it, and the per-profile address-conflict warning has to compare the real
 * address to mean anything.
 *
 * The local-AI screen is the third: its client is shared between a LAN Ollama daemon and
 * ollama.com, and a LAN daemon is not loopback, so a proxy that reached it would take local
 * inference down rather than hide anything.
 */
object AppHttpClients {

    /** The agent's own network calls: model providers and anything a task fetches itself. */
    fun agent(): OkHttpClient = OkHttpClient.Builder()
        .proxySelector(OutboundProxy.selector(ProxyScope.AGENT))
        .build()
}
