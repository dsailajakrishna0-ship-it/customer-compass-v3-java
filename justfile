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

# J8: build and test the comparison modules under comparisons/ (no keys, no network; the live test is skipped unless CRM_BASE_URL is set)
comparisons-test:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml verify

# J8.1: the same, plus the test that reads the real CRM routes: needs `just db` and `just run` up (the application on port 8001)
comparisons-test-live:
    CRM_BASE_URL=http://localhost:8001 ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml verify

# J8.1: start the handWritten tool-calling backend on port 18101 (needs OPENROUTER_API_KEY to answer; CRM_BASE_URL, LLM_BASE_URL, MODEL, PORT optional)
comparisons-hand-written:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl tool-calling/handWritten -am compile exec:java

# J8.1b: start the withSpringAi tool-calling backend on port 18102 (needs OPENROUTER_API_KEY to answer; CRM_BASE_URL, LLM_BASE_URL, MODEL, PORT optional)
comparisons-with-spring-ai:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl tool-calling/withSpringAi -am compile exec:java

# J8.1c: the withLangchain4j backend on port 18103
comparisons-with-langchain4j:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl tool-calling/withLangchain4j -am compile exec:java

# J8.2a: the handWritten RAG-ingestion backend on port 18111 (needs GOOGLE_API_KEY to run, and `just qdrant`; SOURCE_DIR, QDRANT_URL, PORT optional)
comparisons-ingest-hand-written:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl rag-ingestion/handWritten -am compile exec:java

# J8.2b: the withSpringAi RAG-ingestion backend on port 18112 (needs GOOGLE_API_KEY to run, and `just qdrant`; SOURCE_DIR, QDRANT_HOST, QDRANT_GRPC_PORT, PORT optional)
comparisons-ingest-with-spring-ai:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl rag-ingestion/withSpringAi -am compile exec:java

# J8.2c: the withLangchain4j RAG-ingestion backend on port 18113 (needs GOOGLE_API_KEY to run, and `just qdrant`; QDRANT_HOST, QDRANT_GRPC_PORT, SOURCE_DIR, PORT optional)
comparisons-ingest-with-langchain4j:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl rag-ingestion/withLangchain4j -am compile exec:java

# J8.3a: the handWritten RAG-retrieval backend on port 18121 (needs GOOGLE_API_KEY, `just qdrant` and a prior handWritten ingestion; QDRANT_URL, PORT optional)
comparisons-retrieve-hand-written:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl rag-retrieval/handWritten -am compile exec:java

# J8.3b: the withSpringAi RAG-retrieval backend on port 18122 (needs GOOGLE_API_KEY, `just qdrant` and a prior withSpringAi ingestion; QDRANT_HOST, QDRANT_GRPC_PORT, PORT optional)
comparisons-retrieve-with-spring-ai:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl rag-retrieval/withSpringAi -am compile exec:java

# J8.3c: the withLangchain4j RAG-retrieval backend on port 18123 (needs GOOGLE_API_KEY, `just qdrant` and a prior withLangchain4j ingestion; QDRANT_HOST, QDRANT_GRPC_PORT, PORT optional)
comparisons-retrieve-with-langchain4j:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl rag-retrieval/withLangchain4j -am compile exec:java

# J8.4a: the handWritten tool-calling backend with observation on port 18131 (TELEMETRY_ENABLED=true turns it on; needs OPENROUTER_API_KEY and the application up; CRM_BASE_URL, MODEL, PORT optional)
comparisons-observe-hand-written:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl observability/handWritten -am -Dtoolcalling.exec.skip=true compile exec:java

# J8.4b: the withSpringAi tool-calling backend with observation on port 18132 (TELEMETRY_ENABLED=true turns it on; needs OPENROUTER_API_KEY and the application up; CRM_BASE_URL, MODEL, PORT optional)
comparisons-observe-with-spring-ai:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl observability/withSpringAi -am -Dtoolcalling.exec.skip=true compile exec:java

# J8.4c: the withLangchain4j tool-calling backend with observation on port 18133 (TELEMETRY_ENABLED=true turns it on; needs OPENROUTER_API_KEY and the application up; CRM_BASE_URL, MODEL, PORT optional)
comparisons-observe-with-langchain4j:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl observability/withLangchain4j -am -Dtoolcalling.exec.skip=true compile exec:java

# J8.5a: the handWritten agent backend with an approval pause on port 18141 (needs OPENROUTER_API_KEY and the application up; CRM_BASE_URL, MODEL, PORT optional)
comparisons-agent-hand-written:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl agent-graph/handWritten -am compile exec:java

# J8.5b: the withLangGraph4j agent backend with an approval pause on port 18142 (needs OPENROUTER_API_KEY and the application up; CRM_BASE_URL, MODEL, PORT optional)
comparisons-agent-with-langgraph4j:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl agent-graph/withLangGraph4j -am compile exec:java

# J8.5c: the withLangchain4jAgentic agent backend (LangChain4j's agentic module) on port 18143 (needs OPENROUTER_API_KEY and the application up; CRM_BASE_URL, MODEL, PORT optional)
comparisons-agent-with-langchain4j-agentic:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl agent-graph/withLangchain4jAgentic -am compile exec:java

