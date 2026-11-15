#!/usr/bin/env python3
"""J7.4: the rule-based guard against the LLM classifier on the comparison set (74 prompts), replayable.

  python3 evals/guards/guard_eval.py                    replay the recording: the table (no keys, no network)
  python3 evals/guards/guard_eval.py --check            replay and fail if a count differs from baseline.json
  python3 evals/guards/guard_eval.py --record           ask the running API for what is not recorded yet
  python3 evals/guards/guard_eval.py --write-baseline   save the replayed counts as baseline.json (on purpose)

The prompts are the Python edition's Stage 5.3 set: evals/adversarial-prompts.json (30: 24 attacks, 6 benign) followed by the
"prompts" of evals/golden/guards-extra.json (44: 20 attacks, 24 benign), 74 in all (44 attacks, 30 benign). (The file's "pii" texts
are not used: the Java edition has no PII guard yet.) --record calls POST /api/v1/guard/check once per prompt; one call gives both
verdicts, so three strategies are scored from 74 calls: the rules, the classifier, and both together (flagged by either).

The API needs GUARD_CHECK_ENABLED=true (the route is off by default) and OPENROUTER_API_KEY; GUARD_MODEL picks the classifier
model. The in-app classifier fails open, so an unreachable model is recorded as "unavailable", never as SAFE: unavailable prompts
are retried, counted separately, and a baseline cannot be written while any remain. A verdict from a different model than the
configured one (the gateway fell back) is recorded too, and a baseline needs one answering model.

Environment: API_URL (default http://localhost:8001); RECORDED_PATH and BASELINE_PATH point at other files.
"""

import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).parent
EVALS = HERE.parent
BASE_SET = EVALS / "adversarial-prompts.json"
EXTRA_SET = EVALS / "golden" / "guards-extra.json"
RECORDED = Path(os.environ.get("RECORDED_PATH", HERE / "recorded.json"))
BASELINE = Path(os.environ.get("BASELINE_PATH", HERE / "baseline.json"))
STRATEGIES = ["rules", "classifier", "both"]
ATTEMPTS = 6


def load_cases() -> list[dict]:
    base = json.loads(BASE_SET.read_text())
    extra = json.loads(EXTRA_SET.read_text())["prompts"]
    cases = [{**c, "set": "adversarial-30"} for c in base] + [{**c, "set": "extra-44"} for c in extra]
    ids = [c["id"] for c in cases]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate case ids in the guard sets")
    return cases


# ---------------------------------------------------------------- scoring


def verdicts(run: dict) -> dict:
    """True (flagged), False (not flagged) or None (no verdict: the classifier was unavailable)."""
    rules = bool(run["rules"]["flagged"])
    outcome = run["classifier"]["outcome"]
    classifier = {"unsafe": True, "safe": False}.get(outcome)
    both = True if (rules or classifier) else (None if classifier is None else False)
    return {"rules": rules, "classifier": classifier, "both": both}


def score(cases: list[dict], recorded: dict) -> dict:
    out: dict = {"cases": 0, "attacks": 0, "benign": 0, "strategies": {}}
    scored = [(c, verdicts(recorded[c["id"]])) for c in cases if c["id"] in recorded]
    out["cases"] = len(scored)
    out["attacks"] = sum(1 for c, _ in scored if c["is_attack"])
    out["benign"] = len(scored) - out["attacks"]
    for name in STRATEGIES:
        attacks = [(c, v[name]) for c, v in scored if c["is_attack"]]
        benign = [(c, v[name]) for c, v in scored if not c["is_attack"]]
        row = {
            "detected": sum(1 for _, v in attacks if v is True),
            "missed": sorted(c["id"] for c, v in attacks if v is False),
            "attacks_unavailable": sum(1 for _, v in attacks if v is None),
            "false_positives": sum(1 for _, v in benign if v is True),
            "false_positive_ids": sorted(c["id"] for c, v in benign if v is True),
            "benign_unavailable": sum(1 for _, v in benign if v is None),
        }
        for label in ("adversarial-30", "extra-44"):
            sel = [(c, v) for c, v in attacks if c["set"] == label]
            row[f"detected_in_{label}"] = f"{sum(1 for _, v in sel if v is True)}/{len(sel)}"
        out["strategies"][name] = row
    latencies = [recorded[c["id"]]["classifier"].get("ms") for c in cases if c["id"] in recorded]
    latencies = [m for m in latencies if isinstance(m, (int, float))]
    out["classifier_median_ms"] = round(statistics.median(latencies)) if latencies else None
    out["answering_models"] = sorted({recorded[c["id"]]["classifier"].get("model") for c in cases if c["id"] in recorded and recorded[c["id"]]["classifier"].get("model")})
    out["fallbacks"] = sum(1 for c in cases if c["id"] in recorded and recorded[c["id"]]["classifier"].get("fallbackUsed"))
    out["classifier_unavailable"] = sum(1 for c in cases if c["id"] in recorded and recorded[c["id"]]["classifier"]["outcome"] == "unavailable")
    return out


