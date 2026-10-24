package com.roombrowser.data.skills

import com.roombrowser.data.db.AgentSkillDao
import com.roombrowser.data.db.AgentSkillEntity
import com.roombrowser.data.net.AppHttpClients
import com.roombrowser.domain.agent.AgentJson
import com.roombrowser.node.packages.TarGz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.stream.Collectors

/**
 * The transport a skill install fetches through — the one seam a JVM test
 * drives a whole install over, exactly as [com.roombrowser.node.packages.Registry]
 * is the one seam an npm install is driven over. Only https and only the two
 * GitHub hosts are ever handed to it by [SkillInstaller]; the seam exists for
 * testability, not for redirection.
 */
interface SkillFetch {
    fun get(url: HttpUrl, accept: String? = null): SkillHttpResponse
}

/** The answer as the wire gave it: a status, the body, and the reason line. */
class SkillHttpResponse(val code: Int, val body: ByteArray, val message: String) {
    val ok: Boolean get() = code in 200..299
}

/**
 * The app's own OkHttp client doing the fetching, on the AGENT scope — a skill
 * install is agent traffic, so the profile's "Route the AI agent" proxy covers
 * it like every other agent call (see [AppHttpClients]).
 *
 * Redirects are refused, not followed, for the same reason the npm installer
 * refuses them: a host check performed before the request is worthless if a
 * redirect can silently move the request to a host nobody checked. Neither
 * api.github.com nor codeload.github.com redirects for the shapes this
 * installer requests, so a redirect here is an attack signal, not a service.
 */
class HttpSkillFetch(client: OkHttpClient = AppHttpClients.agent()) : SkillFetch {

    private val http: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun get(url: HttpUrl, accept: String?): SkillHttpResponse {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { if (accept != null) header("Accept", accept) }
            .build()
        http.newCall(request).execute().use { response ->
            // Capped at read time: a Content-Length is a claim, and this is the
            // step where the claim would be trusted. One byte past the cap is
            // enough to know the answer, so the over-cap body is not buffered.
            val body = response.body?.byteStream()?.use { stream -> readCapped(stream) }
                ?: ByteArray(0)
            return SkillHttpResponse(response.code, body, response.message)
        }
    }

    private fun readCapped(stream: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(COPY_CHUNK)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) {
                throw java.io.IOException("the response is larger than ${MAX_RESPONSE_BYTES / MB} MB")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        const val USER_AGENT = "room-browser-agent-skills"
        const val COPY_CHUNK = 64 * 1024
        const val MB = 1024L * 1024L

        /**
         * The hard cap on any one response. The npm store refuses a package
         * unpacking to more than TarGz's 64 MB; a repo tarball for one skill
         * is a fraction of that, so a larger body is padding or a lie.
         */
        const val MAX_RESPONSE_BYTES = 64L * MB
    }
}

/** What an install produced, or the sentence explaining why it refused. */
sealed class SkillInstallResult {

    /**
     * Installed; [updated] is true when a skill with the same id already
     * existed (same id = replace, whatever its old source was). [entity] is
     * the row exactly as it now stands in the store.
     */
    data class Installed(
        val entity: AgentSkillEntity,
        val updated: Boolean,
        val npmDeps: List<String>
    ) : SkillInstallResult()

    /** Refused; the reason is written to be shown to a person as it is. */
    data class Refused(val reason: String) : SkillInstallResult()
}

/**
 * The narrow database seam the installer writes through — the two calls an
 * install needs — so a JVM test can pin what was stored without Room.
 */
interface SkillStore {
    suspend fun bySkillId(skillId: String): AgentSkillEntity?
    suspend fun upsert(entity: AgentSkillEntity)
}

/** The real store: the Room DAO the schema exposes (`db.agentSkillDao()`). */
class DaoSkillStore(private val dao: AgentSkillDao) : SkillStore {
    override suspend fun bySkillId(skillId: String): AgentSkillEntity? = dao.bySkillId(skillId)
    override suspend fun upsert(entity: AgentSkillEntity) = dao.upsert(entity)
}