# J8.7b: the supervisor-pattern backend on port 18151 (needs OPENROUTER_API_KEY and the application up; TELEMETRY_ENABLED=true records signals; CRM_BASE_URL, MODEL, MAX_TURNS, PORT optional)
comparisons-multi-agent-supervisor:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl multi-agent/supervisor/withLangGraph4jSpringAi -am compile exec:java

# J8.7d: the network-pattern backend on port 18153 (needs OPENROUTER_API_KEY and the application up; TELEMETRY_ENABLED=true records signals; CRM_BASE_URL, MODEL, MAX_TURNS, PORT optional)
comparisons-multi-agent-network:
    ./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml -pl multi-agent/network/withLangGraph4jSpringAi -am compile exec:java

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

# E1.4: the similarity floor of each knowledge base, from the recorded scores of relevant and unrelated questions (no keys, no network)
kb-calibrate:
    @python3 evals/knowledge-bases/calibrate_floor.py

# E1.4: ask the running API (both knowledge bases ingested, keys set) for the scores with the floor switched off
kb-calibrate-record:
    @python3 evals/knowledge-bases/calibrate_floor.py --record

# ask the tool-calling copilot (the model calls tools through MCP; a write needs your "yes" in the next question). Example: just tools "What are Acme Fabrication's open deals?"  (MODEL=<id> picks a model)
tools question:
    @python3 contracts/http/ask.py tools "$1"

# check the MCP tool server (served by the API at /mcp) against contracts/mcp-tools.json: the same check the Python edition's tool servers pass. Needs `uv`; the API running
mcp-contract url="http://localhost:8001/mcp":
    @uv run --with mcp python contracts/check_server.py {{url}}

# call one MCP tool by hand. Example: just mcp-call get_open_deals '{"company_name": "Acme"}'
mcp-call tool args url="http://localhost:8001/mcp":
    @uv run --with mcp python contracts/call_tool.py {{url}} {{tool}} '{{args}}'

# J7.1: the rule-based guard on the shared 30 adversarial prompts, from the saved baseline (no keys, no network, no build)
eval-adversarial:
    @python3 evals/adversarial/show.py

# J7.1: run the guard over the prompts and fail if any result differs from the baseline or from the Python edition's rules
eval-adversarial-check:
    cd {{api}} && ./mvnw -B -ntp -q test -Dtest=AdversarialEvalTests

# J7.4: the rule guard against the LLM classifier on the 74 comparison prompts, replayed from the recording (no keys, no network)
eval-guards:
    @python3 evals/guards/guard_eval.py

# J7.4: replay and fail if a count differs from evals/guards/baseline.json
eval-guards-check:
    @python3 evals/guards/guard_eval.py --check

# J7.4: ask the running API (GUARD_CHECK_ENABLED=true, OPENROUTER_API_KEY, GUARD_MODEL) for what is not recorded yet: 74 prompts, 74 classifier calls
eval-guards-record:
    @python3 evals/guards/guard_eval.py --record

# J7.4: save the replayed counts as the baseline (on purpose, after a complete recording with no unavailable verdicts)
eval-guards-baseline:
    @python3 evals/guards/guard_eval.py --write-baseline

# J7.4: the scoring, the replay and the recording step proved on a made-up recording (no keys, no network, no API)
eval-guards-selftest:
    @python3 evals/guards/selftest.py

# J7.2: the CRM retrieval score on the shared 42 golden questions, replayed from the recording (no keys, no network)
eval-retrieval:
    @python3 evals/retrieval/retrieval_eval.py

# J7.2: replay and fail if a metric moved more than 0.02 from evals/retrieval/baseline.json
eval-retrieval-check:
    @python3 evals/retrieval/retrieval_eval.py --check

# J7.2: the same check on the floor-0.45 recording (the counterpart of the Python edition's saved baseline)
eval-retrieval-check-floor-045:
    @RECORDED_PATH=evals/retrieval/recorded-floor-0.45.json BASELINE_PATH=evals/retrieval/baseline-floor-0.45.json python3 evals/retrieval/retrieval_eval.py --check

# J7.2: ask the running API (the CRM documents ingested, GOOGLE_API_KEY set) for what is not recorded yet: 42 questions, about 42 embedding calls; MIN_SIMILARITY, EMBEDDING_MODEL, RETRIEVAL_MODE optional
eval-retrieval-record:
    @python3 evals/retrieval/retrieval_eval.py --record

# J7.2: save the replayed metrics as the baseline (on purpose, after a recording)
eval-retrieval-baseline:
    @python3 evals/retrieval/retrieval_eval.py --write-baseline

# J7.2: the scoring, the replay and the recording step proved on a made-up recording (no keys, no network, no API)
eval-retrieval-selftest:
    @python3 evals/retrieval/selftest.py

# J7.1: rewrite evals/adversarial/baseline.json from the guard's current behaviour (on purpose, after a rule change)
eval-adversarial-baseline:
    cd {{api}} && EVAL_WRITE_BASELINE=1 ./mvnw -B -ntp -q test -Dtest=AdversarialEvalTests
