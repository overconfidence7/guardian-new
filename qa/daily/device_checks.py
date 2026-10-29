#!/usr/bin/env python3
"""
Host-side checks for the daily device run: the cases that need adb from outside the
emulator (reboots, reinstalls, killing the app, Doze) and the manifest audit, which
reads the built APK. Writes JUnit XML so qa/daily/report.py can fold the results in
with every other suite.

  python3 qa/daily/device_checks.py --apk app-debug.apk --stubs stub-apks/ --out results/

Runs against whatever emulator `adb` sees. Each check is independent: a failure is
recorded and the next check starts from a fresh install of the app.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "app.guardianmode.debug"
SERVICE = f"{PKG}/app.guardianmode.protection.GuardianService"
UNKNOWN = "5559998888"
CHASE = "com.chase.sig.android"
NOTES = "org.guardianqa.notes"
REPO = Path(__file__).resolve().parents[2]

measurements: dict[str, str] = {}


class CheckFailed(AssertionError):
    pass


def check(cond: bool, message: str) -> None:
    if not cond:
        raise CheckFailed(message)


# ---------------------------------------------------------------- adb helpers

def adb(*args: str, timeout: int = 120, ok: bool = True) -> str:
    res = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if ok and res.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {res.stderr.strip() or res.stdout.strip()}")
    return res.stdout


def sh(command: str, timeout: int = 60) -> str:
    return adb("shell", command, timeout=timeout, ok=False)


def wait_for_boot(timeout: int = 300) -> float:
    """Waits for the boot to finish, then 5 s more. Returns the host time at which
    sys.boot_completed was first seen, so timings can start from the boot itself."""
    adb("wait-for-device", timeout=timeout)
    end = time.time() + timeout
    while time.time() < end:
        if sh("getprop sys.boot_completed").strip() == "1":
            completed = time.time()
            time.sleep(5)
            return completed
        time.sleep(2)
    raise CheckFailed("emulator didn't finish booting")


def boot_id() -> str:
    """Changes on every boot of the phone."""
    return sh("cat /proc/sys/kernel/random/boot_id").strip()


def reboot() -> float:
    """Reboots the phone and waits for the boot to finish. Fails if the phone didn't really
    reboot: `adb reboot` can return without rebooting, and a check that then finds Guardian
    running would pass without testing anything. Returns when boot_completed was seen."""
    before = boot_id()
    if sdk() < 30:
        # Android 10 writes package state (an app's stopped flag, the preferred home app)
        # about 10 s after it changes and drops a pending write on shutdown; Android 11 added
        # the flush. Rebooting sooner after fresh_app boots into a Guardian that is stopped
        # again (so it never gets BOOT_COMPLETED) behind the home chooser: BOOT-API29 on
        # 2026-10-27. A phone isn't set up and restarted inside 10 s, so wait it out.
        time.sleep(15)
    adb("reboot", ok=False)
    time.sleep(10)
    completed = wait_for_boot()
    after = boot_id()
    check(after != "" and after != before, f"the phone didn't reboot (boot id {before!r} before, {after!r} after)")
    return completed


def sdk() -> int:
    return int(sh("getprop ro.build.version.sdk").strip() or "0")


def app_stopped() -> bool:
    """Android's stopped flag for Guardian: set by install and force-stop, cleared by the
    first launch. A stopped app gets no broadcasts, BOOT_COMPLETED included."""
    return "stopped=true" in sh(f"dumpsys package {PKG}")


def keyguard_showing() -> bool:
    """True while the lock screen is up, as the window policy (API 29) or the keyguard controller (API 30+) reports it."""
    out = sh("dumpsys window policy") + sh("dumpsys activity activities")
    return bool(re.search(r"(mKeyguardShowing|isKeyguardShowing|mShowingLockscreen)=true|KeyguardServiceDelegate\s+showing=true", out))


def wake() -> None:
    """Screen on and past the lock screen. Right after a boot one dismiss can be dropped (the
    same thing DeviceRule.wake retries for), and an app opened then stays behind the lock
    screen, so this tries until the lock screen is gone."""
    sh("input keyevent KEYCODE_WAKEUP")
    for _ in range(10):
        sh("wm dismiss-keyguard")
        sh("input keyevent 82")
        time.sleep(1)
        if not keyguard_showing():
            return
    print("  wake: the lock screen is still showing after 10 tries")


def home_holders() -> str:
    """Who holds the home role. Android 10's `cmd role` has no get-role-holders, so there the
    phone's resolved home activity (what the Home button opens) is reported instead."""
    out = sh("cmd role get-role-holders android.app.role.HOME").strip()
    if "Unknown command" not in out:
        return out
    resolved = sh("cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN")
    m = re.search(r"packageName=(\S+)", resolved)
    return m.group(1) if m else f"? ({resolved.strip()[:120]})"


