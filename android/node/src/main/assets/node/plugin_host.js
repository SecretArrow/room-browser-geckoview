'use strict';

// The plugin sandbox host. bridge.js hands it one call; it answers with one
// result envelope. Everything in between — loading the plugin's entry into a
// vm context, handing it the `room` SDK, dispatching the event, checking what
// it sends back — lives here, beside the plugins it runs.
//
// The sandbox is a capability shell over a runtime that was never a security
// boundary to begin with (one UID, one data dir — the node_run doc says so).
// What a plugin gets instead of that trust: a context with no fs/net/process
// in reach, a require shim over a whitelist, storage under a per-chat data
// root it cannot name, and network through an allowlist checked before every
// request and again on every redirect. Anything past that is between the
// plugin and the app's user, not between the plugin and this process.
//
// It runs under the bridge on the device and under plain Node >= 18 on a
// desktop (tools/plugin_dev.js), so it owns no paths, no config and no state
// of its own: a call is entirely described by its opts, and two calls never
// see each other.
//
// Result semantics, per event: `tool` puts the tool's return value in `output`
// (a string as it is, anything else JSON-stringified); every other event
// answers with an empty `output` and speaks through its `cards`. A failure of
// the SCRIPT is `ok:false` with the message and a trimmed stack in `output`;
// a failure of the CALL itself — a malformed request, a manifest that fails
// re-validation — throws instead, which the bridge turns into its error
// envelope: the same split node_run has between a script that threw and a
// runtime that could not run it.

const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const https = require('https');
const nodeModule = require('module');
const path = require('path');
const util = require('util');
const vm = require('vm');

// ---- the contract in numbers ----------------------------------------------
//
// Every cap below is one the design states; this is the one place both the
// bridge and the desktop harness run through, so this is where they hold.

const API_VERSION = 1;

const EVENTS = ['probe', 'activate', 'deactivate', 'command', 'message', 'tool', 'config'];

const DEFAULT_BUDGET_MS = 30000;
const DEACTIVATE_BUDGET_MS = 10000;
const SYNC_CAP_MS = 10000;

const MAX_OUTPUT_CHARS = 20000; // the bridge's own reply cap; stay under it
const MAX_ERROR_CHARS = 4000; // a trimmed stack, per the envelope

const PERMISSIONS = ['storage', 'network', 'agent.tools', 'chat.context', 'agent.generate'];

// The require whitelist. Anything node ships that is not on this list is
// refused — the interesting names are the ones it leaves out: files, sockets,
// other processes, the process itself.
const BUILTIN_WHITELIST = [
  'assert', 'buffer', 'crypto', 'events', 'path', 'punycode',
  'querystring', 'string_decoder', 'url', 'util', 'zlib',
];

// module.isBuiltin is the honest test (node >= 18.6); the list underneath is
// only there so an older runtime refuses the same names instead of hunting
// for them in the plugin's node_modules.
const KNOWN_BUILTINS = BUILTIN_WHITELIST.concat([
  'fs', 'net', 'tls', 'http', 'https', 'http2', 'dgram', 'child_process',
  'cluster', 'os', 'process', 'vm', 'worker_threads', 'module', 'inspector',
  'readline', 'repl', 'stream', 'async_hooks', 'v8', 'diagnostics_channel',
  'trace_events', 'constants', 'sys',
]);
const isBuiltinName =
  typeof nodeModule.isBuiltin === 'function'
    ? function (spec) { return nodeModule.isBuiltin(spec); }
    : function (spec) { return KNOWN_BUILTINS.indexOf(spec) >= 0; };

// Quotas, per call unless the name says otherwise.
const KV_MAX_BYTES = 256 * 1024;
const KV_MAX_KEYS = 128;
const KV_MAX_VALUE = 16 * 1024;
const FILES_MAX_BYTES = 10 * 1024 * 1024;
const FILES_MAX_COUNT = 256;
const FILE_NAME = /^[A-Za-z0-9._-]{1,96}$/;
const NET_BODY_MAX = 2 * 1024 * 1024;
const NET_TOTAL_MAX = 6 * 1024 * 1024;
const NET_TIMEOUT_MAX = 15000;
const NET_REDIRECTS = 2;
const LOG_MAX_LINES = 50;
const LOG_MAX_BYTES = 2048;
const CARD_MAX_COUNT = 5;
const CARD_MAX_JSON = 4096;
const CARD_TITLE_MAX = 120;
const CARD_TEXT_MAX = 4000;
const CARD_FIELDS_MAX = 12;
const CARD_FIELD_MAX = 200;
const CARD_LABEL_MAX = 64;
const CARD_URL_MAX = 500;
const CARD_STYLES = ['info', 'success', 'warn', 'error'];
const TOOL_DESCRIPTION_MAX = 500;
const TOOL_NAME_MAX = 60;
const HISTORY_LIMIT_MAX = 200;
const HISTORY_SUMMARY_CHARS = 4000;

// The generation request (agent.generate): every plugin-supplied string is
// clamped before it travels, because the app renders those fields into its
// own prompt — a field that grew without a cap would be a prompt the plugin
// wrote, which is exactly what the structured request exists to prevent.
const GEN_PURPOSE_MAX = 64;
const GEN_INSTRUCTION_MAX = 500;
const GEN_CONTEXT_MAX = 4000;
const GEN_HINT_MAX = 200;
const GEN_LANGUAGE_MAX = 24;
const GEN_FORBIDDEN_MAX = 64;
const GEN_TONE_MAX = 64;
const GEN_MAX_WORDS_CAP = 200;
const GEN_AVOID_ENTRIES = 50;
const GEN_AVOID_TEXT_MAX = 200;
const GEN_ANSWER_MAX = 2000;

// ---- the one process-wide tolerance ---------------------------------------

// A plugin's promise that rejects after its call was answered must not take
// the runtime with it: bridge.js treats an uncaught exception as fatal, and a
// late rejection arrives as exactly one of those. Keeping it as stderr noise
// is the honest option left — the call it belonged to is already over.
process.on('unhandledRejection', function (reason) {
  try {
    process.stderr.write(
      'room-plugin-host: a plugin promise rejected after its call: ' + describe(reason) + '\n'
    );
  } catch (err) {
    // stderr itself failed; there is nothing left to protect.
  }
});

// ---- small shared helpers -------------------------------------------------

function describe(err) {
  // A realm's Error is not an instanceof this realm's Error, so the check is
  // by shape: anything with a message string reads as its message.
  if (err && typeof err === 'object' && typeof err.message === 'string' && err.message) {
    return err.message;
  }
  return String(err);
}

function clip(text, max) {
  if (text.length <= max) return text;
  return text.slice(0, max) + '\n...[truncated at ' + max + ' characters]';
}

function clipPlain(text, max) {
  return text.length <= max ? text : text.slice(0, max);
}

function safeJson(value) {
  try {
    return JSON.stringify(value);
  } catch (err) {
    return String(CARD_MAX_JSON + 1); // longer than any card may be: dropped
  }
}

function failureOutput(err) {
  let message = describe(err);
  // V8's own words for a script the sync cap stopped, in the runtime's voice.
  if (/^Script execution timed out/.test(message)) {
    message = 'the plugin was still running after its budget and was stopped';
  }
  let text = message;
  if (err && typeof err === 'object' && typeof err.stack === 'string') {
    const frames = err.stack.split('\n').slice(1).join('\n').trim();
    if (frames) text = message + '\n' + frames;
  }
  return text.length > MAX_ERROR_CHARS ? text.slice(0, MAX_ERROR_CHARS) : text;
}

