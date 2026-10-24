package com.roombrowser.data.skills

/**
 * The two pure halves of a skill install: turning what a user or a model said
 * into a repository location ([SkillSpecs]), and turning a fetched SKILL.md
 * into the metadata the skills row is built from ([SkillManifest]).
 *
 * Both are deliberately free of Android and network types so the whole
 * grammar — including every refusal — is pinned by JVM tests. The parser
 * refuses rather than sanitises: a path this file would not parse is a path
 * that must never be fetched, because these strings are what the fetcher and
 * the file system are pointed at.
 */

/** Where inside GitHub a skill lives. [subPath] is "" for a repo-root skill. */
data class SkillSpec(
    val owner: String,
    val repo: String,
    val subPath: String = "",
    /** A branch named in a /tree/ URL, or null to resolve the repo's default. */
    val ref: String? = null
) {
    /** `owner/repo` — the provenance stored on the row and shown in the UI. */
    val sourceRepo: String get() = "$owner/$repo"

    /** The full location as a person would spell it back. */
    val displayPath: String get() = if (subPath.isEmpty()) sourceRepo else "$sourceRepo/$subPath"

    /** The codeload tarball for [branch], percent-safe per path segment. */
    fun tarballUrl(branch: String): String = buildString {
        append("https://codeload.github.com/").append(owner).append('/').append(repo)
        append("/tar.gz/refs/heads/")
        // A branch may itself contain slashes (feature/x); each SEGMENT is
        // encoded, never the separators, or the URL would name the wrong ref.
        append(branch.split('/').joinToString("/") { segment -> urlEncode(segment) })
    }
}

/** What [SkillSpecs.parse] produced: a usable spec, or the reason it refused. */
sealed class SkillSpecParse {
    data class Ok(val spec: SkillSpec) : SkillSpecParse()
    data class Invalid(val reason: String) : SkillSpecParse()
}

/**
 * Parses the many shapes a skill install request arrives in — the CLI string
 * (`npx skills add owner/repo[/path]`), a bare `owner/repo[/path]`, a GitHub
 * URL, or a natural-language sentence naming the repo — into a [SkillSpec].
 *
 * Everything is refused with a sentence the requester can act on, never
 * sanitised into a guess: a guessed repository would be fetched, and a fetch
 * is the one step this grammar must never get wrong.
 */
object SkillSpecs {

    /** One segment of an owner/repo/path: GitHub's own username charset. */
    private val SEGMENT = Regex("[A-Za-z0-9_.-]+")

    /** A run of `segment/segment/...` inside free text (natural language). */
    private val CANDIDATE = Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+")

    private const val MAX_SEGMENTS = 16
    private const val MAX_SEGMENT_LENGTH = 100

    /** The sub-path segments that are GitHub's own navigation, not a skill path. */
    private const val TREE = "tree"
    private const val BLOB = "blob"

    private val CLI_PREFIXES = listOf(
        "npx -y skills add ",
        "npx -y skills install ",
        "npx skills add ",
        "npx skills install ",
        "skills add ",
        "skills install "
    )

    /**
     * Parses [raw]. Accepted shapes, in the order they are tried:
     *
     *  - `npx skills add <spec>` / `skills add <spec>` (install also accepted)
     *  - `https://github.com/owner/repo[/tree/branch[/path]]`
     *  - a bare `owner/repo[/path]`
     *  - a sentence naming one `owner/repo[/path]` — "install the wallet skill
     *    from binance/binance-skills-hub/skills/x". Ambiguous input (several
     *    candidates, or none) is refused with a message asking for the path.
     */
    fun parse(raw: String): SkillSpecParse {
        val text = raw.trim().trim('"', '\'', '`')
        if (text.isEmpty()) {
            return SkillSpecParse.Invalid(
                "no skill was named — give the repo path, " +
                    "e.g. `npx skills add owner/repo/path/to/skill`"
            )
        }
        // The URL check runs before the prefix strip: "npx skills add https://..."
        // is a legal CLI spelling, and the URL parser is the right one for it.
        if (text.contains("github.com/")) return parseUrl(text)
        val stripped = stripCliPrefix(text)
        val segments = stripped.split('/')
        return when {
            segments.size == 1 -> parseNaturalLanguage(text)
            segments[0].contains(' ') || segments[0].contains('\t') -> parseNaturalLanguage(text)
            else -> buildSpec(segments, stripped)
        }
    }