def top_activity() -> str:
    """The resumed activity's component, as the activity manager reports it."""
    out = sh("dumpsys activity activities")
    m = re.search(r"(?:topResumedActivity|mResumedActivity|ResumedActivity)[:=]\s*ActivityRecord\{\S+ \S+ (\S+)", out)
    return m.group(1) if m else "?"


def saved_call_end() -> str:
    """When the saved state says the last unknown call ended, or why that can't be told."""
    out = adb("exec-out", "run-as", PKG, "cat", "files/datastore/guardian_state.json", ok=False)
    try:
        state = json.loads(out)
    except json.JSONDecodeError:
        # run-as can fail for a while after boot; that is not the same as nothing saved.
        return f"state unreadable ({out.strip()[:80]!r})"
    ended = state.get("lastUnknownCallEndedAt")
    now_ms = int(sh("date +%s").strip() or "0") * 1000
    if not isinstance(ended, int):
        return f"not saved ({len(state)} keys)"
    return f"{(now_ms - ended) // 1000}s ago" + (" paused" if state.get("pausedUntil", 0) > now_ms else "")


def guardian_pid() -> str:
    return sh(f"pidof {PKG}").strip()


def card_activity_up() -> bool:
    """A card activity exists, whether or not a UI dump could read it (cards are FLAG_SECURE)."""
    return "GuardianCardActivity" in sh("dumpsys activity activities")


def last_exit() -> str:
    """Why Guardian's last process ended, as Android recorded it (API 30+)."""
    out = sh(f"dumpsys activity exit-info {PKG}")
    first = out.split("ApplicationExitInfo #1")[0]
    fields = []
    for key in ("timestamp", "pid", "reason", "description"):
        m = re.search(rf"\b{key}=(.*?)(?= \w+=|$)", first, re.M)
        if m:
            fields.append(f"{key}={m.group(1).strip()[:120]}")
    return " ".join(fields) or "none recorded"


def guardian_log(limit: int = 8) -> str:
    """The system's lines about Guardian's process and its activity starts since boot: deaths,
    crashes, ANRs and blocked background starts."""
    out = sh("logcat -d -v time -b main,system,crash", timeout=60)
    pattern = re.compile(
        rf"({re.escape(PKG)}.*(Kill|died|ANR|crash|FATAL|[Bb]ackground activity|BAL|Abort|Start proc|Restart))"
        rf"|(GuardianCardActivity)|(AndroidRuntime.*{re.escape(PKG)})|(FATAL EXCEPTION)"
    )
    lines = [line.strip()[:200] for line in out.splitlines() if pattern.search(line)]
    return " | ".join(lines[-limit:]) or "nothing"


def diagnosis() -> str:
    """What the phone and Guardian look like right now, for a failure message (kept short:
    an annotation holds 4 KB)."""
    return (
        f"top={top_activity().split('/')[-1]} keyguard={keyguard_showing()} service={service_running()} "
        f"callEnded={saved_call_end()}"
    )


