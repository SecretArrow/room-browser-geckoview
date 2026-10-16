#!/usr/bin/env node
'use strict';

// The desktop dev harness for plugins: it plays the part the app plays on a
// device — reads the manifest, builds a §6-shaped request, hands it to the
// same plugin_host.js the runtime ships — and prints the result envelope as
// JSON on stdout, cards and all. What a plugin does here is what it does
// there; what it cannot do here (fs, sockets, the process) it cannot do there
// either.
//
// Permissions default to NONE, the same order the app runs in: pass --perms
// for what a user would have accepted. The data root is deterministic per
// plugin under the OS temp dir, so a second run sees the first run's kv —
// pass --data-root to isolate one.

const fs = require('fs');
const os = require('os');
const path = require('path');

const HOST = path.resolve(__dirname, '../node/src/main/assets/node/plugin_host.js');

const USAGE = [
  'usage: node plugin_dev.js <pluginDir> <event> [payloadJson]',
  '                   [--config {json}] [--perms storage,agent.tools,chat.context,network,agent.generate]',
  '                   [--net api.example.com,*.example.com] [--timeout 30000]',
  '                   [--data-root <dir>] [--history [{"role":"user","text":"hi"}]] [--no-gen]',
  '',
  'events: probe activate deactivate command message tool config',
  "payload per event: command {\"name\":\"greet\",\"args\":\"World\"}",
  '                   tool {"name":"count_words","argsJson":"{\\"text\\\":\\"hi\\"}"}',
  '                   deactivate {"reason":"disabled"}   config {"values":{...}}',
].join('\n');

function fail(message) {
  process.stderr.write('plugin_dev: ' + message + '\n\n' + USAGE + '\n');
  process.exit(2);
}

function parseArgs(argv) {
  const positional = [];
  const flags = {};
  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index];
    if (arg === '--help' || arg === '-h') {
      process.stdout.write(USAGE + '\n');
      process.exit(0);
    }
    if (arg === '--no-gen') {
      // A switch rather than a setting: it carries no value, so it must not
      // eat the next argument the way every valued flag does.
      flags['no-gen'] = true;
      continue;
    }
    if (arg.indexOf('--') === 0) {
      const name = arg.slice(2);
      const value = argv[++index];
      if (value === undefined) fail('flag --' + name + ' needs a value');
      flags[name] = value;
      continue;
    }
    positional.push(arg);
  }
  return { positional: positional, flags: flags };
}

function readJsonFlag(flags, name) {
  const raw = flags[name];
  if (raw === undefined) return undefined;
  try {
    return JSON.parse(raw);
  } catch (err) {
    fail('--' + name + ' is not JSON: ' + (err && err.message ? err.message : String(err)));
  }
}

