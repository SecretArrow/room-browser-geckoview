'use strict';

// OnHomeroom Daily — the app.onhomeroom.com routine as a Room chat plugin.
//
// A port of the standalone onhomeroom bot (bot.js, 1878 lines), reshaped for
// the sandbox: no Composer and no message pool — every sentence is composed
// LIVE by THIS chat's own AI through room.agent.generate, then re-checked by
// the original validation gate before it may leave. No endless loop and no
// sleeps — pacing is persisted timestamps, and one /step or run_task call
// executes exactly ONE bounded unit (< 30 s including the login check).
//
// Shape of a unit, by priority (the first one that acts wins the call):
//   1. answer a pending homeroom-bot DM card (question → generated reply,
//      go_ahead / done / proposal-approval cards → the original actions)
//   2. refresh the challenge board + work the next incomplete challenge's
//      next bounded step (join / one dwell chunk / vote / chat / feedback /
//      issue-file / DM nudge — the propose and build pipelines advance one
//      step per unit, driven by the bot's DM cards)
//   3. one vote on a promoted proposal (votes are always yes)
//   4. one chat reply in the configured channel, when not quiet and the text
//      gap has elapsed
//
// Dead code from the original was dropped on purpose (the /api/sessions
// agent-SSE flow, readline, the agent config block): the DM-driven flow is
// the one the site actually answers today. Everything else — headers, the
// 401 → one re-login → replay, the breathe pauses, the quiet rules, the
// state fields — is the original's behavior on the room SDK.
//
// Style: plain CommonJS, top level cheap (constants and functions only), all
// work inside handlers. room.log for diagnostics, cards for humans, short
// plain strings for tool results. No fs, no require, no process — only the
// room object the host injects.

var BASE = 'https://app.onhomeroom.com';

// One unit must fit the 30 s command/tool budget with room for a generation
// (20 s ceiling of its own): network calls are refused past this deadline.
var UNIT_DEADLINE_MS = 22000;
var FETCH_TIMEOUT_MS = 8000;
var CALL_RETRIES = 3;

// Caps the kv store imposes per value (16 KB): the original's file-backed
// lists were capped at 100 entries with no length limit; these caps keep the
// same behavior inside the quota (only the oldest, no longer read entries
// give way — the quiet window and the anti-repeat memory read the newest).
var CHAT_LOG_CAP = 40;
var RECENT_TEXTS_CAP = 60;
var FILED_CAP = 60;
var RUNS_CAP = 30;
var TEXT_CLIP = 200; // the avoid list clips at 200 chars host-side anyway

// The propose/build pipelines' fixed knobs (config.json's tasks.propose /
// tasks.buildApp blocks are not plugin config; their live values were these).
var PROPOSE_MAX_ROUNDS = 3;
var PROPOSE_PER_DAY = 1;
var BUILD_TARGET = 'usernode-2d5619'; // the community's app builder
var BUILD_RESTART_HOURS = 48;
var BUILD_MAX_RESTARTS = 2;

// The vote scan reads one app's promoted list per app; this bounds a unit.
var VOTE_SCAN_APPS = 8;

/* ==========================================================================
 *  The validation gate — VERBATIM from the original Composer.
 *  Nothing leaves this plugin without passing it: one casual English
 *  sentence, no . ! ? ; inside, never the characters ! : — – ? ;, no emoji,
 *  and at most maxWords words (24 by default, as configured).
 * ========================================================================*/

var FORBIDDEN = /[!?:;—–]/;
var EMOJI = /[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}\u{2B00}-\u{2BFF}]/u;

function validate(text, maxWords) {
  var t = String(text || '').trim().replace(/\s+/g, ' ');
  if (!t) throw new Error('empty text');
  if (/[.!?;]/.test(t)) throw new Error('sentence punctuation inside: ' + t);
  if (FORBIDDEN.test(t)) throw new Error('forbidden character in: ' + t);
  if (EMOJI.test(t)) throw new Error('emoji in: ' + t);
  if (t.split(/\s+/).length > (maxWords || 24)) throw new Error('too long: ' + t);
  return t;
}

/* titles + proposal bodies may end in a single period, nothing else */
function validateIdea(idea, maxWords) {
  var title = validate(idea.title, maxWords);
  var body = validate(String(idea.body || '').replace(/\.$/, ''), maxWords);
  return { title: title, body: body + '.' };
}

/* ==========================================================================
 *  Small helpers
 * ========================================================================*/

function errText(err) {
  if (err && typeof err === 'object' && typeof err.message === 'string' && err.message) {
    return err.message;
  }
  return String(err);
}

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function rand(lo, hi) {
  return Math.floor(lo + Math.random() * (hi - lo + 1));
}

function isoNow() {
  return new Date().toISOString();
}

/* seconds until (timestamp + gap) elapses; 0 when it already has */
function secondsUntil(iso, gapSeconds) {
  if (!iso) return 0;
  var at = new Date(iso).getTime();
  if (!Number.isFinite(at)) return 0;
  var wait = Math.ceil((at + gapSeconds * 1000 - Date.now()) / 1000);
  return wait > 0 ? wait : 0;
}

/* the Composer's used-title check, verbatim */
function usedBefore(fragment, usedTitles) {
  var f = String(fragment || '').toLowerCase();
  return (usedTitles || []).some(function (t) {
    return String(t || '').toLowerCase().includes(f);
  });
}

/* the Composer's app-name cleanup, verbatim */
function appRef(app) {
  var raw = app && (app.name || app.title);
  var ref = raw
    ? String(raw).replace(EMOJI, ' ').replace(FORBIDDEN, ' ').replace(/\./g, ' ')
    : String((app && app.slug) || '').replace(/-[0-9a-f]{4,}$/i, '').replace(/-/g, ' ');
  ref = ref.replace(/\s+/g, ' ').trim();
  return ref || 'this app';
}

/* ==========================================================================
 *  Config — the manifest's seven keys, with the same defaults the original
 *  carried in config.json's pacing block.
 * ========================================================================*/

function readConfig(room) {
  function num(key, fallback) {
    var value = Number(room.config.get(key));
    return Number.isFinite(value) && value > 0 ? value : fallback;
  }
  return {
    channel: String(room.config.get('channel') || 'general'),
    textGapS: num('text_gap_seconds', 60),
    taskGapS: num('task_gap_seconds', 180),
    maxWords: Math.max(1, Math.min(200, Math.floor(num('max_words', 24)))),
    dwellChunkS: num('dwell_chunk_seconds', 15),
    dwellTargetS: num('dwell_target_seconds', 180),
    quietMin: num('quiet_minutes', 10),
  };
}

/* ==========================================================================
 *  State — one kv key per data.json field of the original (plus the port's
 *  own lastActionAt / dwellProgress pacing anchors). Loaded with
 *  runCatching-style defaults like the original Store; saved key-by-key so
 *  only what changed is written.
 * ========================================================================*/

var STATE_KEYS = [
  'account', 'session', 'user', 'joinedApps', 'votedSessions', 'triedApps',
  'proposalsFiled', 'chatLog', 'challenges', 'challengeState', 'proposePipeline',
  'buildPipeline', 'dmApproved', 'dmAnswered', 'feedbackSent', 'proposeAppsUsed',
  'buildsRequested', 'lastInvalidReplaceDay', 'lastTextAt', 'lastActionAt',
  'recentTexts', 'dwellProgress', 'stats', 'runs',
];

var STATE_DEFAULTS = {
  account: null,            // { email, password } — config.json's account block
  session: { cookie: null, savedAt: null },
  user: null,               // { id, username, role }
  joinedApps: [],           // slugs
  votedSessions: [],        // session ids already voted on
  triedApps: [],            // slugs actually spent time in
  proposalsFiled: [],       // { app, issueId, issueNumber, title, at }
  chatLog: [],              // { at, conversationId, content }
  challenges: [],           // last seen challenge board snapshot
  challengeState: {},       // per-challenge { attempts, lastAction, lastAt }
  proposePipeline: null,    // the "send a proposal to the vote" tracker
  buildPipeline: null,      // the "build a new app for the community" tracker
  dmApproved: [],           // bot proposal session ids already approved in the DM
  dmAnswered: [],           // bot card message ids already answered
  feedbackSent: [],         // { app, text, at }
  proposeAppsUsed: [],      // apps the proposal rounds already targeted
  buildsRequested: [],      // titles of community app requests already filed
  lastInvalidReplaceDay: null,
  lastTextAt: null,         // when the last text submission went out
  lastActionAt: null,       // when the last unit ran (task pacing anchor)
  recentTexts: [],          // anti-repeat memory, feeds avoid
  dwellProgress: {},        // slug -> seconds of activity already reported
  stats: { runs: 0, votesCast: 0, messagesSent: 0, appsTried: 0, proposalsFiled: 0, feedbackSent: 0 },
  runs: [],                 // { at, note } — the original's run summaries
};

function coerceStateValue(key, value) {
  var fallback = STATE_DEFAULTS[key];
  var wantsContainer = Array.isArray(fallback) || (fallback && typeof fallback === 'object');
  if (typeof value === 'string' && wantsContainer) {
    // belt and braces: a container that arrived as text parses back — plain
    // string keys (the pacing timestamps) must stay strings
    try { value = JSON.parse(value); } catch (err) { value = undefined; }
  }
  if (Array.isArray(fallback)) {
    return Array.isArray(value) ? value : fallback.slice();
  }
  if (fallback && typeof fallback === 'object') {
    if (Object.keys(fallback).length === 0) {
      /* a plain map default (challengeState, dwellProgress): pass the stored
         object through when it is one, so its own keys survive */
      return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
    }
    var out = {};
    var names = Object.keys(fallback);
    for (var i = 0; i < names.length; i++) {
      out[names[i]] =
        value && typeof value === 'object' && !Array.isArray(value) &&
          value[names[i]] !== undefined
          ? value[names[i]]
          : fallback[names[i]];
    }
    if (key === 'stats') {
      // counters survive partial snapshots
      var src = value && typeof value === 'object' && !Array.isArray(value) ? value : {};
      var namesExtra = Object.keys(src);
      for (var j = 0; j < namesExtra.length; j++) {
        if (typeof src[namesExtra[j]] === 'number') out[namesExtra[j]] = src[namesExtra[j]];
      }
    }
    return out;
  }
  // scalar / nullable keys: keep when the type fits, else the default
  if (typeof fallback === 'string') return typeof value === 'string' ? value : fallback;
  return value === undefined || value === null ? fallback : value;
}

