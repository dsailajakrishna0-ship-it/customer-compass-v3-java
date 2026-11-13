#!/usr/bin/env python3
"""Ask one of the chat routes a question and print the JSON answer.

    python3 contracts/http/ask.py rag "What does Clause 7.3 say about the renewal notice period?"

The route is chat, rag, crm, tools, agent or multi (the Java extension: STRATEGY=route|all|agent). The address is http://localhost:8001 unless API_URL says otherwise.
Optional: MODEL=<model id> asks a particular model; MCP_SERVER=<id> picks the tool server for tools.
The question is built into JSON here, so quotes and apostrophes in it need no escaping in the shell.
Standard library only. Exit code 1 when the API answers with an error (the answer is still printed).
"""

import json
import os
import sys
import urllib.error
import urllib.request

ROUTES = {"chat": "/api/v1/chat", "rag": "/api/v1/chat/rag", "crm": "/api/v1/chat/crm", "tools": "/api/v1/chat/tools", "agent": "/api/v1/agent/recommend", "multi": "/api/v1/chat/multi"}


def main(argv: list[str]) -> int:
    if len(argv) != 3 or argv[1] not in ROUTES:
        print(__doc__, file=sys.stderr)
        return 2
    base = os.environ.get("API_URL", "http://localhost:8001").rstrip("/")
    # the agent takes one "message"; the chat routes take a list of messages
    payload = {"message": argv[2]} if argv[1] == "agent" else {"messages": [{"role": "user", "content": argv[2]}]}
    if os.environ.get("MODEL"):
        payload["model"] = os.environ["MODEL"]
    if argv[1] == "multi" and os.environ.get("STRATEGY"):
        payload["strategy"] = os.environ["STRATEGY"]
    if argv[1] == "tools" and os.environ.get("MCP_SERVER"):
        payload["mcpServer"] = os.environ["MCP_SERVER"]
    body = json.dumps(payload).encode()
    request = urllib.request.Request(
        base + ROUTES[argv[1]], data=body, headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=150) as response:
            status, text = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, text = error.code, error.read()
    except urllib.error.URLError as error:
        print(f"Could not reach {base}: {error.reason}. Is the API running (just run)?", file=sys.stderr)
        return 1
    print(json.dumps(json.loads(text or b"null"), indent=2, ensure_ascii=False))
    return 0 if status < 400 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
