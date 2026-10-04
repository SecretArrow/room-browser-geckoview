/*
 * Room Browser bridge -- PAGE WORLD half.
 *
 * This runs in the page's own JavaScript world at document_start, before any
 * page script, in every frame. That is not a preference: the scripts it hosts
 * define globals the page calls (window.RoomWallet, window.RoomVault) and a
 * device shim whose whole purpose is to be installed before the page measures
 * anything. An isolated-world script cannot write those -- its window is a
 * different object -- so this half must be the page world.
 *
 * The cost of running here is that there is no `browser.*` API: a page-world
 * script cannot reach the extension APIs at all. That is what isolated.js is
 * for, and the two halves talk over window.postMessage.
 *
 * MESSAGES USE PRIMITIVE-ONLY PAYLOADS on purpose. A string or a number
 * crosses the world boundary intact; a structured object arrives wrapped, and
 * the receiving side's property access silently stops seeing what was sent.
 */

(function () {
  "use strict";

  // Idempotent: this file is injected once per document, but a re-injection
  // (a same-document navigation, an engine quirk) must not stack a second
  // listener on the same window.
  if (window.__roomBridgeInstalled) return;
  window.__roomBridgeInstalled = true;

  /**
   * Evaluate in the page world.
   *
   * Indirect eval -- (0, eval)(...) -- so the code runs in global scope and
   * can see and define page globals. A bare eval() would run in this closure
   * and `var` declarations would land somewhere the page cannot see.
   */
  function evaluate(code) {
    try {
      return (0, eval)(code);
    } catch (e) {
      return null;
    }
  }

  /**
   * Encode a result for the wire.
   *
   * JSON.stringify, because that is the exact shape the WebView edition's
   * evaluateJavascript produced: a string containing JSON. Keeping the
   * encoding identical means the app-side callers did not have to change, and
   * a caller that unquotes a result still gets the right answer.
   */
  function encode(value) {
    try {
      return JSON.stringify(value === undefined ? null : value);
    } catch (e) {
      // A result that cannot be serialised (a cyclic object, a DOM node) is
      // reported as an absent result rather than as a broken message.
      return null;
    }
  }

  window.addEventListener("message", function (event) {
    if (event.source !== window) return;
    var data = event.data;
    if (!data || typeof data !== "object") return;

    if (data.__roomEval !== undefined) {
      window.postMessage(
        { __roomEvalResult: 1, id: data.__roomEval, value: encode(evaluate(data.code)) },
        "*"
      );
      return;
    }

    if (data.__roomScripts) {
      var scripts = data.__roomScripts;
      // Order matters: the device shim first, so anything the later scripts
      // read from the environment is already the claimed one.
      if (scripts.deviceShim) evaluate(scripts.deviceShim);
      if (scripts.vault) evaluate(scripts.vault);
      if (scripts.wallet) evaluate(scripts.wallet);
    }
  });

  /*
   * Outbound: the page scripts send with window.postMessage and this
   * re-labels it for the isolated half. The marker is a PRIMITIVE, so it
   * survives the boundary -- and only messages this file produced are
   * forwarded, so a page cannot smuggle its own envelope to native code by
   * posting a look-alike object.
   */
  window.addEventListener("message", function (event) {
    if (event.source !== window) return;
    var data = event.data;
    if (!data || typeof data !== "object") return;
    if (data.__roomToIso !== 1) return;
    window.postMessage(
      {
        __roomFromPage: 1,
        channel: String(data.channel),
        payload: String(data.payload)
      },
      "*"
    );
  });
})();
