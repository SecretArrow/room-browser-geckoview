package com.roombrowser.agent

/**
 * JavaScript injected into the live WebView by the agent's tool executor.
 *
 * Interaction model (same family as WebVoyager/agent-ui approaches, adapted
 * to Android WebView): every visible interactive element gets a sequential
 * `data-agent-ref` number; the model refers to elements by that number and
 * the click/fill/enter scripts resolve the number back to the element.
 *
 * Notes:
 *  - All scripts return plain values/objects; evaluateJavascript delivers
 *    them as JSON text.
 *  - Input filling uses the native value setter + dispatched input/change
 *    events so React/Vue controlled inputs pick the value up.
 *  - No JS template literals are used, so the scripts survive Kotlin string
 *    interpolation without escaping problems.
 */
object PageInjector {

    const val REF_ATTR = "data-agent-ref"

    /**
     * Builds the page snapshot: URL, title, viewport text, scroll position
     * and every visible interactive element tagged with a [ref] number.
     */
    fun snapshotJs(): String = """
        (function(){
          function visible(el){
            var r = el.getBoundingClientRect();
            return r.width > 0 && r.height > 0;
          }
          var els = [];
          var nodes = document.querySelectorAll(
            'a, button, input, select, textarea, [role="button"], [role="link"], [role="checkbox"], [role="radio"], [role="tab"], [role="menuitem"], [onclick], [contenteditable="true"]'
          );
          var n = 0;
          for (var i = 0; i < nodes.length && n < 160; i++) {
            var el = nodes[i];
            if (!visible(el)) continue;
            var ref = ++n;
            el.setAttribute('$REF_ATTR', String(ref));
            var tag = el.tagName.toLowerCase();
            var label = (el.innerText || el.getAttribute('aria-label') ||
              el.getAttribute('placeholder') || el.value || el.getAttribute('title') || el.getAttribute('alt') || '')
              .trim().replace(/\s+/g, ' ').slice(0, 80);
            var o = {
              ref: ref,
              tag: tag,
              label: label,
              viewport: el.getBoundingClientRect().top < window.innerHeight && el.getBoundingClientRect().bottom > 0
            };
            if (tag === 'a') o.href = el.getAttribute('href') || '';
            if (tag === 'input' || tag === 'select' || tag === 'textarea') {
              o.type = el.type || tag;
              if (el.type === 'checkbox' || el.type === 'radio') o.checked = el.checked;
            }
            if (el.disabled) o.disabled = true;
            els.push(o);
          }
          var text = (document.body ? document.body.innerText : '') || '';
          return {
            url: location.href,
            title: document.title || '',
            text: text.slice(0, 9000),
            scrollY: Math.round(window.scrollY || document.documentElement.scrollTop || 0),
            maxScrollY: Math.round(Math.max(document.documentElement.scrollHeight - window.innerHeight, 0)),
            elements: els
          };
        })()
    """.trimIndent()

    /** Clicks the element with the given ref number. */
    fun clickJs(ref: Int): String = """
        (function(){
          var el = document.querySelector('[$REF_ATTR="$ref"]');
          if (!el) return 'element [$ref] not found — call read_page again for fresh refs';
          try { el.scrollIntoView({block: 'center'}); } catch (e) {}
          el.click();
          var label = (el.innerText || el.getAttribute('aria-label') || el.getAttribute('title') || '')
            .trim().slice(0, 60);
          return 'clicked [$ref] ' + (label || '<' + el.tagName.toLowerCase() + '>');
        })()
    """.trimIndent()

    /**
     * Types text into the element with the given ref. [jsonText] MUST be a
     * JSON-encoded string (produced by Json.encodeToString(String.serializer)).
     */
    fun fillJs(ref: Int, jsonText: String): String = """
        (function(){
          var el = document.querySelector('[$REF_ATTR="$ref"]');
          if (!el) return 'element [$ref] not found — call read_page again for fresh refs';
          if (el.disabled || el.readOnly) return 'element [$ref] is disabled or read-only';
          el.focus();
          var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype
            : (el.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype);
          var d = Object.getOwnPropertyDescriptor(proto, 'value');
          if (d && d.set) d.set.call(el, $jsonText); else el.value = $jsonText;
          el.dispatchEvent(new Event('input', {bubbles: true}));
          el.dispatchEvent(new Event('change', {bubbles: true}));
          return 'filled [$ref] with the given text';
        })()
    """.trimIndent()

