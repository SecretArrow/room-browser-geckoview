# package-windows.ps1 - Room Browser Windows packaging.
#
# Usage (pwsh / Windows PowerShell 5.1):
#   pwsh ./scripts/package-windows.ps1 -BuildDir <dir-with-RoomBrowser.exe-and-WebView2Loader.dll> `
#                                      -Version 1.0.86 `
#                                      -DistDir <output-dir>
#
# Produces: <DistDir>\RoomBrowser-<Version>-windows-x64.zip containing
#   RoomBrowser.exe, WebView2Loader.dll, LICENSE.txt, README.txt
# Fails loudly (non-zero exit + error message) when the staged build is incomplete.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BuildDir,

    [Parameter(Mandatory = $true)]
    [string]$Version,

    [Parameter(Mandatory = $true)]
    [string]$DistDir
)

$ErrorActionPreference = 'Stop'

# --- Locate inputs -------------------------------------------------------------

# The Rust build puts the exe at target/<triple>/release/ (cross-style) or
# target/release/ (host-style); the loader DLL is staged by CI next to it.
$exePath = Join-Path $BuildDir 'target\x86_64-pc-windows-msvc\release\RoomBrowser.exe'
if (-not (Test-Path -LiteralPath $exePath)) { $exePath = Join-Path $BuildDir 'target\release\RoomBrowser.exe' }
if (-not (Test-Path -LiteralPath $exePath)) { $exePath = Join-Path $BuildDir 'RoomBrowser.exe' }
$dllPath = Join-Path $BuildDir 'WebView2Loader.dll'

$licensePath = Join-Path (Split-Path -Parent $PSScriptRoot) 'LICENSE'

if (-not (Test-Path -LiteralPath $exePath -PathType Leaf)) {
    throw "package-windows.ps1: FATAL: RoomBrowser.exe not found at '$exePath'. Build it first (cargo build --release -p rbwin --target x86_64-pc-windows-msvc)."
}
if (-not (Test-Path -LiteralPath $dllPath -PathType Leaf)) {
    throw "package-windows.ps1: FATAL: WebView2Loader.dll not found at '$dllPath'. Stage the WebView2 loader DLL next to RoomBrowser.exe before packaging."
}
if (-not (Test-Path -LiteralPath $licensePath -PathType Leaf)) {
    throw "package-windows.ps1: FATAL: LICENSE not found at '$licensePath' (expected at desktop/LICENSE, next to scripts/)."
}

# --- Output locations ----------------------------------------------------------

if (-not (Test-Path -LiteralPath $DistDir)) {
    New-Item -ItemType Directory -Path $DistDir -Force | Out-Null
}

$zipName = "RoomBrowser-$Version-windows-x64.zip"
$zipPath = Join-Path $DistDir $zipName

$readmeText = @"
Room Browser $Version (windows x64) - by Maragung
==================================================

HOW TO RUN
  1. Unzip this archive anywhere (for example C:\Tools\RoomBrowser).
  2. Double-click RoomBrowser.exe.
  3. On the very first run Room Browser opens DuckDuckGo as the start page.

REQUIREMENTS
  - Windows 10 or later, x64.
  - Microsoft Edge WebView2 Evergreen Runtime. It is preinstalled on
    Windows 11 and on most updated Windows 10 systems. If it is missing,
    download it from:
    https://developer.microsoft.com/microsoft-edge/webview2/
    (Room Browser shows an honest warning and keeps the window open
    when the runtime is unavailable.)

DATA LOCATION
  History, bookmarks and settings live in:
    %APPDATA%\RoomBrowser
      history.jsonl, bookmarks.jsonl, settings.txt
  The WebView2 browser profile lives in:
    %LOCALAPPDATA%\RoomBrowser\WebView2
  Deleting those directories resets the browser.

KEYBOARD SHORTCUTS
  Ctrl+T new tab, Ctrl+W close tab, Ctrl+L focus omnibox,
  F5 / Ctrl+R reload, Alt+Left / Alt+Right back / forward.

SECURITY NOTE
  This build is NOT code-signed. Windows SmartScreen may show an
  "unknown publisher" warning on the first launch; verify the SHA-256
  checksum of the ZIP before trusting it and only then choose
  "More info" -> "Run anyway":
    PowerShell: Get-FileHash "$zipName" -Algorithm SHA256

Room Browser is a Brave-INSPIRED dark browser written in pure C by Maragung.
It contains no Brave code or assets. MIT licensed - see LICENSE.txt.
"@

# --- Stage and compress --------------------------------------------------------

$stage = Join-Path ([System.IO.Path]::GetTempPath()) "roombrowser-stage-$Version-$([System.IO.Path]::GetRandomFileName())"
New-Item -ItemType Directory -Path $stage -Force | Out-Null

try {
    Copy-Item -LiteralPath $exePath    -Destination (Join-Path $stage 'RoomBrowser.exe')
    Copy-Item -LiteralPath $dllPath    -Destination (Join-Path $stage 'WebView2Loader.dll')
    Copy-Item -LiteralPath $licensePath -Destination (Join-Path $stage 'LICENSE.txt')
    Set-Content -LiteralPath (Join-Path $stage 'README.txt') -Value $readmeText -Encoding ASCII

    if (Test-Path -LiteralPath $zipPath) {
        Remove-Item -LiteralPath $zipPath -Force
    }
    Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zipPath -CompressionLevel Optimal
}
finally {
    if (Test-Path -LiteralPath $stage) {
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
}

if (-not (Test-Path -LiteralPath $zipPath -PathType Leaf)) {
    throw "package-windows.ps1: FATAL: archive was not created at '$zipPath'."
}

$hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash
Write-Host "package-windows.ps1: created $zipPath"
Write-Host "package-windows.ps1: SHA-256 $hash"
Write-Host "package-windows.ps1: contents: RoomBrowser.exe, WebView2Loader.dll, LICENSE.txt, README.txt"
