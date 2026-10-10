/*
 * Room Browser bridge -- SUB-RESOURCE BLOCKER half.
 *
 * The only place in this app where a sub-resource request can be CANCELLED
 * under GeckoView. Android WebView reports every sub-resource to
 * `WebViewClient.shouldInterceptRequest`, so the WebView edition decides in the
 * app; GeckoView has no per-request callback at all, and the only hook that can
 * cancel one is `webRequest.onBeforeRequest` with `["blocking"]`, which is
 * JavaScript running in the extension process.
 *
 * WHY THIS FILE EXISTS RATHER THAN A CALL INTO THE APP. A blocking listener
 * must answer SYNCHRONOUSLY -- it returns the decision, it cannot await one --
 * so there is no way to ask the app's FilterEngine what to do. The rules
 * therefore travel here (the app pushes them over the native port below) and
 * the matching is mirrored. [decide] below is a step-for-step transcription of
 * `FilterEngine.decide` in `android/core/domain/.../FilterEngine.kt`; the two
 * must be changed together. The DATA they match against is NOT duplicated --
 * every host arrives from the app, which reads the one bundled list -- so the
 * two editions cannot disagree about what is blocked, only (at worst) about a
 * detail of the control flow written out twice here.
 *
 * THIS FILE IS AN ASSET, NOT CODE THE COMPILER SEES. Nothing in the build
 * checks it and no unit test can execute it: the JVM unit-test classpath has no
 * JS engine. So it is written to be read, and `BlockerScriptsTest` pins the
 * strings that cross between it and the Kotlin that answers it.
 */

