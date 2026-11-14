#!/usr/bin/env python3
"""J7.2: how well the CRM documents are retrieved for the shared 42 golden questions (recall@1/3/5, MRR), replayable.

  python3 evals/retrieval/retrieval_eval.py                    replay the recording: the table (no keys, no network)
  python3 evals/retrieval/retrieval_eval.py --check            replay and fail if a metric moved more than the tolerance from baseline.json
  python3 evals/retrieval/retrieval_eval.py --record           ask the running API for what is not recorded yet (needs GOOGLE_API_KEY in the API)
  python3 evals/retrieval/retrieval_eval.py --write-baseline   save the replayed metrics as baseline.json (on purpose)

The questions are evals/golden/retrieval.json, the same file the Python edition scores (36 answerable, 6 that the documents do
not answer). For each one --record calls POST /api/v1/knowledge-bases/crm/search (topK 10, with the question's company when it
has one) and keeps the document names in rank order; several chunks of one document count once, at its best rank. Scoring is the
Python edition's (evals/metrics.py there): recall@k is the share of the relevant documents inside the first k, MRR is 1 over the
rank of the first relevant one, and an unanswerable question is "declined" when nothing at all comes back.

Environment: API_URL (default http://localhost:8001); MIN_SIMILARITY, a floor for this run instead of the one the API applies
(0.45 reproduces the Python edition's saved baseline, taken before its floor moved to 0.60); RECORDED_PATH and BASELINE_PATH
point at other files, for example to score a new recording against the saved baseline; EMBEDDING_MODEL and RETRIEVAL_MODE are
only recorded as labels. Reruns of --record skip what is recorded, so an interrupted run resumes.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).parent
GOLDEN = HERE.parent / "golden" / "retrieval.json"
RECORDED = Path(os.environ.get("RECORDED_PATH", HERE / "recorded.json"))
BASELINE = Path(os.environ.get("BASELINE_PATH", HERE / "baseline.json"))
FETCH = 10  # chunks asked for per question; documents are taken from these, as in the Python edition
TOLERANCE = 0.02  # the Python edition's tolerance for the retrieval suite
METRICS = ["recall_at_1", "recall_at_3", "recall_at_5", "mrr", "unanswerable_decline_rate"]


# ---------------------------------------------------------------- scoring (the Python edition's functions)


def recall_at_k(ranked: list[str], relevant: set[str], k: int) -> float:
    if not relevant:
        raise ValueError("recall needs at least one relevant document")
    return len(set(ranked[:k]) & relevant) / len(relevant)


def reciprocal_rank(ranked: list[str], relevant: set[str]) -> float:
    for position, doc in enumerate(ranked, start=1):
        if doc in relevant:
            return 1 / position
    return 0.0


def dedupe_keep_order(items: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for item in items:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out


def mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def score(cases: list[dict], recorded: dict) -> dict:
    """Metrics and per-question rows for every question that has a recording."""
    rows = []
    for case in cases:
        run = recorded.get(case["id"])
        if run is None:
            continue
        ranked = run["ranked"]
        relevant = set(case["relevant"])
        row = {"id": case["id"], "ranked": ranked, "relevant": sorted(relevant)}
        if relevant:
            row.update(
                recall_at_1=recall_at_k(ranked, relevant, 1),
                recall_at_3=recall_at_k(ranked, relevant, 3),
                recall_at_5=recall_at_k(ranked, relevant, 5),
                reciprocal_rank=reciprocal_rank(ranked, relevant),
            )
        else:
            row["declined"] = not ranked
        rows.append(row)
    answerable = [r for r in rows if "recall_at_1" in r]
    unanswerable = [r for r in rows if "declined" in r]
    return {
        "metrics": {
            "cases": len(rows),
            "answerable": len(answerable),
            "unanswerable": len(unanswerable),
            "recall_at_1": mean([r["recall_at_1"] for r in answerable]),
            "recall_at_3": mean([r["recall_at_3"] for r in answerable]),
            "recall_at_5": mean([r["recall_at_5"] for r in answerable]),
            "mrr": mean([r["reciprocal_rank"] for r in answerable]),
            "unanswerable_decline_rate": mean([1.0 if r["declined"] else 0.0 for r in unanswerable]),
        },
        "cases": rows,
    }


def compare(baseline: dict, current: dict, tolerance: float = TOLERANCE) -> list[str]:
    """What moved: the case counts must match, each metric may move by the tolerance."""
    problems = []
    for key in ("cases", "answerable", "unanswerable"):
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


def search(case: dict, floor: float | None) -> list[str]:
    body: dict = {"query": case["question"], "topK": FETCH}
    if case.get("company"):
        body["company"] = case["company"]
    if floor is not None:
        body["minSimilarity"] = floor
    request = urllib.request.Request(
        f"{api()}/api/v1/knowledge-bases/crm/search", json.dumps(body).encode(), {"Content-Type": "application/json"}
    )
    for attempt in range(1, 7):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                results = json.loads(response.read())["results"]
            return dedupe_keep_order([Path(r["sourcePath"]).name for r in results])
        except urllib.error.HTTPError as error:
            if error.code in (404, 422):
                raise SystemExit(f"{case['id']}: the API refused the question (HTTP {error.code}): {error.read()[:200]!r}")
            wait = min(60, 10 * attempt)
            print(f"  {case['id']}: HTTP {error.code}, waiting {wait}s (attempt {attempt})", file=sys.stderr)
            time.sleep(wait)
    raise SystemExit(f"{case['id']}: the search kept failing")


def record(cases: list[dict]) -> None:
    data = json.loads(RECORDED.read_text()) if RECORDED.exists() else {"meta": {}, "cases": {}}
    floor = float(os.environ["MIN_SIMILARITY"]) if os.environ.get("MIN_SIMILARITY") else None
    data["meta"] = {
        "embedding_model": os.environ.get("EMBEDDING_MODEL", "gemini-embedding-001 (the default)"),
        "retrieval_mode": os.environ.get("RETRIEVAL_MODE", "dense (the default)"),
        "floor": floor if floor is not None else "the floor the API applies (retrieval.min-similarity)",
        "fetch": FETCH,
        "recorded": time.strftime("%Y-%m-%d"),
    }
    for case in cases:
        if case["id"] in data["cases"]:
            continue
        data["cases"][case["id"]] = {"ranked": search(case, floor)}
        RECORDED.write_text(json.dumps(data, indent=1))
        print(f"{case['id']} {len(data['cases'][case['id']]['ranked'])} document(s)", file=sys.stderr)


# ---------------------------------------------------------------- output


def show(result: dict, meta: dict) -> None:
    m = result["metrics"]
    print(f"Embeddings: {meta.get('embedding_model')}   mode: {meta.get('retrieval_mode')}   floor: {meta.get('floor')}   recorded: {meta.get('recorded')}\n")
    print(f"questions: {m['cases']} ({m['answerable']} answerable, {m['unanswerable']} not answered by the documents)\n")
    print(f"{'recall@1':28}{m['recall_at_1']:.3f}")
    print(f"{'recall@3':28}{m['recall_at_3']:.3f}")
    print(f"{'recall@5':28}{m['recall_at_5']:.3f}")
    print(f"{'MRR':28}{m['mrr']:.3f}")
    print(f"{'unanswerable declined':28}{m['unanswerable_decline_rate']:.3f}  (nothing retrieved)")
    missed = [r for r in result["cases"] if "recall_at_5" in r and r["recall_at_5"] < 1]
    print(f"\nanswerable questions with a relevant document outside the first 5 ({len(missed)}): " + (", ".join(r["id"] for r in missed) or "none"))
    wrong = [r for r in result["cases"] if "reciprocal_rank" in r and r["reciprocal_rank"] < 1]
    print(f"answerable questions whose first relevant document is not first ({len(wrong)}): " + (", ".join(r["id"] for r in wrong) or "none"))
    answered = [r["id"] for r in result["cases"] if r.get("declined") is False]
    print(f"unanswerable questions that still retrieved something ({len(answered)}): " + (", ".join(answered) or "none"))


def main(argv: list[str]) -> int:
    cases = json.loads(GOLDEN.read_text())
    if "--record" in argv:
        record(cases)
    if not RECORDED.exists():
        print("Nothing recorded yet: run with --record against a running API (see docs/stages/J7-evals-and-observability.md).", file=sys.stderr)
        return 2
    data = json.loads(RECORDED.read_text())
    result = score(cases, data["cases"])
    if result["metrics"]["cases"] != len(cases):
        print(f"Only {result['metrics']['cases']} of {len(cases)} questions are recorded: finish with --record.", file=sys.stderr)
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
