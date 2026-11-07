#!/usr/bin/env bash
# Build VPN Status and install it on the TV.
#   TV_HOST=<tv ip> addons/vpn-status/install.sh
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP address}"
cd "$(dirname "$0")"
TOOLS="$(cd ../../tools && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
PKG=io.github.feresg.vpnstatus
# Script paths stay relative: with MSYS_NO_PATHCONV, Git Bash would hand Windows Python a /c/... path.
tv() { "$PY" -I ../../tools/tv.py "$@"; }

# The SDK where the launcher's build-and-install.sh looks: Android Studio's default on macOS, then on Linux, then
# Homebrew's command-line tools.
export ANDROID_HOME="${ANDROID_HOME:-$(for d in ~/Library/Android/sdk ~/Android/Sdk; do [ -d "$d" ] && echo "$d" && break; done)}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
[ -d "$ANDROID_HOME/platforms" ] || { echo "No Android SDK found at $ANDROID_HOME: install platform 34 and build-tools 34.0.0, or set ANDROID_HOME." >&2; exit 1; }
[ -f local.properties ] || echo "sdk.dir=$(printf '%s' "$ANDROID_HOME" | tr '\\' '/')" > local.properties
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
./gradlew assembleRelease -q --console=plain

"$PY" -I ../../tools/b64push.py app/build/outputs/apk/release/app-release.apk /data/local/tmp/vpn-status.apk
OUT=$(tv 'pm install -r /data/local/tmp/vpn-status.apk; rm -f /data/local/tmp/vpn-status.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: pm install failed" >&2; exit 1 ;; esac

tv "am start -n $PKG/.MainActivity >/dev/null; echo opened"
echo "Installed. VPN Status is open on the TV; press Home and answer Show card for the card."
