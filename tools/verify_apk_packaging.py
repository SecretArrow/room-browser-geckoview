"""Archive-level gates for the release APKs.

Android cares about how the zip is BUILT, not only about what is inside it, and
these are the rules `zipalign -c -P 16 -v 4` enforces: every Stored,
non-directory entry sits on a 4-byte boundary; every native library is Stored
(so the loader can mmap it) and page-aligned to 16 KB; resources.arsc is Stored.
AGP gets all of this right today, and every way it can go wrong is silent --
the APK still builds, installs and runs, and only the Play upload or a 16 KB
device notices.

Exit 0 clean, 1 gate failure, 2 could not run. `--self-test` exercises the
checker against synthetic archives; run it before trusting a verdict.
"""
import argparse
import io
import struct
import sys
import zipfile
import zlib

PAGE = 16384


def local_data_offset(handle, info):
    """Where an entry's payload actually starts.

    Read from the LOCAL header, never from the central directory. The two extra
    fields are allowed to differ and AGP makes them differ on purpose: the
    alignment padding goes into the local extra field only, so a directory
    entry reports an extra length of 0 for a library whose real padding is
    ~15 KB. Trusting the directory's length reports every library in a
    correctly built APK as misaligned.
    """
    handle.seek(info.header_offset)
    header = handle.read(30)
    if header[:4] != b"PK\x03\x04":
        raise ValueError(f"{info.filename}: no local header at {info.header_offset}")
    name_len, extra_len = struct.unpack("<HH", header[26:30])
    return info.header_offset + 30 + name_len + extra_len


def rules_for(name, compress_type):
    """(label, modulus) each entry must satisfy; 0 means Stored.

    Compressed entries and directory entries are exempt from the 4-byte rule,
    which is zipalign's own split: it prints "OK - compressed" and
    "OK - directory" without ever measuring them.
    """
    if name.startswith("lib/") and name.endswith(".so"):
        return (("Stored", 0), (f"page-aligned ({PAGE})", PAGE))
    if name == "resources.arsc":
        return (("Stored", 0), ("4-byte aligned", 4))
    if compress_type == 0 and not name.endswith("/"):
        return (("4-byte aligned", 4),)
    return ()


def check(path, page=PAGE):
    """Returns [(entry, problem)]; empty means the archive passes."""
    problems = []
    with open(path, "rb") as handle:
        for info in zipfile.ZipFile(handle).infolist():
            rules = rules_for(info.filename, info.compress_type)
            if not rules:
                continue
            offset = local_data_offset(handle, info)
            for label, modulus in rules:
                if modulus == 0:
                    if info.compress_type != 0:
                        problems.append((info.filename, f"{label}: compress_type={info.compress_type}"))
                elif offset % modulus:
                    problems.append((
                        info.filename,
                        f"{label}: data offset {offset} is {offset % modulus} bytes past a "
                        f"{modulus}-byte boundary",
                    ))
    return problems


# --- self-test -------------------------------------------------------------
# The gate is only worth having if it is known to reject what it is meant to
# reject, so the fixtures below include the padded-local-extra archive that the
# real build produces and the three ways it can be wrong.

def build_zip(entries):
    """Minimal Stored-only zip writer.

    entries: (name, data, align, pad_local_only). `align` pads the LOCAL extra
    field until the payload lands on a multiple of it; 0 adds no padding.
    """
    out, directory = b"", b""
    for name, data, align, pad_local_only in entries:
        name_b = name.encode()
        pad = (-(len(out) + 30 + len(name_b))) % align if align else 0
        local_extra = b"\x00" * pad
        cd_extra = b"" if pad_local_only else local_extra
        crc = zlib.crc32(data) & 0xFFFFFFFF
        out += struct.pack(
            "<4sHHHHHIIIHH", b"PK\x03\x04", 20, 0, 0, 0, 0,
            crc, len(data), len(data), len(name_b), len(local_extra),
        ) + name_b + local_extra + data
        directory += struct.pack(
            "<4sHHHHHHIIIHHHHHII", b"PK\x01\x02", 20, 20, 0, 0, 0, 0,
            crc, len(data), len(data), len(name_b), len(cd_extra), 0, 0, 0, 0, len(out) - len(data) - len(local_extra) - len(name_b) - 30,
        ) + name_b + cd_extra
    eocd = struct.pack(
        "<4sHHHHIIH", b"PK\x05\x06", 0, 0, len(entries), len(entries), len(directory), len(out), 0
    )
    return out + directory + eocd


