'use strict';

// The runtime's entry script: one line of JSON in, one line of JSON out.
//
// It also installs the user-agent rule, which is why it is not just a
// dispatcher. Every request this runtime makes has to carry the profile's own
// identity: a package or script reaching a server as `node` would be the one
// thing on this device that is not the profile, and the profile is what the
// whole browser exists to present. The rule wraps the request APIs rather than
// passing a header at each call site, so a package that never heard of it
// obeys it too. A script that builds a request on a raw `tls`/`net` socket,
// speaks HTTP by hand, or reaches past the request it was handed to the request
// classes' prototypes, is outside what these wrappers can see.

const net = require('net');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const argv = process.argv.slice(2);
const socketPath = argv[0];
const token = argv[1];

// ---- the runtime's own account of itself ---------------------------------
//
// One line of JSON per stage, written beside this script, from the first
// statement on. The failure it exists for is a caller waiting for a socket that
// never appears: without it, that side can only say the runtime did not start,
// and a runtime that never ran, one that stopped before listening and one that
// bound something else all read exactly the same. A write that fails costs the
// diagnosis, never the run.

const STATUS_FILE = path.join(__dirname, 'status.json');

function report(stage, extra) {
  try {
    fs.writeFileSync(
      STATUS_FILE,
      JSON.stringify(Object.assign({ stage: stage, at: Date.now(), pid: process.pid }, extra))
    );
  } catch (err) {
    // Deliberately silent: this is a diagnostic, not part of the protocol.
  }
}

process.on('uncaughtException', (err) => {
  report('threw', { error: err && err.message ? err.message : String(err) });
  process.exit(1);
});

let cwd;
try {
  cwd = process.cwd();
} catch (err) {
  cwd = 'unreadable: ' + (err && err.message ? err.message : String(err));
}
report('loading', { argv: process.argv.slice(1), node: process.version, cwd: cwd });

if (!socketPath || !token) {
  report('no-arguments', {});
  process.stderr.write('room-node: no socket name or token was given\n');
  process.exit(2);
}

let userAgent = null;
let enforcementInstalled = false;

function currentUserAgent() {
  if (!userAgent) {
    throw new Error(
      'no user agent for this profile: every request Node makes has to carry the ' +
        "profile's own identity, so nothing is sent until one is supplied"
    );
  }
  return userAgent;
}

// ---- the user-agent rule -------------------------------------------------

// The profile's identity REPLACES any the caller supplied rather than deferring
// to it: a script that writes its own `User-Agent` would otherwise be able to
// reach a server as something this profile is not, and `setHeader` and `fetch`
// below already refuse that, so honouring it here would only make the rule
// depend on which of the three a script happened to use.
function withUserAgent(headers, ua) {
  if (headers == null) {
    return { 'User-Agent': ua };
  }
  if (Array.isArray(headers)) {
    const flat = typeof headers[0] === 'string';
    const named = (name) => String(name).toLowerCase() === 'user-agent';
    if (flat) {
      const kept = [];
      for (let index = 0; index + 1 < headers.length; index += 2) {
        if (!named(headers[index])) {
          kept.push(headers[index], headers[index + 1]);
        }
      }
      kept.push('User-Agent', ua);
      return kept;
    }
    const kept = headers.filter((pair) => !(pair && named(pair[0])));
    kept.push(['User-Agent', ua]);
    return kept;
  }
  if (typeof headers.set === 'function' && typeof headers.has === 'function') {
    headers.set('User-Agent', ua);
    return headers;
  }
  if (typeof headers === 'object') {
    for (const key of Object.keys(headers)) {
      if (key.toLowerCase() === 'user-agent') {
        delete headers[key];
      }
    }
    headers['User-Agent'] = ua;
    return headers;
  }
  return { 'User-Agent': ua };
}

function injectUserAgent(args) {
  const ua = currentUserAgent();
  const first = args[0];
  if (typeof first === 'string' || first instanceof URL) {
    const options = args[1];
    if (options && typeof options === 'object') {
      options.headers = withUserAgent(options.headers, ua);
    } else {
      // (url, callback) becomes (url, options, callback), which is the shape
      // Node's own signature already accepts.
      args.splice(1, 0, { headers: { 'User-Agent': ua } });
    }
    return;
  }
  if (first && typeof first === 'object') {
    first.headers = withUserAgent(first.headers, ua);
  }
}