STABLE_STRATEGY_KEYS = ["detected", "missed", "attacks_unavailable", "false_positives", "false_positive_ids", "benign_unavailable", "detected_in_adversarial-30", "detected_in_extra-44"]


def comparable(result: dict) -> dict:
    return {
        "cases": result["cases"],
        "attacks": result["attacks"],
        "benign": result["benign"],
        "strategies": {n: {k: result["strategies"][n][k] for k in STABLE_STRATEGY_KEYS} for n in STRATEGIES},
    }


def differences(baseline: dict, current: dict) -> list[str]:
    problems = []
    for key in ("cases", "attacks", "benign"):
        if baseline.get(key) != current[key]:
            problems.append(f"{key}: baseline {baseline.get(key)}, now {current[key]}")
    for name in STRATEGIES:
        for key in STABLE_STRATEGY_KEYS:
            before = baseline.get("strategies", {}).get(name, {}).get(key)
            now = current["strategies"][name][key]
            if before != now:
                problems.append(f"{name}.{key}: baseline {before}, now {now}")
    return problems


def baseline_problems(result: dict, total: int) -> list[str]:
    """Why this recording must not become a baseline."""
    problems = []
    if result["cases"] != total:
        problems.append(f"only {result['cases']} of {total} prompts are recorded")
    if result["classifier_unavailable"]:
        problems.append(f"{result['classifier_unavailable']} prompt(s) have no classifier verdict (unavailable); rerun --record to retry them")
    if len(result["answering_models"]) > 1:
        problems.append(f"more than one model answered: {', '.join(result['answering_models'])}")
    if result["fallbacks"]:
        problems.append(f"{result['fallbacks']} verdict(s) came from a fallback model, not the configured one")
    return problems


# ---------------------------------------------------------------- recording


def api() -> str:
    return os.environ.get("API_URL", "http://localhost:8001").rstrip("/")


def check_once(text: str) -> tuple[dict, float]:
    request = urllib.request.Request(f"{api()}/api/v1/guard/check", json.dumps({"text": text}).encode(), {"Content-Type": "application/json"})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            body = json.loads(response.read())
    except urllib.error.HTTPError as error:
        if error.code in (404, 422):
            raise SystemExit(f"the API refused the check (HTTP {error.code}): {error.read()[:200]!r}. Start it with GUARD_CHECK_ENABLED=true.")
        raise
    return body, (time.monotonic() - started) * 1000


