package com.roombrowser.devtools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The Application panel's probe scripts, and the models they decode into.
 *
 * SPLIT IN TWO, AND THAT IS THE WHOLE POINT. `EngineSession.evaluateJs` returns
 * the VALUE of the expression it is given: on WebView that is Chromium's
 * `evaluateJavascript`, and on GeckoView it is `(0, eval)(code)` over the
 * native-messaging port. NEITHER AWAITS A PROMISE -- a script whose value is a
 * pending promise comes back as the empty object, silently. So everything this
 * panel reads that is asynchronous -- `indexedDB.databases()`,
 * `caches.keys()`, `navigator.storage.estimate()`, `fetch()` of the manifest --
 * is started by [applicationProbeStartJs], which returns immediately, recorded
 * on a window global, and read back by [applicationProbeReadJs] until it says
 * it is finished.
 *
 * Every field is nullable and every sub-read is individually guarded, so one
 * API a page has removed or that throws leaves its own section absent and the
 * rest of the panel intact. A section that is absent and a section that is
 * empty are different answers here, exactly as they are everywhere else in
 * this module.
 *
 * The probe reads KEY NAMES AND SIZES only. Storage values are not sent to the
 * app by this script at all -- the panel reveals one value at a time through
 * [storageValueJs], so the entire contents of a site's localStorage are never
 * held in a feed, in a buffer, or in anything this module can export.
 */
internal object DeveloperToolsStorageScripts {