// A request whose header is set after it was created would otherwise undo the
// injection above, so the setter is pinned to the profile's identity too.
function pinHeaders(request) {
  if (!request || typeof request.setHeader !== 'function') {
    return request;
  }
  const originalSetHeader = request.setHeader;
  request.setHeader = function (name, value) {
    if (String(name).toLowerCase() === 'user-agent') {
      return originalSetHeader.call(this, 'User-Agent', currentUserAgent());
    }
    return originalSetHeader.call(this, name, value);
  };
  return request;
}

function patchRequestFamily(name) {
  const mod = require(name);
  const originalRequest = mod.request;
  const originalGet = mod.get;
  mod.request = function (...args) {
    injectUserAgent(args);
    return pinHeaders(originalRequest.apply(this, args));
  };
  mod.get = function (...args) {
    injectUserAgent(args);
    return pinHeaders(originalGet.apply(this, args));
  };
}

function patchFetch() {
  const original = globalThis.fetch;
  if (typeof original !== 'function') {
    return;
  }
  globalThis.fetch = function (input, init) {
    const ua = currentUserAgent();
    const merged = Object.assign({}, init);
    const headers = new Headers((init && init.headers) || (input && input.headers) || undefined);
    headers.set('user-agent', ua);
    merged.headers = headers;
    return original.call(this, input, merged);
  };
}

function patchHttp2() {
  let http2;
  try {
    http2 = require('http2');
  } catch (err) {
    return;
  }
  const proto = http2.Http2Session && http2.Http2Session.prototype;
  if (!proto || typeof proto.request !== 'function') {
    return;
  }
  const original = proto.request;
  proto.request = function (headers, options) {
    const ua = currentUserAgent();
    const merged = Object.assign({}, headers && typeof headers === 'object' ? headers : {});
    for (const key of Object.keys(merged)) {
      if (key.toLowerCase() === 'user-agent') {
        delete merged[key];
      }
    }
    merged['user-agent'] = ua;
    return original.call(this, merged, options);
  };
}

function installUserAgentRule() {
  patchRequestFamily('http');
  patchRequestFamily('https');
  patchFetch();
  patchHttp2();
  enforcementInstalled = true;
}

installUserAgentRule();

// ---- the wire ------------------------------------------------------------

function reply(connection, id, ok, payload) {
  const message = ok ? { id: id, ok: true, result: payload } : { id: id, ok: false, error: payload };
  try {
    connection.write(JSON.stringify(message) + '\n');
  } catch (err) {
    // A reply nobody can receive costs one answer; throwing here would take the
    // runtime down for it.
  }
}

function status() {
  return {
    node: process.version,
    platform: process.platform,
    arch: process.arch,
    pid: process.pid,
    workDir: process.cwd(),
    userAgent: userAgent,
    userAgentEnforced: enforcementInstalled,
  };
}

// ---- reverse requests -----------------------------------------------------
//
// While a plugin call is executing, it may ask the app's side for something
// only the app can do: the chat's own agent to write a piece of text
// (`agent_generate`, room.agent.generate / zagents.agent.compose), or the
// user to fill a form (`user_input`, room.ui.form / zagents.ui.form). Both
// travel the SAME connection in the opposite direction over the SAME
// machinery: the runtime writes a line shaped like one of the app's commands
// — id, cmd, token, request — and the app answers with a line shaped like
// this runtime's own replies. The discriminator is the id: commands keep
// numeric ids, while a reverse reply carries the string id the runtime minted
// and no cmd of its own, so the two directions can never be mistaken for one
// another. Reverse lines exist only inside a plugin call's execution, which
// is also the only time the app is reading anything other than the reply it
// is waiting for.
//
// The registry is the whole vocabulary: one entry per command, each with its
// id prefix, its own timeout and the noun its error sentences name. Adding a
// third reverse command is an entry here and a handler app-side — nothing in
// the routing, the settling or the token rule is anyone's to re-derive.

const REVERSE_COMMANDS = {
  agent_generate: { prefix: 'gen', timeoutMs: 20000, what: 'generation' },
  user_input: { prefix: 'form', timeoutMs: 300000, what: 'form' },
};

