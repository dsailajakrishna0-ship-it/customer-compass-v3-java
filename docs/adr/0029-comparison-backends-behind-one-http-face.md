# ADR 0029: the comparison approaches are separate backends behind one shared HTTP face (J8.1a)

Status: accepted, 2026-11-15. Follows the J8 plan in `docs/roadmap.md` (option A: separate backends).

## Context

J8 builds one module several ways (`handWritten`, `withSpringAi`, `withLangchain4j`) and lets the owner choose one in the web app. The first module is tool calling. The plan says the application must not gain a second AI framework, and each approach must be readable on its own.

## Decision

1. **Separate Maven modules under `comparisons/`**, with their own build and dependencies. They never import or depend on `apps/api-java`; they talk to it over HTTP. (The Spring Boot parent is used only for its dependency versions.)
2. **One shared HTTP face in `comparisons/common`**: `POST /api/v1/chat/tools` with the application's request and response shape, and `GET /health`, built on the JDK's own HTTP server, so no approach brings a web framework with it. An approach implements one interface, `Chat.ToolChat`, and everything the approaches differ in is behind it.
3. **The same two read-only tools for every approach** (`get_company_summary`, `get_open_deals`), following `contracts/mcp-tools.json`, reading the application's CRM routes over HTTP. The writing tools are left out: the confirmation rule for writes belongs to the application, and the comparison is about how a model calls a tool.
4. **`handWritten` uses only the JDK and Jackson**: it speaks the OpenAI-compatible chat API (OpenRouter by default) directly and writes the loop itself, with the application's limits (4 rounds, 4 calls per reply, a final answer with tools switched off) and the tool results wrapped as untrusted data.
5. **The web app's chooser changes only the address of the tool-calling route.** The comparison backends serve only that route and `/health`, so every other call keeps going to the application. The chooser is a list in one file (`apps/web/src/api/approaches.ts`); the default is the application itself and nothing changes when it is chosen.

## Known differences from the application's route (stated so nobody mistakes them for parity)

- No prompt guard, so the response has no `guardrail`; no tracing; no audit log; no fallback chain across models (one model per request).
- No `mcpServer` choice (the field is the approach name, "via handWritten").
- A bad request gets a short `detail` message, not FastAPI's full validation list.
- Two tools, not four.

## What was checked, and what the check found

The CRM shape was first assumed from the application's summary type: `GET /api/v1/companies` returning each company with its open deals. The live test against the running application showed it returns only a list (id, name, industry, `relationship_status`, counts), and that deals (`status` open, won or lost, `expected_close_date`) come from `GET /api/v1/companies/{id}`. The client and the tools were changed to follow the real routes, and `CrmToolsLiveTests` now runs the contract's cases against the real application (`just comparisons-test-live`), so the assumption cannot silently drift.

## Consequences

- The web app in this repository now differs from the Python edition's copy (the Approach buttons); a port to the Python edition's web app would be its own PR if wanted.
- Each later approach is a module with its own dependencies; the second and third (Spring AI, LangChain4j) are separate slices, as is the lines-of-code and "what it hides" write-up. Nothing is claimed here about how the approaches compare: that needs all three.
