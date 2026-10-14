package com.roombrowser.node.packages

/**
 * The slice of node-semver the installer needs: one version, the range forms an
 * npm manifest actually publishes, and "which published version does this spec
 * select".
 *
 * DELIBERATE SIMPLIFICATION: a prerelease version satisfies a range only when
 * the range itself names a prerelease at the same major.minor.patch, which is
 * npm's practical rule. `*` and `latest` therefore mean "any release" rather
 * than "any version", so a package that has only ever published prereleases
 * cannot be selected by them.
 */
object Semver {

    data class Version(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val prerelease: List<String> = emptyList()
    ) : Comparable<Version> {

        val isPrerelease: Boolean get() = prerelease.isNotEmpty()

        override fun compareTo(other: Version): Int {
            val byMajor = compareValues(major, other.major)
            if (byMajor != 0) return byMajor
            val byMinor = compareValues(minor, other.minor)
            if (byMinor != 0) return byMinor
            val byPatch = compareValues(patch, other.patch)
            if (byPatch != 0) return byPatch
            // A release outranks every prerelease of the same core version.
            if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
            if (prerelease.isEmpty()) return 1
            if (other.prerelease.isEmpty()) return -1
            for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
                val compared = compareIdentifier(prerelease[index], other.prerelease[index])
                if (compared != 0) return compared
            }
            return compareValues(prerelease.size, other.prerelease.size)
        }