    /** `npx skills add`, `skills add`, and their `install` spellings, once each. */
    private fun stripCliPrefix(text: String): String {
        val lowered = text.lowercase()
        for (prefix in CLI_PREFIXES) {
            if (lowered.startsWith(prefix)) {
                // Anything after the spec itself (a second word, a flag) is not
                // a shape this parser accepts: it fails segment validation below
                // rather than being silently dropped, because a dropped flag
                // would be an install the requester did not quite ask for.
                return text.substring(prefix.length).trim()
            }
        }
        return text
    }

    private fun parseUrl(text: String): SkillSpecParse {
        val start = text.indexOf("github.com/")
        // Only https is accepted: a skill install is a fetch, and an http URL
        // would let a rewrite between here and GitHub choose the content.
        if (text.substring(0, start).contains("http://")) {
            return SkillSpecParse.Invalid("only https://github.com URLs are accepted")
        }
        val path = text.substring(start + "github.com/".length).trimStart('/').trimEnd('/')
        // A query or fragment is not part of a skill location; refuse rather
        // than guess what the requester meant to keep.
        if (path.contains('?') || path.contains('#')) {
            return SkillSpecParse.Invalid("the GitHub URL carries a query or fragment — give the plain repo path")
        }
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) {
            return SkillSpecParse.Invalid("the URL does not name owner/repo — e.g. https://github.com/owner/repo")
        }
        val rest = segments.drop(2).toMutableList()
        if (rest.firstOrNull() == BLOB) {
            return SkillSpecParse.Invalid(
                "the URL points at a single file (blob), not a skill directory — " +
                    "use the /tree/ URL of the folder that holds SKILL.md"
            )
        }
        var ref: String? = null
        if (rest.firstOrNull() == TREE) {
            if (rest.size < 2) {
                return SkillSpecParse.Invalid("the /tree/ URL names no branch")
            }
            ref = rest[1]
            rest.removeAt(0)
            rest.removeAt(0)
        }
        return buildSpec(segments.take(2) + rest, path, ref)
    }

    /**
     * Free text: find the `owner/repo/...` runs and use the ONE that validates.
     * Several candidates means the requester must say which they meant — the
     * installer fetching the wrong repo is the one failure this parser exists
     * to make impossible.
     */
    private fun parseNaturalLanguage(text: String): SkillSpecParse {
        val candidates = CANDIDATE.findAll(text)
            .mapNotNull { match ->
                val parsed = buildSpec(match.value.split('/'), match.value)
                if (parsed is SkillSpecParse.Ok) parsed.spec else null
            }
            .toList()
            .distinctBy { Triple(it.owner, it.repo, it.subPath) }
        return when {
            candidates.isEmpty() -> SkillSpecParse.Invalid(
                "could not find a repository path in that — " +
                    "name it as owner/repo, or paste the full path to the skill's folder"
            )
            candidates.size > 1 -> SkillSpecParse.Invalid(
                "more than one repository path appears in that (" +
                    candidates.joinToString(", ") { it.displayPath } +
                    ") — say which one, or paste the single path to the skill's folder"
            )
            else -> SkillSpecParse.Ok(candidates.single())
        }
    }

    private fun buildSpec(segments: List<String>, raw: String, ref: String? = null): SkillSpecParse {
        if (segments.isEmpty() || segments.size > MAX_SEGMENTS) {
            return SkillSpecParse.Invalid(
                "the path has more than $MAX_SEGMENTS segments — that is not a skill location"
            )
        }
        val owner = segments[0]
        if (!validSegment(owner)) {
            return SkillSpecParse.Invalid("\"${owner.take(60)}\" is not a valid GitHub owner name")
        }
        val repo = segments[1].removeSuffix(".git")
        if (!validSegment(repo)) {
            return SkillSpecParse.Invalid("\"${segments[1].take(60)}\" is not a valid GitHub repository name")
        }
        // A /tree/ branch inside a bare spec (`owner/repo/tree/main/skills/x`)
        // arrives here through the same segments the URL parser uses.
        val pathSegments = segments.drop(2)
        var subRef: String? = ref
        val sub: List<String>
        if (pathSegments.firstOrNull() == TREE) {
            if (pathSegments.size < 2) {
                return SkillSpecParse.Invalid("a /tree/ path names no branch")
            }
            subRef = pathSegments[1]
            sub = pathSegments.drop(2)
        } else {
            sub = pathSegments
        }
        for (segment in sub) {
            if (!validSegment(segment)) {
                return SkillSpecParse.Invalid("\"${segment.take(60)}\" is not a usable folder name in a skill path")
            }
        }
        return SkillSpecParse.Ok(
            SkillSpec(
                owner = owner,
                repo = repo,
                subPath = sub.joinToString("/"),
                ref = subRef
            )
        )
    }

    /**
     * GitHub's username/repo charset, with the traversal and dot tricks a
     * path is otherwise attackable through refused outright: a segment that
     * starts with a dot hides from directory listings (`..` included), so it
     * is never accepted even though GitHub itself would.
     */
    private fun validSegment(segment: String): Boolean =
        segment.isNotEmpty() &&
            segment.length <= MAX_SEGMENT_LENGTH &&
            SEGMENT.matches(segment) &&
            segment != ".." &&
            segment != "." &&
            !segment.startsWith(".")

    /**
     * The SKILL.md `name` as the on-disk directory and the tools' stable
     * handle: lowercase, `[a-z0-9-]`, everything else collapsed to one `-`,
     * trimmed, capped at 64. Collisions are resolved by the installer's
     * upsert (same skillId = replace), never by inventing a suffix — the
     * model must be able to name the skill it just installed.
     */
    fun skillIdFor(name: String): String {
        val collapsed = name.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9' || it == '-') it else '-' }
            .joinToString("")
            .replace("-+".toRegex(), "-")
            .trim('-')
        val capped = if (collapsed.length > 64) collapsed.take(64).trimEnd('-') else collapsed
        return capped.ifEmpty { "skill" }
    }

    private fun urlEncode(segment: String): String =
        java.net.URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")
}