    /**
     * Presses Enter: focuses the given ref (optional), dispatches a keydown
     * and submits the closest form via requestSubmit.
     */
    fun enterJs(ref: Int?): String {
        val selectorPart = if (ref != null) "var el = document.querySelector('[$REF_ATTR=\"$ref\"]');" else "var el = null;"
        return """
            (function(){
              $selectorPart
              if (el) { try { el.scrollIntoView({block: 'center'}); } catch (e) {} el.focus(); }
              var target = el || document.activeElement || document.body;
              target.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true}));
              target.dispatchEvent(new KeyboardEvent('keyup', {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true}));
              var form = el ? el.closest('form') : (document.activeElement ? document.activeElement.closest('form') : null);
              if (!form) form = document.querySelector('form');
              if (form) { try { if (form.requestSubmit) form.requestSubmit(); else if (form.submit) form.submit(); } catch (e) {} }
              return 'enter sent' + (form ? ' (form submit attempted)' : '');
            })()
        """.trimIndent()
    }

    /** Scrolls by [dy] pixels (positive = down). */
    fun scrollJs(dy: Int): String = """
        (function(){
          window.scrollBy(0, $dy);
          return 'scrolled; now at ' + Math.round(window.scrollY || 0) + ' of ' +
            Math.round(Math.max(document.documentElement.scrollHeight - window.innerHeight, 0));
        })()
    """.trimIndent()

    // ------------------------------------------------------------------
    //  Social automation (auto like / repost / reply / post)
    //  Heuristic label matching (EN + ID) over visible buttons; multi-site
    //  by design (X, Facebook, Reddit, Tumblr, LinkedIn, ...). No template
    //  literals and no dollar signs — safe for Kotlin raw strings.
    // ------------------------------------------------------------------

    /** Likes/upvotes up to [limit] visible social posts. */
    fun autoLikeJs(limit: Int = 20): String = socialClickJs(
        verb = "like",
        limit = limit,
        matchRegex = "/(like|suka|favorit|favorite|heart|love this|upvote|vote up|approve)/i",
        excludeRegex = "/(unlike|liked|dislike|sudah suka|batal|undo|remove|un-?heart)/i"
    )

    /** Reposts/retweets/reblogs up to [limit] visible social posts. */
    fun autoRepostJs(limit: Int = 15): String = socialClickJs(
        verb = "repost",
        limit = limit,
        matchRegex = "/(repost|retweet|reblog|bagikan ulang|share post|boost|re-?share)/i",
        excludeRegex = "/(undo|batalkan|batal|remove repost|unrepost|unretweet|quote)/i"
    )

    private fun socialClickJs(verb: String, limit: Int, matchRegex: String, excludeRegex: String): String = """
        (function(){
          function vis(el){ try { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; } catch (e) { return false; } }
          function norm(s){ return (s || '').replace(/\s+/g, ' ').trim().toLowerCase(); }
          function label(el){
            var s = '';
            try {
              s = (el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '') +
                ' ' + (el.innerText || '') + ' ' + (el.value || '');
            } catch (e) {}
            return norm(s);
          }
          var nodes = document.querySelectorAll('button, [role="button"], a, [aria-label]');
          var match = $matchRegex;
          var exclude = $excludeRegex;
          var n = 0;
          var failed = 0;
          for (var i = 0; i < nodes.length; i++) {
            if (n >= $limit) break;
            var el = nodes[i];
            if (!vis(el) || el.disabled) continue;
            var lb = label(el);
            if (!lb) continue;
            if (exclude.test(lb)) continue;
            if (!match.test(lb)) continue;
            try { el.scrollIntoView({block: 'center'}); } catch (e) {}
            try { el.click(); n++; } catch (e) { failed++; }
          }
          var msg = 'clicked ' + n + ' visible ' + '$verb' + ' buttons';
          if (failed) msg += ' (' + failed + ' failed)';
          if (n === 0) msg += ' — none matched on screen; scroll or read_page to check the page';
          return msg;
        })()
    """.trimIndent()