def grant_everything() -> None:
    for c in [
        f"pm grant {PKG} android.permission.READ_PHONE_STATE",
        f"pm grant {PKG} android.permission.CALL_PHONE",
        f"pm grant {PKG} android.permission.READ_CONTACTS",
        f"pm grant {PKG} android.permission.POST_NOTIFICATIONS",
        f"appops set {PKG} GET_USAGE_STATS allow",
        f"appops set {PKG} SYSTEM_ALERT_WINDOW allow",
        f"appops set {PKG} SCHEDULE_EXACT_ALARM allow",
        f"appops set {PKG} USE_FULL_SCREEN_INTENT allow",
        f"cmd role add-role-holder android.app.role.HOME {PKG}",
        f"cmd role add-role-holder android.app.role.CALL_SCREENING {PKG}",
        f"dumpsys deviceidle whitelist +{PKG}",
    ]:
        sh(c)


def write_state(state: dict) -> None:
    """The runbook's state injection: force-stop, write the DataStore file with run-as."""
    sh(f"am force-stop {PKG}")
    tmp = Path("gm-state.json")
    tmp.write_text(json.dumps(state), encoding="utf-8")
    adb("push", str(tmp), "/data/local/tmp/gm-state.json")
    tmp.unlink()
    sh(f"run-as {PKG} mkdir -p files/datastore")
    sh(f"run-as {PKG} sh -c 'cat /data/local/tmp/gm-state.json > files/datastore/guardian_state.json'")
    sh("rm /data/local/tmp/gm-state.json")


def read_state() -> dict:
    out = adb("exec-out", "run-as", PKG, "cat", "files/datastore/guardian_state.json", ok=False)
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        return {}


def fresh_app(apk: str) -> None:
    """Clean install, every permission, setup finished, Guardian on the home screen."""
    sh(f"pm uninstall {PKG}")
    adb("install", "-r", "-g", apk, timeout=300)
    grant_everything()
    write_state({"onboardingDone": True})
    wake()
    sh("input keyevent KEYCODE_HOME")
    time.sleep(3)


def service_running() -> bool:
    """The service exists and has a process. A sticky service whose process was killed keeps
    its ServiceRecord while Android waits to restart it, listed with app=null (and under
    "Restarting services"), so a record alone doesn't mean it runs."""
    out = sh(f"dumpsys activity services {SERVICE}")
    return "ServiceRecord" in out and "app=null" not in out and "Restarting ServiceRecord" not in out


def wait_for(predicate, timeout: float, interval: float = 1.0) -> float | None:
    """Seconds until predicate() is true, or None."""
    start = time.time()
    while time.time() - start < timeout:
        if predicate():
            return time.time() - start
        time.sleep(interval)
    return None


def ui_dump() -> str:
    sh("uiautomator dump /sdcard/gm-ui.xml", timeout=30)
    return adb("exec-out", "cat", "/sdcard/gm-ui.xml", ok=False)


def find_text(text: str) -> tuple[int, int] | None:
    xml = ui_dump()
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if m.group(1).replace("&apos;", "'").replace("&quot;", '"').replace("&amp;", "&") == text:
            x1, y1, x2, y2 = map(int, m.group(2, 3, 4, 5))
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def wait_text(text: str, timeout: float = 10) -> bool:
    return wait_for(lambda: find_text(text) is not None, timeout, interval=0.5) is not None


def tap_text(text: str) -> bool:
    pos = find_text(text)
    if pos:
        sh(f"input tap {pos[0]} {pos[1]}")
    return pos is not None


def call_state() -> int:
    m = re.search(r"mCallState=(\d)", sh("dumpsys telephony.registry"))
    return int(m.group(1)) if m else -1


def standard_call(talk_seconds: int = 35) -> None:
    """The runbook's standard unknown call: ring, answer, talk, hang up."""
    adb("emu", "gsm", "call", UNKNOWN)
    check(wait_for(lambda: call_state() == 1, 15) is not None, "the fake call never rang")
    time.sleep(2)
    adb("emu", "gsm", "accept", UNKNOWN, ok=False)
    if wait_for(lambda: call_state() == 2, 3) is None:
        sh("input keyevent KEYCODE_CALL")
    check(wait_for(lambda: call_state() == 2, 10) is not None, "couldn't answer the fake call")
    time.sleep(talk_seconds)
    adb("emu", "gsm", "cancel", UNKNOWN, ok=False)
    wait_for(lambda: call_state() == 0, 10)
    time.sleep(2)