/**
 * Installs a skill from a GitHub repository into `<skillsDir>/<skillId>/` —
 * SKILL.md, its reference files and whatever else the skill directory
 * carries — and upserts the [AgentSkillEntity] row the rest of the system
 * reads.
 *
 * NOTHING FROM A SKILL IS EVER EXECUTED. No script, no binary, no build
 * step: the files land inert, only the prompt and skills_read ever read
 * them, and any npm dependency a skill declares is REPORTED, not installed —
 * the user-gated node_packages path is the only thing that may fetch those
 * (see AgentNodePackages).
 *
 * SAFETY, in fetch order:
 *  1. The spec is parsed and segment-validated before any network call.
 *  2. Only https, only api.github.com / codeload.github.com, no redirects.
 *  3. TarGz unpacks with its own bounds (64 MB, 10 000 entries, absolute and
 *     `..` paths refused, link entries skipped).
 *  4. The extracted tree is walked for symlinks (nothing may materialize as
 *     one) and for the plugin installer's forbidden extensions — the same
 *     rulebook PluginZipInstaller enforces for a ZIP, duplicated here as
 *     constants on purpose: the plugin installer's private companion is not
 *     a shared surface, and the two rules must stay in lock-step by test.
 *  5. The skill directory is copied into a staging directory and renamed
 *     into place — the rename is the commit, and a refusal anywhere before
 *     it leaves the previously installed skill exactly as it was.
 *
 * JVM-testable with a fake [SkillFetch] and a fake [SkillStore].
 */
