# Port status

Which stages of the Python edition (`customer-compass-v3`) are merged, and whether the Java edition has ported them. The Java session reads the Python edition's `main` before starting a stage; this table is the summary.

Last checked against `customer-compass-v3` `main` at commit `99c59d8` (Stage 4.2 merged; 4.3 in review), 2026-11-07.

| Python stage | Merged there | API contract changed? | Java stage | Java status |
| --- | --- | --- | --- | --- |
| 0 Foundation | Yes | n/a | J0, J1 | J0 and J1 merged |
| 1 Model layer | Yes | Gateway, usage footer, `/llm/models` | J2 | J2 merged (gateway, `/llm/models`, Spring AI client, `POST /chat`) |
| 2 Tools and MCP | 2.1, 2.2a, 2.2b | `/chat/tools`, `/mcp/servers`, `contracts/mcp-tools.json` | J4 | J4 merged (MCP server inside the API, `/chat/tools`, `/mcp/servers`, `/tool-audit-log`) |
| 3 LangGraph port | 3.1, 3.2a, 3.2b | No (26 contract tests unchanged) | J5 | J5 merged (the graph, both routes, the durable pause) |
| 4 Retrieval upgrade | 4.1a, 4.1b, 4.2, 4.3 | 4.2 adds optional `page` and `sheet` to `/chat/rag` citations | J3 (RAG) | J3.1 to J3.5 merged; J3.6 (hybrid search) in review. J3 is then complete |
| 5 and later (Python Stage 8 guard is ported as J6; tracing, evals and comparisons are Stage 5) | No | Unknown | J6 to J8 | J6 (guard and audit) merged; J7 (evals and observability) and J8 (plain-Java comparison) planned |

## Contract gaps

Places where the Python edition's real behaviour differs from its own `api-reference.md`. The Java edition copies the behaviour, not the document. Fix them in the Python edition first, then update the Java handler and this list.

| Gap | Python today | The reference says |
| --- | --- | --- |
| Unknown route or wrong method | `{"detail": "Not Found"}` only | every error has `error` and `detail` |
| A non-numeric id (`/companies/abc`) | FastAPI's `detail` list (422) | every error has `error` and `detail` |
| `GET /companies` example | `relationship_status` shown as `active` | the code and database use `lead`, `customer`, `former_customer` |

The contract is the 26 tests in `apps/api-py/tests/contract/` plus `docs/api-reference.md` and `contracts/mcp-tools.json` in the Python edition.

## Where the Java edition is behind or different

| Item | Python today | Java today | Plan |
| --- | --- | --- | --- |
| Guardrail on `/chat` | Prompt-injection scan of the question, scan of the answer, audit log | The same rules (ADR 0013): the question and the answer are scanned, detections go to `security_audit_log`, `GET /security-audit-log` | Done in J6.1 |
| Raw tool-call markup from a small model (Python PR #67) | Treated as a failed attempt, next model answers | The same, since J4.2 | Done |
| A body that is not valid JSON | `loc: ["body", <character position>]` and Python's own message in `ctx` | `loc: ["body"]`, no `ctx` | Accepted gap; every other chat validation error is byte-identical (checked by sending the same 15 bodies to both editions: 14 identical, this is the one difference) |
| Documents ingested | 19 (16 Markdown, 1 PDF, 1 Excel, 1 Word), 33 chunks | The same: 19 and 33, chunk counts equal file by file | Done in J3.2 |
| Keyword vector (hybrid search) | Stored beside the meaning vector | A second collection `<name>_bm25` with one BM25 sparse vector per chunk, same ids (ADR 0016) | Done in J3.6 |
| Document ids | Follow the sorted file list | The same sorted list, so the ids are equal (3 is the PDF, 9 the Excel file, 14 the Word file) | Done in J3.2 |
| Retrieval mode | `dense` (default), `hybrid` and an optional reranker behind settings | Dense by default; `RETRIEVAL_MODE=hybrid` (ADR 0016) and an optional reranker (`RERANK_PROVIDER`, ADR 0015), same settings | Done in J3.5 and J3.6 |
| `GET /documents/vector-search-info` | Reports exact versus approximate search by collection size | Not built (Spring AI's store does not expose search parameters; hybrid search did not need it) | Accepted gap |
| Prompt-injection scan of the question and of retrieved chunks | Yes, with an audit log | Scanned and redacted before the model sees it; citations keep the original (J6.1) | Done in J6.1 |
| Company scope in retrieval | `company_id` equal to X or null | `company_id` equal to X or 0 (company-agnostic documents are stored with 0; ADR 0007). Ingest again after upgrading | Not visible through the API |
| MCP tool servers | Two (FastMCP in Python, Spring Boot in a separate service) behind a radio button; default FastMCP | One built in, inside the API at `/mcp` (ADR 0008), default `springboot`; the Python server can be added with `MCP_FASTMCP_URL`. `GET /mcp/servers` lists both | Done in J4.3 |
| Tool results and tool arguments | Scanned for prompt injection, with an audit log | Results scanned and redacted, then wrapped (J6.1); arguments are not scanned (the schemas validate them) | Done in J6.1 (results) |
| The `confirm` rule | The user's latest message decides (substring of yes, confirm, approve, go ahead, ...) | The same | Done |
| The agent (`/agent/recommend`, `/agent/confirm`) | A LangGraph graph; a paused run saved in Postgres and resumed by `/confirm` | A LangGraph4j graph (ADR 0011); a paused run saved in Postgres by the LangGraph4j saver and resumed by `/confirm` (ADR 0012) | Done in J5.2 |
| Prompt-injection scan in the agent (request, stored text, output, the note on confirm) | Yes, with audit events | The same (ADR 0014): request, interactions, document chunks, the three output texts and the note on confirm; audit events with endpoint `agent` | Done in J6.2 |
| The optional LLM classifier (`GUARD_MODE`, `GUARD_MODEL`) | `rules` default, `llm`, `both`; flags only, fails open | The same (ADR 0014) | Done in J6.2 |

