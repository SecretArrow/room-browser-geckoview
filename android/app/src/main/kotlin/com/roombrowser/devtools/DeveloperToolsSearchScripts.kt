package com.roombrowser.devtools

import kotlinx.serialization.Serializable

/**
 * The Search panel's probe script, the models it decodes into, and the app-side
 * search over the two buffers this surface already holds.
 *
 * ONE QUERY, EVERYTHING THE PAGE CAN ANSWER FROM. The probe is SYNCHRONOUS,
 * like the overview, security and audit probes: everything it reads is a fact
 * the page already holds, no observer is installed and nothing is mutated, so
 * there is nothing to poll and no second round trip. It walks the page's own
 * globals, both web-storage areas, the cookies the page itself can see, and the
 * element tree -- and answers in one `JSON.stringify`.
 *
 * THE QUERY IS INJECTED ONLY THROUGH `jsString` -- the same escaping helper the
 * storage value read uses -- so a query containing quotes, backslashes or
 * newlines can never break out of the string literal it arrives in. That is
 * this script's one security property and the JVM test pins it.
 *
 * WHAT IS NOT SENT. Storage VALUES are matched in the page and never carried
 * back: a value hit reports the key, the area, and the value's length, and the
 * value itself is read one row at a time through `storageValue` when the user
 * taps that row -- the Application panel's standing rule, kept identically
 * here. Cookie values are matched the same way and reported as
 * matched-in-value without the value. DOM hits carry a snippet of the element's
 * own text, which is the text the page shows anyway.
 *
 * LOWERCASE-COMPARE, NEVER REGEX. A `.*` query is searched for as the literal
 * three characters it is, because a debugging tool whose search explodes on its
 * own query syntax would be a debugging tool that needs another one.
 */
internal object DeveloperToolsSearchScripts {

    /**
     * How many hits one category carries. The section header shows the count it
     * actually is, and the probe's truncation flags say when this cap -- not the
     * page -- is what stopped the list.
     */
    internal const val HIT_CAP = 100

    /**
     * How many elements the DOM scan walks before it stops. A page can hold far
     * more than this, so a cut scan is reported through `domTruncated` rather
     * than passed off as the whole tree.
     */
    internal const val DOM_SCAN_CAP = 4_000

    /**
     * Characters of one element's own text the scan reads. The snippet is cut
     * from this, so a match deep in a long text node is still centered on rather
     * than lost in.
     */
    internal const val DOM_TEXT_CAP = 500

    /** Characters of the snippet a DOM hit carries, centered on the first match. */
    internal const val SNIPPET_CHARS = 80

    /**
     * Characters a global's preview may run to. Past this the row shows the name
     * and the type alone, which is still the answer to "is this variable here".
     */
    internal const val PREVIEW_CHARS = 120

