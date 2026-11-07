# Port status

Which stages of the Python edition (`customer-compass-v3`) are merged, and whether the Java edition has ported them. The Java session reads the Python edition's `main` before starting a stage; this table is the summary.

Last checked against `customer-compass-v3` `main` at commit `99c59d8` (Stage 4.2 merged; 4.3 in review), 2026-11-07.

| Python stage | Merged there | API contract changed? | Java stage | Java status |
| --- | --- | --- | --- | --- |
| 0 Foundation | Yes | n/a | J0, J1 | J0.1 to J0.3 done or in review; J1 in review |
| 1 Model layer | Yes | Gateway, usage footer, `/llm/models` | J2 | Planned |
| 2 Tools and MCP | 2.1, 2.2a, 2.2b | `/chat/tools`, `/mcp/servers`, `contracts/mcp-tools.json` | J4 | Planned |
| 3 LangGraph port | 3.1, 3.2a, 3.2b | No (26 contract tests unchanged) | J5 | Planned |
| 4 Retrieval upgrade | 4.1a, 4.1b, 4.2 (4.3 in review) | 4.2 adds optional `page` and `sheet` to `/chat/rag` citations | J3 (RAG) | Planned |
| 5 and later | No | Unknown | J6 to J8 | Planned |

## Contract gaps

Places where the Python edition's real behaviour differs from its own `api-reference.md`. The Java edition copies the behaviour, not the document. Fix them in the Python edition first, then update the Java handler and this list.

| Gap | Python today | The reference says |
| --- | --- | --- |
| Unknown route or wrong method | `{"detail": "Not Found"}` only | every error has `error` and `detail` |
| A non-numeric id (`/companies/abc`) | FastAPI's `detail` list (422) | every error has `error` and `detail` |
| `GET /companies` example | `relationship_status` shown as `active` | the code and database use `lead`, `customer`, `former_customer` |

The contract is the 26 tests in `apps/api-py/tests/contract/` plus `docs/api-reference.md` and `contracts/mcp-tools.json` in the Python edition.
