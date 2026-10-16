#!/usr/bin/env node
'use strict';

// The local compat proof for ZAgents modules: it loads the SAME plugin_host.js
// the runtime ships, feeds each vendored sample module a call shaped exactly
// like the one the app's bridge sends for a `format: "zagents"` row, and
// asserts what the module actually does — storage counts per chat, the
// allowlist, the state/ directory, the emit composition, the staged prompt
// commands, the compose gate, the runtime form. What a module does here is
// what it does on the device; what it cannot do here it cannot do there.
//
// The app side is faked, deterministically: a net layer that answers canned
// responses (or the network-failure status:0 convention, or the REAL transport
// for the one host that is allowed to touch the network), an agent_generate
// channel with canned sentences — including ones that violate the composer
// gate, to prove the retry and the sanitize — and a user_input channel with
// canned values, a cancel and a timeout. The module ZIPs are the ones bundled
// in the APK (assets/node/plugins/zagents-*.zip), read with a small pure-Node
// ZIP reader so the harness needs nothing beyond `node` itself.
//
// One PASS line per fixture; exit non-zero the moment anything failed.

const fs = require('fs');
const http = require('http');
const https = require('https');
const os = require('os');
const path = require('path');
const zlib = require('zlib');

const HERE = __dirname;
const HOST = path.resolve(HERE, '../node/src/main/assets/node/plugin_host.js');
const BUNDLED = path.resolve(HERE, '../node/src/main/assets/node/plugins');

const MODULE_PERMISSIONS = ['storage', 'network', 'files', 'chat', 'agent'];

// The one host allowed to reach the REAL transport: the network-fetcher
// sample hits api.github.com, and the honest proof is that the call is
// graceful both online (a quote) and offline (status 0) — never a hang. The
// delegate cap keeps a blackholed network from stretching that to the
// module's own 60 s request budget, and ZAGENTS_COMPAT_OFFLINE=1 withdraws
// the delegation entirely so the fully-offline run is provable anywhere.
const REAL_HOST = process.env.ZAGENTS_COMPAT_OFFLINE ? null : 'api.github.com';
const DELEGATE_CAP_MS = 8000;

const WORK_ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'room-zagents-compat-'));

// ---- a tiny pure-Node ZIP reader -------------------------------------------
//
// Enough ZIP for a bundled fixture: the central directory walked entry by
// entry, each file's data slice inflated (method 8) or copied (method 0).
// No zip64, no encryption, no external tools — the same guarantee the
// installer's guards give the device, minus the guards this harness is not
// retesting.