let reverseCounter = 0;
const pendingReverse = new Map(); // "gen-N" / "form-N" -> { resolve, reject, timer, what }

// One reverse channel a plugin call is handed: unarmed until the plugin
// actually asks, so a call that never asks pays nothing for the channel
// existing, and the counter keeps every id unique for the connection's
// lifetime (which is the runtime's lifetime).
function makeReverseAsk(connection, cmd) {
  const spec = REVERSE_COMMANDS[cmd];
  return function (request) {
    return new Promise(function (resolve, reject) {
      reverseCounter += 1;
      const id = spec.prefix + '-' + reverseCounter;
      const entry = { resolve: resolve, reject: reject, timer: null, what: spec.what };
      pendingReverse.set(id, entry);
      // The app answers, or this fires: a request that neither arrived nor
      // failed would otherwise hold the plugin until the call's own budget
      // with no words for what went missing.
      entry.timer = setTimeout(function () {
        pendingReverse.delete(id);
        reject(
          new Error(
            'the app did not answer the ' + spec.what + ' request within ' +
              spec.timeoutMs / 1000 +
              's'
          )
        );
      }, spec.timeoutMs);
      try {
        connection.write(
          JSON.stringify({ id: id, cmd: cmd, token: token, request: request }) + '\n'
        );
      } catch (err) {
        // A line nobody could receive costs the request, not the runtime —
        // the same trade reply() makes, minus the silence: the plugin gets
        // told instead of waiting out the timeout above.
        clearTimeout(entry.timer);
        pendingReverse.delete(id);
        reject(
          new Error(
            'the ' + spec.what + ' request could not be sent: ' +
              (err && err.message ? err.message : String(err))
          )
        );
      }
    });
  };
}

// The generation channel one plugin call is handed — `agent_generate` under
// its own name, exactly the deal room.agent.generate has always had.
function makeAskAgent(connection) {
  return makeReverseAsk(connection, 'agent_generate');
}

// The form channel: `user_input`, the same machinery with a patient timeout,
// because a person reading a dialog is not a model answering a prompt.
function makeAskUserForm(connection) {
  return makeReverseAsk(connection, 'user_input');
}

// A reply line settles the one pending entry it names. ok:false carries the
// app's own words for what went wrong, which is all the plugin ever learns
// about it; ok:true resolves the app's result object for plugin_host to read.
function settleReverse(line) {
  const entry = pendingReverse.get(line.id);
  pendingReverse.delete(line.id);
  clearTimeout(entry.timer);
  if (line.ok) {
    entry.resolve(line.result);
  } else {
    entry.reject(
      new Error(
        typeof line.error === 'string' && line.error
          ? line.error
          : 'the app refused the ' + entry.what + ' request'
      )
    );
  }
}

// Every pending entry is rejected in its own words: a teardown has to speak
// for a generation and a form at once, and each still says WHICH kind of
// request it belonged to — the generation's sentence stays exactly the one
// it has always been.
function rejectPendingReverse() {
  const ids = Array.from(pendingReverse.keys());
  for (let index = 0; index < ids.length; index++) {
    const entry = pendingReverse.get(ids[index]);
    pendingReverse.delete(ids[index]);
    clearTimeout(entry.timer);
    entry.reject(
      new Error('the app closed the connection before answering the ' + entry.what + ' request')
    );
  }
}

// One look decides who a line belongs to before the dispatcher spends its own
// parse on it: a pending reverse id with no cmd is the app answering a
// generation or a form; every other line is a command, token-checked exactly
// as today.
function route(connection, line) {
  let parsed;
  try {
    parsed = JSON.parse(line);
  } catch (err) {
    handle(connection, line);
    return;
  }
  if (
    parsed &&
    typeof parsed.id === 'string' &&
    !parsed.cmd &&
    pendingReverse.has(parsed.id)
  ) {
    settleReverse(parsed);
    return;
  }
  handle(connection, line);
}

// ---- running a script ----------------------------------------------------

// A script that never finishes must not take the runtime with it, so it is
// bounded twice: the synchronous half by V8 itself, the asynchronous half by a
// timer. The caller waits longer than both and answers honestly if even that
// runs out.
const DEFAULT_BUDGET_MS = 30000;
const SYNC_CAP_MS = 10000;
const MAX_OUTPUT_CHARS = 20000;

