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

# Tests read the call log through the shell's `content query` (DeviceRule.callLogQuery). Android
# 10's shell doesn't request READ_CALL_LOG, so there DeviceRule reads it as root (su). The
# shell's errors go to stderr, which a test can't see, so they're shown here.
calllog_probe() { adb shell "$@" content query --uri content://call_log/calls --projection type 2>&1 | head -3 | tr '\n' ' '; }
probe="$(calllog_probe)"
case "$probe" in
  *Row:*|*"No result"*) ;;
  *)
    echo "::notice title=$(basename "$OUT") call log::the shell can't read the call log: ${probe:-<nothing>}; as root: $(calllog_probe su 0)"
    ;;
esac

# A bigger log buffer, so the end-of-run logcat still holds the early tests' traces.
adb logcat -G 32M >/dev/null 2>&1 || true
adb logcat -c >/dev/null 2>&1 || true

# The traces and diagnostics, streamed to a file as they happen (the log buffer alone
# doesn't keep a whole run's worth). ScamScenario carries each scenario's steps.
adb logcat -v time -s TestRunner:I GuardianTrace:I GuardianDiag:I UiDump:I DeviceRule:W ScamScenario:I > "$OUT/logcat-diag.txt" 2>/dev/null &
diag_pid=$!

cd "$ROOT/android"
args=(connectedDebugAndroidTest --no-daemon --continue)
if [ "$KNOWN" != "true" ]; then
  args+=(-Pandroid.testInstrumentationRunnerArguments.notAnnotation=app.guardianmode.support.KnownBug)
fi

# The instrumented suite takes about an hour. It runs under a watchdog, so a run that hangs
# ends with a failure that says where, and the host checks and the report still happen:
#  - the whole suite gets SUITE_LIMIT (a hang on 10-30, branch run 41079010624, sat until the
#    job's 3 h limit);
#  - no test may start or finish for STALL_LIMIT (each test also has a 15-minute limit of its
#    own inside DeviceRule; the longest takes about 5);
#  - while tests run, the emulator must answer adb. On 11-01 (daily run 41303524589, API 29)
#    it stopped answering during gm_scam_004, which turns the screen off during a call, and
#    the suite sat at the 110-minute limit. If it doesn't answer for 2 minutes, power is
#    pressed from the emulator console (which works while the system inside doesn't) and the
#    annotation says whether that brought it back: back means it had gone to sleep, not back
#    means it crashed. Still not back 5 minutes later, the suite is stopped.
SUITE_LIMIT=${SUITE_LIMIT:-$((110 * 60))}
STALL_LIMIT=${STALL_LIMIT:-$((20 * 60))}
POLL=${POLL:-30}
last_events() { grep -E 'TestRunner.*(started|finished):' "$OUT/logcat-diag.txt" 2>/dev/null | tail -2 | tr '\n' ' ' | cut -c1-600; }
last_lines() { tail -n 4 "$OUT/logcat-diag.txt" 2>/dev/null | cut -c1-300 | tr '\n' ' '; }
answers() { timeout 20 adb shell true >/dev/null 2>&1; }

