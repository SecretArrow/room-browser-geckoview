package com.roombrowser.browser.engine

import com.roombrowser.domain.model.ClaimedScreen
import com.roombrowser.domain.model.Device
import com.roombrowser.domain.model.FingerprintProfile
import com.roombrowser.domain.model.WebRtcPolicy

/**
 * The JavaScript a profile runs before any page script, so the properties a
 * page reads agree with what the profile claims to be.
 *
 * Four independent parts, and each is installed only when the profile has
 * actually asked for it:
 *
 *  - the **identity** shim, for a profile presenting a device: the UA, the
 *    client hints, `deviceMemory`, `hardwareConcurrency` and the WebGL
 *    vendor/renderer strings. These are the things a handset determines
 *    and that the real hardware therefore cannot contradict.
 *
 *  - the **derived** shim, for every profile with a fingerprint seed: the
 *    surfaces a device alone cannot keep apart, derived (never drawn per
 *    read). No seed means the old platform line and nothing more.
 *
 *  - the **screen** shim, for a profile whose screen size is set by hand:
 *    `screen.width/height/availWidth/availHeight` and `screen.orientation`.
 *
 *  - the **WebRTC** shim, for a profile whose [WebRtcPolicy] is not DEFAULT:
 *    either the peer connection is taken away altogether, or the host
 *    candidates that carry a literal IP address are kept out of what the page
 *    can read. Both are described at the scripts themselves.
 *
 * Screen geometry is left alone unless the profile asks for it. The default is
 * the phone's own screen, because the page really is laid out here. A profile
 * that sets a size by hand is making a choice the settings row states plainly,
 * and the cost is in what the layout viewport cannot do: it stays the display's
 * own, because `innerWidth` and `innerHeight` are the width and height the page
 * is really laid out at and moving them means re-laying the page out, which is
 * the breakage this file exists to avoid. `devicePixelRatio` follows a claimed
 * screen only for a profile with a seed, and is the one geometric value that
 * can move with the claim; a profile with no claim keeps the compositor's real
 * ratio. Either way a claimed screen that differs from the phone's is a
 * disagreement with the viewport a script can find.
 *
 * It is offered anyway because the alternative is worse. Without it, a profile
 * presenting a Galaxy S24 Ultra reports a screen that handset never had — the
 * same contradiction, except that nobody chose it and the settings screen said
 * nothing about it. This way the mismatch is explicit, bounded to the screen
 * family, and stated where the choice is made. See SECURITY.md.
 *
 * Honest limits: a page that inspects `Function.prototype.toString` on the
 * patched accessors, compares dozens of unrelated signals, or fingerprints
 * the GPU by timing a draw call can still tell. This raises the cost of the
 * cheap checks; it is not and does not claim to be undetectable. And a
 * document-start script runs in documents, not in workers: code that opens a
 * Web Worker — or any other realm this shim is not injected into — gets the
 * engine's own WebRTC, so the policy below is not enforced there.
 */
object DeviceShim {

    /**
     * The document-start script for a profile. Blank when the profile claims
     * neither a device nor a screen size, derives no fingerprint surfaces and
     * its WebRTC policy is [WebRtcPolicy.DEFAULT] — nothing absent is
     * installed, and nothing asked for is changed.
     *
     * The policy is a parameter with a default rather than something read
     * from a profile the way [Device] is, because the shim has never held
     * profile state: callers pass what the profile asked for. The default is
     * DEFAULT, which adds nothing, so a caller that passes a device and
     * nothing else gets exactly the script it got before this parameter
     * existed. A real profile does not start there — the profile settings
     * default the policy to RESTRICT_LOCAL_IP — so the engine passes the
     * profile's own value at every call site and never leans on this default.
     *
     * [fingerprint] defaults to [FingerprintProfile.legacy]; the engine passes
     * the profile's own [FingerprintProfile.from] result.
     */
    fun scriptFor(
        device: Device?,
        screen: ClaimedScreen? = null,
        webRtc: WebRtcPolicy = WebRtcPolicy.DEFAULT,
        fingerprint: FingerprintProfile = FingerprintProfile.legacy()
    ): String = buildString {
        if (device != null) append(identityScript(device))
        // A seed installs the derived surfaces even with no device.
        if (device != null || fingerprint.isSeeded) {
            append(fingerprintScript(fingerprint, screen))
        }
        if (screen != null) append(screenScript(screen))
        append(webRtcScript(webRtc))
    }

