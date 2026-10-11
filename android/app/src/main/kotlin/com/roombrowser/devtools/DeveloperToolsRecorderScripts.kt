package com.roombrowser.devtools

import kotlinx.serialization.Serializable

/**
 * The Recorder's page-side probe.
 *
 * The same class of thing as [DeveloperToolsScripts]: the app authors these and
 * ships them, and they are unrelated to the agent's `run_js`, which runs a
 * script the *model* wrote. The listeners they install are the whole mechanism
 * -- a click, a typed value and a navigation each become one step carrying the
 * selector that found the element and the moment it happened.
 *
 * The steps live in the page's own `sessionStorage` under one key, so a
 * same-site full page load does not lose them (the document's listeners die
 * with the document; the buffer does not). The panel re-evaluates [startJs] on
 * every URL change while recording, which is what reinstalls them. [stopJs]
 * removes both the listeners and the key; a recording that crosses to another
 * origin does not follow, because `sessionStorage` does not.
 */
internal object DeveloperToolsRecorderScripts {

    /** Steps the page keeps. Mirrored in the panel, so the number is a promise. */
    const val CAP = 500

    private const val KEY = "__rbRecorderState"

    private val HELPERS = """
        var __rbKey = '$KEY';
        function __rbRead() {
          try {
            var raw = sessionStorage.getItem(__rbKey);
            if (!raw) return { dropped: 0, steps: [] };
            var parsed = JSON.parse(raw);
            if (!parsed || !parsed.steps) return { dropped: 0, steps: [] };
            return { dropped: parsed.dropped || 0, steps: parsed.steps || [] };
          } catch (e) { return { dropped: 0, steps: [] }; }
        }
        function __rbWrite(state) {
          try { sessionStorage.setItem(__rbKey, JSON.stringify(state)); } catch (e) {}
        }
        function __rbPush(step) {
          var state = __rbRead();
          step.timestampMs = Date.now();
          state.steps.push(step);
          if (state.steps.length > $CAP) {
            state.steps = state.steps.slice(state.steps.length - $CAP);
            state.dropped = (state.dropped || 0) + 1;
          }
          __rbWrite(state);
        }
        function __rbNav(note) {
          __rbPush({ action: 'NAVIGATE', url: String(location.href), note: note });
        }
        function __rbUninstall() {
          var h = window.__rbRecorderHandlers;
          if (!h) return;
          try {
            document.removeEventListener('click', h.click, true);
            document.removeEventListener('input', h.input, true);
            document.removeEventListener('change', h.change, true);
            window.removeEventListener('popstate', h.popstate);
            window.removeEventListener('hashchange', h.hashchange);
            if (h.pushState) history.pushState = h.pushState;
            if (h.replaceState) history.replaceState = h.replaceState;
          } catch (e) {}
          window.__rbRecorderHandlers = null;
        }
    """

    private val SELECTOR = """
        function __rbSelector(el) {
          try {
            if (!el || el.nodeType !== 1) return { kind: 'NONE', selector: null };
            if (el.id) return { kind: 'ID', selector: '#' + el.id };
            var named = el.getAttribute ? el.getAttribute('name') : null;
            if (named) {
              return {
                kind: 'NAME',
                selector: el.tagName.toLowerCase() + '[name="' + String(named).replace(/"/g, '\\"') + '"]'
              };
            }
            var parts = [];
            var node = el;
            var depth = 0;
            while (node && node.nodeType === 1 && depth < 6) {
              var tag = node.tagName.toLowerCase();
              var parent = node.parentElement;
              if (parent) {
                var index = 1;
                var sib = node;
                while ((sib = sib.previousElementSibling)) index++;
                parts.unshift(tag + ':nth-of-type(' + index + ')');
              } else {
                parts.unshift(tag);
              }
              node = parent;
              depth++;
            }
            return { kind: 'STRUCTURAL', selector: parts.join(' > ') };
          } catch (e) {
            return { kind: 'NONE', selector: null };
          }
        }
    """

