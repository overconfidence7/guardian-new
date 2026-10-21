#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner once the emulator has booted.
# Usage: qa/daily/run-device-suite.sh <results-dir> <include-known-bugs: true|false> <host-checks: true|false>
# Expects the app, test and stand-in APKs to be built already (see android-emulator.yml).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$(mkdir -p "$1" && cd "$1" && pwd)"
KNOWN="${2:-false}"
HOST="${3:-false}"
STUBS="$ROOT/qa/antigravity/stub-apps/app/build/outputs/apk"
status=0

for pkg in com.anydesk.anydeskandroid com.teamviewer.quicksupport.market com.carriez.flutter_hbb org.guardianqa.screenhelper; do
  adb uninstall "$pkg" >/dev/null 2>&1 || true
done
# Stand-ins: the ordinary watched apps are installed up front; the remote-control ones and
# Screen Helper are only pushed, so tests can install them and trip the alarm themselves.
adb shell mkdir -p /data/local/tmp/stubs
for apk in "$STUBS"/*/debug/*.apk; do
  flavor="$(basename "$(dirname "$(dirname "$apk")")")"
  adb push "$apk" "/data/local/tmp/stubs/$flavor.apk" >/dev/null
  case "$flavor" in
    anydesk|teamviewer|rustdesk|screenhelper) ;;
    *) adb install -r "$apk" >/dev/null || echo "::warning::couldn't install stand-in $flavor" ;;
  esac
done

# A bigger log buffer, so the end-of-run logcat still holds the early tests' traces.
adb logcat -G 32M >/dev/null 2>&1 || true
adb logcat -c >/dev/null 2>&1 || true

# The traces and diagnostics, streamed to a file as they happen (the log buffer alone
# doesn't keep a whole run's worth).
adb logcat -v time -s TestRunner:I GuardianTrace:I GuardianDiag:I UiDump:I DeviceRule:W > "$OUT/logcat-diag.txt" 2>/dev/null &
diag_pid=$!

cd "$ROOT/android"
args=(connectedDebugAndroidTest --no-daemon --continue)
if [ "$KNOWN" != "true" ]; then
  args+=(-Pandroid.testInstrumentationRunnerArguments.notAnnotation=app.guardianmode.support.KnownBug)
fi
./gradlew "${args[@]}" || status=1
adb logcat -d -v time > "$OUT/logcat-instrumented.txt" 2>/dev/null || true
kill "$diag_pid" 2>/dev/null || true
mkdir -p "$OUT/instrumented"
cp -r app/build/outputs/androidTest-results/connected/. "$OUT/instrumented/" 2>/dev/null || true
# Each failing test's message, what the test traced (calls, app opens) and the one-line
# diagnostics DeviceRule logs on failure, as annotations: they can be read through the
# GitHub API without downloading logs or artifacts.
python3 "$ROOT/qa/daily/device_diagnostics.py" "$OUT/instrumented" "$OUT/logcat-diag.txt" "$(basename "$OUT")" || true

# Host-side checks: reboots, reinstalls, process death, Doze, manifest audit.
if [ "$HOST" = "true" ]; then
  adb logcat -c || true
  python3 "$ROOT/qa/daily/device_checks.py" --apk app/build/outputs/apk/debug/app-debug.apk --out "$OUT/host" || status=1
  adb logcat -d -v time > "$OUT/logcat-host.txt" 2>/dev/null || true
fi

exit $status
