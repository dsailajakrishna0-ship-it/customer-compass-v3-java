#!/usr/bin/env python3
"""J7.2: proves the scoring, the replay and the check on a tiny made-up recording (no keys, no network, no API).

  python3 evals/retrieval/selftest.py

The numbers below are worked by hand, so a wrong formula fails here instead of quietly changing the baseline. The real recording is
evals/retrieval/recorded.json, once someone has made it with --record.
"""

import contextlib
import io
import json
import os
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import retrieval_eval as e

CASES = [
    {"id": "a", "question": "q", "company": None, "relevant": ["x.md"]},
    {"id": "b", "question": "q", "company": None, "relevant": ["x.md", "y.md"]},
    {"id": "c", "question": "q", "company": None, "relevant": ["z.md"]},
    {"id": "u1", "question": "q", "company": None, "relevant": []},
    {"id": "u2", "question": "q", "company": None, "relevant": []},
]
RECORDED = {
    "a": {"ranked": ["x.md", "y.md"]},  # first: recall@1 1, rr 1
    "b": {"ranked": ["q.md", "y.md", "x.md"]},  # y second, x third: recall@1 0, @3 1, rr 1/2
    "c": {"ranked": ["q.md", "r.md", "s.md", "t.md", "u.md", "z.md"]},  # z sixth: recall@5 0, rr 1/6
    "u1": {"ranked": []},  # declined
    "u2": {"ranked": ["x.md"]},  # still retrieved something
}


class Scoring(unittest.TestCase):
    def test_recall_and_reciprocal_rank_by_hand(self):
        self.assertEqual(e.recall_at_k(["x"], {"x", "y"}, 1), 0.5)
        self.assertEqual(e.recall_at_k(["q", "y", "x"], {"x", "y"}, 3), 1.0)
        self.assertEqual(e.recall_at_k(["q", "y", "x"], {"x", "y"}, 1), 0.0)
        self.assertEqual(e.reciprocal_rank(["q", "y"], {"y"}), 0.5)
        self.assertEqual(e.reciprocal_rank(["q"], {"y"}), 0.0)
        with self.assertRaises(ValueError):
            e.recall_at_k(["x"], set(), 1)

    def test_a_document_counts_once_at_its_best_rank(self):
        self.assertEqual(e.dedupe_keep_order(["a", "b", "a", "c", "b"]), ["a", "b", "c"])

    def test_the_metrics_of_the_made_up_recording(self):
        m = e.score(CASES, RECORDED)["metrics"]
        self.assertEqual((m["cases"], m["answerable"], m["unanswerable"]), (5, 3, 2))
        self.assertAlmostEqual(m["recall_at_1"], (1 + 0 + 0) / 3)
        self.assertAlmostEqual(m["recall_at_3"], (1 + 1 + 0) / 3)
        self.assertAlmostEqual(m["recall_at_5"], (1 + 1 + 0) / 3)
        self.assertAlmostEqual(m["mrr"], (1 + 0.5 + 1 / 6) / 3)
        self.assertEqual(m["unanswerable_decline_rate"], 0.5)

    def test_a_question_without_a_recording_is_not_scored(self):
        m = e.score(CASES, {"a": RECORDED["a"]})["metrics"]
        self.assertEqual(m["cases"], 1)


class Check(unittest.TestCase):
    def test_the_same_recording_passes_and_a_moved_metric_fails(self):
        baseline = e.score(CASES, RECORDED)
        self.assertEqual(e.compare(baseline, baseline), [])
        worse = dict(RECORDED, a={"ranked": ["y.md", "x.md"]})  # x drops from first to second
        problems = e.compare(baseline, e.score(CASES, worse))
        self.assertTrue(any(p.startswith("recall_at_1") for p in problems), problems)
        self.assertTrue(any(p.startswith("mrr") for p in problems), problems)

    def test_a_small_move_inside_the_tolerance_passes(self):
        baseline = e.score(CASES, RECORDED)
        nearly = json.loads(json.dumps(baseline))
        nearly["metrics"]["mrr"] += 0.01
        self.assertEqual(e.compare(nearly, baseline), [])
        nearly["metrics"]["mrr"] += 0.02
        self.assertNotEqual(e.compare(nearly, baseline), [])

    def test_a_different_number_of_questions_fails(self):
        baseline = e.score(CASES, RECORDED)
        fewer = e.score(CASES, {k: v for k, v in RECORDED.items() if k != "c"})
        self.assertTrue(any(p.startswith("cases") for p in e.compare(baseline, fewer)))