def dismiss_safe_word() -> None:
    if wait_text("Everything's fine", 6):
        tap_text("Everything's fine")
        time.sleep(1)


def open_app(package: str) -> None:
    sh(f"monkey -p {package} -c android.intent.category.LAUNCHER 1")


def pause_card_within(seconds: float = 5) -> bool:
    return wait_text("Hold on a minute.", seconds)


# What the last pause_card_after_open saw on each try, for a failure message: its prints
# only reach the job log, which can't be read through the API.
open_notes: list[str] = []


def pause_card_after_open(package: str = CHASE) -> bool:
    """Opens the app and waits for the card. An app that never came to the front (the lock
    screen still up, a start dropped while the phone is busy) is opened again after a wake,
    since no card can be expected for an app that wasn't opened."""
    shown = False
    open_notes.clear()
    for attempt in range(2):
        open_app(package)
        shown = pause_card_within(8)
        if shown:
            break
        top = top_activity()
        note = f"try {attempt + 1}: top={top.split('/')[-1]} pid={guardian_pid() or 'none'} cardActivity={card_activity_up()}"
        open_notes.append(note)
        if package in top:
            print(f"  {package} is in front and no card came: {diagnosis()}")
            break
        print(f"  {package} didn't come to the front (try {attempt + 1}): {diagnosis()}")
        wake()
    sh("input keyevent KEYCODE_HOME")
    return shown


# ---------------------------------------------------------------- checks

def manifest_audit(apk: str, **_) -> None:
    """GM-BASE-009: no banned permissions or components, only the expected exports."""
    analyzer = shutil.which("apkanalyzer") or next(
        (str(p) for p in Path(os.environ.get("ANDROID_HOME", "/usr/local/lib/android/sdk")).glob("cmdline-tools/*/bin/apkanalyzer")),
        None,
    )
    check(analyzer is not None, "apkanalyzer not found")
    manifest = subprocess.run([analyzer, "manifest", "print", apk], capture_output=True, text=True, check=True).stdout
    root = ET.fromstring(manifest)
    a = "{http://schemas.android.com/apk/res/android}"
    perms = {p.get(f"{a}name") for p in root.iter("uses-permission")}
    banned = {
        "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_CALL_LOG", "WRITE_CALL_LOG", "PROCESS_OUTGOING_CALLS",
        "QUERY_ALL_PACKAGES", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "RECORD_AUDIO",
        "BIND_ACCESSIBILITY_SERVICE", "READ_MEDIA_IMAGES", "READ_EXTERNAL_STORAGE", "USE_EXACT_ALARM",
    }
    problems = [f"banned permission {p}" for p in sorted(perms) if p and p.split(".")[-1] in banned]
    app = root.find("application")
    check(app is not None, "no <application> in the manifest")
    if app.get(f"{a}allowBackup") != "false":
        problems.append("allowBackup is not false")
    for md in root.iter("meta-data"):
        if "isMonitoringTool" in (md.get(f"{a}name") or ""):
            problems.append("isMonitoringTool meta-data is declared")
    for svc in app.iter("service"):
        for action in svc.iter("action"):
            if action.get(f"{a}name") == "android.accessibilityservice.AccessibilityService":
                problems.append(f"declares an accessibility service: {svc.get(f'{a}name')}")
    allowed_exports = {
        "app.guardianmode.ui.MainActivity", "app.guardianmode.ui.home.SharePhotosActivity",
        "app.guardianmode.protection.GuardianCallScreeningService",
        "app.guardianmode.protection.PhoneStateReceiver", "app.guardianmode.protection.BootReceiver",
    }
    guard_needed = {
        "app.guardianmode.protection.GuardianCallScreeningService": "android.permission.BIND_SCREENING_SERVICE",
        "app.guardianmode.protection.PhoneStateReceiver": "android.permission.READ_PHONE_STATE",
    }
    library_exports = []
    for kind in ("activity", "activity-alias", "service", "receiver", "provider"):
        for c in app.iter(kind):
            name = c.get(f"{a}name") or ""
            exported = c.get(f"{a}exported") == "true"
            if not exported:
                continue
            if name.startswith("app.guardianmode."):
                if name not in allowed_exports:
                    problems.append(f"unexpected exported {kind} {name}")
                if name in guard_needed and c.get(f"{a}permission") != guard_needed[name]:
                    problems.append(f"{name} isn't guarded by {guard_needed[name]}")
            elif not c.get(f"{a}permission"):
                library_exports.append(f"{kind} {name}")
    policy = (REPO / "docs/POLICY.md").read_text(encoding="utf-8")
    unexplained = sorted(p for p in perms if p and p.startswith("android.permission.") and p.split(".")[-1] not in policy)
    measurements["Permissions not named in docs/POLICY.md"] = ", ".join(unexplained) or "none"
    measurements["Unguarded exported library components"] = ", ".join(library_exports) or "none"
    check(not problems, "; ".join(problems))


