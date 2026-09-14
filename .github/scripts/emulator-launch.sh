#!/usr/bin/env bash
# Install APKs on a booted emulator, cold-start MainActivity, and capture logcat.
set -euo pipefail

ACTIVITY=io.github.nutea.anylisten.MainActivity
RELEASE_PKG=io.github.nutea.anylisten

dump_logcat() {
  local out="$1"
  adb logcat -d -v threadtime > "$out" || true
}

extract_fatal() {
  local log="$1"
  grep -A 50 -E 'FATAL EXCEPTION|AndroidRuntime' "$log" | head -100 || true
}

start_and_watch() {
  local pkg="$1"
  local out="$2"
  adb logcat -c || true
  adb shell am force-stop "$pkg" || true
  adb shell am start -W -n "$pkg/$ACTIVITY"
  sleep 8
  dump_logcat "$out"
  local pid
  pid="$(adb shell pidof -s "$pkg" 2>/dev/null | tr -d '\r' || true)"
  echo "pidof ${pkg}: ${pid:-none}"
  if grep -E 'FATAL EXCEPTION|AndroidRuntime' "$out" | grep -q "$pkg"; then
    echo "FATAL EXCEPTION for ${pkg}"
    extract_fatal "$out"
    return 1
  fi
  if [ -z "${pid}" ]; then
    echo "process ${pkg} is not running"
    extract_fatal "$out"
    return 1
  fi
  return 0
}

echo "== Repro shipped v0.1.1-beta.2 (expected crash; do not fail the job) =="
curl -fsSL -o /tmp/beta2.apk \
  https://github.com/nutea/any-listen-android/releases/download/v0.1.1-beta.2/any-listen-android-0.1.1-beta.2.apk
adb install -r -t /tmp/beta2.apk
set +e
start_and_watch "$RELEASE_PKG" beta2-logcat.txt
beta2_rc=$?
set -e
if [ "$beta2_rc" -eq 0 ]; then
  echo "NOTE: beta.2 stayed alive on this emulator; crash may be device-specific."
else
  echo "beta.2 died or threw FATAL. Stack is in beta2-logcat.txt"
fi
adb uninstall "$RELEASE_PKG" || true

echo "== Verify this revision's minified release APK =="
APK=app/build/outputs/apk/release/app-release.apk
if [ ! -f "$APK" ]; then
  echo "Missing ${APK}"
  exit 1
fi
adb install -r -t "$APK"
start_and_watch "$RELEASE_PKG" fixed-logcat.txt
echo "minified release stayed up"