// ---- the network allowlist -------------------------------------------------

// Manifest patterns are exact hostnames (`api.example.com`) or `*.domain`
// forms that also cover the domain itself. Everything is lowercase by the
// time it is validated, and IP literals and single-label hosts were refused
// at install — so the matcher never matches them either way.
function matchesAllowHost(host, patterns) {
  if (typeof host !== 'string' || !host) return false;
  if (!Array.isArray(patterns)) return false;
  const name = host.toLowerCase();
  for (let index = 0; index < patterns.length; index++) {
    const pattern = patterns[index];
    if (typeof pattern !== 'string' || !pattern) continue;
    const lowered = pattern.toLowerCase();
    if (lowered.indexOf('*.') === 0) {
      const domain = lowered.slice(2);
      if (name === domain || name.endsWith('.' + domain)) return true;
    } else if (lowered === name) {
      return true;
    }
  }
  return false;
}

// ---- manifest re-check -----------------------------------------------------
//
// Install-time validation is Kotlin's; this is the second look the design
// asks for, over the subset the wire carries. It fails closed — a manifest
// that does not hold up refuses the call rather than running anyway.

const ID_RE = /^[a-z][a-z0-9]*(\.[a-z0-9-]+){1,4}$/;
const VERSION_RE = /^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/;
const HOST_LABEL_RE = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/;
const IPV4_RE = /^\d{1,3}(\.\d{1,3}){3}$/;

function patternError(pattern) {
  if (typeof pattern !== 'string' || !pattern) return 'must be a non-empty string';
  if (pattern !== pattern.toLowerCase()) return 'must be lowercase';
  if (/[/:\s]/.test(pattern)) return 'must not contain "/", ":" or spaces';
  let body = pattern;
  if (body.indexOf('*.') === 0) body = body.slice(2);
  if (body.indexOf('*') >= 0) return 'may only use "*" as a leading "*." prefix';
  if (IPV4_RE.test(body)) return 'is an IP literal; hostnames only';
  const labels = body.split('.');
  if (labels.length < 2) return 'is a single-label host; localhost included is refused';
  for (let index = 0; index < labels.length; index++) {
    if (!labels[index] || !HOST_LABEL_RE.test(labels[index])) return 'is not a hostname';
  }
  return null;
}

function manifestErrors(manifest) {
  const errors = [];
  if (
    typeof manifest.id !== 'string' ||
    manifest.id.length < 3 ||
    manifest.id.length > 64 ||
    !ID_RE.test(manifest.id)
  ) {
    errors.push('id must match ^[a-z][a-z0-9]*(\\.[a-z0-9-]+){1,4}$ and be 3..64 chars');
  }
  if (
    typeof manifest.name !== 'string' ||
    manifest.name.length < 1 ||
    manifest.name.length > 64 ||
    /[\u0000-\u001f\u007f]/.test(manifest.name)
  ) {
    errors.push('name must be 1..64 printable characters');
  }
  if (typeof manifest.version !== 'string' || !VERSION_RE.test(manifest.version)) {
    errors.push('version must be ^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$');
  }
  const permissions = manifest.permissions;
  if (
    !Array.isArray(permissions) ||
    permissions.length > 5 ||
    new Set(permissions).size !== permissions.length ||
    permissions.some(function (p) { return PERMISSIONS.indexOf(p) < 0; })
  ) {
    errors.push('permissions must be unique and drawn from ' + PERMISSIONS.join(', '));
  }
  const allow = manifest.networkAllow;
  if (allow !== undefined && (!Array.isArray(allow) || allow.length > 20)) {
    errors.push('networkAllow must be an array of at most 20 host patterns');
  } else if (Array.isArray(allow)) {
    for (let index = 0; index < allow.length; index++) {
      const problem = patternError(allow[index]);
      if (problem) {
        errors.push('network allow pattern ' + JSON.stringify(allow[index]) + ' ' + problem);
      }
    }
  }
  if (Array.isArray(permissions) && permissions.indexOf('network') >= 0) {
    if (!Array.isArray(allow) || allow.length < 1) {
      errors.push('the network permission requires a non-empty networkAllow list');
    }
  }
  return errors;
}

// ---- reading the request ---------------------------------------------------

function readRequest(opts) {
  if (!opts || typeof opts !== 'object') {
    throw new Error('the plugin call was malformed: expected an options object');
  }
  const pluginRoot = typeof opts.pluginRoot === 'string' ? opts.pluginRoot : null;
  const dataRoot = typeof opts.dataRoot === 'string' ? opts.dataRoot : null;
  const entry = typeof opts.entry === 'string' ? opts.entry : null;
  const manifest = opts.manifest && typeof opts.manifest === 'object' ? opts.manifest : null;
  const event = typeof opts.event === 'string' ? opts.event : null;
  if (!pluginRoot || !dataRoot || !entry || !manifest || !event) {
    throw new Error(
      'the plugin call was malformed: pluginRoot, dataRoot, entry, manifest and event are required'
    );
  }
  if (EVENTS.indexOf(event) < 0) {
    throw new Error('the plugin call was malformed: unknown event "' + event + '"');
  }
  const problems = manifestErrors(manifest);
  if (problems.length > 0) {
    throw new Error('the plugin manifest failed re-validation: ' + problems.join('; '));
  }
  const payload =
    opts.payload && typeof opts.payload === 'object' && !Array.isArray(opts.payload)
      ? opts.payload
      : {};
  const config =
    opts.config && typeof opts.config === 'object' && !Array.isArray(opts.config)
      ? opts.config
      : {};
  const timeoutMs =
    Number.isFinite(opts.timeoutMs) && opts.timeoutMs > 0
      ? opts.timeoutMs
      : event === 'deactivate'
        ? DEACTIVATE_BUDGET_MS
        : DEFAULT_BUDGET_MS;
  return {
    pluginRoot: pluginRoot,
    dataRoot: dataRoot,
    entry: entry,
    manifest: manifest,
    event: event,
    payload: payload,
    config: config,
    timeoutMs: timeoutMs,
    // The wire carries no history of its own (a §6 gap worth one report line,
    // not one invented field): room.chat reads it from here when a caller has
    // some, and answers an empty chat when it does not.
    history: Array.isArray(opts.history) ? opts.history : [],
    // The profile's own user agent as the command carried it — null when the
    // app supplied none. room.meta.userAgent shows the plugin what its
    // requests ride as; nothing else reads it, so the string arrives cleaned
    // exactly once, here.
    userAgent:
      typeof opts.userAgent === 'string' && opts.userAgent.trim()
        ? opts.userAgent.trim()
        : null,
    // The reverse-request channel room.agent.generate speaks over. Absent
    // (a host that wired none) is a state the namespace reports in words
    // rather than by hanging — the same trade every gated call makes.
    askAgent: typeof opts.askAgent === 'function' ? opts.askAgent : null,
  };
}

// §6 carries no session id, so it is read out of the §9 data-root layout the
// caller already had to build: .../plugin-data/<sessionId>/<sanitized>.
function sessionIdOf(dataRoot) {
  const parts = path.resolve(dataRoot).split(path.sep);
  const at = parts.lastIndexOf('plugin-data');
  if (at >= 0 && at + 1 < parts.length && parts[at + 1]) return parts[at + 1];
  return null;
}