function listFlag(flags, name) {
  const raw = flags[name];
  if (raw === undefined) return undefined;
  return raw
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

function main() {
  const parsed = parseArgs(process.argv.slice(2));
  const positional = parsed.positional;
  const flags = parsed.flags;
  if (positional.length < 2) fail('a plugin directory and an event are required');
  const pluginDir = positional[0];
  const event = positional[1];
  let payload = {};
  if (positional.length > 2) {
    try {
      payload = JSON.parse(positional[2]);
    } catch (err) {
      fail('the payload is not JSON: ' + (err && err.message ? err.message : String(err)));
    }
    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
      fail('the payload must be a JSON object');
    }
  }

  const manifestPath = path.join(pluginDir, 'plugin.json');
  let manifest;
  try {
    manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  } catch (err) {
    fail(
      "could not read the manifest at '" + manifestPath + "': " +
        (err && err.message ? err.message : String(err))
    );
  }
  if (!manifest || typeof manifest !== 'object') fail('the manifest is not a JSON object');

  // The defaults a chat with no overrides would see: manifest schema defaults.
  const config = {};
  const schema =
    manifest.config && Array.isArray(manifest.config.schema) ? manifest.config.schema : [];
  for (const item of schema) {
    if (item && typeof item === 'object' && typeof item.key === 'string') {
      config[item.key] =
        item.default !== undefined
          ? item.default
          : item.type === 'number'
            ? 0
            : item.type === 'boolean'
              ? false
              : '';
    }
  }
  const configOverride = readJsonFlag(flags, 'config');
  if (configOverride !== undefined) {
    if (!configOverride || typeof configOverride !== 'object' || Array.isArray(configOverride)) {
      fail('--config must be a JSON object');
    }
    Object.assign(config, configOverride);
  }

  const sanitized = String(manifest.id || 'plugin').replace(/\./g, '_');
  const dataRoot =
    flags['data-root'] !== undefined
      ? path.resolve(flags['data-root'])
      : path.join(os.tmpdir(), 'room-plugin-dev', 'plugin-data', '1', sanitized);
  fs.mkdirSync(path.join(dataRoot, 'files'), { recursive: true });

  const history = readJsonFlag(flags, 'history') || [];
  if (!Array.isArray(history)) fail('--history must be a JSON array of {role, text}');

  // The app's side of room.agent.generate, faked deterministically: no
  // bridge, no model, no tokens — the same shape of answer every run, so a
  // plugin's generate path is testable from a desktop. --no-gen withdraws it,
  // which lands the plugin on the honest refusal a host with no channel
  // answers with. Every call is logged to stderr so a run can show it asked.
  const noGen = flags['no-gen'] === true;
  const askAgent = noGen
    ? undefined
    : function (request) {
        const purpose = request && typeof request.purpose === 'string' ? request.purpose : '';
        const instruction =
          request && typeof request.instruction === 'string' ? request.instruction : '';
        process.stderr.write(
          '[GEN] purpose=' + purpose + ' instruction=' + instruction.slice(0, 60) + '\n'
        );
        const words = instruction
          .trim()
          .split(/\s+/)
          .filter((word) => word.length > 0)
          .slice(0, 8)
          .join(' ');
        return Promise.resolve({ text: '(composed) ' + purpose + ': ' + words });
      };

  const timeout =
    flags.timeout !== undefined ? Number(flags.timeout) : undefined;
  if (flags.timeout !== undefined && (!Number.isFinite(timeout) || timeout <= 0)) {
    fail('--timeout needs a positive number of milliseconds');
  }

  const request = {
    pluginRoot: path.resolve(pluginDir),
    dataRoot: dataRoot,
    entry: manifest.entry,
    manifest: {
      id: manifest.id,
      name: manifest.name,
      version: manifest.version,
      // The app intersects the manifest's asks with what the user accepted;
      // here --perms IS that decision, and its default is nobody consented.
      permissions: listFlag(flags, 'perms') || [],
      networkAllow:
        listFlag(flags, 'net') ||
        (manifest.network && Array.isArray(manifest.network.allow)
          ? manifest.network.allow
          : []),
    },
    event: event,
    payload: payload,
    config: config,
    history: history,
    // Supplied unless --no-gen said otherwise; plugin_host reports the
    // absence in words when a plugin asks to generate anyway.
    askAgent: askAgent,
    timeoutMs:
      timeout !== undefined
        ? timeout
        : event === 'deactivate'
          ? 10000
          : 30000,
  };

  process.stderr.write(
    'plugin_dev: ' + manifest.id + ' (' + pluginDir + ') event ' + event +
      ', data root ' + dataRoot + '\n'
  );

  const { runPluginCall } = require(HOST);
  runPluginCall(request).then(
    (result) => {
      process.stdout.write(JSON.stringify(result, null, 2) + '\n');
      process.exit(result && result.ok ? 0 : 1);
    },
    (err) => {
      process.stderr.write(
        'plugin_dev: the call failed: ' + (err && err.message ? err.message : String(err)) + '\n'
      );
      process.exit(1);
    }
  );
}

main();