    /**
     * Types [jsonText] (a JSON-encoded string) into the visible reply box
     * and clicks the matching submit button. [jsonText] MUST be produced by
     * Json.encodeToString(String.serializer(), ...).
     */
    fun autoReplyJs(jsonText: String): String = """
        (function(){
          function vis(el){ try { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; } catch (e) { return false; } }
          function norm(s){ return (s || '').replace(/\s+/g, ' ').trim().toLowerCase(); }
          function setVal(el, text){
            el.focus();
            if (el.isContentEditable) {
              try { document.execCommand('selectAll', false, null); } catch (e) {}
              return document.execCommand('insertText', false, text);
            }
            var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
            var d = Object.getOwnPropertyDescriptor(proto, 'value');
            if (d && d.set) d.set.call(el, text); else el.value = text;
            el.dispatchEvent(new Event('input', {bubbles: true}));
            el.dispatchEvent(new Event('change', {bubbles: true}));
            return true;
          }
          function findComposer(){
            var sels = document.querySelectorAll('[contenteditable="true"], [role="textbox"], textarea');
            for (var i = 0; i < sels.length; i++) { if (vis(sels[i])) return sels[i]; }
            return null;
          }
          function findSubmit(){
            var btns = document.querySelectorAll('button, [role="button"], input[type="submit"]');
            var best = null; var bestLen = 1e9;
            for (var i = 0; i < btns.length; i++) {
              var el = btns[i];
              if (!vis(el) || el.disabled) continue;
              var lb = norm((el.getAttribute('aria-label') || '') + ' ' + (el.innerText || '') + ' ' + (el.getAttribute('value') || ''));
              if (!lb) continue;
              if (/(cancel|batal|edit|delete|hapus|close|tutup|attach|photo|image|gif|poll|emoji|schedule|draft|thread)/i.test(lb)) continue;
              if (/(reply|balas|post|tweet|send|kirim|publish|submit|tambah|share|bagikan)/i.test(lb)) {
                if (lb.length < bestLen) { best = el; bestLen = lb.length; }
              }
            }
            return best;
          }
          var c = findComposer();
          if (!c) return 'no visible reply box found - open the post/thread first (click its Reply button), then retry';
          try { c.scrollIntoView({block: 'center'}); } catch (e) {}
          if (!setVal(c, $jsonText)) return 'could not type into the reply box';
          var b = findSubmit();
          if (b) {
            try { b.click(); return 'typed the reply and clicked submit'; } catch (e) {}
          }
          var form = c.closest ? c.closest('form') : null;
          if (form) {
            try {
              if (form.requestSubmit) form.requestSubmit(); else form.submit();
              return 'typed the reply and submitted the form';
            } catch (e) {}
          }
          return 'typed the reply but no submit button found - read_page, then click the submit [ref]';
        })()
    """.trimIndent()

