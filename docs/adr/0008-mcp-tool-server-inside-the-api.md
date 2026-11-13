# ADR 0008: the MCP tool server runs inside the API (J4.1)

Status: accepted, 2026-11-13 (decided with the owner).

## Context

The Python edition serves its four tools (`get_company_summary`, `get_open_deals`, `search_documents`, `create_followup_task`) from separate tool servers, because there the tool server is a different language and framework from the API. There is also a small Spring Boot tool server in that repository (`services/mcp-java`) that calls the Python API over HTTP for document search. The Java edition is one language and one framework end to end, so the question is where its tool server lives.

## Decision

- **Inside the API.** The tools are `@McpTool` methods of a Spring component, and Spring AI's MCP server starter (`spring-ai-starter-mcp-server-webmvc`, streamable HTTP) serves them at `http://localhost:8001/mcp`, on the application's own port. One process, one database connection, one image.
- **Direct calls, no HTTP hop.** `search_documents` calls the retriever in the same process (the one `/chat/rag` uses); the CRM tools call the same facts service as `/chat/crm`. The old Java tool server needed an HTTP client to the Python API for this; there is nothing to call over the network here.
- **Same contract as the Python servers.** Names, argument names (snake_case, as the contract says: Spring AI takes argument names from the Java parameter names), answers and audit rows follow `contracts/mcp-tools.json`. The Python edition's own checker (`contracts/check_server.py`) passes against this server (`OK (4 cases)`), and so does a JUnit test that talks to it with a real MCP client.
- **A write is only ever proposed here.** `create_followup_task` writes a task only when `confirm` is true. The model never sets `confirm`: the chat route (J4.3) decides it from the user's own latest message, as in the Python edition.
- **Every call is audited** in `tool_audit_log` (`executed`, `proposed`, `confirmed` or `error`), including refusals.

## Alternatives considered

- **A separate tool-server application** (as in the Python repository): a second `pom.xml`, image, port and CI job, a second database connection, and a network call back to the API for document search. It would give a piece other programs can use without the whole API; nothing needs that today. Moving the tools out later does not change them: the contract is the boundary.

## Consequences

- The tool server shares the API's lifetime and port. It is on whenever the API is on, and there is no "tool server not reachable" state to handle for the Java server (`GET /mcp/servers`, J4.3, will list one server).
- The MCP endpoint is open like the rest of the API; authentication is a later stage (roadmap 6.1) for both.
- Document search through the tool inherits the retrieval limits of J3.3 (dense search, the 0.45 floor). Retrieved text is returned as it is; the chat loop wraps it as untrusted data before the model sees it (J4.3).
- When Qdrant is down, a search tool call fails with the same clear message as the chat routes ("The vector store (Qdrant) is not reachable. Start it (just qdrant) ..."), which was added in this stage after the first live run showed an unhelpful error.