    /** Installs the listeners and answers with the buffer. Idempotent for one document. */
    fun startJs(): String = """
        (function () {
          $HELPERS
          $SELECTOR
          if (window.__rbRecorderHandlers) {
            var live = __rbRead();
            live.active = true;
            return JSON.stringify(live);
          }
          var handlers = {};
          handlers.click = function (e) {
            var el = e.target;
            if (!el || el.nodeType !== 1) return;
            var sel = __rbSelector(el);
            __rbPush({
              action: 'CLICK',
              selectorKind: sel.kind,
              selector: sel.selector,
              tagName: el.tagName ? el.tagName.toLowerCase() : null
            });
          };
          handlers.input = function (e) {
            var el = e.target;
            if (!el || el.nodeType !== 1) return;
            var tag = el.tagName ? el.tagName.toLowerCase() : '';
            if (tag !== 'input' && tag !== 'textarea') return;
            var type = String(el.type || 'text').toLowerCase();
            if (type === 'checkbox' || type === 'radio' || type === 'range' ||
                type === 'file' || type === 'submit' || type === 'button') return;
            var sel = __rbSelector(el);
            var secret = type === 'password';
            __rbPush({
              action: 'INPUT',
              selectorKind: sel.kind,
              selector: sel.selector,
              tagName: tag,
              value: secret ? null : String(el.value == null ? '' : el.value),
              note: secret ? 'value withheld: password field' : null
            });
          };
          handlers.change = function (e) {
            var el = e.target;
            if (!el || el.nodeType !== 1) return;
            var tag = el.tagName ? el.tagName.toLowerCase() : '';
            if (tag !== 'select') return;
            var sel = __rbSelector(el);
            __rbPush({
              action: 'CHANGE',
              selectorKind: sel.kind,
              selector: sel.selector,
              tagName: tag,
              value: String(el.value == null ? '' : el.value)
            });
          };
          handlers.popstate = function () { __rbNav('history'); };
          handlers.hashchange = function () { __rbNav('hash'); };
          try {
            handlers.pushState = history.pushState;
            handlers.replaceState = history.replaceState;
            history.pushState = function () {
              var r = handlers.pushState.apply(this, arguments);
              __rbNav('history');
              return r;
            };
            history.replaceState = function () {
              var r = handlers.replaceState.apply(this, arguments);
              __rbNav('history');
              return r;
            };
          } catch (e) {}
          document.addEventListener('click', handlers.click, true);
          document.addEventListener('input', handlers.input, true);
          document.addEventListener('change', handlers.change, true);
          window.addEventListener('popstate', handlers.popstate);
          window.addEventListener('hashchange', handlers.hashchange);
          window.__rbRecorderHandlers = handlers;
          var state = __rbRead();
          var last = state.steps.length ? state.steps[state.steps.length - 1] : null;
          if (!last || last.action !== 'NAVIGATE' || last.url !== String(location.href)) {
            __rbNav('load');
          }
          state = __rbRead();
          state.active = true;
          return JSON.stringify(state);
        })()
    """.trimIndent()

    /** Reads the buffer without touching the listeners. */
    fun pullJs(): String = """
        (function () {
          $HELPERS
          var state = __rbRead();
          state.active = !!window.__rbRecorderHandlers;
          return JSON.stringify(state);
        })()
    """.trimIndent()

    /**
     * Removes the listeners and KEEPS the steps.
     *
     * What leaving the panel runs: a recorder left running in someone's page is
     * not acceptable, but neither is throwing away what they recorded.
     */
    fun uninstallJs(): String = """
        (function () {
          $HELPERS
          __rbUninstall();
          var state = __rbRead();
          state.active = false;
          return JSON.stringify(state);
        })()
    """.trimIndent()

    /** Removes the listeners and the buffer, answering with what it held. */
    fun stopJs(): String = """
        (function () {
          $HELPERS
          __rbUninstall();
          var state = __rbRead();
          state.active = false;
          try { sessionStorage.removeItem(__rbKey); } catch (e) {}
          return JSON.stringify(state);
        })()
    """.trimIndent()

    /** Empties the buffer without stopping a running recording. */
    fun clearJs(): String = """
        (function () {
          $HELPERS
          try { sessionStorage.removeItem(__rbKey); } catch (e) {}
          return JSON.stringify({ dropped: 0, steps: [], active: !!window.__rbRecorderHandlers });
        })()
    """.trimIndent()
}

/** One recorded step, exactly as the page wrote it. Everything is nullable: a field the page did not fill is absent, never invented. */
@Serializable
internal data class RecorderStep(
    val action: String = "UNKNOWN",
    val selectorKind: String = "NONE",
    val selector: String? = null,
    val tagName: String? = null,
    val value: String? = null,
    val url: String? = null,
    val timestampMs: Long = 0L,
    val note: String? = null
)

/** What one read of the page buffer returned. */
@Serializable
internal data class RecorderSnapshot(
    val active: Boolean = false,
    val dropped: Int = 0,
    val steps: List<RecorderStep> = emptyList()
)
