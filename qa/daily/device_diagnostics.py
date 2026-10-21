#!/usr/bin/env python3
"""Turns failing on-device tests into GitHub annotations with what the test saw.

Usage: device_diagnostics.py <junit-xml-dir> <logcat-file> <label>

For each failing test: the first line of the failure, the test's GuardianTrace lines (what
the brain remembered after each call and app open) and its GuardianDiag line (written by
DeviceRule when a test fails). Annotations can be read through the GitHub API
(check-runs/<job>/annotations) when logs and artifacts can't be downloaded. A step gets at
most 10 notices, so tests are packed several to a notice.
"""
import glob
import re
import sys
import xml.etree.ElementTree as ET

# A step shows at most 10 notices, and the suite script may already have used two.
MAX_NOTICES = 7
# GitHub keeps the first 4096 characters of an annotation; escaping adds a few.
MAX_CHARS = 3900


def failures(xml_dir):
    rows = []
    for f in sorted(glob.glob(f"{xml_dir}/**/*.xml", recursive=True)):
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            for bad in list(case.findall("failure")) + list(case.findall("error")):
                text = (bad.get("message") or bad.text or "").strip()
                first = (text.splitlines() or [""])[0]
                cls = case.get("classname", "").rsplit(".", 1)[-1]
                # Where in the test it failed: the first stack line in the test's own file.
                body = bad.text or ""
                where = re.search(rf"\(({re.escape(cls)}\.kt:\d+)\)", body) or re.search(r"\((\w+(?:Test|Rule)\.kt:\d+)\)", body)
                rows.append((cls, case.get("name", ""), (f"[{where.group(1)}] " if where else "") + first[:300]))
                break
    return rows


def per_test_lines(logcat):
    """GuardianTrace/GuardianDiag messages grouped by the test that was running."""
    lines = {}
    current = None
    try:
        log = open(logcat, errors="replace").read().splitlines()
    except OSError:
        return lines
    for line in log:
        m = re.search(r"TestRunner.*: started: (\w+)\(", line)
        if m:
            current = m.group(1)
            lines[current] = []
            continue
        if re.search(r"TestRunner.*: finished: ", line):
            current = None
            continue
        if current and ("GuardianDiag" in line or "GuardianTrace" in line):
            msg = line.split("): ", 1)[-1] if "): " in line else line
            tag = "diag" if "GuardianDiag" in line else "trace"
            lines[current].append(f"{tag}: {msg[:3500 if tag == 'diag' else 500]}")
    return lines


def escape(s):
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main():
    xml_dir, logcat, label = sys.argv[1], sys.argv[2], sys.argv[3]
    rows = failures(xml_dir)
    if not rows:
        return
    traces = per_test_lines(logcat)
    # GitHub cuts every annotation at 4096 characters, so each test gets a share of what the
    # notices can carry, and within it the screen dump (longest, least often needed) goes first.
    budget = max(600, min(MAX_CHARS, MAX_NOTICES * MAX_CHARS // len(rows)))
    blocks = []
    for cls, name, msg in rows:
        extra = traces.get(name, [])
        head = f"== {cls}.{name}: {msg}"[:400]
        # The latest traces, as many as fit in about a third of the share.
        trace, used = [], 0
        for l in reversed([l for l in extra if l.startswith("trace")]):
            l = "  " + l[:300]
            if used + len(l) + 1 > budget // 3:
                break
            trace.insert(0, l)
            used += len(l) + 1
        room = budget - len(head) - used - 4
        lines = [head]
        diag = [l for l in extra if l.startswith("diag")][-1:]
        if diag and room > 80:
            core, _, screen = diag[0].partition(" screen=")
            d = core[:room] if len(core) >= room else core + (" screen=" + screen)[: room - len(core)]
            lines.append("  " + d)
        lines += trace
        blocks.append("\n".join(lines)[:budget])
    notices, chunk = [], ""
    for b in blocks:
        if chunk and len(chunk) + len(b) + 1 > MAX_CHARS:
            notices.append(chunk)
            chunk = ""
        chunk += b + "\n"
    if chunk:
        notices.append(chunk)
    if len(notices) > MAX_NOTICES:
        notices = notices[: MAX_NOTICES - 1] + ["(more failures than fit; see the logcat artifact)\n" + notices[MAX_NOTICES - 1]]
    for i, n in enumerate(notices[:MAX_NOTICES], 1):
        print(f"::notice title={label} failures {i}/{min(len(notices), MAX_NOTICES)}::{escape(n)}")


if __name__ == "__main__":
    main()
