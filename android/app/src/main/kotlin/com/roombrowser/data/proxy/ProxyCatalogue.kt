package com.roombrowser.data.proxy

import android.content.Context
import com.roombrowser.domain.proxy.ProxyCandidate
import com.roombrowser.domain.proxy.ProxyListParser
import com.roombrowser.domain.proxy.ProxySource
import com.roombrowser.domain.proxy.ProxySourceCatalogue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Where candidates come from: the list bundled in the APK first, the live sources second.
 *
 * The bundle is what makes the feature work on a fresh install with no network and with no
 * dependency on eight third-party repositories being up at the moment the user asks. The
 * live fetch is the fallback for a bundle that has aged, and it is the only path that can
 * see new endpoints.
 */
class ProxyCatalogue(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetched DIRECT, never through a proxy, on every path. A list of proxies cannot be
     * fetched through one of its own entries: the fetch would fail for the same reason the
     * entry is unusable, and the app would have no way to tell which.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(FETCH_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
        .build()

    suspend fun seed(): List<ProxyCandidate> = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(SEED_ASSET).bufferedReader().use { ProxyListParser.parse(it.readText()) }
        }.getOrDefault(emptyList())
    }

    suspend fun sources(): List<ProxySource> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.assets.open(SOURCES_ASSET).bufferedReader().use { it.readText() }
            json.decodeFromString(ProxySourceCatalogue.serializer(), text).sources
        }.getOrDefault(emptyList())
    }

    /** The bundled list, plus whatever the live sources add that it does not already hold. */
    suspend fun candidates(): List<ProxyCandidate> =
        ProxyListParser.merge(listOf(seed(), fetchLive(sources())))

    /** Fetch every source; a source that fails contributes nothing rather than failing the batch. */
    suspend fun fetchLive(sources: List<ProxySource>): List<ProxyCandidate> =
        withContext(Dispatchers.IO) {
            sources.flatMap { source ->
                runCatching {
                    val request = Request.Builder().url(source.url).header("User-Agent", USER_AGENT).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            emptyList()
                        } else {
                            ProxyListParser.parse(response.body?.string().orEmpty(), source.id)
                        }
                    }
                }.getOrDefault(emptyList())
            }
        }

    private companion object {
        const val SEED_ASSET = "proxies/seed.txt"
        const val SOURCES_ASSET = "proxies/sources.json"
        const val FETCH_TIMEOUT_MS = 15_000L
        const val USER_AGENT = "curl/8.5.0"
    }
}
