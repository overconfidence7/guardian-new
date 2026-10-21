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


def wait_for_boot(timeout: int = 300) -> None:
    adb("wait-for-device", timeout=timeout)
    end = time.time() + timeout
    while time.time() < end:
        if sh("getprop sys.boot_completed").strip() == "1":
            time.sleep(5)
            return
        time.sleep(2)
    raise CheckFailed("emulator didn't finish booting")


def wake() -> None:
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")
    sh("input keyevent 82")


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
    return "ServiceRecord" in sh(f"dumpsys activity services {SERVICE}")


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


def pause_card_after_open(package: str = CHASE) -> bool:
    open_app(package)
    shown = pause_card_within(8)
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
    adb("reboot", ok=False)
    time.sleep(10)
    wait_for_boot()
    took = wait_for(service_running, 90)
    measurements["Reboot: boot to service running"] = f"{took:.0f} s" if took is not None else "never"
    check(took is not None, "GuardianService didn't come back within 90 s of boot")
    wake()


def pause_card_after_reboot(apk: str, **_) -> None:
    """GM-PAUSE-043 + GM-SVC-006 second half: card after a call, then reboot, then Chase."""
    fresh_app(apk)
    standard_call()
    dismiss_safe_word()
    adb("reboot", ok=False)
    time.sleep(10)
    wait_for_boot()
    wake()
    wait_for(service_running, 60)
    check(pause_card_after_open(), "no pause card for Chase after an unknown call and a reboot inside the 30-minute window")


def survives_update(apk: str, **_) -> None:
    """GM-SVC-007: reinstalling over the top keeps settings and restarts the service."""
    fresh_app(apk)
    check(wait_for(service_running, 30) is not None, "service never started")
    time.sleep(5)  # let the app finish writing its first-run defaults
    before = read_state()
    adb("install", "-r", apk, timeout=300)
    took = wait_for(service_running, 60)
    check(took is not None, "service didn't come back after the update (MY_PACKAGE_REPLACED)")
    after = read_state()
    check(after.get("onboardingDone") is True, "setup has to be redone after the update")
    lost = sorted(k for k in before if k not in after)
    check(not lost, f"settings lost across the update: {lost}")
    check(before.get("config") == after.get("config"), "the helper's settings changed across the update")
    check(PKG in sh("cmd role get-role-holders android.app.role.HOME"), "Guardian lost the home role after the update")


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
    """GM-PAUSE-042: Android kills Guardian in the background after the call."""
    fresh_app(apk)
    standard_call()
    dismiss_safe_word()
    open_app(NOTES)
    time.sleep(2)
    adb("root", ok=False)
    time.sleep(3)
    adb("wait-for-device")
    pid = sh(f"pidof {PKG}").strip()
    check(pid != "", "Guardian's process wasn't running to kill")
    sh(f"kill -9 {pid}")
    time.sleep(5)
    check(pause_card_after_open(), "no pause card for Chase after Android killed Guardian's process post-call")


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
            el = ET.SubElement(case, tag, message=str(e)[:500])
            el.text = traceback.format_exc()
            print(f"FAIL {name}: {e}")
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