    /**
     * Starts the asynchronous reads and installs the result global.
     *
     * The pending counter is what makes "done" mean ALL of them rather than the
     * first: each task settles exactly once, whether it succeeded or threw, so
     * a single rejection cannot leave the panel waiting forever. The timer is
     * the backstop for a page that answers neither way, and the app has its own
     * deadline on top of that for a page that never runs this at all.
     */
    fun applicationProbeStartJs(): String = """
        (function () {
          var probe = {
            done: false,
            failures: [],
            manifest: null,
            hasManifestLink: false,
            serviceWorkers: null,
            localStorage: null,
            sessionStorage: null,
            databases: null,
            caches: null,
            estimate: null,
            reports: null,
            background: null,
            bfcache: null
          };
          window.__rbApp = probe;
          try { if (window.__rbAppTimer) clearTimeout(window.__rbAppTimer); } catch (e) {}

          function fail(what, e) {
            probe.failures.push(what + ': ' + ((e && e.message) || String(e)));
          }
          function failThen(what, done) {
            return function (e) { fail(what, e); done(); };
          }
          var pending = 0;
          function task(fn) {
            pending++;
            var settled = false;
            function done() {
              if (settled) return;
              settled = true;
              pending--;
              if (pending === 0) probe.done = true;
            }
            try { fn(done); } catch (e) { fail('task', e); done(); }
          }

          // ---- storage areas, read synchronously --------------------------

          function dumpArea(area) {
            var keys = [];
            for (var i = 0; i < area.length && i < 200; i++) {
              var k = area.key(i);
              if (k === null) continue;
              var v = null;
              try { v = area.getItem(k); } catch (e) {}
              keys.push({ key: k, length: v === null ? null : v.length });
            }
            return { total: area.length, keys: keys, truncated: area.length > 200 };
          }

          try { probe.localStorage = dumpArea(window.localStorage); }
          catch (e) { probe.localStorage = { blocked: true }; }
          try { probe.sessionStorage = dumpArea(window.sessionStorage); }
          catch (e) { probe.sessionStorage = { blocked: true }; }

          // ---- the manifest -----------------------------------------------

          var link = null;
          try { link = document.querySelector('link[rel="manifest"]'); } catch (e) {}
          probe.hasManifestLink = !!link;
          if (link && link.href) {
            task(function (done) {
              fetch(link.href, { credentials: 'omit' })
                .then(function (r) {
                  if (!r.ok) { probe.manifest = { href: link.href, status: r.status }; done(); return null; }
                  return r.text();
                })
                .then(function (text) {
                  if (text === null || text === undefined) return;
                  var parsed = null;
                  try { parsed = JSON.parse(text); } catch (e) { fail('manifest-parse', e); }
                  probe.manifest = {
                    href: link.href,
                    status: 200,
                    name: parsed ? (parsed.name || null) : null,
                    shortName: parsed ? (parsed.short_name || null) : null,
                    startUrl: parsed ? (parsed.start_url || null) : null,
                    display: parsed ? (parsed.display || null) : null,
                    themeColor: parsed ? (parsed.theme_color || null) : null,
                    backgroundColor: parsed ? (parsed.background_color || null) : null,
                    iconCount: parsed && parsed.icons ? parsed.icons.length : null
                  };
                  done();
                })
                .catch(failThen('manifest-fetch', done));
            });
          }

          // ---- service workers --------------------------------------------

          if (!('serviceWorker' in navigator)) {
            probe.serviceWorkers = null;
          } else {
            task(function (done) {
              navigator.serviceWorker.getRegistrations()
                .then(function (regs) {
                  function state(worker) {
                    return worker ? { scriptURL: worker.scriptURL, state: worker.state } : null;
                  }
                  probe.serviceWorkers = {
                    controller: state(navigator.serviceWorker.controller),
                    registrations: regs.map(function (r) {
                      return {
                        scope: r.scope,
                        active: state(r.active),
                        waiting: state(r.waiting),
                        installing: state(r.installing),
                        hasPush: !!r.pushManager
                      };
                    })
                  };
                  done();
                })
                .catch(failThen('serviceWorker', done));
            });
          }

          // ---- IndexedDB --------------------------------------------------

          if (!('indexedDB' in window) || typeof indexedDB.databases !== 'function') {
            probe.databases = null;
          } else {
            task(function (done) {
              indexedDB.databases()
                .then(function (list) {
                  var named = (list || []).filter(function (d) { return !!d.name; }).slice(0, 10);
                  if (named.length === 0) { probe.databases = []; done(); return; }
                  // ONE array, built here and published once: a row pushed
                  // while the loop runs must not be lost to a later iteration.
                  var rows = [];
                  var remaining = named.length;
                  named.forEach(function (d) {
                    var row = { name: d.name, version: d.version || null, stores: null, error: null };
                    rows.push(row);
                    var rowSettled = false;
                    function finishRow() {
                      if (rowSettled) return;
                      rowSettled = true;
                      remaining--;
                      if (remaining === 0) { probe.databases = rows; done(); }
                    }
                    var request = null;
                    try { request = indexedDB.open(d.name); }
                    catch (e) { row.error = String(e); finishRow(); return; }
                    request.onerror = function () { row.error = row.error || 'open failed'; finishRow(); };
                    request.onupgradeneeded = function () {
                      // A database this app would have to CREATE is not one the
                      // page is using; abort rather than leave an empty one
                      // behind as the price of inspecting it.
                      row.error = 'not present; opening it would create it';
                      try { request.transaction.abort(); } catch (e) {}
                      finishRow();
                    };
                    request.onsuccess = function () {
                      var db = request.result;
                      row.version = db.version;
                      row.stores = [];
                      var names = [];
                      try {
                        for (var i = 0; i < db.objectStoreNames.length && i < 20; i++) {
                          names.push(db.objectStoreNames[i]);
                        }
                      } catch (e) { row.error = String(e); }
                      if (names.length === 0) { try { db.close(); } catch (e) {} finishRow(); return; }
                      var left = names.length;
                      function storeDone() { left--; if (left === 0) { try { db.close(); } catch (e) {} finishRow(); } }
                      names.forEach(function (storeName) {
                        var countRow = { name: storeName, count: null };
                        row.stores.push(countRow);
                        try {
                          var tx = db.transaction(storeName, 'readonly');
                          var countReq = tx.objectStore(storeName).count();
                          countReq.onsuccess = function () { countRow.count = countReq.result; };
                          tx.oncomplete = storeDone;
                          tx.onerror = storeDone;
                          tx.onabort = storeDone;
                        } catch (e) { storeDone(); }
                      });
                    };
                  });
                })
                .catch(failThen('indexedDB', done));
            });
          }

          // ---- Cache Storage ----------------------------------------------

          if (!('caches' in window)) {
            probe.caches = null;
          } else {
            task(function (done) {
              caches.keys()
                .then(function (names) {
                  var picked = names.slice(0, 10);
                  if (picked.length === 0) { probe.caches = []; done(); return; }
                  var remaining = picked.length;
                  var out = [];
                  picked.forEach(function (name) {
                    caches.open(name)
                      .then(function (cache) { return cache.keys(); })
                      .then(function (requests) {
                        out.push({
                          name: name,
                          entries: requests.length,
                          urls: requests.slice(0, 100).map(function (r) { return r.url; })
                        });
                      })
                      .catch(function (e) { fail('cache-' + name, e); })
                      .then(function () { remaining--; if (remaining === 0) { probe.caches = out; done(); } });
                  });
                })
                .catch(failThen('caches', done));
            });
          }

          // ---- usage and quota --------------------------------------------

          if (!(navigator.storage && navigator.storage.estimate)) {
            probe.estimate = null;
          } else {
            task(function (done) {
              var usage = null;
              var quota = null;
              var persisted = null;
              var left = 2;
              function one() { left--; if (left === 0) { probe.estimate = { usage: usage, quota: quota, persisted: persisted }; done(); } }
              navigator.storage.estimate()
                .then(function (e) { usage = e.usage; quota = e.quota; one(); })
                .catch(failThen('estimate', one));
              if (navigator.storage.persisted) {
                navigator.storage.persisted()
                  .then(function (p) { persisted = p; one(); })
                  .catch(failThen('persisted', one));
              } else {
                one();
              }
            });
          }

          // ---- Reporting API ----------------------------------------------

          probe.reports = null;
          try {
            if (typeof ReportingObserver === 'function') {
              probe.reports = [];
              var observer = new ReportingObserver(function (reports) {
                reports.forEach(function (r) {
                  var body = r.body || {};
                  var json = null;
                  try { json = typeof body.toJSON === 'function' ? body.toJSON() : null; } catch (e) {}
                  probe.reports.push({ type: r.type || null, url: body.url || null, message: body.message || null, body: json });
                });
              }, { buffered: true, types: ['csp-violation', 'deprecation', 'intervention'] });
              observer.observe();
              window.__rbAppReports = observer;
            }
          } catch (e) { fail('reporting', e); probe.reports = []; }

          // ---- push, background sync, bfcache -----------------------------

          if ('serviceWorker' in navigator && navigator.serviceWorker.getRegistration) {
            task(function (done) {
              navigator.serviceWorker.getRegistration()
                .then(function (reg) {
                  if (!reg) { done(); return null; }
                  var out = {
                    hasPushManager: !!reg.pushManager,
                    subscription: null,
                    sync: !!reg.sync,
                    periodicSync: !!reg.periodicSync,
                    backgroundFetch: !!reg.backgroundFetch
                  };
                  probe.background = out;
                  if (!reg.pushManager || !reg.pushManager.getSubscription) { done(); return null; }
                  return reg.pushManager.getSubscription().then(function (sub) {
                    if (sub) {
                      out.subscription = { endpoint: sub.endpoint, expirationTime: sub.expirationTime };
                    }
                  });
                })
                .then(function () { done(); })
                .catch(failThen('push', done));
            });
          }

          try {
            var nav = performance.getEntriesByType('navigation')[0];
            probe.bfcache = nav && typeof nav.notRestoredReasons !== 'undefined'
              ? { supported: true, notRestoredReasons: nav.notRestoredReasons || null }
              : { supported: false };
          } catch (e) { fail('bfcache', e); }

          window.__rbAppTimer = setTimeout(function () { probe.done = true; }, 6000);
          if (pending === 0) probe.done = true;
          return 'started';
        })()
    """.trimIndent()

