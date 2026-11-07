#!/usr/bin/env bash
# Build Watch, install it on the TV and, optionally, push its keys (TMDB, Trakt, Kodi).
#   TV_HOST=<tv ip> addons/watch/install.sh [watch.json]   # see the README for the keys
set -euo pipefail
CONFIG="${1:-}"
if [ -n "$CONFIG" ]; then
  [ -f "$CONFIG" ] || { echo "no such file: $CONFIG" >&2; exit 1; }
  CONFIG="$(cd "$(dirname "$CONFIG")" && pwd)/$(basename "$CONFIG")"
  if command -v cygpath >/dev/null 2>&1; then CONFIG="$(cygpath -m "$CONFIG")"; fi
fi
: "${TV_HOST:?set TV_HOST to the TV IP address}"
cd "$(dirname "$0")"
TOOLS="$(cd ../../tools && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
PKG=io.github.feresg.watch
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

"$PY" -I ../../tools/b64push.py app/build/outputs/apk/release/app-release.apk /data/local/tmp/watch.apk
OUT=$(tv 'pm install -r /data/local/tmp/watch.apk; rm -f /data/local/tmp/watch.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: pm install failed" >&2; exit 1 ;; esac

# On a TV with more than one profile, use the one on screen for every step after the install.
UOPT=""
USERS=$(tv "pm list users" | grep -c "UserInfo{" || true)
if [ "${USERS:-0}" -gt 1 ]; then
  CUR=$(tv "am get-current-user" | tr -dc '0-9')
  [ -n "$CUR" ] && UOPT="--user $CUR" && echo "This TV has $USERS users; using the one on screen, user $CUR."
fi

# Success can come before Android will start the app (a slow TV still registering it), and something on the TV can
# also remove, hide or disable it right after. Wait until Android resolves the app's own screen before using it.
LAUNCH="$PKG/.MainActivity"
READY=""
for _ in $(seq 1 15); do
  R=$(tv "pm path $UOPT $PKG; cmd package resolve-activity $UOPT --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER $PKG" || true)
  if [[ "$R" == *package:* && ( "$R" == *"$LAUNCH"* || "$R" == *"$PKG/$PKG.MainActivity"* ) ]]; then READY=1; break; fi
  sleep 1
done
if [ -z "$READY" ]; then
  echo "ERROR: pm install said Success, but after 15 s Android still can't start $LAUNCH. What the TV reports:" >&2
  tv "echo '## users'; pm list users; echo '## installed for any user (-u), disabled (-d)'; pm list packages -u $PKG; pm list packages -d $PKG; echo '## dumpsys package'; dumpsys package $PKG | grep -E 'versionName|codePath|User [0-9]+:|installed=|hidden=|suspended=|enabled=' | head -20" >&2 || true
  cat >&2 <<EOF
How to read it:
  - $PKG missing even under -u: the TV removed the app right after installing it (a vendor install guard).
  - "installed=true" only for another user than the one on screen: it went to another profile.
  - "hidden=true" or "suspended=true": something on the TV hid or suspended it.
  - listed under -d, or "enabled=2" or "3": it is disabled; tools/tv.py 'pm enable $PKG' turns it back on.
Nothing else was done after the install.
EOF
  exit 1
fi

# The keys go in as a file through the app's provider (only the ADB shell may write it), so the
# secrets never sit on a command line on the TV. The app reads it once and deletes it.
if [ -n "$CONFIG" ]; then
  STAGE=/data/local/tmp/watch-config.json
  tv --push "$CONFIG" "$STAGE" >/dev/null
  W=$(tv "content write $UOPT --uri content://$PKG.config/config.json < $STAGE; echo status=\$?; rm -f $STAGE" || true)
  case "$W" in
    *Error*|*Exception*|*status=[1-9]*) echo "$W" >&2; echo "ERROR: the keys were not stored; the app is installed without them." >&2; exit 1 ;;
  esac
  echo "Keys stored."
fi

S=$(tv "am start $UOPT -n $LAUNCH" || true)
case "$S" in *Error*|*Exception*) echo "$S" >&2; echo "ERROR: $LAUNCH would not start." >&2; exit 1 ;; esac
echo "Installed. Watch is open on the TV; press Home and answer Show card for the card."