    /** The identity shim: what the profile presents itself as. */
    private fun identityScript(device: Device): String {
        val chromeMajor = device.chromeVersion.substringBefore('.')
        val mobile = device.formFactor != "tablet"
        return IDENTITY
            .replace("__UA__", jsString(device.userAgent))
            .replace("__CHROME__", jsString(device.chromeVersion))
            .replace("__CHROME_MAJOR__", jsString(chromeMajor))
            .replace("__ANDROID__", jsString(device.androidVersion))
            .replace("__MODEL__", jsString(device.code))
            .replace("__MOBILE__", mobile.toString())
            .replace("__FORM__", jsString(if (mobile) "Mobile" else "Tablet"))
            .replace("__MEMORY__", device.deviceMemoryGb.toString())
            .replace("__CORES__", device.hardwareConcurrency.toString())
            .replace("__GPU_VENDOR__", jsString(device.gpuVendor))
            .replace("__GPU_RENDERER__", jsString(device.gpuRenderer))
    }

    /**
     * `platform` always; the rest only when seeded. `devicePixelRatio` also
     * needs a claimed screen, so the ratio never contradicts a display the
     * profile did not describe.
     */
    private fun fingerprintScript(
        fingerprint: FingerprintProfile,
        screen: ClaimedScreen?
    ): String {
        val defines = StringBuilder()
        defines.append("    define(Navigator.prototype, 'platform', ")
            .append(jsString(fingerprint.platform))
            .append(");\n")
        if (fingerprint.isSeeded) {
            fingerprint.colorDepth?.let {
                defines.append("    define(Screen.prototype, 'colorDepth', $it);\n")
            }
            fingerprint.pixelDepth?.let {
                defines.append("    define(Screen.prototype, 'pixelDepth', $it);\n")
            }
            fingerprint.maxTouchPoints?.let {
                defines.append("    define(Navigator.prototype, 'maxTouchPoints', $it);\n")
            }
            if (screen != null) {
                fingerprint.devicePixelRatio?.let {
                    defines.append("    define(window, 'devicePixelRatio', $it);\n")
                }
            }
            defines.append(pluginDefines(fingerprint))
        }
        return FINGERPRINT.replace("__DEFINES__", defines.toString())
    }

    /**
     * `navigator.plugins` / `mimeTypes` as array-likes; only the count varies.
     */
    private fun pluginDefines(fingerprint: FingerprintProfile): String {
        val plugins = fingerprint.plugins.joinToString(", ") { plugin ->
            "{ name: ${jsString(plugin.name)}, " +
                "description: ${jsString(plugin.description)}, " +
                "filename: ${jsString(plugin.filename)}, " +
                "length: ${plugin.mimeTypes.size} }"
        }
        val mimeTypes = fingerprint.mimeTypes.joinToString(", ") { mime ->
            "{ type: ${jsString(mime.type)}, " +
                "suffixes: ${jsString(mime.suffixes)}, " +
                "description: ${jsString(mime.description)} }"
        }
        return "    define(Navigator.prototype, 'plugins', arrayLike([$plugins]));\n" +
            "    define(Navigator.prototype, 'mimeTypes', arrayLike([$mimeTypes]));\n"
    }

    /** The screen shim: what a page is told the display is. */
    private fun screenScript(screen: ClaimedScreen): String = SCREEN
        .replace("__SCREEN_W__", screen.widthPx.toString())
        .replace("__SCREEN_H__", screen.heightPx.toString())
        .replace(
            "__SCREEN_TYPE__",
            jsString(if (screen.isLandscape) "landscape-primary" else "portrait-primary")
        )
        .replace("__SCREEN_ANGLE__", if (screen.isLandscape) "90" else "0")