    /**
     * The whole probe: globals, both storage areas, the page's own cookies, and
     * the element tree, filtered by [query] in one synchronous pass.
     *
     * The body is wrapped in a single try/catch that answers `null` on
     * catastrophic failure, exactly as the other probes do, and every per-item
     * read is guarded on its own so one throwing getter, one blocked storage
     * area or one odd element costs its own hit and nothing else.
     */
    fun searchJs(query: String): String = """
        (function () {
          try {
            var needle = ${DeveloperToolsStorageScripts.jsString(query)}.toLowerCase();
            function matches(value) {
              try { return String(value).toLowerCase().indexOf(needle) >= 0; } catch (e) { return false; }
            }

            var variables = [];
            var variablesTruncated = false;
            try {
              var names = Object.getOwnPropertyNames(window);
              for (var i = 0; i < names.length; i++) {
                if (variables.length >= $HIT_CAP) { variablesTruncated = true; break; }
                var name = names[i];
                var kind = 'unknown';
                var shown = null;
                try {
                  kind = typeof window[name];
                  if (kind === 'string' || kind === 'number' || kind === 'boolean' || kind === 'undefined') {
                    shown = String(window[name]);
                  }
                } catch (e) {}
                if (matches(name) || (shown !== null && matches(shown))) {
                  variables.push({
                    name: String(name),
                    kind: kind,
                    preview: (shown !== null && shown.length <= $PREVIEW_CHARS) ? shown : null
                  });
                }
              }
            } catch (e) {}

            var storage = [];
            var storageTruncated = false;
            function scanArea(label, areaName) {
              var area = null;
              try { area = window[areaName]; } catch (e) { return; }
              if (!area) return;
              try {
                for (var i = 0; i < area.length; i++) {
                  if (storage.length >= $HIT_CAP) { storageTruncated = true; return; }
                  var key = null;
                  try { key = area.key(i); } catch (e) { continue; }
                  if (key === null || key === undefined) continue;
                  if (matches(key)) {
                    storage.push({ area: label, key: String(key), matchedIn: 'key', valueLength: null });
                    continue;
                  }
                  var value = null;
                  try { value = area.getItem(key); } catch (e) {}
                  if (value !== null && value !== undefined && matches(value)) {
                    storage.push({ area: label, key: String(key), matchedIn: 'value', valueLength: String(value).length });
                  }
                }
              } catch (e) {}
            }
            scanArea('local', 'localStorage');
            scanArea('session', 'sessionStorage');

            var cookies = [];
            try {
              var parts = String(document.cookie || '').split('; ');
              for (var c = 0; c < parts.length; c++) {
                if (cookies.length >= $HIT_CAP) break;
                var part = parts[c];
                if (!part) continue;
                var eq = part.indexOf('=');
                var cookieName = eq < 0 ? part : part.substring(0, eq);
                var cookieValue = eq < 0 ? '' : part.substring(eq + 1);
                if (matches(cookieName)) {
                  cookies.push({ name: cookieName, matchedIn: 'name' });
                } else if (cookieValue && matches(cookieValue)) {
                  cookies.push({ name: cookieName, matchedIn: 'value' });
                }
              }
            } catch (e) {}

            var dom = [];
            var domTruncated = false;
            try {
              var all = document.querySelectorAll('*');
              if (all.length > $DOM_SCAN_CAP) domTruncated = true;
              var limit = Math.min(all.length, $DOM_SCAN_CAP);
              for (var d = 0; d < limit; d++) {
                if (dom.length >= $HIT_CAP) { domTruncated = true; break; }
                var el = all[d];
                var tag = '';
                var id = null;
                var classes = null;
                var text = '';
                try {
                  tag = (el.tagName || '').toLowerCase();
                  id = el.id || null;
                  classes = typeof el.className === 'string' ? (el.className || null) : null;
                  var kids = el.childNodes;
                  var acc = '';
                  for (var k = 0; k < kids.length; k++) {
                    if (kids[k].nodeType === 3) {
                      acc += kids[k].nodeValue || '';
                      if (acc.length >= $DOM_TEXT_CAP) break;
                    }
                  }
                  text = acc.length > $DOM_TEXT_CAP ? acc.substring(0, $DOM_TEXT_CAP) : acc;
                } catch (e) { continue; }
                var textAt = text.toLowerCase().indexOf(needle);
                if (matches(tag) || (id !== null && matches(id)) ||
                    (classes !== null && matches(classes)) || textAt >= 0) {
                  var snippet = null;
                  if (text.length > 0) {
                    var start = textAt >= 0 ? Math.max(0, textAt - ($SNIPPET_CHARS / 2)) : 0;
                    var end = Math.min(text.length, start + $SNIPPET_CHARS);
                    if (end - start < $SNIPPET_CHARS) start = Math.max(0, end - $SNIPPET_CHARS);
                    snippet = text.substring(start, end);
                  }
                  dom.push({ tag: tag, id: id, classes: classes, snippet: snippet });
                }
              }
            } catch (e) {}

            return JSON.stringify({
              variables: variables,
              storage: storage,
              cookies: cookies,
              dom: dom,
              variablesTruncated: variablesTruncated,
              storageTruncated: storageTruncated,
              domTruncated: domTruncated
            });
          } catch (e) { return null; }
        })()
    """.trimIndent()
}

/**
 * One page variable that matched: its `typeof`, and a short preview of its
 * value only when the value is a primitive whose string form is short enough
 * to be a preview rather than a dump. A variable matches on its name or on the
 * string form of a primitive value, which is the "find the variable that holds
 * this string" half of the ask.
 */
