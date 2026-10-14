package com.roombrowser.node.packages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

private const val NODE_MODULES = "node_modules"
private const val MANIFEST = "package.json"

/** npm's cap on a package name, scope included. */
private const val MAX_NAME_LENGTH = 214

private val NAME_CHARS: Set<Char> =
    ('a'..'z').toSet() + ('0'..'9').toSet() + setOf('-', '.', '_', '~')

/**
 * Null when [name] may be turned into a path, otherwise why it may not.
 *
 * This is npm's own rule set — lowercase, `@scope/name` allowed, at most one
 * separator — and it is the only thing standing between a registry-supplied
 * string and the file system, so it refuses rather than sanitises.
 */
internal fun invalidPackageName(name: String): String? {
    if (name.isEmpty()) return "the package name is empty"
    if (name.length > MAX_NAME_LENGTH) {
        return "\"$name\" is longer than $MAX_NAME_LENGTH characters"
    }
    val parts = name.split('/')
    val scoped = name.startsWith("@")
    if (parts.size != if (scoped) 2 else 1) return "\"$name\" is not a valid package name"
    for (part in parts) {
        val body = part.removePrefix("@")
        if (body.isEmpty()) return "\"$name\" is not a valid package name"
        if (body.startsWith(".") || body.startsWith("_")) {
            return "\"$name\" starts with a character npm reserves"
        }
        if (!body.all { it in NAME_CHARS }) {
            return "\"$name\" has characters npm does not allow"
        }
    }
    return null
}

/** One package found in the install tree. [version] is null when its manifest is unreadable. */
data class InstalledPackage(
    val name: String,
    val version: String?,
    val dependencies: Map<String, String>,
    val path: File
)

/** What one install did, and what it deliberately left alone. */
data class InstallReport(
    val name: String,
    val version: String? = null,
    val installed: List<InstalledPackage> = emptyList(),
    val skipped: List<String> = emptyList(),
    val error: String? = null
) {
    val ok: Boolean get() = error == null
}

/**
 * Installs plain-JavaScript npm packages into the directory the embedded
 * runtime's `require()` resolves from — `<nodeDir>/node_modules`, which is
 * beside the copy of `bridge.js` that `require` starts from.
 *
 * NOTHING FROM A PACKAGE IS EVER EXECUTED. No `scripts` field is read, no
 * lifecycle script runs, at install time or afterwards; a package's
 * `postinstall` is inert here.
 *
 * DELIBERATE LIMITS. Only `dependencies` are installed — `devDependencies`,
 * `optionalDependencies` and `peerDependencies` are left alone and reported in
 * [InstallReport.skipped]. Dependencies are NESTED under their parent
 * (`<pkg>/node_modules/<dep>`) instead of hoisted, so two packages may each get
 * their own copy of a dependency and a version conflict cannot arise at all;
 * the cost is a bigger tree and the depth cap in [MAX_DEPTH].
 */
