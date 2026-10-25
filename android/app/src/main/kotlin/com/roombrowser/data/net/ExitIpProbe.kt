package com.roombrowser.data.net

import com.roombrowser.domain.net.ExitIp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The direct exit-address probe: ask each of [endpoints] in turn and return the
 * first body [isValid] accepts. [endpoints] defaults to the IPv4 list; the
 * diagnostics screen passes [ExitIp.IPV6_ENDPOINTS] to ask the other question.
 *
 * It deliberately carries none of the profile's configuration. This reading is
 * the address a site would see, so taking it through anything the profile
 * configures would measure the detour instead of the destination.
 *
 * [isValid] is the caller's own rule and stays that way: a caller reading a
 * response body from an unknown middlebox may judge it differently, and neither
 * caller should be forced onto the other's guess.
 */
suspend fun fetchExitIp(
    client: OkHttpClient,
    timeoutMs: Long,
    isValid: (String) -> Boolean,
    endpoints: List<String> = ExitIp.ENDPOINTS
): String? = withContext(Dispatchers.IO) {
    endpoints.firstNotNullOfOrNull { endpoint ->
        runCatching {
            client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(Request.Builder().url(endpoint).build())
                .execute()
                .use { response ->
                    if (response.isSuccessful) {
                        response.body?.string()?.trim()?.takeIf(isValid)
                    } else null
                }
        }.getOrNull()
    }
}
