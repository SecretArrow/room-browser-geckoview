package com.roombrowser.agent.skills

/**
 * A skill's origin, parsed down to the four pieces a GitHub tarball download
 * needs: owner, repository, ref and the subdirectory inside the archive.
 *
 * THE SPELLINGS IT ACCEPTS (the ones a person or a CLI tutorial actually
 * writes, and the ones the system prompt tells the model to translate):
 *
 *   owner/repo
 *   owner/repo/path/to/dir
 *   owner/repo@branch
 *   owner/repo/path/to/dir@branch
 *   https://github.com/owner/repo
 *   https://github.com/owner/repo/tree/branch/path/to/dir
 *
 * [SkillSource.parse] is the one door and answers every refusal in words the
 * model can act on. Everything else in the package trusts it: the installer
 * builds tarball URLs only from these four fields, so a string that never
 * became a [SkillSource] can never become a download.
 *
 * GITHUB ONLY, ON PURPOSE. A skill install drops files under the app's own
 * data directory; the host is the trust decision, and codeload.github.com is
 * the one host this installer will name. A `git@` SSH form, an `http://`
 * URL (TLS is not negotiable here) or any other host is refused with the
 * refusal naming what was wrong — a refusal that reads like a typo sends the
 * model hunting for another route to the same download.
 */
