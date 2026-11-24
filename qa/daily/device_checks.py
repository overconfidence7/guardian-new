#!/usr/bin/env python3
"""
Host-side checks for the daily device run: the cases that need adb from outside the
emulator (reboots, reinstalls, killing the app, Doze) and the manifest audit, which
reads the built APK. Writes JUnit XML so qa/daily/report.py can fold the results in
with every other suite.

  python3 qa/daily/device_checks.py --apk app-debug.apk --shrunk-apk app-benchmark.apk --out results/

Runs against whatever emulator `adb` sees. Each check is independent: a failure is
recorded and the next check starts from a fresh install of the app. Every check installs
the debug APK except the memory budget (GM-PERF-007), which measures the shrunk
`benchmark` build (release's R8 and resource shrinking under debug's package name).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import signal
import struct
import subprocess
import sys
import time
import traceback
import urllib.request
import xml.etree.ElementTree as ET
import zlib
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


class CheckSkipped(Exception):
    """The check can't apply on this Android level (recorded as skipped, with the reason)."""


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
    # The phone's log across the reboot, so an emulator that dies on it shows how far the
    # shutdown got (and whether it began at all).
    global reboot_log
    reboot_log = Path("reboot-logcat.txt")
    capture = subprocess.Popen(["adb", "logcat", "-v", "time", "-T", "1"], stdout=reboot_log.open("w"), stderr=subprocess.STDOUT)
    adb("reboot", ok=False)
    time.sleep(10)
    try:
        completed = wait_for_boot(240)
    except subprocess.TimeoutExpired:
        # The phone never came back to adb. On the CI emulator that is the emulator process
        # dying on the reboot (gone from `adb devices`: the 10-30 and both 10-31 API 36 branch
        # runs, and 11-01's daily run), not Guardian, so start it again: a cold boot is still a
        # boot of the phone.
        relaunch_emulator("didn't come back from a reboot")
        completed = wait_for_boot(600)
    finally:
        capture.kill()
    after = boot_id()
    check(after != "" and after != before, f"the phone didn't reboot (boot id {before!r} before, {after!r} after)")
    return completed


# The emulator's AVD name, read before any check so a lost emulator can be started again.
avd_name = ""
# Set once the emulator couldn't be started again: every later check is skipped with this reason.
emulator_gone = ""
reboot_log: Path | None = None
EMULATOR_OPTIONS = ["-no-window", "-gpu", "swiftshader_indirect", "-noaudio", "-no-boot-anim", "-camera-back", "none"]  # as android-emulator.yml


def emulator_binary() -> str:
    sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    return str(Path(sdk_root) / "emulator" / "emulator") if sdk_root else (shutil.which("emulator") or "emulator")


def run_quietly(cmd: list[str] | str, timeout: int = 30) -> str:
    """A command's output, or "" when it fails or doesn't finish in time."""
    try:
        res = subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True, timeout=timeout)
        return res.stdout.strip()
    except (subprocess.TimeoutExpired, OSError):
        return ""


def find_avd_name() -> str:
    """The running emulator's AVD name. android-emulator.yml names the AVD and passes the name
    in GUARDIAN_AVD; failing that, the emulator console (`adb emu avd name`, which printed
    nothing on 11-01's API 36 run, so the emulator lost on a reboot couldn't be started
    again), then the only AVD `emulator -list-avds` knows."""
    name = os.environ.get("GUARDIAN_AVD", "").strip()
    if name:
        print(f"emulator AVD: {name} (from the workflow)")
        return name
    said = run_quietly(["adb", "emu", "avd", "name"])
    name = next((line.strip() for line in said.splitlines() if line.strip() and line.strip() not in ("OK",) and not line.startswith("KO")), "")
    if name:
        print(f"emulator AVD: {name} (from the emulator console)")
        return name
    avds = [line.strip() for line in run_quietly([emulator_binary(), "-list-avds"]).splitlines() if line.strip() and " " not in line.strip()]
    if len(avds) == 1:
        print(f"emulator AVD: {avds[0]} (the only one emulator -list-avds knows)")
        return avds[0]
    print(f"emulator AVD: unknown (console said {said[:120]!r}; -list-avds said {avds})")
    return ""


def host_diagnostics() -> str:
    """What the CI machine and the emulator looked like: memory, disk, the kernel's messages
    about killed or crashed processes (and whether its log could be read at all), emulator
    processes and crash reports, the emulator's version, and the phone's last log lines
    before a reboot."""
    def run(cmd: str) -> str:
        try:
            return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=20).stdout.strip()
        except subprocess.TimeoutExpired:
            return "timed out"
    kernel_lines = run("sudo -n dmesg 2>/dev/null | wc -l")
    kernel = run('sudo -n dmesg 2>/dev/null | grep -iE "out of memory|killed process|segfault|traps:|general protection|qemu|emulator" | tail -3')
    crashes = run("find /tmp ~/.android -maxdepth 4 \\( -name '*.dmp' -o -name 'emu-crash*' \\) -mmin -240 2>/dev/null | head -4 | tr '\\n' ' '")
    phone_log = ""
    if reboot_log is not None and reboot_log.exists():
        lines = [line for line in reboot_log.read_text(errors="replace").splitlines() if line.strip()]
        phone_log = " / ".join(line[:160] for line in lines[-5:])
    return (f"mem: {run('free -m | sed -n 2p')}; disk: {run('df -h . | tail -1')}; "
            f"kernel ({kernel_lines or '?'} lines readable): {kernel or 'nothing about it'}; "
            f"emulator processes: {run('ps -eo pid,rss,args | grep [q]emu-system | cut -c1-120') or 'none'}; "
            f"crash reports: {crashes or 'none'}; emulator: {run(f'{emulator_binary()} -version 2>/dev/null | head -1') or '?'}; "
            f"adb: {run('adb devices | tail -n +2 | tr -s \"\\n\" \" \"')}; "
            f"phone's last log lines before the reboot: {phone_log or 'none'}")


def relaunch_emulator(why: str) -> None:
    """Starts the emulator again after it died or stopped answering, cold (no snapshot),
    with the options the workflow starts it with. Says so in an annotation and the
    measurements, with what the machine looked like, so a lost emulator is never silent."""
    diag = host_diagnostics()
    print(f"::warning title=device-host emulator::the emulator {why}; starting it again. {diag[:3000]}")
    measurements[f"Emulator {why}, started again"] = diag[:900]
    check(avd_name != "", f"the emulator {why} and its AVD name wasn't known, so it can't be started again ({diag})")
    subprocess.run(["pkill", "-9", "-f", "qemu-system"], capture_output=True)
    time.sleep(3)
    subprocess.run(["adb", "kill-server"], capture_output=True, timeout=20)
    log = open("emulator-relaunch.log", "w")  # noqa: SIM115 - the emulator outlives this function
    subprocess.Popen([emulator_binary(), "-avd", avd_name, "-port", "5554", *EMULATOR_OPTIONS, "-no-snapshot-load", "-no-snapshot-save"],
                     stdout=log, stderr=subprocess.STDOUT, start_new_session=True)


