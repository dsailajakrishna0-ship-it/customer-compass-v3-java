# Customer Compass V3, Java edition

The same CRM and AI assistant as [`customer-compass-v3`](https://github.com/dsailajakrishna0-ship-it/customer-compass-v3), rebuilt on the Java stack: **Spring Boot, Spring AI, Flyway, Postgres, Qdrant**. It is an AI learning project that uses only synthetic data.

**The point:** the API contract is the same as the Python edition's, so the same React web app and the same contract tests run against either backend, and you can read the two implementations side by side.

| Python edition (`customer-compass-v3`) | Java edition (this repo) |
| --- | --- |
| FastAPI, asyncpg, Alembic | Spring Boot, JDBC or Spring Data, Flyway |
| LiteLLM gateway to OpenRouter | Spring AI `ChatClient` to OpenRouter, with our own fallback chain |
| Qdrant client, Gemini embeddings | Spring AI `VectorStore` (Qdrant), Gemini embeddings |
| FastMCP and Spring AI tool servers | Spring AI MCP server and client |
| LangGraph agent with a Postgres checkpointer | Graph with a durable pause (choice recorded in an ADR) |

## How this repo is run

- Plan: [`docs/roadmap.md`](docs/roadmap.md). Each sub-stage is one pull request, squash-merged by the owner.
- This edition follows the Python edition by about one stage; [`docs/port-status.md`](docs/port-status.md) says where it is.
- Guide: [`docs/agent-tool-calling-guide.md`](docs/agent-tool-calling-guide.md): enabling tool calling for a few of this app's APIs, what to do at both ends (the app and the agent).
- A later part of the plan adds a **no-framework** version (plain Java), to compare with the framework one.
- Sibling project, planned: `claims-compass-v1` (insurance claims, Java).

Status (2026-11-28): **J0 to J6 merged, J7.1 to J7.4 merged, and the J8 first round (seven modules built several ways) merged**, plus the Java-only extensions E1 and E3. What is still open is listed in [`docs/roadmap.md`](docs/roadmap.md) and [`docs/port-status.md`](docs/port-status.md); how to test it is in [`docs/smoke-test.md`](docs/smoke-test.md) and [`docs/test-stages.md`](docs/test-stages.md).

Build and check: `just test` (Java, needs Docker) and `just test-web` (web app); run it with `just db` then `just run` (port 8001). See [`docs/testing-guide.md`](docs/testing-guide.md).

Licence: MIT. Security: see [SECURITY.md](SECURITY.md).
