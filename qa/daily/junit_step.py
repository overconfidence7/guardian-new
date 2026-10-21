#!/usr/bin/env python3
"""
Runs one command and records it as a single JUnit test case, so whole-tool checks
(lint, screenshot verification, dependency audit, building the stand-in apps) show up
in the daily report next to individual tests.

  python3 qa/daily/junit_step.py --name gm_base_003_lintClean --out qa-results/android -- ./gradlew lintDebug

Always exits 0 so later steps still run; the report decides what failed.
"""
import argparse
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("--name", required=True)
ap.add_argument("--out", required=True)
ap.add_argument("--cwd", default=".")
ap.add_argument("command", nargs=argparse.REMAINDER)
args = ap.parse_args()
command = args.command[1:] if args.command[:1] == ["--"] else args.command

start = time.time()
proc = subprocess.run(command, cwd=args.cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
sys.stdout.write(proc.stdout)
tail = "\n".join(proc.stdout.splitlines()[-60:])

suite = ET.Element("testsuite", name="steps", tests="1", failures="0" if proc.returncode == 0 else "1")
case = ET.SubElement(suite, "testcase", classname="Steps", name=args.name, time=f"{time.time() - start:.1f}")
if proc.returncode != 0:
    ET.SubElement(case, "failure", message=f"`{' '.join(command)}` exited with {proc.returncode}").text = tail
out = Path(args.out)
out.mkdir(parents=True, exist_ok=True)
ET.ElementTree(suite).write(out / f"TEST-step-{args.name}.xml", encoding="utf-8", xml_declaration=True)
print(f"{'PASS' if proc.returncode == 0 else 'FAIL'} {args.name}")
