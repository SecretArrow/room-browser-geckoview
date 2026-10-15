#!/usr/bin/env python3
"""Extracts every bundled NetworkConfig from the Android wallet sources and
the 23-entry EVM snapshot into a Rust table, so the desktop wallet starts
from the exact same curated network list (endpoints included).

Lives in desktop/scripts/ so it is part of the shared desktop tree both
editions carry; run it from the repository root after changing the
Android chain adapters."""
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent          # desktop/scripts/
ROOT = HERE.parent.parent                       # the repository root
WALLET = ROOT / "android/core/wallet/src/main/kotlin/com/roombrowser/domain/wallet"
OUT = HERE.parent / "crates/rbcore/src/wallet_networks_data.rs"


def split_args(block: str) -> list[str]:
    args, cur, depth, in_str = [], [], 0, False
    i = 0
    while i < len(block):
        c = block[i]
        if in_str:
            if c == "\\" and i + 1 < len(block):
                cur.append(c); i += 1; cur.append(block[i])
            else:
                cur.append(c)
                if c == '"':
                    in_str = False
        else:
            if c == '"':
                in_str = True; cur.append(c)
            elif c in "([{":
                depth += 1; cur.append(c)
            elif c in ")]}":
                depth -= 1; cur.append(c)
            elif c == "," and depth == 0:
                args.append("".join(cur).strip()); cur = []
            else:
                cur.append(c)
        i += 1
    if cur:
        args.append("".join(cur).strip())
    return [a for a in args if a]


def parse_named(block: str) -> dict:
    out = {}
    for a in split_args(block):
        if "=" not in a:
            continue
        k, v = a.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def parse_list_of(v: str) -> list[str]:
    inner = v.strip()
    m = re.match(r"listOf\((.*)\)$", inner, re.S)
    if not m:
        single = re.match(r'"(.*)"$', inner)
        return [single.group(1)] if single else []
    items = [i.strip() for i in split_args(m.group(1)) if i.strip()]
    out = []
    for it in items:
        s = re.match(r'"(.*)"$', it, re.S)
        if s:
            out.append(s.group(1))
    return out


def parse_str(v: str) -> str:
    s = re.match(r'"(.*)"$', v.strip(), re.S)
    return s.group(1) if s else ""


def parse_opt_str(v: str) -> str:
    v = v.strip()
    if v == "null" or v == "":
        return ""
    return parse_str(v)


def parse_bool(v: str) -> bool:
    return v.strip() == "true"


def parse_int(v: str) -> int:
    m = re.search(r"-?\d+", v or "")
    return int(m.group()) if m else 0


