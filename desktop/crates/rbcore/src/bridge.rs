//! The page-side bridge scripts — the desktop port of the room_bridge
//! catalogue: the agent's PageInjector scripts, the password-manager page
//! half (RoomVault) and the dApp wallet provider (RoomWallet).
//!
//! Every script is injected at document-start on all frames; page → host
//! messages ride `window.__roomBridgeSend(json)` which the frontends bind
//! to their native message channel (WebView2 WebMessage / WebKit script
//! message handlers).

/// The host-channel shim, prepended to every injected script.
pub const BRIDGE_BOOT: &str = r#"(function () {
  'use strict';
  if (window.__roomBridge) return;
  var queue = [];
  var send = null;
  window.__roomBridge = {
    setSender: function (fn) { send = fn; flush(); },
    send: function (msg) {
      if (send) { try { send(JSON.stringify(msg)); } catch (e) {} }
      else queue.push(msg);
    }
  };
  function flush() {
    var q = queue; queue = [];
    for (var i = 0; i < q.length; i++) window.__roomBridge.send(q[i]);
  }
})();"#;

// ------------------------------------------------------------------
// PageInjector (agent page-driving)

/// Assigns `[ref]` numbers to visible interactive elements
/// (`data-agent-ref`), capped at 120.
pub const SNAPSHOT_JS: &str = r#"(function () {
  'use strict';
  var selectors = 'a[href], button, input, select, textarea, [role="button"], [role="link"], [role="tab"], [role="checkbox"], [role="menuitem"], [onclick], [contenteditable="true"]';
  var nodes = Array.prototype.slice.call(document.querySelectorAll(selectors));
  var n = 0;
  var out = [];
  for (var i = 0; i < nodes.length && n < 120; i++) {
    var el = nodes[i];
    var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : {width: 0, height: 0};
    var style = window.getComputedStyle ? window.getComputedStyle(el) : null;
    var visible = rect.width > 0 && rect.height > 0 && (!style || style.visibility !== 'hidden') && (style ? style.display !== 'none' : true);
    if (!visible) continue;
    n++;
    el.setAttribute('data-agent-ref', String(n));
    var label = (el.innerText || el.value || el.placeholder || el.getAttribute('aria-label') || el.tagName).trim().slice(0, 80);
    out.push('[' + n + '] ' + el.tagName.toLowerCase() + (el.type ? '(' + el.type + ')' : '') + ' ' + label);
  }
  return out.join('\n');
})();"#;

/// Reads the page: URL, title, visible text (capped, middle-omitted) and
/// the interactive element list.
pub const READ_PAGE_JS: &str = r#"(function () {
  'use strict';
  var text = (document.body ? document.body.innerText : '').trim();
  var MAX = 6000;
  var t = text;
  if (text.length > MAX) {
    var head = text.slice(0, MAX * 0.6);
    var tail = text.slice(text.length - MAX * 0.35);
    t = head + '\n…(middle omitted)…\n' + tail;
  }
  var snapshot = (function () {
    var nodes = Array.prototype.slice.call(document.querySelectorAll('[data-agent-ref]'));
    var out = [];
    for (var i = 0; i < nodes.length && out.length < 120; i++) {
      var el = nodes[i];
      var label = (el.innerText || el.value || el.placeholder || el.getAttribute('aria-label') || el.tagName).trim().slice(0, 80);
      out.push('[' + el.getAttribute('data-agent-ref') + '] ' + el.tagName.toLowerCase() + ' ' + label);
    }
    return out.join('\n');
  })();
  return 'URL: ' + location.href + '\nTitle: ' + document.title + '\n\nElements:\n' + snapshot + '\n\nText:\n' + t;
})();"#;

/// Clicks the element with the given `[ref]`.
pub fn click_js(ref_id: i64) -> String {
    format!(
        r#"(function () {{
  'use strict';
  var el = document.querySelector('[data-agent-ref="{r}"]');
  if (!el) return 'no element with ref {r}';
  el.scrollIntoView({{block: 'center'}});
  el.click();
  return 'clicked';
}})();"#,
        r = ref_id
    )
}