// ---- the generation request -----------------------------------------------
//
// What room.agent.generate sends over the reverse channel: structured fields,
// never a raw prompt. The app renders the prompt itself and the system prompt
// is app-owned, so everything here is data — clamped data, so a plugin bug
// cannot turn one of its fields into an instruction. The app validates and
// clamps the same shape again on receipt; this side clamps before sending
// (defense in depth, and the error a plugin reads is this side's).

function shapeGenerateRequest(options) {
  if (!options || typeof options !== 'object' || Array.isArray(options)) {
    throw new Error('the generation request was malformed: expected an options object');
  }
  if (typeof options.purpose !== 'string' || !options.purpose.trim()) {
    throw new Error(
      'the generation request needs a purpose: a non-blank string of at most ' +
        GEN_PURPOSE_MAX + ' characters'
    );
  }
  if (typeof options.instruction !== 'string' || !options.instruction.trim()) {
    throw new Error(
      'the generation request needs an instruction: a non-blank string of at most ' +
        GEN_INSTRUCTION_MAX + ' characters'
    );
  }
  const request = {
    purpose: clipPlain(options.purpose.trim(), GEN_PURPOSE_MAX),
    instruction: clipPlain(options.instruction.trim(), GEN_INSTRUCTION_MAX),
  };
  if (typeof options.context === 'string') {
    request.context = clipPlain(options.context, GEN_CONTEXT_MAX);
  }
  if (typeof options.hint === 'string') {
    request.hint = clipPlain(options.hint, GEN_HINT_MAX);
  }
  const style =
    options.style && typeof options.style === 'object' && !Array.isArray(options.style)
      ? options.style
      : null;
  if (style) {
    // Wrong-typed members are dropped rather than refused: style is advisory
    // (the app does not guarantee it), so a sloppy member costs its own
    // constraint, never the whole generation.
    const shaped = {};
    if (typeof style.language === 'string') {
      shaped.language = clipPlain(style.language, GEN_LANGUAGE_MAX);
    }
    if (Number.isFinite(style.maxWords)) {
      shaped.maxWords = Math.max(1, Math.min(GEN_MAX_WORDS_CAP, Math.floor(style.maxWords)));
    }
    if (typeof style.oneSentence === 'boolean') shaped.oneSentence = style.oneSentence;
    if (typeof style.lowercase === 'boolean') shaped.lowercase = style.lowercase;
    if (typeof style.forbiddenChars === 'string') {
      shaped.forbiddenChars = clipPlain(style.forbiddenChars, GEN_FORBIDDEN_MAX);
    }
    if (typeof style.tone === 'string') shaped.tone = clipPlain(style.tone, GEN_TONE_MAX);
    if (Object.keys(shaped).length > 0) request.style = shaped;
  }
  if (Array.isArray(options.avoid)) {
    const avoid = [];
    for (
      let index = 0;
      index < options.avoid.length && avoid.length < GEN_AVOID_ENTRIES;
      index++
    ) {
      const text = options.avoid[index];
      if (typeof text === 'string') avoid.push(clipPlain(text, GEN_AVOID_TEXT_MAX));
    }
    if (avoid.length > 0) request.avoid = avoid;
  }
  return request;
}

// ---- cards -----------------------------------------------------------------

// A card is clamped where a length is all that is wrong and dropped where its
// shape is; either way a log line says which, because a card that silently
// never arrived looks like a bug in the app instead of the plugin.
function sanitizeCard(input, logLine) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    logLine('warn', 'a card was dropped: cards must be objects');
    return null;
  }
  const card = {};
  let hasContent = false;
  if (input.title !== undefined && input.title !== null) {
    if (typeof input.title !== 'string') {
      logLine('warn', 'a card was dropped: title must be text');
      return null;
    }
    card.title = clipPlain(input.title, CARD_TITLE_MAX);
    hasContent = true;
  }
  if (input.text !== undefined && input.text !== null) {
    if (typeof input.text !== 'string') {
      logLine('warn', 'a card was dropped: text must be text');
      return null;
    }
    card.text = clipPlain(input.text, CARD_TEXT_MAX);
    hasContent = true;
  }
  if (input.fields !== undefined && input.fields !== null) {
    if (!Array.isArray(input.fields)) {
      logLine('warn', 'a card was dropped: fields must be an array of [key, value] pairs');
      return null;
    }
    const fields = [];
    for (
      let index = 0;
      index < input.fields.length && fields.length < CARD_FIELDS_MAX;
      index++
    ) {
      const pair = input.fields[index];
      if (!Array.isArray(pair) || pair.length < 2) {
        logLine('warn', 'a card field was dropped: each fields entry must be a [key, value] pair');
        continue;
      }
      fields.push([
        clipPlain(String(pair[0]), CARD_FIELD_MAX),
        clipPlain(String(pair[1]), CARD_FIELD_MAX),
      ]);
    }
    if (fields.length > 0) {
      card.fields = fields;
      hasContent = true;
    }
  }
  if (input.link !== undefined && input.link !== null) {
    const link = input.link;
    if (
      !link ||
      typeof link !== 'object' ||
      typeof link.url !== 'string' ||
      !/^https?:\/\//i.test(link.url)
    ) {
      logLine('warn', 'a card was dropped: link must be { label, url } with an http(s) url');
      return null;
    }
    card.link = {
      label: clipPlain(typeof link.label === 'string' ? link.label : '', CARD_LABEL_MAX),
      url: clipPlain(link.url, CARD_URL_MAX),
    };
    hasContent = true;
  }
  card.style = CARD_STYLES.indexOf(input.style) >= 0 ? input.style : 'info';
  if (!hasContent) {
    logLine('warn', 'a card was dropped: it carried nothing to show');
    return null;
  }
  // The envelope's own per-card cap: text is the field that can grow, so it
  // is the one that gives way.
  let encoded = safeJson(card);
  if (encoded.length > CARD_MAX_JSON && typeof card.text === 'string') {
    const without = safeJson(Object.assign({}, card, { text: '' }));
    card.text = clipPlain(card.text, Math.max(0, CARD_MAX_JSON - (without.length + 2)));
    encoded = safeJson(card);
  }
  if (encoded.length > CARD_MAX_JSON) {
    logLine('warn', 'a card was dropped: it would not fit in ' + CARD_MAX_JSON + ' characters');
    return null;
  }
  return card;
}

// ---- introspection -----------------------------------------------------------

