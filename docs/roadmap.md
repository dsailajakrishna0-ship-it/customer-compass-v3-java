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
| J7 | Evals and observability: J7.1 adversarial set on the guard, J7.2 retrieval golden set, J7.3 tracing, J7.4 guard comparison with the classifier, J7.5 tool and agent golden cases ([ADR 0025](adr/0025-evals-as-shared-files-and-replayable-baselines.md)) | Stage 5 | J7.1 merged; J7.2 (retrieval golden set, harness) in review |
| J8 | Approaches compared, each in its own clearly named folder (see "Stage J8" below): J8.1 plain Java version of the core flow, J8.2 tool calling three ways, J8.3 the agent graph several ways, and the comparison write-up | none (Java only) | Planned |
| E3 | Java extension: a supervisor with specialist agents; see [java-extensions.md](java-extensions.md) | none (Java only) | E3.1 (the supervisor graph) merged; E3.2 (answer team, supervisor-written queries) merged; E3.3 (comparison) in review |
| E1 | Java extension: multi-source RAG with routing (several knowledge bases the model searches across); see [java-extensions.md](java-extensions.md) | none (Java only) | E1.1 (second knowledge base) merged; E1.2 (routing strategies) merged; E1.3 (comparison) merged; E1.4 (per-corpus similarity floor) in review |

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

## Stage J8: the same flow, several approaches

Each comparison builds **one small flow several ways** and puts every way in its own folder, named for the approach, so a reader can open one folder and read exactly one approach. These are comparison modules under `comparisons/`, separate from the main app (the app does not depend on them). Each compares lines of code, what the approach hides, and what it lets you control (turn limits, a guard on every tool result, failure behaviour). Any live recording needs the owner's approval first.

| Slice | Question | Folders (one per approach) |
| --- | --- | --- |
| J8.1 | The core flow with no framework at all | `comparisons/plain-java/handWritten` (the whole flow in plain Java) next to what the app does today |
| J8.2 | Tool calling: how does the model call tools? Flow: a CRM question that needs the CRM tools ("What open deals does Acme Fabrication have?") | `comparisons/tool-calling/handWritten` (our own tool loop, the way the app does it today), `comparisons/tool-calling/withSpringAi` (Spring AI `@Tool` and `ChatClient`), `comparisons/tool-calling/withLangchain4j` (LangChain4j `@Tool` and `@AiService`) |
| J8.3 | The agent graph: how is a multi-step workflow with state, branches and an approval pause built? Flow: a small version of the agent (analyse, review, propose, pause for approval) | `comparisons/agent-graph/handWritten` (a plain Java state machine), `comparisons/agent-graph/withLangGraph4j` (what the app uses), plus, if their own documentation shows a fit, `withLangchain4jAgentic` (LangChain4j's agentic workflows) and `withSpringAiWorkflows` (Spring AI workflow patterns) |

Notes: the folder names are fixed in advance so every slice follows them; an extra approach (for example Embabel or Google ADK) is added only if the owner asks, in its own `withEmbabel` or `withAdk` folder. Library versions are checked on each project's own page before a dependency is added, and the dependencies live in the comparison modules only. The J8.3 candidates marked "if their own documentation shows a fit" are not yet checked.

## Rules for this plan

- Java stage *n* starts only when Python stage *n* is merged and `port-status.md` says the contract is stable.
- A contract change is made in the Python edition first.
- Verify versions and free-tier limits on the project's own pages before adopting anything.
