#!/usr/bin/env python3
"""
Keeps the "Daily test report" issue up to date: the body is always the latest report
(edited in place, which notifies nobody), and a comment is added only when something
newly fails or newly passes, so the issue's history reads as a changelog.

  GH_TOKEN=... python3 qa/daily/publish_issue.py --report qa-report --repo owner/name
"""
import argparse
import json
import os
import re
import urllib.request
from pathlib import Path

LABEL = "daily-test-report"
TITLE = "Daily test report"
STATE_RE = re.compile(r"<!-- daily-state (.*?) -->", re.S)


def api(method: str, path: str, repo: str, body: dict | None = None):
    req = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/{path}",
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "Authorization": f"Bearer {os.environ['GH_TOKEN']}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", required=True)
    ap.add_argument("--repo", required=True)
    args = ap.parse_args()

    report = (Path(args.report) / "REPORT.md").read_text(encoding="utf-8")
    results = json.loads((Path(args.report) / "results.json").read_text(encoding="utf-8"))
    failing = sorted(set(results["newFailures"]) | {k for v in results["knownFailing"].values() for k in v})
    state = {"date": results["date"], "sha": results["sha"], "failing": failing}
    body = report[:60_000] + f"\n\n<!-- daily-state {json.dumps(state)} -->\n"

    issues = api("GET", f"issues?labels={LABEL}&state=open&per_page=5", args.repo)
    if not issues:
        issue = api("POST", "issues", args.repo, {"title": TITLE, "body": body, "labels": [LABEL]})
        print(f"Created issue #{issue['number']}")
        return

    issue = issues[0]
    m = STATE_RE.search(issue.get("body") or "")
    before = set(json.loads(m.group(1)).get("failing", [])) if m else set()
    now = set(failing)
    api("PATCH", f"issues/{issue['number']}", args.repo, {"body": body})

    broke, fixed = sorted(now - before), sorted(before - now)
    if broke or fixed or results["missingSuites"]:
        lines = [f"**{results['date']}** · {results['verdict']} [Run]({results['runUrl']})", ""]
        if broke:
            lines += ["Newly failing:", *[f"- `{k.split('::', 1)[1]}` ({k.split('::', 1)[0]})" for k in broke[:40]], ""]
        if fixed:
            lines += ["Now passing:", *[f"- `{k.split('::', 1)[1]}` ({k.split('::', 1)[0]})" for k in fixed[:40]], ""]
        if results["missingSuites"]:
            lines += [f"No results from: {', '.join(results['missingSuites'])}", ""]
        api("POST", f"issues/{issue['number']}/comments", args.repo, {"body": "\n".join(lines)})
    print(f"Updated issue #{issue['number']}: {len(broke)} newly failing, {len(fixed)} now passing")


if __name__ == "__main__":
    main()
