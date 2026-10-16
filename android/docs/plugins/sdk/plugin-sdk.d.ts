/// <reference lib="es2020" />
/**
 * Room plugin SDK — ambient typings for API version 1.
 *
 * Documentation only: nothing loads this file at runtime. The app injects the
 * real `room` object into every entry-point handler; this file exists so an
 * editor can autocomplete and type-check plugin code against it. The lib
 * reference above pulls in the ES2020 globals (Promise, Map, …) the sandbox
 * provides, whatever the editor's default target is; `console`, the timers,
 * `URL` and friends come from the editor's own default libraries.
 *
 * HOW TO USE IT (plain JavaScript, no build step):
 *
 *   1. Copy this file next to your `main.js` (keep the name `plugin-sdk.d.ts`
 *      — `import('./plugin-sdk')` resolves to it).
 *   2. Put `// @ts-check` on the first line of `main.js` so the editor reads
 *      the annotations.
 *   3. Annotate your handlers — that is how the parameter gets its type:
 *
 *          // @ts-check
 *          /** @param {import('./plugin-sdk').Room} room * /
 *          exports.activate = async (room) => {
 *            room.ui.card({ title: 'hi', style: 'info' });   // autocompletes
 *          };
 *
 *      or type the whole contract at once:
 *
 *          /** @type {import('./plugin-sdk').PluginExports} * /
 *          module.exports = { activate: ..., commands: ..., tools: ... };
 *
 * The caps named in the comments are enforced by the host at run time; the
 * full reference, with every rule and every refusal message, is
 * android/docs/PLUGINS.md.
 */

/** One card a plugin asks the chat to show — the plugin's only output that
 *  reaches the user (tool output goes to the model).
 *
 *  Caps: `title` ≤ 120 chars; `text` ≤ 4000; `fields` ≤ 12 pairs with each
 *  side ≤ 200; `link.label` ≤ 64 and `link.url` http(s) ≤ 500; the card's
 *  JSON ≤ 4096 chars (`text` gives way to fit); at most 5 cards per call. */
export interface PluginCard {
  /** Bold heading, ≤ 120 chars. Optional. */
  title?: string;
  /** Body text, ≤ 4000 chars. Optional. */
  text?: string;
  /** Label/value rows: ≤ 12 pairs, each side ≤ 200 chars. */
  fields?: Array<[string, string]>;
  /** A tappable link row; the URL must be http(s), ≤ 500 chars. */
  link?: { label: string; url: string };
  /** Tint. Unknown or absent means "info". */
  style?: 'info' | 'success' | 'warn' | 'error';
}

/** What `room.meta` holds: the call's identity. There is no `process` in the
 *  sandbox — this replaces it. */
export interface RoomMeta {
  readonly pluginId: string;
  readonly pluginName: string;
  readonly pluginVersion: string;
  /** The chat's session id (read out of the injected data-root layout). */
  readonly sessionId: string | null;
  readonly apiVersion: number;
  /** The event this call is answering: probe | activate | deactivate |
   *  command | message | tool | config. */
  readonly event: string;
  /** The browser identity this profile presents — the same user agent the
   *  plugin's own room.net.fetch requests ride as, chosen by the user; null
   *  when the app supplied none. Read-only. */
  readonly userAgent: string | null;
}

/** Per-chat config values (manifest schema defaults merged with this chat's
 *  overrides, declared keys only). No permission needed. */
export interface RoomConfig {
  /** The value for a schema key (string | number | boolean as declared),
   *  or undefined for a key the schema never declared. */
  get(key: string): string | number | boolean | undefined;
}

/** The kv store under this chat's data root — requires the `storage`
 *  permission. Caps: values ≤ 16 KB encoded, ≤ 128 keys, the whole store
 *  ≤ 256 KB of JSON; exceeding any throws a "plugin kv quota exceeded"
 *  Error. Written atomically; survives between calls and app restarts. */
export interface RoomKv {
  /** The stored JSON value, or null when the key is absent. */
  get(key: string): unknown;
  /** Stores a JSON-serializable value. Throws on a non-JSON value or a
   *  quota. */
  set(key: string, value: unknown): void;
  delete(key: string): void;
  clear(): void;
  /** All keys. */
  keys(): string[];
  /** How many keys are stored. */
  size(): number;
}

/** The files directory under this chat's data root — requires the `storage`
 *  permission. File names must match ^[A-Za-z0-9._-]{1,96}$ (no separators,
 *  no ".."). Caps: ≤ 256 files, ≤ 10 MB total, measured from disk on every
 *  write. */