def rust_str(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def main():
    networks = []
    # Non-EVM adapters use NetworkConfig(...) with named arguments.
    for kt in sorted(WALLET.glob("chains/*/[A-Z]*Adapter.kt")):
        text = kt.read_text()
        for m in re.finditer(r"NetworkConfig\(", text):
            i = m.end()
            depth = 1
            in_str = False
            while i < len(text) and depth > 0:
                c = text[i]
                if in_str:
                    if c == '"' and text[i-1] != "\\":
                        in_str = False
                else:
                    if c == '"':
                        in_str = True
                    elif c == "(":
                        depth += 1
                    elif c == ")":
                        depth -= 1
                i += 1
            block = text[m.end():i-1]
            kv = parse_named(block)
            if "id" not in kv:
                continue
            networks.append({
                "id": parse_str(kv.get("id", "")),
                "chain_type": (kv.get("chainType", '""').strip().removeprefix("ChainType.").strip('"')),
                "chain_id": parse_str(kv.get("chainId", "")),
                "name": parse_str(kv.get("name", "")),
                "rpc": parse_list_of(kv.get("rpcUrls", "listOf()")),
                "symbol": parse_str(kv.get("nativeSymbol", "")),
                "decimals": parse_int(kv.get("nativeDecimals", "18")),
                "explorer": parse_opt_str(kv.get("explorerUrl", "null")),
                "lcd": parse_opt_str(kv.get("lcdUrl", "null")),
                "lcd_fallback": parse_list_of(kv.get("lcdFallbackUrls", "listOf()")),
                "indexer": parse_opt_str(kv.get("indexerUrl", "null")),
                "testnet": parse_bool(kv.get("isTestnet", "false")),
                "hrp": parse_opt_str(kv.get("bech32Hrp", "null")),
                "coin_type": parse_int(kv.get("coinType", "0")) or None,
            })
    # EVM snapshot: Entry(chainId, name, symbol, listOf(urls), explorer?, testnet)
    snap = (WALLET / "chains/evm/EvmNetworkSnapshot.kt").read_text()
    for m in re.finditer(r"Entry\(", snap):
        after = snap[m.end():m.end()+16].lstrip()
        if not after[:1].isdigit():
            continue
        i = m.end()
        depth = 1
        in_str = False
        while i < len(snap) and depth > 0:
            c = snap[i]
            if in_str:
                if c == '"' and snap[i-1] != "\\":
                    in_str = False
            else:
                if c == '"':
                    in_str = True
                elif c == "(":
                    depth += 1
                elif c == ")":
                    depth -= 1
            i += 1
        args = split_args(snap[m.end():i-1])
        if len(args) < 6:
            continue
        networks.append({
            "id": f"EVM:{parse_int(args[0])}",
            "chain_type": "EVM",
            "chain_id": str(parse_int(args[0])),
            "name": parse_str(args[1]),
            "rpc": parse_list_of(args[3]),
            "symbol": parse_str(args[2]),
            "decimals": 18,
            "explorer": parse_opt_str(args[4]) if not parse_str(args[4]).startswith("listOf") else "",
            "lcd": "",
            "lcd_fallback": [],
            "indexer": "",
            "testnet": parse_bool(args[5]),
            "hrp": "",
            "coin_type": 60,
        })

    seen = set()
    uniq = []
    for n in networks:
        if not n["id"] or n["id"] in seen:
            continue
        seen.add(n["id"])
        uniq.append(n)

    lines = [
        "// GENERATED FILE — do not edit by hand.",
        "// Source: android/core/wallet NetworkConfig tables + EvmNetworkSnapshot.",
        "// Regenerate with: python3 desktop/scripts/gen-wallet-networks.py",
        "",
        "#[derive(Debug, Clone)]",
        "pub struct NetworkData {",
        "    pub id: &'static str,",
        "    pub chain_type: &'static str,",
        "    pub chain_id: &'static str,",
        "    pub name: &'static str,",
        "    pub rpc_urls: &'static [&'static str],",
        "    pub native_symbol: &'static str,",
        "    pub native_decimals: u32,",
        "    pub explorer_url: &'static str,",
        "    pub lcd_url: &'static str,",
        "    pub lcd_fallback_urls: &'static [&'static str],",
        "    pub indexer_url: &'static str,",
        "    pub is_testnet: bool,",
        "    pub bech32_hrp: &'static str,",
        "    pub coin_type: u32,",
        "}",
        "",
        "pub const NETWORKS: &[NetworkData] = &[",
    ]
    for n in uniq:
        coin = n["coin_type"] if n["coin_type"] else 0
        lines.append(
            "    NetworkData { id: %s, chain_type: %s, chain_id: %s, name: %s, rpc_urls: &[%s], native_symbol: %s, native_decimals: %d, explorer_url: %s, lcd_url: %s, lcd_fallback_urls: &[%s], indexer_url: %s, is_testnet: %s, bech32_hrp: %s, coin_type: %d },"
            % (
                rust_str(n["id"]), rust_str(n["chain_type"]), rust_str(n["chain_id"]), rust_str(n["name"]),
                ", ".join(rust_str(u) for u in n["rpc"]),
                rust_str(n["symbol"]), n["decimals"], rust_str(n["explorer"]), rust_str(n["lcd"]),
                ", ".join(rust_str(u) for u in n["lcd_fallback"]),
                rust_str(n["indexer"]), "true" if n["testnet"] else "false",
                rust_str(n["hrp"]), coin,
            )
        )
    lines.append("];")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines) + "\n")
    evm = sum(1 for n in uniq if n["chain_type"] == "EVM")
    print(f"wallet_networks_data.rs: {len(uniq)} networks ({evm} EVM)")
    if evm != 23:
        print(f"expected 23 EVM networks, got {evm}", )
        raise SystemExit(1)


if __name__ == "__main__":
    main()
