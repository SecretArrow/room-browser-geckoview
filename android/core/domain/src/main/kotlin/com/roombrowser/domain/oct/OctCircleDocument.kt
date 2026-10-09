package com.roombrowser.domain.oct

/**
 * A circle document, made to stand on its own.
 *
 * Nothing serves a circle at a URL. The node answers JSON-RPC, so a `src="/logo.png"` inside a
 * circle has no origin to resolve against and no server to fetch from: the engine would fail
 * every one of them. The reference reader's answer is to fetch every asset itself and inline
 * it, and this does the same.
 *
 * What comes out is one self-contained document. It is handed to the engine with an opaque
 * origin and a CSP that permits no network at all, so a circle cannot reach the reader's
 * device, and -- because both page bridges derive the host from the engine's own URL -- it
 * cannot reach the password vault or the wallet either.
 *
 * Only the parts that must change are rewritten: attribute values are spliced in place and
 * every other byte of the document reaches the engine as the author wrote it.
 */
object OctCircleDocument {

    /** The most subresources one document may pull in. */
    const val MAX_SUBRESOURCES = 64

    /**
     * Ceiling on the circle assets one document inlines, so a circle cannot exhaust memory.
     *
     * The number is the TIGHTER carrier's, not the looser one. A rendered circle reaches
     * GeckoView as a single `data:` URI, and that engine refuses one above 2 MiB; base64
     * costs a third on the way in, so this budget plus the entry document has to land under
     * roughly 1.5 MB. Budgeting for the smaller carrier keeps one circle rendering the same
     * in both editions instead of loading in one and erroring in the other.
     */
    const val MAX_TOTAL_BYTES = 1 * 1024 * 1024

    /** `@import` chains deeper than this are dropped rather than followed. */
    const val MAX_CSS_DEPTH = 4

    /** A reference the document makes to another circle asset. */
    data class Reference(val path: String, val styleSheet: Boolean)

    /**
     * Every circle asset [html] needs, resolved against the document's own [basePath], in
     * document order and without repeats.
     */
    fun references(html: String, basePath: String): List<Reference> = collect { add ->
        walk(
            html,
            text = { raw, inStyle ->
                if (inStyle) cssReferences(raw, basePath).forEach(add)
                ""
            },
            tag = { raw ->
                referencesOf(raw, basePath, add)
                ""
            }
        )
    }

    /** Every circle asset [css] needs, resolved against the stylesheet's own [basePath]. */
    fun styleSheetReferences(css: String, basePath: String): List<Reference> = collect { add ->
        cssReferences(css, basePath).forEach(add)
    }

    /** [html] with every reference to [assets] carried inside it. */
    fun materialize(html: String, uri: OctUri, assets: Map<String, OctInlined>): String {
        val document = walk(
            html,
            text = { raw, inStyle -> if (inStyle) styleSheet(raw, uri.path, assets) else raw },
            tag = { raw -> rewriteTag(raw, uri, assets) }
        )
        return injectPolicy(document)
    }

    /** [css] with every reference to [assets] carried inside it. */
    fun styleSheet(css: String, basePath: String, assets: Map<String, OctInlined>): String =
        rewriteCss(css, basePath, assets, depth = 0)

    private fun collect(block: ((Reference) -> Unit) -> Unit): List<Reference> {
        val seen = LinkedHashMap<String, Reference>()
        block { reference -> seen.putIfAbsent(reference.path, reference) }
        return seen.values.toList()
    }

    // ---------------------------------------------------------------- tags

    private fun referencesOf(raw: String, basePath: String, add: (Reference) -> Unit) {
        val tag = parseTag(raw) ?: return
        if (tag.closing) return
        tag.attributes.forEach { attribute ->
            if (attribute.name == "style") {
                cssReferences(attribute.value, basePath).forEach(add)
                return@forEach
            }
            if (!fetches(tag.name, attribute.name)) return@forEach
            val styleSheet = tag.name == "link" && isStyleSheet(tag)
            specsOf(attribute).forEach { spec ->
                OctUri.resolve(basePath, spec)?.let { add(Reference(it, styleSheet)) }
            }
        }
    }