export interface RoomFiles {
  /** The file's utf8 text, or null when absent. */
  read(name: string): string | null;
  /** The file's bytes as base64, or null when absent. */
  readBytes(name: string): string | null;
  write(name: string, text: string): void;
  writeBytes(name: string, base64: string): void;
  append(name: string, text: string): void;
  delete(name: string): void;
  exists(name: string): boolean;
  /** Sorted file names. */
  list(): string[];
  /** Size in bytes, or null when absent. */
  size(name: string): number | null;
}

/** A fetch response. The body is capped at 2 MB (a warn log says so when it
 *  was truncated); ≤ 6 MB of response bytes per call in total. */
export interface RoomFetchResponse {
  /** true for status 200..299. */
  ok: boolean;
  status: number;
  /** Flat header object; multi-value headers are joined with ", ". */
  headers: Record<string, string>;
  /** The response body as text. */
  body: string;
  /** The parsed body, or null when it is not JSON. */
  json(): unknown;
}

/** Network — requires the `network` permission, restricted to the manifest's
 *  allowlist, checked before every request and again on every redirect
 *  (max 2 redirects; 303 always becomes GET, 301/302 become GET for anything
 *  that was not a GET). http and https only; 15 s timeout ceiling and
 *  default. On the device, requests carry the profile's user agent. */
export interface RoomNet {
  fetch(
    url: string,
    options?: {
      method?: string;
      headers?: Record<string, string | number>;
      body?: string;
      timeoutMs?: number;
    }
  ): Promise<RoomFetchResponse>;
  /** Whether the allowlist covers a host (the matcher the host itself
   *  uses). */
  allowed(host: string): boolean;
}

/** One turn of the current chat's conversation, newest LAST. */
export interface RoomChatEntry {
  role: 'user' | 'assistant';
  text: string;
}

/** The current chat's conversation — requires the `chat.context` permission;
 *  other chats are unreachable by construction. The app sends the last 60
 *  user/assistant entries (≤ 12 000 chars) and only with the grant; without
 *  it, history() answers [] and summary() answers "". */
export interface RoomChat {
  /** The last `limit` entries (newest last); the limit caps at 200. */
  history(limit?: number): RoomChatEntry[];
  /** The whole visible conversation as "role: text" lines, the last 4000
   *  characters. */
  summary(): string;
}

/** Style constraints a generation may ask the chat's model to honour. Every
 *  member is optional and advisory — the app does not guarantee them, so a
 *  plugin that cares (the onhomeroom gate, for one) re-validates the text it
 *  gets back. */
export interface RoomGenerateStyle {
  /** Language to write in ("en"), ≤ 24 chars. */
  language?: string;
  /** At most this many words; 1..200. */
  maxWords?: number;
  /** Exactly one sentence. */
  oneSentence?: boolean;
  /** All lowercase. */
  lowercase?: boolean;
  /** Characters the text must not use ("!:—–?;"), ≤ 64 chars. */
  forbiddenChars?: string;
  /** Tone ("casual"), ≤ 64 chars. */
  tone?: string;
}

/** One generation request — structured fields, never a raw prompt: the app
 *  renders the prompt itself from these, so a plugin cannot smuggle one.
 *  Every field is clamped by the host before it travels (purpose ≤ 64,
 *  instruction ≤ 500, context ≤ 4000, hint ≤ 200, avoid ≤ 50 entries of
 *  ≤ 200 chars). */
export interface RoomGenerateOptions {
  /** What the text is FOR ("chat-reply"). Required, non-blank, ≤ 64 chars. */
  purpose: string;
  /** What is needed ("Reply to the last message ..."). Required, non-blank,
   *  ≤ 500 chars. */
  instruction: string;
  /** Live context the plugin gathered, ≤ 4000 chars. Optional. */
  context?: string;
  /** An extra angle, ≤ 200 chars. Optional. */
  hint?: string;
  /** Advisory style constraints. Optional. */
  style?: RoomGenerateStyle;
  /** Texts not to repeat (an anti-repeat memory), ≤ 50 entries of ≤ 200
   *  chars each. Optional. */
  avoid?: string[];
}

/** This chat's own AI — requires the `agent.generate` permission. The
 *  provider and model are the chat session's own; the plugin never sees
 *  them, their key, or the prompt they were handed. */