// What probe (and activate) report back. A tool that does not hold up is left
// out with a log line rather than failing the plugin over it: a catalog that
// skips one sloppy tool beats a plugin that never installs.
function introspect(entryExports, logLine) {
  const tools = [];
  const commands = [];
  if (
    entryExports.tools &&
    typeof entryExports.tools === 'object' &&
    !Array.isArray(entryExports.tools)
  ) {
    const names = Object.keys(entryExports.tools);
    for (let index = 0; index < names.length; index++) {
      const name = names[index];
      const tool = entryExports.tools[name];
      const valid =
        typeof name === 'string' &&
        name.length >= 1 &&
        name.length <= TOOL_NAME_MAX &&
        tool &&
        typeof tool === 'object' &&
        !Array.isArray(tool) &&
        typeof tool.run === 'function' &&
        typeof tool.description === 'string' &&
        tool.description.length >= 1 &&
        tool.description.length <= TOOL_DESCRIPTION_MAX &&
        tool.parameters !== null &&
        typeof tool.parameters === 'object' &&
        !Array.isArray(tool.parameters);
      if (!valid) {
        logLine(
          'warn',
          "the tool '" + name + "' was left out of introspection: it needs run(), a description " +
            'of 1..' + TOOL_DESCRIPTION_MAX + ' characters and a parameters object'
        );
        continue;
      }
      tools.push({ name: name, description: tool.description, parameters: tool.parameters });
    }
  }
  if (
    entryExports.commands &&
    typeof entryExports.commands === 'object' &&
    !Array.isArray(entryExports.commands)
  ) {
    const names = Object.keys(entryExports.commands);
    for (let index = 0; index < names.length; index++) {
      const name = names[index];
      const command = entryExports.commands[name];
      if (command && typeof command === 'object' && typeof command.run === 'function') {
        commands.push(name);
      } else {
        logLine('warn', "the command '" + name + "' was left out of introspection: it needs run()");
      }
    }
  }
  const hooks = typeof entryExports.onMessage === 'function' ? ['message'] : [];
  return { tools: tools, commands: commands, hooks: hooks };
}

// ---- one call ----------------------------------------------------------------