def record(cases: list[dict], wait=time.sleep) -> int:
    data = json.loads(RECORDED.read_text()) if RECORDED.exists() else {"meta": {}, "cases": {}}
    for case in cases:
        done = data["cases"].get(case["id"])
        if done and done["classifier"]["outcome"] != "unavailable":
            continue
        for attempt in range(1, ATTEMPTS + 1):
            try:
                body, ms = check_once(case["prompt"])
            except urllib.error.HTTPError as error:
                body, ms = None, 0
                print(f"  {case['id']}: HTTP {error.code}", file=sys.stderr)
            if body is not None:
                c = body["classifier"]
                data["meta"] = {
                    "configured_model": body.get("configuredModel") or "(the default chat model)",
                    "recorded": time.strftime("%Y-%m-%d"),
                    "prompts": "evals/adversarial-prompts.json + evals/golden/guards-extra.json (prompts)",
                }
                data["cases"][case["id"]] = {
                    "rules": {"flagged": body["rules"]["flagged"], "patterns": body["rules"]["patterns"]},
                    "classifier": {"outcome": c["outcome"], "word": c.get("word"), "model": c.get("model"), "fallbackUsed": bool(c.get("fallbackUsed")), "failure": c.get("failure"), "ms": round(ms)},
                }
                if c["outcome"] != "unavailable":
                    break
            if attempt < ATTEMPTS:
                pause = min(60, 10 * attempt)
                print(f"  {case['id']}: no classifier verdict, waiting {pause}s (attempt {attempt})", file=sys.stderr)
                wait(pause)
        RECORDED.write_text(json.dumps(data, indent=1))
        print(f"{case['id']} {data['cases'][case['id']]['classifier']['outcome']}", file=sys.stderr)
    left = sum(1 for c in cases if data["cases"].get(c["id"], {}).get("classifier", {}).get("outcome") == "unavailable")
    if left:
        print(f"\n{left} prompt(s) still have no classifier verdict: run --record again later.", file=sys.stderr)
        return 2
    return 0


# ---------------------------------------------------------------- output


def show(result: dict, meta: dict) -> None:
    print(f"Classifier model: {meta.get('configured_model')}   recorded: {meta.get('recorded')}   prompts: {result['cases']} ({result['attacks']} attacks, {result['benign']} benign)\n")
    print(f"{'':34}{'rules':>10}{'classifier':>12}{'both':>8}")

    def row(label, fn):
        print(f"{label:34}" + "".join(f"{fn(result['strategies'][n]):>{w}}" for n, w in zip(STRATEGIES, (10, 12, 8))))

    row("attacks detected", lambda s: f"{s['detected']}/{result['attacks']}")
    row("  in the 30 adversarial prompts", lambda s: s["detected_in_adversarial-30"])
    row("  in the 44 added prompts", lambda s: s["detected_in_extra-44"])
    row("benign flagged (false positives)", lambda s: f"{s['false_positives']}/{result['benign']}")
    row("attacks with no verdict", lambda s: s["attacks_unavailable"])
    row("benign with no verdict", lambda s: s["benign_unavailable"])
    print(f"\nclassifier: {result['classifier_unavailable']} unavailable, {result['fallbacks']} from a fallback model, answering models: {', '.join(result['answering_models']) or 'none'}, median {result['classifier_median_ms']} ms")
    for name in STRATEGIES:
        s = result["strategies"][name]
        print(f"{name}: missed {len(s['missed'])}: {', '.join(s['missed']) or 'none'}; false positives: {', '.join(s['false_positive_ids']) or 'none'}")


def main(argv: list[str]) -> int:
    cases = load_cases()
    if "--record" in argv:
        record(cases)
    if not RECORDED.exists():
        print("Nothing recorded yet: run with --record against a running API (GUARD_CHECK_ENABLED=true; see docs/stages/J7-evals-and-observability.md).", file=sys.stderr)
        return 2
    data = json.loads(RECORDED.read_text())
    result = score(cases, data["cases"])
    show(result, data["meta"])
    if "--write-baseline" in argv:
        problems = baseline_problems(result, len(cases))
        if problems:
            print("\nNo baseline written:\n  " + "\n  ".join(problems), file=sys.stderr)
            return 2
        BASELINE.write_text(json.dumps({"meta": data["meta"], **comparable(result)}, indent=1))
        print(f"\n{BASELINE.name} written")
    if "--check" in argv:
        if not BASELINE.exists():
            print("\nFAIL: no baseline.json yet (write one with --write-baseline after a recording)", file=sys.stderr)
            return 1
        problems = differences(json.loads(BASELINE.read_text()), comparable(result))
        if problems or result["cases"] != len(cases):
            print("\nFAIL: the counts moved from baseline.json:\n  " + "\n  ".join(problems or ["not every prompt is recorded"]), file=sys.stderr)
            return 1
        print("\nOK: every count matches baseline.json")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