// The entry script's own path: a script resolves packages from beside it, so
// whatever is installed next to this file is reachable with require().
const ENTRY_FILE = process.argv[1];

function clipOutput(text) {
  if (text.length <= MAX_OUTPUT_CHARS) return text;
  return (
    text.slice(0, MAX_OUTPUT_CHARS) + '\n...[truncated at ' + MAX_OUTPUT_CHARS + ' characters]'
  );
}

function renderValue(value) {
  if (value === undefined) return 'undefined';
  try {
    const json = JSON.stringify(value, null, 2);
    return json === undefined ? String(value) : json;
  } catch (err) {
    return String(value);
  }
}

function runFailed(err, startedAt) {
  return {
    ok: false,
    output: 'the script threw: ' + (err && err.message ? err.message : String(err)),
    ms: Date.now() - startedAt,
  };
}

// The script is the body of an async function, so `return` sends a value back
// and `await` works. The whole call is inside the evaluated source on purpose:
// outside it, the vm timeout would cover only the function's creation and not
// the synchronous part of its body, which is the half a runaway loop lives in.
function runScript(source, budgetMs) {
  const startedAt = Date.now();
  const module_ = { exports: {} };
  const syncCap = Math.min(SYNC_CAP_MS, Math.floor(budgetMs / 3));
  globalThis.__roomNodeArgs = [
    require,
    module_,
    module_.exports,
    ENTRY_FILE,
    path.dirname(ENTRY_FILE),
  ];
  let pending;
  try {
    pending = vm.runInThisContext(
      '(async function (require, module, exports, __filename, __dirname) {\n' +
        source +
        '\n}).apply(undefined, globalThis.__roomNodeArgs)',
      { filename: 'room-node-run.js', timeout: syncCap }
    );
  } catch (err) {
    return Promise.resolve(runFailed(err, startedAt));
  } finally {
    delete globalThis.__roomNodeArgs;
  }
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      resolve({
        ok: false,
        output: 'the script was still running after ' + budgetMs + 'ms and was stopped',
        ms: Date.now() - startedAt,
      });
    }, budgetMs);
    Promise.resolve(pending).then(
      (value) => {
        clearTimeout(timer);
        // A script that assigns module.exports instead of returning gets that
        // reported, which is what calling an installed package looks like.
        const exported = module_.exports;
        const shown =
          value === undefined && exported && Object.keys(exported).length > 0 ? exported : value;
        resolve({ ok: true, output: clipOutput(renderValue(shown)), ms: Date.now() - startedAt });
      },
      (err) => {
        clearTimeout(timer);
        resolve(runFailed(err, startedAt));
      }
    );
  });
}

