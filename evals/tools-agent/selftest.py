#!/usr/bin/env python3
"""J7.5: proves the scoring, the replay and the recording step on a tiny made-up recording (no keys, no network beyond localhost, no API).

  python3 evals/tools-agent/selftest.py

The numbers are worked by hand, so a wrong rule fails here instead of quietly changing the baseline. The real recording is
evals/tools-agent/recorded.json, once the owner has approved and made it with --record.
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

import tool_agent_eval as e

TOOL_CASES = [
    {"id": "t1", "message": "m", "expect_tool": "get_open_deals", "expect_args_contain": {"company_name": "Acme"}},
    {"id": "t2", "message": "m", "expect_tool": "get_company_summary", "expect_args_contain": {"company_name": "Vertex"}},
    {"id": "t3", "message": "m", "expect_tool": "search_documents", "expect_args_contain": {"query": "alert"}},
    {"id": "t4", "message": "hello", "expect_tool": None, "expect_args_contain": {}},
    {"id": "t5", "message": "hello again", "expect_tool": None, "expect_args_contain": {}},
]
AGENT_CASES = [
    {"id": "a1", "message": "m", "expect_route": "agent", "expect_status": "completed", "expect_company": "Acme Fabrication"},
    {"id": "a2", "message": "m", "expect_route": "agent", "expect_status": "completed", "expect_company": "Vertex Analytics"},
    {"id": "a3", "message": "m", "expect_route": "direct", "expect_status": None, "expect_company": None},
    {"id": "a4", "message": "m", "expect_route": "agent", "expect_status": "company_not_found", "expect_company": None},
]
RECORDED = {
    "tools": {
        "t1": {"tools": [{"tool": "get_open_deals", "arguments": {"company_name": "Acme Fabrication"}}]},  # right tool, right args
        "t2": {"tools": [{"tool": "get_company_summary", "arguments": {"company_name": "Globex"}}]},  # right tool, wrong args
        "t3": {"tools": [{"tool": "get_open_deals", "arguments": {"company_name": "Acme"}}]},  # wrong tool
        "t4": {"tools": []},  # no tool, as wanted
        "t5": {"tools": [{"tool": "create_followup_task", "arguments": {"company_name": "Acme", "confirm": True}}]},  # tool when none wanted, and a write
    },
    "agent": {
        "a1": {"route": "agent", "status": "completed", "company": "acme fabrication"},  # all right (case-insensitive)
        "a2": {"route": "agent", "status": "completed", "company": "Acme Fabrication"},  # wrong company
        "a3": {"route": "agent", "status": None, "company": None},  # wrong route
        "a4": {"route": "agent", "status": "company_not_found", "company": None},  # right
    },
}


class Scoring(unittest.TestCase):
    def test_the_metrics_of_the_made_up_recording_by_hand(self):
        m = e.score(TOOL_CASES, AGENT_CASES, RECORDED)["metrics"]
        self.assertEqual((m["tool_cases"], m["agent_cases"]), (5, 4))
        self.assertAlmostEqual(m["tool_name_accuracy"], 2 / 3)  # t1, t2 right; t3 wrong
        self.assertAlmostEqual(m["tool_argument_accuracy"], 1 / 3)  # only t1 has the arguments too
        self.assertAlmostEqual(m["no_tool_accuracy"], 0.5)  # t4 yes, t5 called a tool
        self.assertEqual(m["write_without_confirm"], 1)
        self.assertAlmostEqual(m["route_accuracy"], 3 / 4)
        self.assertAlmostEqual(m["status_accuracy"], 1.0)  # a1, a2, a4 have a status expected, all right (a3 has none)
        self.assertAlmostEqual(m["company_accuracy"], 1 / 2)  # a1 right, a2 wrong

    def test_arguments_match_as_case_insensitive_substrings(self):
        row = e.score_tool_case(TOOL_CASES[0], {"tools": [{"tool": "get_open_deals", "arguments": {"company_name": "ACME FABRICATION"}}]})
        self.assertTrue(row["tool_ok"] and row["args_ok"])

    def test_one_matching_call_among_several_is_enough(self):
        run = {"tools": [{"tool": "get_open_deals", "arguments": {"company_name": "Globex"}}, {"tool": "get_open_deals", "arguments": {"company_name": "Acme"}}]}
        self.assertTrue(e.score_tool_case(TOOL_CASES[0], run)["args_ok"])

    def test_a_confirmed_write_in_a_one_message_conversation_is_counted_and_a_proposal_is_not(self):
        proposal = {"tools": [{"tool": "create_followup_task", "arguments": {"confirm": False}}]}
        self.assertEqual(e.score_tool_case(TOOL_CASES[0], proposal)["unconfirmed_writes"], 0)
        confirmed = {"tools": [{"tool": "create_followup_task", "arguments": {"confirm": True}}]}
        self.assertEqual(e.score_tool_case(TOOL_CASES[0], confirmed)["unconfirmed_writes"], 1)

    def test_a_case_without_a_recording_is_not_scored(self):
        m = e.score(TOOL_CASES, AGENT_CASES, {"tools": {"t1": RECORDED["tools"]["t1"]}, "agent": {}})["metrics"]
        self.assertEqual((m["tool_cases"], m["agent_cases"]), (1, 0))


class Check(unittest.TestCase):
    def test_the_same_recording_passes_and_a_flipped_case_fails(self):
        baseline = e.score(TOOL_CASES, AGENT_CASES, RECORDED)
        self.assertEqual(e.compare(baseline, baseline), [])
        worse = json.loads(json.dumps(RECORDED))
        worse["agent"]["a1"]["status"] = "error"
        problems = e.compare(baseline, e.score(TOOL_CASES, AGENT_CASES, worse))
        self.assertTrue(any(p.startswith("status_accuracy") for p in problems), problems)

    def test_a_different_number_of_cases_fails(self):
        baseline = e.score(TOOL_CASES, AGENT_CASES, RECORDED)
        fewer = json.loads(json.dumps(RECORDED))
        del fewer["tools"]["t5"]
        self.assertTrue(any(p.startswith("tool_cases") for p in e.compare(baseline, e.score(TOOL_CASES, AGENT_CASES, fewer))))


class TheSharedFiles(unittest.TestCase):
    def test_the_golden_files_are_ten_and_ten_with_unique_ids(self):
        tools = json.loads((e.GOLDEN / "tool_calls.json").read_text())
        agent = json.loads((e.GOLDEN / "agent.json").read_text())
        self.assertEqual((len(tools), len(agent)), (10, 10))
        self.assertEqual(len({c["id"] for c in tools + agent}), 20)

    def test_every_expected_tool_is_one_the_application_offers(self):
        offered = {"get_company_summary", "get_open_deals", "search_documents", "create_followup_task", None}
        self.assertLessEqual({c["expect_tool"] for c in json.loads((e.GOLDEN / "tool_calls.json").read_text())}, offered)


@contextlib.contextmanager
def quiet():
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        yield


class Replay(unittest.TestCase):
    def test_main_writes_a_baseline_then_checks_against_it(self):
        tools = {c["id"]: {"tools": ([{"tool": c["expect_tool"], "arguments": {}}] if c["expect_tool"] else [])} for c in json.loads((e.GOLDEN / "tool_calls.json").read_text())}
        agent = {c["id"]: {"route": c["expect_route"], "status": c["expect_status"], "company": c["expect_company"]} for c in json.loads((e.GOLDEN / "agent.json").read_text())}
        with tempfile.TemporaryDirectory() as tmp:
            rec, base = Path(tmp) / "recorded.json", Path(tmp) / "baseline.json"
            rec.write_text(json.dumps({"meta": {"recorded": "test"}, "tools": tools, "agent": agent}))
            e.RECORDED, e.BASELINE = rec, base
            with quiet():
                self.assertEqual(e.main(["--check"]), 1)  # no baseline yet
                self.assertEqual(e.main(["--write-baseline"]), 0)
                self.assertEqual(e.main(["--check"]), 0)
            agent["agent-01"]["status"] = "error"
            rec.write_text(json.dumps({"meta": {"recorded": "test"}, "tools": tools, "agent": agent}))
            with quiet():
                self.assertEqual(e.main(["--check"]), 1)  # one agent case now ends differently
                rec.unlink()
                self.assertEqual(e.main([]), 2)  # nothing recorded


class Recording(unittest.TestCase):
    """--record against a stand-in for the API: what it sends and how it reads the answers."""

    def test_record_sends_the_message_and_keeps_tools_route_status_and_company(self):
        seen = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                seen.append((self.path, body))
                if self.path.endswith("/chat/tools"):
                    answer = {"toolsUsed": [{"tool": "get_open_deals", "arguments": {"company_name": "Acme"}, "status": "ran"}], "message": "x"}
                else:
                    answer = {"route": "agent", "status": "completed", "company": {"id": 1, "name": "Acme Fabrication"}}
                payload = json.dumps(answer).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        with tempfile.TemporaryDirectory() as tmp:
            e.RECORDED = Path(tmp) / "recorded.json"
            os.environ["API_URL"] = f"http://127.0.0.1:{server.server_port}"
            os.environ["MODEL"] = "some/model"
            try:
                with quiet():
                    e.record(TOOL_CASES[:1], AGENT_CASES[:1])
                    e.record(TOOL_CASES[:1], AGENT_CASES[:1])  # a rerun asks for nothing that is recorded
            finally:
                del os.environ["MODEL"]
                server.shutdown()
            data = json.loads(e.RECORDED.read_text())
        self.assertEqual(len(seen), 2)
        self.assertEqual(seen[0][0], "/api/v1/chat/tools")
        self.assertEqual(seen[0][1]["messages"], [{"role": "user", "content": "m"}])
        self.assertEqual(seen[0][1]["model"], "some/model")
        self.assertEqual(seen[1][0], "/api/v1/agent/recommend")
        self.assertEqual(seen[1][1]["message"], "m")
        self.assertEqual(data["tools"]["t1"], {"tools": [{"tool": "get_open_deals", "arguments": {"company_name": "Acme"}}]})
        self.assertEqual(data["agent"]["a1"], {"route": "agent", "status": "completed", "company": "Acme Fabrication"})
        self.assertEqual(data["meta"]["model"], "some/model")


if __name__ == "__main__":
    unittest.main(verbosity=2)