    /**
     * Reads back what [applicationProbeStartJs] installed.
     *
     * Returns the JSON text of the probe object, or `null` when the page has no
     * probe at all -- a navigation since it started, or a document that never
     * ran the start script.
     */
    fun applicationProbeReadJs(): String = """
        (function () {
          try { return JSON.stringify(window.__rbApp || null); } catch (e) { return null; }
        })()
    """.trimIndent()

    /**
     * The value of ONE storage key, for a single row's reveal.
     *
     * A separate call on purpose: the dump the panel renders carries key names
     * and lengths, so a site's stored values are read only when the user asks
     * for that one row, and never as a whole-store payload.
     */
    fun storageValueJs(area: String, key: String): String {
        val target = if (area == "session") "sessionStorage" else "localStorage"
        return """
            (function () {
              try {
                var v = window.$target.getItem(${jsString(key)});
                return JSON.stringify(v === null ? null : v);
              } catch (e) { return null; }
            })()
        """.trimIndent()
    }

    /**
     * The frame tree, as far as the same-origin policy allows it.
     *
     * A CROSS-ORIGIN FRAME CANNOT BE INSPECTED, and that is the browser
     * behaving correctly rather than a gap to paper over: touching its
     * `document` throws. Such a frame is counted and named by its index, and
     * the row says why it has nothing under it.
     */
    fun framesProbeJs(): String = """
        (function () {
          function describe(frame, path, depth) {
            var node = { path: path, url: null, name: null, title: null, children: [], readable: false };
            try { node.name = frame.name || null; } catch (e) {}
            var doc = null;
            try { doc = frame.document; } catch (e) { return node; }
            if (!doc) return node;
            node.readable = true;
            try { node.url = doc.location.href; } catch (e) {}
            try { node.title = doc.title || null; } catch (e) {}
            if (depth >= 3) return node;
            try {
              for (var i = 0; i < frame.frames.length && i < 20; i++) {
                node.children.push(describe(frame.frames[i], path + '.' + i, depth + 1));
              }
            } catch (e) {}
            return node;
          }
          var root = null;
          try { root = describe(window, '0', 0); } catch (e) {}
          return JSON.stringify(root);
        })()
    """.trimIndent()

