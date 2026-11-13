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

# check the MCP tool server (served by the API at /mcp) against contracts/mcp-tools.json: the same check the Python edition's tool servers pass. Needs `uv`; the API running
mcp-contract url="http://localhost:8001/mcp":
    @uv run --with mcp python contracts/check_server.py {{url}}

# call one MCP tool by hand. Example: just mcp-call get_open_deals '{"company_name": "Acme"}'
mcp-call tool args url="http://localhost:8001/mcp":
    @uv run --with mcp python contracts/call_tool.py {{url}} {{tool}} '{{args}}'

