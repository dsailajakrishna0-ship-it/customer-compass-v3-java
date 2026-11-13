# Stage J4: tools and MCP

Ports Python Stage 2 (native tool calling, MCP tool servers, `/chat/tools`, `/mcp/servers`). Decisions: [ADR 0008](../adr/0008-mcp-tool-server-inside-the-api.md). Cut into slices: **J4.1** the tool server (this page), J4.2 tool calling in the model layer, J4.3 `POST /chat/tools`, `GET /mcp/servers`, the audit log route and the confirm rule.

## J4.1: the MCP tool server, inside the API

```mermaid
flowchart LR
  client["any MCP client<br/>(a test, check_server.py,<br/>later our own chat loop)"] -->|"streamable HTTP"| mcp["/mcp<br/>Spring AI MCP server"]
  mcp --> tools["CrmTools<br/>four @McpTool methods"]
  tools --> facts["CrmFacts<br/>SQL"]
  tools --> ret["Retriever<br/>Qdrant search"]
  tools --> log["ToolLog<br/>audit rows, tasks"]
  facts --> pg[("Postgres")]
  log --> pg
  ret --> qd[("Qdrant")]
```

| Piece | File | Job | Python twin |
| --- | --- | --- | --- |
| Tools | `mcp/CrmTools.java` | the four tools, as `@McpTool` methods | `app/mcp/tools.py`, `server.py` |
| Writes | `mcp/ToolLog.java` | audit rows and the one write (a task) | `_log_tool_call` |
| Company match | `crm/CompanySummary.CompanyRef.findMentioned` | "Acme" finds "Acme Fabrication" | `find_mentioned_company` |
| Settings | `application.yml` (`spring.ai.mcp.server`) | the endpoint and the server name | `app/mcp/server.py` |
| Contract | `contracts/mcp-tools.json`, `contracts/check_server.py` | the one description both editions follow | the same files |

### Concepts

1. **A tool is a described function.** A method marked `@McpTool` with described parameters is all Spring AI needs: it builds the tool list and the JSON Schema a model reads, and routes a call to the method.
2. **MCP is a protocol, not a library.** Any client that speaks it (our own tests, the Python checker, a desktop app) can list and call these tools without knowing anything about Java.
3. **A contract file settles "equivalent".** Two servers in two languages are interchangeable when the same file says what they offer and a checker runs every case against both. Here the checker was written for the Python servers and passes unchanged.
4. **The argument name is the parameter name.** Spring AI cannot rename a tool argument, so the Java parameters are `company_name`, not `companyName`, exactly as the contract says.
5. **A read tool and a write tool are different animals.** Reading is audited and returns facts. The write only proposes unless it is told to confirm, and the one that tells it is the chat route, from the user's own words, never the model.
6. **Untrusted text stays untrusted.** `search_documents` returns document text as it is; whoever puts it in front of a model must wrap it as data (J4.3).

### Questions

1. Why is the tool server inside the API and not a second application? What would make you move it out?
2. The contract says `company_name`. What would happen if the Java parameter were called `companyName`?
3. `create_followup_task` is called without `confirm`. What is returned, what is written to the database, and what is written to the audit log?
4. "Acme" and "Acme Fabrication" both find the company. Which rule does that, and where else is it used?
5. Qdrant is stopped and the model asks for `search_documents`. What does it get back, and why is that better than a generic error?

### Code tour

`CrmTools.java`, then `McpToolsTests.java` (a real MCP client, the contract file, a real Postgres), then `ToolLog.java`. To see the same server from outside: `just mcp-contract` and `just mcp-call get_open_deals '{"company_name": "Acme"}'`.
