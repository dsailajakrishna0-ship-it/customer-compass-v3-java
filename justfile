# Developer commands. Install just once: `uv tool install rust-just` (or your package manager).
# Run `just` to list them.

set shell := ["bash", "-euo", "pipefail", "-c"]
set positional-arguments

api := "apps/api-java"

# list the recipes
default:
    @just --list

# format the Java code (Spotless, google-java-format)
fmt:
    cd {{api}} && ./mvnw -B -ntp -q spotless:apply

# build, run the tests, then check formatting and style (what CI runs)
test:
    cd {{api}} && ./mvnw -B -ntp verify

# web app: lint, types, tests, production build (copied from the Python edition)
test-web:
    npm run lint
    npm run typecheck
    npm test
    npm run build

# everything CI checks
check: test test-web

# a throw-away Postgres for running the API by hand (port 25432: below 32768 on purpose, because Linux hands out 32768 to 60999 as temporary outgoing ports, which a VPN client or any program can hold, never the Python edition's 5432); stop with `just db-stop`
db:
    docker run -d --rm --name cc-java-db -e POSTGRES_DB=customer_compass -e POSTGRES_USER=customer_compass -e POSTGRES_PASSWORD=customer_compass_dev -p 25432:5432 postgres:17.11-alpine
    until docker exec cc-java-db pg_isready -U customer_compass -d customer_compass >/dev/null 2>&1; do sleep 1; done
    @echo "Postgres ready on localhost:25432"

# stop the throw-away Postgres (its data goes with it)
db-stop:
    docker stop cc-java-db

# run the API on http://localhost:8001 against `just db` (Flyway builds the schema and demo data on start)
run:
    cd {{api}} && DATABASE_URL=jdbc:postgresql://localhost:25432/customer_compass ./mvnw -B -ntp spring-boot:run

# the shared HTTP contract check; point it at this API or at the Python edition, e.g. `just contract http://localhost:8001`
contract url="http://localhost:8001":
    python3 contracts/http/crm_contract.py {{url}}

# a throw-away Qdrant for running the API by hand (REST 26333, gRPC 26334, never the Python edition's 6333/6334); stop with `just qdrant-stop`
qdrant:
    docker run -d --rm --name cc-java-qdrant -p 26333:6333 -p 26334:6334 qdrant/qdrant:v1.20.0
    until curl -sf http://localhost:26333/readyz >/dev/null 2>&1; do sleep 1; done
    @echo "Qdrant ready: REST on localhost:26333, gRPC on localhost:26334 (run the API with QDRANT_GRPC_PORT=26334)"

# stop the throw-away Qdrant (its data goes with it)
qdrant-stop:
    docker stop cc-java-qdrant

# ask the general chat a question; the API must be running (just run). Example: just chat "What is the capital of France?"
chat question:
    @python3 contracts/http/ask.py chat "$1"

# ask the document chat (answers from the ingested documents, with citations). Example: just rag "What does Clause 7.3 say about the renewal notice period?"
rag question:
    @python3 contracts/http/ask.py rag "$1"

# ask the CRM copilot (facts from the database and/or documents). Example: just crm "What open deals does Acme Fabrication have?"
crm question:
    @python3 contracts/http/ask.py crm "$1"

# run the account follow-up agent (evidence, analyst, reviewer, task drafter; it proposes a task and never writes). Example: just agent "Analyze Acme Fabrication and recommend a next action"  (MODEL=<id> picks a model)
agent question:
    @python3 contracts/http/ask.py agent "$1"

# what the guard has flagged, newest first (J6.1). Example: just security-log  or  just security-log 'event_type=input'
security-log query='limit=20':
    @curl -s "localhost:8001/api/v1/security-audit-log?{{query}}" | python3 -m json.tool

# the knowledge bases and their chunk counts (Java extension E1). Example: just kb-list
kb-list:
    @curl -s localhost:8001/api/v1/knowledge-bases | python3 -m json.tool

# (re)build an extra knowledge base from its folder. Example: just kb-ingest agri-supply
kb-ingest id:
    @curl -s -X POST localhost:8001/api/v1/knowledge-bases/{{id}}/ingest | python3 -m json.tool

# search one knowledge base. Example: just kb-search agri-supply "re-entry interval after spraying"
kb-search id query:
    @python3 -c 'import json,sys,urllib.request as u; r=u.Request("http://localhost:8001/api/v1/knowledge-bases/"+sys.argv[1]+"/search", json.dumps({"query":sys.argv[2]}).encode(), {"Content-Type":"application/json"}); print(json.dumps(json.load(u.urlopen(r)), indent=2))' {{id}} "{{query}}"

# ask across all knowledge bases (Java extension E1.2). STRATEGY=route|all|agent (default route). Example: STRATEGY=all just multi "How long must workers stay out after spraying?"
multi question:
    @python3 contracts/http/ask.py multi "$1"

# E1.3: the strategy comparison table, replayed from the recorded answers (no keys, no network)
eval-multi:
    @python3 evals/knowledge-bases/compare_strategies.py

# E1.3: replay and fail if the counts differ from evals/knowledge-bases/baseline.json
eval-multi-check:
    @python3 evals/knowledge-bases/compare_strategies.py --check

# E1.3: ask the running API (both knowledge bases ingested, keys set) for whatever is not recorded yet. API_URL, MODEL and EMBEDDING_MODEL are optional labels/targets
eval-multi-record:
    @python3 evals/knowledge-bases/compare_strategies.py --record

# the supervisor with specialist agents (Java extension E3): same request as `just agent`, the answer adds the supervisor's decisions. Example: just supervise "Analyze Acme Fabrication and recommend a next action"
supervise question:
    @python3 contracts/http/ask.py supervise "$1"

# E3.3: the fixed pipeline against the supervisor, replayed from the recorded runs (no keys, no network)
eval-supervisor:
    @python3 evals/supervisor/compare_agents.py

# E3.3: replay and fail if the counts differ from evals/supervisor/baseline.json
eval-supervisor-check:
    @python3 evals/supervisor/compare_agents.py --check

# E3.3: run the cases against the running API (CRM documents and agri-supply ingested, keys set) for whatever is not recorded yet. API_URL, MODEL and EMBEDDING_MODEL are optional
eval-supervisor-record:
    @python3 evals/supervisor/compare_agents.py --record

# ask the tool-calling copilot (the model calls tools through MCP; a write needs your "yes" in the next question). Example: just tools "What are Acme Fabrication's open deals?"  (MODEL=<id> picks a model)
tools question:
    @python3 contracts/http/ask.py tools "$1"

# check the MCP tool server (served by the API at /mcp) against contracts/mcp-tools.json: the same check the Python edition's tool servers pass. Needs `uv`; the API running
mcp-contract url="http://localhost:8001/mcp":
    @uv run --with mcp python contracts/check_server.py {{url}}

# call one MCP tool by hand. Example: just mcp-call get_open_deals '{"company_name": "Acme"}'
mcp-call tool args url="http://localhost:8001/mcp":
    @uv run --with mcp python contracts/call_tool.py {{url}} {{tool}} '{{args}}'