        override fun toString(): String =
            if (prerelease.isEmpty()) "$major.$minor.$patch"
            else "$major.$minor.$patch-${prerelease.joinToString(".")}"
    }

    /** Parses one concrete version; a leading `v`/`=` and `+build` are tolerated. */
    fun parse(text: String): Version? {
        var body = text.trim()
        while (body.isNotEmpty() && (body[0] == 'v' || body[0] == 'V' || body[0] == '=')) {
            body = body.substring(1)
        }
        val match = CONCRETE.matchEntire(body) ?: return null
        val major = number(match.groupValues[1]) ?: return null
        val minor = number(match.groupValues[2]) ?: return null
        val patch = number(match.groupValues[3]) ?: return null
        val prerelease = match.groupValues[4]
        return Version(major, minor, patch, if (prerelease.isEmpty()) emptyList() else prerelease.split("."))
    }

    /**
     * Parses a range, or null for anything not understood. Supported: empty,
     * `*`, `x`, `latest`, an exact or partial version, `^`, `~`, `>`, `>=`,
     * `<`, `<=`, `=`, space-separated conjunctions, `a - b` hyphen ranges and
     * `||` alternatives.
     */
    fun parseRange(spec: String): Range? {
        val alternatives = spec.trim().split("||")
        val parsed = alternatives.map { parseAlternative(it) ?: return null }
        return Range(parsed)
    }

    /** The highest version in [versions] that [range] allows, or null. */
    fun maxSatisfying(versions: List<Version>, range: Range): Version? =
        versions.filter { range.allows(it) }.maxOrNull()

    /**
     * The highest of [versions] that [spec] selects, given back exactly as it
     * was spelled in the list, so a caller can look it up in its own records.
     */
    fun highest(versions: List<String>, spec: String): String? {
        val range = parseRange(spec) ?: return null
        return versions
            .mapNotNull { text -> parse(text)?.let { it to text } }
            .filter { range.allows(it.first) }
            .maxByOrNull { it.first }
            ?.second
    }

    /** A disjunction of conjunctions — what `a || b` becomes. */
    class Range internal constructor(internal val alternatives: List<List<Bound>>) {

        fun allows(version: Version): Boolean = alternatives.any { setAllows(it, version) }

        override fun toString(): String =
            alternatives.joinToString(" || ") { set -> set.joinToString(" ") }
    }

    internal enum class Op(val symbol: String) {
        EQ("="), GT(">"), GTE(">="), LT("<"), LTE("<=")
    }

    internal class Bound(val op: Op, val version: Version) {

        fun matches(version: Version): Boolean = when (op) {
            Op.EQ -> version == this.version
            Op.GT -> version > this.version
            Op.GTE -> version >= this.version
            Op.LT -> version < this.version
            Op.LTE -> version <= this.version
        }

        override fun toString(): String = "${op.symbol}$version"
    }

    private val CONCRETE = Regex(
        "^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$"
    )

    private val HYPHEN = Regex("^\\s*(\\S+)\\s+-\\s+(\\S+)\\s*$")

    private val WHITESPACE = Regex("\\s+")

    private val WILDCARDS = setOf("*", "x", "X", "")

    /** `>=0.0.0`: every release, and — through the prerelease rule — nothing else. */
    private fun any(): List<Bound> = listOf(Bound(Op.GTE, Version(0, 0, 0)))

    /** An unwritten component is zero; a leading zero is refused. */
    private fun number(text: String): Int? {
        if (text.isEmpty()) return 0
        if (text.length > 1 && text.startsWith("0")) return null
        return text.toIntOrNull()
    }

    private fun parseAlternative(text: String): List<Bound>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return any()
        HYPHEN.matchEntire(trimmed)?.let { match ->
            return hyphenRange(match.groupValues[1], match.groupValues[2])
        }
        val bounds = mutableListOf<Bound>()
        for (token in trimmed.split(WHITESPACE)) {
            if (token.isEmpty()) continue
            bounds += parseToken(token) ?: return null
        }
        return bounds
    }

    private fun parseToken(token: String): List<Bound>? {
        val op = when {
            token.startsWith(">=") -> Op.GTE
            token.startsWith("<=") -> Op.LTE
            token.startsWith(">") -> Op.GT
            token.startsWith("<") -> Op.LT
            else -> Op.EQ
        }
        val rest = if (op == Op.EQ) token.removePrefix("=") else token.substring(op.symbol.length)
        if (rest.startsWith("^")) return caret(rest.substring(1))
        if (rest.startsWith("~")) return tilde(rest.substring(1))
        if (op == Op.EQ && (rest.isEmpty() || rest == "latest" || rest in WILDCARDS)) return any()
        if (rest.isEmpty()) return null
        val partial = parsePartial(rest) ?: return null
        if (partial.major == null) return any()
        return bound(op, partial)
    }

    private fun caret(text: String): List<Bound>? {
        val partial = parsePartial(text) ?: return null
        val major = partial.major ?: return any()
        val minor = partial.minor
        val patch = partial.patch
        val lower = Version(major, minor ?: 0, patch ?: 0, partial.prerelease)
        val upper = when {
            major > 0 -> Version(major + 1, 0, 0)
            minor == null -> Version(major + 1, 0, 0)
            patch == null -> Version(0, minor + 1, 0)
            minor > 0 -> Version(0, minor + 1, 0)
            else -> Version(0, 0, patch + 1)
        }
        return listOf(Bound(Op.GTE, lower), Bound(Op.LT, upper))
    }

    private fun tilde(text: String): List<Bound>? {
        val partial = parsePartial(text) ?: return null
        val major = partial.major ?: return any()
        val minor = partial.minor
        val lower = Version(major, minor ?: 0, partial.patch ?: 0, partial.prerelease)
        val upper = if (minor == null) Version(major + 1, 0, 0) else Version(major, minor + 1, 0)
        return listOf(Bound(Op.GTE, lower), Bound(Op.LT, upper))
    }

    private fun hyphenRange(low: String, high: String): List<Bound>? {
        val lower = parsePartial(low) ?: return null
        val upper = parsePartial(high) ?: return null
        val bounds = mutableListOf<Bound>()
        val from = lower.filled() ?: return any()
        bounds += Bound(Op.GTE, from)
        val upperMajor = upper.major
        val upperMinor = upper.minor
        when {
            upperMajor == null -> Unit
            upperMinor == null -> bounds += Bound(Op.LT, Version(upperMajor + 1, 0, 0))
            upper.patch == null -> bounds += Bound(Op.LT, Version(upperMajor, upperMinor + 1, 0))
            else -> bounds += Bound(Op.LTE, upper.filled()!!)
        }
        return bounds
    }

    /** npm widens a partial comparator instead of filling it with zeros. */
    private fun bound(op: Op, partial: Partial): List<Bound> {
        val major = partial.major!!
        val minor = partial.minor
        val patch = partial.patch
        val filled = Version(major, minor ?: 0, patch ?: 0, partial.prerelease)
        return when (op) {
            Op.EQ -> when {
                patch != null -> listOf(Bound(Op.EQ, filled))
                minor != null -> listOf(Bound(Op.GTE, filled), Bound(Op.LT, Version(major, minor + 1, 0)))
                else -> listOf(Bound(Op.GTE, filled), Bound(Op.LT, Version(major + 1, 0, 0)))
            }
            Op.GTE -> listOf(Bound(Op.GTE, filled))
            Op.GT -> when {
                patch != null -> listOf(Bound(Op.GT, filled))
                minor != null -> listOf(Bound(Op.GTE, Version(major, minor + 1, 0)))
                else -> listOf(Bound(Op.GTE, Version(major + 1, 0, 0)))
            }
            Op.LT -> when {
                patch != null -> listOf(Bound(Op.LT, filled))
                minor != null -> listOf(Bound(Op.LT, Version(major, minor, 0)))
                else -> listOf(Bound(Op.LT, Version(major, 0, 0)))
            }
            Op.LTE -> when {
                patch != null -> listOf(Bound(Op.LTE, filled))
                minor != null -> listOf(Bound(Op.LT, Version(major, minor + 1, 0)))
                else -> listOf(Bound(Op.LT, Version(major + 1, 0, 0)))
            }
        }
    }

    private data class Partial(
        val major: Int?,
        val minor: Int?,
        val patch: Int?,
        val prerelease: List<String> = emptyList()
    ) {
        fun filled(): Version? =
            if (major == null) null else Version(major, minor ?: 0, patch ?: 0, prerelease)
    }

    private fun parsePartial(text: String): Partial? {
        val body = text.trim()
        if (body.isEmpty()) return null
        if (body in WILDCARDS) return Partial(null, null, null)
        val withoutBuild = body.substringBefore("+")
        val dash = withoutBuild.indexOf('-')
        val core = if (dash >= 0) withoutBuild.substring(0, dash) else withoutBuild
        val prerelease = if (dash >= 0) withoutBuild.substring(dash + 1) else ""
        if (core.isEmpty()) return null
        val segments = core.split(".")
        if (segments.size > 3) return null
        val numbers = ArrayList<Int?>(3)
        for (segment in segments) {
            if (segment in WILDCARDS) {
                numbers += null
                continue
            }
            numbers += number(segment) ?: return null
        }
        while (numbers.size < 3) numbers += null
        val major = numbers[0] ?: return Partial(null, null, null)
        val minor = numbers[1]
        val patch = if (minor == null) null else numbers[2]
        val pre = if (patch != null && prerelease.isNotEmpty()) prerelease.split(".") else emptyList()
        return Partial(major, minor, patch, pre)
    }
}

/** A comparator set allows a prerelease only where it names one at the same core. */
private fun setAllows(set: List<Semver.Bound>, version: Semver.Version): Boolean {
    if (!set.all { it.matches(version) }) return false
    if (!version.isPrerelease) return true
    return set.any { it.version.isPrerelease && sameTriple(it.version, version) }
}

private fun sameTriple(a: Semver.Version, b: Semver.Version): Boolean =
    a.major == b.major && a.minor == b.minor && a.patch == b.patch

private fun compareIdentifier(a: String, b: String): Int {
    val left = a.toLongOrNull()
    val right = b.toLongOrNull()
    return when {
        left != null && right != null -> compareValues(left, right)
        left != null -> -1
        right != null -> 1
        else -> a.compareTo(b)
    }
}
