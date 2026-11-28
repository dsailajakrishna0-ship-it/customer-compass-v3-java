# Roadmap: Customer Compass V3, Java edition

Goal: the same product and API contract as the Python edition, built with the Java stack, so the two can be read side by side. Each sub-stage is one PR, squash-merged by the owner. Java stage J*n* ports Python stage *n* **after** that stage is merged there ([`port-status.md`](port-status.md)).

## Status

| Stage | Name | Ports (Python edition) | Status |
| --- | --- | --- | --- |
| J0 | Foundation: repo, shared assets, CI | Stage 0 | Merged |
| J1 | Spring Boot skeleton, Postgres + Flyway, CRM API | Stage 0 (V2 base) | Merged |
| J2 | Model layer: Spring AI chat, fallback chain, usage, `/llm/models`, HTTP contract tests | Stage 1, 3.1 | Merged |
| J3 | Documents and RAG: Spring AI `VectorStore` (Qdrant), Gemini embeddings, `/documents/*`, `/chat/rag`, `/chat/crm`; cut into J3.1 Markdown ingestion, J3.2 PDF, Word and Excel, J3.3 retrieval and `/chat/rag`, J3.4 `/chat/crm`, J3.5 optional reranker, J3.6 hybrid search | V2 base, Stage 4 | Merged (J3.1 to J3.6, including the optional reranker and hybrid search) |
| J4 | Tools and MCP: Spring AI MCP server and client, `/chat/tools`, `/mcp/servers` | Stage 2 | Merged |
| J5 | The agent: graph, human approval with a durable pause, `/agent/recommend` and `/agent/confirm` | Stage 3.2 | Merged (J5.1 graph, J5.2 durable pause) |
| J6 | Guardrails and audit: prompt guard, output guard, audit logs | V2 base | Merged (J6.1 guard and audit log, J6.2 agent guard and LLM classifier) |
| J7 | Evals and observability: J7.1 adversarial set on the guard, J7.2 retrieval golden set, J7.3 tracing, J7.4 guard comparison with the classifier, J7.5 tool and agent golden cases ([ADR 0025](adr/0025-evals-as-shared-files-and-replayable-baselines.md)) | Stage 5 | J7.1 to J7.4 merged (tracing: [ADR 0027](adr/0027-tracing-with-opentelemetry.md); guard comparison harness: [ADR 0028](adr/0028-guard-comparison-over-a-dry-run-route.md)); J7.5 harness built ([ADR 0054](adr/0054-tool-and-agent-golden-cases-over-http.md)). Open: the J7.4 and J7.5 recordings (each needs your approval to run with keys) |
| J8 | Seven modules each built several ways, one named folder per approach (see "Stage J8" below): tool calling, RAG ingestion, RAG retrieval, observability, the agent graph, memory and the multi-agent supervisor; the approach is chosen in the web app; the other modules are future scope | none (Java only) | First round merged: J8.1 tool calling, J8.2 RAG ingestion, J8.3 RAG retrieval, J8.4 observability, J8.5 agent graph, J8.6 memory, J8.7 multi-agent patterns (each with its side-by-side write-up). The sections below were written before the slices were built |
| E3 | Java extension: a supervisor with specialist agents; see [java-extensions.md](java-extensions.md) | none (Java only) | E3.1 (the supervisor graph) merged; E3.2 (answer team, supervisor-written queries) merged; E3.3 (comparison) merged |
| E1 | Java extension: multi-source RAG with routing (several knowledge bases the model searches across); see [java-extensions.md](java-extensions.md) | none (Java only) | E1.1 (second knowledge base) merged; E1.2 (routing strategies) merged; E1.3 (comparison) merged; E1.4 (per-corpus similarity floor) merged |

## Stage J0: Foundation