function newCall(request, startedAt) {
  const manifest = request.manifest;
  // The permission list on the wire is what this call was granted, not what
  // the manifest asked for: the app's side intersects the two before sending,
  // which is what makes the enable-time consent mean anything here.
  const granted = new Set(Array.isArray(manifest.permissions) ? manifest.permissions : []);
  const allow = Array.isArray(manifest.networkAllow) ? manifest.networkAllow : [];
  const pluginRoot = request.pluginRoot;
  const dataRoot = request.dataRoot;
  const entry = request.entry;
  const event = request.event;
  const payload = request.payload;
  const budgetMs = request.timeoutMs;
  // The synchronous half is bounded twice, the way bridge.js bounds node_run:
  // V8 itself inside the context, a timer race outside it.
  const syncCap = Math.min(SYNC_CAP_MS, Math.floor(budgetMs / 3));

  const kvPath = path.join(dataRoot, 'kv.json');
  const filesDir = path.join(dataRoot, 'files');

  const state = {
    logs: [],
    logBytes: 0,
    cards: [],
    cardDropLine: false,
    netBytes: 0,
    kv: undefined, // loaded on first touch
    timers: new Map(), // handle -> isInterval
  };

  function logLine(level, message) {
    if (state.logs.length >= LOG_MAX_LINES) return;
    const line =
      (level === 'warn' ? 'warn: ' : level === 'error' ? 'error: ' : '') + message;
    if (state.logBytes + line.length > LOG_MAX_BYTES) {
      // One clipped line says more than silence, and silence is all that is
      // left after the budget itself is spent.
      const roomLeft = LOG_MAX_BYTES - state.logBytes;
      if (roomLeft > 0) {
        state.logs.push(line.slice(0, roomLeft));
        state.logBytes = LOG_MAX_BYTES;
      }
      return;
    }
    state.logs.push(line);
    state.logBytes += line.length;
  }

  // ---- the sandbox context ----

  const sandbox = {};
  const consoleShim = (function () {
    function write(level) {
      return function () {
        const parts = new Array(arguments.length);
        for (let index = 0; index < parts.length; index++) parts[index] = arguments[index];
        logLine(level, util.format.apply(util, parts));
      };
    }
    return {
      log: write('info'),
      info: write('info'),
      debug: write('info'),
      warn: write('warn'),
      error: write('error'),
    };
  })();
  sandbox.console = consoleShim;
  // Timers are the host's own, tracked so they die with the call — the
  // runtime is per-call, and nothing of the plugin outlives its answer.
  sandbox.setTimeout = function (fn, delay) {
    const rest = new Array(arguments.length - 2);
    for (let index = 2; index < arguments.length; index++) rest[index - 2] = arguments[index];
    const handle = setTimeout.apply(undefined, [fn, delay].concat(rest));
    state.timers.set(handle, false);
    return handle;
  };
  sandbox.clearTimeout = function (handle) {
    state.timers.delete(handle);
    return clearTimeout(handle);
  };
  sandbox.setInterval = function (fn, delay) {
    const rest = new Array(arguments.length - 2);
    for (let index = 2; index < arguments.length; index++) rest[index - 2] = arguments[index];
    const handle = setInterval.apply(undefined, [fn, delay].concat(rest));
    state.timers.set(handle, true);
    return handle;
  };
  sandbox.clearInterval = function (handle) {
    state.timers.delete(handle);
    return clearInterval(handle);
  };
  // The real classes, handed in rather than left ambient: a context gets its
  // own intrinsics, but these are the host's, which is also what keeps npm
  // packages that reach for them working.
  sandbox.Buffer = Buffer;
  sandbox.TextEncoder = TextEncoder;
  sandbox.TextDecoder = TextDecoder;
  sandbox.URL = URL;
  sandbox.URLSearchParams = URLSearchParams;
  sandbox.global = sandbox; // globalThis is already there; older packages want this

  const context = vm.createContext(sandbox);
  // The driver handlers are called through, so the whole synchronous half of
  // a handler — the half a runaway loop lives in — is under the timeout.
  vm.runInContext(
    'globalThis.__roomApplyHandler = function (handler, room, arg) { return handler(room, arg); };',
    context
  );

  // ---- the require shim ----

  const cache = new Map(); // realpath -> module record { exports }
  let rootReal = null;
  function root() {
    if (rootReal === null) {
      try {
        rootReal = fs.realpathSync(pluginRoot);
      } catch (err) {
        rootReal = path.resolve(pluginRoot);
      }
    }
    return rootReal;
  }

  function isFile(target) {
    try {
      return fs.statSync(target).isFile();
    } catch (err) {
      return false;
    }
  }

  function isDirectory(target) {
    try {
      return fs.statSync(target).isDirectory();
    } catch (err) {
      return false;
    }
  }

  // The containment check resolves symlinks first: a link planted inside the
  // plugin that points elsewhere is exactly the escape it exists to catch.
  function realInsideRoot(target) {
    let real;
    try {
      real = fs.realpathSync(target);
    } catch (err) {
      return null;
    }
    const rootPath = root();
    return real === rootPath || real.indexOf(rootPath + path.sep) === 0 ? real : null;
  }

  function resolveModule(target) {
    if (isFile(target)) return target;
    if (isFile(target + '.js')) return target + '.js';
    if (isFile(target + '.json')) return target + '.json';
    if (isDirectory(target)) {
      const manifestPath = path.join(target, 'package.json');
      if (isFile(manifestPath)) {
        try {
          const parsed = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
          const main = parsed && typeof parsed.main === 'string' ? parsed.main : null;
          if (main) {
            const resolved = resolveModule(path.resolve(target, main));
            if (resolved) return resolved;
          }
        } catch (err) {
          // A broken package.json falls through to index, which is where node
          // lands too.
        }
      }
      if (isFile(path.join(target, 'index.js'))) return path.join(target, 'index.js');
      if (isFile(path.join(target, 'index.json'))) return path.join(target, 'index.json');
    }
    return null;
  }

  function loadResolved(real, spec) {
    const cached = cache.get(real);
    if (cached) return cached.exports;
    if (real.slice(-5) === '.json') {
      let parsed;
      try {
        parsed = JSON.parse(fs.readFileSync(real, 'utf8'));
      } catch (err) {
        throw new Error("plugin require could not parse '" + spec + "': " + describe(err));
      }
      const record = { exports: parsed };
      cache.set(real, record);
      return parsed;
    }
    return executeModule(real).exports;
  }

  function executeModule(real) {
    const record = { exports: {} };
    // Cached before running, the way node does it: a circular require sees
    // the partial exports rather than an infinite loop.
    cache.set(real, record);
    let source = fs.readFileSync(real, 'utf8');
    if (source.charCodeAt(0) === 0xfeff) source = source.slice(1); // a BOM is not code
    const dir = path.dirname(real);
    sandbox.__roomModuleRun = [record.exports, makeRequire(dir), record, real, dir];
    try {
      vm.runInContext(
        '(function (exports, require, module, __filename, __dirname) {\n' +
          source +
          '\n}).apply(undefined, __roomModuleRun)',
        context,
        { filename: real, timeout: syncCap }
      );
    } finally {
      delete sandbox.__roomModuleRun;
    }
    return record;
  }

  function makeRequire(dir) {
    return function requireShim(spec) {
      if (typeof spec !== 'string' || !spec) {
        throw new Error('plugin require needs a module name');
      }
      if (isBuiltinName(spec)) {
        if (BUILTIN_WHITELIST.indexOf(spec) >= 0) return require(spec);
        throw new Error(
          "plugin require is not allowed to load '" + spec + "': the sandbox allows only these " +
            'built-ins: ' + BUILTIN_WHITELIST.join(', ')
        );
      }
      if (path.isAbsolute(spec)) {
        throw new Error(
          "plugin require refuses to load '" + spec + "': absolute paths are not allowed"
        );
      }
      const relative =
        spec.indexOf('./') === 0 ||
        spec.indexOf('../') === 0 ||
        spec === '.' ||
        spec === '..';
      if (relative) {
        const resolved = resolveModule(path.resolve(dir, spec));
        if (!resolved) throw new Error("plugin require cannot find module '" + spec + "'");
        return loadFrom(resolved, spec);
      }
      // A bare name is a bundled package: node_modules is walked from the
      // requiring module up to the plugin root, and never past it.
      let base = dir;
      for (;;) {
        const candidate = resolveModule(path.join(base, 'node_modules', spec));
        if (candidate) return loadFrom(candidate, spec);
        const parent = path.dirname(base);
        if (parent === base || !insideRoot(parent)) break;
        base = parent;
      }
      throw new Error(
        "plugin require cannot find module '" + spec + "': it is not one of the allowed " +
          'built-ins and not a package bundled in the plugin'
      );
    };
  }

  function insideRoot(target) {
    const resolved = path.resolve(target);
    const rootPath = root();
    return resolved === rootPath || resolved.indexOf(rootPath + path.sep) === 0;
  }

  function loadFrom(target, spec) {
    const real = realInsideRoot(target);
    if (real === null) {
      throw new Error(
        "plugin require refuses to load '" + spec + "': it resolves outside the plugin directory"
      );
    }
    return loadResolved(real, spec);
  }

  function loadEntry() {
    const target = path.resolve(pluginRoot, entry);
    const resolved = resolveModule(target);
    if (!resolved || realInsideRoot(resolved) === null) {
      throw new Error("the plugin's entry '" + entry + "' was not found in the plugin directory");
    }
    return loadResolved(realInsideRoot(resolved), entry);
  }

  // ---- the room SDK ----

  // The namespaces exist even without the grant and every call on them
  // throws: a plugin that forgot its permission reads exactly which one,
  // instead of reading a missing key and guessing.
  function gated(permission, fn) {
    return function () {
      if (!granted.has(permission)) {
        throw new Error('plugin permission not granted: ' + permission);
      }
      return fn.apply(this, arguments);
    };
  }

  const configValues = (function () {
    const values = Object.assign({}, request.config);
    if (
      event === 'config' &&
      payload.values &&
      typeof payload.values === 'object' &&
      !Array.isArray(payload.values)
    ) {
      Object.assign(values, payload.values);
    }
    return values;
  })();

  function configGet(key) {
    if (typeof key !== 'string') return undefined;
    return Object.prototype.hasOwnProperty.call(configValues, key) ? configValues[key] : undefined;
  }

  function kvLoad() {
    if (state.kv !== undefined) return state.kv;
    let parsed = {};
    try {
      parsed = JSON.parse(fs.readFileSync(kvPath, 'utf8'));
    } catch (err) {
      // A store nobody can read is an empty one; the warning says what
      // happened rather than letting it look like nothing was ever saved.
      if (!err || err.code !== 'ENOENT') {
        logLine('warn', 'kv.json could not be read and was started fresh: ' + describe(err));
      }
    }
    state.kv =
      parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
    return state.kv;
  }

  // kv.json is the one file two calls in a row both depend on, so it is
  // written the way a crash cannot half-finish: a temp file beside it, then
  // the rename.
  function kvSave(text) {
    fs.mkdirSync(dataRoot, { recursive: true });
    const temp = kvPath + '.tmp-' + crypto.randomBytes(6).toString('hex');
    fs.writeFileSync(temp, text);
    try {
      fs.renameSync(temp, kvPath);
    } catch (err) {
      try {
        fs.unlinkSync(temp);
      } catch (ignored) {
        // The rename failed; the temp file failing to leave is noise on top.
      }
      throw err;
    }
  }

  const kvApi = {
    get: gated('storage', function (key) {
      const store = kvLoad();
      return Object.prototype.hasOwnProperty.call(store, key) ? store[key] : null;
    }),
    set: gated('storage', function (key, value) {
      if (typeof key !== 'string' || !key) {
        throw new Error('kv keys must be non-empty strings');
      }
      let encoded;
      try {
        encoded = JSON.stringify(value);
      } catch (err) {
        encoded = undefined;
      }
      if (encoded === undefined) {
        throw new Error('kv values must be JSON values');
      }
      if (encoded.length > KV_MAX_VALUE) {
        throw new Error(
          'plugin kv quota exceeded: a value may hold at most ' + KV_MAX_VALUE +
            ' characters, this one needs ' + encoded.length
        );
      }
      const next = Object.assign({}, kvLoad());
      next[key] = value;
      if (Object.keys(next).length > KV_MAX_KEYS) {
        throw new Error('plugin kv quota exceeded: at most ' + KV_MAX_KEYS + ' keys');
      }
      const text = JSON.stringify(next);
      if (text.length > KV_MAX_BYTES) {
        throw new Error(
          'plugin kv quota exceeded: the store may hold at most ' + KV_MAX_BYTES + ' characters'
        );
      }
      state.kv = next;
      kvSave(text);
    }),
    delete: gated('storage', function (key) {
      const next = Object.assign({}, kvLoad());
      if (Object.prototype.hasOwnProperty.call(next, key)) {
        delete next[key];
        state.kv = next;
        kvSave(JSON.stringify(next));
      }
    }),
    clear: gated('storage', function () {
      state.kv = {};
      kvSave('{}');
    }),
    keys: gated('storage', function () {
      return Object.keys(kvLoad());
    }),
    size: gated('storage', function () {
      return Object.keys(kvLoad()).length;
    }),
  };

  function fileName(name) {
    if (
      typeof name !== 'string' ||
      !FILE_NAME.test(name) ||
      name === '.' ||
      name === '..'
    ) {
      throw new Error(
        "plugin file names must match ^[A-Za-z0-9._-]{1,96}$ and hold no separators: '" +
          String(name) + "' does not"
      );
    }
    return name;
  }

  function filePathOf(name) {
    return path.join(filesDir, fileName(name));
  }

  function fileSizeOf(name) {
    try {
      return fs.statSync(filePathOf(name)).size;
    } catch (err) {
      return null;
    }
  }

  // The quota is about what is on disk, not what this call remembers writing,
  // so it is measured from the directory every time.
  function filesUsage() {
    let count = 0;
    let bytes = 0;
    let names;
    try {
      names = fs.readdirSync(filesDir);
    } catch (err) {
      return { count: 0, bytes: 0 };
    }
    for (let index = 0; index < names.length; index++) {
      try {
        const stat = fs.statSync(path.join(filesDir, names[index]));
        if (stat.isFile()) {
          count++;
          bytes += stat.size;
        }
      } catch (err) {
        // A file that vanished mid-count was not ours to measure.
      }
    }
    return { count: count, bytes: bytes };
  }

  function filesWrite(name, data) {
    fileName(name);
    const existing = fileSizeOf(name);
    const usage = filesUsage();
    if (existing === null && usage.count >= FILES_MAX_COUNT) {
      throw new Error('plugin files quota exceeded: at most ' + FILES_MAX_COUNT + ' files');
    }
    const total = usage.bytes - (existing || 0) + data.length;
    if (total > FILES_MAX_BYTES) {
      throw new Error(
        'plugin files quota exceeded: the directory may hold at most ' + FILES_MAX_BYTES + ' bytes'
      );
    }
    fs.mkdirSync(filesDir, { recursive: true });
    fs.writeFileSync(filePathOf(name), data);
  }

  function decodeBase64(text) {
    if (typeof text !== 'string') throw new Error('writeBytes() takes base64 text');
    const cleaned = text.replace(/\s+/g, '');
    if (!/^[A-Za-z0-9+/]*={0,2}$/.test(cleaned)) {
      throw new Error('writeBytes() was given text that is not base64');
    }
    return Buffer.from(cleaned, 'base64');
  }

  const filesApi = {
    read: gated('storage', function (name) {
      try {
        return fs.readFileSync(filePathOf(name), 'utf8');
      } catch (err) {
        return null;
      }
    }),
    readBytes: gated('storage', function (name) {
      try {
        return fs.readFileSync(filePathOf(name)).toString('base64');
      } catch (err) {
        return null;
      }
    }),
    write: gated('storage', function (name, text) {
      if (typeof text !== 'string') {
        throw new Error('write() takes text; writeBytes() is the bytes side of it');
      }
      filesWrite(name, Buffer.from(text, 'utf8'));
    }),
    writeBytes: gated('storage', function (name, base64) {
      filesWrite(name, decodeBase64(base64));
    }),
    append: gated('storage', function (name, text) {
      if (typeof text !== 'string') throw new Error('append() takes text');
      let current = Buffer.alloc(0);
      try {
        current = fs.readFileSync(filePathOf(name));
      } catch (err) {
        // Absent is the ordinary first append.
      }
      filesWrite(name, Buffer.concat([current, Buffer.from(text, 'utf8')]));
    }),
    delete: gated('storage', function (name) {
      try {
        fs.unlinkSync(filePathOf(name));
      } catch (err) {
        // Absent is already deleted.
      }
    }),
    exists: gated('storage', function (name) {
      return isFile(filePathOf(name));
    }),
    list: gated('storage', function () {
      try {
        return fs.readdirSync(filesDir).sort();
      } catch (err) {
        return [];
      }
    }),
    size: gated('storage', function (name) {
      return fileSizeOf(name);
    }),
  };

  // room.net.fetch runs here, in the host realm, over real http/https: the
  // context has neither. Under the bridge the profile's user-agent rule is
  // already wrapped around these modules, which is what carries it to plugin
  // requests too.
  function pluginFetch(url, options) {
    return new Promise(function (resolve, reject) {
      let target;
      try {
        target = new URL(String(url));
      } catch (err) {
        reject(new Error('room.net.fetch needs an absolute URL: ' + describe(err)));
        return;
      }
      if (target.protocol !== 'http:' && target.protocol !== 'https:') {
        reject(new Error("room.net.fetch speaks http and https only, not '" + target.protocol + "'"));
        return;
      }
      if (state.netBytes >= NET_TOTAL_MAX) {
        reject(
          new Error(
            'plugin network quota exceeded: this call has already read ' + NET_TOTAL_MAX +
              ' bytes of responses'
          )
        );
        return;
      }
      const opts = options && typeof options === 'object' ? options : {};
      let method = 'GET';
      if (typeof opts.method === 'string' && opts.method.trim()) {
        method = opts.method.trim().toUpperCase();
      }
      const headers = {};
      if (opts.headers && typeof opts.headers === 'object' && !Array.isArray(opts.headers)) {
        const names = Object.keys(opts.headers);
        for (let index = 0; index < names.length; index++) {
          const value = opts.headers[names[index]];
          if (typeof value !== 'string' && typeof value !== 'number') {
            reject(new Error('room.net.fetch header values must be strings: ' + names[index]));
            return;
          }
          headers[names[index]] = String(value);
        }
      }
      let body;
      if (opts.body !== undefined && opts.body !== null) {
        if (typeof opts.body !== 'string') {
          reject(new Error('room.net.fetch bodies must be strings'));
          return;
        }
        body = opts.body;
      }
      const timeoutMs =
        Number.isFinite(opts.timeoutMs) && opts.timeoutMs > 0
          ? Math.min(Math.floor(opts.timeoutMs), NET_TIMEOUT_MAX)
          : NET_TIMEOUT_MAX;

      perform(0);

      function perform(hop) {
        // The allowlist is checked before the request, and again on every
        // redirect: a hop to a host the plugin never named is a hop refused.
        if (!matchesAllowHost(target.hostname, allow)) {
          reject(
            new Error(
              "room.net.fetch refused '" + target.hostname + "': the plugin's network allowlist " +
                'does not include it'
            )
          );
          return;
        }
        const transport = target.protocol === 'https:' ? https : http;
        const request = transport.request(
          {
            protocol: target.protocol,
            hostname: target.hostname,
            port: target.port || undefined,
            path: target.pathname + target.search,
            method: method,
            headers: headers,
          },
          onResponse
        );
        request.setTimeout(timeoutMs, function () {
          request.destroy(new Error('the request timed out after ' + timeoutMs + 'ms'));
        });
        request.on('error', function (err) {
          reject(new Error('room.net.fetch failed: ' + describe(err)));
        });
        if (body !== undefined && method !== 'GET' && method !== 'HEAD') request.write(body);
        request.end();

        function onResponse(response) {
          const status = response.statusCode || 0;
          const location = response.headers.location;
          if (
            hop < NET_REDIRECTS &&
            [301, 302, 303, 307, 308].indexOf(status) >= 0 &&
            typeof location === 'string' &&
            location
          ) {
            response.resume(); // a redirect's body is not worth reading
            let next;
            try {
              next = new URL(location, target);
            } catch (err) {
              reject(new Error('room.net.fetch got a redirect it could not follow: ' + describe(err)));
              return;
            }
            // 303 always, and 301/302 for anything that was not a GET, become
            // GET — the same lossy history browsers have.
            if (
              status === 303 ||
              ((status === 301 || status === 302) && method !== 'GET' && method !== 'HEAD')
            ) {
              method = 'GET';
            }
            target = next;
            perform(hop + 1);
            return;
          }
          collectBody(response, function (err, chunks, truncated) {
            if (err) {
              reject(new Error('room.net.fetch failed: ' + describe(err)));
              return;
            }
            let bytes = 0;
            for (let index = 0; index < chunks.length; index++) bytes += chunks[index].length;
            state.netBytes += bytes;
            if (state.netBytes > NET_TOTAL_MAX) {
              reject(
                new Error(
                  'plugin network quota exceeded: at most ' + NET_TOTAL_MAX +
                    ' response bytes per call'
                )
              );
              return;
            }
            if (truncated) {
              logLine(
                'warn',
                "the response from '" + target.hostname + "' was capped at " + NET_BODY_MAX + ' bytes'
              );
            }
            const flat = {};
            const names = Object.keys(response.headers);
            for (let index = 0; index < names.length; index++) {
              const value = response.headers[names[index]];
              flat[names[index]] = Array.isArray(value) ? value.join(', ') : String(value);
            }
            const text = Buffer.concat(chunks).toString('utf8');
            resolve({
              ok: status >= 200 && status < 300,
              status: status,
              headers: flat,
              body: text,
              json: function () {
                try {
                  return JSON.parse(text);
                } catch (err) {
                  return null;
                }
              },
            });
          });
        }
      }

      function collectBody(response, onDone) {
        const chunks = [];
        let bytes = 0;
        let done = false;
        response.on('data', function (chunk) {
          if (done) return;
          if (bytes + chunk.length > NET_BODY_MAX) {
            const keep = NET_BODY_MAX - bytes;
            if (keep > 0) chunks.push(chunk.slice(0, keep));
            done = true;
            onDone(null, chunks, true);
            response.destroy();
            return;
          }
          chunks.push(chunk);
          bytes += chunk.length;
        });
        response.on('end', function () {
          if (!done) {
            done = true;
            onDone(null, chunks, false);
          }
        });
        response.on('error', function (err) {
          if (!done) {
            done = true;
            onDone(err, chunks, false);
          }
        });
      }
    });
  }

  const netApi = {
    fetch: gated('network', pluginFetch),
    allowed: gated('network', function (host) {
      return matchesAllowHost(host, allow);
    }),
  };

  function historyEntries() {
    const entries = [];
    // Tool and system turns are the app's business, not context: what a
    // plugin may read is the conversation itself.
    for (let index = 0; index < request.history.length; index++) {
      const item = request.history[index];
      if (
        item &&
        typeof item === 'object' &&
        (item.role === 'user' || item.role === 'assistant') &&
        typeof item.text === 'string'
      ) {
        entries.push({ role: item.role, text: item.text });
      }
    }
    return entries;
  }

  const chatApi = {
    history: gated('chat.context', function (limit) {
      const cap =
        Number.isFinite(limit) && limit > 0
          ? Math.min(Math.floor(limit), HISTORY_LIMIT_MAX)
          : HISTORY_LIMIT_MAX;
      return historyEntries().slice(-cap);
    }),
    summary: gated('chat.context', function () {
      const text = historyEntries()
        .map(function (entry) { return entry.role + ': ' + entry.text; })
        .join('\n');
      return text.length <= HISTORY_SUMMARY_CHARS ? text : text.slice(-HISTORY_SUMMARY_CHARS);
    }),
  };

  // room.agent.generate: THIS chat's own model, asked to write one piece of
  // text. The permission names the grant; the channel comes with the call —
  // the bridge's reverse-request wiring on a device, a fake in the desktop
  // harness, nothing in a host that cannot generate. The prompt itself is the
  // app's business: this side shapes and clamps the structured request, and
  // re-validates the answer, because the app does not guarantee the style
  // constraints it was told.
  const agentApi = {
    generate: gated('agent.generate', async function (options) {
      const payload = shapeGenerateRequest(options);
      if (typeof request.askAgent !== 'function') {
        throw new Error('agent generation is not available in this host');
      }
      const answer = await request.askAgent(payload);
      const text = answer && typeof answer.text === 'string' ? answer.text.trim() : '';
      if (!text) {
        throw new Error('the chat agent wrote no text');
      }
      return clipPlain(text, GEN_ANSWER_MAX);
    }),
  };

  function pushCard(card) {
    if (!card) return;
    if (state.cards.length >= CARD_MAX_COUNT) {
      if (!state.cardDropLine) {
        state.cardDropLine = true;
        logLine(
          'warn',
          'the plugin sent more than ' + CARD_MAX_COUNT + ' cards; the extras were dropped'
        );
      }
      return;
    }
    state.cards.push(card);
  }

  const uiApi = {
    card: function (input) {
      pushCard(sanitizeCard(input, logLine));
    },
    text: function (text) {
      pushCard(sanitizeCard({ text: String(text) }, logLine));
    },
  };

  const logApi = {
    info: function (message) { logLine('info', typeof message === 'string' ? message : describe(message)); },
    warn: function (message) { logLine('warn', typeof message === 'string' ? message : describe(message)); },
    error: function (message) { logLine('error', typeof message === 'string' ? message : describe(message)); },
  };

  const room = {
    meta: Object.freeze({
      pluginId: manifest.id,
      pluginName: manifest.name,
      pluginVersion: manifest.version,
      sessionId: sessionIdOf(dataRoot),
      apiVersion: API_VERSION,
      event: event,
      // The identity this profile presents — the same string room.net.fetch
      // already rides as, now visible to the plugin instead of only enforced;
      // null when the app supplied none.
      userAgent: request.userAgent,
    }),
    config: Object.freeze({ get: configGet }),
    storage: Object.freeze({
      kv: Object.freeze(kvApi),
      files: Object.freeze(filesApi),
    }),
    net: Object.freeze(netApi),
    chat: Object.freeze(chatApi),
    agent: Object.freeze(agentApi),
    ui: Object.freeze(uiApi),
    log: Object.freeze(logApi),
    util: Object.freeze({
      now: function () { return Date.now(); },
      uuid: function () { return crypto.randomUUID(); },
      b64encode: function (text) { return Buffer.from(String(text), 'utf8').toString('base64'); },
      b64decode: function (base64) { return Buffer.from(String(base64), 'base64').toString('utf8'); },
    }),
  };
  Object.freeze(room);

  // The handler call is made from inside the context, under the timeout, and
  // its promise is awaited out here, under the budget. The one argument after
  // room is the event's own: raw command text, the message, parsed tool args
  // — and for deactivate, the reason, which would otherwise be the one thing
  // §5 defines that a plugin can never read.
  function invoke(handler, arg) {
    sandbox.__roomHandlerCall = [handler, room, arg];
    try {
      return vm.runInContext(
        '__roomApplyHandler.apply(null, __roomHandlerCall)',
        context,
        { filename: 'room-plugin-handler.js', timeout: syncCap }
      );
    } finally {
      delete sandbox.__roomHandlerCall;
    }
  }

  function teardown() {
    const handles = Array.from(state.timers.keys());
    for (let index = 0; index < handles.length; index++) {
      const handle = handles[index];
      try {
        if (state.timers.get(handle)) clearInterval(handle);
        else clearTimeout(handle);
      } catch (err) {
        // A timer that was already gone is gone.
      }
    }
    state.timers.clear();
    delete sandbox.__roomModuleRun;
    delete sandbox.__roomHandlerCall;
    delete sandbox.__roomApplyHandler;
  }

  return {
    request: request,
    event: event,
    payload: payload,
    granted: granted,
    startedAt: startedAt,
    budgetMs: budgetMs,
    state: state,
    logLine: logLine,
    pushCard: pushCard,
    invoke: invoke,
    loadEntry: loadEntry,
    teardown: teardown,
    exports: undefined, // filled once the entry has loaded
  };
}

