# ADR 0010: the tools chat loop, the server registry and the confirm rule (J4.3)

Status: accepted, 2026-11-13.

## Context

`POST /chat/tools` (Python mode 4) lets the model call the four tools instead of answering: the loop offers the tools, runs the ones the model asks for, puts the results back and asks again. Three decisions are not obvious: who runs the tools and how the loop finds them, which tool servers can be chosen, and who decides that a write has been confirmed.

## Decision

- **The loop is an MCP client, even of its own server.** It connects to the tool server over streamable HTTP, lists the tools, and calls them with the MCP SDK's client. For the default server that is this application's own `/mcp` (J4.1), reached on the port it listens on. It costs one local HTTP round trip per tool call and buys three things: tool discovery and execution go through the protocol exactly as for any other client, the Python edition's FastMCP server can be driven by the same code (set `MCP_FASTMCP_URL`), and the path a model's call takes is the one a desktop MCP client would take.
- **Two server ids, like the Python edition's registry.** `springboot` is always configured (the server inside this API); `fastmcp` is configured only when `MCP_FASTMCP_URL` is set. `GET /mcp/servers` lists both with `configured` and `reachable` (a two-second probe), so the web app's radio buttons work unchanged; a request that names an unknown or unconfigured server gets a 400 that says which setting to change. The default is `springboot` (`MCP_BACKEND`).
- **Arguments are validated before any tool runs**, from a schema that is also what the model is shown (`ToolSchemas`): required strings, a 500-character note, no extra arguments. Every problem comes back to the model as a tool result in Pydantic's wording, never as a server error, so the model can correct itself; a call that failed validation is not recorded as "used".
- **The confirm rule: the user's words, not the model's.** The tool schema for `create_followup_task` has no `confirm` argument, so a model that sends one is refused ("Extra inputs are not permitted"). The loop adds `confirm` itself: true only when the user's own latest message contains an affirmative word or phrase (yes, confirm, approve, go ahead, do it, proceed). It is a plain substring test with no state kept between turns, as in the Python edition ("yes" also matches inside "eyes"; a known limit).
- **Limits:** at most 4 rounds of tool calls and 4 tools per model reply; after the fourth round with the model still asking, one last call with tool choice `none` and an instruction to answer from what it has.
- **Tool results are data.** Each result goes into the conversation wrapped in untrusted-context tags, tied to the model's call id. The scan for injected commands, and the audit of what it finds, are stage J6.
- **Failures are specific:** the model chain failing is a 503 with the models tried; the tool server not answering is a 503 naming the server; a tool that throws (Qdrant down, for example) becomes a tool result the model can read.

## Consequences

- The model can propose a task but cannot create one. A write needs two turns and the user's own "yes", and each step is in the audit log (`proposed`, then `confirmed`).
- The loop works with a small free model only as well as the model makes structured tool calls. The 2.6B default usually does; a larger model (Nemotron, Gemma) is steadier. When a model is rate-limited the gateway's fallback chain moves on, as for every other route.
- A second tool server needs no new code: it needs the same four tools and the shared contract (`just mcp-contract <url>`).
- The tool results and the tool arguments are not scanned or redacted yet, and the MCP endpoint is not authenticated (roadmap 6.1).