@Serializable
data class VariableHit(val name: String, val kind: String, val preview: String? = null)

/**
 * One storage key that matched. [matchedIn] is `"key"` or `"value"`; a value
 * match carries [valueLength] and never the value itself, which stays behind
 * the reveal-on-demand rule this module keeps everywhere.
 *
 * [area] is `"local"` or `"session"` -- the spelling `storageValue` accepts, so
 * the row a reader taps passes its own hit straight through.
 */
@Serializable
data class StorageHit(
    val area: String,
    val key: String,
    val matchedIn: String,
    val valueLength: Int? = null
)

/**
 * One cookie the page itself can see that matched. A value match is reported as
 * `matchedIn = "value"` without the value; an HttpOnly cookie is invisible to
 * the page and therefore to this probe, which the limits page says outright
 * rather than pretending the list is complete.
 */
@Serializable
data class CookieHit(val name: String, val matchedIn: String)

/**
 * One element whose tag name, id, class list or own text matched. [snippet] is
 * the window around the first match in the element's own text, and is null when
 * the element matched on its name or attributes and has no text of its own.
 */
@Serializable
data class DomHit(
    val tag: String,
    val id: String? = null,
    val classes: String? = null,
    val snippet: String? = null
)

/**
 * Decoded [DeveloperToolsSearchScripts.searchJs] output.
 *
 * PUBLIC ON PURPOSE, like [AuditProbe] and [ApplicationProbe]: this is the
 * return type of a public read on `InspectorSession`, and a public function may
 * not expose an internal type. Every list defaults to empty because an empty
 * category and an unreadable one are the same answer to "did anything match"
 * -- the panel says nothing for the first and the session's null covers the
 * second.
 */
@Serializable
data class SearchProbe(
    val variables: List<VariableHit> = emptyList(),
    val storage: List<StorageHit> = emptyList(),
    val cookies: List<CookieHit> = emptyList(),
    val dom: List<DomHit> = emptyList(),
    val variablesTruncated: Boolean = false,
    val storageTruncated: Boolean = false,
    val domTruncated: Boolean = false
)

/**
 * The console buffer searched app-side. The page-side probe cannot read it back
 * because it never left the app: the ring is this module's own, redacted at
 * entry by its own rules, so the search happens where the data is.
 *
 * Matches the entry's text and its source URL, case-insensitively, and keeps
 * the NEWEST [DeveloperToolsSearchScripts.HIT_CAP] rows -- the same end of the
 * feed the Console panel draws.
 */
internal fun consoleHits(entries: List<ConsoleEntry>, query: String): List<ConsoleEntry> {
    if (query.isEmpty()) return emptyList()
    return entries.filter { entry ->
        entry.text.contains(query, ignoreCase = true) ||
            entry.source?.contains(query, ignoreCase = true) == true
    }.takeLast(DeveloperToolsSearchScripts.HIT_CAP)
}

/**
 * The network buffer searched app-side: the URL, the method, the kind, the
 * status digits, and header NAMES.
 *
 * Header VALUES are not searched at all. They were redacted on the way in, so
 * the original secret is not in the buffer to be found -- and a redacted
 * placeholder must not become searchable text either. The only way the literal
 * `<redacted>` can hit is a URL that carries it, because a redacted URL keeps
 * the placeholder in place of the credentials it masked, and the URL is
 * genuinely on the row.
 */
internal fun networkHits(entries: List<NetworkEntry>, query: String): List<NetworkEntry> {
    if (query.isEmpty()) return emptyList()
    return entries.filter { entry ->
        entry.url.contains(query, ignoreCase = true) ||
            entry.method?.contains(query, ignoreCase = true) == true ||
            entry.kind.contains(query, ignoreCase = true) ||
            entry.status?.toString()?.contains(query) == true ||
            (entry.requestHeaders + entry.responseHeaders).any { header ->
                header.name.contains(query, ignoreCase = true)
            }
    }.takeLast(DeveloperToolsSearchScripts.HIT_CAP)
}
