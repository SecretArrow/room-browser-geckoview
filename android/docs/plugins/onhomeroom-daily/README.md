# OnHomeroom Daily — the app.onhomeroom.com routine as a chat plugin

A port of the standalone OnHomeroom bot (a Node script that worked
app.onhomeroom.com's challenge board, votes, chat and app-try routine, paced
like a person) into a Room chat plugin. What changes in the port is *who
writes the words* and *how time passes*:

- **No Composer, no message pool.** Every sentence the routine posts — chat
  replies, feedback notes, proposal titles and bodies, DM answers, nudges —
  is composed **live by THIS chat's own AI** through `room.agent.generate`,
  then re-checked by the original bot's validation gate before it may leave.
- **No endless loop, no sleeps.** The standalone bot slept for minutes
  between tasks and ran forever. A plugin call is one shot inside a 30 s
  budget, so the routine is **state-driven**: one `/step` or one `run_task`
  call executes exactly ONE bounded unit, and pacing is enforced by
  persisted timestamps (`task_gap_seconds`, `text_gap_seconds`) that the
  next call reads before touching the network.

It needs four permissions: `storage` (its state), `network` (restricted to
`*.onhomeroom.com`), `agent.generate` (the live composer), and
`agent.tools` (the two tools the chat's agent can call).

## Installing

Build the ZIP and install it through the app's plugin manager (Agent →
plugins → **Install from ZIP**, which opens the system file picker — SAF):

```sh
python3 android/tools/make_plugin_zip.py android/docs/plugins/onhomeroom-daily out.zip
```

The archive is byte-reproducible (fixed timestamps, sorted entries). Then,
in a chat: the composer's puzzle-piece button (or `/plugins`) → enable
**OnHomeroom Daily** → accept the grant dialog. The activation card reminds
you of the next step, which is always the same: `/login`.

On a desktop you can exercise the plugin without a device:

```sh
node android/tools/plugin_dev.js android/docs/plugins/onhomeroom-daily probe \
  --perms storage,network,agent.generate,agent.tools
node android/tools/plugin_dev.js android/docs/plugins/onhomeroom-daily activate \
  --perms storage,network,agent.generate,agent.tools
node android/tools/plugin_dev.js android/docs/plugins/onhomeroom-daily command \
  '{"name":"status","args":""}' --perms storage,network,agent.generate,agent.tools
```

Note that the harness's fake generator answers `'(composed) purpose: …'` —
deliberately non-conforming text, so every text-sending unit stays quiet
under it (that is the gate doing its job); `--no-gen` exercises the
not-available refusal the same way.

## Config

Per chat; the manifest defaults are the standalone bot's pacing values.

| Key | Default | Meaning |
|---|---|---|
| `channel` | `general` | The channel the chat task replies in |
| `text_gap_seconds` | 60 | Minimum seconds between any two sent texts (votes are exempt, as in the original) |
| `task_gap_seconds` | 180 | Minimum seconds between two `run_task`/`/step` units |
| `max_words` | 24 | Word cap the composer is told to respect (and the gate enforces) |
| `dwell_chunk_seconds` | 15 | App-try dwell reported per `run_task` call (one activity POST) |
| `dwell_target_seconds` | 180 | Total dwell per app before it counts as tried |
| `quiet_minutes` | 10 | Stay quiet this long after your own channel message |

## Commands

| Command | What it does |
|---|---|
| `/status` | A card with the logged-in user, challenges open/done, votes cast, messages sent, apps tried, the next-action wait and the cookie's age — plus a plain-text fallback line with the same facts |
| `/login email password` | Attempts the login NOW. On success stores the credentials + session cookie in this chat's plugin storage and shows who is signed in (and, when the app reports one, the browser identity it is riding as); on failure a card with the API's own words |
| `/step` | Runs ONE bounded unit of the routine now, and answers with a card carrying the unit's summary |
| `/reset` | Wipes this chat's plugin state — credentials included |

## Tools (for the chat's agent)

Both return short plain text for the model; the app registers them as
`plugin_io_onhomeroom_daily_run_task` and `plugin_io_onhomeroom_daily_status`.

