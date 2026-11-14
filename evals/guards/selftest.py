#!/usr/bin/env python3
"""J7.4: proves the scoring, the replay, the check and the recording step on a made-up recording (no keys, no network, no API).

  python3 evals/guards/selftest.py

The numbers are worked by hand. The real recording is evals/guards/recorded.json, once someone has made it with --record.
"""

import contextlib
import io
import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import guard_eval as e

CASES = [
    {"id": "a1", "is_attack": True, "set": "adversarial-30", "prompt": "p"},
    {"id": "a2", "is_attack": True, "set": "adversarial-30", "prompt": "p"},
    {"id": "a3", "is_attack": True, "set": "extra-44", "prompt": "p"},
    {"id": "b1", "is_attack": False, "set": "adversarial-30", "prompt": "p"},
    {"id": "b2", "is_attack": False, "set": "extra-44", "prompt": "p"},
]


def run(rules, outcome, model="m", fallback=False):
    return {"rules": {"flagged": rules, "patterns": []}, "classifier": {"outcome": outcome, "word": "x", "model": model, "fallbackUsed": fallback, "failure": None, "ms": 100}}


RECORDED = {
    "a1": run(True, "unsafe"),  # both catch it
    "a2": run(False, "unsafe"),  # only the classifier
    "a3": run(False, "safe"),  # nobody
    "b1": run(False, "safe"),  # clean
    "b2": run(True, "unsafe"),  # both flag a benign prompt
}


@contextlib.contextmanager
def quiet():
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        yield


class Scoring(unittest.TestCase):
    def test_the_three_strategies_by_hand(self):
        r = e.score(CASES, RECORDED)
        self.assertEqual((r["cases"], r["attacks"], r["benign"]), (5, 3, 2))
        rules, classifier, both = (r["strategies"][n] for n in e.STRATEGIES)
        self.assertEqual((rules["detected"], rules["missed"], rules["false_positives"]), (1, ["a2", "a3"], 1))
        self.assertEqual((classifier["detected"], classifier["missed"], classifier["false_positives"]), (2, ["a3"], 1))
        self.assertEqual((both["detected"], both["missed"], both["false_positive_ids"]), (2, ["a3"], ["b2"]))
        self.assertEqual(classifier["detected_in_adversarial-30"], "2/2")
        self.assertEqual(classifier["detected_in_extra-44"], "0/1")

    def test_an_unavailable_classifier_is_not_counted_as_safe(self):
        down = dict(RECORDED, a2=run(False, "unavailable", model=None), b1=run(False, "unavailable", model=None))
        r = e.score(CASES, down)
        classifier = r["strategies"]["classifier"]
        self.assertEqual((classifier["detected"], classifier["missed"], classifier["attacks_unavailable"], classifier["benign_unavailable"]), (1, ["a3"], 1, 1))
        both = r["strategies"]["both"]
        self.assertEqual((both["detected"], both["missed"], both["attacks_unavailable"]), (1, ["a3"], 1))  # a2: rules clean and no verdict; a3: rules clean and classifier safe
        self.assertEqual(r["classifier_unavailable"], 2)

    def test_rules_plus_classifier_flags_when_either_does(self):
        v = e.verdicts(run(True, "safe"))
        self.assertEqual((v["rules"], v["classifier"], v["both"]), (True, False, True))
        self.assertIsNone(e.verdicts(run(False, "unavailable"))["both"])


class Check(unittest.TestCase):
    def test_baseline_rules(self):
        r = e.score(CASES, RECORDED)
        self.assertEqual(e.baseline_problems(r, 5), [])
        self.assertTrue(e.baseline_problems(r, 6))  # a prompt is not recorded
        self.assertTrue(e.baseline_problems(e.score(CASES, dict(RECORDED, a2=run(False, "unavailable", model=None))), 5))
        self.assertTrue(e.baseline_problems(e.score(CASES, dict(RECORDED, a2=run(False, "unsafe", model="other"))), 5))
        self.assertTrue(e.baseline_problems(e.score(CASES, dict(RECORDED, a2=run(False, "unsafe", fallback=True))), 5))

    def test_a_moved_count_fails(self):
        base = e.comparable(e.score(CASES, RECORDED))
        self.assertEqual(e.differences(base, base), [])
        worse = e.comparable(e.score(CASES, dict(RECORDED, a2=run(False, "safe"))))
        self.assertTrue(any(p.startswith("classifier.detected") for p in e.differences(base, worse)))


class TheSharedFiles(unittest.TestCase):
    def test_the_comparison_set_is_the_74_prompts_of_the_python_edition(self):
        cases = e.load_cases()
        self.assertEqual(len(cases), 74)
        self.assertEqual(sum(1 for c in cases if c["is_attack"]), 44)
        self.assertEqual(sum(1 for c in cases if c["set"] == "adversarial-30"), 30)


class Replay(unittest.TestCase):
    def test_main_refuses_a_bad_baseline_then_writes_and_checks_one(self):
        full = {c["id"]: run(c["is_attack"], "unsafe" if c["is_attack"] else "safe") for c in e.load_cases()}
        with tempfile.TemporaryDirectory() as tmp:
            rec, base = Path(tmp) / "recorded.json", Path(tmp) / "baseline.json"
            e.RECORDED, e.BASELINE = rec, base
            rec.write_text(json.dumps({"meta": {"configured_model": "m", "recorded": "t"}, "cases": full}))
            with quiet():
                self.assertEqual(e.main(["--check"]), 1)  # no baseline yet
                self.assertEqual(e.main(["--write-baseline"]), 0)
                self.assertEqual(e.main(["--check"]), 0)
                full["override-01"] = run(False, "unavailable", model=None)
                rec.write_text(json.dumps({"meta": {"configured_model": "m", "recorded": "t"}, "cases": full}))
                self.assertEqual(e.main(["--write-baseline"]), 2)  # an unavailable verdict blocks a baseline
                self.assertEqual(e.main(["--check"]), 1)
                rec.unlink()
                self.assertEqual(e.main([]), 2)


class Recording(unittest.TestCase):
    def test_record_retries_an_unavailable_prompt_and_keeps_the_verdicts_apart(self):
        calls = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                calls.append(body["text"])
                down = len(calls) == 1  # the first call finds the model down
                classifier = {"outcome": "unavailable", "word": None, "model": None, "fallbackUsed": False, "failure": "LlmUnavailableException"} if down else {"outcome": "unsafe", "word": "UNSAFE", "model": "m", "fallbackUsed": False, "failure": None}
                payload = json.dumps({"rules": {"flagged": False, "patterns": []}, "classifier": classifier, "configuredModel": "m"}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                e.RECORDED = Path(tmp) / "recorded.json"
                import os

                os.environ["API_URL"] = f"http://127.0.0.1:{server.server_port}"
                with quiet():
                    self.assertEqual(e.record(CASES[:1], wait=lambda s: None), 0)
                data = json.loads(e.RECORDED.read_text())
                self.assertEqual(len(calls), 2)  # the retry
                self.assertEqual(data["cases"]["a1"]["classifier"]["outcome"], "unsafe")
                self.assertEqual(data["meta"]["configured_model"], "m")
        finally:
            server.shutdown()


if __name__ == "__main__":
    unittest.main()