    /** A JS string literal, quoted and escaped, so a key from the page cannot break out of the script. */
    private fun jsString(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}

/** One storage area's key listing, or the reason it could not be read. */
@Serializable
data class StorageAreaDump(
    val total: Int? = null,
    val keys: List<StorageKeyEntry>? = null,
    val truncated: Boolean? = null,
    val blocked: Boolean? = null
)

/** A key name and the length of its value. The value itself is not in this dump. */
@Serializable
data class StorageKeyEntry(val key: String, val length: Int? = null)

/** The page's manifest, as the page itself resolved it. */
@Serializable
data class ManifestReport(
    val href: String? = null,
    val status: Int? = null,
    val name: String? = null,
    val shortName: String? = null,
    val startUrl: String? = null,
    val display: String? = null,
    val themeColor: String? = null,
    val backgroundColor: String? = null,
    val iconCount: Int? = null
)

@Serializable
data class ServiceWorkerState(val scriptURL: String? = null, val state: String? = null)

@Serializable
data class ServiceWorkerRegistrationReport(
    val scope: String? = null,
    val active: ServiceWorkerState? = null,
    val waiting: ServiceWorkerState? = null,
    val installing: ServiceWorkerState? = null,
    val hasPush: Boolean? = null
)

@Serializable
data class ServiceWorkerReport(
    val controller: ServiceWorkerState? = null,
    val registrations: List<ServiceWorkerRegistrationReport>? = null
)

@Serializable
data class IndexedDbStore(val name: String? = null, val count: Int? = null)

@Serializable
data class IndexedDbReport(
    val name: String? = null,
    val version: Int? = null,
    val stores: List<IndexedDbStore>? = null,
    val error: String? = null
)

@Serializable
data class CacheReport(val name: String? = null, val entries: Int? = null, val urls: List<String>? = null)

@Serializable
data class StorageEstimateReport(val usage: Double? = null, val quota: Double? = null, val persisted: Boolean? = null)

@Serializable
data class ReportingEntry(
    val type: String? = null,
    val url: String? = null,
    val message: String? = null,
    val body: JsonElement? = null
)

@Serializable
data class PushSubscriptionReport(val endpoint: String? = null, val expirationTime: Double? = null)

@Serializable
data class BackgroundServicesReport(
    val hasPushManager: Boolean? = null,
    val subscription: PushSubscriptionReport? = null,
    val sync: Boolean? = null,
    val periodicSync: Boolean? = null,
    val backgroundFetch: Boolean? = null
)

@Serializable
data class BfcacheReport(val supported: Boolean? = null, val notRestoredReasons: JsonElement? = null)

/** Everything [DeveloperToolsStorageScripts.applicationProbeStartJs] collects. */
@Serializable
data class ApplicationProbe(
    val done: Boolean = false,
    val failures: List<String>? = null,
    val manifest: ManifestReport? = null,
    val hasManifestLink: Boolean? = null,
    val serviceWorkers: ServiceWorkerReport? = null,
    val localStorage: StorageAreaDump? = null,
    val sessionStorage: StorageAreaDump? = null,
    val databases: List<IndexedDbReport>? = null,
    val caches: List<CacheReport>? = null,
    val estimate: StorageEstimateReport? = null,
    val reports: List<ReportingEntry>? = null,
    val background: BackgroundServicesReport? = null,
    val bfcache: BfcacheReport? = null
)

/** One node of the frame tree. [readable] false means the same-origin policy refused it. */
@Serializable
data class FrameNode(
    val path: String? = null,
    val url: String? = null,
    val name: String? = null,
    val title: String? = null,
    val readable: Boolean? = null,
    val children: List<FrameNode>? = null
)