def emulator_answers() -> bool:
    """The emulator answers adb and Android's package service answers too. On 11-04 (API 29,
    twice) the package service died during a stand-in's `pm install` while `adb shell true`
    still worked, and every later pm command hung or failed with "Broken pipe"."""
    try:
        res = subprocess.run(["adb", "shell", "pm path android"], capture_output=True, text=True, timeout=30)
        return res.returncode == 0 and "package:" in res.stdout
    except subprocess.TimeoutExpired:
        return False


def ensure_emulator() -> None:
    """Before each check: the emulator answers adb, or is started again cold. After the
    11-01 API 29 suite hung, the emulator answered nothing and all 13 checks failed one by
    one on a 60 s adb timeout; on the API 36 run, the check after a lost reboot failed on
    "device not found". Now such a run restarts the emulator once, or reports one failure."""
    global emulator_gone
    if emulator_answers():
        return
    try:
        relaunch_emulator("wasn't answering adb before a check")
        wait_for_boot(600)
    except (subprocess.TimeoutExpired, CheckFailed, RuntimeError, OSError) as e:
        emulator_gone = f"the emulator stopped answering adb and couldn't be brought back with a cold restart ({e})"
        raise CheckFailed(emulator_gone) from e
    if not emulator_answers():
        emulator_gone = "the emulator stopped answering adb and didn't answer after a cold restart either"
        raise CheckFailed(emulator_gone)
    sh("input keyevent 82")


def sdk() -> int:
    """The Android level. Fails when the phone doesn't answer, rather than reading as level 0:
    on the 10-31 branch run the emulator was gone after a reboot and a check that skips below
    API 33 was skipped instead of failing."""
    out = sh("getprop ro.build.version.sdk").strip()
    check(out.isdigit(), f"the phone didn't say its Android level (is the emulator still there? {out[:120]!r})")
    return int(out)


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
    death = re.compile(rf"{re.escape(PKG)}.*(Kill|has died|ANR|FATAL|[Bb]ackground activity|Abort background|Scheduling restart)|FATAL EXCEPTION")
    card = re.compile(r"ActivityTaskManager.*(START u0 .*GuardianCardActivity|Displayed .*GuardianCardActivity)")
    lines = [line.strip()[:200] for line in out.splitlines() if death.search(line) or card.search(line)]
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


def installed(package: str) -> bool:
    """Whether exactly [package] is installed. `pm list packages <name>` lists every package
    whose name contains it, and the test APK (app.guardianmode.debug.test) stays installed
    when the instrumented suite ends early: on 11-04's API 29 run the emulator stopped
    answering during the suite, Gradle couldn't uninstall it ("Broken pipe"), and ROB-011 read
    the test APK as Guardian still being there."""
    return f"package:{package}" in sh(f"pm list packages {package}").split()


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


def protection_notification() -> str | None:
    """The title of Guardian's persistent notification if it is posted, else None. Whether it
    is posted comes from `cmd notification list` (one key per active notification,
    "0|pkg|id|tag|uid"); the title from dumpsys, for the message."""
    keys = sh("cmd notification list")
    if "|" not in keys and re.search(r"unknown|usage|not found", keys, re.I):
        keys = sh("dumpsys notification --noredact", timeout=60).replace("key=", "\n")
    if not re.search(rf"^\d+\|{re.escape(PKG)}\|1\|", keys, re.M):
        return None
    out = sh("dumpsys notification --noredact", timeout=60)
    for record in out.split("NotificationRecord(")[1:]:
        if f"pkg={PKG} " in record[:200] and " id=1 " in record[:300]:
            m = re.search(r"android\.title=\S+ \((.*?)\)\s*$", record, re.M)
            return m.group(1) if m else "?"
    return "?"


def protected() -> bool:
    """The service runs and its notification says protection is on."""
    return service_running() and protection_notification() == "Guardian Mode is on"


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


def find_desc(prefix: str) -> tuple[int, int, int, int] | None:
    """Bounds of the first node whose content-desc starts with [prefix]."""
    xml = ui_dump()
    for m in re.finditer(r'<node [^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if m.group(1).replace("&apos;", "'").replace("&quot;", '"').replace("&amp;", "&").startswith(prefix):
            return tuple(map(int, m.group(2, 3, 4, 5)))  # type: ignore[return-value]
    return None


def pixel(x: int, y: int) -> tuple[int, int, int]:
    """The screen's colour at (x, y), from a raw screencap (a short header, then RGBA rows)."""
    raw = subprocess.run(["adb", "exec-out", "screencap"], capture_output=True, timeout=30).stdout
    w, h = struct.unpack_from("<II", raw, 0)
    offset = len(raw) - w * h * 4
    check(12 <= offset <= 64, f"unexpected screencap layout: {len(raw)} bytes for {w}x{h}")
    i = offset + (y * w + x) * 4
    return raw[i], raw[i + 1], raw[i + 2]


def solid_png(path: Path, rgb: tuple[int, int, int], w: int = 400, h: int = 300) -> None:
    """A plain PNG of one colour, so a screenshot can tell whether the picture itself is drawn."""
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    row = b"\x00" + bytes(rgb) * w
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(row * h))
        + chunk(b"IEND", b"")
    )


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


def dismiss_safe_word() -> str:
    """Taps "Everything's fine" on the safe-word card. Returns what happened, for a failure
    message: a card that was never read can still be up behind the next app."""
    if wait_text("Everything's fine", 6):
        tap_text("Everything's fine")
        time.sleep(1)
        return "tapped"
    return f"not read in 6 s (card activity up: {card_activity_up()})"


def open_app(package: str) -> None:
    sh(f"monkey -p {package} -c android.intent.category.LAUNCHER 1")


def pause_card_within(seconds: float = 5, card_before: bool = False) -> bool:
    """The pause card's headline on screen, or, when a UI dump can't read the window, the
    card activity itself in front. The 10-30 API 36 branch run (41079010624) had the card
    displayed 1.7 s after Chase opened (ActivityTaskManager "Displayed ...GuardianCardActivity")
    while 8 s of dumps never read its text. Only a pause card can show in these checks: none
    of them sets a lock level. A card that was already up before the app opened (a safe-word
    card left over) doesn't count."""
    def up() -> bool:
        return find_text("Hold on a minute.") is not None or (not card_before and top_activity().endswith("GuardianCardActivity"))
    return wait_for(up, seconds, interval=0.5) is not None


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
        before = card_activity_up()
        open_app(package)
        shown = pause_card_within(8, card_before=before)
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


