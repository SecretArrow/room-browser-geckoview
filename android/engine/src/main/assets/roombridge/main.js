/*
 * Room Browser bridge -- PAGE WORLD half.
 *
 * This runs in the page's own JavaScript world at document_start, before any
 * page script, in every frame. That is not a preference: it defines the two
 * globals the page calls (window.RoomWallet, window.RoomVault) and hosts the
 * device shim, whose whole purpose is to be installed before the page measures
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

  /*
   * ===================== the console patch =====================
   *
   * Installed at document start in EVERY frame, always: the ring buffer is
   * what makes a log written before the DevTools panel opened still visible.
   * Forwarding is gated on `consoleArmed`, so a page nobody is inspecting
   * produces no port traffic at all.
   */

  /** Bounded, oldest dropped: an uninspected page must not grow a buffer forever. */
  var CONSOLE_BUFFER_MAX = 200;
  var consoleBuffer = [];
  var consoleArmed = false;

  function formatConsoleArg(value) {
    try {
      if (typeof value === "string") return value;
      if (value === null) return "null";
      if (typeof value === "object") return JSON.stringify(value);
      return String(value);
    } catch (e) {
      return "[unserialisable]";
    }
  }

  function recordConsole(level, parts) {
    var entry = {
      level: level,
      text: parts.map(formatConsoleArg).join(" ").slice(0, 4000),
      source: location.href,
      line: 0,
      ts: Date.now()
    };
    consoleBuffer.push(entry);
    if (consoleBuffer.length > CONSOLE_BUFFER_MAX) consoleBuffer.shift();
    if (consoleArmed) sendConsole(entry);
  }

  // A JSON string, not the object: an object does not survive the world
  // boundary (see the PRIMITIVE-ONLY note at the top).
  function sendConsole(entry) {
    window.postMessage({ __roomConsole: 1, entry: JSON.stringify(entry) }, "*");
  }

  (function installConsole() {
    ["log", "info", "warn", "error", "debug"].forEach(function (level) {
      var original = console[level];
      if (!original) return;
      console[level] = function () {
        // Recording is best-effort: a failure here must never stop the page's
        // own console call from happening.
        try { recordConsole(level, Array.prototype.slice.call(arguments)); } catch (e) {}
        return original.apply(console, arguments);
      };
    });
    window.addEventListener("error", function (event) {
      recordConsole("error", [
        String(event.message || "error") + " @ " + (event.filename || "") + ":" + (event.lineno || 0)
      ]);
    });
    window.addEventListener("unhandledrejection", function (event) {
      recordConsole("error", ["Unhandled rejection: " + String(event.reason)]);
    });
  })();

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

  /*
   * ===================== the page-callable globals =====================
   *
   * window.RoomWallet and window.RoomVault live here, and this is the whole
   * reason the GeckoView edition needs a page-world half at all. In the WebView
   * edition they were `addJavascriptInterface` registrations; that API has no
   * GeckoView equivalent, so the globals are written by hand and both of their
   * halves -- the page-visible shape and the upward envelope -- are reproduced
   * from the WebView edition's native objects (see WebViewPageBridges.kt, which
   * documents the same contract from the other side).
   *
   * WHAT MUST NOT DRIFT, because the app-side parsers are shared between the
   * two editions and neither was changed for this:
   *
   *   window.RoomWallet.request(json)             -> channel "RoomWallet"
   *     {"method":"request","payload":<json>}        and RETURNS ""
   *   window.RoomVault.requestCredentials(h, href) -> channel "RoomVault"
   *     {"method":"requestCredentials","host":..,"href":..}
   *   window.RoomVault.reportCredential(h, u, p)   -> channel "RoomVault"
   *     {"method":"reportCredential","host":..,"username":..,"password":..}
   *
   * The return value is the empty string for the same reason the WebView
   * edition returns it: answers are asynchronous and arrive as an eval of
   * `window.__roomWalletResponse(...)`, so a caller must not read the return as
   * an answer.
   *
   * Arguments go through `arg`, which mirrors the Kotlin `String?` parameters
   * they used to cross: a number or boolean becomes its string form because
   * that is what the platform bridge did, and undefined/null becomes a JSON
   * null rather than the string "null", which is what `JSONObject.NULL` wrote.
   * The receiving side treats null as absent (`stringOrNull`), and the vault's
   * host check returns early on it -- so getting this wrong would turn a
   * missing host into the literal string "null" and then into a host-check
   * failure rather than a drop.
   *
   * A PAGE CAN CALL THESE, AND THAT IS NOT A NEW CAPABILITY. It could call the
   * WebView edition's interfaces just as directly; the trust anchor is the
   * native side comparing the claimed host against the session's own URL
   * (WalletBridge.dispatch, RoomVaultBridge.validatedHost), not the secrecy of
   * this entry point. The one thing the page gains here is that it can also
   * forge the `__roomToIso` envelope directly instead of going through these
   * objects -- which reaches exactly the same native method with exactly the
   * same validation, so there is nothing behind it to protect.
   */
  function post(channel, envelope) {
    window.postMessage(
      { __roomToIso: 1, channel: channel, payload: JSON.stringify(envelope) },
      "*"
    );
  }

  function arg(value) {
    return value === undefined || value === null ? null : String(value);
  }

  if (!window.RoomWallet) {
    window.RoomWallet = {
      request: function (payload) {
        post("RoomWallet", { method: "request", payload: arg(payload) });
        return "";
      }
    };
  }

  if (!window.RoomVault) {
    window.RoomVault = {
      requestCredentials: function (host, href) {
        post("RoomVault", {
          method: "requestCredentials",
          host: arg(host),
          href: arg(href)
        });
      },
      reportCredential: function (host, username, password) {
        post("RoomVault", {
          method: "reportCredential",
          host: arg(host),
          username: arg(username),
          password: arg(password)
        });
      }
    };
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
      // A JSON string -- see the PRIMITIVE-ONLY note at the top. The object
      // form this used to receive arrived wrapped, so all three properties
      // below read as undefined and nothing was evaluated.
      var scripts = null;
      try {
        scripts = JSON.parse(data.__roomScripts);
      } catch (e) {
        scripts = null;
      }
      if (scripts) {
        // Order matters: the device shim first, so anything the later scripts
        // read from the environment is already the claimed one.
        if (scripts.deviceShim) evaluate(scripts.deviceShim);
        if (scripts.vault) evaluate(scripts.vault);
        if (scripts.wallet) evaluate(scripts.wallet);
      }
      return;
    }

    if (data.__roomConsoleStart) {
      consoleArmed = true;
      // Flush what happened before the panel opened, then empty the buffer so
      // the same entry is never delivered twice.
      consoleBuffer.forEach(sendConsole);
      consoleBuffer.length = 0;
      return;
    }

    if (data.__roomConsoleStop) {
      consoleArmed = false;
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

    if (data.__roomConsole === 1) {
      window.postMessage(
        { __roomConsoleFromPage: 1, entry: String(data.entry) },
        "*"
      );
      return;
    }

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
