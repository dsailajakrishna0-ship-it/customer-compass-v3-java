#!/usr/bin/env python3
"""E1.4: choose a similarity floor for each knowledge base from what its own questions score.

  python3 evals/knowledge-bases/calibrate_floor.py            replay the recorded scores (no keys, no network)
  python3 evals/knowledge-bases/calibrate_floor.py --record   ask the running API for the scores (floor switched off)

QUESTIONS_PATH and RECORDED_PATH point it at another question set and recording (heldout.json: twelve questions the
floors were not calibrated on, to check that they hold up); CRM_FLOOR and AGRI_FLOOR are the floors to check.

A floor is the cosine similarity a chunk needs to be cited. One global floor (0.45) was tuned on the CRM documents;
this measures, for each knowledge base, the best score a relevant question reaches against its relevant documents
and the best score an unrelated question reaches, from the same labelled questions the strategy comparison uses
(questions.json). For a knowledge base:

  relevant   = its own questions, and the questions that need both, scored by the best chunk from a relevant document
  unrelated  = the questions of the other corpus and the four that belong to neither, scored by their best chunk

A good floor sits above the unrelated scores and below the relevant ones; where they overlap, the table shows what
each candidate floor keeps and lets through. --record needs the API running (API_URL, default http://localhost:8001)
with both knowledge bases ingested; EMBEDDING_MODEL is recorded as a label. Reruns skip what is recorded.
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
QUESTIONS = json.loads(Path(os.environ.get("QUESTIONS_PATH", HERE / "questions.json")).read_text())
FLOORS = {"crm": float(os.environ.get("CRM_FLOOR", "0.60")), "agri-supply": float(os.environ.get("AGRI_FLOOR", "0.64"))}
RECORDED = Path(os.environ.get("RECORDED_PATH", HERE / "calibration.json"))
KNOWLEDGE_BASES = {"crm": "crm", "agri-supply": "agri"}  # id -> the kind of question that belongs to it
TOP_K = 10


def api() -> str:
    return os.environ.get("API_URL", "http://localhost:8001").rstrip("/")


def search(kb: str, question: str) -> list[dict]:
    request = urllib.request.Request(
        f"{api()}/api/v1/knowledge-bases/{kb}/search",
        json.dumps({"query": question, "topK": TOP_K, "minSimilarity": 0}).encode(),
        {"Content-Type": "application/json"},
    )
    for attempt in range(1, 7):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return json.loads(response.read())["results"]
        except urllib.error.HTTPError as error:
            wait = min(60, 10 * attempt)
            print(f"  {kb}: HTTP {error.code}, waiting {wait}s (attempt {attempt})", file=sys.stderr)
            time.sleep(wait)
    raise SystemExit(f"{kb}: the search kept failing")


def load() -> dict:
    return json.loads(RECORDED.read_text()) if RECORDED.exists() else {"meta": {}, "scores": {}}


def record() -> None:
    data = load()
    data["meta"] = {
        "embedding_model": os.environ.get("EMBEDDING_MODEL", "gemini-embedding-001 (the default)"),
        "recorded": time.strftime("%Y-%m-%d"),
    }
    for kb in KNOWLEDGE_BASES:
        scores = data["scores"].setdefault(kb, {})
        for q in QUESTIONS:
            if q["id"] in scores:
                continue
            results = search(kb, q["question"])
            scores[q["id"]] = [{"doc": Path(r["sourcePath"]).name, "similarity": round(r["similarity"], 4)} for r in results]
            RECORDED.write_text(json.dumps(data, indent=1))
            print(f"{kb} {q['id']} {len(results)} result(s)", file=sys.stderr)


def split(kb: str, data: dict) -> tuple[list[float], list[float]]:
    """(relevant, unrelated): the best score of each question against this knowledge base."""
    own_kind = KNOWLEDGE_BASES[kb]
    relevant, unrelated = [], []
    for q in QUESTIONS:
        hits = data["scores"][kb].get(q["id"], [])
        if q["kind"] == own_kind or q["kind"] == "both":
            docs = set(q["relevant"].get(kb, []))
            best = max((h["similarity"] for h in hits if h["doc"] in docs), default=None)
            if best is not None:
                relevant.append(best)
        else:  # the other corpus' questions and the four that belong to neither
            unrelated.append(max((h["similarity"] for h in hits), default=0.0))
    return relevant, unrelated


def recommend(relevant: list[float], unrelated: list[float]) -> tuple[float, str]:
    """The midpoint of the gap when there is one, else the threshold that separates best."""
    low, high = min(relevant), max(unrelated)
    if low > high:
        return round((low + high) / 2, 2), "clean gap"
    best, best_score = 0.45, -1.0
    for t in [x / 100 for x in range(30, 91)]:
        kept = sum(r >= t for r in relevant) / len(relevant)
        blocked = sum(u < t for u in unrelated) / len(unrelated)
        score = kept + blocked
        if score > best_score:
            best, best_score = t, score
    return round(best, 2), "the scores overlap: the best compromise"


def show(data: dict) -> None:
    meta = data.get("meta", {})
    print(f"Embeddings: {meta.get('embedding_model')}   recorded: {meta.get('recorded')}\n")
    for kb in KNOWLEDGE_BASES:
        relevant, unrelated = split(kb, data)
        print(f"== {kb}: {len(relevant)} relevant questions, {len(unrelated)} unrelated ones")
        print(f"   relevant best-hit similarity:  min {min(relevant):.2f}  median {statistics.median(relevant):.2f}  max {max(relevant):.2f}")
        print(f"   unrelated top-hit similarity:  min {min(unrelated):.2f}  median {statistics.median(unrelated):.2f}  max {max(unrelated):.2f}")
        floor, why = recommend(relevant, unrelated)
        print(f"   recommended floor: {floor:.2f} ({why})")
        chosen = FLOORS[kb]
        print(
            f"   at the floor in use ({chosen:.2f}): {sum(r >= chosen for r in relevant)}/{len(relevant)} relevant kept, "
            f"{sum(u >= chosen for u in unrelated)}/{len(unrelated)} unrelated let through"
        )
        print(f"   {'floor':>6} {'relevant kept':>15} {'unrelated passing':>19}")
        for t in sorted({0.45, floor, 0.5, 0.55, 0.6, 0.65}):
            kept = sum(r >= t for r in relevant)
            passing = sum(u >= t for u in unrelated)
            print(f"   {t:>6.2f} {f'{kept}/{len(relevant)}':>15} {f'{passing}/{len(unrelated)}':>19}")
        print()


def main(argv: list[str]) -> int:
    if "--record" in argv:
        record()
    data = load()
    if not data["scores"]:
        print("Nothing recorded yet: run with --record against a running API.", file=sys.stderr)
        return 2
    show(data)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