- **`run_task`** — one bounded unit, by priority: (1) answer a pending
  homeroom-bot DM card (a generated reply for questions, the original
  go_ahead / done / approve actions for decision cards); (2) refresh the
  challenge board and work the next incomplete challenge's next bounded
  step (join, one dwell chunk, one vote, one chat reply, one feedback note,
  or the propose/build pipeline's next step); (3) one yes vote on a
  promoted proposal; (4) one chat reply when the quiet rules allow.
  Typical answers: `voted yes on "Add a streak counter to Daily Puzzle"
  (#412 on daily-puzzle)`, `replied in general: honestly the daily puzzle
  keeps getting better`, `challenge "Try 3 apps": app wordchain dwell
  15/180s (next step in 180s)`, `next action allowed in 132s`,
  `no credentials — ask the user to run /login`.
- **`status`** — the state summary as text (login, board, counters, wait).

## How the composer works now

The standalone bot assembled every sentence from small safe fragments
inside a `Composer` object. This plugin has no composer: each outgoing text
is one `room.agent.generate` call — a structured request (purpose,
instruction, context, style, an `avoid` list of the last texts — never a raw
prompt) answered live by **this chat's own model**, then put through the
original validation gate, kept verbatim from the bot:

- English, casual, exactly one sentence — no `. ! ? ;` anywhere inside;
- the characters `! : — – ? ;` never appear, and no emoji;
- at most `max_words` words (24 by default);
- nothing repeats a recent text — the anti-repeat memory
  (`recentTexts`, capped, plus `lastTextAt`) feeds the request's `avoid`
  list on every call.

**On any failure — the generation throws, the model writes nothing, or the
gate rejects the answer (say it answers `nice!`) — the plugin stays quiet**
for that text, exactly like the original bot; nothing invalid is ever sent.
Each text kind mirrors one original Composer method's intent:
`chat-reply` (the last messages as context, the detected kind — weekend,
thanks, question, greeting, or the live topic — as the instruction),
`feedback` (the app's name plus one feature angle), `dm-reply` (the bot's
question as context, branched like the original), `nudge`, and the two
`TITLE | BODY` kinds — `proposal` and `build-app` — which use ONE
generation asked to answer with exactly `"TITLE | BODY"`, split on
`' | '` (a deliberate choice: one generation per text keeps a unit inside
its budget; the body then keeps the original `validateIdea` rule of a
single trailing period).

## User agent

Requests ride the browser identity the user selected for the profile —
`room.net.fetch` carries it automatically (a plugin cannot announce Node).
`room.meta.userAgent` exposes the same string read-only, and `/login`'s
success card shows it ("riding as …"). In the desktop harness it reads
`null`, because the harness supplies no identity.

## Isolation

All state lives under **this chat's** plugin data root: credentials, the
session cookie, the challenge board, the pipelines, the anti-repeat memory.
No other chat can reach it, the plugin cannot name another chat's storage,
and `/reset` (or deleting the chat) wipes it. Credentials are stored in
plain JSON in that chat's `kv.json` — the same trust level the standalone
bot's `config.json` had, but scoped to one chat; use a dedicated account,
and `/reset` when done.

## Honest differences from the standalone bot

- **Dead code dropped:** the whole `/api/sessions` agent flow
  (`listSessions`/`createSession`/`sessionStatus`/SSE `kickSession`/
  checks/promote/archive) that the site no longer answers, the `readline`
  import, the `agent` config block, `tasks.vote.choice` (votes are always
  yes), and `tasks.chat.maxPerCycle`. The DM-driven flow — the homeroom
  bot's cards — is what the port follows.
- **Pacing is state, not sleeps.** The bot's 3–7-minute task gaps, its
  60-second text gap and its 15-minute idle poll become persisted
  timestamps; `run_task` refuses early with `next action allowed in Ns`
  *without any network call*, and the text gap is reported the same way
  instead of sleeping through it.
- **Dwell is chunked.** The bot spent 180 s inside an app in one loop of
  30 s heartbeats; a plugin unit reports ONE activity POST of
  `dwell_chunk_seconds` (plus the join and the single iframe visit before
  the first chunk), so finishing one app takes about
  `dwell_target / dwell_chunk` units.
- **`prompts.txt` is not shipped.** The original never loaded it either —
  its rules were already encoded in the Composer; here they live in the
  generate requests (style constraints, avoid lists) and in the gate.
- **List caps adapted to the kv quota** (each kv value ≤ 16 KB): `chatLog`
  keeps 40 entries and `recentTexts` 60 (contents clipped to 200 chars,
  the same clip the `avoid` list applies anyway); `proposalsFiled`,
  `feedbackSent` and `buildsRequested` keep 60. Nothing the logic reads
  was capped — only the oldest, no-longer-read entries give way.
- **Config is the manifest's seven keys.** The knobs that were effectively
  constants stayed constants: propose rounds 3 / per day 1, the community
  builder app `usernode-2d5619`, the 48 h stale-build restart. There is no
  console status line with countdowns — `/status` and the `status` tool
  answer instead, and `stats.runs` counts executed units (the standalone
  counted process starts).

The full plugin reference — every manifest field, every `room` API, the
sandbox rules — is [android/docs/PLUGINS.md](../../PLUGINS.md).