export interface RoomAgent {
  /** Asks the chat's model to write one piece of text and resolves with it
   *  (trimmed, 1..2000 chars). One attempt, no retry: a failure rejects with
   *  the app's own words for it. Serial inside a call — two fired in parallel
   *  are answered in order. Budget: 20 s per generation, inside the call's
   *  own budget (30 s commands and tools; the 10 s message hook will usually
   *  not fit one). */
  generate(options: RoomGenerateOptions): Promise<string>;
}

/** Cards — no permission needed: a card cannot leave the call. At most 5 per
 *  call (extras dropped with one warn log); each card validated, clamped and
 *  dropped per the PluginCard caps. */
export interface RoomUi {
  card(card: PluginCard): void;
  text(text: string): void;
}

/** Log lines collected with console.* into the call's result envelope —
 *  capped at 50 lines / 2 KB total. */
export interface RoomLog {
  info(message: string): void;
  warn(message: string): void;
  error(message: string): void;
}

/** Small utilities — no permission needed. */
export interface RoomUtil {
  /** Milliseconds since the epoch (Date.now()). */
  now(): number;
  /** A random UUID (crypto.randomUUID()). */
  uuid(): string;
  b64encode(text: string): string;
  b64decode(base64: string): string;
}

/** The `room` object handed to every entry-point handler. Frozen; the same
 *  instance for the whole call; per-chat by construction.
 *
 *  The permission-gated namespaces (`storage`, `net`, `chat`, `agent`) exist
 *  even without their grant — every call on them throws
 *  `Error("plugin permission not granted: <name>")` until the chat granted
 *  it, so a forgotten permission is an error that names itself. */
export interface Room {
  readonly meta: RoomMeta;
  readonly config: RoomConfig;
  readonly storage: {
    readonly kv: RoomKv;
    readonly files: RoomFiles;
  };
  readonly net: RoomNet;
  readonly chat: RoomChat;
  readonly agent: RoomAgent;
  readonly ui: RoomUi;
  readonly log: RoomLog;
  readonly util: RoomUtil;
}

/** What a tool's `run` may return: a string passes as the tool result;
 *  anything else is JSON-stringified. Both capped at 20 000 chars. */
export type ToolResult = string | number | boolean | object | null | undefined;

/** One agent tool. The object key must match ^[a-z][a-z0-9_-]{0,23}$ and the
 *  catalogue spelling `plugin_<sanitized-id>_<name>` must stay ≤ 60 chars —
 *  a name that cannot survive is dropped, not renamed. `parameters` is a
 *  JSON Schema object (the minimal {"type":"object"} is offered when
 *  omitted). */
export interface PluginTool {
  /** 1..500 chars; the model reads it prefixed "[<plugin name>] ". */
  description: string;
  parameters?: object;
  run(room: Room, args: Record<string, unknown>): ToolResult | Promise<ToolResult>;
}

/** One command. The object key must match a name the manifest declares. */
export interface PluginCommand {
  run(room: Room, args: string): HandlerResult | Promise<HandlerResult>;
}

/** What any non-tool handler may return: nothing → no card; a string → one
 *  text card; a card object → that card; an array → each element by these
 *  rules. */
export type HandlerResult = void | string | PluginCard | PluginCard[];

/** The contract a plugin entry (`main.js`, CommonJS) may export. Everything
 *  is optional; the manifest declares what the app routes to you.
 *
 *  Throwing fails that one call, never the app: the failure text (message +
 *  trimmed stack, ≤ 4000 chars) is carried as the call's answer. */
export interface PluginExports {
  /** Enable-time, right after the plugin was switched on for the chat. */
  activate?(room: Room): HandlerResult | Promise<HandlerResult>;
  /** Best-effort goodbye. `reason` is "disabled" (switched off for the
   *  chat) or "chat-deleted" (the chat, or all chats, is going); the wire
   *  vocabulary also reserves "uninstall", which this build does not send.
   *  The second argument is additive — a `(room)` handler keeps working. */
  deactivate?(room: Room, reason: string): HandlerResult | Promise<HandlerResult>;
  /** Before the agent turn, for every user message — requires the manifest
   *  to declare hooks:["message"]. The payload carries `{ text }`. */
  onMessage?(room: Room, message: { text: string }): HandlerResult | Promise<HandlerResult>;
  /** After a config edit; the new values are already merged into
   *  room.config before this runs. */
  onConfig?(room: Room): HandlerResult | Promise<HandlerResult>;
  /** Commands the manifest declares; `/<name>` routes here with the raw
   *  text after the command word. */
  commands?: { [name: string]: PluginCommand };
  /** Agent tools — requires the `agent.tools` permission, granted per chat;
   *  the tool event itself is refused without it. */
  tools?: { [name: string]: PluginTool };
}
