package com.roombrowser.node.packages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where a packument and its tarball come from. Both are on the one seam so a
 * whole install can be driven by a fake with no network.
 */
interface Registry {

    fun packument(name: String): Packument

    fun tarball(url: String): ByteArray
}

/** One published version's metadata, as the registry reports it. */
data class VersionRecord(
    val version: String,
    val dependencies: Map<String, String> = emptyMap(),
    val tarballUrl: String,
    val integrity: String? = null,
    val shasum: String? = null
)

/** One package's registry document: its dist-tags and every published version. */
data class Packument(
    val name: String,
    val distTags: Map<String, String>,
    val versions: List<VersionRecord>
)

/**
 * The public npm registry over plain HTTP calls.
 *
 * The base is a constant rather than a constructor argument, so no caller — and
 * in particular nothing a page or a package can reach — can point an install at
 * another host. A tarball URL is fetched only when its host is this registry's,
 * which closes the same door from the other side.
 */
class HttpRegistry(
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MS
) : Registry {

    override fun packument(name: String): Packument {
        val url = URL("$REGISTRY/${name.replace("/", "%2F")}")
        return PackumentJson.parse(name, String(readBytes(url, ACCEPT_PACKUMENT), Charsets.UTF_8))
    }

    override fun tarball(url: String): ByteArray {
        val parsed = try {
            URL(url)
        } catch (err: Exception) {
            throw IOException("the registry gave an unusable tarball URL: $url")
        }
        val host = parsed.host ?: throw IOException("the registry gave an unusable tarball URL: $url")
        val sameHost = host.equals(registryHost, ignoreCase = true)
        if (!parsed.protocol.equals("https", ignoreCase = true) || !sameHost) {
            throw IOException("refused a tarball that is not from $REGISTRY: $url")
        }
        return readBytes(parsed, null)
    }

    private fun readBytes(url: URL, accept: String?): ByteArray {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            // A redirect would leave the host this class has just checked.
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (accept != null) connection.setRequestProperty("Accept", accept)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val detail = connection.errorStream
                    ?.use { String(it.readBytes(), Charsets.UTF_8).take(200) }
                    .orEmpty()
                throw IOException("the registry answered HTTP $code for $url $detail".trim())
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {

        /** The one registry an install may talk to. */
        const val REGISTRY = "https://registry.npmjs.org"

        /** The abbreviated document, which carries everything an install reads. */
        private const val ACCEPT_PACKUMENT = "application/vnd.npm.install-v1+json"

        private const val USER_AGENT = "room-browser-node-packages"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        private val registryHost = URL(REGISTRY).host
    }
}

/** Tolerant by design: a registry document carries far more than an install reads. */
internal object PackumentJson {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(name: String, body: String): Packument {
        val document = json.decodeFromString(PackumentDocument.serializer(), body)
        val versions = document.versions.map { (key, record) ->
            VersionRecord(
                version = record.version.ifEmpty { key },
                dependencies = record.dependencies,
                tarballUrl = record.dist?.tarball.orEmpty(),
                integrity = record.dist?.integrity,
                shasum = record.dist?.shasum
            )
        }
        return Packument(name, document.distTags, versions)
    }
}

@Serializable
internal data class PackumentDocument(
    @SerialName("dist-tags") val distTags: Map<String, String> = emptyMap(),
    val versions: Map<String, VersionDocument> = emptyMap()
)

@Serializable
internal data class VersionDocument(
    val version: String = "",
    val dependencies: Map<String, String> = emptyMap(),
    val dist: DistDocument? = null
)

@Serializable
internal data class DistDocument(
    val tarball: String = "",
    val integrity: String? = null,
    val shasum: String? = null
)