// ---- dispatch ----------------------------------------------------------------

async function runPluginEvent(call) {
  const entryExports = call.loadEntry();
  call.exports = entryExports;
  switch (call.event) {
    case 'probe':
      // Introspection only; no handler is ever called on a probe.
      return undefined;
    case 'activate':
      if (typeof entryExports.activate === 'function') {
        return await call.invoke(entryExports.activate);
      }
      return undefined;
    case 'deactivate':
      // Best-effort by the design's word: the host still reports a throw
      // honestly, and it is the app's side that chooses to carry on.
      if (typeof entryExports.deactivate === 'function') {
        return await call.invoke(entryExports.deactivate, call.payload.reason);
      }
      return undefined;
    case 'command': {
      const name = call.payload.name;
      if (typeof name !== 'string' || !name) {
        throw new Error('the command event carried no command name');
      }
      const command =
        entryExports.commands && typeof entryExports.commands === 'object'
          ? entryExports.commands[name]
          : null;
      if (!command || typeof command.run !== 'function') {
        throw new Error("the plugin does not define command '" + name + "'");
      }
      const args = typeof call.payload.args === 'string' ? call.payload.args : '';
      return await call.invoke(command.run, args);
    }
    case 'message': {
      if (typeof entryExports.onMessage !== 'function') {
        throw new Error('the plugin declared the message hook but exports.onMessage is not a function');
      }
      return await call.invoke(entryExports.onMessage, call.payload);
    }
    case 'tool': {
      // The one event that is itself a capability: a tool nobody consented to
      // is not run at all, whatever the manifest asked for.
      if (!call.granted.has('agent.tools')) {
        throw new Error('plugin permission not granted: agent.tools');
      }
      const name = call.payload.name;
      if (typeof name !== 'string' || !name) {
        throw new Error('the tool event carried no tool name');
      }
      const tool =
        entryExports.tools && typeof entryExports.tools === 'object'
          ? entryExports.tools[name]
          : null;
      if (!tool || typeof tool.run !== 'function') {
        throw new Error("the plugin does not define tool '" + name + "'");
      }
      let args = {};
      if (typeof call.payload.argsJson === 'string' && call.payload.argsJson.trim()) {
        try {
          args = JSON.parse(call.payload.argsJson);
        } catch (err) {
          throw new Error('the tool arguments were not valid JSON: ' + describe(err));
        }
      }
      return await call.invoke(tool.run, args);
    }
    case 'config':
      // The new values are already what room.config reads; the hook is the
      // plugin's chance to react, not its only way to see them.
      if (typeof entryExports.onConfig === 'function') {
        return await call.invoke(entryExports.onConfig);
      }
      return undefined;
    default:
      throw new Error('unknown event "' + call.event + '"');
  }
}