    private fun isStyleSheet(tag: ParsedTag): Boolean =
        tag.attributes.firstOrNull { it.name == "rel" }?.value
            ?.split(' ', '\t', '\n')
            ?.any { it.equals("stylesheet", ignoreCase = true) } == true

    private fun rewriteTag(raw: String, uri: OctUri, assets: Map<String, OctInlined>): String {
        val tag = parseTag(raw) ?: return raw
        if (tag.closing) return raw
        when (tag.name) {
            // A circle may not rebase its own references: every path in it is a path we
            // resolved, and a base would silently redirect the ones we did not.
            "base" -> return ""
            "meta" -> if (isHostileMeta(tag)) return ""
        }
        val edits = ArrayList<Edit>()
        tag.attributes.forEach { attribute ->
            val change = when {
                attribute.name == "style" ->
                    Change.Set(styleSheet(attribute.value, uri.path, assets))
                attribute.name == "srcset" || attribute.name == "imagesrcset" ->
                    srcSet(attribute.value, uri, assets)
                attribute.name == "href" && (tag.name == "a" || tag.name == "area") ->
                    Change.Set(linkTarget(attribute.value, uri))
                fetches(tag.name, attribute.name) ->
                    fetch(attribute.value, uri, assets)
                else -> null
            }
            when (change) {
                null -> Unit
                is Change.Set -> edits.add(attribute.set(change.value))
                Change.Drop -> edits.add(Edit(attribute.start, attribute.end, ""))
            }
        }
        return splice(raw, edits)
    }

    /**
     * The attributes a browser fetches without being asked. An unresolved one is dropped
     * rather than left in place, because the CSP forbids the fetch anyway and a dropped
     * attribute is not a failed request.
     */
    private fun fetches(tagName: String, attribute: String): Boolean = when (attribute) {
        "srcset", "imagesrcset" -> true
        "poster" -> tagName == "video"
        "data" -> tagName == "object"
        "href" -> tagName == "link"
        "src" -> tagName in SOURCED
        else -> false
    }

    private val SOURCED = setOf("img", "source", "video", "audio", "script", "embed", "track", "input")

    private fun specsOf(attribute: Attr): List<String> = when (attribute.name) {
        "srcset", "imagesrcset" -> srcSetSpecs(attribute.value)
        else -> listOf(attribute.value)
    }

