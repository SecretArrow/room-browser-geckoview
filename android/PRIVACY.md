# Privacy

## Data Room Browser collects

**None.** Telemetry is OFF by default and no telemetry code path exists that
could be silently enabled. Specifically, Room Browser does **not** collect:
URLs, browsing history, page contents, profile identity, cookies, IP history,
search queries, or any identifier.

## Data Room Browser stores (locally, on-device only)

| Data | Where | Leaves device? |
|---|---|---|
| Profile settings | Room DB | never |
| Tabs / bookmarks / history | Room DB | never (exports are explicit, user-initiated, and exclude secrets) |
| Downloads list | Room DB | never |
| Site permissions / site settings | Room DB | never |
| IP history (network protection) | Room DB | never — deleted per retention policy; purgeable |
| Real block events (dashboard stats) | Room DB | never |
| Cookies / storage of each profile | per-profile engine data directory | never |

Cloud backups and device transfers exclude the database and profile
directories entirely (`allowBackup=false`, data extraction rules).

## Network requests the app makes

1. **Page loads & subresources** — the browser engine loads what you ask it
   to, through the profile's engine context.
2. **Public IP check** — ONLY when Profile Network Protection or network
   diagnostics is enabled. Plain HTTPS GETs to `api.ipify.org`,
   `icanhazip.com` or `checkip.amazonaws.com`. Results are cached for 10
   minutes and stored only on-device. Disable via
   Settings → Privacy & Network → Profile Network Protection.
3. **Search suggestions** — OFF by default; when enabled, typed queries go to
   the selected search engine (clearly labeled in the UI).
4. **Downloads** — only the files you request.
5. **DoH resolution** — if configured, DNS queries for the app's own
   connections go to the configured resolver.
6. **Wallet networks** — the wallet sends JSON-RPC calls only to the endpoints
   configured for the networks you use, or to the public ones its Chainlist
   catalogue supplies. That catalogue is fetched from
   `https://chainid.network/chains.json` when the wallet asks for it, and is
   cached in memory for 10 minutes.

The bundled filter list (323 hosts) ships inside the APK and is **never
fetched or updated over the network**.

## IP conflict warning privacy posture

A shared public IP does **not** prove two profiles belong to the same person.
The warning is informational, per-profile configurable, supports retention
windows (1–∞ days), can be dismissed permanently per IP, and its history can
be cleared at any time. The IP never leaves the device.

## Private tabs

Private tabs skip history recording. The two editions differ here. The
GeckoView edition runs each private session in a private storage context of its
own, which never writes to the profile's jar and is discarded when the last
private session closes. The WebView edition has no equivalent — Android gives
one cookie jar per profile directory — so there, session cookies and form data
are purged when the last private tab closes, while persistent cookies set
during a private session remain in the profile's jar (platform limitation —
see `PROFILE_ISOLATION.md`). Use a dedicated profile for stronger separation.

## DNS privacy — exact scope

- DoH/DoT configured in Room Browser applies to **the app's own connections**
  (IP checks, suggestions, downloads).
- Page-load DNS follows the Android OS resolver. For page-load DNS
  privacy, enable Android system Private DNS (DoT) with the same resolver —
  the in-app DNS screen says this explicitly.
