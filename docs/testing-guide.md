# Testing guide

One section per PR, newest first. Each says how to start, what to run, what you should see, and how to stop.

> **Scratch ports are 25432 (Postgres), 26333 and 26334 (Qdrant).** They are below 32768 on purpose: Linux uses 32768 to 60999 for temporary outgoing connections, and a program that holds one of those numbers (a VPN client did) makes `just db` fail with "address already in use". If a port is still taken, `ss -tanp | grep <port>` shows who has it.

> **If `just db` or `just test` fails with a crashing Postgres** (`Segmentation fault`, "general protection fault" in the kernel log): the Debian image `postgres:17.11` crashes on some AVX-512 laptops. The scratch database and the tests use `postgres:17.11-alpine` (same version, Alpine build). Pull this change and run `just db-stop` (if one is running), then `just db` again. The data in `just db` is throw-away, so nothing is lost.

## J3.2: PDF, Word and Excel, with the page or sheet in the citation

Needs: the same as J3.1 (Java 21, Docker, `just`; a Google key for the live steps 6 to 8). Start `just db`, `just qdrant`, then `QDRANT_GRPC_PORT=26334 just run` with `GOOGLE_API_KEY` exported, as in J3.1.

| # | Do | Expect | What it shows |
| --- | --- | --- | --- |
| 1 | `just test` | all tests pass (83); read `DocumentReaderTests` | The three readers give the text, pages and sheets the Python edition gives |
| 2 | `ls sample-data/documents` | 22 files: 16 `.md`, a PDF, an Excel file, a Word file and three `.meta.yaml` sidecars | The shared sample data, now complete |
| 3 | `cat sample-data/documents/acme-service-agreement.meta.yaml` | `title`, `type`, `company` | Metadata for non-Markdown files sits beside them |
| 4 | Copy the PDF to a scratch folder `x/sample-data/documents/report.pdf` without its sidecar, point `SAMPLE_DATA_DIR` at it, ingest | `502` and `Document report.pdf needs a metadata file named report.meta.yaml.` | A missing sidecar is named |
| 5 | Remove the `type:` line from a Markdown file in the scratch folder, ingest | `502` and `Document <name> is missing required metadata (title, type).` | The same clear message (a missing type used to crash, see ADR 0005) |
| 6 | `curl -s -X POST localhost:8001/api/v1/documents/ingest` (with the key, default folder) | `"documentsProcessed":19,"chunksCreated":33,"status":"completed"` | All formats, as in the Python edition |
| 7 | `curl -s localhost:8001/api/v1/documents \| python3 -m json.tool` | 19 documents; id 3 is `acme-service-agreement.pdf` (2 chunks), id 9 `northstar-rate-card.xlsx` (2), id 14 `riverside-data-handling-policy.docx` (1) | The ids equal the Python edition's |
| 8 | Look at the stored fields: `curl -s -X POST localhost:26333/collections/java_document_chunks/points/scroll -H 'Content-Type: application/json' -d '{"limit":100,"with_payload":true}' \| python3 -m json.tool \| grep -E '"page"|"sheet"'` | `"page": 1` and `2`, `"sheet": "Rate card"` and `"SLA credits"` | Every chunk keeps where it came from |

Questions that use this (they need J3.3, `/chat/rag`): "What does Clause 7.3 say about the renewal notice period?" should cite the PDF, page 2; "Which plan costs 3400 USD a month and how many vehicles does it track?" should cite the Excel file, sheet Rate card; "How often are accounts with access to clinical data reviewed?" should cite the Word file with no page or sheet.

**Stop:** `Ctrl+C` in the `just run` terminal, then `just db-stop` and `just qdrant-stop`.

## J3.1: ingest Markdown documents