def deflated_zip(name, data, align):
    """A Stored-aligned archive whose one library entry is deflated."""
    stored = build_zip([(name, data, align, True)])
    handle = io.BytesIO(stored)
    info = zipfile.ZipFile(handle).infolist()[0]
    # Rebuild with compress_type=1 in both headers, keeping the same layout.
    off = info.header_offset
    patched = bytearray(stored)
    patched[off + 8:off + 10] = struct.pack("<H", 8)          # local: deflate
    cd_off = patched.rfind(b"PK\x01\x02")
    patched[cd_off + 10:cd_off + 12] = struct.pack("<H", 8)    # directory: deflate
    return bytes(patched)


def self_test():
    lib = "lib/arm64-v8a/libxul.so"
    cases = []

    padded = build_zip([(lib, b"x" * 4096, PAGE, True)])
    with zipfile.ZipFile(io.BytesIO(padded)) as z:
        info = z.infolist()[0]
    local_extra = struct.unpack("<H", padded[info.header_offset + 28:info.header_offset + 30])[0]
    if local_extra == 0 or len(info.extra) != 0:
        raise AssertionError("fixture no longer exercises the local-vs-directory extra gap")
    cases.append(("padded local extra, stored, page-aligned", padded, []))

    cases.append(("deflated library", deflated_zip(lib, b"x" * 4096, PAGE), [(lib, "Stored")]))
    cases.append(("library with no alignment padding",
                  build_zip([(lib, b"x" * 4096, 0, False)]), [(lib, "page-aligned")]))

    arsc = build_zip([("resources.arsc", b"y" * 512, 4, False)])
    cases.append(("resources.arsc stored and 4-byte aligned", arsc, []))

    # A Stored entry whose payload is not 4-byte aligned, reached by offsetting
    # it behind a directory entry. The directory is exempt from the rule, so
    # resources.arsc is the only entry this fixture can blame; the name length
    # is searched rather than hardcoded so a rename cannot quietly turn this
    # into an aligned archive that passes.
    for n in range(1, 5):
        arsc_off = build_zip([("r" * n + "/", b"", 0, False), ("resources.arsc", b"y" * 512, 0, False)])
        with zipfile.ZipFile(io.BytesIO(arsc_off)) as z:
            off = local_data_offset(io.BytesIO(arsc_off), z.infolist()[1])
        if off % 4:
            break
    else:
        raise AssertionError("could not build a misaligned resources.arsc fixture")
    cases.append(("resources.arsc stored but misaligned", arsc_off, [("resources.arsc", "4-byte aligned")]))

    # The baseline rule zipalign reports most often, plus its exemptions.
    cases.append(("stored entry on a 4-byte boundary",
                  build_zip([("classes.dex", b"d" * 7, 4, False)]), []))
    cases.append(("stored entry off a 4-byte boundary",
                  build_zip([("classes.dex", b"d" * 7, 0, False)]),
                  [("classes.dex", "4-byte aligned")]))
    cases.append(("compressed entry is not alignment-gated",
                  deflated_zip("classes.dex", b"d" * 7, 0), []))
    cases.append(("directory entry is not alignment-gated",
                  build_zip([("res/", b"", 0, False)]), []))

    failures = 0
    for label, blob, expected in cases:
        got = check_from_blob(blob)
        ok = [name for name, _ in got] == [name for name, _ in expected] and \
            all(any(frag in problem for _, problem in got) for _, frag in expected)
        print(f"  {'ok  ' if ok else 'FAIL'} {label}")
        if not ok:
            failures += 1
            print(f"       expected {expected}, got {got}")
    if failures:
        raise SystemExit(f"self-test failed: {failures} of {len(cases)} cases")
    print(f"  {len(cases)} cases pass")


def check_from_blob(blob):
    import tempfile
    import os
    fd, path = tempfile.mkstemp(suffix=".apk")
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(blob)
        return check(path)
    finally:
        os.unlink(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", nargs="*", help="release APKs to check")
    parser.add_argument("--self-test", action="store_true", help="exercise the checker itself")
    parser.add_argument("--page", type=int, default=PAGE, help="native library page size")
    args = parser.parse_args()

    if args.self_test:
        self_test()
    if not args.apks:
        return 0 if args.self_test else 2

    failed = False
    for apk in args.apks:
        try:
            problems = check(apk, args.page)
        except Exception as exc:  # noqa: BLE001 - report, never traceback over a gate
            print(f"::error::{apk}: could not be checked: {exc}")
            return 2
        if problems:
            failed = True
            print(f"::error::{apk} fails the archive packaging rules:")
            for name, problem in problems:
                print(f"  {name}: {problem}")
        else:
            print(f"{apk}: archive packaging is correct (Stored + aligned, zipalign -P 16 rules)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