def installs_and_opens(apk: str, **_) -> None:
    """GM-INST-001: a plain install (no permissions granted), opened from the launcher entry,
    opens without a crash on the role choice. The first open after install and a later cold
    start (after a force stop) are both timed with `am start -W`; the bar is the case's,
    3 s on API 36 and 5 s on the budget API 29 image, for the later cold start."""
    sh(f"pm uninstall {PKG}")
    adb("install", "-r", apk, timeout=300)
    wake()
    sh("logcat -b crash -c")

    def launch() -> int:
        out = sh(f"am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n {PKG}/app.guardianmode.ui.MainActivity", timeout=60)
        m = re.search(r"TotalTime:\s*(\d+)", out) or re.search(r"WaitTime:\s*(\d+)", out)
        check(m is not None and "Error" not in out, f"am start -W didn't report a launch: {out.strip()[:400]}")
        return int(m.group(1))

    first = launch()
    check(wait_text("How will this phone be used?", 20), f"the first screen isn't the role choice ({top_activity()})")
    sh(f"am force-stop {PKG}")
    sh("input keyevent KEYCODE_HOME")
    time.sleep(3)
    cold = launch()
    check(wait_text("How will this phone be used?", 20), f"the role choice didn't come back after a cold start ({top_activity()})")
    crashes = sh("logcat -b crash -d")
    measurements["Cold start: first open after install"] = f"{first} ms"
    measurements["Cold start: after a force stop"] = f"{cold} ms"
    check(PKG not in crashes, f"Guardian crashed while opening: {crashes.strip()[:600]}")
    check(guardian_pid() != "", "Guardian's process isn't running after it opened")
    bar = 5000 if sdk() < 33 else 3000
    check(cold <= bar, f"cold start took {cold} ms (bar {bar} ms on API {sdk()}; first open after install {first} ms)")


# Location, SMS, call log and Accessibility: Guardian must never ask for them or use them.
BANNED_PERMISSIONS = {
    "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH", "READ_CALL_LOG", "WRITE_CALL_LOG",
    "PROCESS_OUTGOING_CALLS", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
    "BIND_ACCESSIBILITY_SERVICE",
}
BANNED_OPS = {
    "COARSE_LOCATION", "FINE_LOCATION", "MONITOR_LOCATION", "MONITOR_HIGH_POWER_LOCATION", "READ_SMS", "WRITE_SMS",
    "RECEIVE_SMS", "SEND_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH", "READ_ICC_SMS", "WRITE_ICC_SMS", "READ_CELL_BROADCASTS",
    "RECEIVE_EMERGECY_SMS", "READ_CALL_LOG", "WRITE_CALL_LOG", "PROCESS_OUTGOING_CALLS", "BIND_ACCESSIBILITY_SERVICE",
    "ACCESS_ACCESSIBILITY",
}


def requested_and_granted() -> tuple[set[str], set[str]]:
    """Guardian's requested permissions, and those granted (install and runtime), from dumpsys package."""
    out = sh(f"dumpsys package {PKG}")
    requested: set[str] = set()
    granted: set[str] = set()
    section = None
    for line in out.splitlines():
        t = line.strip()
        header = re.match(r"^(requested|install|runtime|declared) permissions:$", t)
        if header:
            section = header.group(1)
            continue
        m = re.match(r"^([A-Za-z0-9_.]+\.[A-Z0-9_]+)(:.*)?$", t)
        if not m:
            section = None
            continue
        if section == "requested":
            requested.add(m.group(1))
        elif section in ("install", "runtime") and "granted=true" in (m.group(2) or ""):
            granted.add(m.group(1))
    return requested, granted


def ops_touched() -> dict[str, str]:
    """Each app op `appops get` lists for Guardian, with what it says about it. On API 29 an
    op is one line ("FINE_LOCATION: allow; time=+1m ago"); later levels add indented
    "Access:" / "Reject:" lines under it."""
    out = sh(f"appops get {PKG}")
    ops: dict[str, str] = {}
    current = None
    for line in out.splitlines():
        m = re.match(r"^\s{0,4}([A-Z][A-Z0-9_]+)\b[^:]*:\s*(.*)$", line)
        if m:
            current = m.group(1)
            ops[current] = m.group(2).strip()
        elif current and line.strip():
            ops[current] += " " + line.strip()
    return ops


def no_banned_capabilities(apk: str, **_) -> None:
    """GM-PRIV-023: after setup, an unknown call, the safe-word card and a pause card,
    Guardian has never asked for or used Accessibility, SMS, the call log or location:
    not in its requested or granted permissions, not in any app op it touched, not as an
    enabled accessibility service. The full lists go in the report."""
    fresh_app(apk)
    check(wait_for(service_running, 30) is not None, "service never started")
    broadcasts_idle()
    standard_call()
    dismiss_safe_word()
    shown = pause_card_after_open()
    sh("input keyevent KEYCODE_HOME")
    time.sleep(2)
    requested, granted = requested_and_granted()
    ops = ops_touched()
    measurements["Permissions requested"] = ", ".join(sorted(p.split(".")[-1] for p in requested)) or "none"
    measurements["Permissions granted at the end of the run"] = ", ".join(sorted(p.split(".")[-1] for p in granted)) or "none"
    used = {op: v for op, v in ops.items() if re.search(r"time=|Access:|Reject:|rejectTime=|duration=", v)}
    measurements["App ops Guardian used"] = ", ".join(sorted(used)) or "none"
    check(requested, "couldn't read Guardian's requested permissions from dumpsys package")
    problems = [f"requests {p}" for p in sorted(requested) if p.split(".")[-1] in BANNED_PERMISSIONS]
    problems += [f"holds {p}" for p in sorted(granted) if p.split(".")[-1] in BANNED_PERMISSIONS]
    problems += [f"app op {op}: {v[:160]}" for op, v in sorted(used.items()) if op in BANNED_OPS]
    a11y = sh("settings get secure enabled_accessibility_services")
    if PKG in a11y:
        problems.append(f"enabled as an accessibility service: {a11y.strip()}")
    check(not problems, "; ".join(problems))
    # The run has to have exercised protection for "never used" to mean something.
    check(shown, f"the pause card never showed, so the run didn't exercise protection ({diagnosis()})")


PHOTO_RGB = (220, 30, 150)


