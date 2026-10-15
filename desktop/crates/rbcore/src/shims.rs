//! Device-identity and screen-claim shims — verbatim port of
//! `rb_device_shim_js` / `rb_screen_shim_js`. The exact same JavaScript the
//! C edition injected at document start, so pages read the same fingerprint
//! from both editions of the browser.

use crate::devices::Device;

pub const SCREEN_PX_MIN: i32 = 240;
pub const SCREEN_PX_MAX: i32 = 4320;

/// JSON-quote a JS string literal (port of `js_quote`).
fn js_quote(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

fn chrome_major(chrome: &str) -> String {
    // "124.0.6367.91" → "124"
    chrome
        .split('.')
        .next()
        .unwrap_or("")
        .chars()
        .filter(|c| c.is_ascii_digit())
        .collect()
}

/// The device half: Navigator UA/platform/deviceMemory/hardwareConcurrency,
/// userAgentData with getHighEntropyValues, and the WebGL GPU patch.
pub fn device_shim_js(device: &Device) -> String {
    format!(
        r#"(function () {{
  'use strict';
  try {{
    var UA = {ua};
    var CHROME = {chrome};
    var CHROME_MAJOR = {major};
    var PLATFORM = {platform};
    var UA_PLATFORM = {ua_platform};
    var PLATFORM_VERSION = {platform_version};
    var ARCH = {arch};
    var MEMORY = {memory};
    var CORES = {cores};
    var GPU_VENDOR = {gpu_vendor};
    var GPU_RENDERER = {gpu_renderer};

    function define(target, prop, value) {{
      try {{
        Object.defineProperty(target, prop, {{
          get: function () {{ return value; }},
          configurable: true,
          enumerable: true
        }});
      }} catch (e) {{}}
    }}

    define(Navigator.prototype, 'userAgent', UA);
    define(Navigator.prototype, 'platform', PLATFORM);
    define(Navigator.prototype, 'deviceMemory', MEMORY);
    define(Navigator.prototype, 'hardwareConcurrency', CORES);

    // Client hints. These are the ones that name the machine, so a
    // profile that shims the UA but not these is not presenting one.
    function brands(full) {{
      var v = full ? CHROME : CHROME_MAJOR;
      return [
        {{ brand: 'Chromium', version: v }},
        {{ brand: 'Google Chrome', version: v }},
        {{ brand: 'Not?A_Brand', version: '24' }}
      ];
    }}
    var uaData = {{
      brands: brands(false),
      mobile: false,
      platform: UA_PLATFORM,
      getHighEntropyValues: function (hints) {{
        var wanted = hints || [];
        var out = {{}};
        for (var i = 0; i < wanted.length; i++) {{
          switch (wanted[i]) {{
            case 'architecture': out.architecture = ARCH; break;
            case 'bitness': out.bitness = '64'; break;
            case 'formFactor': out.formFactor = 'Desktop'; break;
            case 'model': out.model = ''; break;
            case 'platform': out.platform = UA_PLATFORM; break;
            case 'platformVersion':
              out.platformVersion = PLATFORM_VERSION; break;
            case 'uaFullVersion': out.uaFullVersion = CHROME; break;
            case 'fullVersionList':
              out.fullVersionList = brands(true); break;
            case 'wow64': out.wow64 = false; break;
            default: break;
          }}
        }}
        return Promise.resolve(out);
      }},
      toJSON: function () {{
        return {{ brands: brands(false), mobile: false,
                 platform: UA_PLATFORM }};
      }}
    }};
    try {{
      Object.defineProperty(Navigator.prototype, 'userAgentData', {{
        get: function () {{ return uaData; }},
        configurable: true,
        enumerable: true
      }});
    }} catch (e) {{}}

    // WebGL reports the GPU. An Adreno string on a machine that
    // ships an RTX is exactly the contradiction this exists to avoid.
    function patchGL(ctx) {{
      if (!ctx || !ctx.prototype || !ctx.prototype.getParameter) return;
      var proto = ctx.prototype;
      var original = proto.getParameter;
      var patched = function (pname) {{
        if (pname === 37445) return GPU_VENDOR;
        if (pname === 37446) return GPU_RENDERER;
        return original.call(this, pname);
      }};
      try {{
        patched.toString = function () {{ return original.toString(); }};
      }} catch (e) {{}}
      try {{
        Object.defineProperty(proto, 'getParameter', {{
          value: patched,
          configurable: true,
          writable: true
        }});
      }} catch (e) {{}}
    }}
    if (typeof WebGLRenderingContext !== 'undefined') {{
      patchGL(WebGLRenderingContext);
    }}
    if (typeof WebGL2RenderingContext !== 'undefined') {{
      patchGL(WebGL2RenderingContext);
    }}
  }} catch (e) {{
    // A page must still load even if the platform refuses one of these.
  }}
}})();
"#,
        ua = js_quote(device.ua),
        chrome = js_quote(device.chrome),
        major = js_quote(&chrome_major(device.chrome)),
        platform = js_quote(device.platform),
        ua_platform = js_quote(device.ua_platform),
        platform_version = js_quote(device.platform_version),
        arch = js_quote(device.arch),
        memory = device.memory,
        cores = device.cores,
        gpu_vendor = js_quote(device.gpu_vendor),
        gpu_renderer = js_quote(device.gpu_renderer),
    )
}