/// Types text into the element with the given `[ref]` using the native
/// value setter + input/change events (the same contract as Android's
/// fillJs so SPA frameworks notice).
pub fn fill_js(ref_id: i64, text: &str) -> String {
    let mut escaped = String::new();
    for c in text.chars() {
        match c {
            '\\' => escaped.push_str("\\\\"),
            '"' => escaped.push_str("\\\""),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '\t' => escaped.push_str("\\t"),
            c if (c as u32) < 0x20 => escaped.push_str(&format!("\\u{:04x}", c as u32)),
            c => escaped.push(c),
        }
    }
    format!(
        r#"(function () {{
  'use strict';
  var el = document.querySelector('[data-agent-ref="{r}"]');
  if (!el) return 'no element with ref {r}';
  el.scrollIntoView({{block: 'center'}});
  el.focus();
  var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
  setter.call(el, {text});
  el.dispatchEvent(new Event('input', {{bubbles: true}}));
  el.dispatchEvent(new Event('change', {{bubbles: true}}));
  return 'filled';
}})();"#,
        r = ref_id,
        text = format!("\"{escaped}\"")
    )
}

/// Presses Enter on the element (or the focused element).
pub fn enter_js(ref_id: Option<i64>) -> String {
    match ref_id {
        Some(r) => format!(
            r#"(function () {{ var el = document.querySelector('[data-agent-ref="{r}"]'); if (!el) return 'no element'; el.focus(); el.dispatchEvent(new KeyboardEvent('keydown', {{key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true}})); el.dispatchEvent(new KeyboardEvent('keyup', {{key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true}})); if (el.form) {{ try {{ el.form.requestSubmit(el); return 'submitted'; }} catch (e) {{}} }} return 'pressed'; }})();"#
        ),
        None => r#"(function () { var el = document.activeElement; if (!el) return 'nothing focused'; el.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true})); el.dispatchEvent(new KeyboardEvent('keyup', {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true})); return 'pressed'; })();"#.into(),
    }
}

/// Selects an option by value or visible text.
pub fn select_option_js(ref_id: i64, value: &str) -> String {
    format!(
        r#"(function () {{
  'use strict';
  var el = document.querySelector('[data-agent-ref="{r}"]');
  if (!el) return 'no element with ref {r}';
  if (el.tagName !== 'SELECT') return 'not a select';
  var want = {val};
  for (var i = 0; i < el.options.length; i++) {{
    var o = el.options[i];
    if (o.value === want || o.text.trim() === want) {{
      el.value = o.value;
      el.dispatchEvent(new Event('input', {{bubbles: true}}));
      el.dispatchEvent(new Event('change', {{bubbles: true}}));
      return 'selected: ' + o.text.trim();
    }}
  }}
  return 'option not found';
}})();"#,
        r = ref_id,
        val = serde_json::to_string(value).unwrap_or_else(|_| "\"\"".into())
    )
}

/// Scrolls the page (amount = % of viewport, default 80).
pub fn scroll_js(direction: &str, amount: i64) -> String {
    let down = direction != "up";
    let amt = amount.clamp(1, 100);
    format!(
        r#"(function () {{ var h = window.innerHeight * ({amt}/100); window.scrollBy({{top: {dy}, left: 0, behavior: 'instant'}}); return 'scrolled ' + ({dy} > 0 ? 'down' : 'up') + ' by ' + Math.round(h) + 'px'; }})();"#,
        amt = amt,
        dy = if down { "h" } else { "-h" }
    )
}

/// Key chord dispatch on the active element.
pub fn press_keys_js(keys: &str) -> String {
    let key_json = serde_json::to_string(keys).unwrap_or_else(|_| "\"\"".into());
    format!(
        r#"(function () {{
  'use strict';
  var chord = {key};
  var parts = chord.split('+').map(function (s) {{ return s.trim(); }});
  var key = parts[parts.length - 1];
  var mk = function (type) {{
    var init = {{key: key, bubbles: true, cancelable: true}};
    var lower = key.toLowerCase();
    if (lower === 'enter') {{ init.keyCode = 13; init.which = 13; init.code = 'Enter'; }}
    else if (lower === 'escape') {{ init.keyCode = 27; init.which = 27; init.code = 'Escape'; }}
    else if (lower === 'tab') {{ init.keyCode = 9; init.which = 9; init.code = 'Tab'; }}
    var ev = new KeyboardEvent(type, init);
    if (parts.length > 1 && parts[0].toLowerCase() === 'control') {{ try {{ Object.defineProperty(ev, 'ctrlKey', {{get: function(){{return true;}}}}); }} catch (e) {{}} }}
    return ev;
  }};
  var el = document.activeElement || document.body;
  var target = (el && el.dispatchEvent) ? el : document;
  target.dispatchEvent(mk('keydown'));
  target.dispatchEvent(mk('keyup'));
  return 'pressed ' + chord;
}})();"#,
        key = key_json
    )
}