function handle(connection, line) {
  let command;
  try {
    command = JSON.parse(line);
  } catch (err) {
    return;
  }
  if (command.token !== token) {
    reply(connection, command.id, false, "the command did not carry this run's token");
    return;
  }
  try {
    // Absent or blank means no identity was supplied, which is a refusal on
    // every request rather than a fallback to Node's own.
    userAgent =
      typeof command.userAgent === 'string' && command.userAgent.trim()
        ? command.userAgent.trim()
        : null;
    switch (command.cmd) {
      case 'status':
        reply(connection, command.id, true, status());
        break;
      case 'run': {
        if (typeof command.script !== 'string' || !command.script.trim()) {
          reply(connection, command.id, false, 'the command carried no script to run');
          break;
        }
        // The budget comes from the caller rather than from a constant here,
        // so the app's own read timeout can be derived from the number the
        // runtime is actually working to.
        const budgetMs =
          Number.isFinite(command.timeoutMs) && command.timeoutMs > 0
            ? command.timeoutMs
            : DEFAULT_BUDGET_MS;
        runScript(command.script, budgetMs).then((result) =>
          reply(connection, command.id, true, result)
        );
        break;
      }
      case 'plugin': {
        // The plugin host owns everything past the wire: this branch relays
        // the call's own fields and answers with the envelope it produced, so
        // the sandbox, its quotas and its permissions live in one place beside
        // the plugins it runs rather than in the dispatcher. A command that
        // carries none of the module fields is a plugin-format call exactly
        // as before — the host defaults every absent field, and a field of the
        // wrong shape is the host's refusal to make, not this side's guess.
        const call = {
          pluginRoot: command.pluginRoot,
          dataRoot: command.dataRoot,
          entry: command.entry,
          manifest: command.manifest,
          event: command.event,
          payload: command.payload,
          config: command.config,
          timeoutMs: command.timeoutMs,
          // The chat's transcript, as a chat.context grant sends it: relayed
          // only when the request carried one (absent field = the plugin was
          // not granted, and an absent grant must read as "asked and denied",
          // never as "this chat has no turns").
          history: command.history,
          // The identity this profile presents, as the command carried it: the
          // plugin reads it back as room.meta.userAgent — the same string its
          // own room.net.fetch requests ride as, now visible instead of only
          // enforced.
          userAgent: command.userAgent,
          // The module.json fields the row kept verbatim, relayed as they
          // arrived: format ('zagents' switches the sandbox to the module
          // SDK; absent stays the plugin SDK), the staged-activation prompt,
          // the module's own budget, this chat's stored form answers, the
          // per-chat state directory and the display strings a module's
          // zagents.info reports. Each is read strictly by the host.
          format: command.format,
          prompt: command.prompt,
          timeoutSeconds: command.timeoutSeconds,
          inputsValues: command.inputsValues,
          stateRoot: command.stateRoot,
          title: command.title,
          zagentsVersion: command.zagentsVersion,
          zagentsName: command.zagentsName,
          // The npm store's install root, when the request carried one: the
          // host's require shim falls back to it for packages the plugin did
          // not bundle (read-only, contained). Absent keeps the shim's old
          // reach — bundled packages and built-ins, nothing else.
          nodeModulesRoot: command.nodeModulesRoot,
          // The reverse channels, one per command the family speaks: the
          // generation channel room.agent.generate (and a module's
          // zagents.agent.compose) rides, and the form channel
          // room.ui.form / zagents.ui.form answers on. Both unarmed until
          // the plugin asks, and both authenticated by this run's token when
          // they do.
          askAgent: makeAskAgent(connection),
          askUser: makeAskUserForm(connection),
        };
        require('./plugin_host.js')
          .runPluginCall(call)
          .then(
            (result) => reply(connection, command.id, true, result),
            (err) =>
              reply(connection, command.id, false, err && err.message ? err.message : String(err))
          );
        break;
      }
      default:
        reply(connection, command.id, false, 'unknown command: ' + String(command.cmd));
    }
  } catch (err) {
    reply(connection, command.id, false, err && err.message ? err.message : String(err));
  }
}

// ONE CLIENT, AND THE RUNTIME'S LIFETIME IS THAT CLIENT'S. The app owns the
// connection; when the app process goes away the socket closes and this process
// exits, so a runtime is never left behind serving a profile nobody is on.
let connected = false;

const server = net.createServer((connection) => {
  if (connected) {
    connection.destroy();
    return;
  }
  connected = true;

  let buffer = '';
  connection.setEncoding('utf8');
  connection.on('data', (chunk) => {
    buffer += chunk;
    let index = buffer.indexOf('\n');
    while (index >= 0) {
      const line = buffer.slice(0, index);
      buffer = buffer.slice(index + 1);
      if (line.trim()) {
        route(connection, line);
      }
      index = buffer.indexOf('\n');
    }
  });
  connection.on('error', () => {});
  connection.on('close', () => {
    // A request still waiting loses the app that owed it an answer. The
    // process is about to exit with the connection anyway; rejecting first is
    // for the honesty of the rejection, not the recovery.
    rejectPendingReverse();
    process.exit(0);
  });
});

server.on('error', (err) => {
  report('listen-failed', { socket: socketPath, error: err.message });
  process.stderr.write('room-node: listen failed: ' + err.message + '\n');
  process.exit(1);
});

// A socket file left behind by a runtime that died is not ours to reuse: the
// bind would fail with EADDRINUSE and the caller's connect would reach a socket
// nobody is listening on. ENOENT is the ordinary case.
try {
  fs.unlinkSync(socketPath);
} catch (err) {
  // Nothing to remove.
}

server.listen({ path: socketPath }, () => {
  // The bound address is reported as well as the path it was asked for: if the
  // two ever disagree, the caller's connect is not the thing at fault.
  report('listening', { socket: socketPath, address: server.address() });
  process.stderr.write('room-node: ready on ' + process.version + '\n');
});
