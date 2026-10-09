package com.roombrowser.domain.proxy

import com.roombrowser.domain.model.ProfileSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The proxy rides in `profiles.settings_json`, so these fields must be addable without a
 * migration: a blob written before they existed has to keep decoding.
 *
 * The default is AUTO, not OFF, and that is the whole of "turning the finder on works for
 * every profile": the master switch is the global setting, and a per-profile default of OFF
 * would have meant the switch applied only to profiles created after it was flipped.
 */
class ProfileProxySettingsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a fresh profile follows the finder and routes pages only`() {
        val settings = ProfileSettings()
        assertThat(settings.proxyMode).isEqualTo(ProxyMode.AUTO)
        assertThat(settings.proxyHost).isNull()
        assertThat(settings.proxyPinnedId).isNull()
        assertThat(settings.proxyScopes).containsExactly(ProxyScope.PAGES)
    }

    @Test
    fun `a blob written before these fields existed still decodes`() {
        // Exactly what an older build stored: the same object, with the proxy keys absent.
        val stored = json.encodeToString(ProfileSettings.serializer(), ProfileSettings(searchEngineId = "startpage"))
        val stripped = JsonObject(
            json.parseToJsonElement(stored).jsonObject.filterKeys { !it.startsWith("proxy") }
        ).toString()
        assertThat(stripped).doesNotContain("proxy")

        val decoded = json.decodeFromString(ProfileSettings.serializer(), stripped)
        assertThat(decoded.searchEngineId).isEqualTo("startpage")
        assertThat(decoded.proxyMode).isEqualTo(ProxyMode.AUTO)
        assertThat(decoded.proxyScopes).containsExactly(ProxyScope.PAGES)
        assertThat(decoded.proxyPort).isEqualTo(0)
    }

    @Test
    fun `an enabled proxy survives a round trip`() {
        val settings = ProfileSettings(
            proxyMode = ProxyMode.MANUAL,
            proxyHost = "203.0.113.9",
            proxyPort = 8080,
            proxyScheme = ProxyScheme.SOCKS5,
            proxyScopes = setOf(ProxyScope.PAGES, ProxyScope.WALLET),
            proxyPinnedId = "203.0.113.9:8080"
        )
        val restored = json.decodeFromString(
            ProfileSettings.serializer(),
            json.encodeToString(ProfileSettings.serializer(), settings)
        )
        assertThat(restored).isEqualTo(settings)
    }
}