def apk_size(apk: str, **_) -> None:
    """GM-PERF-008: record the test APK's size; flag it past 150 MB."""
    mb = Path(apk).stat().st_size / 1_048_576
    measurements["Debug APK size"] = f"{mb:.1f} MB"
    check(mb < 150, f"debug APK is {mb:.1f} MB")


def service_starts(apk: str, **_) -> None:
    """GM-SVC-001 (host side): the protection service is running after setup."""
    fresh_app(apk)
    check(wait_for(service_running, 30) is not None, "GuardianService isn't running after setup")


def survives_reboot(apk: str, **_) -> None:
    """GM-SVC-006: the service is back after a reboot without opening anything."""
    fresh_app(apk)
    check(wait_for(service_running, 30) is not None, "service never started before the reboot")
    completed = reboot()
    took = wait_for(service_running, 90)
    # Counted from boot_completed, not from after reboot()'s 5 s settle.
    since_boot = time.time() - completed if took is not None else None
    measurements["Reboot: boot to service running"] = f"{since_boot:.0f} s" if since_boot is not None else "never"
    check(took is not None, f"GuardianService didn't come back within 90 s of boot ({diagnosis()} stopped={app_stopped()} home={home_holders()})")
    wake()


def broadcasts_idle(timeout: int = 120) -> None:
    """Waits until Android has delivered every queued broadcast. Right after a boot the queue
    still holds BOOT_COMPLETED for every app, and Guardian hears about calls through a
    PHONE_STATE receiver on that same queue, so a call placed then reaches it many seconds
    late. Android 10 may not have the command; then this returns at once."""
    try:
        sh("am wait-for-broadcast-idle", timeout=timeout)
    except subprocess.TimeoutExpired:
        print(f"  broadcasts still queued after {timeout} s")


def pause_card_after_reboot(apk: str, **_) -> None:
    """GM-PAUSE-043 + GM-SVC-006 second half: card after a call, then reboot, then Chase.

    This runs right after gm_svc_006_survivesReboot's reboot. On the 2026-10-25 and
    2026-10-26 API 36 runs (and 2026-10-24 d7804f3) the call's end was "not saved" when the
    phone was rebooted 2-8 s after hanging up: the PHONE_STATE broadcasts were still behind
    the boot broadcasts, so the reboot came before Guardian heard about the call at all. It
    passed whenever the previous check had spent longer after its boot. So the call waits for
    the broadcast queue, and the reboot waits until the end is saved, as it is within
    moments on a settled phone (gm_pause_042 reads the same saved end)."""
    fresh_app(apk)
    wait_for(service_running, 30)
    broadcasts_idle()
    standard_call()
    dismiss_safe_word()
    broadcasts_idle(60)
    took = wait_for(lambda: saved_call_end().endswith(("ago", "paused")), 30)
    saved = saved_call_end()
    measurements["Reboot check: hang-up to call end saved"] = f"{took:.0f} s" if took is not None else "never"
    check(took is not None, f"Guardian never saved the unknown call's end (30 s after hang-up and the broadcast queue idle): {saved}; {diagnosis()}")
    reboot()
    wake()
    wait_for(service_running, 60)
    before = f"{diagnosis()} pid={guardian_pid() or 'none'}"
    shown = pause_card_after_open()
    if not shown:
        raise CheckFailed(
            f"no pause card for Chase after an unknown call and a reboot inside the 30-minute window (call end before reboot: {saved}; "
            f"after boot: {before}; opening Chase: {'; '.join(open_notes)}; now: {diagnosis()} pid={guardian_pid() or 'none'}; "
            f"last exit: {last_exit()}; log: {guardian_log()})"
        )