/**
 * One parsed SKILL.md: the front-matter fields the skills row is built from,
 * plus the BODY — everything after the closing `---` — which is what the
 * prompt block injects. [name] is the required field; a SKILL.md without one
 * is an invalid skill and the installer refuses it.
 */
class SkillMd(
    val name: String,
    val description: String,
    val version: String,
    /** npm packages the `openclaw.install` hints declare (`kind: node`). */
    val npmDeps: List<String>,
    val body: String
)

/**
 * The minimal YAML subset SKILL.md front-matter uses — and only that subset,
 * because a YAML library's generality is exactly the surface a malicious
 * manifest would be written against.
 *
 * Handled: the `---` fence; `name:`; `description:` inline or as a `|`/`>`
 * block scalar; nested `metadata: version:`; and `openclaw.install` entries
 * of `kind: node` whose `package:` values are collected for the declared-deps
 * list (the Binance hub's `metadata.openclaw` shape and a top-level
 * `openclaw:` are both read). Every other key is ignored. CRLF is tolerated.
 */
object SkillManifest {

    internal const val MAX_NAME = 200
    internal const val MAX_DESCRIPTION = 2000

    private const val FENCE = "---"

    fun parse(raw: String): SkillMd? {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        if (lines.firstOrNull()?.trim() != FENCE) return null
        val close = lines.drop(1).indexOfFirst { it.trim() == FENCE || it.trim() == "..." }
        if (close < 0) return null
        val front = lines.drop(1).take(close)
        val body = lines.drop(close + 2).joinToString("\n").trim()

        var name: String? = null
        var description = ""
        var version = ""
        val npmDeps = mutableListOf<String>()

        // The stack of keys the current line is nested under, by indent, so
        // `metadata:` → `openclaw:` → `install:` is read as a path rather than
        // as three unrelated scalars.
        val stack = ArrayDeque<Pair<Int, String>>()
        var listPath: List<String>? = null
        var listIndent = -1
        var itemKeys = mutableMapOf<String, String>()
        // Block-scalar state: which field is being read, its parent indent,
        // the indent of the first content line, and the buffer.
        var blockKey: String? = null
        var blockParent = -1
        var blockIndent = -1
        var blockFold = false
        val blockBuffer = mutableListOf<String>()

        fun flushItem() {
            if (itemKeys["kind"]?.equals("node", ignoreCase = true) == true) {
                itemKeys["package"]?.let { pkg ->
                    val unquoted = unquote(pkg)
                    if (unquoted.isNotEmpty() && unquoted !in npmDeps) npmDeps += unquoted
                }
            }
            itemKeys = mutableMapOf()
        }

        fun flushBlock() {
            val key = blockKey ?: return
            val joined = if (blockFold) {
                // `>` folds: one paragraph per blank-line-separated chunk, the
                // chunk's own newlines flattened to spaces.
                blockBuffer.joinToString("\n").split("\n\n").joinToString(" ") { paragraph ->
                    paragraph.replace('\n', ' ').trim()
                }
            } else {
                blockBuffer.joinToString("\n")
            }.trim()
            when (key) {
                "description" -> description = joined.take(MAX_DESCRIPTION)
                "name" -> name = joined.take(MAX_NAME)
            }
            blockKey = null
            blockParent = -1
            blockIndent = -1
        }

        for (line in front) {
            if (line.isBlank()) {
                if (blockKey != null) blockBuffer += ""
                continue
            }
            val indent = line.indexOfFirst { it != ' ' }
            val trimmed = line.trim()

            // A block scalar consumes everything indented under its key.
            if (blockKey != null) {
                if (indent > blockParent) {
                    if (blockIndent < 0) blockIndent = indent
                    blockBuffer += line.drop(blockIndent)
                    continue
                }
                flushBlock()
                // fall through: this line is ordinary syntax again
            }

            // List items under the tracked install list.
            val inList = listPath != null
            if (inList && trimmed.startsWith("- ")) {
                flushItem()
                listIndent = indent
                splitKeyValue(trimmed.removePrefix("- ").trim())?.let { (key, value) ->
                    itemKeys[key] = value
                }
                continue
            }
            if (inList && indent > listIndent) {
                splitKeyValue(trimmed)?.let { (key, value) ->
                    itemKeys[key] = value
                }
                continue
            }
            if (inList) {
                // A line at or below the list's indent ends the list.
                flushItem()
                listPath = null
                listIndent = -1
            }

            val (key, value) = splitKeyValue(trimmed) ?: continue
            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeLast()
            stack.addLast(indent to key)
            val path = stack.map { it.second }

            when {
                path == listOf("name") && value.isEmpty() -> {
                    // A folded name is not a documented shape, but it is still
                    // a name — read it rather than refuse a skill over punctuation.
                    blockKey = "name"
                    blockParent = indent
                    blockFold = false
                    blockIndent = -1
                }
                path == listOf("name") -> name = unquote(value).take(MAX_NAME)
                path == listOf("description") && isBlockScalar(value) -> {
                    blockKey = "description"
                    blockParent = indent
                    blockFold = value.startsWith(">")
                    blockIndent = -1
                }
                path == listOf("description") -> description = unquote(value).take(MAX_DESCRIPTION)
                path == listOf("metadata", "version") -> version = unquote(value)
                path.last() == "install" && value.isEmpty() &&
                    path.dropLast(1).lastOrNull() == "openclaw" -> {
                    flushItem()
                    listPath = path
                    listIndent = indent
                }
            }
        }
        flushBlock()
        flushItem()

        val parsedName = name?.trim().orEmpty()
        if (parsedName.isEmpty()) return null
        return SkillMd(
            name = parsedName,
            description = description.trim(),
            version = version.trim(),
            npmDeps = npmDeps.toList(),
            body = body
        )
    }

    private fun isBlockScalar(value: String): Boolean =
        value.startsWith("|") || value.startsWith(">")

    /** `key: value` → pair; `key:` → (key, ""); anything else → null. */
    private fun splitKeyValue(line: String): Pair<String, String>? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val key = line.take(colon).trim()
        if (key.isEmpty() || key.contains(' ')) return null
        return key to line.substring(colon + 1).trim()
    }

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length >= 2) {
            val first = trimmed.first()
            val last = trimmed.last()
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return trimmed.substring(1, trimmed.length - 1)
            }
        }
        return trimmed
    }
}