// A handler's return value is cards for everything but the tool event, whose
// return value is the tool result itself. The same path room.ui takes shapes
// them, so clamping and the 5-card cap hold no matter which side sent it.
function acceptReturnValue(call, value) {
  if (value === undefined || value === null) return;
  if (typeof value === 'string') {
    call.pushCard(sanitizeCard({ text: value }, call.logLine));
    return;
  }
  if (Array.isArray(value)) {
    for (let index = 0; index < value.length; index++) {
      acceptReturnValue(call, value[index]);
    }
    return;
  }
  if (typeof value === 'object') {
    call.pushCard(sanitizeCard(value, call.logLine));
    return;
  }
  call.pushCard(sanitizeCard({ text: String(value) }, call.logLine));
}

function toolOutput(value) {
  if (typeof value === 'string') return clip(value, MAX_OUTPUT_CHARS);
  if (value === undefined || value === null) return '';
  let text;
  try {
    text = JSON.stringify(value, null, 2);
  } catch (err) {
    text = String(value);
  }
  if (text === undefined) text = String(value);
  return clip(text, MAX_OUTPUT_CHARS);
}

// ---- the answers -------------------------------------------------------------

function introspectionFor(call) {
  if (call.exports && (call.event === 'probe' || call.event === 'activate')) {
    return introspect(call.exports, call.logLine);
  }
  return { tools: [], commands: [], hooks: [] };
}