def survives_update(apk: str, **_) -> None:
    """GM-SVC-007: reinstalling over the top keeps settings and restarts the service."""
    fresh_app(apk)
    check(wait_for(service_running, 30) is not None, "service never started")
    time.sleep(5)  # let the app finish writing its first-run defaults
    before = read_state()
    home_before = home_holders()
    adb("install", "-r", apk, timeout=300)
    took = wait_for(service_running, 60)
    check(took is not None, "service didn't come back after the update (MY_PACKAGE_REPLACED)")
    after = read_state()
    check(after.get("onboardingDone") is True, "setup has to be redone after the update")
    lost = sorted(k for k in before if k not in after)
    check(not lost, f"settings lost across the update: {lost}")
    check(before.get("config") == after.get("config"), "the helper's settings changed across the update")
    # The role controller re-checks roles after a package changes, on its own time: give it a moment.
    kept = wait_for(lambda: PKG in home_holders(), 30)
    check(kept is not None, f"Guardian lost the home role after the update (home before: {home_before!r}; 30 s after: {home_holders()!r})")


def force_stop_then_home(apk: str, **_) -> None:
    """GM-SVC-008: after a force stop, pressing Home brings protection back."""
    fresh_app(apk)
    check(wait_for(service_running, 30) is not None, "service never started")
    pid = sh(f"pidof {PKG}").strip()
    sh(f"am force-stop {PKG}")
    # Guardian is the home app, so Android may relaunch it on its own right away. What
    # matters is that the old process died and protection is back after Home.
    time.sleep(2)
    check(sh(f"pidof {PKG}").strip() != pid, "force-stop didn't stop Guardian")
    sh("input keyevent KEYCODE_HOME")
    took = wait_for(service_running, 60)
    measurements["Force stop: Home to service running"] = f"{took:.0f} s" if took is not None else "never"
    check(took is not None, "pressing Home didn't bring the service back")


def killed_after_call(apk: str, **_) -> None:
    """GM-PAUSE-042: Android kills Guardian in the background after the call.

    What this tests is that the call's end survives the process dying. So, like
    gm_pause_043, it waits for the broadcast queue before the call and for the saved end
    before the kill (it runs right after the Doze check's call, and a PHONE_STATE broadcast
    can still be queued then), makes sure the kill happened, and waits for Android to
    restart the service before opening Chase. Each step that doesn't happen fails saying
    which, with what the phone looked like."""
    fresh_app(apk)
    wait_for(service_running, 30)
    broadcasts_idle()
    standard_call()
    dismiss_safe_word()
    broadcasts_idle(60)
    saved_in = wait_for(lambda: saved_call_end().endswith(("ago", "paused")), 30)
    saved = saved_call_end()
    check(saved_in is not None, f"Guardian never saved the unknown call's end (30 s after hang-up and the broadcast queue idle): {saved}; {diagnosis()}")
    open_app(NOTES)
    time.sleep(2)
    adb("root", ok=False)
    time.sleep(3)
    adb("wait-for-device")
    pid = sh(f"pidof {PKG}").strip()
    check(pid != "", "Guardian's process wasn't running to kill")
    out = sh(f"kill -9 {pid}").strip()
    check(wait_for(lambda: sh(f"pidof {PKG}").strip() != pid, 10) is not None, f"kill -9 {pid} didn't end Guardian's process ({out or 'no output'})")
    back = wait_for(service_running, 60)
    measurements["Process killed: kill to service running"] = f"{back:.0f} s" if back is not None else "never"
    check(back is not None, f"Android didn't restart GuardianService within 60 s of the kill ({diagnosis()})")
    after = diagnosis()
    check(pause_card_after_open(), f"no pause card for Chase after Android killed Guardian's process post-call (call end before kill: {saved}; service back after {back:.0f} s: {after}; now: {diagnosis()})")