def photo_showing() -> bool:
    """The photo card is on the home screen and its centre is the test picture's colour."""
    bounds = None
    for prefix in ("Today. ", "Yesterday. "):
        bounds = find_desc(prefix)
        if bounds:
            break
    if not bounds:
        return False
    x1, y1, x2, y2 = bounds
    # Above the caption's gradient, which covers the bottom of the card.
    r, g, b = pixel((x1 + x2) // 2, y1 + (y2 - y1) // 3)
    return all(abs(a - e) <= 40 for a, e in zip((r, g, b), PHOTO_RGB))


def photos_survive(apk: str, **_) -> None:
    """GM-PHOTO-009: a picture added on the phone is still there and drawn on the home
    screen after a force stop, an install over the top and a reboot. (The picture goes in
    the way the importer leaves it, a file in Guardian's files/photos and a localPhotos
    entry; adding it through the share sheet is gm_photo_003 on the device suite.)"""
    fresh_app(apk)
    wait_for(service_running, 30)
    photo_id = "local-gm-photo-009"
    path = f"/data/user/0/{PKG}/files/photos/{photo_id}.jpg"
    png = Path("gm-photo.png")
    solid_png(png, PHOTO_RGB)
    adb("push", str(png), "/data/local/tmp/gm-photo.png")
    png.unlink()
    write_state({
        "onboardingDone": True,
        "localPhotos": [{"id": photo_id, "url": f"file://{path}", "fromName": "", "createdAt": int(time.time() * 1000)}],
    })
    sh(f"run-as {PKG} mkdir -p files/photos")
    sh(f"run-as {PKG} sh -c 'cat /data/local/tmp/gm-photo.png > files/photos/{photo_id}.jpg'")
    sh("rm /data/local/tmp/gm-photo.png")

    def still_there(when: str) -> None:
        state = read_state()
        ids = [p.get("id") for p in state.get("localPhotos", [])]
        check(photo_id in ids, f"{when}: the photo is gone from Guardian's saved state (localPhotos={ids})")
        size = sh(f"run-as {PKG} stat -c %s files/photos/{photo_id}.jpg").strip()
        check(size.isdigit() and int(size) > 0, f"{when}: the photo's file is gone ({size or 'no output'})")
        wake()
        sh("input keyevent KEYCODE_HOME")
        check(wait_for(photo_showing, 20) is not None, f"{when}: the photo card isn't showing the picture on the home screen ({top_activity()})")

    still_there("after it was added")
    sh(f"am force-stop {PKG}")
    time.sleep(2)
    still_there("after a force stop")
    adb("install", "-r", apk, timeout=300)
    wait_for(service_running, 60)
    still_there("after installing over the top")
    reboot()
    wake()
    wait_for(service_running, 60)
    still_there("after a reboot")


def home_before_setup(apk: str, **_) -> None:
    """GM-INST-011: before setup is finished, Guardian isn't the home app. A plain install
    (nothing granted), opened on the role choice, then Home: the phone's normal launcher
    comes up. Opening Guardian again goes back to setup."""
    sh(f"pm uninstall {PKG}")
    adb("install", "-r", apk, timeout=300)
    wake()
    sh("input keyevent KEYCODE_HOME")
    time.sleep(2)
    launcher = top_activity()
    open_app(PKG)
    check(wait_text("How will this phone be used?", 20), f"Guardian didn't open on the role choice ({top_activity()})")
    sh("input keyevent KEYCODE_HOME")
    left = wait_for(lambda: not top_activity().startswith(PKG), 10)
    top = top_activity()
    measurements["Before setup: Home opens"] = top
    check(left is not None, f"Home during setup stayed in Guardian ({top}; the launcher before was {launcher})")
    holders = home_holders()
    check(PKG not in holders, f"Guardian holds the home role before setup asked for it ({holders})")
    check(read_state().get("onboardingDone") is not True, "setup is marked done though it was never finished")
    if "ResolverActivity" in top:  # a home chooser, not the launcher: leave it before reopening
        sh("input keyevent KEYCODE_BACK")
    open_app(PKG)
    check(wait_text("How will this phone be used?", 20), f"reopening Guardian didn't go back to setup ({top_activity()})")
    sh("input keyevent KEYCODE_HOME")


def ui_nodes() -> list[dict[str, str]]:
    """Every node of a UI dump, with its text (or description), resource id, package and center."""
    nodes = []
    for m in re.finditer(r"<node [^>]*>", ui_dump()):
        def attr(name: str, node: str = m.group(0)) -> str:
            a = re.search(rf'\b{name}="([^"]*)"', node)
            return (a.group(1) if a else "").replace("&apos;", "'").replace("&quot;", '"').replace("&amp;", "&")
        b = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", attr("bounds"))
        x1, y1, x2, y2 = map(int, b.groups()) if b else (0, 0, 0, 0)
        nodes.append({"text": attr("text") or attr("content-desc"), "res": attr("resource-id"), "pkg": attr("package"),
                      "x": str((x1 + x2) // 2), "y": str((y1 + y2) // 2)})
    return nodes


def screen_words(package_part: str) -> str:
    """What the window of the package whose name contains [package_part] says, for measurements and messages."""
    return " | ".join(dict.fromkeys(n["text"] for n in ui_nodes() if package_part in n["pkg"] and n["text"]))[:600]


def tap_text_scrolling(text: str, swipes: int = 4) -> bool:
    """Taps [text] on Guardian's page, swiping the page up when it is below the fold."""
    for _ in range(swipes + 1):
        if tap_text(text):
            return True
        sh("input swipe 540 1700 540 700 300")
        time.sleep(1)
    return False


CONSENT_TITLES = ["A simpler home screen", "Pause before sending money", "Quiet unknown callers",
                  'A daily "I\'m OK"', "Reminders", "Keep Guardian awake"]


def page_marks(page: str) -> list[str]:
    """What tells a screen apart: for a consent page ("Step N of 6") its step line or its
    title, since a UI dump doesn't always carry both (API 36 dropped page 4's title on 11-03)."""
    m = re.fullmatch(r"Step (\d) of 6", page)
    return [page, CONSENT_TITLES[int(m.group(1)) - 1]] if m else [page]


def showing(page: str) -> bool:
    texts = {n["text"] for n in ui_nodes()}
    return any(t in texts for t in page_marks(page))


def tap_until(text: str, then: str, before: str | None = None, tries: int = 3) -> bool:
    """Taps [text] until [then] shows. A tap is repeated only while the screen it was meant
    for ([before]) still shows: a dump that misses [then] must not send a second tap to the
    next page (on 11-03's API 29 run one "Not now" became three and setup skipped to page 5)."""
    for i in range(tries):
        time.sleep(1)
        if i == 0 or before is None or showing(before):
            tap_text_scrolling(text, swipes=2)
        if wait_for(lambda: showing(then), 10, interval=0.5) is not None:
            return True
    return False


def answer_android_screens(page: str, ops: list[str]) -> list[str]:
    """Taps "Turn on" on the consent page whose "Step N of 6" is [page] (UI dumps on API 36
    don't always carry the page title: page 4's was missing on 11-03), then answers Android as a parent saying yes would,
    until Guardian says "All set": Allow on a permission dialog; on a Settings page the switch
    (granted with appops from [ops], in order, since where the switch sits differs per level)
    and Back; Continue on Guardian's checklist for the next item. Returns what Android showed,
    in order: "dialog: <its words>" or "settings: <activity>"."""
    check(wait_for(lambda: showing(page), 15, interval=0.5) is not None, f"setup didn't reach \"{page}\" ({top_activity()}): {screen_words(PKG)}")
    check(tap_text_scrolling("Turn on"), f"no \"Turn on\" on \"{page}\": {screen_words(PKG)}")
    shown: list[str] = []
    unanswered: list[str] = []
    for _ in range(12):
        time.sleep(2)
        top = top_activity()
        if "permissioncontroller" in top:
            nodes = ui_nodes()
            allow = next((n for n in nodes if n["res"].endswith(":id/permission_allow_button")), None)
            if allow is None:
                # Android grants a permission whose group already has one without showing
                # anything, but its activity is briefly on top while it does.
                unanswered.append(f"{top.split('/')[-1]}: {screen_words('permissioncontroller')[:200]}")
                continue
            shown.append("dialog: " + " | ".join(dict.fromkeys(n["text"] for n in nodes if "permissioncontroller" in n["pkg"] and n["text"]))[:600])
            sh(f"input tap {allow['x']} {allow['y']}")
        elif top.startswith("com.android.settings/"):
            shown.append(f"settings: {top.split('/')[-1]} ({screen_words('com.android.settings')[:400]})")
            check(bool(ops), f"Settings opened ({top}) with nothing left to turn on; Android showed {shown}")
            sh(f"appops set {PKG} {ops.pop(0)} allow")
            sh("input keyevent KEYCODE_BACK")
        elif top.startswith(PKG + "/"):
            if find_text("All set"):
                return shown
            if not tap_text("Continue"):
                sh("input swipe 540 1700 540 700 300")  # below the fold
        else:
            shown.append(f"other: {top}")
    check(False, f"\"{page}\" never said \"All set\" ({top_activity()}); Android showed {shown}; "
                 f"dialogs with no Allow: {unanswered[-3:]}; Guardian shows: {screen_words(PKG)}")
    return shown


def consent_android_screens(apk: str, **_) -> None:
    """GM-CONS-004, GM-CONS-007 and GM-CONS-009: setup's runtime permission dialogs, walked
    from a plain install (nothing granted) with real taps on Android's Allow. Roles and
    special access are left granted here: ConsentDeviceTest covers those screens on all three
    levels, from inside the app, where runtime permissions can't be taken away (that kills
    the process the tests run in). Exact alarms and full-screen notifications are taken away
    here for the same reason. What Android showed on each page goes to the measurements."""
    level = sdk()
    sh(f"pm uninstall {PKG}")
    adb("install", "-r", apk, timeout=300)  # no -g: every runtime permission still to be asked
    for c in [
        f"cmd role add-role-holder android.app.role.HOME {PKG}",
        f"cmd role add-role-holder android.app.role.CALL_SCREENING {PKG}",
        f"appops set {PKG} GET_USAGE_STATS allow",
        f"appops set {PKG} SYSTEM_ALERT_WINDOW allow",
    ]:
        sh(c)
    reminder_ops = (["SCHEDULE_EXACT_ALARM"] if level >= 31 else []) + (["USE_FULL_SCREEN_INTENT"] if level >= 34 else [])
    for op in reminder_ops:
        sh(f"appops set {PKG} {op} deny")
    wake()
    open_app(PKG)
    check(wait_text("How will this phone be used?", 20), f"Guardian didn't open on the role choice ({top_activity()})")
    # A tap right after a cold start can be lost (on 11-03's API 29 run the role choice stayed
    # after one), so each step is tapped again until the next screen shows.
    check(tap_until("Make this phone simpler", "Make your phone easier to use.", "How will this phone be used?"), f"Welcome didn't follow: {screen_words(PKG)}")
    check(tap_until("Set up on my own for now", "Step 1 of 6", "Make your phone easier to use."), f"page 1 didn't follow: {screen_words(PKG)}")

    # GM-CONS-004: page 1 asks only for Phone calls (the home role is held).
    shown = answer_android_screens("Step 1 of 6", [])
    measurements["Setup page 1: what Android showed"] = " / ".join(shown) or "nothing"
    check(len(shown) == 1 and shown[0].startswith("dialog:") and "phone calls" in shown[0].lower(),
          f"page 1 should show Android's \"make and manage phone calls\" dialog once; it showed {shown}")
    _, granted = requested_and_granted()
    check("android.permission.CALL_PHONE" in granted, "Phone calls not granted after Allow")
    check(tap_until("Next", "Step 2 of 6", "Step 1 of 6"), f"page 2 didn't follow: {screen_words(PKG)}")
    check(tap_until("Not now", "Step 3 of 6", "Step 2 of 6"), f"page 3 didn't follow: {screen_words(PKG)}")

    # GM-CONS-007: phone state and contacts (the screening role is held). Phone state is in the
    # same group as Phone calls, which page 1 got, so Android may grant it without asking.
    shown = answer_android_screens("Step 3 of 6", [])
    measurements["Setup page 3: what Android showed"] = " / ".join(shown) or "nothing"
    _, granted = requested_and_granted()
    check({"android.permission.READ_PHONE_STATE", "android.permission.READ_CONTACTS"} <= granted,
          f"phone state and contacts not both granted after Allow; Android showed {shown}")
    check(any(s.startswith("dialog:") and "contacts" in s.lower() for s in shown), f"no Contacts dialog on page 3; Android showed {shown}")
    check(all(s.startswith("dialog:") for s in shown), f"page 3 opened something besides permission dialogs: {shown}")
    check(tap_until("Next", "Step 4 of 6", "Step 3 of 6"), f"page 4 didn't follow: {screen_words(PKG)}")
    check(tap_until("Not now", "Step 5 of 6", "Step 4 of 6"), f"page 5 didn't follow: {screen_words(PKG)}")

    # GM-CONS-009: notifications (API 33+), alarms (31+), full screen (34+), each on its own screen.
    ops = list(reminder_ops)
    # On the 11-03 API 36 run notifications were already allowed by page 5 (nothing in setup
    # had asked), so whether Android must ask is read first.
    _, granted = requested_and_granted()
    notifications_before = "android.permission.POST_NOTIFICATIONS" in granted
    measurements["Setup page 5: notifications already allowed"] = str(notifications_before)
    shown = answer_android_screens("Step 5 of 6", ops)
    measurements["Setup page 5: what Android showed"] = " / ".join(shown) or "nothing"
    dialogs = [s for s in shown if s.startswith("dialog:")]
    settings = [s for s in shown if s.startswith("settings:")]
    if level >= 33 and not notifications_before:
        check(len(dialogs) == 1 and "notifications" in dialogs[0].lower(), f"expected one notifications dialog on API {level}; Android showed {shown}")
        _, granted = requested_and_granted()
        check("android.permission.POST_NOTIFICATIONS" in granted, "notifications not granted after Allow")
    else:
        check(not dialogs, f"API {level} needs no notifications dialog (allowed before: {notifications_before}); Android showed {shown}")
    check(len(settings) == len(reminder_ops) and not ops,
          f"expected a Settings screen for each of {reminder_ops} on API {level}; Android showed {shown}")
    check(all("Guardian Mode" in s for s in settings), f"a Settings screen wasn't Guardian's own: {settings}")
    check(len(shown) == len(dialogs) + len(settings), f"page 5 opened something unexpected: {shown}")
    sh("input keyevent KEYCODE_HOME")


def reinstall_starts_fresh(apk: str, **_) -> None:
    """GM-ROB-011: after uninstalling and installing again, setup starts fresh and nothing
    from before survives: no saved state or pictures, no protection running, not the home app."""
    fresh_app(apk)
    check(wait_for(service_running, 30) is not None, "service never started before the uninstall")
    time.sleep(3)
    check(read_state().get("onboardingDone") is True, "setup wasn't saved as done before the uninstall")
    sh(f"run-as {PKG} sh -c 'mkdir -p files/photos && echo x > files/photos/local-gm-rob-011.jpg'")
    said = sh(f"pm uninstall {PKG}").strip()
    check(not installed(PKG), f"Guardian is still installed after pm uninstall (pm said {said[:200]!r}; "
                              f"packages: {sh(f'pm list packages {PKG}').split()})")
    adb("install", "-r", apk, timeout=300)
    wake()
    left = sh(f"run-as {PKG} ls files files/datastore files/photos").strip()
    check(read_state() == {} and "guardian_state.json" not in left and "local-gm-rob-011" not in left,
          f"something from before the uninstall survived: {left[:300]!r} state={read_state()}")
    open_app(PKG)
    check(wait_text("How will this phone be used?", 20), f"Guardian didn't start setup again ({top_activity()})")
    check(read_state().get("onboardingDone") is not True, "setup is marked done after a reinstall")
    check(not service_running(), "protection is running before setup was done again")
    check(PKG not in home_holders(), f"Guardian is still the home app after a reinstall ({home_holders()})")
    sh("input keyevent KEYCODE_HOME")


def stopped_from_active_apps(apk: str, **_) -> None:
    """GM-SVC-003: the parent stops Guardian from Android's "Active apps" list (Quick
    Settings, API 33+). Its Stop button is the system's stop-app (stop the process and its
    services, keep alarms and jobs), which `am stop-app` runs (API 34+). The pass rule, from
    docs/SPEC.md design rule 3 and docs/POLICY.md §1 rule 4 (user-stoppable):
      1. Stop works: within 10 s the service is gone and so is "Guardian Mode is on".
      2. It stays stopped while the parent uses another app: not back within 15 s, and
         never running without its notification (no hidden protection).
      3. The next Home press brings it back, notification included, within 30 s (Guardian is
         the home app; the notification is how the parent sees it is on again).
      4. Nothing keeps it from coming back at the next boot: Android hasn't marked it
         stopped (a stopped app gets no BOOT_COMPLETED, GM-SVC-006's path), or, if it has,
         Guardian is still the home app, which the boot opens (GM-SVC-008's path).
    Point 4 doesn't reboot: GM-SVC-006 does, and each reboot risks the CI emulator not
    coming back (it didn't on the 10-30 and 10-31 API 36 branch runs).
    Stopped from a Notes app in front, as a parent would from any app."""
    if sdk() < 33:
        raise CheckSkipped(f"API {sdk()} has no Active apps list (Android 13 added it)")
    fresh_app(apk)
    check(wait_for(protected, 30) is not None,
          f"protection isn't on before the stop (service={service_running()} notification={protection_notification()!r})")
    open_app(NOTES)
    check(wait_for(lambda: NOTES in top_activity(), 10) is not None, f"Notes didn't open ({top_activity()})")
    pid = guardian_pid()
    out = sh(f"am stop-app {PKG}").strip()
    check(not re.search(r"Unknown command|Error|Exception", out), f"am stop-app isn't available on API {sdk()}: {out[:300]}")
    gone = wait_for(lambda: not service_running() and protection_notification() is None, 10)
    check(gone is not None, f"10 s after Stop the service={service_running()} notification={protection_notification()!r} "
                            f"pid {pid} -> {guardian_pid() or 'none'}")
    hidden: list[str] = []

    def back() -> bool:
        running, title = service_running(), protection_notification()
        if running and title is None:
            hidden.append(f"service running with no notification (pid {guardian_pid()})")
        return running or title is not None
    again = wait_for(back, 15)
    check(not hidden, hidden[0] if hidden else "")
    check(again is None, f"Guardian started again by itself {again or 0:.0f} s after the parent stopped it, with Notes in front "
                         f"(service={service_running()} notification={protection_notification()!r} last exit: {last_exit()} log: {guardian_log(4)})")
    stopped, holders = app_stopped(), home_holders()
    measurements["Stopped from Active apps: Android's stopped flag"] = str(stopped).lower()
    check(not stopped or PKG in holders, f"after Stop Android marks Guardian stopped (no BOOT_COMPLETED) and it isn't the home app ({holders})")

    wake()
    sh("input keyevent KEYCODE_HOME")
    took = wait_for(protected, 30)
    measurements["Stopped from Active apps: Home to protection on"] = f"{took:.0f} s" if took is not None else "never"
    check(took is not None, f"Home didn't bring protection back within 30 s (top={top_activity()} service={service_running()} "
                            f"notification={protection_notification()!r} stopped={app_stopped()})")


def apk_size(apk: str, shrunk_apk: str | None = None, **_) -> None:
    """GM-PERF-008: record the test APK's size; flag it past 150 MB. The shrunk build's size is recorded too."""
    mb = Path(apk).stat().st_size / 1_048_576
    measurements["Debug APK size"] = f"{mb:.1f} MB"
    if shrunk_apk and Path(shrunk_apk).is_file():
        measurements["Shrunk APK size"] = f"{Path(shrunk_apk).stat().st_size / 1_048_576:.1f} MB"
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


TESTING_APK_URL = (
    f"https://github.com/{os.environ.get('GITHUB_REPOSITORY', 'overconfidence7/guardian-new')}"
    "/releases/download/testing/guardian-mode-test.apk"
)


def package_version() -> str:
    out = sh(f"dumpsys package {PKG}")
    name = re.search(r"versionName=(\S+)", out)
    code = re.search(r"versionCode=(\d+)", out)
    return f"{name.group(1) if name else '?'} (versionCode {code.group(1) if code else '?'})"


def download_testing_apk(dest: Path) -> None:
    """The APK on the rolling "testing" release (test-apk.yml), what a test phone has."""
    last = ""
    for attempt in range(3):
        try:
            with urllib.request.urlopen(TESTING_APK_URL, timeout=120) as res, dest.open("wb") as out:
                shutil.copyfileobj(res, out)
            if dest.stat().st_size > 1_000_000:
                return
            last = f"only {dest.stat().st_size} bytes"
        except Exception as e:  # noqa: BLE001 - retried, then said
            last = str(e)
        # test-apk.yml deletes the release and makes it again on every push to main.
        time.sleep(20 * (attempt + 1))
    raise CheckFailed(f"couldn't download the testing build from {TESTING_APK_URL}: {last}")


def install(*args: str) -> str:
    """`adb install`, with what it printed on both streams (the failure reason goes to stderr)."""
    res = subprocess.run(["adb", "install", *args], capture_output=True, text=True, timeout=300)
    return f"{res.stdout.strip()} {res.stderr.strip()}".strip()


def apk_signer(path: str) -> str:
    """The APK's signing certificate digest, when apksigner is at hand (for the failure message)."""
    sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    tools = sorted(Path(sdk_root, "build-tools").glob("*/apksigner")) if sdk_root else []
    if not tools:
        return "?"
    out = run_quietly([str(tools[-1]), "verify", "--print-certs", path], timeout=120)
    found = re.search(r"SHA-256 digest: ([0-9a-f]+)", out)
    return found.group(1)[:16] if found else "?"


def upgrade_from_testing_build(apk: str, **_) -> None:
    """GM-ROB-012: the testing build a test phone has (the "testing" release), set up in solo
    mode with the parent's settings (Guided, a lock code, a photo, every consent answered),
    then this build installed over it with `adb install -r`, as a phone gets the next testing
    build. CI signs with the same test key (android/testing) and a higher versionCode, so it is
    a real update. Everything must be kept, and the service must come back by itself."""
    previous = Path("guardian-mode-test.apk")
    download_testing_apk(previous)
    try:
        sh(f"pm uninstall {PKG}")
        out = install(str(previous))
        check("Success" in out, f"the testing build didn't install: {out[-400:]}")
        before_version = package_version()
        grant_everything()
        write_state({"onboardingDone": True})
        wake()
        sh("input keyevent KEYCODE_HOME")
        check(wait_for(service_running, 60) is not None, f"the testing build's service never started ({diagnosis()})")
        check(wait_for(lambda: "config" in read_state(), 30) is not None, "the testing build never saved its settings")

        # The parent's settings, on top of what the testing build saved itself.
        state = read_state()
        salt = "gm-rob-012-salt"
        state["config"]["lock"]["level"] = "GUIDED"
        photo_id = "local-gm-rob-012"
        state.update({
            "consent": {k: True for k in ("home", "pause", "calls", "checkIn", "reminders", "battery")},
            "consentAcceptedAt": int(time.time() * 1000) - 86_400_000,
            "lockCodeSalt": salt,
            "lockCodeHash": hashlib.sha256(f"{salt}:2580".encode()).hexdigest(),
            "localPhotos": [{"id": photo_id, "url": f"file:///data/user/0/{PKG}/files/photos/{photo_id}.jpg",
                             "fromName": "", "createdAt": int(time.time() * 1000)}],
        })
        write_state(state)
        png = Path("gm-photo.png")
        solid_png(png, PHOTO_RGB)
        adb("push", str(png), "/data/local/tmp/gm-photo.png")
        png.unlink()
        sh(f"run-as {PKG} mkdir -p files/photos")
        sh(f"run-as {PKG} sh -c 'cat /data/local/tmp/gm-photo.png > files/photos/{photo_id}.jpg'")
        sh("rm /data/local/tmp/gm-photo.png")
        wake()
        sh("input keyevent KEYCODE_HOME")
        check(wait_for(service_running, 60) is not None, "the testing build's service didn't start again with the parent's settings")
        check(wait_for(photo_showing, 20) is not None, f"the testing build doesn't show the photo on its home screen ({top_activity()})")
        time.sleep(3)
        before = read_state()
        check(before.get("config", {}).get("lock", {}).get("level") == "GUIDED", "the testing build didn't keep the Guided level it was given")

        # Leave Guardian in front, as on a phone, and install over it without opening anything after.
        out = install("-r", apk)
        check("Success" in out, f"this build didn't install over the testing build: {out[-400:]} "
                                f"(signers: testing {apk_signer(str(previous))}, this build {apk_signer(apk)}; installed {before_version})")
        after_version = package_version()
        measurements["Upgrade from the testing build: version before"] = before_version
        measurements["Upgrade from the testing build: version after"] = after_version
        took = wait_for(service_running, 60)
        check(took is not None, f"the service didn't come back by itself after the update ({diagnosis()})")
        measurements["Upgrade from the testing build: update to service running"] = f"{took:.0f} s"

        after = read_state()
        for key in ("onboardingDone", "consent", "consentAcceptedAt", "lockCodeHash", "lockCodeSalt", "localPhotos"):
            check(after.get(key) == before.get(key), f"{key} changed across the update: {before.get(key)!r} -> {after.get(key)!r}")
        changed = sorted(k for k, v in before.get("config", {}).items() if after.get("config", {}).get(k) != v)
        check(not changed, f"settings changed across the update: {changed} (lock level after: {after.get('config', {}).get('lock')})")
        size = sh(f"run-as {PKG} stat -c %s files/photos/{photo_id}.jpg").strip()
        check(size.isdigit() and int(size) > 0, f"the photo's file is gone after the update ({size or 'no output'})")
        kept = wait_for(lambda: PKG in home_holders(), 30)
        check(kept is not None, f"Guardian lost the home role after the update ({home_holders()!r})")
        wake()
        sh("input keyevent KEYCODE_HOME")
        check(wait_for(photo_showing, 20) is not None, f"the photo card isn't showing the picture after the update ({top_activity()})")
    finally:
        previous.unlink(missing_ok=True)


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


def deep_idle_state() -> str:
    """Doze's deep state (IDLE while dozing, ACTIVE when not), or what came back instead."""
    return sh("dumpsys deviceidle get deep").strip() or "no answer"


def doze_call(apk: str, **_) -> None:
    """GM-SVC-009: an unknown call while the phone dozes still arms the tripwires.

    The 11-06 API 29 daily run failed this with only "no pause card", which couldn't tell a
    call Guardian missed in Doze from a card that didn't come afterwards (it said nothing
    else: the call's end had been saved, the service ran, Chase was in front). So each link
    is checked on its own and fails saying which: Guardian running before the screen goes
    off (API 29 takes 3.4-3.7 s to cold start, and fresh_app only waits 3 s), the phone really
    in Doze, the call's end saved while still in Doze (what Doze could break: Guardian hearing
    about the call at all), and then the card, with the safe-word card's fate, each try's
    view of the screen, and Guardian's own log lines (card starts, blocked background starts,
    deaths) in the message, since the job's logcat can't be read through the API."""
    fresh_app(apk)
    check(wait_for(service_running, 30) is not None, f"the service never started after setup ({diagnosis()})")
    broadcasts_idle()
    sh("input keyevent KEYCODE_SLEEP")
    time.sleep(2)
    forced = sh("dumpsys deviceidle force-idle").strip()
    before = deep_idle_state()
    check(before == "IDLE", f"the phone didn't go into Doze, so nothing here tests it (force-idle said {forced!r}; deep state {before})")
    try:
        standard_call()
        saved_in = wait_for(lambda: saved_call_end().endswith(("ago", "paused")), 30)
        saved = saved_call_end()
        during = deep_idle_state()
    finally:
        sh("dumpsys deviceidle unforce")
    check(saved_in is not None, f"Guardian never saved the end of an unknown call taken in Doze (30 s after hang-up, deep state {during}): {saved}; {diagnosis()}")
    measurements["Doze: hang-up to call end saved"] = f"{saved_in:.0f} s (deep state then: {during})"
    wake()
    safe_word = dismiss_safe_word()
    if pause_card_after_open():
        return
    raise CheckFailed(
        f"no pause card after an unknown call taken in Doze (call end saved {saved_in:.0f} s after hang-up, in deep state {during}; "
        f"safe-word card: {safe_word}; {'; '.join(open_notes) or 'no tries noted'}; now: {diagnosis()}; Guardian's log: {guardian_log()})"
    )


def memory(apk: str, shrunk_apk: str | None = None, **_) -> None:
    """GM-PERF-007: PSS on the home screen under 150 MB, measured on the shrunk build.

    The debug APK carries every class of every library; a Play install is R8-shrunk, so the
    budget is measured on the `benchmark` build type (release's shrinking, debug's package
    name and key). Without --shrunk-apk the debug APK is measured and the measurement says so."""
    if shrunk_apk is not None:
        check(Path(shrunk_apk).is_file(), f"shrunk APK not found at {shrunk_apk} (android: ./gradlew assembleBenchmark)")
    measured = shrunk_apk or apk
    fresh_app(measured)
    time.sleep(5)
    out = sh(f"dumpsys meminfo {PKG}")
    m = re.search(r"TOTAL PSS:\s+(\d+)", out) or re.search(r"^\s*TOTAL\s+(\d+)", out, re.M)
    check(m is not None, "couldn't read PSS from dumpsys meminfo")
    mb = int(m.group(1)) / 1024
    build = "shrunk build" if shrunk_apk else "debug build"
    measurements["Home screen PSS"] = f"{mb:.0f} MB ({build})"
    check(mb < 150, f"home screen PSS is {mb:.0f} MB on the {build} (bar: 150 MB)")


CHECKS = [
    ("gm_base_009_manifestAudit", manifest_audit, False),
    ("gm_perf_008_apkSize", apk_size, False),
    ("gm_inst_001_installsAndOpensOnTheRoleChoice", installs_and_opens, True),
    ("gm_inst_011_homeBeforeSetupIsTheOldLauncher", home_before_setup, True),
    ("gm_cons_004_gm_cons_007_gm_cons_009_runtimeDialogsInSetup", consent_android_screens, True),
    ("gm_rob_011_reinstallStartsFresh", reinstall_starts_fresh, True),
    ("gm_svc_001_serviceRunsAfterSetup", service_starts, True),
    ("gm_priv_023_noBannedCapabilitiesInPractice", no_banned_capabilities, True),
    ("gm_perf_007_homeScreenMemory", memory, True),
    ("gm_svc_007_survivesUpdate", survives_update, True),
    ("gm_rob_012_upgradeFromTheTestingBuild", upgrade_from_testing_build, True),
    ("gm_svc_008_forceStopThenHome", force_stop_then_home, True),
    ("gm_svc_003_stoppedFromActiveAppsComesBackOnHome", stopped_from_active_apps, True),
    ("gm_svc_009_unknownCallDuringDoze", doze_call, True),
    ("gm_pause_042_processKilledAfterCall", killed_after_call, True),
    ("gm_photo_009_photosSurviveRestartUpdateAndReboot", photos_survive, True),
    ("gm_svc_006_survivesReboot", survives_reboot, True),
    ("gm_pause_043_gm_svc_006_pauseCardAfterReboot", pause_card_after_reboot, True),
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", help="the debug APK every check installs")
    ap.add_argument("--shrunk-apk", help="the benchmark APK (R8-shrunk) the memory budget GM-PERF-007 measures")
    ap.add_argument("--out")
    ap.add_argument("--suite", default="device-host")
    ap.add_argument("--no-device", action="store_true", help="only the checks that read the APK")
    ap.add_argument("--bring-back", action="store_true",
                    help="only make sure the emulator answers adb, cold-starting it if not (run-device-suite.sh, "
                         "after the instrumented suite lost it); exit 1 if it still doesn't")
    args = ap.parse_args()
    if args.bring_back:
        global avd_name
        avd_name = find_avd_name()
        try:
            ensure_emulator()
        except Exception as e:  # noqa: BLE001 - said once, the caller decides
            print(f"the emulator couldn't be brought back: {e}")
            return 1
        return 0
    if not args.apk or not args.out:
        ap.error("--apk and --out are required")
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    if not args.no_device:
        avd_name = find_avd_name()
    suite = ET.Element("testsuite", name=args.suite)
    failures = 0
    current = {"name": "", "start": time.time()}

    def stopped_from_outside(signum, _frame) -> None:
        # run-device-suite.sh stops this after 45 minutes; say where it was, since nothing
        # else from a stopped run can be read through the API.
        done = [c.get("name") for c in suite]
        print(f"::error title={args.suite} stopped::device_checks.py was stopped (signal {signum}) during {current['name']} "
              f"after {time.time() - current['start']:.0f} s; checks before it: {', '.join(done[:-1]) or 'none'}", flush=True)
        sys.exit(1)
    signal.signal(signal.SIGTERM, stopped_from_outside)
    for name, fn, needs_device in CHECKS:
        if needs_device and args.no_device:
            continue
        current.update(name=name, start=time.time())
        if needs_device and not emulator_gone:
            try:
                ensure_emulator()
            except Exception as e:  # noqa: BLE001 - recorded once, as its own failure
                failures += 1
                lost = ET.SubElement(suite, "testcase", classname="DeviceChecks", name="emulatorAnswersAdb")
                ET.SubElement(lost, "failure", message=str(e)[:1500]).text = traceback.format_exc()
                print(f"FAIL emulatorAnswersAdb: {e}")
                print(f"::error title={args.suite} emulator::{str(e)[:3500].replace('%', '%25').replace(chr(10), '%0A')}; the checks from {name} on are skipped")
        case = ET.SubElement(suite, "testcase", classname="DeviceChecks", name=name)
        if needs_device and emulator_gone:
            ET.SubElement(case, "skipped", message=f"not run: {emulator_gone[:300]}")
            print(f"SKIP {name}: the emulator is gone")
            continue
        start = time.time()
        try:
            fn(apk=args.apk, shrunk_apk=args.shrunk_apk)
            print(f"PASS {name}")
        except CheckSkipped as e:
            ET.SubElement(case, "skipped", message=str(e))
            print(f"SKIP {name}: {e}")
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
    # Which checks passed and what they measured, readable through the API like the failures.
    passed = [c.get("name") for c in suite if len(c) == 0]
    summary = f"passed: {', '.join(passed) or 'none'}; " + "; ".join(f"{k}: {v}" for k, v in measurements.items() if "Permissions not named" not in k and "library" not in k)
    print(f"::notice title={args.suite} summary::{summary[:3500].replace('%', '%25').replace(chr(10), '%0A')}")
    suite.set("tests", str(len(suite)))
    suite.set("failures", str(failures))
    ET.ElementTree(suite).write(out / f"TEST-{args.suite}.xml", encoding="utf-8", xml_declaration=True)
    (out / f"measurements-{args.suite}.json").write_text(json.dumps(measurements, indent=2), encoding="utf-8")
    if not args.no_device and not emulator_gone:
        sh(f"pm uninstall {PKG}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
