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
 * It deliberately never carries a proxy. This reading is the measurement a
 * proxy is judged against, so asking for it through a proxy would confirm the
 * proxy by trusting it.
 *
 * [isValid] is the caller's own rule and stays that way: the profile network
 * check and the proxy sweep prefer to judge a response body differently (a
 * sweep is reading whatever an unknown middlebox returned), and neither should
 * be forced onto the other's guess.
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
