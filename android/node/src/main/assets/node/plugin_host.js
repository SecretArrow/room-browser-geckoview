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

// ---- the zagents compatibility profile ---------------------------------------
//
// A module.json package runs under format === 'zagents': the same sandbox, the
// same per-chat data root, a SECOND SDK spelled the way ZAgents modules spell
// it. The numbers below are ZAgents' own (module spec E); the two places this
// host bounds what ZAgents leaves unbounded are marked in comments.

const MODULE_PERMISSIONS = ['storage', 'network', 'files', 'chat', 'agent'];

const Z_STORAGE_VALUE_MAX = 64 * 1024; // one value, JSON-encoded length
const Z_STORAGE_TOTAL_MAX = 1024 * 1024; // the whole kv store, JSON-encoded
const Z_STORAGE_KEYS_MAX = 4096; // Room's safety cap; ZAgents has none
const Z_HTTP_HEADERS_MAX = 24;
const Z_HTTP_VALUE_MAX = 8 * 1024;
const Z_HTTP_TIMEOUT_MAX = 60000; // the zagents per-request cap (room.net: 15 s)
const Z_BLOCKED_HEADERS = [
  'host', 'content-length', 'connection', 'accept-encoding',
  'transfer-encoding', 'upgrade', 'keep-alive', 'user-agent',
];
const Z_COMPOSE_DEFAULT_WORDS = 24;
const Z_COMPOSE_MIN_WORDS = 1;
const Z_COMPOSE_MAX_WORDS = 40;
const Z_COMPOSE_AVOID_MAX = 20;
const Z_COMPOSE_ATTEMPTS = 2;
const Z_OUTPUT_LINES = 400;
const Z_OUTPUT_BYTES = 32 * 1024;
// Room's bounds on a runaway print: ZAgents accumulates console lines without
// a cap and truncates only at composition, so these stay far above the caps
// that actually shape the output and only stop the pathological case.
const Z_LINE_MAX = 64 * 1024;
const Z_LINES_MAX = 1000;
// While a reverse request (agent_generate, user_input) is outstanding the
// call's deadline is paused; this is the backstop that ends a pause the
// bridge's own timeout somehow did not.
const REVERSE_HARD_CAP_MS = 360000;
const FORM_FIELDS_MAX = 12;
const FORM_TITLE_MAX = 64;
const FORM_DESCRIPTION_MAX = 200;

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

// ---- the zagents value formats ----------------------------------------------
//
// ZAgents' own `fmt` for console/log lines, and the composer gate it applies
// to every sentence the chat's model writes for a module — ported exactly
// (module spec E), because the samples and the app's prompt both lean on the
// exact violation names and repair behaviour.

function zFmt(value) {
  if (value === undefined) return 'undefined';
  if (value === null) return 'null';
  if (typeof value === 'string') return value;
  try {
    const json = JSON.stringify(value);
    return json === undefined ? String(value) : json;
  } catch (err) {
    return String(value);
  }
}

function zJoinArgs(args) {
  const parts = new Array(args.length);
  for (let index = 0; index < args.length; index++) parts[index] = zFmt(args[index]);
  return parts.join(' ');
}

const Z_FORBIDDEN_RE = /[!?:;\u2014\u2013]/;
const Z_EMOJI_RE = /[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}\u{2B00}-\u{2BFF}]/u;

function zNormalize(text) {
  return String(text).toLowerCase().replace(/\s+/g, ' ').trim();
}

function zWordCount(text) {
  const trimmed = String(text).trim();
  if (trimmed === '') return 0;
  return trimmed.split(/\s+/).length;
}

// Light pre-clean on raw model output before validation: trim, collapse
// whitespace, strip ONE layer of wrapping quotes/backticks.
function zPreClean(text) {
  let cleaned = String(text).trim().replace(/\s+/g, ' ');
  const quotes = ['"', "'", '`'];
  for (let index = 0; index < quotes.length; index++) {
    const quote = quotes[index];
    if (cleaned.length >= 2 && cleaned.charAt(0) === quote && cleaned.charAt(cleaned.length - 1) === quote) {
      cleaned = cleaned.slice(1, -1).trim();
    }
  }
  return cleaned;
}

// Every violation found, in the order ZAgents reports them (empty = the text
// passes the gate).
function zComposerViolations(text, maxWords, trailingPeriod, avoid) {
  const normalized = zNormalize(text);
  const problems = [];
  if (normalized === '') return ['empty output'];
  const body = trailingPeriod && normalized.slice(-1) === '.'
    ? normalized.slice(0, -1)
    : normalized;
  if (/[.!?;]/.test(body)) problems.push('sentence punctuation inside the text');
  if (Z_FORBIDDEN_RE.test(normalized)) problems.push('forbidden character');
  if (Z_EMOJI_RE.test(normalized)) problems.push('emoji');
  if (zWordCount(normalized) > maxWords) problems.push('over ' + maxWords + ' words');
  for (let index = 0; index < avoid.length; index++) {
    if (zNormalize(avoid[index]) === normalized && normalized !== '') {
      problems.push('repeats an earlier text');
      break;
    }
  }
  return problems;
}

// Deterministic last-resort repair: strip wrapping quotes, drop emoji, soften
// the forbidden characters, remove internal sentence punctuation, cut to the
// word budget. Not guaranteed to pass (a repeat cannot be repaired) — callers
// re-validate.
function zComposerSanitize(text, maxWords, trailingPeriod) {
  let cleaned = String(text).trim();
  const quotes = ['"', "'", '`'];
  for (let index = 0; index < quotes.length; index++) {
    const quote = quotes[index];
    if (cleaned.length >= 2 && cleaned.charAt(0) === quote && cleaned.charAt(cleaned.length - 1) === quote) {
      cleaned = cleaned.slice(1, -1).trim();
    }
  }
  cleaned = cleaned.replace(Z_EMOJI_RE, ' ');
  cleaned = cleaned.replace(/[:;\u2014\u2013]/g, ' -');
  cleaned = cleaned.replace(/[!?]/g, '');
  const endsWithPeriod = cleaned.slice(-1) === '.';
  let body = endsWithPeriod ? cleaned.slice(0, -1) : cleaned;
  body = body.replace(/\./g, ' ');
  cleaned = body + (trailingPeriod && endsWithPeriod ? '.' : '');
  cleaned = cleaned.replace(/\s+/g, ' ').trim();
  cleaned = cleaned.replace(/ - -+/g, ' - ').trim();
  const words = cleaned.split(/\s+/).filter(function (word) { return word !== ''; });
  if (words.length > maxWords) cleaned = words.slice(0, maxWords).join(' ');
  return cleaned.trim();
}