function loadState(room) {
  var state = {};
  var loaded = {};
  for (var i = 0; i < STATE_KEYS.length; i++) {
    var key = STATE_KEYS[i];
    var raw = null;
    try { raw = room.storage.kv.get(key); } catch (err) { raw = null; }
    state[key] = coerceStateValue(key, raw);
    try { loaded[key] = JSON.stringify(state[key]); } catch (err) { loaded[key] = null; }
  }
  return { state: state, loaded: loaded };
}

function saveState(room, state, loaded) {
  // caps that keep every value inside the kv store's 16 KB-per-value quota;
  // only the oldest entries give way and nothing the logic still reads
  if (Array.isArray(state.chatLog)) state.chatLog = state.chatLog.slice(0, CHAT_LOG_CAP);
  if (Array.isArray(state.recentTexts)) state.recentTexts = state.recentTexts.slice(0, RECENT_TEXTS_CAP);
  if (Array.isArray(state.proposalsFiled)) state.proposalsFiled = state.proposalsFiled.slice(0, FILED_CAP);
  if (Array.isArray(state.feedbackSent)) state.feedbackSent = state.feedbackSent.slice(0, FILED_CAP);
  if (Array.isArray(state.buildsRequested)) state.buildsRequested = state.buildsRequested.slice(0, FILED_CAP);
  if (Array.isArray(state.runs)) state.runs = state.runs.slice(0, RUNS_CAP);
  for (var i = 0; i < STATE_KEYS.length; i++) {
    var key = STATE_KEYS[i];
    var encoded;
    try { encoded = JSON.stringify(state[key]); } catch (err) { encoded = undefined; }
    if (encoded === undefined) continue; // not representable: leave the old value
    if (loaded && encoded === loaded[key]) continue; // unchanged: no write
    try {
      room.storage.kv.set(key, state[key]);
      if (loaded) loaded[key] = encoded;
    } catch (err) {
      room.log.warn('kv write of ' + key + ' failed: ' + errText(err));
    }
  }
}

function pushUnique(state, key, value) {
  var list = state[key];
  if (!Array.isArray(list)) list = state[key] = [];
  if (!list.includes(value)) list.push(value);
}

/* the original's rememberText: pacing anchor + never repeat a sent text */
function rememberText(state, text) {
  state.lastTextAt = isoNow();
  var list = Array.isArray(state.recentTexts) ? state.recentTexts : [];
  list.unshift(String(text).slice(0, TEXT_CLIP));
  state.recentTexts = list.slice(0, RECENT_TEXTS_CAP);
}

/* ==========================================================================
 *  The Composer, replaced — every text is written live by THIS chat's own AI
 *  (room.agent.generate) out of structured fields, then gated by validate.
 *  On ANY failure — generate throws, the answer is blank, validate rejects —
 *  the caller stays quiet, exactly like the original: nothing invalid is
 *  ever sent.
 *
 *  Every kind mirrors one original Composer method's intent; proposal and
 *  build-app use ONE generation answered as "TITLE | BODY", split on ' | '
 *  (documented in the README) — the body then keeps validateIdea's
 *  single-trailing-period rule.
 * ========================================================================*/

function buildAvoid(state, extraAvoid) {
  var avoid = [];
  var recents = Array.isArray(state.recentTexts) ? state.recentTexts : [];
  var recent = recents.slice(0, 25);
  for (var i = 0; i < recent.length; i++) {
    if (typeof recent[i] === 'string' && recent[i]) avoid.push(recent[i].slice(0, TEXT_CLIP));
  }
  var extra = Array.isArray(extraAvoid) ? extraAvoid : [];
  for (var j = 0; j < extra.length; j++) {
    if (typeof extra[j] === 'string' && extra[j]) avoid.push(extra[j].slice(0, TEXT_CLIP));
  }
  return avoid.slice(0, 50);
}

async function askAgent(room, cfg, state, spec) {
  var options = {
    purpose: spec.purpose,
    instruction: spec.instruction,
    style: {
      language: 'en',
      maxWords: cfg.maxWords,
      oneSentence: true,
      forbiddenChars: '!:—–?;',
      tone: 'casual',
    },
    avoid: buildAvoid(state, spec.extraAvoid),
  };
  if (spec.context) options.context = spec.context;
  if (spec.hint) options.hint = spec.hint;
  try {
    return await room.agent.generate(options);
  } catch (err) {
    room.log.warn('generate failed (' + errText(err) + ') — staying quiet');
    return null;
  }
}

async function compose(room, cfg, state, spec) {
  var raw = await askAgent(room, cfg, state, spec);
  if (raw === null || raw === undefined) return null;
  try {
    return validate(raw, cfg.maxWords);
  } catch (err) {
    room.log.warn('validate rejected the generation (' + errText(err) + ') — staying quiet');
    return null;
  }
}

async function composeIdea(room, cfg, state, spec) {
  var raw = await askAgent(room, cfg, state, spec);
  if (raw === null || raw === undefined) return null;
  var text = String(raw);
  var at = text.indexOf(' | ');
  if (at <= 0) {
    room.log.warn('the generation did not answer "TITLE | BODY" — retry next step');
    return null;
  }
  var title = text.slice(0, at);
  var body = text.slice(at + 3);
  try {
    return validateIdea({ title: title, body: body }, cfg.maxWords);
  } catch (err) {
    room.log.warn('validate rejected the idea (' + errText(err) + ') — retry next step');
    return null;
  }
}

/* ---- the original Composer's topic detection, ported verbatim ---------- */

var STOP = new Set(('a an the and or but so if then than that this these those there here ' +
  'it its is are was were be been being am i you he she they we me my your his her ' +
  'their our us them of to in on at by for with from about into over after before ' +
  'under up down out off again once just also very can will would should could do ' +
  'does did done not no yes ok okay hey hi hello yo thanks thank please really ' +
  'quite too more most much many any some such get got getting go going went come ' +
  'came one two first second now new like well yeah yep nah hmm gonna wanna kinda ' +
  'tho though although while when what which who whom whose why how where all both ' +
  'each few nor own make made makes take took look looks looking').split(' '));

function topicFrom(text, max) {
  var words = String(text || '').toLowerCase()
    .replace(/[^a-z0-9\s]/g, ' ').split(/\s+/)
    .filter(function (w) { return w.length >= 4 && !STOP.has(w); });
  var unique = [];
  for (var i = 0; i < words.length; i++) {
    if (unique.indexOf(words[i]) < 0) unique.push(words[i]);
  }
  return unique.slice(0, max || 4);
}

var CHAT_TOPICS = [
  'leaderboard', 'daily puzzle', 'voting rounds', 'season rewards', 'new apps',
  'challenge board', 'streaks', 'activity feed', 'tier list', 'feedback box',
  'change votes', 'updates',
];

function chatTopic(messages, myUsername) {
  var others = (messages || []).filter(function (m) {
    return m.sender && m.sender.username !== myUsername && !m.deleted;
  });
  var lines = others.slice(-4).map(function (m) { return m.content || ''; }).join(' ').toLowerCase();
  var known = CHAT_TOPICS.filter(function (t) { return lines.includes(t); });
  if (known.length) return pick(known);
  var words = topicFrom(lines, 6);
  return words.length ? pick(words) : null;
}

/* ---- the per-kind generate specs ---------------------------------------- */

/* Composer.chatReply: the detected kind of the last message is the instruction */
function chatReplySpec(msgs, myUsername, extraAvoid) {
  var others = (msgs || []).filter(function (m) {
    return m.sender && m.sender.username !== myUsername && !m.deleted;
  });
  var last = others[others.length - 1];
  var who = last && last.sender && last.sender.username ? last.sender.username : null;
  var t = last ? String(last.content || '').toLowerCase() : '';
  var context = others.slice(-6).map(function (m) {
    return (m.sender && m.sender.username ? m.sender.username : 'someone') + ': ' + String(m.content || '');
  }).join('\n');
  var instruction;
  var hint;
  if (/\b(weekend|saturday|sunday)\b/.test(t)) {
    instruction = 'Reply to the last message, which is about the weekend, with one short cheerful line';
  } else if (/\b(thanks|thank you|thx|ty)\b/.test(t)) {
    instruction = 'Reply to the last message, which thanks someone, with one brief casual you are welcome';
  } else if (t.includes('?')) {
    instruction = 'Reply to the last message, which asks a question nobody answered, with one honest line saying you do not know either but you are curious';
  } else if (/^(hi|hey|hello|yo|morning|afternoon|evening|gm|gn)\b/.test(t) || /good (morning|afternoon|evening)/.test(t)) {
    instruction = 'Reply to the greeting with one short casual hello';
    if (who) hint = 'the greeter is ' + who + ', naming them is fine but not required';
  } else {
    var topic = chatTopic(msgs, myUsername) || pick(CHAT_TOPICS);
    instruction = 'Add one casual line to the channel about the ' + topic + ', like a member who actually uses the site';
    hint = 'sound like a regular member typing quickly, never like an assistant';
  }
  return { purpose: 'chat-reply', instruction: instruction, context: context, hint: hint, extraAvoid: extraAvoid };
}

/* Composer.feedback: the app name plus one feature angle */
var FEATS = ['search', 'sorting', 'dark mode', 'settings page', 'back button', 'font size',
  'color contrast', 'save flow', 'first run intro', 'loading state', 'share button',
  'help section'];