function successResult(call, value) {
  let output = '';
  if (call.event === 'tool') output = toolOutput(value);
  else acceptReturnValue(call, value);
  const seen = introspectionFor(call);
  return {
    ok: true,
    output: output,
    cards: call.state.cards,
    logs: call.state.logs,
    tools: seen.tools,
    commands: seen.commands,
    hooks: seen.hooks,
    ms: Date.now() - call.startedAt,
  };
}

function failureResult(call, err) {
  const seen = introspectionFor(call);
  return {
    ok: false,
    output: failureOutput(err),
    cards: call.state.cards,
    logs: call.state.logs,
    tools: seen.tools,
    commands: seen.commands,
    hooks: seen.hooks,
    ms: Date.now() - call.startedAt,
  };
}

function timeoutResult(call) {
  return {
    ok: false,
    output: 'the plugin was still running after ' + call.budgetMs + 'ms and was stopped',
    cards: call.state.cards,
    logs: call.state.logs,
    tools: [],
    commands: [],
    hooks: [],
    ms: Date.now() - call.startedAt,
  };
}

// ---- the entry point -----------------------------------------------------------

// Never crashes the process it runs in: everything a call can throw or hang on
// is answered as a result or a rejection, and the rejection is a shaped Error
// the bridge turns into its error envelope.
async function runPluginCall(opts) {
  const startedAt = Date.now();
  const request = readRequest(opts);
  const call = newCall(request, startedAt);
  const outcome = await new Promise(function (resolve) {
    const timer = setTimeout(function () {
      resolve({ timedOut: true });
    }, call.budgetMs);
    // The work starts on a microtask so the budget timer is armed before any
    // of it runs; the synchronous half is bounded inside by V8's own timeout.
    Promise.resolve()
      .then(function () { return runPluginEvent(call); })
      .then(
        function (value) {
          clearTimeout(timer);
          resolve({ value: value });
        },
        function (err) {
          clearTimeout(timer);
          resolve({ error: err });
        }
      );
  });
  call.teardown();
  if (outcome.timedOut) return timeoutResult(call);
  if (outcome.error) return failureResult(call, outcome.error);
  return successResult(call, outcome.value);
}

module.exports = {
  runPluginCall: runPluginCall,
  matchesAllowHost: matchesAllowHost,
  shapeGenerateRequest: shapeGenerateRequest,
};