    /**
     * Publishes a new post: opens the composer when needed, then types and
     * submits. Typing/submission happen asynchronously (setTimeout) because
     * the composer appears after the opener click.
     */
    fun autoPostJs(jsonText: String): String = """
        (function(){
          function vis(el){ try { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; } catch (e) { return false; } }
          function norm(s){ return (s || '').replace(/\s+/g, ' ').trim().toLowerCase(); }
          function label(el){
            var s = '';
            try {
              s = (el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '') + ' ' + (el.innerText || '');
            } catch (e) {}
            return norm(s);
          }
          function setVal(el, text){
            el.focus();
            if (el.isContentEditable) {
              try { document.execCommand('selectAll', false, null); } catch (e) {}
              return document.execCommand('insertText', false, text);
            }
            var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
            var d = Object.getOwnPropertyDescriptor(proto, 'value');
            if (d && d.set) d.set.call(el, text); else el.value = text;
            el.dispatchEvent(new Event('input', {bubbles: true}));
            el.dispatchEvent(new Event('change', {bubbles: true}));
            return true;
          }
          function composer(){
            var sels = document.querySelectorAll('[contenteditable="true"], [role="textbox"], textarea');
            for (var i = 0; i < sels.length; i++) { if (vis(sels[i])) return sels[i]; }
            return null;
          }
          function submitBtn(){
            var btns = document.querySelectorAll('button, [role="button"]');
            var best = null; var bestLen = 1e9;
            for (var i = 0; i < btns.length; i++) {
              var el = btns[i];
              if (!vis(el) || el.disabled) continue;
              var lb = label(el);
              if (!lb) continue;
              if (/(cancel|batal|edit|delete|hapus|close|tutup|attach|photo|image|gif|poll|emoji|schedule|draft|next|back|thread)/i.test(lb)) continue;
              if (/(post|tweet|publish|kirim|send|submit|bagikan|tambah)/i.test(lb)) {
                if (lb.length < bestLen) { best = el; bestLen = lb.length; }
              }
            }
            return best;
          }
          var opened = false;
          var nodes = document.querySelectorAll('button, [role="button"], a');
          for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (!vis(el)) continue;
            var lb = label(el);
            if (!lb) continue;
            if (lb === 'post' || lb === 'tweet' ||
                /(new post|new tweet|compose|create post|post baru|buat posting|tulis posting|start a post|mulai postingan|what.?s on your mind|apa yang sedang terjadi|share an update)/i.test(lb)) {
              try { el.click(); opened = true; } catch (e) {}
              break;
            }
          }
          var hadComposer = !!composer();
          var delay = (opened && !hadComposer) ? 1200 : 150;
          window.setTimeout(function(){
            var c = composer();
            if (!c) return;
            try { c.scrollIntoView({block: 'center'}); } catch (e) {}
            setVal(c, $jsonText);
            window.setTimeout(function(){
              var b = submitBtn();
              if (b) { try { b.click(); } catch (e) {} }
              else {
                var form = c.closest ? c.closest('form') : null;
                if (form) { try { if (form.requestSubmit) form.requestSubmit(); else form.submit(); } catch (e) {} }
              }
            }, 900);
          }, delay);
          var state = opened ? 'opened the composer' : (hadComposer ? 'composer already open' : 'no composer found - type into the page manually');
          return state + '; typing and submitting - call wait (~2s) then read_page to verify';
        })()
    """.trimIndent()

    // ------------------------------------------------------------------
    //  Direct page control (run_js / select_option / press_keys / wait_for)
    // ------------------------------------------------------------------

    /**
     * Runs an arbitrary script the model wrote and hands its value back as
     * text.
     *
     * The script goes through `eval` rather than a wrapping function body so
     * statements AND a final expression both work the way they do in a
     * browser console. Nothing is awaited: a script that returns a Promise is
     * reported as such instead of hanging the tool.
     *
     * [jsonScript] MUST be a JSON-encoded string, which is also what keeps a
     * script containing quotes or newlines from breaking out of the wrapper.
     */
    fun runJs(jsonScript: String): String = """
        (function(){
          try {
            var v = eval($jsonScript);
            if (v === undefined) return 'undefined';
            if (v === null) return 'null';
            if (typeof v === 'object') {
              try { return JSON.stringify(v); } catch (e) { return String(v); }
            }
            return String(v);
          } catch (e) {
            return 'ERROR: ' + (e && e.message ? e.message : String(e));
          }
        })()
    """.trimIndent()