class TheSharedFile(unittest.TestCase):
    def test_the_golden_questions_are_the_42_the_python_edition_scores(self):
        cases = json.loads(e.GOLDEN.read_text())
        self.assertEqual(len(cases), 42)
        self.assertEqual(sum(1 for c in cases if c["relevant"]), 36)
        self.assertEqual(len({c["id"] for c in cases}), 42)

    def test_every_relevant_document_is_a_file_the_java_ingest_reads(self):
        sample = e.HERE.parent.parent / "sample-data" / "documents"
        names = {p.name for p in sample.rglob("*") if p.is_file()}
        wanted = {d for c in json.loads(e.GOLDEN.read_text()) for d in c["relevant"]}
        self.assertEqual(sorted(wanted - names), [])


@contextlib.contextmanager
def quiet():
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        yield


class Replay(unittest.TestCase):
    def test_main_writes_a_baseline_then_checks_against_it(self):
        full = {c["id"]: {"ranked": c["relevant"][:1]} for c in json.loads(e.GOLDEN.read_text())}
        with tempfile.TemporaryDirectory() as tmp:
            rec, base = Path(tmp) / "recorded.json", Path(tmp) / "baseline.json"
            rec.write_text(json.dumps({"meta": {"recorded": "test"}, "cases": full}))
            e.RECORDED, e.BASELINE = rec, base
            with quiet():
                self.assertEqual(e.main(["--check"]), 1)  # no baseline yet
                self.assertEqual(e.main(["--write-baseline"]), 0)
                self.assertEqual(e.main(["--check"]), 0)
            full["ret-01"] = {"ranked": []}
            rec.write_text(json.dumps({"meta": {"recorded": "test"}, "cases": full}))
            with quiet():
                self.assertEqual(e.main(["--check"]), 1)  # a question now finds nothing
                rec.unlink()
                self.assertEqual(e.main([]), 2)  # nothing recorded


class Recording(unittest.TestCase):
    """--record against a stand-in for the API: what it sends and how it reads the answer."""

    def test_record_sends_company_and_floor_and_keeps_documents_in_rank_order(self):
        seen = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                seen.append((self.path, body))
                chunks = [{"sourcePath": "docs/b.md"}, {"sourcePath": "a.md"}, {"sourcePath": "b.md"}]
                payload = json.dumps({"results": chunks}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        cases = [
            {"id": "p", "question": "plain", "company": None, "relevant": ["b.md"]},
            {"id": "c", "question": "scoped", "company": "Acme Fabrication", "relevant": ["a.md"]},
        ]
        with tempfile.TemporaryDirectory() as tmp:
            e.RECORDED = Path(tmp) / "recorded.json"
            os.environ["API_URL"] = f"http://127.0.0.1:{server.server_port}"
            os.environ["MIN_SIMILARITY"] = "0.45"
            try:
                with quiet():
                    e.record(cases)
                    e.record(cases)  # a rerun asks for nothing that is recorded
            finally:
                del os.environ["MIN_SIMILARITY"]
                server.shutdown()
            data = json.loads(e.RECORDED.read_text())
        self.assertEqual(len(seen), 2)
        self.assertEqual(seen[0][0], "/api/v1/knowledge-bases/crm/search")
        self.assertEqual(seen[0][1], {"query": "plain", "topK": 10, "minSimilarity": 0.45})
        self.assertEqual(seen[1][1]["company"], "Acme Fabrication")
        self.assertEqual(data["cases"]["p"]["ranked"], ["b.md", "a.md"])  # the folder is dropped, b.md counts once
        self.assertEqual(data["meta"]["floor"], 0.45)


if __name__ == "__main__":
    unittest.main(verbosity=2)