// The module message body cap, suffixes exactly as ZAgents spells them.
function zCapOutput(text) {
  let lines = text.split('\n');
  if (lines.length > Z_OUTPUT_LINES) {
    lines = lines.slice(0, Z_OUTPUT_LINES);
    lines.push('…[output truncated at ' + Z_OUTPUT_LINES + ' lines]');
  }
  let capped = lines.join('\n');
  if (capped.length > Z_OUTPUT_BYTES) {
    capped = capped.slice(0, Z_OUTPUT_BYTES) + '…[output truncated at ' + Z_OUTPUT_BYTES + ' bytes]';
  }
  return capped;
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

function manifestErrors(manifest, moduleFormat) {
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
  // The permission vocabulary is the format's own: plugin.json's five Room
  // names, or module.json's five ZAgents names. A module row carries the
  // ZAgents spelling on the wire, so the gate a zagents call runs behind is
  // the module vocabulary — exactly one of the two, never a mix.
  const vocabulary = moduleFormat ? MODULE_PERMISSIONS : PERMISSIONS;
  const permissions = manifest.permissions;
  if (
    !Array.isArray(permissions) ||
    permissions.length > 5 ||
    new Set(permissions).size !== permissions.length ||
    permissions.some(function (p) { return vocabulary.indexOf(p) < 0; })
  ) {
    errors.push('permissions must be unique and drawn from ' + vocabulary.join(', '));
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
  // The manifest format the row was installed from: 'plugin' (the default
  // the wire has always spoken) or 'zagents' (a module.json package, run
  // under the module SDK). Anything else is a malformed call, not a guess.
  let format = 'plugin';
  if (opts.format !== undefined && opts.format !== null) {
    if (opts.format !== 'plugin' && opts.format !== 'zagents') {
      throw new Error('the plugin call was malformed: format must be "plugin" or "zagents"');
    }
    format = opts.format;
  }
  const moduleFormat = format === 'zagents';
  const problems = manifestErrors(manifest, moduleFormat);
  if (problems.length > 0) {
    throw new Error('the plugin manifest failed re-validation: ' + problems.join('; '));
  }
  // The module.json fields the row kept verbatim: the staged-activation
  // message (present only when this run IS the activation of a staged
  // module), the module's own budget, and the display strings. Each is read
  // strictly — a wrong-typed field refuses the call rather than guessing what
  // the caller meant, the same fail-closed deal every other field gets.
  let prompt = null;
  if (opts.prompt !== undefined && opts.prompt !== null) {
    if (typeof opts.prompt !== 'string') {
      throw new Error('the plugin call was malformed: prompt must be a string');
    }
    prompt = opts.prompt;
  }
  let timeoutSeconds = null;
  if (opts.timeoutSeconds !== undefined && opts.timeoutSeconds !== null) {
    if (!Number.isInteger(opts.timeoutSeconds) || opts.timeoutSeconds < 1) {
      throw new Error('the plugin call was malformed: timeoutSeconds must be a whole number of seconds');
    }
    timeoutSeconds = Math.min(120, opts.timeoutSeconds);
  }
  let inputsValues = null;
  if (opts.inputsValues !== undefined && opts.inputsValues !== null) {
    if (typeof opts.inputsValues !== 'object' || Array.isArray(opts.inputsValues)) {
      throw new Error('the plugin call was malformed: inputsValues must be an object');
    }
    inputsValues = opts.inputsValues;
  }
  let stateRoot = null;
  if (opts.stateRoot !== undefined && opts.stateRoot !== null) {
    if (typeof opts.stateRoot !== 'string' || !path.isAbsolute(opts.stateRoot)) {
      throw new Error('the plugin call was malformed: stateRoot must be an absolute path');
    }
    stateRoot = opts.stateRoot;
  }
  const displayFields = { title: 'title', zagentsVersion: 'zagentsVersion', zagentsName: 'zagentsName' };
  const display = {};
  for (const field of Object.keys(displayFields)) {
    if (opts[field] !== undefined && opts[field] !== null) {
      if (typeof opts[field] !== 'string') {
        throw new Error('the plugin call was malformed: ' + displayFields[field] + ' must be a string');
      }
      display[field] = opts[field];
    }
  }
  // The npm store's install root, when the caller has one. Read strictly —
  // a wrong-typed field refuses the call rather than being ignored — and
  // required to be absolute for the same reason stateRoot is: the shim uses
  // it as a containment ROOT, and a relative root would be resolved against
  // whatever the process's cwd happens to be.
  let nodeModulesRoot = null;
  if (opts.nodeModulesRoot !== undefined && opts.nodeModulesRoot !== null) {
    if (typeof opts.nodeModulesRoot !== 'string' || !path.isAbsolute(opts.nodeModulesRoot)) {
      throw new Error(
        'the plugin call was malformed: nodeModulesRoot must be an absolute path'
      );
    }
    nodeModulesRoot = opts.nodeModulesRoot;
  }
  const payload =
    opts.payload && typeof opts.payload === 'object' && !Array.isArray(opts.payload)
      ? opts.payload
      : {};
  const config =
    opts.config && typeof opts.config === 'object' && !Array.isArray(opts.config)
      ? opts.config
      : {};
  // A module's budget is its own timeoutSeconds (clamped above to 1..120 s);
  // everything else keeps the timeoutMs the caller sent, or the event's
  // default. The module's number wins when both arrive, which is also what
  // the app's own read timeout derives from, so the two never disagree.
  let timeoutMs;
  if (moduleFormat && timeoutSeconds !== null) {
    timeoutMs = timeoutSeconds * 1000;
  } else if (Number.isFinite(opts.timeoutMs) && opts.timeoutMs > 0) {
    timeoutMs = opts.timeoutMs;
  } else if (event === 'deactivate') {
    timeoutMs = DEACTIVATE_BUDGET_MS;
  } else {
    timeoutMs = DEFAULT_BUDGET_MS;
  }
  return {
    pluginRoot: pluginRoot,
    dataRoot: dataRoot,
    entry: entry,
    manifest: manifest,
    event: event,
    payload: payload,
    config: config,
    timeoutMs: timeoutMs,
    format: format,
    zagents: moduleFormat,
    prompt: prompt,
    timeoutSeconds: timeoutSeconds,
    inputsValues: inputsValues,
    stateRoot: stateRoot,
    title: display.title !== undefined ? display.title : null,
    zagentsVersion: display.zagentsVersion !== undefined ? display.zagentsVersion : null,
    zagentsName: display.zagentsName !== undefined ? display.zagentsName : null,
    nodeModulesRoot: nodeModulesRoot,
    // The wire carries no history of its own (a §6 gap worth one report line,
    // not one invented field): room.chat reads it from here when a caller has
    // some, and answers an empty chat when it does not.
    history: Array.isArray(opts.history) ? opts.history : [],
    // The profile's own user agent as the command carried it — null when the
    // app supplied none. room.meta.userAgent shows the plugin what its
    // requests ride as; nothing else reads it, so the string arrives cleaned
    // exactly once, here. For a module the same string is what zagents.info
    // reports and what its requests ride as on the device.
    userAgent:
      typeof opts.userAgent === 'string' && opts.userAgent.trim()
        ? opts.userAgent.trim()
        : null,
    // The reverse-request channels room.agent.generate (and, for a module,
    // zagents.agent.compose) and the runtime form speak over. Absent (a host
    // that wired none) is a state each namespace reports in words rather
    // than by hanging — the same trade every gated call makes.
    askAgent: typeof opts.askAgent === 'function' ? opts.askAgent : null,
    askUser: typeof opts.askUser === 'function' ? opts.askUser : null,
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
  // The module sandbox root: the per-chat state directory a zagents-format
  // call gets (its zagents.fs). The app names it; a caller that did not gets
  // the layout's own spelling beside the data root, so the sandbox exists
  // even when the field was left off — never nowhere.
  const stateRoot = request.zagents
    ? request.stateRoot !== null
      ? request.stateRoot
      : path.join(dataRoot, 'state')
    : null;

  const state = {
    logs: [],
    logBytes: 0,
    cards: [],
    cardDropLine: false,
    netBytes: 0,
    kv: undefined, // loaded on first touch
    timers: new Map(), // handle -> isInterval
    formPending: false, // one outstanding form per call
    terminated: false, // set at teardown and at budget expiry
    // The module's output channels: chat.emit texts (each the line it wrote)
    // and the console/log lines, in arrival order, exactly as ZAgents
    // captures them. Composition — emits, then the logs after a blank line —
    // happens once, at answer time.
    zEmits: [],
    zLines: [],
  };

  // ---- the log channel ----
  //
  // A plugin call keeps its own bounded log (the envelope's `logs`); a module
  // call writes the channel its message body is composed from instead — the
  // ZAgents console, prefixes and all, bounded only by the runaway bounds
  // above and the output caps that apply at composition.
  function pluginLogLine(level, message) {
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

  function moduleLogLine(level, message) {
    if (state.zLines.length >= Z_LINES_MAX) return;
    const line =
      (level === 'warn' ? 'WARN ' : level === 'error' ? 'ERROR ' : '') + message;
    state.zLines.push(line.length > Z_LINE_MAX ? line.slice(0, Z_LINE_MAX) : line);
  }

  const logLine = request.zagents ? moduleLogLine : pluginLogLine;

  // ---- the sandbox context ----

  const sandbox = {};
  const consoleShim = (function () {
    // A module's console is the ZAgents console: arguments formatted with
    // its `fmt`, warn/error carrying its prefixes, everything landing in the
    // output channel. A plugin's keeps util.format — the format its `logs`
    // have always arrived in.
    const join = request.zagents
      ? function (parts) { return zJoinArgs(parts); }
      : function (parts) { return util.format.apply(util, parts); };
    function write(level) {
      return function () {
        const parts = new Array(arguments.length);
        for (let index = 0; index < parts.length; index++) parts[index] = arguments[index];
        logLine(level, join(parts));
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
    // A leading shebang is not code either — ZAgents strips it before
    // evaluating module sources, and a module that kept one would otherwise
    // die on its first character.
    if (source.charCodeAt(0) === 0x23 && source.charCodeAt(1) === 0x21) {
      const newline = source.indexOf('\n');
      source = newline >= 0 ? source.slice(newline + 1) : '';
    }
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

  // The module SDK as a virtual package: inside a zagents-format run
  // require('zagents-sdk') returns the injected global (the optional import
  // beside the ambient one), and everywhere else it is refused in the words
  // ZAgents' own shim uses — a plugin-format call is not a ZAgents chat
  // session, and pretending otherwise would just move the failure later.
  // Built below, before the entry ever loads.
  let moduleSdk = null;

  function makeRequire(dir) {
    return function requireShim(spec) {
      if (typeof spec !== 'string' || !spec) {
        throw new Error('plugin require needs a module name');
      }
      if (spec === 'zagents-sdk') {
        if (moduleSdk === null) {
          throw new Error(
            'zagents SDK is only available inside ZAgents chat sessions ' +
              '(run the module with /plugins or from the Modules screen).'
          );
        }
        return moduleSdk;
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
      // THE STORE FALLBACK. WHY: bundling every npm dependency in every
      // plugin ZIP is wasteful, and it keeps a plugin from using the packages
      // the app already installed beside its runtime (node_packages) — so
      // when the request carries nodeModulesRoot and the plugin's own tree
      // had no answer, the shim resolves from the store. THE GUARDS, the same
      // ones the plugin tree gets: bare names only (a relative require stays
      // inside the plugin by definition), realpath containment inside the
      // store root so a symlink planted in the store cannot point the load
      // elsewhere, and the shared module cache. READ-ONLY, full stop: the
      // shim only ever reads files, and the store is integrity-verified at
      // INSTALL time (see NodePackageStore); nothing is re-verified per
      // require, here or for the plugin's own bytes — both are already on
      // this device.
      const store = storeRoot();
      if (store !== null) {
        const storeCandidate = resolveModule(path.join(store, spec));
        if (storeCandidate) {
          const storeReal = realInsideStore(storeCandidate);
          if (storeReal !== null) return loadResolved(storeReal, spec);
        }
      }
      throw new Error(
        "plugin require cannot find module '" + spec + "': it is not one of the allowed " +
          'built-ins, not a package bundled in the plugin' +
          (store !== null ? ', and not installed beside the runtime' : '')
      );
    };
  }

  function insideRoot(target) {
    const resolved = path.resolve(target);
    const rootPath = root();
    return resolved === rootPath || resolved.indexOf(rootPath + path.sep) === 0;
  }

  // The store's realpath, or null when there is no store to fall back to —
  // computed once per call, and false-as-sentinel so a store that is not
  // there is remembered as absent rather than re-stat'd per require.
  let storeReal = null;
  function storeRoot() {
    if (storeReal === null) {
      storeReal = false;
      if (request.nodeModulesRoot) {
        try {
          storeReal = fs.realpathSync(request.nodeModulesRoot);
        } catch (err) {
          // The store is not on this device (or the caller passed a dead
          // path): the fallback is closed, and the error says so in words.
          storeReal = false;
        }
      }
    }
    return storeReal === false ? null : storeReal;
  }

  // The containment check against the STORE root, resolved through symlinks
  // first — the same shape the plugin tree's check is, because a link inside
  // the store that points elsewhere is exactly the same escape.
  function realInsideStore(target) {
    let real;
    try {
      real = fs.realpathSync(target);
    } catch (err) {
      return null;
    }
    const store = storeRoot();
    return store !== null && (real === store || real.indexOf(store + path.sep) === 0)
      ? real
      : null;
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
  //
  // A module call grants in the ZAgents vocabulary, so the room namespaces
  // — the superset the module may also use — accept each Room permission's
  // module counterpart ('chat' stands in for chat.context, 'agent' for
  // agent.generate, 'files' for the storage side of the flat file API).
  // Nothing widens: the wire set IS the grant either way.
  function roomGranted(permission) {
    if (granted.has(permission)) return true;
    if (!request.zagents) return false;
    if (permission === 'chat.context') return granted.has('chat');
    if (permission === 'agent.generate') return granted.has('agent');
    if (permission === 'storage') return granted.has('files');
    return false;
  }

  function gated(permission, fn) {
    return function () {
      if (!roomGranted(permission)) {
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
    });
  }

  // Shared by both fetch paths: collect the response body, capped at the
  // per-response quota, and say so when the cap did the cutting.
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

  // ---- the runtime form (both formats) -------------------------------------
  //
  // room.ui.form / zagents.ui.form: the mid-execution input request, riding
  // the reverse channel as `user_input` and answered by the app's dialog.
  // One outstanding form per call — the dialog is modal to the run, and a
  // second request while the first is up is a busy answer, not a queue. The
  // errors the app answers with (cancelled, timed out, no UI host) are the
  // words the module reads, verbatim.

  function askUserForm(options) {
    return Promise.resolve().then(function () {
      if (typeof request.askUser !== 'function') {
        throw new Error('No interactive UI is available for this call');
      }
      if (state.formPending) {
        throw new Error('a form is already pending for this call');
      }
      if (!options || typeof options !== 'object' || Array.isArray(options)) {
        throw new Error('the form request was malformed: expected an options object');
      }
      if (typeof options.title !== 'string' || !options.title.trim() || options.title.length > FORM_TITLE_MAX) {
        throw new Error('a form needs a title of at most ' + FORM_TITLE_MAX + ' characters');
      }
      if (!Array.isArray(options.fields) || options.fields.length < 1 || options.fields.length > FORM_FIELDS_MAX) {
        throw new Error('a form needs between 1 and ' + FORM_FIELDS_MAX + ' fields');
      }
      for (let index = 0; index < options.fields.length; index++) {
        const field = options.fields[index];
        if (!field || typeof field !== 'object' || Array.isArray(field)) {
          throw new Error('every form field must be an object');
        }
      }
      // The field items travel as given — the dialog and the validator they
      // describe live in the app, which owns the one schema both formats
      // share. The description is clamped here because it is presentation,
      // not schema: a long one is trimmed, not refused.
      const form = {
        title: options.title,
        fields: options.fields,
      };
      if (typeof options.description === 'string' && options.description) {
        form.description = clipPlain(options.description, FORM_DESCRIPTION_MAX);
      }
      state.formPending = true;
      return Promise.resolve(request.askUser(form)).then(
        function (answer) {
          state.formPending = false;
          // {values} is the envelope the reverse reply carries; an answer
          // without one is a protocol bug, not an empty form.
          if (answer && typeof answer === 'object' && answer.values && typeof answer.values === 'object') {
            return { values: answer.values };
          }
          return { values: {} };
        },
        function (err) {
          state.formPending = false;
          throw err;
        }
      );
    });
  }

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
    form: function (options) {
      return askUserForm(options);
    },
  };

  const logApi = {
    info: function (message) { logLine('info', typeof message === 'string' ? message : describe(message)); },
    warn: function (message) { logLine('warn', typeof message === 'string' ? message : describe(message)); },
    error: function (message) { logLine('error', typeof message === 'string' ? message : describe(message)); },
  };

  // A module call extends meta with what the module SDK is built from, so
  // the room global reports the same truths zagents.info does — the format,
  // the verbatim module strings, the staged prompt.
  const meta = {
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
  };
  if (request.zagents) {
    meta.format = 'zagents';
    meta.title = request.title !== null ? request.title : '';
    meta.zagentsName = request.zagentsName !== null ? request.zagentsName : manifest.name;
    meta.zagentsVersion = request.zagentsVersion !== null ? request.zagentsVersion : manifest.version;
    if (request.prompt !== null) meta.prompt = request.prompt;
  }
  // The form's stored answers ride EVERY format's call — the app sends them
  // for plugin rows exactly as for module rows, because inputs are a shared
  // feature — so a plugin's own code reads them off the same meta the module
  // SDK does. Only a call with answers to show carries the field: a plugin
  // that declares no form never sees it, and its meta stays the fields it
  // has always had.
  if (request.inputsValues !== null) meta.inputs = request.inputsValues;

  const room = {
    meta: Object.freeze(meta),
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

  // ---- the zagents SDK (module format) --------------------------------------
  //
  // The module's own API surface, built on the same per-chat root and the
  // same enforcement the room SDK already runs behind: the kv file under the
  // ZAgents quota profile, the state/ directory as a path-addressed sandbox,
  // the network layer with the module's exact coercion and error strings,
  // and the composer gate. Permission checks throw synchronously with
  // ZAgents' exact words; everything after the gate is a promise, exactly
  // the split its own shim makes (zDeny throws, the bridge replies ok:false).
  if (request.zagents) {
    function zGated(permission, fn) {
      return function () {
        if (!granted.has(permission)) {
          throw new Error('Permission denied: ' + permission);
        }
        const args = Array.prototype.slice.call(arguments);
        return Promise.resolve().then(function () { return fn.apply(undefined, args); });
      };
    }

    // -- storage: the same kv.json, the module's quota profile --

    const zStorageApi = {
      get: zGated('storage', function (key) {
        const store = kvLoad();
        const name = String(key);
        return Object.prototype.hasOwnProperty.call(store, name) ? store[name] : null;
      }),
      set: zGated('storage', function (key, value) {
        const name = String(key);
        if (name === '') throw new Error('Storage key must not be empty');
        const stored = value === undefined ? null : value;
        let encoded;
        try {
          encoded = JSON.stringify(stored);
        } catch (err) {
          encoded = undefined;
        }
        if (encoded === undefined) {
          throw new Error('Storage value could not be JSON-encoded');
        }
        if (encoded.length > Z_STORAGE_VALUE_MAX) {
          throw new Error('Storage value exceeds the ' + Z_STORAGE_VALUE_MAX + '-byte limit');
        }
        const next = Object.assign({}, kvLoad());
        next[name] = stored;
        if (Object.keys(next).length > Z_STORAGE_KEYS_MAX) {
          throw new Error('Module storage exceeds the ' + Z_STORAGE_KEYS_MAX + '-key budget');
        }
        const text = JSON.stringify(next);
        if (text.length > Z_STORAGE_TOTAL_MAX) {
          // The store is not written: the budget refusal leaves the file as
          // it was, which is the promise the error makes.
          throw new Error('Module storage exceeds the ' + Z_STORAGE_TOTAL_MAX + '-byte budget');
        }
        state.kv = next;
        kvSave(text);
      }),
      keys: zGated('storage', function () {
        return Object.keys(kvLoad());
      }),
      remove: zGated('storage', function (key) {
        const next = Object.assign({}, kvLoad());
        const name = String(key);
        if (Object.prototype.hasOwnProperty.call(next, name)) {
          delete next[name];
          state.kv = next;
          kvSave(JSON.stringify(next));
        }
      }),
    };

    // -- fs: the state/ directory as a path-addressed sandbox --

    let stateRootReal = null;
    function zStateRoot() {
      if (stateRootReal === null) {
        fs.mkdirSync(stateRoot, { recursive: true }); // created on demand
        stateRootReal = fs.realpathSync(stateRoot);
      }
      return stateRootReal;
    }

    // Relative paths resolve against the state directory — the documented
    // module contract (the original runtime resolves CWD-first, a latent bug
    // its own spec flags). Containment is checked on the REAL path, so a
    // symlink planted inside the state directory cannot point its way out;
    // a path that does not exist yet is checked through its nearest
    // existing ancestor.
    function zResolveFsPath(rawPath) {
      if (typeof rawPath !== 'string' || rawPath === '') {
        throw new Error('fs paths must be non-empty strings');
      }
      const rootPath = zStateRoot();
      const joined = path.isAbsolute(rawPath) ? path.normalize(rawPath) : path.resolve(rootPath, rawPath);
      let real = null;
      try {
        real = fs.realpathSync(joined);
      } catch (err) {
        real = null;
      }
      if (real !== null) {
        if (real !== rootPath && real.indexOf(rootPath + path.sep) !== 0) {
          throw new Error('fs path is outside the app sandbox');
        }
        return real;
      }
      let probe = joined;
      const tail = [];
      for (;;) {
        const parent = path.dirname(probe);
        if (parent === probe) break;
        tail.unshift(path.basename(probe));
        probe = parent;
        let parentReal = null;
        try {
          parentReal = fs.realpathSync(probe);
        } catch (err) {
          parentReal = null;
        }
        if (parentReal !== null) {
          if (parentReal !== rootPath && parentReal.indexOf(rootPath + path.sep) !== 0) {
            throw new Error('fs path is outside the app sandbox');
          }
          return path.join.apply(path, [parentReal].concat(tail));
        }
      }
      throw new Error('fs path is outside the app sandbox');
    }

    // The quota is about what is on disk, measured from the state directory
    // every time — the same deal the flat file API makes, over a tree.
    function zFsUsage() {
      let count = 0;
      let bytes = 0;
      const walk = function (dir) {
        let names;
        try {
          names = fs.readdirSync(dir);
        } catch (err) {
          return; // a directory that vanished mid-count was not ours to measure
        }
        for (let index = 0; index < names.length; index++) {
          const child = path.join(dir, names[index]);
          let stat = null;
          try {
            stat = fs.statSync(child);
          } catch (err) {
            stat = null;
          }
          if (stat === null) continue;
          if (stat.isDirectory()) walk(child);
          else if (stat.isFile()) {
            count++;
            bytes += stat.size;
          }
        }
      };
      walk(zStateRoot());
      return { count: count, bytes: bytes };
    }

    function zFsQuota(target, extraBytes) {
      const usage = zFsUsage();
      let existing = 0;
      let stat = null;
      try {
        stat = fs.statSync(target);
      } catch (err) {
        stat = null;
      }
      if (stat !== null && stat.isFile()) existing = stat.size;
      else if (stat === null && usage.count >= FILES_MAX_COUNT) {
        throw new Error('plugin files quota exceeded: at most ' + FILES_MAX_COUNT + ' files');
      }
      if (usage.bytes - existing + extraBytes > FILES_MAX_BYTES) {
        throw new Error(
          'plugin files quota exceeded: the directory may hold at most ' + FILES_MAX_BYTES + ' bytes'
        );
      }
    }

    const zFsApi = {
      readFile: zGated('files', function (rawPath) {
        return fs.readFileSync(zResolveFsPath(rawPath), 'utf8');
      }),
      writeFile: zGated('files', function (rawPath, data) {
        const text = String(data);
        const target = zResolveFsPath(rawPath);
        zFsQuota(target, Buffer.byteLength(text, 'utf8'));
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.writeFileSync(target, text);
      }),
      appendFile: zGated('files', function (rawPath, data) {
        const text = String(data);
        const target = zResolveFsPath(rawPath);
        let current = 0;
        try {
          current = fs.statSync(target).size;
        } catch (err) {
          current = 0; // absent is the ordinary first append
        }
        zFsQuota(target, current + Buffer.byteLength(text, 'utf8') - current);
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.appendFileSync(target, text);
      }),
      readdir: zGated('files', function (rawPath) {
        return fs.readdirSync(zResolveFsPath(rawPath));
      }),
      mkdir: zGated('files', function (rawPath) {
        fs.mkdirSync(zResolveFsPath(rawPath), { recursive: true });
      }),
      unlink: zGated('files', function (rawPath) {
        fs.rmSync(zResolveFsPath(rawPath), { recursive: true });
      }),
      stat: zGated('files', function (rawPath) {
        const stat = fs.statSync(zResolveFsPath(rawPath));
        return {
          isDirectory: stat.isDirectory(),
          size: stat.size,
          modified: stat.mtime.toISOString(),
        };
      }),
    };

    // -- http: the module's own coercion, headers and failure convention --

    function zSanitizeHeaders(raw) {
      const out = {};
      if (!raw || typeof raw !== 'object') return out;
      const names = Object.keys(raw);
      let kept = 0;
      for (let index = 0; index < names.length; index++) {
        if (kept >= Z_HTTP_HEADERS_MAX) break; // the loop stops, as ZAgents' does
        const name = String(names[index]).trim().toLowerCase();
        if (name === '' || Z_BLOCKED_HEADERS.indexOf(name) >= 0) continue;
        const value = raw[names[index]];
        const text = value === null || value === undefined ? '' : String(value);
        if (text.length > Z_HTTP_VALUE_MAX) continue;
        out[name] = text;
        kept++;
      }
      return out;
    }

    function zWrapResponse(status, body, rawHeaders) {
      const flat = {};
      const names = Object.keys(rawHeaders || {});
      for (let index = 0; index < names.length; index++) {
        const value = rawHeaders[names[index]];
        flat[names[index].toLowerCase()] = Array.isArray(value) ? value.join(', ') : String(value);
      }
      return Object.freeze({
        ok: status >= 200 && status < 300,
        status: status,
        body: body,
        headers: Object.freeze(flat),
        text: function () {
          return Promise.resolve(typeof body === 'string' ? body : JSON.stringify(body));
        },
        json: function () {
          return Promise.resolve().then(function () { return JSON.parse(body); });
        },
      });
    }

    // The network-failure convention: status 0, empty everything, ok:false —
    // an answer the module reads, never a rejection.
    function zNetFailure() {
      return zWrapResponse(0, '', {});
    }

    function zHttpRequest(url, opts) {
      const options = opts && typeof opts === 'object' ? opts : {};
      // The JS-side coercion is the module spec's own, exactly: the shapes
      // the bridge is specified to receive, whatever the module passed.
      const urlString = String(url);
      const method = options.method || 'GET';
      const body = options.body === undefined || options.body === null ? null : String(options.body);
      const rawTimeout = typeof options.timeoutMs === 'number' ? options.timeoutMs : null;
      const headOnly = options.headOnly === true;
      const headers = zSanitizeHeaders(options.headers || {});
      const timeoutMs =
        rawTimeout !== null && Number.isFinite(rawTimeout) && rawTimeout > 0
          ? Math.min(Z_HTTP_TIMEOUT_MAX, Math.floor(rawTimeout))
          : Z_HTTP_TIMEOUT_MAX;
      return new Promise(function (resolve, reject) {
        let target;
        try {
          target = new URL(urlString);
        } catch (err) {
          target = null;
        }
        // A URL that does not parse, or one with no host, never reaches the
        // transport: it is not a host the allowlist names.
        if (target === null || typeof target.hostname !== 'string' || target.hostname === '') {
          reject(new Error('Host is not allowed by this module (allowedHosts)'));
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
        perform(0);

        function perform(hop) {
          // The allowlist is checked before the request and again on every
          // redirect — a hop to a host the module never named is refused.
          if (!matchesAllowHost(target.hostname, allow)) {
            reject(new Error('Host is not allowed by this module (allowedHosts)'));
            return;
          }
          if (target.protocol !== 'http:' && target.protocol !== 'https:') {
            // A scheme the transport cannot speak is a transport failure.
            resolve(zNetFailure());
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
          request.on('error', function () {
            resolve(zNetFailure());
          });
          if (body !== null && method !== 'GET' && method !== 'HEAD') request.write(body);
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
                resolve(zNetFailure());
                return;
              }
              // 303 always, and 301/302 for anything that was not a GET,
              // become GET — the same lossy history browsers have.
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
            if (headOnly) {
              // The body is simply not read — the point of headOnly.
              response.destroy();
              resolve(zWrapResponse(status, '', response.headers));
              return;
            }
            collectBody(response, function (err, chunks, truncated) {
              if (err) {
                resolve(zNetFailure());
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
              resolve(zWrapResponse(status, Buffer.concat(chunks).toString('utf8'), response.headers));
            });
          }
        }
      });
    }

    // -- chat.emit: progressive output into the module message --

    function zEmit(text) {
      if (state.terminated) return Promise.resolve(); // delivery stopped, silently ok
      const line = String(text);
      if (state.zEmits.length < Z_LINES_MAX) {
        state.zEmits.push(line.length > Z_LINE_MAX ? line.slice(0, Z_LINE_MAX) : line);
      }
      return Promise.resolve();
    }

    // -- agent.compose: the gated pipeline over the reverse generate channel --

    function zAgentCompose(options) {
      const opts = options && typeof options === 'object' && !Array.isArray(options) ? options : {};
      const task = opts.task === undefined || opts.task === null ? '' : String(opts.task);
      const context = opts.context === undefined || opts.context === null ? '' : String(opts.context);
      const instruction =
        opts.instruction === undefined || opts.instruction === null ? '' : String(opts.instruction);
      const avoid = (Array.isArray(opts.avoid) ? opts.avoid.map(String) : [])
        .filter(function (text) { return text.trim() !== ''; })
        .slice(0, Z_COMPOSE_AVOID_MAX);
      let maxWords = Z_COMPOSE_DEFAULT_WORDS;
      if (typeof opts.maxWords === 'number' && Number.isInteger(opts.maxWords) && opts.maxWords > 0) {
        maxWords = Math.max(Z_COMPOSE_MIN_WORDS, Math.min(Z_COMPOSE_MAX_WORDS, opts.maxWords));
      }
      const trailingPeriod = opts.trailingPeriod === true;
      return Promise.resolve().then(function () {
        if (typeof request.askAgent !== 'function') {
          throw new Error('Agent composer is unavailable');
        }
        if (context.trim() === '') {
          throw new Error('agent.compose requires a non-empty context');
        }
        // The structured request the app renders: the module's task as the
        // purpose, its context verbatim, the instruction carrying the hard
        // rules line, the avoid list — and on the retry, the corrective note
        // the model is told its previous attempt earned.
        const hardRules =
          'one casual sentence, at most ' + maxWords + ' words, no ! : ; — – ?, no emoji, ' +
          (trailingPeriod ? 'ends with a single period' : 'does not end with a period') +
          ', do not sound like an assistant';
        const composeInstruction =
          (instruction.trim() !== '' ? instruction.trim() + ' ' : '') + hardRules;
        const pipeline = async function () {
          let retryNote = null;
          let lastText = null;
          for (let attempt = 1; attempt <= Z_COMPOSE_ATTEMPTS; attempt++) {
            const shaped = shapeGenerateRequest({
              purpose: task.trim() !== '' ? task : 'compose',
              instruction: composeInstruction,
              context: context,
              hint: retryNote === null ? undefined : retryNote,
              avoid: avoid,
            });
            const answer = await request.askAgent(shaped);
            const text = zPreClean(answer && typeof answer.text === 'string' ? answer.text : '');
            lastText = text;
            const violations = zComposerViolations(text, maxWords, trailingPeriod, avoid);
            if (violations.length === 0) return { text: text };
            retryNote =
              'Your previous attempt violated the rules: ' + violations.join(', ') +
              '\nWrite a different sentence that follows every rule.';
          }
          // Both attempts broke the contract — deterministic repair, then the
          // final check. A repeat cannot be repaired; that fails.
          const repaired = zComposerSanitize(lastText === null ? '' : lastText, maxWords, trailingPeriod);
          const finalViolations = zComposerViolations(repaired, maxWords, trailingPeriod, avoid);
          if (finalViolations.length === 0) return { text: repaired };
          throw new Error(
            'composer output kept violating the rules (' + finalViolations.join(', ') + ')'
          );
        };
        return pipeline();
      });
    }

    // -- the object itself --

    const zagentsInfo = {
      name: request.zagentsName !== null ? request.zagentsName : manifest.name,
      version: request.zagentsVersion !== null ? request.zagentsVersion : manifest.version,
      title: request.title !== null ? request.title : '',
      sessionId: sessionIdOf(dataRoot) || '',
      entry: entry,
    };
    // Present only when they exist, exactly as ZAgents reports them: the
    // profile's identity, the staged-activation message, the form's input.
    if (request.userAgent !== null) zagentsInfo.userAgent = request.userAgent;
    if (request.prompt !== null) zagentsInfo.prompt = request.prompt;
    if (request.inputsValues !== null) zagentsInfo.inputs = request.inputsValues;

    moduleSdk = {
      info: Object.freeze(zagentsInfo),
      log: function () {
        logLine('info', zJoinArgs(arguments));
        return Promise.resolve();
      },
      chat: Object.freeze({ emit: zGated('chat', zEmit) }),
      http: Object.freeze({ request: zGated('network', zHttpRequest) }),
      storage: Object.freeze(zStorageApi),
      fs: Object.freeze(zFsApi),
      agent: Object.freeze({ compose: zGated('agent', zAgentCompose) }),
      // Room's own extension, aliased so a module that prefers the SDK
      // spelling still reaches the one form channel.
      ui: Object.freeze({ form: askUserForm }),
    };
    Object.freeze(moduleSdk);
    sandbox.zagents = moduleSdk;
    // The room SDK stays reachable beside the module's own spelling — the
    // documented superset (room.ui.form is the form's other name, and a
    // module's entry runs outside any handler invocation, so an argument is
    // a shape it could never receive). A plugin-format call keeps today's
    // deal instead: room is the first argument of every handler, never a
    // global.
    sandbox.room = room;
  }

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
    // Emits delivered after this point are silently dropped (ZAgents'
    // termination semantics: delivery stops, persisted storage stays).
    state.terminated = true;
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
    delete sandbox.zagents;
    delete sandbox.room;
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
  // A module run is its entry, once: the activate event loads the entry as a
  // CommonJS module, takes module.exports and awaits it when it is a promise
  // — `module.exports = main()` is the shape every ZAgents sample uses. There
  // is no activate() to call, no probe to introspect (a module is not a
  // tool), and nothing to run for any other event: deactivation and its
  // siblings answer empty, the same silence ZAgents answers with, because a
  // module's lifecycle lives in the app, not in its code.
  if (call.request.zagents) {
    if (call.event !== 'activate') return undefined;
    const exported = call.loadEntry();
    call.exports = exported;
    if (exported !== null && typeof exported === 'object' && typeof exported.then === 'function') {
      return await exported;
    }
    return exported;
  }
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

// ---- the module answer --------------------------------------------------------
//
// The module message body, composed once at answer time: the emit texts in
// arrival order, then the console/log lines after a blank-line separator,
// then — when the run failed — the failure sentence after two. Capped at
// ZAgents' 400 lines / 32 KB with its own suffixes. The return value rides
// the envelope's `result` (JSON, never rendered): a module that wants the
// user to see something emits or logs it, exactly as upstream.

function zagentsOutput(call, failureText) {
  let body = '';
  const emits = call.state.zEmits;
  for (let index = 0; index < emits.length; index++) {
    body += emits[index] + '\n';
  }
  const logs = call.state.zLines.join('\n');
  if (logs.trim() !== '') {
    if (body !== '') body += '\n';
    body += logs;
  }
  if (failureText !== null) {
    if (body !== '') body += '\n\n';
    body += 'Module run failed: ' + failureText;
  }
  return zCapOutput(body);
}

// The module's return value as a JSON value the wire can carry — anything a
// JSON round-trip cannot hold is reported as nothing, because a result that
// cannot travel is not a result the app can read.
function zagentsResultValue(value) {
  if (value === undefined) return null;
  try {
    return JSON.parse(JSON.stringify(value));
  } catch (err) {
    return null;
  }
}

function zagentsResult(call, ok, value, failureText) {
  return {
    ok: ok,
    output: zagentsOutput(call, failureText),
    result: ok ? zagentsResultValue(value) : null,
    cards: call.state.cards,
    logs: call.state.zLines,
    tools: [],
    commands: [],
    hooks: [],
    ms: Date.now() - call.startedAt,
  };
}

function successResult(call, value) {
  if (call.request.zagents) return zagentsResult(call, true, value, null);
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
  if (call.request.zagents) {
    return zagentsResult(call, false, null, describe(err));
  }
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
  if (call.request.zagents) {
    // ZAgents' own timeout sentence, carried by the failure path.
    return zagentsResult(
      call,
      false,
      null,
      'Module timed out after ' + Math.round(call.budgetMs / 1000) + 's.'
    );
  }
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

// ---- the deadline, pausable ----------------------------------------------------
//
// The call's overall wall-clock budget, with the one pause rule both formats
// share: while a reverse request (agent_generate, user_input) is outstanding
// the deadline is STOPPED — waiting for the user or the model is not compute
// the module should be charged for — and restarted, with whatever remained,
// when the answer arrives. Each pause carries its own hard cap, far above
// the bridge's reverse timeouts, so a channel that neither answered nor
// failed cannot hold a call forever.
function makeDeadline(budgetMs, onExpire) {
  let remainingMs = budgetMs;
  let armedAt = 0;
  let timer = null;
  let pauses = 0;
  let expired = false;

  function disarm() {
    if (timer === null) return;
    clearTimeout(timer);
    timer = null;
    remainingMs = Math.max(0, remainingMs - (Date.now() - armedAt));
  }

  function arm() {
    if (expired || timer !== null || pauses > 0) return;
    if (remainingMs <= 0) {
      expired = true;
      onExpire();
      return;
    }
    armedAt = Date.now();
    timer = setTimeout(function () {
      timer = null;
      expired = true;
      onExpire();
    }, remainingMs);
  }

  return {
    start: arm,
    pause: function () {
      pauses += 1;
      disarm();
    },
    resume: function () {
      pauses = Math.max(0, pauses - 1);
      arm();
    },
    stop: disarm,
  };
}

// The reverse channel wrapper that runs the pause rule: pause going in,
// resume coming out, and the hard cap on any wait that outlived its own
// protocol timeout.
function pausingChannel(fn, deadline) {
  if (typeof fn !== 'function') return null;
  return function (payload) {
    return new Promise(function (resolve, reject) {
      let settled = false;
      deadline.pause();
      const cap = setTimeout(function () {
        if (settled) return;
        settled = true;
        deadline.resume();
        reject(
          new Error(
            'the app did not answer the reverse request within ' + REVERSE_HARD_CAP_MS / 1000 + 's'
          )
        );
      }, REVERSE_HARD_CAP_MS);
      function settle(err, value) {
        if (settled) return;
        settled = true;
        clearTimeout(cap);
        deadline.resume();
        if (err) reject(err);
        else resolve(value);
      }
      Promise.resolve()
        .then(function () { return fn(payload); })
        .then(
          function (value) { settle(null, value); },
          function (err) { settle(err); }
        );
    });
  };
}

// Never crashes the process it runs in: everything a call can throw or hang on
// is answered as a result or a rejection, and the rejection is a shaped Error
// the bridge turns into its error envelope.
async function runPluginCall(opts) {
  const startedAt = Date.now();
  const request = readRequest(opts);
  let call = null;
  const outcome = await new Promise(function (resolve) {
    const deadline = makeDeadline(request.timeoutMs, function () {
      if (call !== null) call.state.terminated = true; // emit delivery stops here
      resolve({ timedOut: true });
    });
    // The channels are wrapped before the SDK ever sees them, so every
    // reverse request — generate, form, whichever comes next — pauses the
    // deadline for exactly as long as it is outstanding.
    request.askAgent = pausingChannel(request.askAgent, deadline);
    request.askUser = pausingChannel(request.askUser, deadline);
    call = newCall(request, startedAt);
    deadline.start();
    // The work starts on a microtask so the budget timer is armed before any
    // of it runs; the synchronous half is bounded inside by V8's own timeout.
    Promise.resolve()
      .then(function () { return runPluginEvent(call); })
      .then(
        function (value) {
          deadline.stop();
          resolve({ value: value });
        },
        function (err) {
          deadline.stop();
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
