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
}
