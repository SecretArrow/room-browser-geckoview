#!/usr/bin/env bash
# package-linux.sh - Room Browser Linux packaging (deb + tar.gz).
#
# Usage:
#   ./scripts/package-linux.sh BUILD_DIR VERSION DIST_DIR
#
#   BUILD_DIR  the desktop/ workspace root; the binary is at target/release/roombrowser
#   VERSION    release version, e.g. 1.0.86
#   DIST_DIR   output directory (created when missing)
#
# Produces in DIST_DIR:
#   roombrowser_<VERSION>_amd64.deb
#   RoomBrowser-<VERSION>-linux-x64.tar.gz
# Fails loudly (non-zero exit + message) when the build is incomplete.
set -euo pipefail

if [ "$#" -ne 3 ]; then
    echo "package-linux.sh: usage: $0 BUILD_DIR VERSION DIST_DIR" >&2
    exit 2
fi

BUILD_DIR="$1"
VERSION="$2"
DIST_DIR="$3"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BIN="$BUILD_DIR/target/release/roombrowser"
ICON="$SCRIPT_DIR/../assets/icon-256.png"
LICENSE="$SCRIPT_DIR/../LICENSE"

fail() {
    echo "package-linux.sh: FATAL: $*" >&2
    exit 1
}

[ -f "$BIN" ]     || fail "built executable not found at '$BIN' (run cargo build --release -p rblinux first)."
[ -x "$BIN" ]     || fail "'$BIN' is not executable."
[ -f "$ICON" ]    || fail "icon not found at '$ICON' (expected at desktop/assets/icon-256.png)."
[ -f "$LICENSE" ] || fail "LICENSE not found at '$LICENSE' (expected at desktop/LICENSE, next to scripts/)."
command -v dpkg-deb >/dev/null 2>&1 || fail "dpkg-deb is required but not installed."
command -v tar >/dev/null 2>&1      || fail "tar is required but not installed."

mkdir -p "$DIST_DIR"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------- .deb --------
DEB_ROOT="$WORK/deb"
install -d -m 0755 "$DEB_ROOT/DEBIAN" \
                   "$DEB_ROOT/usr/bin" \
                   "$DEB_ROOT/usr/share/applications" \
                   "$DEB_ROOT/usr/share/icons/hicolor/256x256/apps" \
                   "$DEB_ROOT/usr/share/doc/roombrowser"

cat > "$DEB_ROOT/DEBIAN/control" <<EOF
Package: roombrowser
Version: $VERSION
Section: web
Priority: optional
Architecture: amd64
Maintainer: Maragung <maragung@users.noreply.github.com>
Depends: libwebkit2gtk-4.1-0 | libwebkit2gtk-4.0-37, libgtk-3-0t64 | libgtk-3-0
Description: Room Browser - privacy desktop browser in pure C (Brave-inspired dark chrome).
 Desktop edition of Room Browser by Maragung.
EOF
chmod 0644 "$DEB_ROOT/DEBIAN/control"

install -m 0755 "$BIN" "$DEB_ROOT/usr/bin/roombrowser"

cat > "$DEB_ROOT/usr/share/applications/roombrowser.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Room Browser
Exec=roombrowser
Icon=roombrowser
Categories=Network;WebBrowser;
Terminal=false
EOF
chmod 0644 "$DEB_ROOT/usr/share/applications/roombrowser.desktop"

install -m 0644 "$ICON" "$DEB_ROOT/usr/share/icons/hicolor/256x256/apps/roombrowser.png"

cat > "$DEB_ROOT/usr/share/doc/roombrowser/copyright" <<EOF
MIT License

Copyright (c) 2026 Maragung

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
EOF
chmod 0644 "$DEB_ROOT/usr/share/doc/roombrowser/copyright"

DEB_OUT="$DIST_DIR/roombrowser_${VERSION}_amd64.deb"
# Keep ownership root:root in the archive on rootless build hosts.
DPKG_DEB_ROOT_OPTS=""
if dpkg-deb --help 2>/dev/null | grep -- --root-owner-group >/dev/null 2>&1; then
    DPKG_DEB_ROOT_OPTS="--root-owner-group"
fi
dpkg-deb --build $DPKG_DEB_ROOT_OPTS "$DEB_ROOT" "$DEB_OUT"
echo "package-linux.sh: created $DEB_OUT"

# ------------------------------------------------------------- tar.gz --------
TAR_DIR_NAME="RoomBrowser-${VERSION}-linux-x64"
TAR_ROOT="$WORK/$TAR_DIR_NAME"
install -d -m 0755 "$TAR_ROOT"

install -m 0755 "$BIN" "$TAR_ROOT/roombrowser"

cat > "$TAR_ROOT/install.sh" <<'EOF'
#!/usr/bin/env bash
# Room Browser installer (tarball layout). Run with sudo from the extracted
# directory:   sudo ./install.sh
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "install.sh: this installer must run as root (try: sudo ./install.sh)" >&2
    exit 1
fi

echo "install.sh: installing runtime dependencies (best effort)..."
sudo apt-get install -y libwebkit2gtk-4.1-dev libgtk-3-0 || true

echo "install.sh: copying roombrowser to /usr/local/bin/ ..."
cp roombrowser /usr/local/bin/
chmod 0755 /usr/local/bin/roombrowser

echo "install.sh: done. Start Room Browser with:  roombrowser"
EOF
chmod 0755 "$TAR_ROOT/install.sh"

cat > "$TAR_ROOT/README.txt" <<EOF
Room Browser $VERSION (linux x64) - by Maragung
================================================

HOW TO RUN
  - Debian/Ubuntu (recommended):
        sudo apt install ./roombrowser_${VERSION}_amd64.deb
        roombrowser
  - Any distro (tarball):
        sudo ./install.sh
        roombrowser
    install.sh tries to install libwebkit2gtk-4.1-dev and libgtk-3-0 via
    apt-get and copies the browser to /usr/local/bin/roombrowser.

REQUIREMENTS
  - WebKitGTK 4.1 (or 4.0) and GTK 3 runtime libraries:
        Debian/Ubuntu: sudo apt install libwebkit2gtk-4.1-0 libgtk-3-0
  - The Debian package declares these dependencies automatically.

DATA LOCATION
  History, bookmarks and settings live in:
        \$HOME/.config/RoomBrowser
        history.jsonl, bookmarks.jsonl, settings.txt
  Deleting that directory resets the browser.

KEYBOARD SHORTCUTS
  Ctrl+T new tab, Ctrl+W close tab, Ctrl+L focus omnibox,
  F5 / Ctrl+R reload, Alt+Left / Alt+Right back / forward.

SECURITY NOTE
  This build is NOT code-signed. Verify the SHA-256 checksum of the
  archive before trusting it:
        sha256sum RoomBrowser-${VERSION}-linux-x64.tar.gz

Room Browser is a Brave-INSPIRED dark browser written in pure C by Maragung.
It contains no Brave code or assets. MIT licensed - see LICENSE.txt.
EOF
chmod 0644 "$TAR_ROOT/README.txt"

install -m 0644 "$LICENSE" "$TAR_ROOT/LICENSE.txt"

TAR_OUT="$DIST_DIR/RoomBrowser-${VERSION}-linux-x64.tar.gz"
tar -czf "$TAR_OUT" -C "$WORK" "$TAR_DIR_NAME"
echo "package-linux.sh: created $TAR_OUT"

# ---------------------------------------------------------------- summary -----
echo "package-linux.sh: SHA-256 checksums:"
sha256sum "$DEB_OUT" "$TAR_OUT"