| Sub-stage | Tasks | Exit criteria |
| --- | --- | --- |
| **J0.1 Plan** | README, `CLAUDE.md`, CONTRIBUTING, SECURITY, this roadmap, `port-status.md`. | The plan is merged. |
| **J0.2 Shared assets** | Copy from the Python edition at a recorded commit: the web app (`apps/web`), `sample-data/`, `contracts/`, `evals/golden`. Record the commit in the PR. | Web app builds; nothing Java yet. |
| **J0.3 Tooling and CI** | Maven wrapper, formatter (Spotless), Checkstyle or Error Prone, a `justfile`, GitHub Actions (build, test, secret scan), `.env.example` with placeholders. | `just test` and CI pass on an empty app. |

## Stage J1: Skeleton and CRM API

- Spring Boot 4.1.x app `apps/api-java` with `GET /health`.
- Flyway migrations equal to the Python edition's Alembic ones (same tables, same demo data).
- `GET /api/v1/companies`, `GET /api/v1/companies/{id}`, `GET /api/v1/tasks`, the error shape from `docs/api-reference.md`.
- **Exit:** the contract tests (as HTTP tests run against a base URL) pass for these routes; the web app's company list works against the Java API.

## Stage J2: Model layer

- Spring AI chat client to OpenRouter; our own fallback chain from `models.yaml`-style config, empty reply counted as a failure (as in Python PR #48); the usage footer; `GET /api/v1/llm/models`; `POST /api/v1/chat`.
- The contract tests become **HTTP-level** so the same file runs against the Python and the Java API (a shared `contract-tests` approach, recorded in an ADR).

## Stage J3: Documents and RAG

- Spring AI `VectorStore` on Qdrant, Gemini embeddings, ingestion and search, `POST /chat/rag`, `POST /chat/crm`.

## Stage J4: Tools and MCP

- Reuse `services/mcp-java` from the Python edition as the starting point; the web app's tool-server radio keeps working.

## Stage J5: The agent

- ADR first: Spring AI workflow patterns, or LangGraph4j. Same behaviour and trace as Python; the pause survives a restart (the same exit test).

## Stage J6 to J8

Guardrails and audit (J6), evals and observability (J7), then a **plain Java** version of the core flow with no framework, plus a short comparison: lines of code, what the framework gave us, what it hid (J8).

## Stage J8: each module built several ways

The idea: take one module of an AI application, build it **several ways** with the same behaviour, and put every way in its own folder named for the approach, so a reader opens one folder and reads exactly one approach. The approach names are fixed and used everywhere:

| Folder name | What it is |
| --- | --- |
| `handWritten` | Plain Java, no AI framework: our own code against the model's API |
| `withSpringAi` | Spring AI's own building blocks (`ChatClient`, `@Tool`, advisors, chat memory, vector store, structured output) |
| `withLangchain4j` | LangChain4j (`@AiService`, `@Tool`, chat memory, retrievers, structured output) |
| `withLangGraph4j` | LangGraph4j: for graphs, workflows and agents only (what the app uses today) |

An approach is added to a module only where its library has that feature; a missing folder means the library has nothing there, and the write-up says so. Extra approaches (Embabel, Google ADK) can be added later as `withEmbabel` and `withAdk`. If their own documentation shows a fit, `withLangchain4jAgentic` and `withSpringAiWorkflows` can be added for the agent graph; this is not yet checked.

### The modules

**First round (decided by the owner, to get to know the latest frameworks without overloading):**

| Slice | Module | Approaches to build | Today in the app |
| --- | --- | --- | --- |
| J8.1 | Tool calling (the template: built first) | `handWritten`, `withSpringAi`, `withLangchain4j` | Our own tool loop plus an MCP server |
| J8.2 | RAG ingestion (read, split, embed, store) | `handWritten`, `withSpringAi`, `withLangchain4j` | Our readers and chunker, Spring AI embeddings and store |
| J8.3 | RAG retrieval (search, hybrid, reranker, similarity floor) | `handWritten`, `withSpringAi`, `withLangchain4j` | Spring AI Qdrant store plus our own retrieval |
| J8.4 | Observability (traces, metrics, model and tool call records) | `handWritten`, `withSpringAi`, `withLangchain4j` | Our OpenTelemetry spans and optional Langfuse export (J7.3) |
| J8.5 | The agent graph with the approval pause | `handWritten`, `withLangGraph4j` (maybe `withLangchain4jAgentic`, `withSpringAiWorkflows`) | LangGraph4j |
| J8.6 | Memory (conversation history that the server keeps between requests; later long-term facts) | `handWritten`, `withSpringAi`, `withLangchain4j` | None: the client sends the messages each time. A new feature in every approach. |
| J8.7 | Multi-agent patterns: supervisor, hierarchical, network, handoff (rules in code). **Plan changed, ADR 0045:** the four patterns on LangGraph4j with Spring AI as the model layer; no hand-written path; other frameworks by a verified survey | `multi-agent/{supervisor, hierarchical, network, handoff}` on LangGraph4j | Our supervisor on LangGraph4j (E3) |

Each approach exists only where its library has the feature. For every slice the library's own documentation was read before the slice was built; the result is in each slice's ADR and write-up (for example [ADR 0045](adr/0045-multi-agent-patterns-on-langgraph4j.md) for J8.7).

**Memory is a new feature, not just a comparison.** The app keeps no conversation memory today (the client sends 1 to 12 messages per request), and the HTTP contract is shared with the Python edition, so a server-kept conversation needs a decision before J8.6 starts: either a Java-only optional field in the comparison backends (for example a conversation id), or a contract change made in the Python edition first. Nothing changes in the main app either way.

**Future scope (not planned yet):** model layer, prompts and structured output, guardrails, long-term memory beyond conversation history, streaming and multimodal.

### Layout

```
comparisons/
  common/                      shared code every approach uses: the HTTP contract types, sample data loading,
                               small utilities, the evaluation questions (kept out of the approach folders)
  tool-calling/{handWritten, withSpringAi, withLangchain4j}
  rag-ingestion/{handWritten, withSpringAi, withLangchain4j}
  rag-retrieval/{handWritten, withSpringAi, withLangchain4j}
  observability/{handWritten, withSpringAi, withLangchain4j}
  agent-graph/{handWritten, withLangGraph4j, ...}
  memory/{handWritten, withSpringAi, withLangchain4j}
  multi-agent/{supervisor, hierarchical, network, handoff}/withLangGraph4jSpringAi   (J8.7: patterns on LangGraph4j with Spring AI as the model layer, ADR 0045; the folder names the concept, the pattern and the framework)
```

Rules: every comparison module is separate from the main app and holds its own dependencies, so the app never gains a second AI framework. Each module gets a short write-up with lines of code, what the approach hides and what it lets you control (turn limits, a guard on every tool result, failure behaviour). Library versions are checked on each project's own page before a dependency is added. Any live recording needs the owner's approval. Folder names are fixed so every slice follows them.

### Choosing an approach in the web app (decided: separate backends)

Today the web app has no switch between the Python and Java editions: it calls one fixed address (`http://localhost:8001`), and you choose by starting one backend on that port. The owner chose **option A**: each approach is a small backend that serves the same routes of the shared contract on its own port; the web app gets an "Approach" drop-down that switches the base address, and the contract tests run against each backend. No second framework enters the main app. Consequence: each compared module needs a runnable backend, so each approach is a bigger piece of work than a code sample; the chooser itself (the drop-down and the list of backends) is built with the first slice, J8.1. The rejected option B (one app with a per-request setting) would have put every framework in the main app.

## Rules for this plan

- Java stage *n* starts only when Python stage *n* is merged and `port-status.md` says the contract is stable.
- A contract change is made in the Python edition first.
- Verify versions and free-tier limits on the project's own pages before adopting anything.