(function () {
  "use strict";

  /** Must match BLOCKER_NATIVE_APP in GeckoEngineHost.kt. */
  var NATIVE_APP = "roomblock";

  /**
   * The filter the app last pushed, or null when none has arrived yet.
   *
   * NULL MEANS ALLOW, NOT BLOCK. The rules cannot be compiled into this file
   * (that would be a second copy of the blocklist) and they cannot be fetched
   * synchronously, so there is a window at process start -- and after any
   * failure to open the port -- in which requests are not filtered. Failing
   * OPEN is the deliberate choice: the alternative is a blocker that, before
   * its first push, cancels every request on the device, which is not a
   * privacy feature but an outage. The lost window is the same behaviour this
   * edition had before the blocker existed, so it is a floor, not a regression.
   */
  var filter = null;

  /**
   * Whether the app is listening for network events.
   *
   * The webRequest observers below run for EVERY request on the device, so the
   * gate is what keeps a page nobody is inspecting from posting anything: when
   * it is false each observer returns after one boolean test.
   */
  var netArmed = false;

  var port = null;
  try {
    port = browser.runtime.connectNative(NATIVE_APP);
  } catch (e) {
    // Without the port there are no rules and nothing is ever blocked. There
    // is no fallback channel: reporting this needs native messaging, which is
    // exactly what just failed. The app side detects the same condition by
    // never seeing a port, and logs it there.
    console.error("[roomblock] connectNative failed: " + e);
  }

  if (port) {
    port.onMessage.addListener(function (message) {
      if (!message || typeof message !== "object") return;
      if (message.type === "netStart") {
        netArmed = true;
        return;
      }
      if (message.type === "netStop") {
        netArmed = false;
        return;
      }
      if (message.type !== "filter") return;
      filter = {
        ads: new Set(message.adHosts || []),
        trackers: new Set(message.trackerHosts || []),
        malicious: new Set(message.maliciousHosts || []),
        keywords: message.keywordRules || [],
        blockAds: message.blockAds === true,
        blockTrackers: message.blockTrackers === true,
        blockCrossSite: message.blockCrossSite === true,
        blockMalicious: message.blockMalicious === true,
        shieldsDisabled: new Set(message.shieldsDisabledHosts || [])
      };
    });

    port.onDisconnect.addListener(function () {
      // The last filter stays in force rather than being cleared: a dead port
      // cannot be told about a settings change, and forgetting the rules would
      // silently stop blocking the moment the app-side delegate went away --
      // the wrong direction for a failure to fall.
      port = null;
    });
  }

  /**
   * Report a block the app should count. A lost report under-counts the
   * privacy dashboard; it never un-blocks anything, because the cancel has
   * already been returned by the time this runs.
   */
  function report(host, category) {
    if (!port) return;
    try {
      port.postMessage({ type: "blocked", host: host, category: category });
    } catch (e) {
      console.error("[roomblock] could not report a block: " + e);
    }
  }

  /**
   * Report one network observation, when anyone is listening.
   *
   * A LOST REPORT IS HARMLESS HERE, unlike a lost block: these are observations
   * for a panel, and the panel can only be open when `netArmed` is true. These
   * observers are non-blocking, so their failure cannot affect a request.
   */
  function reportNet(payload) {
    if (!netArmed || !port) return;
    try {
      port.postMessage(payload);
    } catch (e) {
      console.error("[roomblock] could not report a network event: " + e);
    }
  }

  /*
   * The network feed: response status, response headers, completion and
   * failure. OBSERVERS ONLY -- no "blocking" in the third argument, so none of
   * them can delay or alter a request, and none of them returns a decision.
   * The blocking path is onBeforeRequest below and is deliberately separate.
   */

  browser.webRequest.onHeadersReceived.addListener(
    function (details) {
      reportNet({
        type: "netResponse",
        requestId: details.requestId,
        url: details.url,
        method: details.method || null,
        statusCode: details.statusCode,
        responseHeaders: details.responseHeaders || [],
        documentUrl: details.documentUrl || null,
        timeStamp: details.timeStamp
      });
    },
    { urls: ["<all_urls>"] }
  );

  browser.webRequest.onCompleted.addListener(
    function (details) {
      reportNet({
        type: "netCompleted",
        requestId: details.requestId,
        url: details.url,
        documentUrl: details.documentUrl || null,
        timeStamp: details.timeStamp
      });
    },
    { urls: ["<all_urls>"] }
  );

  browser.webRequest.onErrorOccurred.addListener(
    function (details) {
      reportNet({
        type: "netError",
        requestId: details.requestId,
        url: details.url,
        error: details.error || null,
        documentUrl: details.documentUrl || null,
        timeStamp: details.timeStamp
      });
    },
    { urls: ["<all_urls>"] }
  );

  /** Host of a URL, lowercased, or null when there is none. */
  function hostOf(url) {
    try {
      var host = new URL(url).hostname;
      return host ? host.toLowerCase() : null;
    } catch (e) {
      return null;
    }
  }

  /** Path of a URL for the keyword rules, "/" when it has none. */
  function pathOf(url) {
    try {
      return new URL(url).pathname || "/";
    } catch (e) {
      return "/";
    }
  }

  /**
   * Exact host, or any parent domain of it: "cdn.doubleclick.net" matches an
   * entry for "doubleclick.net", but a shared TLD suffix never does. Mirror of
   * `FilterEngine.matchesSuffix`.
   */
  function matchesSuffix(set, host) {
    if (set.has(host)) return true;
    var idx = host.indexOf(".");
    while (idx !== -1) {
      if (set.has(host.substring(idx + 1))) return true;
      idx = host.indexOf(".", idx + 1);
    }
    return false;
  }

  /**
   * Mirror of `FilterEngine.decide`. Returns the category to report, or null
   * to allow. The order of the checks IS the policy: a malicious host is
   * reported as malicious even when it is also on the ad list, and the first
   * matching keyword rule wins.
   *
   * [flags] carries the four switches already resolved for THIS request --
   * the profile's settings with the site exemption applied -- which is how the
   * Kotlin call site passes them too, so no argument here is re-derived.
   */
  function decide(host, pageHost, path, flags) {
    if (host === "") return null;

    if (flags.malicious && matchesSuffix(filter.malicious, host)) {
      return "MALICIOUS";
    }
    if (flags.ads && matchesSuffix(filter.ads, host)) {
      return "AD";
    }
    if (flags.trackers && matchesSuffix(filter.trackers, host)) {
      var crossSite = pageHost !== null && pageHost !== host;
      if (flags.crossSite || !crossSite) {
        return crossSite ? "CROSS_SITE_TRACKER" : "TRACKER";
      }
      return null;
    }

    if (flags.ads || flags.trackers) {
      var lowered = host + path.toLowerCase();
      for (var i = 0; i < filter.keywords.length; i++) {
        var rule = filter.keywords[i];
        if (lowered.indexOf(rule.pattern) === -1) continue;
        // A rule whose category is switched off does not block -- but it does
        // not stop the scan either, exactly as the Kotlin loop continues.
        var relevant = rule.category === "AD"
          ? flags.ads
          : (rule.category === "TRACKER" || rule.category === "CROSS_SITE_TRACKER")
            ? flags.trackers
            : true;
        if (relevant) return rule.category;
      }
    }
    return null;
  }

  browser.webRequest.onBeforeRequest.addListener(
    function (details) {
      // Reported BEFORE any early return, so a blocked request and an allowed
      // one are both visible. The RETURN VALUE below is untouched -- this call
      // is a side effect and never contributes to the block decision.
      reportNet({
        type: "netRequest",
        requestId: details.requestId,
        url: details.url,
        method: details.method || null,
        requestHeaders: details.requestHeaders || [],
        documentUrl: details.documentUrl || null,
        isForMainFrame: details.type === "main_frame",
        resourceType: details.type || null,
        timeStamp: details.timeStamp
      });

      if (!filter) return {};
      // The main frame is a NAVIGATION and belongs to the app's navigation
      // policy (`onNavigationRequest`: malicious-site refusal, HTTPS upgrade),
      // which this listener cannot express -- it can only cancel. Same
      // early return, same reason, as the WebView edition's
      // `if (isForMainFrame) return false`.
      if (details.type === "main_frame") return {};

      var host = hostOf(details.url);
      if (!host) return {};
      // `trimEnd('.')` on the Kotlin side: a trailing-dot host is the same
      // host to DNS, so it must be the same host to the blocklist.
      host = host.replace(/\.+$/, "");
      if (host === "") return {};

      // The page that triggered the request, for the cross-site decision.
      // `documentUrl` is the document the resource loads into, which is the
      // page host the app resolves for the firing session; `originUrl` is the
      // fallback Gecko offers for a request with no document behind it.
      var pageHost = hostOf(details.documentUrl || details.originUrl || "");

      // Faithful to `WebClients.onResourceRequest`, which looks the site
      // setting up by the REQUEST host. Not a correction of it: the two
      // editions must answer a stored per-site setting the same way, and the
      // lookup key is what decides that.
      var shieldsDisabled = filter.shieldsDisabled.has(host);
      var category = decide(host, pageHost, pathOf(details.url), {
        ads: filter.blockAds && !shieldsDisabled,
        trackers: filter.blockTrackers && !shieldsDisabled,
        crossSite: filter.blockCrossSite && !shieldsDisabled,
        malicious: filter.blockMalicious
      });

      if (category === null) return {};
      report(host, category);
      return { cancel: true };
    },
    // Every scheme the extension can see. The rules are host-based, so a
    // narrower filter would only mean missing the requests that matter.
    { urls: ["<all_urls>"] },
    ["blocking"]
  );
})();
