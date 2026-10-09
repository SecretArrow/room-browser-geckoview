package com.roombrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.domain.proxy.ProxyScheme
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The proxy seam, as the settings screen sees it.
 *
 * WHAT THIS DOES AND DOES NOT PIN. The screen decides what to offer from
 * [ProfileEngine.proxySupported] and [ProfileEngine.proxySupportsScheme], and getting those
 * wrong is what would leave a settings switch claiming a proxy that is not in force. Those
 * answers are pure — neither call touches a session — so they are what this suite checks.
 *
 * It deliberately does NOT call `setProxy` here. That path needs a live engine runtime, and
 * binding one in the instrumentation process is not a valid harness for it: on this edition
 * the GeckoThread reaches EXITED there, so a result that never settles would say nothing
 * about the app. The applied-proxy behaviour is covered by the JVM tests for the resolution
 * rules and by a real device for the engine's own acceptance.
 */
@RunWith(AndroidJUnit4::class)
class ProxyFacadeE2eTest {

    @Test
    fun both_engines_carry_the_schemes_a_web_page_needs() {
        assertThat(ProfileEngine.proxySupportsScheme(ProxyScheme.HTTP)).isTrue()
        assertThat(ProfileEngine.proxySupportsScheme(ProxyScheme.HTTPS)).isTrue()
    }

    @Test
    fun a_socks_answer_is_given_rather_than_assumed() {
        // WebView's override has no SOCKS rule; Necko has one. Either answer is correct, but
        // an engine that cannot carry HTTP cannot carry SOCKS, and that ordering is the part
        // a wrong constant would break.
        val http = ProfileEngine.proxySupportsScheme(ProxyScheme.HTTP)
        val socks = ProfileEngine.proxySupportsScheme(ProxyScheme.SOCKS5)
        assertThat(socks && !http).isFalse()
    }

    @Test
    fun the_proxy_override_feature_is_present_on_this_device() {
        // WebView answers from the installed provider's feature set -- PROXY_OVERRIDE needs
        // WebView 72 or later -- and the simulator image CI runs is far past that. A false
        // here means the settings screen would be telling the truth about a switch that does
        // nothing, which is worth failing over.
        assertThat(ProfileEngine.proxySupported()).isTrue()
    }
}