function feedbackSpec(app, sentTexts) {
  var ref = appRef(app);
  var feat = pick(FEATS);
  return {
    purpose: 'feedback',
    instruction: 'Write one short casual feedback note about ' + ref + ', touching on ' + feat +
      ', one honest observation like a quick note a real user leaves',
    context: 'app: ' + ref + ' (slug ' + ((app && app.slug) || '') + ')',
    hint: 'keep it small and specific, no technical deep dive',
    extraAvoid: sentTexts,
  };
}

/* Composer.proposalIdea: the app's domain suggests the angle */
var DOMAINS = [
  { test: /puzzle|game|quiz|word|trivia|tier|chess|card/,
    shorts: ['a streak counter', 'a personal best line', 'a gentle hint button', 'a daily challenge marker'],
    longs: ['small streak counter on the main screen', 'personal best line under each play card',
      'hint button that costs one retry', 'daily challenge marker next to every puzzle'] },
  { test: /todo|task|list|note|kanban/,
    shorts: ['a progress bar per list', 'simple tags', 'an archive action'],
    longs: ['progress bar on top of every list', 'simple tag system for filtering items',
      'archive action for finished items'] },
  { test: /recipe|food|cook|meal/,
    shorts: ['a favorites tab', 'a servings scaler', 'a shopping list'],
    longs: ['favorites tab for dishes i cook often', 'servings scaler on every recipe',
      'shopping list built from saved recipes'] },
  { test: /timer|focus|study|pomo|habit/,
    shorts: ['a session counter', 'a weekly summary', 'a rest reminder'],
    longs: ['counter of finished sessions for today', 'small weekly summary at the top',
      'gentle rest reminder between long sessions'] },
  { test: /poll|vote|survey|class/,
    shorts: ['a poll history', 'an anonymous mode'],
    longs: ['short history of past polls and results', 'anonymous results mode for shy voters'] },
];
var DEFAULT_DOMAIN = {
  shorts: ['a small search box', 'a compact view', 'a last used marker', 'a friendlier empty state'],
  longs: ['small search box above the main list', 'compact view for long lists',
    'last used marker on every item', 'empty state with one short hint'],
};

function domainFor(app) {
  var hay = [app && app.slug, app && app.name, app && (app.description || app.tagline || '')]
    .filter(Boolean).join(' ').toLowerCase();
  for (var i = 0; i < DOMAINS.length; i++) {
    if (DOMAINS[i].test.test(hay)) return DOMAINS[i];
  }
  return DEFAULT_DOMAIN;
}

function proposalSpec(app, usedTitles) {
  var ref = appRef(app);
  var d = domainFor(app);
  var pairs = d.shorts.map(function (short, i) { return { short: short, long: d.longs[i] }; });
  var open = pairs.filter(function (p) {
    return !usedBefore(p.short.replace(/^(an?)\s+/, ''), usedTitles);
  });
  var pair = open.length ? pick(open) : pick(pairs);
  return {
    purpose: 'proposal',
    instruction: 'Write one small useful change proposal for ' + ref +
      '. Answer with exactly two parts separated by " | ": a short title of at most 8 words that starts with "Add", then a one sentence body in simple everyday English naming the change and its everyday benefit',
    context: 'app: ' + ref + '\nslug: ' + ((app && app.slug) || '') +
      (usedTitles.length ? '\ntitles already filed, stay different:\n' + usedTitles.slice(0, 10).join('\n') : ''),
    hint: 'angle to propose: ' + pair.long,
    extraAvoid: usedTitles,
  };
}

/* Composer.buildAppIdea: the fixed idea list, as the hint */
var BUILD_IDEAS = [
  { name: 'Study Timer', long: 'pomodoro study timer with a big start button and a soft beep' },
  { name: 'Flashcard Quiz', long: 'flashcard quiz where you flip question and answer pairs and keep score' },
  { name: 'Class Poll', long: 'class poll with one question, four options and big live result bars' },
  { name: 'Reading Log', long: 'reading log to track books, pages reached and minutes per week' },
  { name: 'Habit Tracker', long: 'habit tracker with daily checkboxes, a streak number and a monthly grid' },
  { name: 'Group Scheduler', long: 'weekly grid where everyone marks free times and shared slots light up' },
  { name: 'Word of the Day', long: 'word of the day board with a short meaning and one example' },
];

function buildSpec(usedTitles) {
  var open = BUILD_IDEAS.filter(function (i) { return !usedBefore(i.name, usedTitles); });
  var idea = open.length ? pick(open) : pick(BUILD_IDEAS);
  return {
    purpose: 'build-app',
    instruction: 'Write one request for a new small community app. Answer with exactly two parts separated by " | ": a short title that starts with "Create a new app called" plus the app name, then a one sentence body describing what the app does and how it feels on a phone',
    context: usedTitles.length
      ? 'requests already filed, stay different:\n' + usedTitles.slice(0, 10).join('\n')
      : 'no community app requests filed yet',
    hint: 'app to request: ' + idea.name + ', ' + idea.long,
    extraAvoid: usedTitles,
  };
}

/* Composer.dmReply: the bot's question, branched the same way */
function dmReplySpec(questionText) {
  var t = String(questionText || '').toLowerCase();
  var instruction;
  if (/skip|intro|welcome|onboard/.test(t)) {
    instruction = 'Answer the bot asking about the intro or a skip option, say a small skip link would be plenty for you';
  } else if (/colou?r|theme|dark|light mode/.test(t)) {
    instruction = 'Answer the bot asking about theme or colors, either theme works for you so go with what reads better';
  } else if (/\bname\b|title|call it/.test(t)) {
    instruction = 'Answer the bot asking about the name or title to use, the current name works fine for you so keep it simple';
  } else {
    instruction = 'Answer the bot asking you to pick between options, either option works for you so go with whatever is simplest';
  }
  return {
    purpose: 'dm-reply',
    instruction: instruction,
    context: 'the bot asked: ' + String(questionText || '').slice(0, 500),
  };
}

/* Composer.nudge */
function nudgeSpec(title) {
  var t = String(title || '')
    .replace(/^create a new app( called)?:?\s*/i, '')
    .replace(/^[:\s]+/, '')
    .trim() || 'the app';
  return {
    purpose: 'nudge',
    instruction: 'Write one polite checking-in nudge to the community builder about the ' + t + ' request',
    hint: 'no rush at all, keep it light',
  };
}

/* ==========================================================================
 *  Api — the original's fetch wrapper on room.net.fetch: same headers
 *  (Content-Type, X-Requested-With, Cookie when held), retry on thrown
 *  network errors, 401 -> ONE re-login -> replay, breathe() between calls.
 *  The dead /api/sessions agent flow is gone; the DM-driven inventory is
 *  what remains. Redirects auto-follow (the original never branched on 3xx).
 * ========================================================================*/

