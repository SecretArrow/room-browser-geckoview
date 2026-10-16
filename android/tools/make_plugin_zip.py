#!/usr/bin/env python3
"""Deterministic packer for Room plugin ZIPs.

Validates the source directory against the package rules (plugin.json format,
forbidden binaries, size caps) and writes a DEFLATE archive whose every entry
is dated 1980-01-01 00:00:00, carries no extra fields and sits in sorted
order — two runs over the same tree produce the same bytes, so a rebuilt
example shows up as an empty diff rather than a shuffled one.

The manifest checks mirror PluginManifestValidator (the design's §3.2) over
everything a source tree can prove; the archive checks mirror the §3.1
extraction guards. A source that fails any of them is refused here rather
than at install time on a device.

Run: python3 make_plugin_zip.py <sourceDir> <out.zip>
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import zipfile

MANIFEST_NAME = "plugin.json"
FORBIDDEN_SUFFIXES = (".node", ".so", ".dll", ".exe", ".bat", ".sh", ".jar", ".apk")
MAX_ENTRIES = 512
MAX_TOTAL_BYTES = 20 * 1024 * 1024
MAX_ENTRY_BYTES = 2 * 1024 * 1024
MAX_MANIFEST_BYTES = 64 * 1024
MAX_NAME_CHARS = 200

PERMISSIONS = ("storage", "network", "agent.tools", "chat.context", "agent.generate")
HOOKS = ("message",)
CONFIG_TYPES = ("string", "number", "boolean")
INPUT_TYPES = ("string", "number", "boolean", "choice", "secret")
TEXT_TYPES = ("string", "secret")
PLACEHOLDER_TYPES = ("string", "number", "secret")
INPUT_KEYS = {
    "key", "type", "title", "description", "required", "default",
    "placeholder", "pattern", "patternError", "minLength", "maxLength",
    "min", "max", "options",
}
MANIFEST_KEYS = {
    "id", "name", "version", "description", "author", "license", "api",
    "entry", "permissions", "network", "config", "commands", "hooks",
    "inputs",
}

ID_RE = re.compile(r"^[a-z][a-z0-9]*(\.[a-z0-9-]+){1,4}$")
VERSION_RE = re.compile(r"^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$")
COMMAND_RE = re.compile(r"^[a-z][a-z0-9_-]{0,23}$")
CONFIG_KEY_RE = re.compile(r"^[a-z][a-z0-9_]{0,31}$")
LABEL_RE = re.compile(r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")
IPV4_RE = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")


def length_error(value: object, field: str, low: int, high: int) -> str | None:
    if not isinstance(value, str) or not low <= len(value) <= high:
        return f"{field} must be {low}..{high} characters"
    return None


def manifest_error(data: object) -> list[str]:
    """Every §3.2 rule a source tree can check; empty means valid."""
    errors: list[str] = []
    if not isinstance(data, dict):
        return ["the manifest must be a JSON object"]

    unknown = sorted(set(data) - MANIFEST_KEYS)
    if unknown:
        # Strict on purpose: a manifest from a future version has to fail at
        # install, not silently behave as something it never declared.
        errors.append(f"unknown top-level keys: {', '.join(unknown)}")

    _id = data.get("id")
    if not isinstance(_id, str) or not 3 <= len(_id) <= 64 or not ID_RE.match(_id):
        errors.append("id must match ^[a-z][a-z0-9]*(\\.[a-z0-9-]+){1,4}$ and be 3..64 characters")

    name = data.get("name")
    if name_error := length_error(name, "name", 1, 64):
        errors.append(name_error)
    elif any(ord(char) < 0x20 or ord(char) == 0x7F for char in name):
        errors.append("name must be printable characters with no control characters")

    version = data.get("version")
    if not isinstance(version, str) or not VERSION_RE.match(version):
        errors.append("version must be ^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$")

    if (problem := length_error(data.get("description", ""), "description", 0, 500)):
        errors.append(problem)

    author = data.get("author")
    if author is not None:
        if not isinstance(author, dict):
            errors.append("author must be an object with a name")
        else:
            if (problem := length_error(author.get("name"), "author.name", 1, 64)):
                errors.append(problem)
            if (problem := length_error(author.get("email", ""), "author.email", 0, 200)):
                errors.append(problem)
            url = author.get("url", "")
            if (problem := length_error(url, "author.url", 0, 200)):
                errors.append(problem)
            elif url and not url.startswith(("http://", "https://")):
                errors.append("author.url must start with http:// or https://")

    if (problem := length_error(data.get("license", ""), "license", 0, 64)):
        errors.append(problem)

    api = data.get("api")
    if not isinstance(api, int) or isinstance(api, bool) or api != 1:
        # The one supported version, named in the refusal rather than implied.
        errors.append("api must be exactly 1 (the only supported plugin API version)")

    entry = data.get("entry")
    if not isinstance(entry, str) or not 1 <= len(entry) <= 200:
        errors.append("entry must be a relative path of 1..200 characters")
    elif entry.startswith("/") or "\\" in entry or ".." in entry.split("/"):
        errors.append("entry must be a relative path that stays inside the plugin")
    elif not entry.endswith(".js"):
        errors.append('entry must end in ".js"')

    permissions = data.get("permissions")
    if not isinstance(permissions, list) or len(permissions) > 5 \
            or len(set(permissions)) != len(permissions) \
            or any(item not in PERMISSIONS for item in permissions):
        errors.append(f"permissions must be unique and drawn from {', '.join(PERMISSIONS)}")

    network = data.get("network")
    if network is not None:
        if not isinstance(network, dict) or not isinstance(network.get("allow"), list):
            errors.append("network must be an object with an allow list")
        else:
            allow = network["allow"]
            if not 1 <= len(allow) <= 20:
                errors.append("network.allow must hold 1..20 host patterns")
            for pattern in allow:
                if problem := pattern_error(pattern):
                    errors.append(f"network allow pattern {pattern!r} {problem}")
    if isinstance(permissions, list) and "network" in permissions:
        if not isinstance(network, dict) or not isinstance(network.get("allow"), list) \
                or len(network.get("allow", [])) < 1:
            errors.append("the network permission requires a non-empty network.allow list")

    config = data.get("config")
    if config is not None:
        if not isinstance(config, dict) or not isinstance(config.get("schema", []), list):
            errors.append("config must be an object with a schema array")
        else:
            errors.extend(schema_errors(config.get("schema", [])))

    errors.extend(inputs_errors(data.get("inputs")))

    commands = data.get("commands", [])
    if not isinstance(commands, list) or len(commands) > 12:
        errors.append("commands must be an array of at most 12 items")
    else:
        names = [item.get("name") for item in commands if isinstance(item, dict)]
        if len(names) != len(commands):
            errors.append("every command must be an object")
        elif len(set(names)) != len(names):
            errors.append("command names must be unique")
        else:
            for item in commands:
                command_name = item.get("name")
                if not isinstance(command_name, str) or not COMMAND_RE.match(command_name):
                    errors.append(f"command name {command_name!r} must match ^[a-z][a-z0-9_-]{{0,23}}$")
                elif command_name == "plugins":
                    errors.append("the command name 'plugins' is reserved")
                if problem := length_error(item.get("title", ""), "command title", 0, 64):
                    errors.append(problem)
                if problem := length_error(item.get("description", ""), "command description", 0, 200):
                    errors.append(problem)

    hooks = data.get("hooks")
    if hooks is not None:
        if not isinstance(hooks, list) or len(hooks) > 1 \
                or any(item not in HOOKS for item in hooks) \
                or len(set(hooks)) != len(hooks):
            errors.append(f"hooks must be a subset of {', '.join(HOOKS)}, unique, at most one")

    return errors


def schema_errors(schema: list) -> list[str]:
    errors: list[str] = []
    if len(schema) > 12:
        errors.append("config.schema must hold at most 12 items")
    keys = []
    for item in schema:
        if not isinstance(item, dict):
            errors.append("every config.schema item must be an object")
            continue
        key = item.get("key")
        if not isinstance(key, str) or not CONFIG_KEY_RE.match(key):
            errors.append(f"config key {key!r} must match ^[a-z][a-z0-9_]{{0,31}}$")
        else:
            keys.append(key)
        if item.get("type") not in CONFIG_TYPES:
            errors.append(f"config type {item.get('type')!r} must be one of {', '.join(CONFIG_TYPES)}")
        if "default" in item and item["default"] is not None and not default_matches(item.get("type"), item["default"]):
            errors.append(f"config default for {key!r} does not match its type")
        if problem := length_error(item.get("title", ""), "config title", 0, 64):
            errors.append(problem)
        if problem := length_error(item.get("description", ""), "config description", 0, 200):
            errors.append(problem)
    if len(keys) != len(set(keys)):
        errors.append("config keys must be unique")
    return errors


def default_matches(kind: str, default: object) -> bool:
    if kind == "boolean":
        return isinstance(default, bool)
    if kind == "number":
        # bool is an int in python; a default of true is not a number here.
        return isinstance(default, (int, float)) and not isinstance(default, bool)
    return isinstance(default, str)


def inputs_errors(inputs: object) -> list[str]:
    """The `inputs` array's rules, mirroring PluginInputsSchema.validate over
    everything a source tree can prove; absent/None reads as no form."""
    if inputs is None:
        return []
    if not isinstance(inputs, list):
        return ["inputs must be an array of field objects"]
    errors: list[str] = []
    if len(inputs) > 12:
        errors.append("inputs must hold at most 12 items")
    keys: list[str] = []
    for index, item in enumerate(inputs):
        if not isinstance(item, dict):
            errors.append(f"inputs entry {index + 1} must be an object")
            continue
        key = item.get("key")
        who = f"key {key!r}" if isinstance(key, str) and key else f"entry {index + 1}"
        if not isinstance(key, str) or not CONFIG_KEY_RE.match(key):
            errors.append(f"inputs {who}: key must match ^[a-z][a-z0-9_]{{0,31}}$")
        elif key in keys:
            errors.append(f"inputs {who}: keys must be unique")
        else:
            keys.append(key)
        kind = item.get("type")
        if kind not in INPUT_TYPES:
            errors.append(f"inputs {who}: type must be one of {', '.join(INPUT_TYPES)}")
        title = item.get("title")
        if not isinstance(title, str) or not 1 <= len(title) <= 64:
            errors.append(f"inputs {who}: title must be 1..64 characters")
        description = item.get("description", "")
        if isinstance(description, str) and len(description) > 200:
            errors.append(f"inputs {who}: description must be at most 200 characters")
        if "required" in item and not isinstance(item["required"], bool):
            errors.append(f"inputs {who}: required must be a boolean")
        options = item.get("options")
        if options is not None and (not isinstance(options, list) or
                                    any(not isinstance(option, dict) for option in options)):
            errors.append(f"inputs {who}: options must be an array of objects")
            options = None
        values: list[str] = []
        if isinstance(options, list):
            if kind != "choice":
                errors.append(f"inputs {who}: only choice fields may list options")
            elif not 1 <= len(options) <= 12:
                errors.append(f"inputs {who}: options must hold 1..12 items")
            for option in options:
                value = option.get("value")
                if not isinstance(value, str) or not 1 <= len(value) <= 64:
                    errors.append(f"inputs {who}: option values must be 1..64 characters")
                elif value in values:
                    errors.append(f"inputs {who}: option values must be unique")
                else:
                    values.append(value)
                label = option.get("label", "")
                if isinstance(label, str) and len(label) > 64:
                    errors.append(f"inputs {who}: option labels must be at most 64 characters")
        if "default" in item and item["default"] is not None:
            if kind == "choice":
                if item["default"] not in values:
                    errors.append(f"inputs {who}: default must be one of the listed options")
            elif not default_matches(kind if kind in INPUT_TYPES else "string", item["default"]):
                errors.append(f"inputs {who}: default does not match its type")
        placeholder = item.get("placeholder", "")
        if isinstance(placeholder, str):
            if placeholder and kind not in PLACEHOLDER_TYPES:
                errors.append(f"inputs {who}: only string, number and secret fields may have a placeholder")
            if len(placeholder) > 100:
                errors.append(f"inputs {who}: placeholder must be at most 100 characters")
        pattern = item.get("pattern", "")
        if isinstance(pattern, str):
            if pattern and kind not in TEXT_TYPES:
                errors.append(f"inputs {who}: only string and secret fields may have a pattern")
            if len(pattern) > 200:
                errors.append(f"inputs {who}: pattern must be at most 200 characters")
            if pattern and kind in TEXT_TYPES:
                try:
                    re.compile(pattern)
                except re.error:
                    errors.append(f"inputs {who}: pattern must be a valid regular expression")
        pattern_error_text = item.get("patternError", "")
        if isinstance(pattern_error_text, str) and len(pattern_error_text) > 200:
            errors.append(f"inputs {who}: patternError must be at most 200 characters")
        for bound in ("minLength", "maxLength"):
            if bound in item:
                if kind not in TEXT_TYPES:
                    errors.append(f"inputs {who}: only string and secret fields may have a {bound}")
                elif not isinstance(item[bound], int) or isinstance(item[bound], bool) or item[bound] < 0:
                    errors.append(f"inputs {who}: {bound} must be a non-negative integer")
        if all(isinstance(item.get(bound), int) and not isinstance(item.get(bound), bool)
               for bound in ("minLength", "maxLength")) and \
                "minLength" in item and "maxLength" in item and item["minLength"] > item["maxLength"]:
            errors.append(f"inputs {who}: minLength must not exceed maxLength")
        for bound in ("min", "max"):
            if bound in item:
                if kind != "number":
                    errors.append(f"inputs {who}: only number fields may have a {bound}")
                elif not isinstance(item[bound], (int, float)) or isinstance(item[bound], bool):
                    errors.append(f"inputs {who}: {bound} must be a number")
        if all(isinstance(item.get(bound), (int, float)) and not isinstance(item.get(bound), bool)
               for bound in ("min", "max")) and "min" in item and "max" in item and \
                item["min"] > item["max"]:
            errors.append(f"inputs {who}: min must not exceed max")
        for name in sorted(set(item) - INPUT_KEYS):
            errors.append(f"inputs {who}: unknown field {name!r}")
    return errors


def pattern_error(pattern: object) -> str | None:
    if not isinstance(pattern, str) or not pattern:
        return "must be a non-empty string"
    if pattern != pattern.lower():
        return "must be lowercase"
    if any(char in pattern for char in "/: "):
        return "must not contain '/', ':' or spaces"
    body = pattern[2:] if pattern.startswith("*.") else pattern
    if "*" in body:
        return "may only use '*' as a leading '*.' prefix"
    if IPV4_RE.match(body):
        return "is an IP literal; hostnames only"
    labels = body.split(".")
    if len(labels) < 2:
        return "is a single-label host; localhost included is refused"
    for label in labels:
        if not label or not LABEL_RE.match(label):
            return "is not a hostname"
    return None


def collect(source: pathlib.Path) -> tuple[list[tuple[str, pathlib.Path]], list[str]]:
    """The archive's entries as (name, file), plus everything wrong with them."""
    errors: list[str] = []
    entries: list[tuple[str, pathlib.Path]] = []
    total = 0
    if not source.is_dir():
        return [], [f"{source} is not a directory"]
    if not (source / MANIFEST_NAME).is_file():
        return [], [f"the manifest ({MANIFEST_NAME}) must sit at the source directory's root"]

    files = sorted(source.rglob("*"))
    for file in files:
        if file.is_symlink():
            # A link in the source never becomes one in the archive; refusing
            # it outright beats shipping whatever it points at.
            errors.append(f"{file.name}: symlinks are not packable")
            continue
        if not file.is_file():
            continue
        name = file.relative_to(source).as_posix()
        if not 1 <= len(name) <= MAX_NAME_CHARS:
            errors.append(f"{name}: entry names must be 1..{MAX_NAME_CHARS} characters")
        if "\\" in name or name.startswith("/"):
            errors.append(f"{name}: entry names must be relative with forward slashes")
        if ".." in name.split("/"):
            errors.append(f"{name}: entry names must not climb out of the archive")
        if name.lower().endswith(FORBIDDEN_SUFFIXES):
            errors.append(f"{name}: binaries and scripts are not plugin entries")
        size = file.stat().st_size
        total += size
        if size > MAX_ENTRY_BYTES:
            errors.append(f"{name}: entries must hold at most {MAX_ENTRY_BYTES} bytes")
        entries.append((name, file))
    if len(entries) > MAX_ENTRIES:
        errors.append(f"the archive would hold more than {MAX_ENTRIES} entries")
    if total > MAX_TOTAL_BYTES:
        errors.append(f"the archive would hold more than {MAX_TOTAL_BYTES} bytes")
    return entries, errors