data class SkillSource(
    val owner: String,
    val repo: String,
    val ref: String,
    val subPath: String,
    /** The string the user actually gave, stored verbatim in the skill's metadata. */
    val raw: String
) {

    /**
     * The codeload tarball URLs to try, in order: the explicit `refs/heads/`
     * form resolves branches directly, and the bare form resolves everything
     * else — tags, commit hashes and `HEAD`, the default branch. A URL is
     * built ONLY from the parsed fields, never from the raw string, so no
     * spelling quirk can smuggle a path segment past this constructor.
     */
    val tarballUrls: List<String>
        get() = buildList {
            if (ref != DEFAULT_REF) add("$CODELOAD/$owner/$repo/tar.gz/refs/heads/$ref")
            add("$CODELOAD/$owner/$repo/tar.gz/$ref")
        }

    /** `owner/repo[/subPath]@ref` — the short spelling, for reports and labels. */
    val shortForm: String
        get() = buildString {
            append(owner).append('/').append(repo)
            if (subPath.isNotEmpty()) append('/').append(subPath)
            if (ref != DEFAULT_REF) append('@').append(ref)
        }

    companion object {

        /** The codeload host — the one host a skill tarball may come from. */
        const val CODELOAD = "https://codeload.github.com"

        /** The default ref: codeload's name for the repository's default branch. */
        const val DEFAULT_REF = "HEAD"

        private const val GITHUB_HOST = "github.com"
        private const val MAX_SEGMENTS = 12

        /** The characters GitHub itself allows in an owner or repository name. */
        private val NAME_OK = Regex("^[A-Za-z0-9._-]+$")

        /**
         * Parses [raw] into a source, or returns the reason it was refused.
         * The refusal sentences name the fix (the host, the missing segment,
         * the file-shaped URL) because this text is read by the model, which
         * can rewrite its next call — and by a person pasting into the
         * manager screen, who can too.
         */
        fun parse(raw: String): Result<SkillSource> {
            val text = raw.trim()
            if (text.isEmpty()) {
                return failure("the source is empty — give 'owner/repo', 'owner/repo/path', " +
                    "or a https://github.com URL")
            }
            if (text.length > MAX_SOURCE_CHARS) {
                return failure("the source is too long to be a GitHub path (${text.length} characters)")
            }
            if (text.any { it.code < 0x20 || it.code == 0x7F }) {
                return failure("the source contains control characters")
            }

            // Strip one accepted spelling of the front, refuse every other.
            // https:// is the only scheme; TLS is how an install travels, and
            // an http:// URL is refused rather than quietly upgraded, so the
            // user sees the rule instead of a silent exception to it.
            var rest = text
            if (rest.startsWith("$GITHUB_HOST/", ignoreCase = true)) {
                rest = rest.substring(GITHUB_HOST.length + 1)
            } else {
                val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").find(rest)?.value
                if (scheme != null) {
                    if (!text.lowercase().startsWith("https://")) {
                        return failure("'$scheme…' is refused: skill installs travel over https only")
                    }
                    val withoutScheme = rest.substring("https://".length)
                    val host = withoutScheme.substringBefore('/').substringBefore(':')
                        .lowercase().removePrefix("www.")
                    if (host != GITHUB_HOST) {
                        return failure("'$host' is not a GitHub host — skills install only " +
                            "from github.com")
                    }
                    rest = withoutScheme.substringAfter('/', "")
                    if (rest.isEmpty()) {
                        return failure("the GitHub URL names no repository — use " +
                            "https://github.com/owner/repo")
                    }
                } else if (rest.startsWith("git@") || rest.contains("git@github.com")) {
                    return failure("SSH remote spellings (git@…) are refused — give the " +
                        "https://github.com URL or the plain owner/repo form")
                }
            }
            rest = rest.trimEnd('/')

            // The ref suffix: everything after the LAST '@'. A branch can carry
            // a slash; a path segment with an '@' in it is rare enough that
            // taking the last one as the ref marker is the right trade.
            var ref = DEFAULT_REF
            val at = rest.lastIndexOf('@')
            if (at >= 0) {
                ref = rest.substring(at + 1).trim()
                if (ref.isEmpty()) {
                    return failure("'@' names no branch — put the branch after it, as in " +
                        "owner/repo@main")
                }
                if (!refOk(ref)) {
                    return failure("the ref '$ref' has characters a git ref may not carry")
                }
                rest = rest.substring(0, at)
            }

            val segments = rest.split('/').filter { it.isNotEmpty() }
            if (segments.size < 2) {
                return failure("'$text' does not name a repository — a source needs at " +
                    "least 'owner/repo'")
            }
            if (segments.size > MAX_SEGMENTS) {
                return failure("the source has too many path segments (${segments.size})")
            }
            val owner = segments[0]
            val repo = segments[1].removeSuffix(".git")
            for (name in listOf(owner to "owner", repo to "repository")) {
                if (!NAME_OK.matches(name.first) || name.first == "." || name.first == "..") {
                    return failure("'${name.first}' is not a valid GitHub ${name.second} name")
                }
            }

            // The URL tree form and the plain path form are the same segments
            // after owner/repo, except that `tree` is the ref's marker and is
            // not part of any path. A `blob` URL points at ONE file, and a
            // skill is a directory, so it is refused with the reason rather
            // than silently installed from the file's parent.
            if (segments.size > 2 && segments[2].equals("tree", ignoreCase = true)) {
                if (ref != DEFAULT_REF) {
                    return failure("the source names a branch twice — use either " +
                        "'owner/repo/tree/BRANCH/…' or '@BRANCH', not both")
                }
                if (segments.size == 3) {
                    return failure("the tree URL names no branch — use " +
                        "https://github.com/owner/repo/tree/BRANCH")
                }
                ref = segments[3]
                if (!refOk(ref)) {
                    return failure("the ref '$ref' has characters a git ref may not carry")
                }
                return Result.success(
                    SkillSource(owner, repo, ref, segments.drop(4).joinToString("/"), text)
                )
            }
            if (segments.size > 2 && segments[2].equals("blob", ignoreCase = true)) {
                return failure("that URL points at a single file (blob) — a skill is a " +
                    "directory; use the tree URL or the plain owner/repo/path form")
            }
            val subPath = segments.drop(2).joinToString("/")
            if (subPath.split('/').any { it == "." || it == ".." }) {
                return failure("the sub-path climbs out of the repository ('.' and '..' are not allowed)")
            }
            return Result.success(SkillSource(owner, repo, ref, subPath, text))
        }

        /**
         * The CLI spellings a README tells people to type — `npx skills add …`,
         * `skills add …` — are not part of the source, and a user pastes them
         * verbatim. This removes the well-known prefixes so the same string
         * parses wherever it arrives: the chat tool's install action and the
         * manager screen's install field both go through here, so a spelling
         * one door accepts the other cannot refuse. The scheme stripping and
         * the host checks stay [parse]'s business.
         */
        fun stripCliSpelling(raw: String): String = raw.trim()
            .removePrefix("npx skills add ")
            .removePrefix("skills add ")
            .trim()

        private fun refOk(ref: String): Boolean =
            ref.none { it.isWhitespace() } &&
                !ref.startsWith("/") &&
                !ref.endsWith("/") &&
                !ref.contains("..") &&
                ref.none { it.code < 0x20 || it.code == 0x7F }

        private fun failure(reason: String): Result<SkillSource> =
            Result.failure(IllegalArgumentException(reason))

        /** Generous but bounded: a legitimate GitHub path never approaches this. */
        const val MAX_SOURCE_CHARS = 300
    }
}
