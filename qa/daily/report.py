#!/usr/bin/env python3
"""
Turns every JUnit XML file from the daily run into one report a person can read in a
minute, and a results.json the daily Claude check reads.

  python3 qa/daily/report.py --results qa-results --out qa-report [--run-url URL --sha SHA --repo owner/name]

Tests are tied to the case catalog (qa/antigravity/cases/*.md) by the case id in their
name: `gm_pause_004_...` in Kotlin, "GM-PAUSE-004 ..." in JS. qa/daily/known-bugs.json
lists failures we already know about, so the report can tell new breakage from old.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import urllib.request
import xml.etree.ElementTree as ET
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CASES_DIR = ROOT / "qa/antigravity/cases"
DAILY = ROOT / "qa/daily"
CASE_RE = re.compile(r"gm[-_]([a-z0-9]+)[-_](\d{3})", re.I)
HEADING_RE = re.compile(r"^### (GM-[A-Z0-9]+-\d{3}) · (.+?)\s*((?:`[^`]+`\s*)*)$")

# Suites the daily run must produce. A suite with no results at all is a failure in itself
# (usually a compile error), and the baseline cases follow from them.
EXPECTED_SUITES = {
    "core": "GM-BASE-001",
    "android": "GM-BASE-002",
    "device-api29": "GM-BASE-004",
    "device-api33": "GM-BASE-004",
    "device-api36": "GM-BASE-004",
    "backend": "GM-BASE-005",
    "web": "GM-BASE-006",
    "web-e2e": "GM-BASE-006",
}


@dataclass
class Case:
    id: str
    title: str
    priority: str
    tags: list[str]
    area: str
    order: int = 0


@dataclass
class Result:
    suite: str
    classname: str
    name: str
    status: str  # passed | failed | skipped
    message: str = ""
    cases: list[str] = field(default_factory=list)
    known: str | None = None

    @property
    def key(self) -> str:
        return f"{self.suite}::{self.classname}.{self.name}"


def load_catalog() -> dict[str, Case]:
    catalog = {}
    for order, f in enumerate(sorted(CASES_DIR.glob("*.md"))):
        area = f.stem.split("-", 1)[1].replace("-", " ") if "-" in f.stem else f.stem
        for line in f.read_text(encoding="utf-8").splitlines():
            m = HEADING_RE.match(line.strip())
            if not m:
                continue
            labels = re.findall(r"`([^`]+)`", m.group(3))
            pri = next((t for t in labels if re.fullmatch(r"P\d", t)), "P?")
            tags = [t.strip("[]") for t in labels if t.startswith("[")]
            catalog[m.group(1)] = Case(m.group(1), m.group(2), pri, tags, area, order)
    return catalog


def suite_of(path: Path, results_root: Path) -> str:
    rel = path.relative_to(results_root).parts
    # download-artifact puts each artifact in its own folder: results-core/core/TEST-x.xml
    for part in rel:
        if part.startswith("results-"):
            continue
        return part
    return rel[0]


def parse_results(results_root: Path) -> list[Result]:
    out: list[Result] = []
    for f in sorted(results_root.rglob("*.xml")):
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        if root.tag not in ("testsuite", "testsuites"):
            continue
        suite = suite_of(f, results_root)
        for tc in root.iter("testcase"):
            bad = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
            if bad is not None:
                status = "failed"
                message = (bad.get("message") or (bad.text or "")).strip().splitlines()[0:1]
                message = message[0][:240] if message else ""
            elif tc.find("skipped") is not None:
                status, message = "skipped", ""
            else:
                status, message = "passed", ""
            name, cls = tc.get("name") or "", tc.get("classname") or ""
            ids = sorted({f"GM-{a.upper()}-{n}" for a, n in CASE_RE.findall(f"{cls} {name}")})
            out.append(Result(suite, cls, name, status, message, ids))
    return out


def load_known() -> dict[str, dict]:
    path = DAILY / "known-bugs.json"
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return {k: v for k, v in data.items() if not k.startswith("_")}


def match_known(r: Result, known: dict[str, dict]) -> str | None:
    for bug_id, bug in known.items():
        for pattern in bug.get("tests", []):
            if pattern and (pattern in r.name or pattern in f"{r.classname}.{r.name}"):
                return bug_id
    return None


def release_check(repo: str, sha: str) -> Result:
    """GM-BASE-008: the testing release exists, isn't a draft, and points at this commit."""
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    r = Result("report", "Release", "gm_base_008_testingReleaseMatchesMain", "passed", cases=["GM-BASE-008"])

    def get(url: str) -> dict:
        req = urllib.request.Request(f"https://api.github.com/repos/{repo}/{url}", headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"})
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.load(resp)

    try:
        rel = get("releases/tags/testing")
        ref = get("git/ref/tags/testing")
        problems = []
        if rel.get("draft"):
            problems.append("the testing release is a draft")
        if not any(a.get("name") == "guardian-mode-test.apk" for a in rel.get("assets", [])):
            problems.append("guardian-mode-test.apk is missing from the release")
        if ref.get("object", {}).get("sha") != sha:
            problems.append(f"the testing tag points at {ref.get('object', {}).get('sha', '?')[:7]}, not {sha[:7]}")
        if problems:
            r.status, r.message = "failed", "; ".join(problems)
    except Exception as e:  # noqa: BLE001
        r.status, r.message = "failed", f"couldn't read the testing release: {e}"
    return r


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--run-url", default="")
    ap.add_argument("--sha", default="")
    ap.add_argument("--repo", default="")
    ap.add_argument("--check-release", action="store_true", help="GM-BASE-008; only meaningful on main")
    args = ap.parse_args()

    results_root = Path(args.results)
    catalog = load_catalog()
    known = load_known()
    results = parse_results(results_root) if results_root.exists() else []
    if args.check_release and args.repo and args.sha:
        results.append(release_check(args.repo, args.sha))
    for r in results:
        if r.status == "failed":
            r.known = match_known(r, known)

    suites = defaultdict(list)
    for r in results:
        suites[r.suite].append(r)
    missing = [s for s in EXPECTED_SUITES if s not in suites]

    # Baseline cases follow from whole suites: green when the suite ran with no new failures.
    for s, cid in EXPECTED_SUITES.items():
        problem = "no results" if s in missing else (
            "new failures" if any(r.status == "failed" and not match_known(r, known) for r in suites[s]) else "")
        results.append(Result("report", "Baseline", f"{cid.lower().replace('-', '_')}_{s.replace('-', '_')}",
                              "failed" if problem else "passed", f"{s}: {problem}" if problem else "", [cid]))

    measurements: dict[str, str] = {}
    for f in sorted(results_root.rglob("measurements-*.json")) if results_root.exists() else []:
        suite = suite_of(f, results_root)
        for k, v in json.loads(f.read_text(encoding="utf-8")).items():
            measurements[f"{k} ({suite})"] = v

    failed = [r for r in results if r.status == "failed"]
    # Baseline rows only restate suite-level problems listed below; keep them out of the table.
    new_failures = [r for r in failed if not r.known and r.classname != "Baseline"]
    known_failing = defaultdict(list)
    for r in failed:
        if r.known:
            known_failing[r.known].append(r)
    # A known bug whose tests ran and all passed is probably fixed.
    known_fixed = []
    for bug_id, bug in known.items():
        ran = [r for r in results if r.status in ("passed", "failed") and match_known(r, {bug_id: bug})]
        if ran and all(r.status == "passed" for r in ran):
            known_fixed.append(bug_id)

    # Per-case status.
    by_case: dict[str, list[Result]] = defaultdict(list)
    for r in results:
        for c in r.cases:
            by_case[c].append(r)
    person_only = json.loads((DAILY / "catalog.json").read_text(encoding="utf-8")).get("needsAPerson", {}) if (DAILY / "catalog.json").exists() else {}

    def case_status(cid: str) -> str:
        rs = [r for r in by_case.get(cid, []) if r.status != "skipped"]
        if not rs:
            return "person" if cid in person_only else "not automated"
        if any(r.status == "failed" and not r.known for r in rs):
            return "failing"
        if any(r.status == "failed" for r in rs):
            return "known bug"
        return "passing"

    areas = defaultdict(lambda: defaultdict(int))
    p0_gaps = []
    for cid, case in catalog.items():
        st = case_status(cid)
        a = areas[case.area]
        a["total"] += 1
        a[st] += 1
        if st == "not automated" and case.priority == "P0":
            p0_gaps.append(case)
    unknown_ids = sorted(c for c in by_case if c not in catalog)

    total = len(catalog)
    automated = sum(1 for c in catalog if case_status(c) not in ("not automated", "person"))
    ran = [r for r in results if r.status != "skipped"]
    verdict = (
        "All green." if not new_failures and not missing and not known_failing
        else f"{len(new_failures)} new failure{'s' if len(new_failures) != 1 else ''}, {len(known_failing)} known bug{'s' if len(known_failing) != 1 else ''} still open"
        + (f", {len(missing)} suite{'s' if len(missing) != 1 else ''} produced no results" if missing else "")
        + "."
    )

    today = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d")
    lines = [f"# Daily test report, {today}", ""]
    meta = []
    if args.sha:
        meta.append(f"commit `{args.sha[:7]}`")
    if args.run_url:
        meta.append(f"[workflow run]({args.run_url})")
    if meta:
        lines += [" · ".join(meta), ""]
    lines += [f"**{verdict}** {len(ran)} tests ran, {sum(r.status == 'passed' for r in ran)} passed. "
              f"{automated} of {total} catalog cases are automated ({automated * 100 // max(total, 1)}%).", ""]

    if new_failures:
        lines += ["## New failures", "", "| Test | Cases | Suite | What failed |", "|---|---|---|---|"]
        for r in sorted(new_failures, key=lambda r: (r.suite, r.classname, r.name)):
            lines.append(f"| `{r.classname.split('.')[-1]}.{r.name}` | {', '.join(r.cases) or '-'} | {r.suite} | {r.message.replace('|', '/')} |")
        lines.append("")
    if missing:
        lines += ["## Suites with no results", "", *[f"- **{s}**: nothing was reported, usually a build or compile failure. Check the job log." for s in missing], ""]
    if known_failing or known_fixed:
        lines += ["## Known bugs", "", "| Bug | What | Failing tests | Owner |", "|---|---|---|---|"]
        for bug_id in sorted(set(known_failing) | set(known_fixed)):
            bug = known.get(bug_id, {})
            n = "now passing: remove from known-bugs.json" if bug_id in known_fixed else str(len(known_failing[bug_id]))
            lines.append(f"| {bug_id} | {bug.get('title', '')} | {n} | {bug.get('owner', '')} |")
        lines.append("")

    lines += ["## Coverage by area", "", "| Area | Cases | Passing | Known bug | Failing | Needs a person | Not automated yet |", "|---|---|---|---|---|---|---|"]
    for area in sorted(areas, key=lambda a: min(c.order for c in catalog.values() if c.area == a)):
        a = areas[area]
        lines.append(f"| {area} | {a['total']} | {a['passing']} | {a['known bug']} | {a['failing']} | {a['person']} | {a['not automated']} |")
    lines.append(f"| **all** | {total} | {sum(a['passing'] for a in areas.values())} | {sum(a['known bug'] for a in areas.values())} | "
                 f"{sum(a['failing'] for a in areas.values())} | {sum(a['person'] for a in areas.values())} | {sum(a['not automated'] for a in areas.values())} |")
    lines.append("")
    if p0_gaps:
        lines += ["## P0 cases not automated yet", "", *[f"- {c.id} · {c.title}" for c in p0_gaps], ""]
    if measurements:
        lines += ["## Measurements", "", "| What | Value |", "|---|---|", *[f"| {k} | {v} |" for k, v in measurements.items()], ""]
    if unknown_ids:
        lines += ["## Test names with case ids that aren't in the catalog", "", ", ".join(unknown_ids), ""]

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "REPORT.md").write_text("\n".join(lines), encoding="utf-8")
    (out / "results.json").write_text(json.dumps({
        "date": today,
        "sha": args.sha,
        "runUrl": args.run_url,
        "verdict": verdict,
        "newFailures": [r.key for r in new_failures],
        "knownFailing": {k: [r.key for r in v] for k, v in known_failing.items()},
        "knownFixed": known_fixed,
        "missingSuites": missing,
        "coverage": {"cases": total, "automated": automated},
        "cases": {cid: case_status(cid) for cid in catalog},
        "failures": [{"key": r.key, "suite": r.suite, "test": f"{r.classname}.{r.name}", "cases": r.cases, "known": r.known, "message": r.message} for r in failed],
    }, indent=2), encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
