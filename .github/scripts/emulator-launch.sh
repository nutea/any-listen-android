#!/usr/bin/env bash
# Usage: emulator-launch.sh repro|verify
# repro  — install shipped beta.2, cold-start, write beta2-logcat.txt (never fails the job)
# verify — install this revision's minified release APK and require the process to stay up
set -euo pipefail

MODE="${1:-repro}"
ACTIVITY=io.github.nutea.anylisten.MainActivity
RELEASE_PKG=io.github.nutea.anylisten
BETA2_URL=https://github.com/nutea/any-listen-android/releases/download/v0.1.1-beta.2/any-listen-android-0.1.1-beta.2.apk

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

repro_beta2() {
  echo "== Repro shipped v0.1.1-beta.2 (expected crash; do not fail the job) =="
  curl -fsSL -o /tmp/beta2.apk "$BETA2_URL"
  adb install -r -t /tmp/beta2.apk
  set +e
  start_and_watch "$RELEASE_PKG" beta2-logcat.txt
  local rc=$?
  set -e
  if [ "$rc" -eq 0 ]; then
    echo "NOTE: beta.2 stayed alive on this emulator; crash may be device-specific."
  else
    echo "beta.2 died or threw FATAL. Stack is in beta2-logcat.txt"
    extract_fatal beta2-logcat.txt
  fi
  adb uninstall "$RELEASE_PKG" || true
}

verify_fix() {
  echo "== Verify this revision's minified release APK =="
  local apk=app/build/outputs/apk/release/app-release.apk
  if [ ! -f "$apk" ]; then
    echo "Missing ${apk}"
    exit 1
  fi
  adb install -r -t "$apk"
  start_and_watch "$RELEASE_PKG" fixed-logcat.txt
  echo "minified release stayed up"
}

case "$MODE" in
  repro) repro_beta2 ;;
  verify) verify_fix ;;
  *) echo "usage: $0 repro|verify" >&2; exit 2 ;;
esac
