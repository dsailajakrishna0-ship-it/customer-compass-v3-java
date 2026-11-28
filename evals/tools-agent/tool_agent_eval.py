#!/usr/bin/env python3
"""J7.5: does the model pick the right tool, and does the agent route and finish as expected, on the shared golden cases? Replayable.

  python3 evals/tools-agent/tool_agent_eval.py                    replay the recording: the table (no keys, no network)
  python3 evals/tools-agent/tool_agent_eval.py --check            replay and fail if a metric moved more than the tolerance from baseline.json
  python3 evals/tools-agent/tool_agent_eval.py --record           ask the running API for what is not recorded yet (needs keys; the owner approves this run)
  python3 evals/tools-agent/tool_agent_eval.py --write-baseline   save the replayed metrics as baseline.json (on purpose, after a recording)

The cases are evals/golden/tool_calls.json (10) and evals/golden/agent.json (10), the files the Python edition scores. --record sends
each tool case as a one-message conversation to POST /api/v1/chat/tools and keeps the tools the model called with their arguments
(response field toolsUsed); each agent case goes to POST /api/v1/agent/recommend and keeps route, status and the company's name.
Nothing is written by a recording: a write tool is only proposed in a one-message conversation, and recommend writes nothing.

Scoring: a tool case passes the tool check when the expected tool was called at least once (a case that expects no tool passes when
none was called); it passes the argument check when, besides that, one call to that tool has every expected argument as a
case-insensitive substring of the argument's text. An agent case checks the route, the status (when one is expected) and the
company (when one is expected). "write_without_confirm" counts calls of create_followup_task recorded with confirm true in these
one-message conversations: it must stay 0, because only the user's own later "yes" may set it.

Environment: API_URL (default http://localhost:8001); MODEL (asks a particular model and is recorded as a label); RECORDED_PATH and
BASELINE_PATH point at other files, for example to score a new recording against the saved baseline. Reruns of --record skip what
is recorded, so an interrupted run resumes.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).parent
GOLDEN = HERE.parent / "golden"
RECORDED = Path(os.environ.get("RECORDED_PATH", HERE / "recorded.json"))
BASELINE = Path(os.environ.get("BASELINE_PATH", HERE / "baseline.json"))
# Ten cases per suite: one flipped case moves a rate by 0.1, so 0.05 makes any single flip fail the check.
TOLERANCE = 0.05
METRICS = [
    "tool_name_accuracy",
    "tool_argument_accuracy",
    "no_tool_accuracy",
    "write_without_confirm",
    "route_accuracy",
    "status_accuracy",
    "company_accuracy",
]


# ---------------------------------------------------------------- scoring


def mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def contains(actual: object, wanted: str) -> bool:
    return wanted.lower() in str(actual).lower()


def score_tool_case(case: dict, run: dict) -> dict:
    calls = run.get("tools", [])
    expected = case["expect_tool"]
    row = {"id": case["id"], "expected": expected, "called": [c["tool"] for c in calls]}
    if expected is None:
        row["no_tool_ok"] = not calls
    else:
        matching = [c for c in calls if c["tool"] == expected]
        wanted = case.get("expect_args_contain", {})
        row["tool_ok"] = bool(matching)
        row["args_ok"] = any(all(contains(c.get("arguments", {}).get(k, ""), v) for k, v in wanted.items()) for c in matching)
    row["unconfirmed_writes"] = sum(
        1 for c in calls if c["tool"] == "create_followup_task" and c.get("arguments", {}).get("confirm") is True
    )
    return row


def score_agent_case(case: dict, run: dict) -> dict:
    row = {"id": case["id"], "route": run.get("route"), "status": run.get("status"), "company": run.get("company")}
    row["route_ok"] = run.get("route") == case["expect_route"]
    if case.get("expect_status") is not None:
        row["status_ok"] = run.get("status") == case["expect_status"]
    if case.get("expect_company") is not None:
        row["company_ok"] = (run.get("company") or "").lower() == case["expect_company"].lower()
    return row


def score(tool_cases: list[dict], agent_cases: list[dict], recorded: dict) -> dict:
    """Metrics and per-case rows for every case that has a recording."""
    tool_rows = [score_tool_case(c, recorded["tools"][c["id"]]) for c in tool_cases if c["id"] in recorded.get("tools", {})]
    agent_rows = [score_agent_case(c, recorded["agent"][c["id"]]) for c in agent_cases if c["id"] in recorded.get("agent", {})]
    with_tool = [r for r in tool_rows if "tool_ok" in r]
    without_tool = [r for r in tool_rows if "no_tool_ok" in r]
    with_status = [r for r in agent_rows if "status_ok" in r]
    with_company = [r for r in agent_rows if "company_ok" in r]
    return {
        "metrics": {
            "tool_cases": len(tool_rows),
            "agent_cases": len(agent_rows),
            "tool_name_accuracy": mean([float(r["tool_ok"]) for r in with_tool]),
            "tool_argument_accuracy": mean([float(r["tool_ok"] and r["args_ok"]) for r in with_tool]),
            "no_tool_accuracy": mean([float(r["no_tool_ok"]) for r in without_tool]),
            "write_without_confirm": sum(r["unconfirmed_writes"] for r in tool_rows),
            "route_accuracy": mean([float(r["route_ok"]) for r in agent_rows]),
            "status_accuracy": mean([float(r["status_ok"]) for r in with_status]),
            "company_accuracy": mean([float(r["company_ok"]) for r in with_company]),
        },
        "tool_cases": tool_rows,
        "agent_cases": agent_rows,
    }


def compare(baseline: dict, current: dict, tolerance: float = TOLERANCE) -> list[str]:
    """What moved: the case counts must match, each metric may move by the tolerance."""
    problems = []
    for key in ("tool_cases", "agent_cases"):
        if baseline["metrics"].get(key) != current["metrics"][key]:
            problems.append(f"{key}: baseline {baseline['metrics'].get(key)}, now {current['metrics'][key]}")
    for key in METRICS:
        before, now = baseline["metrics"][key], current["metrics"][key]
        if abs(before - now) > tolerance:
            problems.append(f"{key}: baseline {before:.4f}, now {now:.4f} (tolerance {tolerance})")
    return problems


# ---------------------------------------------------------------- recording


def api() -> str:
    return os.environ.get("API_URL", "http://localhost:8001").rstrip("/")


def post(case_id: str, path: str, body: dict) -> dict:
    if os.environ.get("MODEL"):
        body["model"] = os.environ["MODEL"]
    request = urllib.request.Request(f"{api()}{path}", json.dumps(body).encode(), {"Content-Type": "application/json"})
    for attempt in range(1, 7):
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as error:
            if error.code in (404, 422):
                raise SystemExit(f"{case_id}: the API refused the request (HTTP {error.code}): {error.read()[:200]!r}")
            wait = min(60, 10 * attempt)
            print(f"  {case_id}: HTTP {error.code}, waiting {wait}s (attempt {attempt})", file=sys.stderr)
            time.sleep(wait)
    raise SystemExit(f"{case_id}: the request kept failing")


def tool_run(case: dict) -> dict:
    answer = post(case["id"], "/api/v1/chat/tools", {"messages": [{"role": "user", "content": case["message"]}]})
    return {"tools": [{"tool": t["tool"], "arguments": t.get("arguments", {})} for t in answer.get("toolsUsed", [])]}


def agent_run(case: dict) -> dict:
    answer = post(case["id"], "/api/v1/agent/recommend", {"message": case["message"]})
    company = answer.get("company")
    name = company.get("name") if isinstance(company, dict) else None
    return {"route": answer.get("route"), "status": answer.get("status"), "company": name}


def record(tool_cases: list[dict], agent_cases: list[dict]) -> None:
    data = json.loads(RECORDED.read_text()) if RECORDED.exists() else {"meta": {}, "tools": {}, "agent": {}}
    data["meta"] = {"model": os.environ.get("MODEL", "the API's default chain"), "recorded": time.strftime("%Y-%m-%d")}
    for suite, cases, run in (("tools", tool_cases, tool_run), ("agent", agent_cases, agent_run)):
        for case in cases:
            if case["id"] in data[suite]:
                continue
            data[suite][case["id"]] = run(case)
            RECORDED.write_text(json.dumps(data, indent=1))
            print(f"{case['id']} recorded", file=sys.stderr)


# ---------------------------------------------------------------- output


def show(result: dict, meta: dict) -> None:
    m = result["metrics"]
    print(f"Model: {meta.get('model')}   recorded: {meta.get('recorded')}\n")
    print(f"tool cases: {m['tool_cases']}   agent cases: {m['agent_cases']}\n")
    print(f"{'right tool called':28}{m['tool_name_accuracy']:.3f}")
    print(f"{'right tool and arguments':28}{m['tool_argument_accuracy']:.3f}")
    print(f"{'no tool when none needed':28}{m['no_tool_accuracy']:.3f}")
    print(f"{'write without confirm':28}{m['write_without_confirm']}  (must be 0)")
    print(f"{'right route':28}{m['route_accuracy']:.3f}")
    print(f"{'right status':28}{m['status_accuracy']:.3f}")
    print(f"{'right company':28}{m['company_accuracy']:.3f}")
    bad_tools = [r["id"] for r in result["tool_cases"] if not (r.get("tool_ok", True) and r.get("args_ok", True) and r.get("no_tool_ok", True))]
    print(f"\ntool cases that missed ({len(bad_tools)}): " + (", ".join(bad_tools) or "none"))
    bad_agent = [r["id"] for r in result["agent_cases"] if not (r["route_ok"] and r.get("status_ok", True) and r.get("company_ok", True))]
    print(f"agent cases that missed ({len(bad_agent)}): " + (", ".join(bad_agent) or "none"))


def main(argv: list[str]) -> int:
    tool_cases = json.loads((GOLDEN / "tool_calls.json").read_text())
    agent_cases = json.loads((GOLDEN / "agent.json").read_text())
    if "--record" in argv:
        record(tool_cases, agent_cases)
    if not RECORDED.exists():
        print("Nothing recorded yet: the live recording needs keys and the owner's approval (see docs/stages/J7-evals-and-observability.md).", file=sys.stderr)
        return 2
    data = json.loads(RECORDED.read_text())
    result = score(tool_cases, agent_cases, data)
    if result["metrics"]["tool_cases"] != len(tool_cases) or result["metrics"]["agent_cases"] != len(agent_cases):
        print(
            f"Only {result['metrics']['tool_cases']} of {len(tool_cases)} tool cases and {result['metrics']['agent_cases']} of {len(agent_cases)} agent cases are recorded: finish with --record.",
            file=sys.stderr,
        )
        return 2
    show(result, data["meta"])
    if "--write-baseline" in argv:
        BASELINE.write_text(json.dumps({"meta": data["meta"], **result}, indent=1))
        print(f"\n{BASELINE.name} written")
    if "--check" in argv:
        if not BASELINE.exists():
            print("\nFAIL: no baseline.json yet (write one with --write-baseline after a recording)", file=sys.stderr)
            return 1
        problems = compare(json.loads(BASELINE.read_text()), result)
        if problems:
            print("\nFAIL: the metrics moved from baseline.json:\n  " + "\n  ".join(problems), file=sys.stderr)
            return 1
        print("\nOK: every metric is within the tolerance of baseline.json")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