/// Wait probe: does the page contain the text?
pub fn wait_probe_js(text: &str) -> String {
    let t = serde_json::to_string(text).unwrap_or_else(|_| "\"\"".into());
    format!(
        r#"(function () {{ var body = document.body ? document.body.innerText : ''; return body.indexOf({t}) !== -1 ? 'found' : 'not-found'; }})();"#,
        t = t
    )
}

/// Page readiness digest (SHA-256 is computed host-side over this text).
pub const DIGEST_JS: &str = r#"(function () {
  return (document.body ? document.body.innerText.length : 0) + ':' + document.querySelectorAll('*').length + ':' + location.href;
})();"#;

/// Social heuristics: like/repost visible items, reply or post text.
pub const AUTO_LIKE_JS: &str = r#"(function () {
  'use strict';
  var buttons = Array.prototype.slice.call(document.querySelectorAll('button, [role="button"], a'));
  var liked = 0;
  var hints = ['like', 'suka', '❤', '👍', 'heart', 'favorite'];
  for (var i = 0; i < buttons.length && liked < 20; i++) {
    var b = buttons[i];
    var label = ((b.getAttribute('aria-label') || b.innerText || '') + '').toLowerCase();
    var pressed = b.getAttribute('aria-pressed') === 'true' || b.getAttribute('aria-pressed') === 'mixed';
    if (pressed) continue;
    if (hints.some(function (h) { return label.indexOf(h) !== -1; })) {
      try { b.click(); liked++; } catch (e) {}
    }
  }
  return 'liked ' + liked;
})();"#;

pub const AUTO_REPOST_JS: &str = r#"(function () {
  'use strict';
  var buttons = Array.prototype.slice.call(document.querySelectorAll('button, [role="button"], a'));
  var n = 0;
  var hints = ['repost', 'retweet', 'bagikan ulang', 'boost', 'share'];
  for (var i = 0; i < buttons.length && n < 15; i++) {
    var b = buttons[i];
    var label = ((b.getAttribute('aria-label') || b.innerText || '') + '').toLowerCase();
    if (hints.some(function (h) { return label.indexOf(h) !== -1; })) {
      try { b.click(); n++; } catch (e) {}
    }
  }
  return 'reposted ' + n;
})();"#;

pub fn auto_reply_js(text: &str) -> String {
    let t = serde_json::to_string(text).unwrap_or_else(|_| "\"\"".into());
    format!(
        r#"(function () {{
  'use strict';
  var box = document.querySelector('textarea:not([readonly]), [contenteditable="true"]');
  if (!box) return 'no composer found';
  box.focus();
  if (box.tagName === 'TEXTAREA') {{
    var proto = HTMLTextAreaElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(box, {t});
    box.dispatchEvent(new Event('input', {{bubbles: true}}));
  }} else {{
    document.execCommand('insertText', false, {t});
  }}
  var btns = Array.prototype.slice.call(document.querySelectorAll('button, [role="button"]'));
  for (var i = 0; i < btns.length; i++) {{
    var l = ((btns[i].getAttribute('aria-label') || btns[i].innerText || '') + '').toLowerCase();
    if (l === 'reply' || l === 'post' || l === 'balas' || l === 'kirim' || l === 'send') {{ try {{ btns[i].click(); return 'replied'; }} catch (e) {{}} }}
  }}
  return 'typed reply; submit button not found';
}})();"#,
        t = t
    )
}

pub fn auto_post_js(text: &str) -> String {
    auto_reply_js(text)
}

// ------------------------------------------------------------------
// RoomVault — the password-manager page half.

