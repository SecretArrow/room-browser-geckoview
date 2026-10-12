# Privacy

Room Browser collects nothing on its own. There is no telemetry, no analytics
and no crash-reporting SDK in the Android or the desktop tree, no account, no
sign-in, and no server operated by this project. Nothing is uploaded unless a
feature you switched on has to send something to work.

## What stays on the device

Profiles, history, bookmarks, downloads, saved pages, site permissions, the
wallet's encrypted material and the on-device agent's memory all live under the
app's own data directory. Clearing a profile's data, or uninstalling the app,
removes them. The two Android editions are separate apps with separate data
directories (`com.roombrowser` and `com.roombrowser.gecko`), so neither can read
the other's data.

The bundled filter lists ship inside the APK
(`android/app/src/main/assets/filters/`). They are never fetched at runtime, and
the app never reports which rules it matched. Every statistic shown in the app
is computed from locally recorded events.

## What can leave the device, and only when you turn it on

Every item below is off by default.

- **Pages you load.** That traffic goes to the site, and to whichever DNS
  resolver is in use. `DNS mode` defaults to the system resolver; choosing
  DNS-over-HTTPS or DNS-over-TLS sends the query to the resolver you name.
- **The proxy finder** (`proxyEnabled`, default off). When enabled, the app
  fetches the public proxy lists named in
  `android/app/src/main/assets/proxies/sources.json` so it has candidates to
  test. Each profile's own proxy mode decides whether that profile uses one.
- **The AI agent**, when it is pointed at a remote provider: the page text, DOM
  or screenshot a tool asks for goes to that endpoint, under that provider's
  policy. Pointing the agent at a local llama.cpp model keeps all of it on the
  device.
- **A diagnostic report** (`diagnosticsEnabled`, default off). It is produced
  only on request and handed to you or to the agent that asked for it; the app
  itself never uploads it.

## The desktop edition

The desktop browser is a C program that embeds the platform's own web view
(WebView2 on Windows, WebKitGTK on Ubuntu) and keeps its bookmarks, history and
settings in local files. It has no analytics or telemetry code of its own.

## Reporting

A privacy defect is a security defect here. See [SECURITY.md](SECURITY.md).