    /**
     * The WebRTC shim for a profile whose policy is not DEFAULT, and nothing
     * at all for one whose policy is.
     *
     * Neither script is a substitute for a TURN server, and neither touches
     * the camera/microphone permission prompts the engine already shows:
     *
     *  - [WebRtcPolicy.DISABLED] removes `RTCPeerConnection` and the
     *    `webkitRTCPeerConnection` alias from the window, so
     *    `'RTCPeerConnection' in window` is false and code that feature-detects
     *    correctly concludes WebRTC is unavailable. That is what the engine
     *    itself looks like with WebRTC turned off, and removing the property
     *    (rather than defining it as undefined) is what that state actually
     *    is: a property that is present with an undefined value fails an
     *    own-property check and then throws at the first `new`, which turns a
     *    clean feature test into a crash inside library setup code.
     *
     *  - [WebRtcPolicy.RESTRICT_LOCAL_IP] leaves peer connections working and
     *    keeps the host candidates that carry a literal IP address out of what
     *    the page can read, so the far end does not learn the address this
     *    device holds on its own network. An mDNS host candidate — the
     *    `<uuid>.local` form a modern engine hands out instead of a literal —
     *    is left alone: there is no address in it, and it is the candidate two
     *    peers on the same network connect over when no STUN or TURN server is
     *    reachable, so withholding it would buy nothing and cost the call.
     *    Candidates are filtered on their way to the page's `onicecandidate`
     *    handler and to `addEventListener('icecandidate')` listeners, and
     *    stripped from the local SDP the page reads or hands back.
     *    Server-reflexive and relay candidates are untouched, and no
     *    relay-only transport policy is forced — with no TURN server
     *    configured, relay-only would make every call fail, which is not what
     *    "restrict local IP exposure" means.
     */
    private fun webRtcScript(policy: WebRtcPolicy): String = when (policy) {
        WebRtcPolicy.DEFAULT -> ""
        WebRtcPolicy.DISABLED -> WEBRTC_DISABLED
        WebRtcPolicy.RESTRICT_LOCAL_IP -> WEBRTC_RESTRICT
    }