Needs: Java 21, Docker, `just`, and for the live steps (5 to 7) a free Google key (<https://aistudio.google.com/apikey>) exported as `GOOGLE_API_KEY` (never paste it into a chat or a commit). Steps 1 to 4 need no key. If the Python edition's stack is running, **leave its Qdrant alone**: the Java edition writes to its own collection, `java_document_chunks`.

```bash
git checkout main && git pull
just test              # chunker, parser, a fake-embedding ingestion test against real Postgres and Qdrant containers
just db                # scratch Postgres on localhost:25432
just qdrant            # scratch Qdrant: REST on 26333, gRPC on 26334 (skip if you use the Python stack's Qdrant)
```

Run the API with `QDRANT_GRPC_PORT=26334 just run` (add `GOOGLE_API_KEY=...` in front for the live steps; with the Python stack's Qdrant use the default port 6334). In a second terminal:

| # | Do | Expect | What it shows |
| --- | --- | --- | --- |
| 1 | `just test` | all tests pass; read `ChunkerTests`: the last test checks the 16 Markdown files against the Python edition's chunk counts | Same chunking as Python |
| 2 | `curl -s -X POST localhost:8001/api/v1/documents/ingest` with no key | `502` and `"error": "GOOGLE_API_KEY is not set..."` | A missing key says what to set |
| 3 | `curl -s localhost:8001/api/v1/documents/ingestion-runs \| python3 -m json.tool` | the failed run, with `"status": "failed"` | Every run is recorded |
| 4 | Remove one `type:` line from a copy of a document, point `SAMPLE_DATA_DIR` at that folder, ingest | `502` and `Document <name> is missing required metadata (title, type).` | A bad file is named |
| 5 | With the key: `curl -s -X POST localhost:8001/api/v1/documents/ingest` | `{"ingestionRunId":..,"documentsProcessed":19,"chunksCreated":33,"status":"completed"}` | The real Gemini and Qdrant path (since J3.2 all 19 files, 33 chunks, as in the Python edition) |
| 6 | `curl -s localhost:8001/api/v1/documents \| python3 -m json.tool \| head -30` | 19 documents, each with `chunkCount` (1 or 2) and its company | The chunk counts match the Python edition's for the same files |
| 7 | Run step 5 again, then `curl -s localhost:26333/collections/java_document_chunks \| python3 -m json.tool \| grep -E "points_count|size"` | `points_count` still 33 and `size` 768 | Re-ingesting never duplicates |

**Stop:** `Ctrl+C` in the `just run` terminal, then `just db-stop` and `just qdrant-stop`.

## J2.2: Spring AI client and `POST /api/v1/chat`

Needs: Java 21, Node 22, Docker, `just`, and for steps 5 to 8 a free OpenRouter key (<https://openrouter.ai/keys>) exported as `OPENROUTER_API_KEY` (never paste it into a chat or a commit). Steps 1 to 4 need no key. The Python edition's stack must be stopped (`docker compose stop api-py` in that repo), because both use port 8001.

```bash
git checkout main && git pull
just test              # all tests pass, including 8 against a fake OpenRouter and 14 for the chat route
just db                # scratch Postgres on localhost:25432
just run               # the API on http://localhost:8001 (add OPENROUTER_API_KEY=... in front for steps 5 to 8)
```

In a second terminal:

| # | Do | Expect | What it shows |
| --- | --- | --- | --- |
| 1 | `curl -si -X POST localhost:8001/api/v1/chat -H 'Content-Type: application/json' -d '{"messages":[]}'` | `422` and `"type":"too_short"` | Validation before any model call |
| 2 | the same with `-d '{"messages":[{"role":"user","content":"   "}]}'`, then `-d '{"messages":[{"role":"system","content":"hi"}]}'` | `value_error` ("content must not be blank"), then `literal_error` | The same errors FastAPI gives |
| 3 | `curl -si -X POST localhost:8001/api/v1/chat -H 'Content-Type: text/plain' -d 'hello'` | `422` `model_attributes_type`, not a 500 | A wrong content type is a client error |
| 4 | Start `just run` with no key, then step 1 body but with a valid message: `-d '{"messages":[{"role":"user","content":"hi"}]}'` | `503` and a message that names `OPENROUTER_API_KEY` | The no-key message |
| 5 | With the key: `curl -s -X POST localhost:8001/api/v1/chat -H 'Content-Type: application/json' -d '{"messages":[{"role":"user","content":"What is the capital of France?"}]}' \| python3 -m json.tool` | `message` says Paris; `usage` has `model`, `tokensIn`, `tokensOut`, `latencyMs`, `estimatedCost` 0.0, `fallbackUsed` false; `guardrail` is `{"flagged": false, "reasons": []}` | The real Spring AI call to OpenRouter |
| 6 | Send a conversation: messages `user` "My name is Murali.", `assistant` "Nice to meet you, Murali.", `user` "What is my name?" | The answer says Murali | History reaches the model in order |
| 7 | Add `"model":"not-a-real-model"` to a valid body | A real answer with `usage.fallbackUsed: true` and `fallbackFromModel: "not-a-real-model"`; or a 503 naming the models tried if the free models are rate-limited | The gateway's fallback chain with the real client. **Note which model answered.** |
| 8 | `just contract` | `OK`, including the chat checks | The shared contract |
| 9 | Optional: open http://localhost:5173 (`cd apps/web && npm ci && npm run dev`), mode **General chat**, ask the same question | The answer and a usage footer under it | The unchanged web app against the Java API |

**What is not here yet:** the prompt-injection tag (the orange "Potential prompt injection detected") needs the guard, which arrives in J6. In this stage a "reveal your system prompt" question is not flagged.

**Stop:** `Ctrl+C` in the `just run` terminal, then `just db-stop`.

## J2.1: the model gateway and the model list

Needs: Java 21, Docker (for `just test`). No key. Chat itself arrives in J2.2.

```bash
git pull
just test              # 31 tests: the CRM API (Testcontainers) and, new, 22 model-layer tests with no network
```

| # | Do | Expect | What it shows |
| --- | --- | --- | --- |
| 1 | `cd apps/api-java && ./mvnw -B -ntp test -Dtest=ModelGatewayTests` | 18 tests pass. Read the names: `a429FallsBackToTheNextModel`, `emptyAnswerFallsBackToTheNextModel`, `aModelThatJustFailedIsSkipped...` | The policy, exact and offline |
| 2 | Break it on purpose: in `ModelGateway.java` change `e.kind().retryable()` to `true`, run step 1 | 5 tests fail, among them `a429IsNotRetriedOnTheSameModel`. Undo with `git checkout apps/api-java/src/main` | The tests can fail |
| 3 | `just db`, `just run`, then `curl -s localhost:8001/api/v1/llm/models \| python3 -m json.tool \| head -20` | `"source": "live"`, about a dozen free models sorted by name, and one paid example (`openai/gpt-4o-mini`) | The live list from OpenRouter (no key needed) |
| 4 | Call it again | Answers in a few milliseconds | The five-minute cache |
| 5 | Disconnect the network (or block openrouter.ai), restart `just run`, call it | `"source": "fallback"` and the four models from `models.yaml`, `"paid": []` | A dropdown that still works offline |
| 6 | `python3 contracts/http/crm_contract.py http://localhost:8001` | `OK`, including the four `/llm/models` checks | The shared contract |
| 7 | Optional parity: run the Python edition on another port and `curl` its `/api/v1/llm/models` | The same keys and the same models (both read OpenRouter) | Same contract, two languages |

**Stop:** `Ctrl+C` in the `just run` terminal, then `just db-stop`.

## J1: the CRM API (health, companies, tasks)

Needs: Java 21, Node 22, Docker (for the tests and a throw-away Postgres), `just`. No keys. The web app's address is fixed at port 8001, so **stop the Python edition's stack first** if it is running.

```bash
git pull
just test              # 9 tests over real HTTP; Testcontainers starts Postgres for you (about a minute)
just db                # throw-away Postgres on localhost:25432
just run               # the API on http://localhost:8001 (leave it running; Flyway builds the schema and demo data)
```

In a second terminal:

| # | Do | Expect | What it shows |
| --- | --- | --- | --- |
| 1 | `curl -s localhost:8001/health` | `{"status":"ok"}` | The app is up and reaches Postgres |
| 2 | `curl -s localhost:8001/api/v1/companies \| python3 -m json.tool \| head -20` | 7 companies sorted by name, each with `relationship_status`, `deal_count`, `interaction_count` | The demo data came from Flyway |
| 3 | `curl -s localhost:8001/api/v1/companies/1 \| python3 -m json.tool` | Acme Fabrication with 2 contacts, 2 deals (`"amount": "48000.00"`) and 4 interactions (`"occurred_at": "2026-09-20T15:00:00+00:00"` first) | The detail shape |
| 4 | `curl -si localhost:8001/api/v1/companies/9999` | `404` and `{"error":"Company not found.","detail":"Company not found."}` | The shared error body |
| 5 | `curl -si localhost:8001/api/v1/companies/abc` | `422` and a `detail` list naming `company_id` | Same body as the Python edition (a copied gap, see port-status) |
| 6 | `curl -si -H 'X-Request-ID: my-id_1' localhost:8001/health` | The header comes back as `my-id_1`; with `'bad id!'` it is replaced | The request id rules |
| 7 | `just contract` | `OK: the CRM contract holds against http://localhost:8001` | The shared HTTP check |
| 8 | `docker exec cc-java-db psql -U customer_compass -d customer_compass -c "select version, description from flyway_schema_history"` | Two rows: `1 baseline schema`, `2 demo seed data` | Flyway recorded what it ran |
| 9 | `cd apps/web && npm ci && npm run dev`, open http://localhost:5173 | The company list; click Acme Fabrication and see its contacts, deals and interactions | The unchanged web app works against the Java API |
| 10 | Add a task, then list it: `docker exec cc-java-db psql -U customer_compass -d customer_compass -c "insert into tasks (company_id, note) values (1, 'TEST: try')"` then `curl -s "localhost:8001/api/v1/tasks?company_id=1"` | One task with `company_name` Acme Fabrication | The tasks route and its filter |

**Optional, to see both editions agree:** run the Python edition on another port against its own database and run `python3 contracts/http/crm_contract.py http://localhost:<port>`; it passes on both.

**Stop:** `Ctrl+C` in the `just run` and `npm run dev` terminals, then `just db-stop` (the data goes with the container).

## J0.3: build tooling and CI

Needs: Java 21 and Node 22. No keys, no Docker.

```bash
git pull
just test            # Maven: compile, run the tests, check formatting and style (about a minute the first time)
just test-web        # web app: lint, types, 16 tests, build
```

| # | Do | Expect | What it shows |
| --- | --- | --- | --- |
| 1 | `just test` | `Tests run: 1, Failures: 0` and `BUILD SUCCESS` | The empty app starts and the checks pass |
| 2 | Break the format on purpose: add three blank lines and odd spacing to `apps/api-java/src/main/java/com/customercompass/api/Application.java`, then `just test` | `BUILD FAILURE` from `spotless:check`. Then `just fmt` and `just test` pass again | The formatter is enforced |
| 3 | Break the style on purpose: add `import java.util.*;` to the same file, run `just fmt` then `just test` | `BUILD FAILURE` from Checkstyle (star import) | The style rules are enforced. Undo with `git checkout apps/api-java` |
| 4 | `cat apps/api-java/.mvn/wrapper/maven-wrapper.properties` | The address starts with `https://repo.maven.apache.org/` | The wrapper does not point at a private mirror |
| 5 | `just test-web` | 16 passed | The shared web app still builds here |
| 6 | Open the pull request's Checks tab | api, web, docs and security all green | CI runs the same commands |

**Stop:** nothing is left running.