/// Detects login forms, reports captured credentials and requests stored
/// ones. The host side answers through `window.__roomVaultFill(...)`.
pub const ROOM_VAULT_SCRIPT: &str = r#"(function () {
  'use strict';
  var REQUEST_COOLDOWN = 800;
  var REPORT_COOLDOWN = 30000;
  var lastRequest = 0;
  var lastReportKey = '';
  var lastReportAt = 0;

  function host() { return (window.__roomBridge && window.__roomBridge.send) ? window.__roomBridge : null; }

  function usernameCandidate(form) {
    var scope = form || document;
    var inputs = scope.querySelectorAll('input[type="text"], input[type="email"], input[type="tel"], input:not([type])');
    var best = null, bestScore = -1;
    for (var i = 0; i < inputs.length; i++) {
      var el = inputs[i];
      if (!el.offsetParent) continue;
      var score = 0;
      var hints = (el.name + ' ' + (el.id || '') + ' ' + (el.autocomplete || '') + ' ' + (el.placeholder || '')).toLowerCase();
      if (/user|email|login|account|handphone|phone/.test(hints)) score += 10;
      if (el.type === 'email') score += 5;
      if (form && el.form === form) score += 3;
      if (best === null || score > bestScore) { best = el; bestScore = score; }
    }
    return best;
  }

  function findPasswordFields() {
    var out = [];
    var fields = document.querySelectorAll('input[type="password"]');
    for (var i = 0; i < fields.length; i++) if (fields[i].offsetParent) out.push(fields[i]);
    return out;
  }

  function requestCredentials() {
    var pw = findPasswordFields();
    if (pw.length === 0) return;
    var now = Date.now();
    if (now - lastRequest < REQUEST_COOLDOWN) return;
    lastRequest = now;
    var user = usernameCandidate(pw[0].form);
    var h = host();
    if (h) h.send({kind: 'vault.request', host: location.hostname, href: location.href, hasUsername: !!user, username: user ? user.value : ''});
  }

  function reportCredential() {
    var pw = findPasswordFields();
    if (pw.length === 0) return;
    var user = usernameCandidate(pw[0].form);
    var password = pw[0].value;
    if (!password) return;
    var username = user ? user.value : '';
    var key = location.hostname + '|' + username;
    var now = Date.now();
    if (key === lastReportKey && now - lastReportAt < REPORT_COOLDOWN) return;
    lastReportKey = key; lastReportAt = now;
    var h = host();
    if (h) h.send({kind: 'vault.report', host: location.hostname, href: location.href, username: username, password: password});
  }

  function fill(username, password) {
    var pw = findPasswordFields();
    if (pw.length === 0) return 'no password field';
    var user = usernameCandidate(pw[0].form);
    if (user) {
      var proto = HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(user, username);
      user.dispatchEvent(new Event('input', {bubbles: true}));
      user.dispatchEvent(new Event('change', {bubbles: true}));
    }
    for (var i = 0; i < pw.length; i++) {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(pw[i], password);
      pw[i].dispatchEvent(new Event('input', {bubbles: true}));
      pw[i].dispatchEvent(new Event('change', {bubbles: true}));
    }
    return 'filled';
  }

  // SPA formless logins: watch fetch/XHR shortly after typing.
  var windowForFill = 0;
  document.addEventListener('input', function (e) {
    if (e.target && e.target.type === 'password') windowForFill = Date.now() + 15000;
  }, true);
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function () {
      if (Date.now() < windowForFill) setTimeout(reportCredential, 1200);
      return origFetch.apply(this, arguments);
    };
  }
  var origOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function () {
    if (Date.now() < windowForFill) setTimeout(reportCredential, 1200);
    return origOpen.apply(this, arguments);
  };

  // Focus on a username/password field offers credentials.
  document.addEventListener('focusin', function (e) {
    var t = e.target;
    if (t && (t.type === 'password' || /user|email|login/i.test((t.name || '') + ' ' + (t.id || '')))) requestCredentials();
  }, true);

  // Submit reports credentials.
  document.addEventListener('submit', function () { setTimeout(reportCredential, 400); }, true);

  // The host fills through this entry point.
  window.__roomVaultFill = function (username, password) { return fill(username, password); };
  window.__roomVaultRequest = function () { requestCredentials(); };
})();"#;

// ------------------------------------------------------------------
// RoomWallet — the dApp provider page half.