    /** A JS string literal, so a model code can never break out of the script. */
    private fun jsString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")
        return "'$escaped'"
    }

    // No template literals and no "$" anywhere: the script is spliced into a
    // Kotlin string, and a stray dollar would be read as interpolation.
    private val IDENTITY = """
(function () {
  'use strict';
  try {
    var UA = __UA__;
    var CHROME = __CHROME__;
    var CHROME_MAJOR = __CHROME_MAJOR__;
    var ANDROID = __ANDROID__;
    var MODEL = __MODEL__;
    var MOBILE = __MOBILE__;
    var FORM = __FORM__;
    var MEMORY = __MEMORY__;
    var CORES = __CORES__;
    var GPU_VENDOR = __GPU_VENDOR__;
    var GPU_RENDERER = __GPU_RENDERER__;

    function define(target, prop, value) {
      try {
        Object.defineProperty(target, prop, {
          get: function () { return value; },
          configurable: true,
          enumerable: true
        });
      } catch (e) {}
    }

    define(Navigator.prototype, 'userAgent', UA);
    // `platform` is deliberately not here: it belongs to the derived shim
    // below, because a profile with no device still has a platform to report.
    define(Navigator.prototype, 'deviceMemory', MEMORY);
    define(Navigator.prototype, 'hardwareConcurrency', CORES);

    // Client hints. These are the ones that name the handset outright, so a
    // profile that shims the UA but not these is not presenting a device.
    function brands(full) {
      var chromium = full ? CHROME : CHROME_MAJOR;
      return [
        { brand: 'Chromium', version: chromium },
        { brand: 'Google Chrome', version: chromium },
        { brand: 'Not?A_Brand', version: '24' }
      ];
    }
    var uaData = {
      brands: brands(false),
      mobile: MOBILE,
      platform: 'Android',
      getHighEntropyValues: function (hints) {
        var wanted = hints || [];
        var out = {};
        for (var i = 0; i < wanted.length; i++) {
          switch (wanted[i]) {
            case 'architecture': out.architecture = ''; break;
            case 'bitness': out.bitness = ''; break;
            case 'formFactor': out.formFactor = FORM; break;
            case 'model': out.model = MODEL; break;
            case 'platform': out.platform = 'Android'; break;
            case 'platformVersion': out.platformVersion = ANDROID + '.0.0'; break;
            case 'uaFullVersion': out.uaFullVersion = CHROME; break;
            case 'fullVersionList': out.fullVersionList = brands(true); break;
            case 'wow64': out.wow64 = false; break;
            default: break;
          }
        }
        return Promise.resolve(out);
      },
      toJSON: function () {
        return { brands: brands(false), mobile: MOBILE, platform: 'Android' };
      }
    };
    try {
      Object.defineProperty(Navigator.prototype, 'userAgentData', {
        get: function () { return uaData; },
        configurable: true,
        enumerable: true
      });
    } catch (e) {}

    // WebGL reports the SoC. An Adreno string on a handset that ships an
    // Exynos is exactly the kind of contradiction this exists to avoid.
    function patchGL(ctx) {
      if (!ctx || !ctx.prototype || !ctx.prototype.getParameter) return;
      var proto = ctx.prototype;
      var original = proto.getParameter;
      var patched = function (pname) {
        if (pname === 37445) return GPU_VENDOR;
        if (pname === 37446) return GPU_RENDERER;
        return original.call(this, pname);
      };
      // Keep the patched accessor reporting as native to a casual toString().
      try {
        patched.toString = function () { return original.toString(); };
      } catch (e) {}
      try {
        Object.defineProperty(proto, 'getParameter', {
          value: patched,
          configurable: true,
          writable: true
        });
      } catch (e) {}
    }
    if (typeof WebGLRenderingContext !== 'undefined') patchGL(WebGLRenderingContext);
    if (typeof WebGL2RenderingContext !== 'undefined') patchGL(WebGL2RenderingContext);
  } catch (e) {
    // A page must still load even if the platform refuses one of these.
  }
})();
"""

    // Installed when the profile presents a device or carries a seed; for an
    // unseeded one __DEFINES__ is the single platform line it always got.
    private val FINGERPRINT = """
(function () {
  'use strict';
  try {
    function define(target, prop, value) {
      try {
        Object.defineProperty(target, prop, {
          get: function () { return value; },
          configurable: true,
          enumerable: true
        });
      } catch (e) {}
    }

    // Array-like shape plugins/mimeTypes expose: length, entries, item, namedItem.
    function arrayLike(items) {
      var out = [];
      for (var i = 0; i < items.length; i++) out[i] = items[i];
      out.item = function (i) { return this[i] === undefined ? null : this[i]; };
      out.namedItem = function (name) {
        for (var i = 0; i < this.length; i++) {
          if (this[i].name === name || this[i].type === name) return this[i];
        }
        return null;
      };
      return out;
    }

__DEFINES__
  } catch (e) {
    // A page must still load even if the platform refuses one of these.
  }
})();
"""

    // Installed only for a profile that set a screen size by hand, so the
    // numbers below are always the claimed ones and never a "real" default.
    private val SCREEN = """
(function () {
  'use strict';
  try {
    var SCREEN_W = __SCREEN_W__;
    var SCREEN_H = __SCREEN_H__;
    var TYPE = __SCREEN_TYPE__;
    var ANGLE = __SCREEN_ANGLE__;

    function define(target, prop, value) {
      try {
        Object.defineProperty(target, prop, {
          get: function () { return value; },
          configurable: true,
          enumerable: true
        });
      } catch (e) {}
    }

    // What the page is told the screen is. Chrome on Android reports the whole
    // display as the available rectangle too — there is no persistent chrome to
    // subtract — so both pairs carry the same numbers rather than inventing a
    // difference a real handset does not have.
    define(Screen.prototype, 'width', SCREEN_W);
    define(Screen.prototype, 'height', SCREEN_H);
    define(Screen.prototype, 'availWidth', SCREEN_W);
    define(Screen.prototype, 'availHeight', SCREEN_H);

    // The layout viewport and the pixel ratio are NOT touched here, on purpose.
    // The viewport is the page's real width and height on this display and the
    // ratio is what the compositor actually renders at; overriding either would
    // re-lay the page out at a size the screen does not have, which is the
    // breakage this file exists to avoid. The consequence is real and is stated
    // in settings and in SECURITY.md: a claimed screen that differs from the
    // phone's is a disagreement with the viewport, and a script can find it.
    // The unit test pins those names out of this script so the trade cannot be
    // undone by a later edit without the test saying so.

    // Orientation follows the shape that was claimed, not the hinge. A profile
    // claiming a landscape screen must not also answer "portrait-primary" —
    // that pairing is the contradiction this shim exists to remove.
    try {
      if (typeof ScreenOrientation !== 'undefined' && ScreenOrientation.prototype) {
        define(ScreenOrientation.prototype, 'type', TYPE);
        define(ScreenOrientation.prototype, 'angle', ANGLE);
      }
    } catch (e) {}
    try {
      // The pre-standard spelling, still present in Chromium. Guarded because
      // it is on its way out and a missing property is not an error: 0 is
      // portrait, 90 is landscape, which is what the legacy value always was.
      if ('orientation' in window) {
        Object.defineProperty(window, 'orientation', {
          get: function () { return ANGLE; },
          configurable: true,
          enumerable: true
        });
      }
    } catch (e) {}
  } catch (e) {
    // A page must still load even if the platform refuses one of these.
  }
})();
"""

    // Installed only for a profile whose policy is Disabled. An engine built
    // without WebRTC does not have the constructor at all, and that — not a
    // property whose value is undefined — is the state this reproduces.
    private val WEBRTC_DISABLED = """
(function () {
  'use strict';
  try {
    function remove(target, prop) {
      try {
        if (delete target[prop]) return;
      } catch (e) {}
      try {
        Object.defineProperty(target, prop, {
          value: undefined,
          configurable: true,
          writable: true,
          enumerable: false
        });
      } catch (e) {}
    }
    remove(window, 'RTCPeerConnection');
    // The pre-standard alias, present in some engines only: removed where it
    // exists, never created where it never did.
    if ('webkitRTCPeerConnection' in window) remove(window, 'webkitRTCPeerConnection');
  } catch (e) {
    // A page must still load even if the platform refuses this.
  }
})();
"""

    // Installed only for a profile whose policy is Restrict local IP exposure.
    //
    // The rule, in one place in the script below: a "typ host" candidate whose
    // address is a literal IP is an address this device holds on its own
    // network, and the page does not get to see it. A "typ host" candidate
    // whose address is an mDNS name is not that — there is no address in
    // ".local" for the far end to learn, and it is the candidate two peers on
    // the same network pair over when no STUN or TURN server is reachable, so
    // withholding it would buy nothing and cost the call. Server-reflexive and
    // relay candidates are addresses the far end already reaches this device
    // at, so they stay, and the transport policy the page asked for is left
    // exactly as it was — forcing relay-only with no TURN server configured
    // would make every call fail, which is not what this policy says.
    //
    // Where a host candidate can escape, and what is done about it:
    //   1. the icecandidate event, whether the page used the onicecandidate
    //      property or addEventListener — the event is dropped, so no
    //      scrubbed copy has to be forged;
    //   2. the local SDP the page reads (localDescription and its current and
    //      pending forms) or submits (setLocalDescription), and the SDP
    //      createOffer/createAnswer resolve with.
    // Both paths ask the same predicate, so a candidate removed from one
    // cannot escape through the other and the judgement cannot drift.
    //
    // Honest limits, stated rather than implied:
    //  - getStats() is not wrapped, so a page reading ICE candidate statistics
    //    still sees whatever the engine reports there — a local candidate pair
    //    statistic carries the address this filter removes from the event and
    //    the SDP.
    //  - a Web Worker is a realm this document-start script does not reach; a
    //    page that opens one gets the engine's own RTCPeerConnection.
    //  - an engine that spells a candidate in a shape this parser does not
    //    recognise — no "candidate:" text, or an address in an unexpected
    //    position — has that candidate kept rather than dropped: nothing is
    //    parsed, so nothing is claimed about it. The Chrome and Firefox
    //    spellings are the ones covered.
    private val WEBRTC_RESTRICT = """
(function () {
  'use strict';
  try {
    var Native = window.RTCPeerConnection || window.webkitRTCPeerConnection;
    if (typeof Native !== 'function' || !Native.prototype) return;
    // Reflect.construct is how the wrapper builds a connection whose
    // prototype is still the engine's, which a page that subclasses the
    // constructor needs. On an engine without it the shim installs nothing at
    // all rather than leaving a constructor that throws on every call.
    if (typeof Reflect !== 'object' || typeof Reflect.construct !== 'function') return;
    // Which names the engine actually published, so the shim never creates an
    // alias that was not there: a page feature-detecting the legacy spelling
    // must keep getting the answer the engine gave.
    var hadStandard = !!window.RTCPeerConnection;
    var hadLegacy = !!window.webkitRTCPeerConnection;
    var PROTO = Native.prototype;
    var HOST_MARKER = 'typ host';
    var CANDIDATE_MARKER = 'candidate:';

    function endsMarker(text, at) {
      var next = text.charAt(at + HOST_MARKER.length);
      return next === '' || next === ' ';
    }

    // The address of a candidate sits fifth after "candidate:" — foundation,
    // component, transport, priority, address — and both shapes carry that
    // same text: the candidate object's own .candidate string, and the
    // "a=candidate:..." line in an SDP. That is why one reader serves both.
    // Empty tokens are skipped so a doubled space shifts nothing.
    function addressToken(text) {
      var at = text.indexOf(CANDIDATE_MARKER);
      if (at === -1) return '';
      var raw = text.slice(at + CANDIDATE_MARKER.length).split(' ');
      var tokens = [];
      for (var i = 0; i < raw.length; i++) {
        if (raw[i] !== '') tokens.push(raw[i]);
      }
      return tokens.length > 4 ? tokens[4] : '';
    }

    // Whether an address token is a literal IP, which is the thing this policy
    // withholds. An mDNS name ("<uuid>.local") is not one: it carries no
    // address at all, and it is the candidate that connects two peers on the
    // same network when nothing else can. Every IPv6 spelling has a colon in
    // it — the compressed "2001:db8::1", the full form, and the bracketed
    // "[...]" form with a zone on the end — so one colon test covers them all,
    // and a dotted quad is four numeric labels in range.
    function isLiteralAddress(token) {
      if (!token) return false;
      var zone = token.indexOf('%');
      if (zone !== -1) token = token.slice(0, zone);
      if (token.charAt(0) === '[') return true;
      if (token.indexOf(':') !== -1) return true;
      var labels = token.split('.');
      if (labels.length !== 4) return false;
      for (var i = 0; i < 4; i++) {
        var label = labels[i];
        if (label.length < 1 || label.length > 3) return false;
        for (var j = 0; j < label.length; j++) {
          var code = label.charCodeAt(j);
          if (code < 48 || code > 57) return false;
        }
        if (Number(label) > 255) return false;
      }
      return true;
    }

    // The one judgement both paths make, and the only place either asks
    // whether a candidate is withheld: a "typ host" marker whose address is a
    // literal IP. The candidate object's own .type is deliberately not
    // consulted — it reads "host" for an mDNS candidate too, and that is
    // exactly the candidate that has to survive.
    function withholds(text) {
      if (typeof text !== 'string' || text === '') return false;
      var at = text.indexOf(HOST_MARKER);
      if (at === -1 || !endsMarker(text, at)) return false;
      return isLiteralAddress(addressToken(text));
    }

    // The text an event carries, or "" when it carries none: an
    // end-of-candidates event has a null candidate, and the page still has to
    // be told that gathering finished or a caller may wait forever.
    function candidateText(candidate) {
      if (!candidate) return '';
      try {
        return typeof candidate.candidate === 'string' ? candidate.candidate : '';
      } catch (e) {
        return '';
      }
    }

    // The event-level spelling of that same judgement, so both listeners ask
    // one expression rather than each building its own.
    function withholdsEvent(event) {
      return withholds(candidateText(event && event.candidate));
    }

    // A candidate line looks like:
    //   a=candidate:1 1 UDP 2122252543 192.168.1.5 54321 typ host
    // Only the "a=candidate:" lines are considered, so nothing else in the
    // SDP that happens to contain those words can be removed by accident.
    function scrubSdp(sdp) {
      if (typeof sdp !== 'string' || sdp.indexOf(HOST_MARKER) === -1) return sdp;
      var eol = sdp.indexOf('\r\n') === -1 ? '\n' : '\r\n';
      var lines = sdp.split(eol);
      var kept = [];
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        if (line.indexOf('a=candidate:') === 0 && withholds(line)) continue;
        kept.push(line);
      }
      return kept.join(eol);
    }

    // The page gets a description of the same type and prototype where the
    // engine still offers RTCSessionDescription, and a plain object shaped
    // like one where it does not.
    function copyDescription(description, sdp) {
      if (!description || description.sdp === sdp) return description;
      if (typeof RTCSessionDescription === 'function') {
        try { return new RTCSessionDescription({ type: description.type, sdp: sdp }); } catch (e) {}
      }
      return { type: description.type, sdp: sdp };
    }

    function scrubDescription(description) {
      if (!description || typeof description.sdp !== 'string') return description;
      return copyDescription(description, scrubSdp(description.sdp));
    }

    function defineValue(target, prop, fn) {
      try {
        Object.defineProperty(target, prop, {
          value: fn, configurable: true, writable: true, enumerable: false
        });
        return true;
      } catch (e) {
        return false;
      }
    }

    // (2) The SDP path.
    var setLocal = PROTO.setLocalDescription;
    if (typeof setLocal === 'function') {
      defineValue(PROTO, 'setLocalDescription', function (description) {
        var args = Array.prototype.slice.call(arguments);
        if (args.length > 0) args[0] = scrubDescription(args[0]);
        return setLocal.apply(this, args);
      });
    }

    function wrapOffer(name) {
      var native = PROTO[name];
      if (typeof native !== 'function') return;
      defineValue(PROTO, name, function () {
        var args = Array.prototype.slice.call(arguments);
        var self = this;
        if (typeof args[0] === 'function') {
          // The legacy success/failure callback form, which must keep
          // working: the page's callback is wrapped, not dropped.
          var success = args[0];
          var failure = (typeof args[1] === 'function') ? args[1] : null;
          return native.call(this, function (description) {
            return success.call(self, scrubDescription(description));
          }, failure, args[2]);
        }
        var result = native.apply(this, args);
        if (result && typeof result.then === 'function') {
          return result.then(function (description) {
            return scrubDescription(description);
          });
        }
        return result;
      });
    }
    wrapOffer('createOffer');
    wrapOffer('createAnswer');

    var DESCRIPTIONS = ['localDescription', 'currentLocalDescription', 'pendingLocalDescription'];
    for (var d = 0; d < DESCRIPTIONS.length; d++) {
      (function (prop) {
        var existing = null;
        try { existing = Object.getOwnPropertyDescriptor(PROTO, prop); } catch (e) {}
        if (!existing || typeof existing.get !== 'function') return;
        try {
          Object.defineProperty(PROTO, prop, {
            get: function () { return scrubDescription(existing.get.call(this)); },
            configurable: true,
            enumerable: existing.enumerable
          });
        } catch (e) {}
      })(DESCRIPTIONS[d]);
    }

    // (1) The icecandidate event. Wrapping the constructor gives every
    // connection its own filter and keeps the page's handler in a closure
    // rather than on the object, so nothing new is there to enumerate.
    var eventHandlerSet = null;
    try {
      var onIce = Object.getOwnPropertyDescriptor(PROTO, 'onicecandidate');
      if (onIce && typeof onIce.set === 'function') eventHandlerSet = onIce.set;
    } catch (e) {}

    function wrapConnection(connection) {
      var pageHandler = null;

      function delivered(event) {
        if (withholdsEvent(event)) return undefined;
        var handler = pageHandler;
        if (!handler) return undefined;
        if (typeof handler === 'function') return handler.call(connection, event);
        if (typeof handler.handleEvent === 'function') return handler.handleEvent(event);
        return undefined;
      }

      if (eventHandlerSet) {
        try {
          Object.defineProperty(connection, 'onicecandidate', {
            configurable: true,
            enumerable: true,
            get: function () { return pageHandler; },
            set: function (handler) {
              var usable = (typeof handler === 'function' || (handler !== null && typeof handler === 'object'));
              pageHandler = usable ? handler : null;
              eventHandlerSet.call(connection, pageHandler ? delivered : null);
            }
          });
        } catch (e) {}
      }

      // The same filter for the addEventListener spelling. The wrapper is
      // remembered per listener so removeEventListener with the page's own
      // function still detaches it.
      var registered = [];
      function wrapperFor(listener) {
        for (var i = 0; i < registered.length; i++) {
          if (registered[i].listener === listener) return registered[i].wrapper;
        }
        return null;
      }
      // The DOM accepts a function or an object with handleEvent, and both
      // have to be wrapped — and later found again — by the same rule.
      function isListener(value) {
        if (typeof value === 'function') return true;
        return typeof value === 'object' && value !== null && typeof value.handleEvent === 'function';
      }
      function invokes(listener, event) {
        if (typeof listener === 'function') return listener.call(connection, event);
        if (listener && typeof listener.handleEvent === 'function') return listener.handleEvent(event);
        return undefined;
      }
      defineValue(connection, 'addEventListener', function (type, listener, options) {
        if (type !== 'icecandidate' || !isListener(listener)) {
          return EventTarget.prototype.addEventListener.call(connection, type, listener, options);
        }
        var wrapper = wrapperFor(listener);
        if (!wrapper) {
          wrapper = function (event) {
            if (withholdsEvent(event)) return undefined;
            return invokes(listener, event);
          };
          registered.push({ listener: listener, wrapper: wrapper });
        }
        return EventTarget.prototype.addEventListener.call(connection, type, wrapper, options);
      });
      defineValue(connection, 'removeEventListener', function (type, listener, options) {
        var wrapper = (type === 'icecandidate' && isListener(listener)) ? wrapperFor(listener) : null;
        return EventTarget.prototype.removeEventListener.call(connection, type, wrapper || listener, options);
      });
    }

    function ShimConnection() {
      if (!new.target) {
        throw new TypeError("Failed to construct 'RTCPeerConnection': Please use the 'new' operator.");
      }
      var connection = Reflect.construct(Native, Array.prototype.slice.call(arguments), new.target);
      wrapConnection(connection);
      return connection;
    }
    // Same prototype object, so instanceof and a page that subclasses this
    // both keep working; the identity of the function is the only change.
    ShimConnection.prototype = PROTO;
    try { Object.defineProperty(ShimConnection, 'name', { value: 'RTCPeerConnection', configurable: true }); } catch (e) {}
    try { ShimConnection.toString = function () { return Native.toString(); }; } catch (e) {}
    // A connection's own constructor property points back at the shim, the
    // way it points at the engine's constructor without one.
    try {
      Object.defineProperty(PROTO, 'constructor', {
        value: ShimConnection, configurable: true, writable: true, enumerable: false
      });
    } catch (e) {}

    function install(prop) {
      var enumerable = false;
      try {
        var current = Object.getOwnPropertyDescriptor(window, prop);
        if (current) enumerable = current.enumerable;
      } catch (e) {}
      try {
        Object.defineProperty(window, prop, {
          value: ShimConnection, configurable: true, writable: true, enumerable: enumerable
        });
      } catch (e) {}
    }
    if (hadStandard) install('RTCPeerConnection');
    if (hadLegacy) install('webkitRTCPeerConnection');
  } catch (e) {
    // A page must still load, and a call must still connect, even if the
    // platform refuses one of these.
  }
})();
"""
}
