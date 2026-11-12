# Roadmap: Customer Compass V3, Java edition

Goal: the same product and API contract as the Python edition, built with the Java stack, so the two can be read side by side. Each sub-stage is one PR, squash-merged by the owner. Java stage J*n* ports Python stage *n* **after** that stage is merged there ([`port-status.md`](port-status.md)).

## Status

| Stage | Name | Ports (Python edition) | Status |
| --- | --- | --- | --- |
| J0 | Foundation: repo, shared assets, CI | Stage 0 | J0.1 and J0.2 merged; J0.3 in review |
| J1 | Spring Boot skeleton, Postgres + Flyway, CRM API | Stage 0 (V2 base) | In review (stacked on J0.3) |
| J2 | Model layer: Spring AI chat, fallback chain, usage, `/llm/models`, HTTP contract tests | Stage 1, 3.1 | J2.1 gateway and model list merged; J2.2 Spring AI chat in review |
| J3 | Documents and RAG: Spring AI `VectorStore` (Qdrant), Gemini embeddings, `/documents/*`, `/chat/rag`, `/chat/crm`; cut into J3.1 Markdown ingestion, J3.2 PDF, Word and Excel, J3.3 retrieval and `/chat/rag`, J3.4 `/chat/crm` | V2 base, Stage 4 | J3.1 merged; J3.2 in review |
| J4 | Tools and MCP: Spring AI MCP server and client, `/chat/tools`, `/mcp/servers` | Stage 2 | Planned |
| J5 | The agent: graph, human approval with a durable pause, `/agent/recommend` and `/agent/confirm` | Stage 3.2 | Planned |
| J6 | Guardrails and audit: prompt guard, output guard, audit logs | V2 base | Planned |
| J7 | Evals and observability | Stage 5 | Planned |
| J8 | The no-framework version (plain Java) and the comparison write-up | none | Idea |

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

## Rules for this plan

- Java stage *n* starts only when Python stage *n* is merged and `port-status.md` says the contract is stable.
- A contract change is made in the Python edition first.
- Verify versions and free-tier limits on the project's own pages before adopting anything.