    /**
     * Where a link goes. Another circle path becomes an `oct://` address the app resolves the
     * same way; a plain web link is left alone, because this is a browser and a circle is
     * allowed to point at the web. Everything else is neutralised: `javascript:` would run in
     * the circle's own origin and `data:`/`blob:` documents would inherit it.
     */
    private fun linkTarget(href: String, uri: OctUri): String {
        OctUri.resolve(uri.path, href)?.let { return "${OctUri.PREFIX}${uri.circleId}$it" }
        val trimmed = href.trim().lowercase()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) href.trim() else "#"
    }

    private fun fetch(reference: String, uri: OctUri, assets: Map<String, OctInlined>): Change {
        val path = OctUri.resolve(uri.path, reference) ?: return Change.Drop
        val asset = assets[path] ?: return Change.Drop
        return Change.Set(asset.dataUrl)
    }

    private fun srcSet(value: String, uri: OctUri, assets: Map<String, OctInlined>): Change {
        val kept = value.split(',').mapNotNull { candidate ->
            val trimmed = candidate.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val split = trimmed.indexOfFirst { it == ' ' || it == '\t' }
            val url = if (split < 0) trimmed else trimmed.substring(0, split)
            val descriptor = if (split < 0) "" else trimmed.substring(split)
            val asset = OctUri.resolve(uri.path, url)?.let { assets[it] } ?: return@mapNotNull null
            asset.dataUrl + descriptor
        }
        return if (kept.isEmpty()) Change.Drop else Change.Set(kept.joinToString(", "))
    }

    private fun srcSetSpecs(value: String): List<String> = value.split(',').mapNotNull { candidate ->
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val split = trimmed.indexOfFirst { it == ' ' || it == '\t' }
        if (split < 0) trimmed else trimmed.substring(0, split)
    }

    /** A circle may not replace the policy this app set, nor claim a referrer. */
    private fun isHostileMeta(tag: ParsedTag): Boolean {
        val directive = tag.attributes.firstOrNull { it.name == "http-equiv" }?.value?.trim()?.lowercase()
        if (directive == "content-security-policy") return true
        return tag.attributes.firstOrNull { it.name == "name" }?.value?.trim()?.equals("referrer", true) == true
    }

    // ---------------------------------------------------------------- css

    private fun cssReferences(css: String, basePath: String): List<Reference> =
        CSS_TOKEN.findAll(css).mapNotNull { match ->
            val import = match.importSpec()
            val spec = import ?: match.urlSpec() ?: return@mapNotNull null
            OctUri.resolve(basePath, spec)?.let { Reference(it, styleSheet = import != null) }
        }.toList()

    /**
     * One pass, left to right, with `@import` and `url()` in the same alternation.
     *
     * Two passes would corrupt each other: a `url()` pass run over CSS that already had an
     * import inlined would match the `data:` URL it just wrote and blank it out again.
     */
    private fun rewriteCss(css: String, basePath: String, assets: Map<String, OctInlined>, depth: Int): String =
        replaceAll(css, CSS_TOKEN) { match ->
            val import = match.importSpec()
            if (import == null) {
                // A url() that cannot be inlined is emptied rather than left as a URL: the
                // CSP forbids the fetch anyway, and an empty data URL is not a request.
                val path = match.urlSpec()?.let { OctUri.resolve(basePath, it) }
                path?.let { assets[it] }?.let { "url(\"${it.dataUrl}\")" } ?: "url(\"data:,\")"
            } else {
                val path = OctUri.resolve(basePath, import)
                val body = path?.let { assets[it] }?.text
                if (body == null || depth >= MAX_CSS_DEPTH) "" else rewriteCss(body, path, assets, depth + 1)
            }
        }

    /** Whichever of the alternation's quoting styles matched; never more than one does. */
    private fun MatchResult.importSpec(): String? =
        (1..5).firstNotNullOfOrNull { groupValues[it].takeIf { it.isNotBlank() } }

    private fun MatchResult.urlSpec(): String? =
        (6..8).firstNotNullOfOrNull { groupValues[it].takeIf { it.isNotBlank() } }

    private val CSS_TOKEN = Regex(
        """@import\s+(?:url\(\s*(?:"([^"]*)"|'([^']*)'|([^)\s]*))\s*\)|"([^"]*)"|'([^']*)')\s*[^;]*;""" +
            """|url\(\s*(?:"([^"]*)"|'([^']*)'|([^)\s]*))\s*\)""",
        RegexOption.IGNORE_CASE
    )

    private fun replaceAll(text: String, pattern: Regex, replacement: (MatchResult) -> String): String {
        val out = StringBuilder(text.length)
        var last = 0
        pattern.findAll(text).forEach { match ->
            out.append(text, last, match.range.first)
            out.append(replacement(match))
            last = match.range.last + 1
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    // ---------------------------------------------------------------- policy

    /**
     * The document's whole privilege, stated once and first.
     *
     * It is inserted before anything else in the document on purpose: a meta-delivered policy
     * applies from the point the parser reads it, so a `<script>` the circle wrote ahead of
     * its own `<head>` would otherwise run before the policy existed. Everything else the
     * parser hoists into that same head lands after it.
     */
    private fun injectPolicy(html: String): String {
        val start = afterDoctype(html)
        return html.substring(0, start) + POLICY + html.substring(start)
    }

    private fun afterDoctype(html: String): Int {
        val trimmed = html.trimStart()
        val offset = html.length - trimmed.length
        if (!trimmed.startsWith("<!doctype", ignoreCase = true)) return offset
        val end = html.indexOf('>', offset)
        return if (end < 0) offset else end + 1
    }

    /**
     * A replacement that only has to satisfy the tokenizer we are feeding, not every parser:
     * an unquoted value is quoted, because a data URL contains `=` and an unquoted `=` is the
     * one character HTML does not allow in an attribute value.
     */
    private fun Attr.set(value: String): Edit {
        val escaped = if (quote == '\'') value.replace("'", "&#39;") else value.replace("\"", "&quot;")
        val text = if (quote == '"' || quote == '\'') escaped else "\"$escaped\""
        return Edit(valueStart, valueEnd, text)
    }

    private const val CSP = "default-src 'none'; script-src 'unsafe-inline' data:; " +
        "style-src 'unsafe-inline' data:; img-src data:; font-src data:; media-src data:; " +
        "connect-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; " +
        "object-src 'none'; base-uri 'none'; form-action 'none'; manifest-src 'none'"

    private const val POLICY = "<meta charset=\"utf-8\">" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"$CSP\">" +
        "<meta name=\"referrer\" content=\"no-referrer\">"

    // ---------------------------------------------------------------- tokenizer

    private val RAW_TEXT = setOf("script", "style", "textarea", "title")

    /**
     * Emit [html] through [text] and [tag], which may rewrite what they are given. Raw-text
     * elements are handed to [text] whole, so nothing inside a `<script>` is ever mistaken
     * for markup.
     */
    private fun walk(
        html: String,
        text: (raw: String, inStyle: Boolean) -> String,
        tag: (raw: String) -> String
    ): String {
        val out = StringBuilder(html.length + POLICY.length)
        var i = 0
        var inStyle = false
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                out.append(text(html.substring(i), inStyle))
                break
            }
            if (lt > i) out.append(text(html.substring(i, lt), inStyle))
            val skipTo = declarationEnd(html, lt)
            if (skipTo > 0) {
                out.append(html, lt, skipTo)
                i = skipTo
                continue
            }
            val name = tagName(html, lt)
            if (name == null) {
                out.append('<')
                i = lt + 1
                continue
            }
            val end = tagEnd(html, lt)
            val raw = html.substring(lt, end)
            out.append(tag(raw))
            val closing = raw.length > 1 && raw[1] == '/'
            if (name == "style") inStyle = !closing && !raw.endsWith("/>")
            i = end
            if (closing || raw.endsWith("/>") || name !in RAW_TEXT) continue
            val close = closingTagStart(html, i, name)
            val stop = if (close < 0) html.length else close
            if (stop > i) out.append(text(html.substring(i, stop), inStyle))
            i = stop
        }
        return out.toString()
    }

    /** The end of a comment, doctype or processing instruction starting at [lt], or -1. */
    private fun declarationEnd(html: String, lt: Int): Int = when {
        html.startsWith("<!--", lt) -> html.indexOf("-->", lt + 4).let { if (it < 0) html.length else it + 3 }
        html.startsWith("<!", lt) || html.startsWith("<?", lt) ->
            html.indexOf('>', lt).let { if (it < 0) html.length else it + 1 }
        else -> -1
    }

    private fun tagName(html: String, lt: Int): String? {
        var i = lt + 1
        if (i < html.length && html[i] == '/') i += 1
        val start = i
        while (i < html.length && (html[i].isLetterOrDigit() || html[i] == '-' || html[i] == ':' || html[i] == '_')) i += 1
        return if (i == start) null else html.substring(start, i).lowercase()
    }

    private fun tagEnd(html: String, start: Int): Int {
        var i = start + 1
        var quote = ' '
        while (i < html.length) {
            val c = html[i]
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i + 1
            }
            i += 1
        }
        return html.length
    }

    private fun closingTagStart(html: String, from: Int, name: String): Int {
        var i = from
        while (true) {
            val at = html.indexOf("</", i)
            if (at < 0) return -1
            val after = at + 2
            if (html.regionMatches(after, name, 0, name.length, ignoreCase = true)) {
                val next = after + name.length
                if (next >= html.length || html[next] == '>' || html[next].isWhitespace()) return at
            }
            i = at + 2
        }
    }

    // ---------------------------------------------------------------- attributes

    private class Attr(
        val name: String,
        val value: String,
        val quote: Char,
        /** Where the attribute starts and ends in its tag's text, for a removal. */
        val start: Int,
        val valueStart: Int,
        val valueEnd: Int,
        val end: Int
    )

    private class ParsedTag(val name: String, val closing: Boolean, val attributes: List<Attr>)

    private fun parseTag(raw: String): ParsedTag? {
        var i = 1
        val closing = i < raw.length && raw[i] == '/'
        if (closing) i += 1
        val nameStart = i
        while (i < raw.length && isNameChar(raw[i])) i += 1
        if (i == nameStart) return null
        val name = raw.substring(nameStart, i).lowercase()
        val body = raw.length - 1
        val attributes = ArrayList<Attr>()
        while (i < body) {
            while (i < body && raw[i].isWhitespace()) i += 1
            if (i >= body || raw[i] == '>' || raw[i] == '/') break
            val start = i
            val attributeStart = i
            while (i < body && isNameChar(raw[i])) i += 1
            if (i == attributeStart) {
                i += 1
                continue
            }
            val attributeName = raw.substring(attributeStart, i).lowercase()
            while (i < body && raw[i].isWhitespace()) i += 1
            var quote = ' '
            var valueStart = i
            var valueEnd = i
            if (i < body && raw[i] == '=') {
                i += 1
                while (i < body && raw[i].isWhitespace()) i += 1
                if (i < body && (raw[i] == '"' || raw[i] == '\'')) {
                    quote = raw[i]
                    i += 1
                    valueStart = i
                    while (i < body && raw[i] != quote) i += 1
                    valueEnd = i
                    if (i < body) i += 1
                } else {
                    valueStart = i
                    while (i < body && !raw[i].isWhitespace() && raw[i] != '>') i += 1
                    valueEnd = i
                }
            }
            attributes.add(Attr(attributeName, raw.substring(valueStart, valueEnd), quote, start, valueStart, valueEnd, i))
        }
        return ParsedTag(name, closing, attributes)
    }

    private fun isNameChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '-' || c == '_' || c == ':' || c == '.' || c == '@'

    private class Edit(val from: Int, val to: Int, val replacement: String)

    private fun splice(raw: String, edits: List<Edit>): String {
        if (edits.isEmpty()) return raw
        val out = StringBuilder(raw.length)
        var last = 0
        edits.sortedBy { it.from }.forEach { edit ->
            out.append(raw, last, edit.from)
            out.append(edit.replacement)
            last = edit.to
        }
        out.append(raw, last, raw.length)
        return out.toString()
    }

    private sealed interface Change {
        data class Set(val value: String) : Change
        data object Drop : Change
    }
}

/** A circle asset already fetched, ready to be carried inside a document. */
class OctInlined(val path: String, val contentType: String, val bytes: ByteArray) {

    val dataUrl: String by lazy { OctCircleCodec.dataUrl(contentType, bytes) }

    /** Text is only decoded for the types a nested reference can appear in. */
    val text: String? get() = if (isTextual) String(bytes, Charsets.UTF_8) else null

    private val isTextual: Boolean
        get() {
            val type = contentType.substringBefore(';').trim().lowercase()
            return type.startsWith("text/") ||
                type == "application/json" ||
                type == "application/javascript" ||
                type == "image/svg+xml"
        }
}