class SkillInstaller(
    private val skillsDir: File,
    private val fetch: SkillFetch,
    private val store: SkillStore
) {

    suspend fun install(rawSpec: String, nowMs: Long = System.currentTimeMillis()): SkillInstallResult =
        withContext(Dispatchers.IO) {
            val spec = when (val parsed = SkillSpecs.parse(rawSpec)) {
                is SkillSpecParse.Ok -> parsed.spec
                is SkillSpecParse.Invalid -> return@withContext SkillInstallResult.Refused(parsed.reason)
            }

            // Resolve the branch (and, when the API answers, the head commit
            // the tarball was cut from — recorded as the row's commitRef for
            // update checks). Anonymous GitHub API access is fine; a 403 rate
            // limit or an unreachable API costs only the sha, never the install.
            val defaultBranch = defaultBranchOf(spec)
            val candidates = listOfNotNull(spec.ref, defaultBranch, "main", "master").distinct()
            var tarball: ByteArray? = null
            var branch: String? = null
            var lastProblem = "the repository answered with nothing"
            for (candidate in candidates) {
                val result = try {
                    fetch.get(spec.tarballUrl(candidate).toHttpUrl(), null)
                } catch (err: Exception) {
                    lastProblem = err.message ?: err.javaClass.simpleName
                    continue
                }
                if (result.ok) {
                    tarball = result.body
                    branch = candidate
                    break
                }
                lastProblem = "HTTP ${result.code} ${result.message}".trim()
            }
            if (tarball == null || branch == null) {
                val named = spec.ref ?: defaultBranch ?: "its default branch"
                return@withContext SkillInstallResult.Refused(
                    "could not fetch ${spec.sourceRepo} (tried ${candidates.joinToString(", ")}; " +
                        "last answer: $lastProblem) — check the owner/repo path and the branch \"$named\""
                )
            }
            val commitRef = headShaOf(spec, branch) ?: branch

            // Stage → validate → locate → commit. Every failure between here
            // and the rename is absorbed by the finally below.
            skillsDir.mkdirs()
            val stage = File(skillsDir, ".stage-${UUID.randomUUID()}")
            try {
                val extracted = try {
                    TarGz.extract(tarball, stage)
                } catch (err: Exception) {
                    return@withContext SkillInstallResult.Refused(
                        "the repository archive could not be unpacked: ${err.message ?: err.javaClass.simpleName}"
                    )
                }
                if (extracted.isEmpty()) {
                    return@withContext SkillInstallResult.Refused(
                        "the repository archive holds no files"
                    )
                }
                extractionProblem(stage)?.let { return@withContext SkillInstallResult.Refused(it) }

                // codeload wraps everything in `<repo>-<branch>/`; a tree with
                // exactly one top-level directory and no loose files is that
                // wrapper, and the repo root is inside it.
                val root = repoRoot(stage)
                val skillDir = if (spec.subPath.isEmpty()) {
                    root
                } else {
                    File(root, spec.subPath)
                }
                if (!skillDir.isDirectory) {
                    return@withContext SkillInstallResult.Refused(
                        "no SKILL.md found at \"${spec.subPath}\" in ${spec.sourceRepo} — " +
                            "the path must name the folder that holds SKILL.md"
                    )
                }
                val skillMd = File(skillDir, SKILL_MD)
                if (!skillMd.isFile) {
                    val lookedIn = if (spec.subPath.isEmpty()) "the repository root" else "\"${spec.subPath}\""
                    return@withContext SkillInstallResult.Refused(
                        "no SKILL.md found at $lookedIn of ${spec.sourceRepo} — " +
                            "a skill is a directory whose SKILL.md carries the name and instructions"
                    )
                }
                val skillMdBytes = skillMd.readBytes()
                val manifest = SkillManifest.parse(String(skillMdBytes, Charsets.UTF_8))
                    ?: return@withContext SkillInstallResult.Refused(
                        "SKILL.md has no usable front-matter name — a skill must declare " +
                            "`name:` (and may declare `description:`) between --- fences"
                    )
                val skillId = SkillSpecs.skillIdFor(manifest.name)

                // The copy is the commit's payload: the whole skill directory —
                // SKILL.md, references/, everything — into a fresh staging
                // directory that will BE the installed skill.
                val commit = File(skillsDir, ".commit-${UUID.randomUUID()}")
                val destination = File(commit, skillId)
                try {
                    copyTree(skillDir, destination)
                    val existing = store.bySkillId(skillId)
                    val target = File(skillsDir, skillId)
                    // The rename is the commit: the old directory is removed
                    // only once the new tree sits fully staged beside it, so
                    // a failure before this line leaves the previous install
                    // untouched and one after it leaves the row authoritative.
                    if (target.exists() && !target.deleteRecursively()) {
                        return@withContext SkillInstallResult.Refused(
                            "the previously installed \"$skillId\" directory could not be replaced"
                        )
                    }
                    if (!destination.renameTo(target)) {
                        return@withContext SkillInstallResult.Refused(
                            "the skill directory could not be written (${target.name})"
                        )
                    }
                    val entity = AgentSkillEntity(
                        id = existing?.id ?: 0L,
                        skillId = skillId,
                        displayName = manifest.name.take(SkillManifest.MAX_NAME),
                        description = manifest.description,
                        sourceRepo = spec.sourceRepo,
                        subPath = spec.subPath,
                        version = manifest.version,
                        commitRef = commitRef,
                        skillMdSha256 = sha256Hex(skillMdBytes),
                        // Declared, never installed: the report points the
                        // model at node_packages, the user-gated path.
                        npmDepsJson = manifest.npmDeps.joinToString(",", "[", "]") { pkg ->
                            JsonPrimitive(pkg).toString()
                        },
                        // A re-install keeps the enabled state the user chose;
                        // a first install is enabled, because a skill nobody
                        // enabled is never followed and the install said so.
                        enabled = existing?.enabled ?: true,
                        installedAt = existing?.installedAt ?: nowMs,
                        updatedAt = nowMs
                    )
                    store.upsert(entity)
                    SkillInstallResult.Installed(
                        entity = entity,
                        updated = existing != null,
                        npmDeps = manifest.npmDeps
                    )
                } finally {
                    commit.deleteRecursively()
                }
            } finally {
                stage.deleteRecursively()
            }
        }

    /**
     * The repo API's default_branch, or null when the API is unreachable,
     * rate-limited (403), or the repo is not there — the install falls back
     * to trying main and then master regardless.
     */
    private fun defaultBranchOf(spec: SkillSpec): String? {
        val url = "https://api.github.com/repos/${spec.owner}/${spec.repo}".toHttpUrl()
        val response = try {
            fetch.get(url, ACCEPT_GITHUB_JSON)
        } catch (err: Exception) {
            return null
        }
        if (!response.ok) return null
        return runCatching {
            (AgentJson.parseToJsonElement(String(response.body, Charsets.UTF_8)).jsonObject["default_branch"]
                as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * The branch head's commit sha, or null when the API will not say — the
     * branch name is recorded instead, which is the honest resolution the
     * fallback path always had.
     */
    private fun headShaOf(spec: SkillSpec, branch: String): String? {
        val url = ("https://api.github.com/repos/${spec.owner}/${spec.repo}/branches/" +
            java.net.URLEncoder.encode(branch, Charsets.UTF_8)).toHttpUrl()
        val response = try {
            fetch.get(url, ACCEPT_GITHUB_JSON)
        } catch (err: Exception) {
            return null
        }
        if (!response.ok) return null
        return runCatching {
            val document = AgentJson.parseToJsonElement(String(response.body, Charsets.UTF_8)).jsonObject
            ((document["commit"] as? kotlinx.serialization.json.JsonObject)?.get("sha") as? JsonPrimitive)
                ?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * The belt-and-braces walk of the REAL extracted tree, before anything is
     * copied: a symlink that materialized by any means and a file carrying a
     * forbidden extension each refuse the install, exactly as the plugin
     * installer refuses them (FORBIDDEN_EXTENSIONS there, FORBIDDEN here —
     * the two lists are kept identical on purpose and pinned by test). One
     * walk collects both verdicts: the stream must be closed (it holds an
     * open directory handle), and a collected list is the honest way to
     * iterate it without a terminal op the caller would have to know about.
     */
    private fun extractionProblem(stage: File): String? {
        // Every path, links included: a symlink entry — even a broken one —
        // is itself a node in the walk, and refusing it is the point. The
        // extension pass below then skips what is not a regular file, which
        // is exactly what a link is.
        val paths = Files.walk(stage.toPath()).use { it.collect(Collectors.toList()) }
        val linked = paths.firstOrNull { Files.isSymbolicLink(it) }
        if (linked != null) {
            return "the repository archive extracted a symlink (" +
                stage.toPath().relativize(linked) + "), which a skill may not carry"
        }
        for (path in paths) {
            if (!Files.isRegularFile(path)) continue
            val extension = path.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (extension in FORBIDDEN_EXTENSIONS) {
                return "the repository carries \"${stage.toPath().relativize(path)}\", a native " +
                    "executable or package, which a skill may not contain"
            }
        }
        return null
    }

    /** The one top-level directory of a codeload tree, or the stage itself. */
    private fun repoRoot(stage: File): File {
        val children = stage.listFiles().orEmpty()
        val directories = children.filter { it.isDirectory }
        return if (directories.size == 1 && children.size == 1) directories.single() else stage
    }

    /** Copies [from] and everything under it into [to]; regular files only. */
    private fun copyTree(from: File, to: File) {
        to.mkdirs()
        for (child in from.listFiles().orEmpty().sortedBy { it.name }) {
            val target = File(to, child.name)
            if (child.isDirectory) {
                copyTree(child, target)
            } else {
                child.copyTo(target, overwrite = false)
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val SKILL_MD = "SKILL.md"
        private const val ACCEPT_GITHUB_JSON = "application/vnd.github+json"

        /**
         * The plugin ZIP rulebook's extension list, duplicated rather than
         * imported: PluginZipInstaller keeps it in a private companion, and
         * widening it to internal for one more caller would make the plugin
         * store's contract mutable from outside its file. SkillGatesTest
         * pins the two lists equal, so a rule added to one and not the other
         * fails CI instead of silently diverging.
         */
        val FORBIDDEN_EXTENSIONS = setOf(
            "node", "so", "dll", "exe", "bat", "sh", "jar", "apk"
        )
    }
}
