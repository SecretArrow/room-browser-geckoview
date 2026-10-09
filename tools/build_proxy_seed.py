#!/usr/bin/env python3
"""Build the bundled free-proxy seed shipped at android/app/src/main/assets/proxies/.

Fetches the curated sources, parses them with the same rules as ProxyListParser.kt,
de-duplicates by host:port and writes seed.txt + sources.json. Re-running produces a
stable file, so a refresh shows up as a readable diff.

Run: python3 tools/build_proxy_seed.py [--assets-dir <dir>]
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import pathlib
import re
import sys
import urllib.request
from datetime import date

SOURCES = [
    ("speedx", "TheSpeedX/PROXY-List",
     "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt"),
    ("monosans", "monosans/proxy-list",
     "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt"),
    ("proxifly", "proxifly/free-proxy-list",
     "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/http/data.txt"),
    ("shifty", "ShiftyTR/Proxy-List",
     "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/http.txt"),
    ("rooster", "roosterkid/openproxylist",
     "https://raw.githubusercontent.com/roosterkid/openproxylist/main/HTTPS_RAW.txt"),
    ("hideip", "zloi-user/hideip.me",
     "https://raw.githubusercontent.com/zloi-user/hideip.me/main/http.txt"),
    ("aliilapro", "ALIILAPRO/Proxy",
     "https://raw.githubusercontent.com/ALIILAPRO/Proxy/main/http.txt"),
    ("clarketm", "clarketm/proxy-list",
     "https://raw.githubusercontent.com/clarketm/proxy-list/master/proxy-list-raw.txt"),
]

SCHEMES = [
    ("socks5://", "socks5"),
    ("socks4://", "socks4"),
    ("socks://", "socks5"),
    ("https://", "https"),
    ("http://", "http"),
]

HOSTNAME = re.compile(r"^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$")
MAX_LABEL = 40


def parse_line(line: str):
    head = line.split("#", 1)[0].strip()
    if not head:
        return None
    body, scheme = head, "http"
    for prefix, name in SCHEMES:
        if body.lower().startswith(prefix):
            scheme, body = name, body[len(prefix):]
            break
    body = body.split("/", 1)[0].strip()
    parts = body.split(":")
    if not 2 <= len(parts) <= 3:
        return None
    host = parts[0].strip()
    if not host or not HOSTNAME.match(host):
        return None
    try:
        port = int(parts[1].strip())
    except ValueError:
        return None
    if not 1 <= port <= 65535:
        return None
    country = None
    if len(parts) == 3:
        label = parts[2].strip()
        if not label or len(label) > MAX_LABEL or label.isdigit():
            return None
        country = label
    return host, port, scheme, country


def fetch(source):
    sid, _label, url = source
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "curl/8.5.0"})
        with urllib.request.urlopen(req, timeout=45) as response:
            return sid, response.read().decode("utf-8", "replace"), None
    except Exception as error:  # a dead source must not take the bundle down
        return sid, None, str(error)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets-dir", default="android/app/src/main/assets/proxies")
    args = parser.parse_args()

    with concurrent.futures.ThreadPoolExecutor(max_workers=len(SOURCES)) as pool:
        results = {sid: (body, err) for sid, body, err in pool.map(fetch, SOURCES)}

    by_id: dict[str, tuple] = {}
    per_source: list[tuple[str, int, int]] = []
    for sid, _label, _url in SOURCES:
        body, err = results[sid]
        if err:
            print(f"  !! {sid}: {err}", file=sys.stderr)
            per_source.append((sid, 0, 0))
            continue
        parsed = [p for p in (parse_line(line) for line in body.splitlines()) if p]
        added = 0
        for host, port, scheme, country in parsed:
            key = f"{host}:{port}"
            previous = by_id.get(key)
            if previous is None:
                by_id[key] = (host, port, scheme, country, sid)
                added += 1
            elif previous[2] == "http" and scheme != "http":
                by_id[key] = (host, port, scheme, country, sid)
        per_source.append((sid, len(parsed), added))
        print(f"  {sid:10s} parsed={len(parsed):5d} new={added:5d}")

    # A source that parsed nothing is either genuinely all-duplicate or a source that moved;
    # both are worth seeing, so the count is always printed rather than assumed.
    empty = [sid for sid, parsed, _ in per_source if parsed == 0]
    if empty:
        print(f"  note: no lines parsed from {', '.join(empty)}", file=sys.stderr)

    assets = pathlib.Path(args.assets_dir)
    assets.mkdir(parents=True, exist_ok=True)

    lines = [
        "# Room Browser bundled proxy seed.",
        f"# Generated {date.today().isoformat()} by tools/build_proxy_seed.py — do not edit by hand.",
        "# Format: [scheme://]host:port[:Country], read by ProxyListParser.",
        "# Endpoints are unverified listings: the health sweep decides what is actually usable.",
        "# Sources: " + ", ".join(label for _, label, _ in SOURCES),
        "",
    ]
    for host, port, scheme, country, _sid in by_id.values():
        endpoint = f"{host}:{port}" if scheme == "http" else f"{scheme}://{host}:{port}"
        lines.append(f"{endpoint}:{country}" if country else endpoint)
    text = "\n".join(lines) + "\n"

    # The asset is only useful if the Kotlin parser reads it back exactly; check that here
    # rather than discovering it on a device.
    written = [parse_line(line) for line in text.splitlines()]
    round_tripped = [p for p in written if p]
    if len(round_tripped) != len(by_id):
        print(f"  !! round trip lost entries: {len(by_id)} -> {len(round_tripped)}", file=sys.stderr)
        return 1

    (assets / "seed.txt").write_text(text, encoding="utf-8")
    catalogue = {"sources": [{"id": sid, "label": label, "url": url} for sid, label, url in SOURCES]}
    (assets / "sources.json").write_text(json.dumps(catalogue, indent=2) + "\n", encoding="utf-8")

    print(f"  wrote {len(by_id)} endpoints to {assets / 'seed.txt'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