setsid ./gradlew "${args[@]}" &
gradle_pid=$!
started=$(date +%s)
last_change=$started
last_count=0
silent=0
pressed=""
stopped=""
while kill -0 "$gradle_pid" 2>/dev/null; do
  sleep "$POLL"
  now=$(date +%s)
  count=$(grep -cE 'TestRunner.*(started|finished):' "$OUT/logcat-diag.txt" 2>/dev/null || true)
  count=${count:-0}
  if [ "$count" != "$last_count" ]; then last_count=$count; last_change=$now; fi
  if [ "$count" -gt 0 ]; then
    if answers; then
      if [ -n "$pressed" ]; then
        echo "::warning title=$(basename "$OUT") emulator asleep::the emulator stopped answering adb during the suite (${pressed}) and answered again after power was pressed from the emulator console, so it had gone to sleep rather than crashed. The suite went on."
        pressed=""
      fi
      silent=0
    else
      silent=$((silent + 1))
      if [ "$silent" -eq 4 ] && [ -z "$pressed" ]; then
        pressed="last test events: $(last_events); last lines: $(last_lines)"
        timeout 20 adb emu event send EV_KEY:KEY_POWER:1 EV_KEY:KEY_POWER:0 >/dev/null 2>&1 || true
      elif [ "$silent" -ge 14 ]; then
        stopped="the emulator stopped answering adb during the suite and didn't answer again within 5 minutes of power being pressed from the emulator console, so it crashed or hung rather than slept (${pressed:-last test events: $(last_events)})"
      fi
    fi
  fi
  if [ -z "$stopped" ] && [ $((now - last_change)) -ge "$STALL_LIMIT" ] && [ "$count" -gt 0 ]; then
    stopped="no test started or finished for $((STALL_LIMIT / 60)) minutes. Last test events: $(last_events). Last lines: $(last_lines)"
  fi
  if [ -z "$stopped" ] && [ $((now - started)) -ge "$SUITE_LIMIT" ]; then
    stopped="the instrumented suite was still running after $((SUITE_LIMIT / 60)) minutes. Last test events: $(last_events)"
  fi
  if [ -n "$stopped" ]; then
    kill -TERM -- "-$gradle_pid" 2>/dev/null || kill -TERM "$gradle_pid" 2>/dev/null
    for _ in 1 2 3 4 5 6; do kill -0 "$gradle_pid" 2>/dev/null || break; sleep 5; done
    kill -KILL -- "-$gradle_pid" 2>/dev/null || true
    # The tests keep going inside the emulator after Gradle is gone.
    timeout 20 adb shell am force-stop app.guardianmode.debug >/dev/null 2>&1 || true
    adb_state="$(timeout 20 adb devices 2>&1 | tail -n +2 | tr '\n' ' ')"
    echo "::error title=$(basename "$OUT") instrumented::stopped the instrumented suite: ${stopped}. adb devices: ${adb_state:-no answer}"
    break
  fi
done
wait "$gradle_pid"
rc=$?
[ -n "$stopped" ] && rc=124
[ $rc -eq 0 ] || status=1
timeout 120 adb logcat -d -v time > "$OUT/logcat-instrumented.txt" 2>/dev/null || true
kill "$diag_pid" 2>/dev/null || true
mkdir -p "$OUT/instrumented"
cp -r app/build/outputs/androidTest-results/connected/. "$OUT/instrumented/" 2>/dev/null || true
# Each failing test's message, what the test traced (calls, app opens) and the one-line
# diagnostics DeviceRule logs on failure, as annotations: they can be read through the
# GitHub API without downloading logs or artifacts.
python3 "$ROOT/qa/daily/device_diagnostics.py" "$OUT/instrumented" "$OUT/logcat-diag.txt" "$(basename "$OUT")" || true

# Host-side checks: reboots, reinstalls, process death, Doze, manifest audit.
if [ "$HOST" = "true" ]; then
  # Every adb call here has a time limit: after a hang the emulator or adb itself may not
  # answer, and an adb call without one (such as this logcat -c used to be) is the likeliest
  # reason the 10-31 API 29 run went on to the 3 h limit after the suite was stopped.
  timeout 30 adb logcat -c || true
  timeout 45m python3 "$ROOT/qa/daily/device_checks.py" --apk app/build/outputs/apk/debug/app-debug.apk --out "$OUT/host" || status=1
  timeout 120 adb logcat -d -v time > "$OUT/logcat-host.txt" 2>/dev/null || true
fi

# An emulator that didn't come back from a reboot (gone from adb, or there but not
# answering) kept the 10-30 and 10-31 API 36 branch runs going until the 3 h limit after
# every check had finished and posted its summary (most likely the action's shutdown, which
# talks to the emulator, waited on it). Stop it, and adb, here so the job ends.
if ! timeout 30 adb shell true >/dev/null 2>&1; then
  echo "::warning title=$(basename "$OUT") emulator::the emulator stopped answering adb by the end of the run; stopping it"
  timeout 20 adb emu kill >/dev/null 2>&1 || true
  sleep 5
  pkill -9 -f qemu-system >/dev/null 2>&1 || true
  # A wedged adb server would hang the action's own `adb emu kill` too.
  timeout 10 adb kill-server >/dev/null 2>&1 || pkill -9 -x adb >/dev/null 2>&1 || true
fi

exit $status