def doze_call(apk: str, **_) -> None:
    """GM-SVC-009: an unknown call while the phone dozes still arms the tripwires."""
    fresh_app(apk)
    sh("input keyevent KEYCODE_SLEEP")
    time.sleep(2)
    sh("dumpsys deviceidle force-idle")
    standard_call()
    sh("dumpsys deviceidle unforce")
    wake()
    dismiss_safe_word()
    check(pause_card_after_open(), "no pause card after an unknown call taken in Doze")


def memory(apk: str, **_) -> None:
    """GM-PERF-007: PSS on the home screen under 150 MB."""
    fresh_app(apk)
    time.sleep(5)
    out = sh(f"dumpsys meminfo {PKG}")
    m = re.search(r"TOTAL PSS:\s+(\d+)", out) or re.search(r"^\s*TOTAL\s+(\d+)", out, re.M)
    check(m is not None, "couldn't read PSS from dumpsys meminfo")
    mb = int(m.group(1)) / 1024
    measurements["Home screen PSS"] = f"{mb:.0f} MB"
    check(mb < 150, f"home screen PSS is {mb:.0f} MB (bar: 150 MB)")


CHECKS = [
    ("gm_base_009_manifestAudit", manifest_audit, False),
    ("gm_perf_008_apkSize", apk_size, False),
    ("gm_svc_001_serviceRunsAfterSetup", service_starts, True),
    ("gm_perf_007_homeScreenMemory", memory, True),
    ("gm_svc_007_survivesUpdate", survives_update, True),
    ("gm_svc_008_forceStopThenHome", force_stop_then_home, True),
    ("gm_svc_009_unknownCallDuringDoze", doze_call, True),
    ("gm_pause_042_processKilledAfterCall", killed_after_call, True),
    ("gm_svc_006_survivesReboot", survives_reboot, True),
    ("gm_pause_043_gm_svc_006_pauseCardAfterReboot", pause_card_after_reboot, True),
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--suite", default="device-host")
    ap.add_argument("--no-device", action="store_true", help="only the checks that read the APK")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    suite = ET.Element("testsuite", name=args.suite)
    failures = 0
    for name, fn, needs_device in CHECKS:
        if needs_device and args.no_device:
            continue
        case = ET.SubElement(suite, "testcase", classname="DeviceChecks", name=name)
        start = time.time()
        try:
            fn(apk=args.apk)
            print(f"PASS {name}")
        except Exception as e:  # noqa: BLE001 - every check is isolated
            failures += 1
            tag = "failure" if isinstance(e, CheckFailed) else "error"
            el = ET.SubElement(case, tag, message=str(e)[:1500])
            el.text = traceback.format_exc()
            print(f"FAIL {name}: {e}")
            # The report keeps 240 characters of the message and the job log can't be read
            # through the API, so the whole message goes out as an annotation too.
            text = str(e)[:3500].replace("%", "%25").replace("\r", "").replace("\n", "%0A")
            print(f"::error title={args.suite} {name}::{text}")
        case.set("time", f"{time.time() - start:.1f}")
    suite.set("tests", str(len(suite)))
    suite.set("failures", str(failures))
    ET.ElementTree(suite).write(out / f"TEST-{args.suite}.xml", encoding="utf-8", xml_declaration=True)
    (out / f"measurements-{args.suite}.json").write_text(json.dumps(measurements, indent=2), encoding="utf-8")
    if not args.no_device:
        sh(f"pm uninstall {PKG}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
