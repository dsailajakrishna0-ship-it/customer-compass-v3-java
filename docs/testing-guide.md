# Testing guide

One section per PR, newest first. Each says how to start, what to run, what you should see, and how to stop.

> **If `just db` or `just test` fails with a crashing Postgres** (`Segmentation fault`, "general protection fault" in the kernel log): the Debian image `postgres:17.11` crashes on some AVX-512 laptops. The scratch database and the tests use `postgres:17.11-alpine` (same version, Alpine build). Pull this change and run `just db-stop` (if one is running), then `just db` again. The data in `just db` is throw-away, so nothing is lost.

## J2.2: Spring AI client and `POST /api/v1/chat`

Needs: Java 21, Node 22, Docker, `just`, and for steps 5 to 8 a free OpenRouter key (<https://openrouter.ai/keys>) exported as `OPENROUTER_API_KEY` (never paste it into a chat or a commit). Steps 1 to 4 need no key. The Python edition's stack must be stopped (`docker compose stop api-py` in that repo), because both use port 8001.

```bash
git checkout main && git pull
just test              # all tests pass, including 8 against a fake OpenRouter and 14 for the chat route
just db                # scratch Postgres on localhost:55432
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
just db                # throw-away Postgres on localhost:55432
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