/// Exposes `window.RoomWallet` (EIP-1193-style) and forwards requests to
/// the host through the bridge channel.
pub const ROOM_WALLET_SCRIPT: &str = r#"(function () {
  'use strict';
  if (window.RoomWallet) return;
  var listeners = {chainChanged: [], accountsChanged: []};
  var state = {chain: 'EVM:1', accounts: []};

  function send(msg) {
    var h = window.__roomBridge;
    if (h) h.send(msg);
  }

  function emit(name, value) {
    var arr = listeners[name] || [];
    for (var i = 0; i < arr.length; i++) {
      try { arr[i](value); } catch (e) {}
    }
  }

  window.RoomWallet = {
    isRoomWallet: true,
    request: function (args) {
      var method = args && args.method;
      var params = args && args.params;
      // Local answers first — the same three BRIDGE_LOCAL_METHODS.
      if (method === 'eth_accounts' || method === 'eth_requestAccounts') {
        if (state.accounts.length === 0) {
          // The host approves and answers asynchronously.
          return new Promise(function (resolve, reject) {
            var id = 'w' + Math.random().toString(36).slice(2);
            window.__roomWalletResolve = window.__roomWalletResolve || {};
            window.__roomWalletResolve[id] = {resolve: resolve, reject: reject};
            send({id: id, chain: state.chain, kind: 'request', method: method, params: params || []});
          });
        }
        return Promise.resolve(state.accounts.slice());
      }
      if (method === 'eth_chainId') {
        var idNum = parseInt(state.chain.split(':')[1], 10);
        return Promise.resolve('0x' + idNum.toString(16));
      }
      if (method === 'disconnect') {
        state.accounts = [];
        emit('accountsChanged', []);
        return Promise.resolve(null);
      }
      return new Promise(function (resolve, reject) {
        var id = 'w' + Math.random().toString(36).slice(2);
        window.__roomWalletResolve = window.__roomWalletResolve || {};
        window.__roomWalletResolve[id] = {resolve: resolve, reject: reject};
        send({id: id, chain: state.chain, kind: 'request', method: method, params: params || []});
      });
    },
    on: function (name, cb) {
      if (listeners[name]) listeners[name].push(cb);
    },
    removeListener: function (name, cb) {
      var arr = listeners[name] || [];
      var i = arr.indexOf(cb);
      if (i !== -1) arr.splice(i, 1);
    }
  };

  // Host → page events.
  window.__roomWalletEvent = function (name, value) {
    if (name === 'chainChanged') state.chain = value.chain || state.chain;
    if (name === 'accountsChanged') state.accounts = value.accounts || [];
    emit(name, value);
  };
  window.__roomWalletResult = function (id, ok, value) {
    var pending = window.__roomWalletResolve || {};
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    if (ok) p.resolve(value); else p.reject(value instanceof Error ? value : new Error(String(value)));
  };
})();"#;

// ------------------------------------------------------------------
// Console capture (RoomConsole) + devtools probes

pub const ROOM_CONSOLE_SCRIPT: &str = r#"(function () {
  'use strict';
  if (window.__roomConsole) return;
  var buf = [];
  function line(level, args) {
    var parts = [];
    for (var i = 0; i < args.length; i++) {
      var a = args[i];
      try { parts.push(typeof a === 'string' ? a : JSON.stringify(a)); } catch (e) { parts.push(String(a)); }
    }
    var entry = {level: level, text: parts.join(' ').slice(0, 2000), ts: Date.now()};
    buf.push(entry);
    if (buf.length > 500) buf.shift();
    var h = window.__roomBridge;
    if (h) h.send({kind: 'console', level: level, text: entry.text});
  }
  ['log', 'info', 'warn', 'error', 'debug'].forEach(function (level) {
    var orig = console[level];
    console[level] = function () { line(level, arguments); if (orig) try { orig.apply(console, arguments); } catch (e) {} };
  });
  window.addEventListener('error', function (e) { line('error', [String(e.message)]); });
  window.__roomConsole = {
    read: function () { return JSON.stringify(buf); },
    clear: function () { buf = []; }
  };
})();"#;

/// The network probe the DevTools network panel uses (performance entries;
/// headers are NEVER captured — they are redacted by policy anyway).
pub const NETWORK_PROBE_JS: &str = r#"(function () {
  'use strict';
  var entries = performance.getEntriesByType('resource');
  var out = [];
  for (var i = 0; i < entries.length && out.length < 200; i++) {
    var e = entries[i];
    out.push({name: e.name, type: e.initiatorType, ms: Math.round(e.duration), size: e.transferSize});
  }
  return JSON.stringify(out);
})();"#;

/// Application-panel probe: storage key counts only (values are never read).
pub const APPLICATION_PROBE_JS: &str = r#"(function () {
  'use strict';
  var ls = 0, ss = 0;
  try { ls = localStorage.length; } catch (e) {}
  try { ss = sessionStorage.length; } catch (e) {}
  var cookies = 0;
  try { cookies = document.cookie ? document.cookie.split(';').length : 0; } catch (e) {}
  var idb = typeof indexedDB !== 'undefined';
  var sw = 'serviceWorker' in navigator;
  return JSON.stringify({
    localStorage: ls, sessionStorage: ss, cookieCount: cookies,
    indexedDB: idb, serviceWorker: sw,
    manifest: !!document.querySelector('link[rel="manifest"]')
  });
})();"#;

