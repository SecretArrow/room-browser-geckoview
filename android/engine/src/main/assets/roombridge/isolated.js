/*
 * Room Browser bridge -- ISOLATED WORLD half.
 *
 * The only half that can hold a native port. It owns one port to the app and
 * is a two-way pump between it and main.js:
 *
 *   native  --port-->  isolated.js  --postMessage-->  main.js  -->  page
 *   native  <--port--  isolated.js  <--postMessage--  main.js  <--  page
 *
 * Deliberately NOT `all_frames`: one port per document is what the app-side
 * bookkeeping assumes, and a port per iframe would multiply the eval
 * round-trips and the pending-result map for no gain -- the page-world half
 * already runs in every frame for the shim's sake, and it is the frame that
 * needs evaluating, not this one.
 *
 * There is no background page. A content-script port is already routed to the
 * per-session MessageDelegate on the app side, so a background page would add
 * a hop and a lifecycle to manage while changing nothing about where the
 * message lands.
 */

(function () {
  "use strict";

  if (window.__roomIsolatedInstalled) return;
  window.__roomIsolatedInstalled = true;

  /** Must match BRIDGE_NATIVE_APP in GeckoEngineSession.kt. */
  var NATIVE_APP = "roombridge";

  var port;
  try {
    port = browser.runtime.connectNative(NATIVE_APP);
  } catch (e) {
    // Without a port the page sees no wallet, no vault and no device shim, so
    // this is the loudest failure in the extension -- and until the manifest
    // carried the full permission trio it was also the quietest, because
    // GeckoView reports a missing privileged permission by leaving
    // `browser.runtime.connectNative` undefined rather than by refusing to
    // install the extension. The install succeeded, the app-side delegate was
    // installed, and neither end logged anything.
    //
    // The console is the only channel left: reporting this needs native
    // messaging, which is exactly what just failed. An earlier version of this
    // catch said "the app side logs the same failure" and returned silently --
    // it did not, and could not, since a failure to open a port never reaches
    // the app as a message.
    console.error("[roombridge] connectNative failed: " + e);
    return;
  }

  port.onMessage.addListener(function (message) {
    if (!message || typeof message !== "object") return;
    if (message.type === "eval") {
      window.postMessage({ __roomEval: message.id, code: message.code }, "*");
    } else if (message.type === "scripts") {
      // Stringified, not the object: an object does not survive the world
      // boundary (see the PRIMITIVE-ONLY note in main.js).
      window.postMessage({ __roomScripts: JSON.stringify(message) }, "*");
    } else if (message.type === "consoleStart") {
      window.postMessage({ __roomConsoleStart: 1 }, "*");
    } else if (message.type === "consoleStop") {
      window.postMessage({ __roomConsoleStop: 1 }, "*");
    }
  });

  port.onDisconnect.addListener(function () {
    // A disconnect this early is the app-side gate, not the page: GeckoView
    // refuses a content-script sender whose extension lacks
    // WebExtension.Flags.ALLOW_CONTENT_MESSAGING and answers with
    // "This NativeApp can't receive messages from Content Scripts." on this
    // exact channel. `lastError` is only readable synchronously here, so it is
    // read before anything else can clear it.
    var error = browser.runtime.lastError;
    if (error) {
      console.error("[roombridge] port closed by the app: " + error.message);
    }
    port = null;
  });

  window.addEventListener("message", function (event) {
    if (event.source !== window) return;
    var data = event.data;
    if (!data || typeof data !== "object") return;

    if (!port) return;

    if (data.__roomEvalResult === 1) {
      port.postMessage({ type: "evalResult", id: data.id, value: data.value });
      return;
    }

    if (data.__roomFromPage === 1) {
      port.postMessage({
        type: "app",
        channel: data.channel,
        payload: data.payload
      });
      return;
    }

    if (data.__roomConsoleFromPage === 1) {
      // The entry is already a JSON string, so it crosses this boundary and
      // the port unchanged.
      port.postMessage({ type: "console", entry: String(data.entry) });
    }
  });
})();
