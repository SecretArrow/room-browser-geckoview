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
    // Without a port the page sees no wallet, no vault and no device shim.
    // Nothing can be done from here -- the app side logs the same failure --
    // and throwing would only replace a silent absence with a broken page.
    return;
  }

  port.onMessage.addListener(function (message) {
    if (!message || typeof message !== "object") return;
    if (message.type === "eval") {
      window.postMessage({ __roomEval: message.id, code: message.code }, "*");
    } else if (message.type === "scripts") {
      window.postMessage({ __roomScripts: message }, "*");
    }
  });

  port.onDisconnect.addListener(function () {
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
    }
  });
})();