/// Security probe (page-observable facts only).
pub const SECURITY_PROBE_JS: &str = r#"(function () {
  return JSON.stringify({
    protocol: location.protocol,
    host: location.hostname,
    secure: location.protocol === 'https:',
    mixedContent: (location.protocol === 'https:' && Array.prototype.some.call(document.querySelectorAll('img[src^="http:"], script[src^="http:"]'), function () { return true; }))
  });
})();"#;

/// The elements tree probe (Elements panel root).
pub const ELEMENTS_TREE_JS: &str = r#"(function () {
  'use strict';
  function node(el, depth, maxDepth) {
    if (depth > maxDepth) return {tag: el.tagName, children: el.children.length, truncated: true};
    var attrs = {};
    for (var i = 0; i < el.attributes.length && i < 8; i++) attrs[el.attributes[i].name] = el.attributes[i].value.slice(0, 100);
    var kids = [];
    for (var j = 0; j < el.children.length && j < 12; j++) kids.push(node(el.children[j], depth + 1, maxDepth));
    return {tag: el.tagName, attrs: attrs, children: kids};
  }
  return JSON.stringify(node(document.documentElement, 0, 4));
})();"#;

/// Reader mode: strips the page to its article text.
pub const READER_ON_JS: &str = r#"(function () {
  'use strict';
  if (document.getElementById('room-reader-style')) return 'already on';
  var candidates = document.querySelectorAll('article, main, [role="main"], .content, #content');
  var best = null, bestLen = 0;
  for (var i = 0; i < candidates.length; i++) {
    var len = (candidates[i].innerText || '').length;
    if (len > bestLen) { best = candidates[i]; bestLen = len; }
  }
  var source = best || document.body;
  var html = '<h1>' + (document.title || '') + '</h1>' + source.innerHTML;
  var style = document.createElement('style');
  style.id = 'room-reader-style';
  style.textContent = 'body{max-width:42em;margin:2em auto;padding:0 1em;font:16px/1.7 Georgia,serif;background:#faf8f4;color:#222;}img,video{max-width:100%;height:auto;}nav,aside,footer,header,.ad,[class*="ad-"]{display:none!important;}';
  document.head.appendChild(style);
  document.body.innerHTML = html;
  document.body.removeAttribute('class');
  return 'reader on';
})();"#;

pub const READER_OFF_JS: &str = r#"(function () {
  var s = document.getElementById('room-reader-style');
  if (s) s.remove();
  return 'reader style removed; reload the page to restore content';
})();"#;

/// Find-in-page counter (the Windows edition's helper).
pub fn find_count_js(query: &str) -> String {
    let q = serde_json::to_string(query).unwrap_or_else(|_| "\"\"".into());
    format!(
        r#"(function () {{ var b = document.body ? document.body.innerText : ''; var L = b.toLowerCase(), Q = {q}.toLowerCase(), n = 0, i = 0; while ((i = L.indexOf(Q, i)) !== -1) {{ n++; i += Q.length; }} return String(n); }})();"#,
        q = q
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generated_scripts_carry_the_args() {
        assert!(click_js(7).contains("data-agent-ref=\"7\""));
        assert!(fill_js(3, "hello \"world\"").contains("\\\"world\\\""));
        assert!(select_option_js(2, "ID").contains("data-agent-ref=\"2\""));
        assert!(scroll_js("down", 80).contains("80"));
        assert!(scroll_js("up", 80).contains("-h"));
        assert!(press_keys_js("Control+a").contains("Control"));
        assert!(wait_probe_js("Welcome").contains("Welcome"));
        assert!(auto_reply_js("hi").contains("hi"));
        assert!(find_count_js("a").contains("indexOf"));
    }

    #[test]
    fn bridge_scripts_expose_the_documented_surfaces() {
        assert!(BRIDGE_BOOT.contains("setSender"));
        assert!(ROOM_VAULT_SCRIPT.contains("__roomVaultFill"));
        assert!(ROOM_VAULT_SCRIPT.contains("vault.report"));
        assert!(ROOM_VAULT_SCRIPT.contains("vault.request"));
        assert!(ROOM_WALLET_SCRIPT.contains("window.RoomWallet"));
        assert!(ROOM_WALLET_SCRIPT.contains("__roomWalletResult"));
        assert!(ROOM_CONSOLE_SCRIPT.contains("__roomConsole"));
        assert!(SNAPSHOT_JS.contains("data-agent-ref"));
        assert!(READ_PAGE_JS.contains("middle omitted"));
        assert!(READER_ON_JS.contains("room-reader-style"));
    }
}
