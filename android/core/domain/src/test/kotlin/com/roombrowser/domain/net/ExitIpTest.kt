package com.roombrowser.domain.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExitIpTest {

    @Test
    fun every_endpoint_is_an_https_address() {
        assertThat(ExitIp.ENDPOINTS).isNotEmpty()
        ExitIp.ENDPOINTS.forEach { endpoint ->
            // The address IS the answer, so a plain-HTTP endpoint would hand it
            // to every middlebox on the path.
            assertThat(endpoint).startsWith("https://")
            assertThat(endpoint.removePrefix("https://")).isNotEmpty()
            assertThat(endpoint).doesNotContain(" ")
        }
    }

    @Test
    fun no_endpoint_is_listed_twice() {
        assertThat(ExitIp.ENDPOINTS).containsNoDuplicates()
    }

    @Test
    fun the_ipv6_probe_is_https_and_asks_nothing_the_v4_list_already_asks() {
        assertThat(ExitIp.IPV6_ENDPOINTS).isNotEmpty()
        ExitIp.IPV6_ENDPOINTS.forEach { assertThat(it).startsWith("https://") }
        // api64.ipify.org would answer over either family, which is the question
        // the v4 list already asks — the v6 list has to be v6-only to mean anything.
        assertThat(ExitIp.IPV6_ENDPOINTS).containsNoneIn(ExitIp.ENDPOINTS)
    }
}