    /**
     * Chooses an option in the `<select>` with the given ref. [jsonChoice] is
     * matched against the option's `value` first, then against its visible
     * text, so a model that only saw the label still selects the right row.
     * On a miss the available options are returned to correct the next call.
     */
    fun selectOptionJs(ref: Int, jsonChoice: String): String = """
        (function(){
          var el = document.querySelector('[$REF_ATTR="$ref"]');
          if (!el) return 'element [$ref] not found — call read_page again for fresh refs';
          if (el.tagName !== 'SELECT') return 'element [$ref] is a <' + el.tagName.toLowerCase() + '>, not a <select>';
          var want = $jsonChoice;
          var opts = el.options || [];
          var picked = null;
          for (var i = 0; i < opts.length; i++) {
            if (opts[i].value === want) { picked = opts[i]; break; }
          }
          if (!picked) {
            var norm = String(want).replace(/\s+/g, ' ').trim().toLowerCase();
            for (var j = 0; j < opts.length; j++) {
              var t = (opts[j].text || '').replace(/\s+/g, ' ').trim().toLowerCase();
              if (t === norm) { picked = opts[j]; break; }
            }
          }
          if (!picked) {
            var names = [];
            for (var k = 0; k < opts.length && k < 40; k++) {
              names.push(opts[k].value + ' ("' + (opts[k].text || '').trim().slice(0, 40) + '")');
            }
            return 'no option matching "' + want + '" in [$ref]. Available: ' + names.join(', ');
          }
          var proto = HTMLSelectElement.prototype;
          var d = Object.getOwnPropertyDescriptor(proto, 'value');
          var setter = (Object.getOwnPropertyDescriptor(proto, 'selectedIndex') || {}).set;
          var idx = picked.index;
          if (setter) setter.call(el, idx); else el.selectedIndex = idx;
          if (d && d.set) d.set.call(el, picked.value);
          el.dispatchEvent(new Event('input', {bubbles: true}));
          el.dispatchEvent(new Event('change', {bubbles: true}));
          return 'selected "' + (picked.text || picked.value).trim().slice(0, 60) + '" in [$ref]';
        })()
    """.trimIndent()

    /**
     * Dispatches a real key chord on the focused element (or on the element
     * with [ref]). [jsonKey] and [jsonCode] are JSON-encoded strings; the
     * legacy `keyCode`/`which` fields are set because pages still branch on
     * them, and a keydown that a page cancels does not get a keypress.
     */
    fun pressKeysJs(
        ref: Int?,
        jsonKey: String,
        jsonCode: String,
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean,
        alt: Boolean,
        meta: Boolean,
        jsonLabel: String
    ): String {
        val target = if (ref != null) {
            "var el = document.querySelector('[$REF_ATTR=\"$ref\"]');"
        } else {
            "var el = document.activeElement || document.body;"
        }
        return """
            (function(){
              $target
              if (!el) return 'no element to send the key to — call read_page first';
              try { if (el.focus) el.focus(); } catch (e) {}
              var init = {key: $jsonKey, code: $jsonCode, keyCode: $keyCode, which: $keyCode,
                bubbles: true, cancelable: true, composed: true,
                ctrlKey: $ctrl, shiftKey: $shift, altKey: $alt, metaKey: $meta};
              var notCancelled = el.dispatchEvent(new KeyboardEvent('keydown', init));
              if (notCancelled && $jsonKey.length === 1) {
                el.dispatchEvent(new KeyboardEvent('keypress', init));
              }
              el.dispatchEvent(new KeyboardEvent('keyup', init));
              return 'sent ' + $jsonLabel;
            })()
        """.trimIndent()
    }

    /**
     * Cheap presence probe for wait_for: '1' when the visible text contains
     * [jsonText] (case-insensitive), '0' otherwise. The polling loop lives in
     * Kotlin because JS cannot suspend.
     */
    fun waitProbeJs(jsonText: String): String = """
        (function(){
          var body = document.body ? (document.body.innerText || '') : '';
          return body.toLowerCase().indexOf(String($jsonText).toLowerCase()) !== -1 ? '1' : '0';
        })()
    """.trimIndent()
}