function makeApi(room, state, cfg, log, creds) {

  var cookie = state.session && state.session.cookie ? String(state.session.cookie) : null;
  var user = state.user || null;
  var deadline = Date.now() + UNIT_DEADLINE_MS;

  function pause(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  /* the original's small human-ish pause between API calls (500-1800 ms) */
  async function breathe() {
    if (Date.now() >= deadline) return;
    await pause(rand(500, 1800));
  }

  function outOfTime() {
    return Date.now() >= deadline;
  }

  function outOfTimeError() {
    var err = new Error('the unit ran out of time before finishing, retry the next step');
    err.unitDeadline = true;
    return err;
  }

  async function call(method, urlPath, body, opts) {
    opts = opts || {};
    var url = urlPath.indexOf('http') === 0 ? urlPath : BASE + urlPath;
    var headers = { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' };
    if (cookie && !opts.noAuth) headers.Cookie = cookie;

    var res = null;
    var json = null;
    var text = '';
    for (var attempt = 1; attempt <= CALL_RETRIES; attempt++) {
      if (outOfTime()) throw outOfTimeError();
      try {
        res = await room.net.fetch(url, {
          method: method,
          headers: headers,
          body: body === undefined ? undefined : JSON.stringify(body),
          timeoutMs: Math.max(1000, Math.min(FETCH_TIMEOUT_MS, deadline - Date.now())),
        });
        text = res.body;
        json = res.json();
        break;
      } catch (err) {
        if (attempt === CALL_RETRIES) throw new Error('network: ' + errText(err));
        await pause(1500 * attempt);
      }
    }

    if (res.status === 401 && !opts.noAuth && !opts._retried) {
      log('session expired, re-logging in');
      var again = await login();
      if (again.ok) {
        opts._retried = true;
        return call(method, urlPath, body, opts);
      }
    }
    return { status: res.status, json: json, text: text, headers: res.headers };
  }

  function get(p, o) { return call('GET', p, undefined, o); }
  function post(p, b, o) { return call('POST', p, b, o); }

  /* ------------------------------------------------------------------ auth
   * POST /api/auth/login {username: email, password}; the session cookie
   * comes out of the JOINED set-cookie header via /session=([^;]+)/ — the
   * regex still extracts it — then /api/auth/me names the user. */

  async function login() {
    var email = creds && creds.email;
    var password = creds && creds.password;
    if (!email || !password) {
      return { ok: false, error: 'no credentials saved, run /login email password' };
    }
    var res;
    try {
      res = await room.net.fetch(BASE + '/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
        body: JSON.stringify({ username: email, password: password }),
        timeoutMs: Math.max(1000, Math.min(FETCH_TIMEOUT_MS, deadline - Date.now())),
      });
    } catch (err) {
      return { ok: false, error: 'network: ' + errText(err) };
    }
    if (res.status !== 200) {
      var j = res.json();
      var words = j && (j.error || j.message || j.title);
      return { ok: false, error: words ? String(words) : 'HTTP ' + res.status };
    }
    var setCookie = (res.headers && res.headers['set-cookie']) || '';
    var m = String(setCookie).match(/session=([^;]+)/);
    if (!m) return { ok: false, error: 'login response did not include a session cookie' };
    cookie = 'session=' + m[1];
    state.session = { cookie: cookie, savedAt: isoNow() };
    await breathe();
    /* _retried keeps a 401 on /me from recursing into login again: the
       original answered the same situation with "cookie set, /me failed" */
    var me = await get('/api/auth/me', { _retried: true });
    if (me.status === 200 && me.json && me.json.user) {
      user = { id: me.json.user.id, username: me.json.user.username, role: me.json.user.role };
      state.user = user;
      log('signed in as ' + user.username);
      return { ok: true, user: user };
    }
    log('cookie set, but /me lookup failed, continuing anyway');
    return { ok: true, user: null };
  }

  async function ensureLoggedIn() {
    if (cookie) {
      var me = await get('/api/auth/me');
      if (me.status === 200 && me.json && me.json.user) {
        user = { id: me.json.user.id, username: me.json.user.username, role: me.json.user.role };
        state.user = user;
        return { ok: true, user: user };
      }
    }
    return login();
  }

  /* ------------------------------------------------------------- discovery */

  async function listApps() {
    var res = await get('/api/apps');
    var apps = (res.json && (res.json.apps || res.json)) || [];
    await breathe();
    return Array.isArray(apps) ? apps : [];
  }

  async function conversations() {
    var res = await get('/api/conversations');
    return (res.json && res.json.conversations) || [];
  }

  async function challengesBoard() {
    var ev = await get('/api/v4/season-events?include_past=0');
    await breathe();
    var events = (ev.json && ev.json.data) || [];
    var eventId = null;
    if (Array.isArray(events) && events.length) {
      var active = events.find(function (e) { return e.is_current; }) || events[0];
      eventId = active && active.id;
    }
    if (!eventId) eventId = 10; // known Season 2 fallback
    var res = await get('/challenges-api/challenges?season_event_id=' + eventId);
    return (res.json && res.json.data) || [];
  }

  async function promoted(appSlug) {
    var res = await get('/api/apps/' + appSlug + '/promoted');
    await breathe();
    return (res.json && res.json.promoted) || [];
  }

  /* -------------------------------------------------- homeroom bot DM */

  var botConvId = undefined;

  async function botConversationId() {
    if (botConvId !== undefined) return botConvId;
    var convs = await conversations();
    var bot = convs.find(function (c) {
      return c.homeroom_bot === true || c.homeroomBot === true ||
        (c.title || '').toLowerCase().includes('homeroom bot');
    });
    botConvId = bot ? bot.id : null;
    return botConvId;
  }

  async function dmCards() {
    var convId = await botConversationId();
    if (!convId) return [];
    var res = await get('/api/conversations/' + convId + '/messages?limit=50');
    var msgs = (res.json && res.json.messages) || [];
    await breathe();
    return msgs
      .map(function (m) {
        return { id: m.id, content: m.content, hb: m.metadata && m.metadata.homeroomBot };
      })
      .filter(function (x) { return x.hb && typeof x.hb === 'object'; });
  }

  async function botAction(actionId, choice, answers) {
    var body = answers ? { choice: choice, answers: answers } : { choice: choice };
    var res = await post('/api/conversations/homeroom-bot/actions/' + actionId, body);
    return res.status === 200 && res.json && res.json.ok ? (res.json.label || 'ok') : null;
  }

  /* prompt-type bot actions are answered by quoting the card with the
     button's label as a threaded reply (same as tapping it in the UI) */
  async function botPrompt(convId, content, replyToId, threadRootId) {
    var body = {
      content: content,
      idempotency_key: 'hb-' + Date.now() + '-' + Math.floor(Math.random() * 1e9),
    };
    if (replyToId) body.reply_to_id = replyToId;
    if (threadRootId) body.thread_root_id = threadRootId;
    var r = await post('/api/conversations/' + convId + '/messages', body);
    return (r.status === 200 || r.status === 201) && r.json && r.json.message ? r.json.message : null;
  }

  async function sendDmNote(content) {
    var convId = await botConversationId();
    if (!convId) return null;
    return sendMessage(convId, content);
  }

  /* -------------------------------------------------------------- feedback */

  async function sendFeedback(appSlug, text) {
    var res = await post('/api/feedback', {
      description: text,
      target: 'app',
      appSlug: appSlug,
    });
    return (res.status === 200 || res.status === 201) ? (res.json || true) : null;
  }

  /* ------------------------------------------------------------ app try-in */

  async function iframeToken(slug) {
    var tok = await get('/api/iframe-token?app=' + slug);
    if (tok.status !== 200 || !tok.json || !tok.json.token) return null;
    return tok.json.token;
  }

  /* ONE frame visit to the app server before the first activity chunk — the
     original registered the visit the same way (redirect follows). */
  async function visitApp(slug, token) {
    try {
      await room.net.fetch('https://' + slug + '.onhomeroom.com/?token=' + token, {
        method: 'GET',
        headers: { 'X-Requested-With': 'XMLHttpRequest' },
        timeoutMs: Math.max(1000, Math.min(FETCH_TIMEOUT_MS, deadline - Date.now())),
      });
      return true;
    } catch (err) {
      return false; /* app servers can be slow — not fatal */
    }
  }

  async function activity(slug, seconds) {
    var r = await post('/api/apps/' + slug + '/activity', { seconds: seconds });
    return { ok: r.status === 200 && !!(r.json && r.json.ok) };
  }

  /* ------------------------------------------------------------------ vote */

  async function vote(sessionId) {
    var r = await post('/api/sessions/' + sessionId + '/vote', { vote: 'yes' });
    return r.status === 200 && r.json && !!r.json.ok;
  }

  /* the DM decision path needs the raw outcome: a 404 means the card went
     stale (approved by someone else or cleaned up), not a transient failure */
  async function voteWithStatus(sessionId) {
    var r = await post('/api/sessions/' + sessionId + '/vote', { vote: 'yes' });
    return {
      ok: r.status === 200 && r.json && !!r.json.ok,
      gone: r.status === 404,
    };
  }

  /* ------------------------------------------------------- chat in channel */

  async function channelMessages(convId, limit) {
    var r = await get('/api/conversations/' + convId + '/messages?limit=' + (limit || 12));
    return (r.json && r.json.messages) || [];
  }

  async function sendMessage(convId, content) {
    var r = await post('/api/conversations/' + convId + '/messages', { content: content });
    return (r.status === 200 || r.status === 201) && r.json && r.json.message ? r.json.message : null;
  }

  /* ------------------------------------------------------------- proposals */

  async function fileIssue(slug, title, body) {
    var r = await post('/api/apps/' + slug + '/issues', { title: title, description: body });
    var ok = r.status === 200 || r.status === 201;
    return ok && r.json && r.json.issue ? r.json.issue : null;
  }

  async function joinApp(slug) {
    var r = await post('/api/apps/' + slug + '/membership', { joined: true });
    return r.status === 200 && r.json && !!r.json.ok;
  }

  return {
    call: call,
    login: login,
    ensureLoggedIn: ensureLoggedIn,
    listApps: listApps,
    conversations: conversations,
    challengesBoard: challengesBoard,
    promoted: promoted,
    botConversationId: botConversationId,
    dmCards: dmCards,
    botAction: botAction,
    botPrompt: botPrompt,
    sendDmNote: sendDmNote,
    sendFeedback: sendFeedback,
    iframeToken: iframeToken,
    visitApp: visitApp,
    activity: activity,
    vote: vote,
    voteWithStatus: voteWithStatus,
    channelMessages: channelMessages,
    sendMessage: sendMessage,
    fileIssue: fileIssue,
    joinApp: joinApp,
    breathe: breathe,
    outOfTime: outOfTime,
  };
}

/* ==========================================================================
 *  The planner — maps a challenge to an automated action, verbatim from the
 *  original: things only a real human can do are reported honestly, never
 *  faked.
 * ========================================================================*/

function planFor(challenge) {
  var t = String(challenge.goal || '') + ' ' + String(challenge.task || '');
  t = t.toLowerCase();
  if (/invite|refer|referral|\bfriends?\b|share.*link/.test(t)) return 'manual';
  if (/connect\b.*(\bx\b|twitter|github)|\bgithub\b/.test(t)) return 'manual';
  if (/feedback|report|bug|suggest/.test(t)) return 'feedback';
  if (/build|create/.test(t) && /(community|app)/.test(t)) return 'buildApp';
  if (/try/.test(t) && /app/.test(t)) return 'tryApps';
  if (/\buse\b|spend|minutes/.test(t) && /app/.test(t)) return 'tryApps';
  if (/propose|proposal/.test(t) && /change|vote|accept|merge/.test(t)) return 'propose';
  if (/vote/.test(t)) return 'vote';
  if (/\bjoin\b/.test(t)) return 'join';
  if (/message|chat|discuss|comment|say hello|introduce/.test(t)) return 'chat';
  return null; // unknown — reported, not faked
}

/* the board snapshot, with the same completion announcement */
function snapshotChallenges(ctx, challenges) {
  var before = ctx.state.challenges || [];
  ctx.state.challenges = (challenges || []).map(function (c) {
    return {
      id: c.id,
      goal: c.goal,
      task: c.task || '',
      current: c.progress && c.progress.current,
      target: c.progress && c.progress.target,
      done: !!(c.progress && c.progress.done) || !!c.completed,
      reward: c.reward,
      at: isoNow(),
    };
  });
  for (var i = 0; i < ctx.state.challenges.length; i++) {
    var now = ctx.state.challenges[i];
    var was = before.find(function (b) { return b.id === now.id; });
    if (was && !was.done && now.done) {
      ctx.log('challenge complete. "' + now.goal + '" (' + now.reward + ')');
    }
  }
}

function ensureChallengeState(ctx, id) {
  var cs = ctx.state.challengeState;
  if (!cs[id]) cs[id] = { attempts: 0, lastAction: null, lastAt: null };
  return cs[id];
}

/* ==========================================================================
 *  One bounded unit — the priorities
 * ========================================================================*/

function note(ctx, text) {
  ctx.notes.push(text);
}

/* run one api call, turning a failure into a logged note + null */
async function safe(ctx, label, fn) {
  try {
    return await fn();
  } catch (err) {
    if (err && err.unitDeadline) throw err; // the deadline propagates
    ctx.room.log.warn(label + ' failed (' + errText(err) + ')');
    return null;
  }
}

async function listAppsOnce(ctx) {
  if (ctx.appsCache !== null && ctx.appsCache !== undefined) return ctx.appsCache;
  var apps = await safe(ctx, 'the app list', function () { return ctx.api.listApps(); });
  ctx.appsCache = apps;
  if (!apps) note(ctx, 'the app list was unreadable');
  return apps;
}

/* ---- priority 1: answer pending homeroom-bot DM cards --------------------
 * answerBotQuestions + taskDecisions from the original, one card per unit:
 * questions get a generated reply; decide / done / proposal cards get the
 * actions a member would tap. */

async function answerDmCards(ctx) {
  var state = ctx.state;
  var cfg = ctx.cfg;
  var cards = await safe(ctx, 'the bot DM', function () { return ctx.api.dmCards(); });
  if (!cards) return null;

  /* open questions first — they block builds */
  var q = cards.find(function (x) {
    return x.hb.kind === 'question' && x.hb.status === 'open' && !state.dmAnswered.includes(x.id);
  });
  if (q) {
    var waitS = secondsUntil(state.lastTextAt, cfg.textGapS);
    if (waitS > 0) {
      return { summary: 'text pacing, next submission in ' + waitS + 's (a bot question is waiting)', style: 'info' };
    }
    var text = await compose(ctx.room, cfg, state, dmReplySpec(q.content || (q.hb && q.hb.issueTitle) || ''));
    if (!text) {
      pushUnique(state, 'dmAnswered', q.id);
      return {
        summary: 'bot asked a question on ' + (q.hb.appName || 'a request') +
          ' — no safe answer composed, needs a human',
        style: 'warn',
      };
    }
    var sent = await safe(ctx, 'the bot question reply', function () { return ctx.api.sendDmNote(text); });
    if (sent) {
      pushUnique(state, 'dmAnswered', q.id);
      rememberText(state, text);
      return {
        summary: 'answered the bot question on ' + (q.hb.appName || 'a request') + ': ' + text,
        style: 'success',
      };
    }
    return { summary: 'could not answer the bot question yet, retry next step', style: 'warn' };
  }

  /* then the decision cards */
  for (var i = 0; i < cards.length; i++) {
    var x = cards[i];
    var hb = x.hb || {};
    if (hb.status !== 'open' || !Array.isArray(hb.actions) || !hb.actions.length) continue;
    if (state.dmAnswered.includes(x.id)) continue;
    var ids = hb.actions.map(function (a) { return a.id; });

    /* green-light a new community app (prompt cards answer as quoted replies) */
    if (hb.said === 'decide' && ids.includes('go_ahead')) {
      var act = hb.actions.find(function (a) { return a.id === 'go_ahead'; });
      var label = null;
      if (act && act.type === 'prompt') {
        var convId = await safe(ctx, 'the bot conversation', function () { return ctx.api.botConversationId(); });
        if (convId) {
          label = await safe(ctx, 'the go_ahead reply', function () {
            return ctx.api.botPrompt(convId, act.label || 'Go ahead', x.id, hb.threadRootId || x.id);
          });
        }
      } else {
        label = await safe(ctx, 'the go_ahead action', function () {
          return ctx.api.botAction((act && act.id) || hb.actionId, 'go_ahead');
        });
      }
      if (label) {
        pushUnique(state, 'dmAnswered', x.id);
        return {
          summary: 'green-lit "' + (hb.requestTitle || hb.issueTitle || 'the request') + '" — the builder is on it',
          style: 'success',
        };
      }
      return { summary: 'could not answer the go_ahead card, retry next step', style: 'warn' };
    }

    /* "this looks done already" — agreeing honestly closes the loop */
    if (hb.said === 'done' && ids.includes('yes') && ids.includes('missing')) {
      var doneLabel = await safe(ctx, 'the done-check card', function () {
        return ctx.api.botAction(hb.actionId, 'yes');
      });
      if (doneLabel) {
        pushUnique(state, 'dmAnswered', x.id);
        return {
          summary: 'confirmed "' + (hb.issueTitle || 'the request') + '" is already shipped' +
            (hb.appName ? ' on ' + hb.appName : '') + ', proposed to close',
          style: 'success',
        };
      }
      return { summary: 'could not answer the done-check card, retry next step', style: 'warn' };
    }

    /* a finished change is ready — approve it like a happy member would */
    if (hb.kind === 'proposal') {
      var appr = hb.actions.find(function (a) { return a.id === 'approve' && a.sessionId; });
      if (appr && state.votedSessions.includes(appr.sessionId)) {
        pushUnique(state, 'dmApproved', appr.sessionId); // already voted in the scan — remember only
        continue;
      }
      if (appr && !state.dmApproved.includes(appr.sessionId)) {
        var r = await safe(ctx, 'the proposal approval', function () {
          return ctx.api.voteWithStatus(appr.sessionId);
        });
        if (r && r.ok) {
          pushUnique(state, 'dmApproved', appr.sessionId);
          state.stats.votesCast += 1;
          return {
            summary: 'approved "' + (hb.issueTitle || 'the change') + '", session #' + appr.sessionId,
            style: 'success',
          };
        }
        if (r && r.gone) {
          pushUnique(state, 'dmAnswered', x.id);
          return {
            summary: '"' + (hb.issueTitle || 'the change') + '" — session already gone, nothing to click',
            style: 'info',
          };
        }
        return { summary: 'could not approve session #' + appr.sessionId + ' yet, retry next step', style: 'warn' };
      }
    }
  }
  return null;
}

/* ---- priority 2: the challenge board — work everything listed there ----- */

async function challengeStep(ctx) {
  var state = ctx.state;
  var board = [];
  try {
    board = await ctx.api.challengesBoard();
  } catch (err) {
    if (err && err.unitDeadline) throw err;
    ctx.room.log.warn('the challenge board was unreadable (' + errText(err) + ')');
  }
  snapshotChallenges(ctx, board);

  var open = (state.challenges || []).filter(function (c) { return !c.done; });
  if (!open.length) return null;

  for (var i = 0; i < open.length; i++) {
    var ch = open[i];
    var plan = planFor(ch);
    var st = ensureChallengeState(ctx, ch.id);

    if (!plan || plan === 'manual') {
      /* be honest about what cannot be automated — never fake it */
      if (st.lastAction !== 'manual-hint') {
        var why = plan === 'manual'
          ? 'needs a real human (invites or external accounts), skipping, never faked'
          : 'no safe automation known, ' + (ch.task || 'no hint available');
        ctx.log('challenge "' + ch.goal + '" — ' + why);
        st.lastAction = 'manual-hint';
        st.lastAt = isoNow();
      }
      continue;
    }

    st.attempts += 1;
    st.lastAction = plan;
    st.lastAt = isoNow();

    var out = null;
    if (plan === 'join') out = await joinUnit(ctx, ch);
    else if (plan === 'tryApps') out = await dwellUnit(ctx, ch);
    else if (plan === 'propose') out = await proposeUnit(ctx, ch);
    else if (plan === 'buildApp') out = await buildUnit(ctx, ch);
    else if (plan === 'feedback') out = await feedbackUnit(ctx, ch);
    else if (plan === 'vote') out = await voteUnit(ctx, ch);
    else if (plan === 'chat') out = await chatUnit(ctx, ch);
    if (out) return out;
  }
  return null;
}

/* "Join a community" — become a member of one running app we missed */
async function joinUnit(ctx, ch) {
  var state = ctx.state;
  var apps = await listAppsOnce(ctx);
  if (!apps) return null;
  var joined = new Set(state.joinedApps);
  var cand = apps.filter(function (a) { return a.status === 'running' && !joined.has(a.slug); });
  if (!cand.length) {
    note(ctx, 'already a member everywhere, nothing left to join');
    return null;
  }
  var app = cand[Math.floor(Math.random() * cand.length)];
  var ok = await safe(ctx, 'the join', function () { return ctx.api.joinApp(app.slug); });
  if (ok) {
    pushUnique(state, 'joinedApps', app.slug);
    return { summary: 'joined ' + (app.name || app.slug) + ' for "' + ch.goal + '"', style: 'success' };
  }
  return { summary: 'could not join ' + app.slug + ' yet, retry next step', style: 'warn' };
}

/* "Try N apps" — the dwell, chunked: ONE activity POST of dwell_chunk_seconds
 * per unit (join first when needed, ONE iframe visit before the app's FIRST
 * chunk). The original spent the whole dwell in one loop; the sandbox paces
 * it across units through dwellProgress. */
async function dwellUnit(ctx, ch) {
  var state = ctx.state;
  var cfg = ctx.cfg;
  var apps = await listAppsOnce(ctx);
  if (!apps) return null;
  var tried = new Set(state.triedApps);
  var candidates = apps.filter(function (a) {
    return a.status === 'running' && !tried.has(a.slug) && a.slug !== BUILD_TARGET;
  });
  if (!candidates.length) {
    note(ctx, 'no fresh apps left to try for "' + ch.goal + '"');
    return null;
  }

  /* prefer finishing an app whose dwell is already in progress */
  var progress = state.dwellProgress || {};
  var app = null;
  for (var i = 0; i < candidates.length; i++) {
    var slugTry = candidates[i].slug;
    var progTry = progress[slugTry] || 0;
    if (progTry > 0 && progTry < cfg.dwellTargetS) { app = candidates[i]; break; }
  }
  if (!app) app = candidates[Math.floor(Math.random() * candidates.length)];
  var slug = app.slug;

  if (!state.joinedApps.includes(slug)) {
    var joined = await safe(ctx, 'the join before trying', function () { return ctx.api.joinApp(slug); });
    if (joined) pushUnique(state, 'joinedApps', slug);
  }

  var prog = progress[slug] || 0;
  if (prog === 0) {
    var token = await safe(ctx, 'the iframe token', function () { return ctx.api.iframeToken(slug); });
    if (!token) {
      return { summary: 'no iframe token for ' + slug + ', try another app next step', style: 'warn' };
    }
    await ctx.api.breathe();
    await safe(ctx, 'the app frame visit', function () { return ctx.api.visitApp(slug, token); });
    await ctx.api.breathe();
  }

  var chunk = Math.max(1, Math.min(cfg.dwellChunkS, cfg.dwellTargetS - prog));
  var r = await safe(ctx, 'the activity ping', function () { return ctx.api.activity(slug, chunk); });
  if (!r || !r.ok) {
    return { summary: 'activity ping rejected for ' + slug + ', retry next step', style: 'warn' };
  }
  prog += chunk;
  state.dwellProgress[slug] = prog;

  var nextIn = ' (next step in ' + Math.ceil(cfg.taskGapS) + 's)';
  if (prog >= cfg.dwellTargetS) {
    pushUnique(state, 'triedApps', slug);
    state.stats.appsTried += 1;
    return {
      summary: 'challenge "' + ch.goal + '": tried app ' + slug + ', dwell ' + prog + 's complete' + nextIn,
      style: 'success',
    };
  }
  return {
    summary: 'challenge "' + ch.goal + '": app ' + slug + ' dwell ' + prog + '/' + cfg.dwellTargetS + 's' + nextIn,
    style: 'info',
  };
}

/* "Send useful feedback" — one short, specific note on a real app */
async function feedbackUnit(ctx, ch) {
  var state = ctx.state;
  var cfg = ctx.cfg;
  var apps = await listAppsOnce(ctx);
  if (!apps) return null;
  var sent = state.feedbackSent;
  var candidates = apps.filter(function (a) {
    return a.status === 'running' && a.slug !== BUILD_TARGET && !sent.some(function (f) { return f.app === a.slug; });
  });
  if (!candidates.length) {
    note(ctx, 'feedback already sent to every running app');
    return null;
  }
  var app = candidates[Math.floor(Math.random() * candidates.length)];

  var waitS = secondsUntil(state.lastTextAt, cfg.textGapS);
  if (waitS > 0) {
    return { summary: 'text pacing, next submission in ' + waitS + 's (feedback on ' + app.slug + ' is ready)', style: 'info' };
  }

  var sentTexts = sent.map(function (f) { return f.text; });
  var text = await compose(ctx.room, cfg, state, feedbackSpec(app, sentTexts));
  if (!text) return { summary: 'composer failed, skipping feedback this round', style: 'warn' };

  var r = await safe(ctx, 'the feedback', function () { return ctx.api.sendFeedback(app.slug, text); });
  if (r) {
    sent.push({ app: app.slug, text: text.slice(0, TEXT_CLIP), at: isoNow() });
    rememberText(state, text);
    state.stats.feedbackSent = (state.stats.feedbackSent || 0) + 1;
    return { summary: 'sent feedback on ' + app.slug + ' for "' + ch.goal + '": ' + text, style: 'success' };
  }
  return { summary: 'feedback on ' + app.slug + ' did not go through', style: 'warn' };
}

/* ---- the vote unit — shared by the board's vote challenges and priority 3.
 * House rule: votes are ALWAYS yes, and votes are exempt from text pacing. */

async function voteUnit(ctx, ch) {
  var state = ctx.state;
  var apps = await listAppsOnce(ctx);
  if (!apps) return null;
  var joined = new Set(state.joinedApps);
  /* joined apps first (where the votes usually live), then the rest */
  var scan = [];
  for (var a = 0; a < apps.length; a++) {
    if (joined.has(apps[a].slug)) scan.push(apps[a].slug);
  }
  for (var b = 0; b < apps.length; b++) {
    if (!joined.has(apps[b].slug) && apps[b].status === 'running' && scan.indexOf(apps[b].slug) < 0) {
      scan.push(apps[b].slug);
    }
  }

  var scanned = 0;
  for (var s = 0; s < scan.length && scanned < VOTE_SCAN_APPS; s++) {
    if (ctx.api.outOfTime()) {
      return { summary: 'ran out of time scanning for votes, more next step', style: 'info' };
    }
    var slug = scan[s];
    scanned++;
    var items = await safe(ctx, 'the promoted list of ' + slug, function () { return ctx.api.promoted(slug); });
    if (!items) continue;
    var open = items.filter(function (p) {
      return p.status === 'promoted' && !p.my_vote && !state.votedSessions.includes(p.id);
    });
    for (var p = 0; p < open.length; p++) {
      var item = open[p];
      var title = (item.pr_title || '').slice(0, 58);
      var ok = await safe(ctx, 'the vote', function () { return ctx.api.vote(item.id); });
      if (ok) {
        pushUnique(state, 'votedSessions', item.id);
        state.stats.votesCast += 1;
        return {
          summary: 'voted yes on "' + title + '"' + (ch ? ' for "' + ch.goal + '"' : '') +
            ' (#' + item.id + ' on ' + slug + ')',
          style: 'success',
        };
      }
      /* membership gate? join quietly then retry once */
      var joinedNow = await safe(ctx, 'the join', function () { return ctx.api.joinApp(slug); });
      if (joinedNow) {
        pushUnique(state, 'joinedApps', slug);
        var ok2 = await safe(ctx, 'the vote after joining', function () { return ctx.api.vote(item.id); });
        if (ok2) {
          pushUnique(state, 'votedSessions', item.id);
          state.stats.votesCast += 1;
          return {
            summary: 'joined ' + slug + ' then voted yes on "' + title + '"',
            style: 'success',
          };
        }
        pushUnique(state, 'votedSessions', item.id); /* avoid hammering failures */
        note(ctx, 'skipped "' + title + '" (not eligible)');
      } else {
        pushUnique(state, 'votedSessions', item.id);
        note(ctx, 'skipped "' + title + '" (needs a join we cannot do)');
      }
    }
  }
  ctx.voteScanned = true;
  note(ctx, 'no open proposals waiting on a vote right now');
  return null;
}

/* ---- the chat unit — one short, natural message in the channel ----------
 * Quiet rules are the original's: stay quiet when the last message is own,
 * or when we said something in this conversation within quiet_minutes. */

async function chatUnit(ctx, ch) {
  var state = ctx.state;
  var cfg = ctx.cfg;
  var convs = await safe(ctx, 'the conversation list', function () { return ctx.api.conversations(); });
  if (!convs) return null;
  var conv = convs.find(function (c) {
    return c.channelKey === cfg.channel || c.title === cfg.channel;
  }) || null;
  if (!conv) {
    ctx.log('#' + cfg.channel + ' not found');
    note(ctx, 'channel #' + cfg.channel + ' not found');
    return null;
  }
  var msgs = await safe(ctx, 'the channel messages', function () {
    return ctx.api.channelMessages(conv.id, 12);
  });
  if (!msgs || !msgs.length) return null;

  var last = msgs[msgs.length - 1];
  var mine = state.user && last.sender && last.sender.username === state.user.username;
  var recent = (state.chatLog || []).some(function (c) {
    if (c.conversationId !== conv.id) return false;
    var mins = (Date.now() - new Date(c.at).getTime()) / 60000;
    return mins < cfg.quietMin;
  });
  if (mine || recent) {
    note(ctx, 'staying quiet in #' + cfg.channel + ', said something recently');
    return null;
  }

  /* text pacing is state now: report the wait instead of sleeping through it */
  var waitS = secondsUntil(state.lastTextAt, cfg.textGapS);
  if (waitS > 0) {
    return { summary: 'text pacing, next submission in ' + waitS + 's', style: 'info' };
  }

  var seenTexts = (state.chatLog || []).map(function (c) { return c.content; });
  var content = await compose(ctx.room, cfg, state,
    chatReplySpec(msgs, state.user ? state.user.username : '', seenTexts));
  if (!content) {
    return { summary: 'composer failed, staying quiet this turn', style: 'warn' };
  }

  var sent = await safe(ctx, 'the channel message', function () { return ctx.api.sendMessage(conv.id, content); });
  if (sent) {
    state.chatLog.unshift({ at: isoNow(), conversationId: conv.id, content: content.slice(0, TEXT_CLIP) });
    state.chatLog = state.chatLog.slice(0, CHAT_LOG_CAP);
    state.stats.messagesSent += 1;
    rememberText(state, content);
    return { summary: 'replied in ' + cfg.channel + ': ' + content, style: 'success' };
  }
  return { summary: 'message did not go through', style: 'warn' };
}

/* ---- the propose pipeline — "propose an app change / send it to the vote".
 * The DM-driven flow from the original, one step per unit: file the issue,
 * then follow the homeroom bot's cards (the decision unit answers them),
 * until a proposal card puts the change up for the community vote. */

async function proposeUnit(ctx, ch) {
  var state = ctx.state;
  var cfg = ctx.cfg;
  var p = state.proposePipeline;

  if (p && p.done) {
    var replacingInvalid = !!p.invalidated; /* idea was already shipped → try another now */
    if ((p.round || 1) >= PROPOSE_MAX_ROUNDS && !replacingInvalid) {
      note(ctx, (p.round || 1) + ' proposal(s) up for the vote already, they need community yes votes');
      return null;
    }
    var today = isoNow().slice(0, 10);
    var filedToday = (state.proposalsFiled || []).filter(function (f) {
      return (f.at || '').slice(0, 10) === today;
    }).length;
    var lastReplaceDay = state.lastInvalidReplaceDay;
    if (filedToday >= PROPOSE_PER_DAY && !(replacingInvalid && lastReplaceDay !== today)) {
      note(ctx, 'next proposal comes tomorrow, keeping it human-paced');
      return null;
    }
    if (replacingInvalid) state.lastInvalidReplaceDay = today;
    var nextRound = (p.round || 1) + (replacingInvalid ? 0 : 1);
    state.proposePipeline = null;
    p = null;
    ctx.nextRound = nextRound;
  }

  /* fresh pipeline — pick a fresh app + an idea that has not been filed before */
  if (!p) {
    var apps = await listAppsOnce(ctx);
    if (!apps) return null;
    var filedTitles = (state.proposalsFiled || []).map(function (f) { return f.title; });
    var usedApps = new Set(state.proposeAppsUsed || []);
    var runningApps = apps.filter(function (a) {
      return a.status === 'running' && a.slug !== BUILD_TARGET;
    });
    var fresh = runningApps.map(function (a) { return a.slug; }).filter(function (sl) { return !usedApps.has(sl); });
    var slug = fresh.sort(function () { return Math.random() - 0.5; })[0];
    if (!slug) slug = (runningApps[0] || {}).slug;
    if (!slug) return null;
    var appCtx = apps.find(function (a) { return a.slug === slug; }) || { slug: slug };
    var idea = await composeIdea(ctx.room, cfg, state, proposalSpec(appCtx, filedTitles));
    if (!idea) {
      note(ctx, 'composer failed, retry next step');
      return null;
    }
    p = state.proposePipeline = {
      app: slug, idea: idea, round: ctx.nextRound || 1, at: isoNow(),
    };
    ctx.nextRound = null;
    if (!state.proposeAppsUsed.includes(slug)) state.proposeAppsUsed.push(slug);
  }

  /* step 1 — the issue describing one clear change */
  if (!p.issueNumber) {
    var issue = await safe(ctx, 'the proposal issue', function () {
      return ctx.api.fileIssue(p.app, p.idea.title, p.idea.body);
    });
    if (!issue) {
      p.fileFailures = (p.fileFailures || 0) + 1;
      if (p.fileFailures >= 2) {
        note(ctx, p.app + ' keeps refusing new issues, rotating to another app next step');
        p.done = true;
        p.invalidated = true;
        p.invalidatedAt = isoNow();
      } else {
        note(ctx, 'could not file the proposal issue yet, retry next step');
      }
      return null;
    }
    p.issueNumber = issue.github_issue_number || issue.number || issue.id;
    p.issueId = issue.id;
    state.proposalsFiled.push({
      app: p.app, issueId: issue.id, issueNumber: p.issueNumber,
      title: p.idea.title, at: isoNow(),
    });
    state.stats.proposalsFiled += 1;
    return {
      summary: 'filed issue #' + p.issueNumber + ' on ' + p.app + ' for "' + ch.goal + '": "' + p.idea.title + '"',
      style: 'success',
    };
  }

  /* step 2 — the homeroom bot reviews the issue in the DM (the decision unit
     answers its cards); a proposal card means the change is up for the vote */
  var cards = await safe(ctx, 'the bot DM', function () { return ctx.api.dmCards(); }) || [];
  var mine = cards.filter(function (x) {
    return x.hb && (x.hb.issueNumber === p.issueNumber ||
      (x.hb.issueTitle || '').toLowerCase() === p.idea.title.toLowerCase());
  });

  var person = mine.find(function (x) { return x.hb.kind === 'person' && x.hb.status === 'open'; });
  if (person && person.hb.said === 'done') {
    /* the bot checked the code: this idea is already shipped. Be honest —
       drop it and queue a fresh idea for the next attempt. */
    p.done = true;
    p.invalidated = true;
    p.invalidatedAt = isoNow();
    return {
      summary: 'the bot says "' + p.idea.title + '" already exists, filing a different idea next step',
      style: 'info',
    };
  }
  if (person && person.hb.said === 'decide') {
    note(ctx, 'waiting on our go-ahead in the DM, the next step answers it');
    return null;
  }

  var prop = mine.find(function (x) { return x.hb.kind === 'proposal'; });
  if (prop) {
    var appr = (prop.hb.actions || []).find(function (a) { return a.id === 'approve' && a.sessionId; });
    if (appr && !state.dmApproved.includes(appr.sessionId)) {
      var v = await safe(ctx, 'the own-change vote', function () { return ctx.api.vote(appr.sessionId); });
      if (v) pushUnique(state, 'dmApproved', appr.sessionId);
    }
    p.done = true;
    p.sessionId = (appr && appr.sessionId) || prop.hb.link || null;
    p.promotedAt = isoNow();
    return {
      summary: 'the change is up for the vote, issue #' + p.issueNumber + ' on ' + p.app + ' (round ' + (p.round || 1) + ')',
      style: 'success',
    };
  }

  if (mine.some(function (x) { return x.hb.kind === 'activity'; })) {
    note(ctx, 'the builder picked it up, checks continue next step');
    return null;
  }
  note(ctx, 'waiting for the homeroom bot to review issue #' + p.issueNumber);
  return null;
}

/* ---- the build pipeline — "build for your community": ask the community
 * builder for a fresh app, then follow its DM cards (green-light, plan
 * approval, first-version approval) one step per unit. */

async function buildUnit(ctx, ch) {
  var state = ctx.state;
  var cfg = ctx.cfg;
  var p = state.buildPipeline;

  if (p && p.done) {
    note(ctx, 'new app is already up, waiting on the platform now');
    return null;
  }
  var apps = await listAppsOnce(ctx);
  if (!apps) return null;
  if (!apps.some(function (a) { return a.slug === BUILD_TARGET; })) {
    note(ctx, 'community builder app not found, retry next step');
    return null;
  }

  if (!p) {
    var usedTitles = (state.buildsRequested || []).map(function (b) { return b.title; });
    var idea = await composeIdea(ctx.room, cfg, state, buildSpec(usedTitles));
    if (!idea) {
      note(ctx, 'composer failed, retry next step');
      return null;
    }
    state.buildsRequested.push({ title: idea.title, at: isoNow() });
    p = state.buildPipeline = { app: BUILD_TARGET, idea: idea, at: isoNow() };
  }

  /* stale-build recovery: if nothing moved for two days, file a fresh request
     with the next unused idea instead of waiting forever */
  var anchor = p.planApprovedAt || p.at;
  var staleH = (Date.now() - new Date(anchor).getTime()) / 3600000;
  if (!p.done && staleH > BUILD_RESTART_HOURS && (p.restartCount || 0) < BUILD_MAX_RESTARTS) {
    var usedTitles2 = (state.buildsRequested || []).map(function (b) { return b.title; });
    usedTitles2.push(p.idea.title);
    var next = await composeIdea(ctx.room, cfg, state, buildSpec(usedTitles2));
    if (next) {
      state.buildsRequested.push({ title: next.title, at: isoNow() });
      state.buildPipeline = {
        app: BUILD_TARGET, idea: next, restartCount: (p.restartCount || 0) + 1, at: isoNow(),
      };
      return {
        summary: 'no movement for ~2 days, filing a fresh request ("' + next.title + '") instead',
        style: 'info',
      };
    }
    /* nothing new to file — nudge the builder once, politely */
    if (!p.nudgedAt) {
      var waitS = secondsUntil(state.lastTextAt, cfg.textGapS);
      if (waitS > 0) {
        return { summary: 'text pacing, next submission in ' + waitS + 's (a nudge is ready)', style: 'info' };
      }
      var msg = await compose(ctx.room, cfg, state, nudgeSpec(p.idea.title));
      if (msg) {
        var sent = await safe(ctx, 'the nudge', function () { return ctx.api.sendDmNote(msg); });
        if (sent) rememberText(state, msg);
      }
      p.nudgedAt = isoNow();
      return { summary: 'sent a friendly nudge about the stalled request', style: 'success' };
    }
  }

  /* step 1 — the request describing the app to build */
  if (!p.issueNumber) {
    var issue = await safe(ctx, 'the app request', function () {
      return ctx.api.fileIssue(BUILD_TARGET, p.idea.title, p.idea.body);
    });
    if (!issue) {
      note(ctx, 'could not file the app request yet, retry next step');
      return null;
    }
    p.issueNumber = issue.github_issue_number || issue.number || issue.id;
    p.issueId = issue.id;
    state.proposalsFiled.push({
      app: BUILD_TARGET, issueId: issue.id, issueNumber: p.issueNumber,
      title: p.idea.title, at: isoNow(),
    });
    state.stats.proposalsFiled += 1;
    return {
      summary: 'filed request #' + p.issueNumber + ' for "' + ch.goal + '": "' + p.idea.title + '"',
      style: 'success',
    };
  }

  /* step 2 — the homeroom bot reviews the request in the DM; the decision
     unit answers person cards (green-light, done-checks) and approvals */
  var cards = await safe(ctx, 'the bot DM', function () { return ctx.api.dmCards(); }) || [];
  var mine = cards.filter(function (x) {
    return x.hb && (x.hb.issueNumber === p.issueNumber ||
      (x.hb.issueTitle || '').toLowerCase() === p.idea.title.toLowerCase());
  });

  var decide = mine.find(function (x) { return x.hb.kind === 'person' && x.hb.status === 'open'; });
  if (decide && decide.hb.said === 'decide') {
    note(ctx, 'waiting on our green-light in the DM, the next step answers it');
    return null;
  }

  /* legacy plan card (older flow) — approve the plan if one shows up */
  var plan = mine.find(function (x) {
    return x.hb.kind === 'plan' && x.hb.status === 'open' && x.hb.actionId;
  });
  if (plan) {
    var label = await safe(ctx, 'the plan approval', function () {
      return ctx.api.botAction(plan.hb.actionId, 'build');
    });
    if (!label) {
      note(ctx, 'plan approval did not go through, retry next step');
      return null;
    }
    p.planApprovedAt = isoNow();
    return { summary: 'plan approved, the agent is building the new app', style: 'success' };
  }

  /* step 3 — when the first version is up, approve it like a member would */
  var proposal = mine.find(function (x) { return x.hb.kind === 'proposal'; });
  if (proposal) {
    var appr = (proposal.hb.actions || []).find(function (a) { return a.id === 'approve' && a.sessionId; });
    if (appr && state.dmApproved.includes(appr.sessionId)) {
      p.done = true;
      p.approvedAt = isoNow();
      return { summary: 'first version approved, it goes live when the group agrees', style: 'success' };
    }
    if (appr) {
      var v = await safe(ctx, 'the first-version vote', function () { return ctx.api.vote(appr.sessionId); });
      if (v) {
        pushUnique(state, 'dmApproved', appr.sessionId);
        p.done = true;
        p.approvedAt = isoNow();
        return { summary: 'first version approved, it goes live when the group agrees', style: 'success' };
      }
    }
    note(ctx, 'built and waiting on the group approval, nothing for us to click');
    return null;
  }

  if (mine.some(function (x) {
    return x.hb.kind === 'person' || x.hb.kind === 'plan' || x.hb.kind === 'activity';
  })) {
    note(ctx, 'the builder is on it, checks continue next step');
    return null;
  }
  note(ctx, 'no card from the bot yet, retry next step');
  return null;
}

/* ==========================================================================
 *  stepOnce — the whole routine's front door, shared by /step and the
 *  run_task tool. ONE bounded unit: credentials check (kv only), pacing
 *  check (kv only, NO network), login check, then the priority ladder.
 * ========================================================================*/

async function oneUnit(ctx) {
  var dm = await answerDmCards(ctx);
  if (dm) return dm;
  var ch = await challengeStep(ctx);
  if (ch) return ch;
  if (!ctx.voteScanned) {
    var vote = await voteUnit(ctx, null);
    if (vote) return vote;
  }
  var chat = await chatUnit(ctx, null);
  if (chat) return chat;
  /* nothing acted: surface the two most specific reasons it did not */
  var why = ctx.notes.slice(-2).join('; ');
  return { summary: 'all caught up, nothing pending' + (why ? ' (' + why + ')' : ''), style: 'info' };
}

async function stepOnce(room) {
  var cfg = readConfig(room);
  var loaded = loadState(room);
  var state = loaded.state;
  var log = function (m) { room.log.info(m); };

  /* no credentials → the clean error, no network, no pacing anchor */
  if (!state.account || !state.account.email || !state.account.password) {
    return { summary: 'no credentials — ask the user to run /login', style: 'warn' };
  }

  /* pacing is state, not sleeps: refusing here touches nothing but kv */
  var waitS = secondsUntil(state.lastActionAt, cfg.taskGapS);
  if (waitS > 0) {
    return { summary: 'next action allowed in ' + waitS + 's', style: 'info' };
  }

  var ctx = {
    room: room, cfg: cfg, state: state, log: log,
    notes: [], appsCache: null, voteScanned: false, nextRound: null,
  };
  ctx.api = makeApi(room, state, cfg, log, state.account);

  try {
    var auth = await ctx.api.ensureLoggedIn();
    if (!auth.ok) {
      state.lastActionAt = isoNow(); /* the login attempt cost a network round */
      saveState(room, state, loaded.loaded);
      return {
        summary: 'login failed — ' + (auth.error || 'check the credentials') + ' (run /login to fix them)',
        style: 'error',
      };
    }
    var out = await oneUnit(ctx);
    state.lastActionAt = isoNow();
    state.stats.runs = (state.stats.runs || 0) + 1;
    state.runs.unshift({ at: isoNow(), note: String(out.summary).slice(0, 80) });
    state.runs = state.runs.slice(0, RUNS_CAP);
    saveState(room, state, loaded.loaded);
    return out;
  } catch (err) {
    /* a deadline blow-out or an unexpected failure: keep the pacing anchor
       so the next try is not immediate, and answer with clean words */
    state.lastActionAt = isoNow();
    saveState(room, state, loaded.loaded);
    return { summary: 'step failed: ' + errText(err), style: 'error' };
  }
}

/* ==========================================================================
 *  Status — the same summary the original's --status printed.
 * ========================================================================*/

function cookieAge(savedAt) {
  if (!savedAt) return 'none';
  var ms = Date.now() - new Date(savedAt).getTime();
  if (!Number.isFinite(ms) || ms < 0) return 'unknown';
  var mins = Math.floor(ms / 60000);
  if (mins < 60) return mins + 'm old';
  var hours = Math.floor(mins / 60);
  if (hours < 48) return hours + 'h ' + (mins % 60) + 'm old';
  return Math.floor(hours / 24) + 'd old';
}

function statusOf(room) {
  var cfg = readConfig(room);
  var state = loadState(room).state;
  var s = state.stats || {};
  var board = state.challenges || [];
  var open = board.filter(function (c) { return !c.done; }).length;
  var done = board.length - open;
  var waitS = secondsUntil(state.lastActionAt, cfg.taskGapS);
  return {
    user: state.user && state.user.username ? state.user.username : '(never logged in)',
    open: open,
    done: done,
    votes: s.votesCast || 0,
    messages: s.messagesSent || 0,
    appsTried: s.appsTried || 0,
    nextAction: waitS > 0 ? 'in ' + waitS + 's' : 'ready',
    cookie: cookieAge(state.session && state.session.savedAt),
  };
}

function statusText(room) {
  var s = statusOf(room);
  return 'user: ' + s.user +
    '; challenges: ' + s.open + ' open / ' + s.done + ' done' +
    '; votes cast: ' + s.votes +
    '; messages sent: ' + s.messages +
    '; apps tried: ' + s.appsTried +
    '; next action: ' + s.nextAction +
    '; cookie: ' + s.cookie;
}

/* ==========================================================================
 *  The plugin's exports
 * ========================================================================*/

/* activate: no network, no generation — an info card only */
exports.activate = async function (room) {
  room.ui.card({
    title: 'OnHomeroom Daily is on for this chat',
    text: 'Use /login once (the email and password for app.onhomeroom.com), then /step — ' +
      'or ask the agent to run the routine. Every sentence it posts is composed live by ' +
      "this chat's own AI and re-checked by the original validation gate.",
    style: 'info',
  });
};

/* deactivate: the reason goes to the log; state stays until /reset */
exports.deactivate = async function (room, reason) {
  room.log.info('OnHomeroom Daily disabled' + (reason ? ' (' + reason + ')' : '') +
    " — this chat's state stays until /reset or the chat is deleted");
};

exports.commands = {

  status: {
    run: async function (room) {
      var s = statusOf(room);
      var fields = [
        ['logged in as', String(s.user).slice(0, 200)],
        ['challenges open', String(s.open)],
        ['challenges done', String(s.done)],
        ['votes cast', String(s.votes)],
        ['messages sent', String(s.messages)],
        ['apps tried', String(s.appsTried)],
        ['next action', String(s.nextAction)],
        ['cookie age', String(s.cookie)],
      ];
      var card = {
        title: 'OnHomeroom Daily — status',
        fields: fields,
        style: 'info',
      };
      /* the same facts as a plain-text fallback */
      return [card, statusText(room)];
    },
  },

  login: {
    /* /login email password — the password is everything after the email */
    run: async function (room, args) {
      var raw = String(args || '').trim();
      var parts = raw.split(/\s+/).filter(Boolean);
      if (parts.length < 2) {
        return {
          title: 'Login needs credentials',
          text: 'usage: /login email password — the OnHomeroom account this chat will ride as',
          style: 'warn',
        };
      }
      var email = parts[0];
      var password = raw.slice(raw.indexOf(email) + email.length).trim();

      var cfg = readConfig(room);
      var loaded = loadState(room);
      var state = loaded.state;
      var api = makeApi(room, state, cfg, function (m) { room.log.info(m); }, { email: email, password: password });
      var res;
      try {
        res = await api.login();
      } catch (err) {
        return { title: 'Login failed', text: 'login attempt failed: ' + errText(err), style: 'error' };
      }

      if (res.ok) {
        state.account = { email: email, password: password };
        saveState(room, state, loaded.loaded);
        var fields = [
          ['user', (res.user && res.user.username) || 'session saved'],
          ['account', email.slice(0, 200)],
        ];
        var ua = room.meta.userAgent;
        if (ua) fields.push(['riding as', String(ua).slice(0, 200)]);
        return {
          title: 'Logged in to OnHomeroom',
          text: res.user
            ? 'Signed in as ' + res.user.username + (ua ? ', riding as ' + ua : '') +
                '. One /step now runs a bounded unit of the routine.'
            : 'Session cookie saved (the /me lookup failed, continuing anyway). Try /step.',
          fields: fields,
          style: 'success',
        };
      }
      return { title: 'Login failed', text: String(res.error), style: 'error' };
    },
  },

  step: {
    run: async function (room) {
      var out = await stepOnce(room);
      return { title: 'OnHomeroom Daily — step', text: out.summary, style: out.style };
    },
  },

  reset: {
    run: async function (room) {
      try {
        room.storage.kv.clear();
      } catch (err) {
        return { title: 'Reset failed', text: errText(err), style: 'error' };
      }
      return {
        title: 'OnHomeroom Daily reset',
        text: "This chat's plugin state was wiped — credentials included. Run /login to start over.",
        style: 'info',
      };
    },
  },
};

exports.tools = {

  /* one bounded unit of the routine — the model's steering wheel */
  run_task: {
    description: 'Run one bounded unit of the OnHomeroom routine: answer a pending homeroom-bot DM ' +
      'card, work the next incomplete challenge step (join, one app-dwell chunk, feedback, a proposal ' +
      'or build step), cast one yes vote, or reply once in the general channel. Paced by persisted ' +
      'timestamps: calling it again too soon reports the wait instead of acting.',
    parameters: { type: 'object', properties: {}, required: [] },
    run: async function (room) {
      var out = await stepOnce(room);
      return out.summary;
    },
  },

  /* the state summary as text */
  status: {
    description: 'Report the OnHomeroom routine state as text: who is logged in, the challenge board, ' +
      'votes cast, messages sent, apps tried, and when the next action is allowed.',
    parameters: { type: 'object', properties: {}, required: [] },
    run: async function (room) {
      return statusText(room);
    },
  },
};