/// The screen half: claims width/height/avail*, orientation type (angle
/// stays 0). NEVER touches innerWidth/innerHeight/devicePixelRatio.
pub fn screen_shim_js(width_px: i32, height_px: i32) -> Option<String> {
    if !(SCREEN_PX_MIN..=SCREEN_PX_MAX).contains(&width_px)
        || !(SCREEN_PX_MIN..=SCREEN_PX_MAX).contains(&height_px)
    {
        return None;
    }
    let orientation = if width_px > height_px { "landscape-primary" } else { "portrait-primary" };
    Some(format!(
        r#"(function () {{
  'use strict';
  try {{
    var SCREEN_W = {w};
    var SCREEN_H = {h};
    var TYPE = '{orientation}';

    function define(target, prop, value) {{
      try {{
        Object.defineProperty(target, prop, {{
          get: function () {{ return value; }},
          configurable: true,
          enumerable: true
        }});
      }} catch (e) {{}}
    }}

    // Measured while the real values are still readable, then carried
    // over: the claim keeps this display's own chrome inset.
    var INSET_W = screen.width - screen.availWidth;
    var INSET_H = screen.height - screen.availHeight;
    if (!(INSET_W > 0)) INSET_W = 0;
    if (!(INSET_H > 0)) INSET_H = 0;
    if (SCREEN_W - INSET_W < 1) INSET_W = 0;
    if (SCREEN_H - INSET_H < 1) INSET_H = 0;

    define(Screen.prototype, 'width', SCREEN_W);
    define(Screen.prototype, 'height', SCREEN_H);
    define(Screen.prototype, 'availWidth', SCREEN_W - INSET_W);
    define(Screen.prototype, 'availHeight', SCREEN_H - INSET_H);

    // The layout viewport and the pixel ratio are NOT touched, on
    // purpose: the viewport is the page's real size in this window and
    // the ratio is what the compositor renders at. A claimed screen
    // that differs from the display's disagrees with the viewport, and
    // the unit test pins those names out of this script so the trade
    // cannot be undone by a later edit without the test saying so.

    // `type` follows the shape that was claimed; `angle` does not,
    // because it is the device's rotation and a desktop is never
    // rotated. A landscape claim answering "portrait-primary" is the
    // contradiction this exists to remove; a landscape claim answering
    // 90 degrees would be a new one.
    try {{
      if (typeof ScreenOrientation !== 'undefined' &&
          ScreenOrientation.prototype) {{
        define(ScreenOrientation.prototype, 'type', TYPE);
        define(ScreenOrientation.prototype, 'angle', 0);
      }}
    }} catch (e) {{}}
  }} catch (e) {{
    // A page must still load even if the platform refuses one of these.
  }}
}})();
"#,
        w = width_px,
        h = height_px,
        orientation = orientation
    ))
}

/// `rb_shim_js`: device half first, screen half second; None when neither
/// applies (the default installs nothing).
pub fn combined_shim_js(device: Option<&Device>, screen: Option<(i32, i32)>) -> Option<String> {
    let mut out = String::new();
    if let Some(d) = device {
        out.push_str(&device_shim_js(d));
    }
    if let Some((w, h)) = screen {
        if let Some(js) = screen_shim_js(w, h) {
            out.push_str(&js);
        }
    }
    if out.is_empty() {
        None
    } else {
        Some(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn device_shim_carries_the_fingerprint() {
        let d = &crate::devices::DEVICES[0];
        let js = device_shim_js(d);
        assert!(js.contains(&format!("var UA = \"{}\";", d.ua)));
        assert!(js.contains(&format!("var GPU_VENDOR = \"{}\";", d.gpu_vendor)));
        assert!(js.contains("define(Navigator.prototype, 'userAgent', UA);"));
        assert!(js.contains("getHighEntropyValues"));
        assert!(js.contains("patchGL(WebGL2RenderingContext);"));
        // toString preserved on the patched getParameter
        assert!(js.contains("patched.toString = function () { return original.toString(); };"));
    }

    #[test]
    fn screen_shim_bounds_and_pins() {
        assert!(screen_shim_js(0, 0).is_none());
        assert!(screen_shim_js(240, 4320).is_some());
        let js = screen_shim_js(1920, 1080).unwrap();
        assert!(js.contains("var SCREEN_W = 1920;"));
        assert!(js.contains("'landscape-primary'"));
        assert!(js.contains("define(Screen.prototype, 'width', SCREEN_W);"));
        // the forbidden names are absent
        assert!(!js.contains("innerWidth"));
        assert!(!js.contains("innerHeight"));
        assert!(!js.contains("devicePixelRatio"));
        let portrait = screen_shim_js(1080, 1920).unwrap();
        assert!(portrait.contains("'portrait-primary'"));
        assert!(portrait.contains("define(ScreenOrientation.prototype, 'angle', 0);"));
    }

    #[test]
    fn combined_returns_none_when_nothing_applies() {
        assert!(combined_shim_js(None, None).is_none());
        assert!(combined_shim_js(None, Some((0, 0))).is_none());
        let d = &crate::devices::DEVICES[0];
        let js = combined_shim_js(Some(d), Some((1920, 1080))).unwrap();
        assert!(js.contains("var UA = "));
        assert!(js.contains("var SCREEN_W = 1920;"));
    }

    #[test]
    fn js_quote_escapes() {
        assert_eq!(js_quote("a\"b\\c\n"), "\"a\\\"b\\\\c\\n\"");
    }
}
