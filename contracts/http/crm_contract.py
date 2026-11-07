#!/usr/bin/env python3
"""HTTP contract check for the CRM routes (J1). Runs against any base URL, so the same file can be
pointed at the Python edition and at the Java edition and must pass on both.

    python3 contracts/http/crm_contract.py http://localhost:8001

Standard library only. Needs the seeded demo data (7 companies) and no tasks named "TEST:".
Exit code 0 when every check passes.
"""

import json
import sys
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8001").rstrip("/")
failures: list[str] = []


def get(path, headers=None):
    request = urllib.request.Request(BASE + path, headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, json.loads(response.read() or b"null"), response.headers
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"null"), error.headers


def check(name, condition, detail=""):
    print(("ok    " if condition else "FAIL  ") + name + (f"  ({detail})" if detail and not condition else ""))
    if not condition:
        failures.append(name)


status, body, _ = get("/health")
check("GET /health is 200 {status: ok}", status == 200 and body == {"status": "ok"}, f"{status} {body}")

status, companies, _ = get("/api/v1/companies")
check("GET /companies is 200 with 7 companies", status == 200 and len(companies) == 7, f"{status}")
names = [c["name"] for c in companies]
check("companies are sorted by name", names == sorted(names))
check(
    "a company summary has exactly the documented fields",
    all(
        set(c) == {"id", "name", "industry", "relationship_status", "deal_count", "interaction_count"}
        for c in companies
    ),
)
check(
    "relationship_status uses lead / customer / former_customer",
    {c["relationship_status"] for c in companies} == {"lead", "customer", "former_customer"},
)

acme = next(c for c in companies if c["name"] == "Acme Fabrication")
status, detail, _ = get(f"/api/v1/companies/{acme['id']}")
check("GET /companies/{id} is 200", status == 200, f"{status}")
check(
    "a company detail has exactly the documented fields",
    set(detail) == {"id", "name", "industry", "website", "relationship_status", "contacts", "deals", "interactions"},
)
check("Acme has 2 contacts, 2 deals, 4 interactions", (len(detail["contacts"]), len(detail["deals"]), len(detail["interactions"])) == (2, 2, 4))
renewal = next(d for d in detail["deals"] if d["name"] == "Premium Operations Plan renewal")
check("a deal amount is a string with two decimals", renewal["amount"] == "48000.00", renewal["amount"])
check("a deal date is YYYY-MM-DD text", renewal["expected_close_date"] == "2026-10-15")
check(
    "an interaction time is ISO 8601 with +00:00, newest first",
    detail["interactions"][0]["occurred_at"] == "2026-09-20T15:00:00+00:00",
    detail["interactions"][0]["occurred_at"],
)
check("contacts are sorted by full_name", [c["full_name"] for c in detail["contacts"]] == sorted(c["full_name"] for c in detail["contacts"]))

status, body, _ = get("/api/v1/companies/999999")
check("unknown company: 404 with error and detail", status == 404 and body == {"error": "Company not found.", "detail": "Company not found."}, f"{status} {body}")

status, body, _ = get("/api/v1/nothing-here")
# Today the Python edition answers an unknown route with {"detail": "Not Found"} only (see docs/port-status.md, Contract gaps)
check("unknown route: 404 with detail", status == 404 and body.get("detail") == "Not Found", f"{status} {body}")
status, body, _ = get("/api/v1/companies/abc")
check("a non-numeric company id: 422 with a validation list", status == 422 and body["detail"][0]["loc"] == ["path", "company_id"], f"{status} {body}")

status, tasks, _ = get("/api/v1/tasks")
check("GET /tasks is 200 and a list", status == 200 and isinstance(tasks, list), f"{status}")
status, tasks, _ = get(f"/api/v1/tasks?company_id={acme['id']}")
check("GET /tasks?company_id= is 200 and a list", status == 200 and isinstance(tasks, list), f"{status}")
check(
    "a task has exactly the documented fields",
    all(set(t) == {"id", "company_id", "company_name", "note", "status", "created_at"} for t in tasks),
)

_, _, headers = get("/health")
check("every response has X-Request-ID", bool(headers.get("X-Request-ID")))
_, _, headers = get("/health", {"X-Request-ID": "my-id_1.2"})
check("a good X-Request-ID is kept", headers.get("X-Request-ID") == "my-id_1.2")
_, _, headers = get("/health", {"X-Request-ID": "bad id!"})
check("an unsafe X-Request-ID is replaced", headers.get("X-Request-ID") not in (None, "bad id!"))

print(f"\n{len(failures)} failed" if failures else "\nOK: the CRM contract holds against " + BASE)
sys.exit(1 if failures else 0)