function readZip(buffer) {
  let eocd = -1;
  for (let at = buffer.length - 22; at >= 0; at--) {
    if (buffer.readUInt32LE(at) === 0x06054b50) {
      eocd = at;
      break;
    }
  }
  if (eocd < 0) throw new Error('not a ZIP: no end-of-central-directory record');
  const count = buffer.readUInt16LE(eocd + 10);
  const dirAt = buffer.readUInt32LE(eocd + 16);
  const entries = [];
  let cursor = dirAt;
  for (let index = 0; index < count; index++) {
    if (buffer.readUInt32LE(cursor) !== 0x02014b50) {
      throw new Error('corrupt ZIP: bad central directory entry');
    }
    const method = buffer.readUInt16LE(cursor + 10);
    const compressedSize = buffer.readUInt32LE(cursor + 20);
    const nameLength = buffer.readUInt16LE(cursor + 28);
    const extraLength = buffer.readUInt16LE(cursor + 30);
    const commentLength = buffer.readUInt16LE(cursor + 32);
    const localAt = buffer.readUInt32LE(cursor + 42);
    const name = buffer.slice(cursor + 46, cursor + 46 + nameLength).toString('utf8');
    if (buffer.readUInt32LE(localAt) !== 0x04034b50) {
      throw new Error('corrupt ZIP: bad local header for ' + name);
    }
    const localNameLength = buffer.readUInt16LE(localAt + 26);
    const localExtraLength = buffer.readUInt16LE(localAt + 28);
    const dataAt = localAt + 30 + localNameLength + localExtraLength;
    const raw = buffer.slice(dataAt, dataAt + compressedSize);
    const data = method === 0 ? raw : zlib.inflateRawSync(raw);
    entries.push({ name: name, data: data });
    cursor += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

// The samples ship one wrapping folder; the installer rebases it, so the
// harness does too — every entry under the same single root loses it.
function unwrapped(entries) {
  const files = entries.filter((entry) => entry.name.slice(-1) !== '/');
  if (files.length === 0) throw new Error('the ZIP carries no files');
  const root = files[0].name.split('/')[0];
  const shared = files.every((entry) => entry.name.indexOf(root + '/') === 0);
  return shared
    ? files.map((entry) => ({ name: entry.name.slice(root.length + 1), data: entry.data }))
    : files;
}

function extractFixture(zipName) {
  const dir = fs.mkdtempSync(path.join(WORK_ROOT, zipName.replace(/\.zip$/, '-') + '-'));
  const entries = unwrapped(readZip(fs.readFileSync(path.join(BUNDLED, zipName))));
  for (const entry of entries) {
    if (entry.name.indexOf('..') >= 0 || path.isAbsolute(entry.name)) {
      throw new Error('the ZIP carries an unsafe name: ' + entry.name);
    }
    const target = path.join(dir, entry.name);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, entry.data);
  }
  const manifest = JSON.parse(fs.readFileSync(path.join(dir, 'module.json'), 'utf8'));
  return { dir: dir, manifest: manifest };
}

// A synthetic module: source + the wire manifest the harness asserts against.
function writeModule(name, source, permissions, networkAllow) {
  const dir = fs.mkdtempSync(path.join(WORK_ROOT, name + '-'));
  fs.writeFileSync(path.join(dir, 'main.js'), source);
  return {
    dir: dir,
    manifest: {
      name: name,
      version: '1.0.0',
      title: name,
      entry: 'main.js',
      permissions: permissions,
      allowedHosts: networkAllow || [],
    },
  };
}

// ---- the fake app side ------------------------------------------------------

// The net layer: canned responses per host, the status:0 failure convention
// for everything else, and one host delegated to the REAL transport with its
// timeout capped. Every request is recorded — header-shape assertions (the
// user-agent drop among them) read the records, not the wire.
function fakeNet() {
  const records = [];
  const canned = {}; // host -> {status, headers, body}
  const real = { http: http.request, https: https.request };

  function deliver(record, onResponse) {
    const plan = canned[record.host];
    if (plan) {
      setImmediate(function () {
        onResponse(fakeResponse(plan.status, plan.headers || {}, plan.body || ''));
      });
      return;
    }
    // Offline for everyone else: the transport fails, which the module SDK
    // reads as the network-failure convention — status 0, never a rejection.
    setImmediate(function () {
      record.errorListeners.forEach(function (fn) {
        fn(new Error('offline: no route to ' + record.host));
      });
    });
  }

  function delegate(record, onResponse) {
    const transport = record.secure ? real.https : real.http;
    const upstream = transport(record.options, onResponse);
    record.request = {
      setTimeout: function (ms, cb) {
        upstream.setTimeout(Math.min(ms, DELEGATE_CAP_MS), cb);
      },
      on: function (event, fn) {
        upstream.on(event, fn);
      },
      write: function (chunk) {
        upstream.write(chunk);
      },
      end: function () {
        upstream.end();
      },
      destroy: function (err) {
        upstream.destroy(err);
      },
    };
  }

  function patched(secure) {
    return function (options, onResponse) {
      const headers = {};
      const sent = options && options.headers ? options.headers : {};
      for (const name of Object.keys(sent)) headers[name.toLowerCase()] = String(sent[name]);
      const record = {
        host: options.hostname,
        path: options.path,
        method: options.method,
        headers: headers,
        secure: secure,
        options: options,
        errorListeners: [],
      };
      records.push(record);
      if (REAL_HOST && record.host === REAL_HOST && !canned[record.host]) {
        delegate(record, onResponse);
        return record.request;
      }
      return {
        setTimeout: function () {},
        on: function (event, fn) {
          if (event === 'error') record.errorListeners.push(fn);
          return this;
        },
        write: function () {},
        end: function () {
          deliver(record, onResponse);
        },
        destroy: function (err) {
          if (err) {
            setImmediate(function () {
              record.errorListeners.forEach(function (fn) {
                fn(err);
              });
            });
          }
        },
      };
    };
  }

  function fakeResponse(status, headers, body) {
    const chunks = body ? [Buffer.from(body, 'utf8')] : [];
    return {
      statusCode: status,
      headers: headers,
      resume: function () {},
      destroy: function () {},
      on: function (event, fn) {
        if (event === 'data') chunks.forEach(fn);
        else if (event === 'end') setImmediate(fn);
        return this;
      },
    };
  }

  return {
    records: records,
    install: function () {
      http.request = patched(false);
      https.request = patched(true);
    },
    restore: function () {
      http.request = real.http;
      https.request = real.https;
    },
    canned: canned,
    requestsTo: function (host) {
      return records.filter(function (record) {
        return record.host === host;
      });
    },
  };
}

// The fake agent_generate channel: canned sentences in order (the last one
// repeats forever), every shaped request kept for the pipeline assertions.
function fakeAgent(sentences) {
  const calls = [];
  return {
    calls: calls,
    ask: function (request) {
      calls.push(request);
      const text = sentences.length > 1 ? sentences.shift() : sentences[0];
      return Promise.resolve({ text: text });
    },
  };
}

// The fake user_input channel: a script of answers — values, a cancel, a
// timeout, a delayed release — every form kept for the field assertions.
function fakeUserInput(script) {
  const forms = [];
  return {
    forms: forms,
    ask: function (form) {
      forms.push(form);
      const step = script.length > 1 ? script.shift() : script[0];
      return step(form);
    },
  };
}

// ---- the call builder -------------------------------------------------------

// The wire manifest for a module row, built the way the app builds it: the
// ZAgents permission vocabulary verbatim, allowedHosts as the allowlist, the
// verbatim display strings riding beside it.
function wireManifestOf(moduleJson) {
  const name = moduleJson.name;
  const permissions = (moduleJson.permissions || []).filter(function (p) {
    return MODULE_PERMISSIONS.indexOf(p) >= 0;
  });
  return {
    id: 'zagents.' + name,
    name: moduleJson.title || name,
    version: /^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/.test(moduleJson.version || '')
      ? moduleJson.version
      : '0.0.0',
    permissions: permissions,
    networkAllow: moduleJson.allowedHosts || [],
  };
}

let chatCounter = 0;

// One module activation, exactly the call bridge.js assembles for a
// `format: "zagents"` row: the module's own budget, the per-chat data and
// state roots, the staged prompt, the stored input values, the display
// strings, and the two reverse channels.
async function runModule(fixture, opts) {
  const chat = opts.chat || String(++chatCounter);
  const dataRoot = path.join(WORK_ROOT, 'plugin-data', chat, 'zagents_' + fixture.manifest.name.replace(/[^a-z0-9._-]/gi, '_'));
  const moduleJson = fixture.manifest;
  const request = {
    pluginRoot: fixture.dir,
    dataRoot: dataRoot,
    entry: moduleJson.entry || 'main.js',
    manifest: wireManifestOf(moduleJson),
    event: 'activate',
    payload: {},
    config: {},
    timeoutMs: opts.timeoutMs || 30000,
    format: 'zagents',
    prompt: opts.prompt !== undefined ? opts.prompt : null,
    timeoutSeconds:
      typeof moduleJson.timeoutSeconds === 'number' ? moduleJson.timeoutSeconds : null,
    stateRoot: path.join(dataRoot, 'state'),
    title: moduleJson.title || '',
    zagentsVersion: moduleJson.version || '0.0.0',
    zagentsName: moduleJson.name,
    userAgent: opts.userAgent !== undefined ? opts.userAgent : 'RoomCompat/1.0',
    askAgent: opts.askAgent !== undefined ? opts.askAgent : null,
    askUser: opts.askUser !== undefined ? opts.askUser : null,
  };
  const { runPluginCall } = require(HOST);
  return runPluginCall(request);
}

// A plugin-format call (no format field, the Room permission vocabulary) —
// the unchanged path a plugin.json row rides.
async function runPlugin(fixture, permissions) {
  const dataRoot = path.join(WORK_ROOT, 'plugin-data', 'plugin-side', fixture.name);
  const { runPluginCall } = require(HOST);
  return runPluginCall({
    pluginRoot: fixture.dir,
    dataRoot: dataRoot,
    entry: 'main.js',
    manifest: {
      id: 'com.example.' + fixture.name.replace(/[^a-z0-9-]/g, ''),
      name: fixture.name,
      version: '1.0.0',
      permissions: permissions,
      networkAllow: [],
    },
    event: 'activate',
    payload: {},
    config: {},
    timeoutMs: 30000,
    askAgent: null,
  });
}

// ---- the fixtures -----------------------------------------------------------

const fixtures = [];

function fixture(name, fn) {
  fixtures.push({ name: name, fn: fn });
}

function check(ok, label, detail) {
  if (!ok) {
    throw new Error(label + (detail !== undefined ? ' — ' + detail : ''));
  }
}

function contains(text, needle, label) {
  check(typeof text === 'string' && text.indexOf(needle) >= 0, label, JSON.stringify(text));
}

// counter: counts per chat, resets across chats — the per-chat isolation the
// private-window guarantee promises.
fixture('zagents-counter', async function () {
  const module = extractFixture('zagents-counter.zip');
  const first = await runModule(module, { chat: 'counter-a' });
  check(first.ok, 'the first counter run failed', JSON.stringify(first));
  check(first.result.activationsInThisChat === 1, 'count should be 1 on first activation', JSON.stringify(first.result));
  contains(first.output, 'Activations in THIS chat: 1', 'the first run should report count 1');
  contains(first.output, 'Storage keys now: count', 'the store should hold exactly the count key');

  const second = await runModule(module, { chat: 'counter-a' });
  check(second.ok, 'the second counter run failed', JSON.stringify(second));
  check(second.result.activationsInThisChat === 2, 'count should be 2 in the same chat', JSON.stringify(second.result));
  contains(second.output, 'Activations in THIS chat: 2', 'the second run should report count 2');

  const other = await runModule(module, { chat: 'counter-b' });
  check(other.ok, 'the other-chat counter run failed', JSON.stringify(other));
  check(other.result.activationsInThisChat === 1, 'count should reset to 1 in another chat', JSON.stringify(other.result));
  contains(other.output, 'Activations in THIS chat: 1', 'a fresh chat should start from zero');
});

// network-fetcher: the UA header the module sent is dropped before the
// transport, the identity it read from zagents.info is the one on record, and
// the real api.github.com call is graceful online OR offline — never a hang.
fixture('zagents-network-fetcher', async function () {
  const net = fakeNet();
  net.install();
  try {
    const module = extractFixture('zagents-network-fetcher.zip');
    const startedAt = Date.now();
    const result = await runModule(module, { chat: 'fetcher-a' });
    const elapsed = Date.now() - startedAt;

    check(result.ok, 'the fetcher run failed', JSON.stringify(result));
    const github = net.requestsTo('api.github.com');
    check(github.length === 1, 'one request should have reached the transport', JSON.stringify(net.records.map(function (r) { return r.host; })));
    check(elapsed < 30000, 'the real call must never hang', elapsed + 'ms');
    const seen = github[0].headers;
    check(!('user-agent' in seen), 'the module-supplied User-Agent must be dropped before the transport', JSON.stringify(seen));
    check(!('host' in seen) && !('content-length' in seen), 'the blocked header set must not reach the transport', JSON.stringify(seen));
    contains(result.output, 'User-Agent (from Settings): RoomCompat/1.0', 'the module should read the profile identity');
    contains(result.output, 'Fetching https://api.github.com/zen …', 'the emit should ride the output body');
    // Graceful whatever the network said: a 200 carries the quote, anything
    // else — a refusal, a rate limit, the offline status:0 convention — is
    // reported as a definitive answer the module read, never a hang.
    const status = result.result.status;
    check(typeof status === 'number', 'the call should answer a definitive status', JSON.stringify(result.result));
    if (status === 200) {
      check(typeof result.result.quote === 'string' && result.result.quote.length > 0, 'an online run should carry the quote');
    } else {
      contains(result.output, 'Request failed with status ' + status, 'a non-2xx answer should be reported gracefully');
    }
  } finally {
    net.restore();
  }
});

// file-notes: the state/ round-trip — readFile absent, mkdir, appendFile,
// readdir, stat — and the note grows only within its own chat.
fixture('zagents-file-notes', async function () {
  const module = extractFixture('zagents-file-notes.zip');
  const first = await runModule(module, { chat: 'notes-a' });
  check(first.ok, 'the first notes run failed', JSON.stringify(first));
  check(first.result.appendsInThisChat === 1, 'the first run appends once', JSON.stringify(first.result));
  check(first.result.stateEntries.join(',') === 'notes', 'the state directory should hold the notes folder', JSON.stringify(first.result));
  check(first.result.noteSizeBytes === 25, 'one timestamp line is 25 bytes', JSON.stringify(first.result));
  contains(first.output, 'state/ entries: notes', 'readdir should report the notes folder');
  contains(first.output, 'Note size: 25 bytes', 'stat should report the size');
  contains(first.output, 'Appends so far in THIS chat: 1', 'the append count should read 1');

  const second = await runModule(module, { chat: 'notes-a' });
  check(second.ok, 'the second notes run failed', JSON.stringify(second));
  check(second.result.appendsInThisChat === 2, 'the second run appends again', JSON.stringify(second.result));
  check(second.result.noteSizeBytes === 50, 'two timestamp lines are 50 bytes', JSON.stringify(second.result));
  check(typeof second.result.noteModified === 'string' && second.result.noteModified.indexOf('T') === 10, 'stat should report an ISO-8601 modified time', JSON.stringify(second.result));

  const other = await runModule(module, { chat: 'notes-b' });
  check(other.ok, 'the other-chat notes run failed', JSON.stringify(other));
  check(other.result.appendsInThisChat === 1, 'another chat starts an empty state root', JSON.stringify(other.result));

  // The state root is the one the caller named — the file is on disk there.
  const note = path.join(WORK_ROOT, 'plugin-data', 'notes-a', 'zagents_file-notes', 'state', 'notes', 'session.txt');
  check(fs.existsSync(note), 'the note should live under the state root', note);
  check(fs.readFileSync(note, 'utf8').split('\n').filter(Boolean).length === 2, 'the note on disk should hold two lines');
});

// chat-streamer: three emits accumulate into ONE output body — the module
// message, not three messages — with the log lines after a blank line.
fixture('zagents-chat-streamer', async function () {
  const module = extractFixture('zagents-chat-streamer.zip');
  const result = await runModule(module, { chat: 'streamer-a' });
  check(result.ok, 'the streamer run failed', JSON.stringify(result));
  check(result.result.streamed === 3, 'the module should report three updates', JSON.stringify(result.result));
  const expected =
    'Step 1/3 — reading module metadata…\n' +
    'Step 2/3 — everything stays inside THIS chat (no storage, no network).\n' +
    'Step 3/3 — streaming complete.\n' +
    '\n' +
    'Streamed 3 updates into the conversation';
  check(result.output === expected, 'all emits should accumulate into one body, logs after a blank line', JSON.stringify(result.output));
});

// onhomeroom-daily, offline: the staged prompt commands. `status` before any
// setup answers cleanly without a single request; `setup` stores credentials
// in this chat's storage and then fails sign-in gracefully (the status:0
// convention); a follow-up `status` still reads storage and stays offline; a
// plain (non-command) message proves the stored credentials were read back by
// driving the module to attempt sign-in again. The sample declares no `chat`
// permission, so its emit texts are dropped by its own permission set —
// exactly as upstream ZAgents behaves — and the readable surface is the run
// result and the log lines.
fixture('zagents-onhomeroom-daily', async function () {
  const net = fakeNet();
  net.install();
  try {
    const module = extractFixture('zagents-onhomeroom-daily.zip');
    const moduleJson = module.manifest;
    check(moduleJson.timeoutSeconds === 120, 'the sample should carry its own 120 s budget');

    const statusBefore = await runModule(module, { chat: 'daily-a', prompt: 'status' });
    check(statusBefore.ok, 'the status run failed', JSON.stringify(statusBefore));
    check(statusBefore.result.command === 'status', 'the status command should parse off the prompt', JSON.stringify(statusBefore.result));
    check(net.records.length === 0, 'status before setup needs no network', JSON.stringify(net.records));
    check(statusBefore.output.indexOf('Module run failed') < 0, 'status should not fail');

    const setup = await runModule(module, { chat: 'daily-a', prompt: 'setup a@b.com pw123456' });
    check(setup.result && setup.result.error === 'login failed', 'an offline setup should fail sign-in gracefully', JSON.stringify(setup.result));
    const kv = JSON.parse(
      fs.readFileSync(path.join(WORK_ROOT, 'plugin-data', 'daily-a', 'zagents_onhomeroom-daily', 'kv.json'), 'utf8')
    );
    check(kv.config && kv.config.account.email === 'a@b.com', 'the credentials should be stored in this chat\'s storage', JSON.stringify(kv.config && kv.config.account));
    check(kv.config.account.password === 'pw123456', 'the stored password should round-trip');
    const logins = net.requestsTo('app.onhomeroom.com');
    check(logins.length === 1, 'the setup run should attempt exactly one sign-in', JSON.stringify(net.records.map(function (r) { return r.host + r.path; })));
    check(logins[0].method === 'POST' && logins[0].path === '/api/auth/login', 'the sign-in request should be the login endpoint', JSON.stringify(logins[0]));
    contains(setup.output, 'login failed (HTTP 0)', 'the offline sign-in should log the status:0 convention');

    const statusAfter = await runModule(module, { chat: 'daily-a', prompt: 'status' });
    check(statusAfter.ok && statusAfter.result.command === 'status', 'the follow-up status should answer cleanly', JSON.stringify(statusAfter.result));
    check(net.requestsTo('app.onhomeroom.com').length === 1, 'a follow-up status still reads storage without network', JSON.stringify(net.records.map(function (r) { return r.path; })));
    const kvAfter = JSON.parse(
      fs.readFileSync(path.join(WORK_ROOT, 'plugin-data', 'daily-a', 'zagents_onhomeroom-daily', 'kv.json'), 'utf8')
    );
    check(kvAfter.config.account.email === 'a@b.com', 'the stored credentials should survive the follow-up run');

    const plain = await runModule(module, { chat: 'daily-a', prompt: 'run the cycle now please' });
    check(plain.result && plain.result.error === 'login failed', 'a non-command message should drive the stored credentials to sign-in', JSON.stringify(plain.result));
    check(net.requestsTo('app.onhomeroom.com').length === 2, 'the plain run should attempt sign-in from the stored config', JSON.stringify(net.records.map(function (r) { return r.path; })));
    contains(plain.output, 'login failed (HTTP 0)', 'the plain run should log the offline sign-in');
  } finally {
    net.restore();
  }
});

// The allowlist gate: a host the manifest never named is refused before the
// transport ever sees it — with ZAgents' own words — and a URL that does not
// parse is the same refusal. The allowed host, answered from a CAN, rides the
// full response wrapper: ok/status/body, lowercased headers, text() and
// json().
fixture('zagents-gate-allowlist', async function () {
  const net = fakeNet();
  net.install();
  try {
    const module = writeModule(
      'gate-allowlist',
      [
        "'use strict';",
        'async function main() {',
        '  let refused = null;',
        '  try {',
        '    const res = await zagents.http.request("https://example.com/anything");',
        '    refused = "allowed with status " + res.status;',
        '  } catch (e) { refused = e.message; }',
        '  let badUrl = null;',
        '  try { await zagents.http.request("::::not a url"); } catch (e) { badUrl = e.message; }',
        '  zagents.log("refused: " + refused);',
        '  zagents.log("bad-url: " + badUrl);',
        '  return { refused: refused, badUrl: badUrl };',
        '}',
        'module.exports = main();',
      ].join('\n'),
      ['network'],
      ['api.github.com']
    );
    const result = await runModule(module, { chat: 'allow-a' });
    check(result.ok, 'the allowlist gate run failed', JSON.stringify(result));
    check(result.result.refused === 'Host is not allowed by this module (allowedHosts)', 'a non-allowed host is refused with the exact words', JSON.stringify(result.result));
    check(result.result.badUrl === 'Host is not allowed by this module (allowedHosts)', 'an unparseable URL is the same refusal', JSON.stringify(result.result));
    check(net.records.length === 0, 'nothing should reach the transport', JSON.stringify(net.records));

    // The allowed host, answered from a canned response: the wrapper the
    // module reads is ZAgents' own — ok on 2xx, lowercased headers, text()
    // and json() over the body.
    net.canned['api.github.com'] = {
      status: 200,
      headers: { 'X-Test': 'yes', 'Set-Cookie': 'session=abc' },
      body: '{"ok":true,"n":2}',
    };
    const allowed = writeModule(
      'gate-allowlist-allowed',
      [
        "'use strict';",
        'async function main() {',
        '  const res = await zagents.http.request("https://api.github.com/canned", {',
        '    method: "GET",',
        '    headers: { "Accept": "application/json" },',
        '  });',
        '  const text = await res.text();',
        '  const json = await res.json();',
        '  return {',
        '    ok: res.ok,',
        '    status: res.status,',
        '    header: res.headers["x-test"],',
        '    cookie: res.headers["set-cookie"],',
        '    text: text,',
        '    n: json.n,',
        '  };',
        '}',
        'module.exports = main();',
      ].join('\n'),
      ['network'],
      ['api.github.com']
    );
    const answered = await runModule(allowed, { chat: 'allow-b' });
    check(answered.ok, 'the allowed-host run failed', JSON.stringify(answered));
    check(answered.result.ok === true && answered.result.status === 200, 'a canned 2xx should read as ok', JSON.stringify(answered.result));
    check(answered.result.header === 'yes', 'the headers should arrive lowercased', JSON.stringify(answered.result));
    check(answered.result.cookie === 'session=abc', 'the cookie header should ride through', JSON.stringify(answered.result));
    check(answered.result.text === '{"ok":true,"n":2}', 'text() should be the body verbatim', JSON.stringify(answered.result));
    check(answered.result.n === 2, 'json() should parse the body', JSON.stringify(answered.result));
    const seen = net.requestsTo('api.github.com');
    check(seen.length === 1 && seen[0].path === '/canned', 'the canned request should reach the transport once', JSON.stringify(net.records));
    check(seen[0].headers.accept === 'application/json', 'the module header should ride the transport', JSON.stringify(seen[0].headers));
  } finally {
    net.restore();
  }
});

// The compose gate: a sentence that violates the rules earns one corrective
// retry (the retry note carries the violation names), a second violation
// falls through to the deterministic sanitize, and a repeat of an avoided
// text cannot be repaired — the exact throw, with the exact words.
fixture('zagents-gate-compose', async function () {
  const agent = fakeAgent([
    'Wow! the puzzle streak is amazing: five days strong — nice 🎉',
    'Sure; the puzzle streak held at five days and the digest left on time',
    'the daily digest went out fine today',
  ]);
  const module = writeModule(
    'gate-compose',
    [
      "'use strict';",
      'async function main() {',
      '  const first = await zagents.agent.compose({',
      '    task: "digest line",',
      '    context: "the user finished two puzzles and kept a five day streak",',
      '    trailingPeriod: true,',
      '  });',
      '  zagents.log("first: " + first.text);',
      '  let repeatError = null;',
      '  try {',
      '    await zagents.agent.compose({',
      '      task: "repeat test",',
      '      context: "the daily digest went out fine today",',
      '      avoid: ["the daily digest went out fine today"],',
      '      trailingPeriod: true,',
      '    });',
      '  } catch (e) { repeatError = e.message; }',
      '  zagents.log("repeat: " + repeatError);',
      '  return { first: first.text, repeatError: repeatError };',
      '}',
      'module.exports = main();',
    ].join('\n'),
    ['agent']
  );
  const result = await runModule(module, { chat: 'compose-a', askAgent: agent.ask });
  check(result.ok, 'the compose gate run failed', JSON.stringify(result));
  check(
    result.result.first === 'Sure - the puzzle streak held at five days and the digest left on time',
    'two violations should fall through to the sanitized sentence',
    JSON.stringify(result.result)
  );
  check(
    result.result.repeatError === 'composer output kept violating the rules (repeats an earlier text)',
    'an unrepairable repeat should throw the exact words',
    JSON.stringify(result.result)
  );
  check(agent.calls.length === 4, 'the pipeline should make exactly two attempts per compose', agent.calls.length + ' calls');
  const retry = agent.calls[1];
  check(retry.purpose === 'digest line', 'the retry should carry the task as the purpose', JSON.stringify(retry));
  contains(retry.instruction, 'one casual sentence', 'the instruction should carry the hard rules line');
  contains(retry.instruction, 'at most 24 words', 'the word budget should be in the rules line');
  check(retry.context === 'the user finished two puzzles and kept a five day streak', 'the context should ride verbatim');
  contains(retry.hint, 'Your previous attempt violated the rules:', 'the retry note should name the violations');
  contains(retry.hint, 'forbidden character', 'the violations should include the forbidden character');
  contains(retry.hint, 'emoji', 'the violations should include the emoji');
});

// The runtime form: canned values resolve, a cancel and a timeout reject with
// the app's own words, a second concurrent form is a busy error, and a host
// that wired no channel at all answers with the no-UI sentence. The forms the
// channel received are the ones the module sent — title, description, fields.
fixture('zagents-gate-form', async function () {
  const user = fakeUserInput([
    function () {
      return Promise.resolve({ values: { name: 'Rin', pets: 'cat' } });
    },
    function () {
      return Promise.reject(new Error('Form was cancelled'));
    },
    function () {
      return Promise.reject(new Error('Form timed out'));
    },
    function () {
      return new Promise(function (resolve) {
        setImmediate(function () {
          resolve({ values: {} });
        });
      });
    },
  ]);
  const module = writeModule(
    'gate-form',
    [
      "'use strict';",
      'async function main() {',
      '  const got = await zagents.ui.form({',
      '    title: "Your name",',
      '    description: "the compat harness wants it",',
      '    fields: [{ key: "name", type: "string", title: "Name", required: true }],',
      '  });',
      '  let cancelError = null;',
      '  try {',
      '    await room.ui.form({ title: "Cancelled", fields: [{ key: "x", type: "string", title: "X" }] });',
      '  } catch (e) { cancelError = e.message; }',
      '  let timeoutError = null;',
      '  try {',
      '    await zagents.ui.form({ title: "Timed out", fields: [{ key: "y", type: "string", title: "Y" }] });',
      '  } catch (e) { timeoutError = e.message; }',
      '  let secondError = null;',
      '  const first = zagents.ui.form({ title: "A", fields: [{ key: "a", type: "string", title: "A" }] });',
      '  const second = zagents.ui.form({ title: "B", fields: [{ key: "b", type: "string", title: "B" }] })',
      '    .catch(function (e) { secondError = e.message; return null; });',
      '  await first;',
      '  await second;',
      '  return {',
      '    name: got.values.name,',
      '    pets: got.values.pets,',
      '    cancelError: cancelError,',
      '    timeoutError: timeoutError,',
      '    secondError: secondError,',
      '  };',
      '}',
      'module.exports = main();',
    ].join('\n'),
    ['chat']
  );
  const result = await runModule(module, { chat: 'form-a', askUser: user.ask });
  check(result.ok, 'the form gate run failed', JSON.stringify(result));
  check(result.result.name === 'Rin' && result.result.pets === 'cat', 'the canned values should resolve', JSON.stringify(result.result));
  check(result.result.cancelError === 'Form was cancelled', 'a dismissed dialog rejects with the exact words', JSON.stringify(result.result));
  check(result.result.timeoutError === 'Form timed out', 'a timed-out dialog rejects with the exact words', JSON.stringify(result.result));
  check(result.result.secondError === 'a form is already pending for this call', 'a second concurrent form is a busy error', JSON.stringify(result.result));
  check(user.forms.length === 4, 'four forms should have crossed the channel', user.forms.length);
  check(user.forms[0].title === 'Your name', 'the form title should ride the channel', JSON.stringify(user.forms[0]));
  check(user.forms[0].description === 'the compat harness wants it', 'the form description should ride the channel');
  check(user.forms[0].fields.length === 1 && user.forms[0].fields[0].key === 'name', 'the fields should ride the channel as given', JSON.stringify(user.forms[0]));

  // No channel wired: the module reads the no-UI sentence and the call fails
  // honestly rather than hanging.
  const bare = writeModule(
    'gate-form-bare',
    [
      "'use strict';",
      'async function main() {',
      '  await zagents.ui.form({ title: "Nobody home", fields: [{ key: "n", type: "string", title: "N" }] });',
      '  return { asked: true };',
      '}',
      'module.exports = main();',
    ].join('\n'),
    ['chat']
  );
  const refused = await runModule(bare, { chat: 'form-b', askUser: null });
  check(!refused.ok, 'a host with no form channel should fail the run', JSON.stringify(refused));
  contains(refused.output, 'No interactive UI is available for this call', 'the refusal should use the exact words');
});

// The SDK surface: require('zagents-sdk') is the injected global inside a
// zagents run; zagents.info reports the staged prompt, the stored input
// values and the profile identity; the storage and permission error strings
// are ZAgents' own; and outside a module run the virtual package is refused
// with ZAgents' own sentence.
fixture('zagents-gate-sdk', async function () {
  const module = writeModule(
    'gate-sdk',
    [
      "'use strict';",
      'async function main() {',
      '  const sdk = require("zagents-sdk");',
      '  let emptyKey = null;',
      '  try { await zagents.storage.set("", 1); } catch (e) { emptyKey = e.message; }',
      '  let denied = null;',
      '  try { zagents.http.request("https://api.github.com/zen"); } catch (e) { denied = e.message; }',
      '  return {',
      '    sdkIsGlobal: sdk === zagents,',
      '    name: zagents.info.name,',
      '    prompt: zagents.info.prompt,',
      '    inputToken: zagents.info.inputs.token,',
      '    userAgent: zagents.info.userAgent,',
      '    metaFormat: room.meta.format,',
      '    emptyKey: emptyKey,',
      '    denied: denied,',
      '  };',
      '}',
      'module.exports = main();',
    ].join('\n'),
    ['storage']
  );
  // The call the app would send for a module whose form was answered: the
  // stored input values ride beside the staged prompt and the identity.
  const request = {
    pluginRoot: module.dir,
    dataRoot: path.join(WORK_ROOT, 'plugin-data', 'sdk-a', 'zagents_gate-sdk'),
    entry: 'main.js',
    manifest: wireManifestOf(module.manifest),
    event: 'activate',
    payload: {},
    config: {},
    timeoutMs: 30000,
    format: 'zagents',
    prompt: 'hello module',
    stateRoot: path.join(WORK_ROOT, 'plugin-data', 'sdk-a', 'zagents_gate-sdk', 'state'),
    title: module.manifest.title,
    zagentsVersion: module.manifest.version,
    zagentsName: module.manifest.name,
    userAgent: 'RoomCompat/1.0',
    inputsValues: { token: 'abc123' },
    askAgent: null,
    askUser: null,
  };
  const { runPluginCall } = require(HOST);
  const withInputs = await runPluginCall(request);
  check(withInputs.ok, 'the sdk run failed', JSON.stringify(withInputs));
  const info = withInputs.result;
  check(info.sdkIsGlobal === true, "require('zagents-sdk') should return the injected global", JSON.stringify(info));
  check(info.name === 'gate-sdk', 'zagents.info.name should report the module name', JSON.stringify(info));
  check(info.prompt === 'hello module', 'the staged prompt should ride zagents.info', JSON.stringify(info));
  check(info.inputToken === 'abc123', 'the stored input values should ride zagents.info.inputs', JSON.stringify(info));
  check(info.userAgent === 'RoomCompat/1.0', 'the profile identity should ride zagents.info.userAgent', JSON.stringify(info));
  check(info.metaFormat === 'zagents', 'room.meta should report the zagents format', JSON.stringify(info));
  check(info.emptyKey === 'Storage key must not be empty', 'the empty-key refusal should use ZAgents\' words', JSON.stringify(info));
  check(info.denied === 'Permission denied: network', 'an ungranted permission should refuse with ZAgents\' words', JSON.stringify(info));

  // Outside a module run the virtual package is refused, in its own words.
  const pluginSide = {
    name: 'sdkrefusal',
    dir: fs.mkdtempSync(path.join(WORK_ROOT, 'plugin-sdk-')),
  };
  fs.writeFileSync(
    path.join(pluginSide.dir, 'main.js'),
    "require('zagents-sdk');\nmodule.exports = {};\n"
  );
  const refused = await runPlugin(pluginSide, ['storage']);
  check(!refused.ok, 'a plugin-format run should fail on the sdk require', JSON.stringify(refused));
  contains(
    refused.output,
    'zagents SDK is only available inside ZAgents chat sessions ' +
      '(run the module with /plugins or from the Modules screen).',
    'the refusal should use ZAgents\' own sentence'
  );
});

// ---- the runner -------------------------------------------------------------

async function main() {
  const mode = process.argv[2];
  const only = mode === '--only' ? process.argv.slice(3) : null;
  const selected = only ? fixtures.filter((f) => only.indexOf(f.name) >= 0) : fixtures;
  process.stderr.write(
    'room-zagents-compat: ' + selected.length + ' fixtures, host ' + HOST + ', root ' + WORK_ROOT + '\n'
  );
  let failed = 0;
  for (const item of selected) {
    try {
      await item.fn();
      process.stdout.write('PASS ' + item.name + '\n');
    } catch (err) {
      failed += 1;
      process.stdout.write('FAIL ' + item.name + '\n');
      process.stderr.write('  ' + item.name + ': ' + (err && err.message ? err.message : String(err)) + '\n');
    }
  }
  const summary = failed === 0 ? 'all ' + selected.length + ' fixtures passed' : failed + ' of ' + selected.length + ' fixtures failed';
  process.stdout.write((failed === 0 ? 'PASS ' : 'FAIL ') + summary + '\n');
  fs.rmSync(WORK_ROOT, { recursive: true, force: true });
  process.exit(failed === 0 ? 0 : 1);
}

// A wedged fixture must not outlive the proof: the watchdog ends the run
// non-zero instead of letting a silent hang read as success.
const watchdog = setTimeout(function () {
  process.stderr.write('room-zagents-compat: the watchdog fired — a fixture hung\n');
  process.exit(1);
}, 240000);
watchdog.unref();

main().catch(function (err) {
  process.stderr.write('room-zagents-compat: ' + (err && err.message ? err.message : String(err)) + '\n');
  process.exit(1);
});