def write_zip(entries: list[tuple[str, pathlib.Path]], out: pathlib.Path) -> None:
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, file in sorted(entries, key=lambda pair: pair[0]):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            # A plain readable file on every platform, so the bytes do not
            # depend on the machine that packed them.
            info.create_system = 3
            info.external_attr = 0o644 << 16
            archive.writestr(info, file.read_bytes())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("sourceDir", help="the plugin's source directory (plugin.json at its root)")
    parser.add_argument("outZip", help="the zip to write")
    args = parser.parse_args()

    source = pathlib.Path(args.sourceDir)
    out = pathlib.Path(args.outZip)

    manifest_bytes = b""
    try:
        manifest_bytes = (source / MANIFEST_NAME).read_bytes()
    except OSError:
        pass  # collect() reports the missing manifest with its own words
    if len(manifest_bytes) > MAX_MANIFEST_BYTES:
        print(f"plugin package rejected: {MANIFEST_NAME} must hold at most {MAX_MANIFEST_BYTES} bytes",
              file=sys.stderr)
        return 1

    data = None
    try:
        data = json.loads(manifest_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        print(f"plugin package rejected: {MANIFEST_NAME} is not valid UTF-8 JSON", file=sys.stderr)
        return 1

    errors = manifest_error(data)
    if isinstance(data, dict) and isinstance(data.get("entry"), str):
        if not (source / data["entry"]).is_file():
            errors.append(f"the entry '{data['entry']}' does not exist in the source directory")
    entries, file_errors = collect(source)
    errors.extend(file_errors)
    if errors:
        print("plugin package rejected:", file=sys.stderr)
        for problem in errors:
            print(f"  {problem}", file=sys.stderr)
        return 1

    out.parent.mkdir(parents=True, exist_ok=True)
    write_zip(entries, out)
    print(f"  wrote {len(entries)} entries to {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