class NodePackageStore(
    private val nodeDir: File,
    private val registry: Registry
) {

    /** Every package directly under `<nodeDir>/node_modules`. */
    fun installed(): List<InstalledPackage> {
        val nodeModules = nodeModules()
        if (!nodeModules.isDirectory) return emptyList()
        val found = mutableListOf<InstalledPackage>()
        for (entry in nodeModules.listFiles().orEmpty().sortedBy { it.name }) {
            if (entry.name.startsWith(".")) continue
            if (!entry.name.startsWith("@")) {
                if (entry.isDirectory) found += describe(entry.name, entry)
                continue
            }
            for (scoped in entry.listFiles().orEmpty().sortedBy { it.name }) {
                if (scoped.isDirectory) found += describe("${entry.name}/${scoped.name}", scoped)
            }
        }
        return found
    }

    /** Deletes one package's directory; false for a bad name or nothing to delete. */
    fun remove(name: String): Boolean {
        if (invalidPackageName(name) != null) return false
        val target = File(nodeModules(), name)
        if (!target.exists()) return false
        if (!target.deleteRecursively()) return false
        val scope = target.parentFile
        if (scope != null && scope.name.startsWith("@") && scope.list().orEmpty().isEmpty()) {
            scope.delete()
        }
        return true
    }

    /**
     * Installs [name] at [spec] — a version, a range, a dist-tag, or null for
     * `latest` — with its dependencies, and reports what happened.
     *
     * The whole tree is built in a temporary directory under [nodeDir] and only
     * then moved into place, so a refusal anywhere leaves the installed set
     * exactly as it was.
     */
    fun install(name: String, spec: String?): InstallReport {
        invalidPackageName(name)?.let { return InstallReport(name, error = it) }
        nodeDir.mkdirs()
        val staging = Files.createTempDirectory(nodeDir.toPath(), ".install-").toFile()
        try {
            val placed = mutableListOf<Placed>()
            val skipped = mutableListOf<String>()
            val root = File(staging, "root")
            place(name, spec, root, "", 0, placed, skipped)?.let { problem ->
                return InstallReport(name, skipped = skipped.toList(), error = problem)
            }
            val target = File(nodeModules(), name)
            target.parentFile?.mkdirs()
            if (target.exists() && !target.deleteRecursively()) {
                return InstallReport(name, skipped = skipped.toList(), error = "could not replace the existing $name")
            }
            if (!root.renameTo(target)) {
                return InstallReport(name, skipped = skipped.toList(), error = "could not move $name into node_modules")
            }
            val installed = placed.map {
                InstalledPackage(
                    name = it.name,
                    version = it.version,
                    dependencies = it.dependencies,
                    path = if (it.relative.isEmpty()) target else File(target, it.relative)
                )
            }
            return InstallReport(
                name = name,
                version = placed.firstOrNull()?.version,
                installed = installed,
                skipped = skipped.toList()
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Resolves, verifies, unpacks and recurses into [dest]; the reason it could not, or null. */
    private fun place(
        name: String,
        spec: String?,
        dest: File,
        relative: String,
        depth: Int,
        placed: MutableList<Placed>,
        skipped: MutableList<String>
    ): String? {
        if (depth > MAX_DEPTH) {
            return "$name is nested more than $MAX_DEPTH levels deep, which this installer does not follow"
        }
        val packument = try {
            registry.packument(name)
        } catch (err: Exception) {
            return "could not read the registry entry for $name: ${reason(err)}"
        }
        val wanted = spec?.trim().orEmpty().ifEmpty { "latest" }
        val version = resolveVersion(packument, spec)
            ?: return "no published version of $name matches \"$wanted\""
        val record = packument.versions.firstOrNull { it.version == version }
            ?: return "the registry lists no tarball for $name@$version"
        val bytes = try {
            registry.tarball(record.tarballUrl)
        } catch (err: Exception) {
            return "could not download $name@$version: ${reason(err)}"
        }
        verify(name, version, bytes, record)?.let { return it }
        dest.parentFile?.mkdirs()
        val manifest = try {
            if (TarGz.extract(bytes, dest).isEmpty()) {
                return "the tarball for $name@$version holds no files"
            }
            manifestOf(dest)
        } catch (err: Exception) {
            return "could not unpack $name@$version: ${reason(err)}"
        }
        val dependencies = manifest?.dependencies.orEmpty().ifEmpty { record.dependencies }
        placed += Placed(name, version, dependencies, relative)
        if (manifest != null) {
            val ignored = manifest.ignoredFields()
            if (ignored.isNotEmpty()) {
                skipped += "$name@$version: ${ignored.joinToString(", ")} are not installed"
            }
        }
        for ((dependency, dependencySpec) in dependencies) {
            val badName = invalidPackageName(dependency)
            if (badName != null) {
                skipped += "$dependency (wanted by $name@$version) was not installed: $badName"
                continue
            }
            val nested = if (relative.isEmpty()) {
                "$NODE_MODULES/$dependency"
            } else {
                "$relative/$NODE_MODULES/$dependency"
            }
            place(
                dependency,
                dependencySpec,
                File(File(dest, NODE_MODULES), dependency),
                nested,
                depth + 1,
                placed,
                skipped
            )?.let { return it }
        }
        return null
    }

    /** A null, empty or `*` spec is `latest`; a named dist-tag is that tag; else a range. */
    private fun resolveVersion(packument: Packument, spec: String?): String? {
        val text = spec?.trim().orEmpty()
        if (text.isEmpty() || text == "*" || text == "latest") {
            packument.distTags["latest"]?.let { return it }
        }
        packument.distTags[text]?.let { return it }
        return Semver.highest(packument.versions.map { it.version }, text)
    }

    /**
     * Checks the tarball against the registry's own digest BEFORE anything is
     * unpacked, because unpacking is the step that touches the file system.
     */
    private fun verify(
        name: String,
        version: String,
        bytes: ByteArray,
        record: VersionRecord
    ): String? {
        val tokens = record.integrity?.trim().orEmpty().split(WHITESPACE).filter { it.isNotEmpty() }
        for (token in tokens) {
            val dash = token.indexOf('-')
            if (dash <= 0) continue
            val digest = digestFor(token.substring(0, dash)) ?: continue
            val expected = try {
                Base64.getDecoder().decode(token.substring(dash + 1))
            } catch (err: IllegalArgumentException) {
                return "$name@$version came with an unreadable integrity value"
            }
            return if (digest.digest(bytes).contentEquals(expected)) {
                null
            } else {
                "$name@$version does not match its integrity value"
            }
        }
        val shasum = record.shasum?.trim().orEmpty()
        if (shasum.isEmpty()) return "$name@$version came with no digest to check it against"
        val actual = digestFor("sha1")!!.digest(bytes).joinToString("") { "%02x".format(it) }
        return if (actual.equals(shasum, ignoreCase = true)) null else "$name@$version does not match its shasum"
    }

    private fun digestFor(algorithm: String): MessageDigest? {
        val name = when (algorithm.lowercase()) {
            "sha512" -> "SHA-512"
            "sha384" -> "SHA-384"
            "sha256" -> "SHA-256"
            "sha1" -> "SHA-1"
            else -> return null
        }
        return MessageDigest.getInstance(name)
    }

    /** Null when the manifest is missing or unreadable, which is not a failure. */
    private fun manifestOf(directory: File): Manifest? {
        val file = File(directory, MANIFEST)
        if (!file.isFile) return null
        val document = try {
            manifestJson.parseToJsonElement(file.readText()).jsonObject
        } catch (err: Exception) {
            return null
        }
        return Manifest(
            version = (document["version"] as? JsonPrimitive)?.contentOrNull,
            dependencies = strings(document["dependencies"]),
            devDependencies = strings(document["devDependencies"]),
            optionalDependencies = strings(document["optionalDependencies"]),
            peerDependencies = strings(document["peerDependencies"])
        )
    }

    private fun strings(element: JsonElement?): Map<String, String> =
        (element as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }
            ?.toMap()
            .orEmpty()

    private fun describe(name: String, directory: File): InstalledPackage {
        val manifest = manifestOf(directory)
        return InstalledPackage(name, manifest?.version, manifest?.dependencies.orEmpty(), directory)
    }

    private fun nodeModules(): File = File(nodeDir, NODE_MODULES)

    private fun reason(err: Exception): String = err.message ?: err::class.java.simpleName

    private data class Placed(
        val name: String,
        val version: String?,
        val dependencies: Map<String, String>,
        val relative: String
    )

    private data class Manifest(
        val version: String?,
        val dependencies: Map<String, String>,
        val devDependencies: Map<String, String>,
        val optionalDependencies: Map<String, String>,
        val peerDependencies: Map<String, String>
    ) {
        fun ignoredFields(): List<String> = buildList {
            if (devDependencies.isNotEmpty()) add("devDependencies")
            if (optionalDependencies.isNotEmpty()) add("optionalDependencies")
            if (peerDependencies.isNotEmpty()) add("peerDependencies")
        }
    }

    companion object {

        /** How deep the nesting may go; the package the caller asked for is 0. */
        internal const val MAX_DEPTH = 10

        private val WHITESPACE = Regex("\\s+")

        private val manifestJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
