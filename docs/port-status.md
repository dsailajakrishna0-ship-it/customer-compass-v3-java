# Port status

Which stages of the Python edition (`customer-compass-v3`) are merged, and whether the Java edition has ported them. The Java session reads the Python edition's `main` before starting a stage; this table is the summary.

Last checked against `customer-compass-v3` `main` at commit `139c7f8` (Stage 3.2b merged), 2026-11-05.

| Python stage | Merged there | API contract changed? | Java stage | Java status |
| --- | --- | --- | --- | --- |
| 0 Foundation | Yes | n/a | J0 | In progress |
| 1 Model layer | Yes | Gateway, usage footer, `/llm/models` | J2 | Planned |
| 2 Tools and MCP | 2.1, 2.2a, 2.2b | `/chat/tools`, `/mcp/servers`, `contracts/mcp-tools.json` | J4 | Planned |
| 3 LangGraph port | 3.1, 3.2a, 3.2b | No (26 contract tests unchanged) | J5 | Planned |
| 4 and later | No | Unknown | J3 (RAG), J6 to J8 | Planned |

The contract is the 26 tests in `apps/api-py/tests/contract/` plus `docs/api-reference.md` and `contracts/mcp-tools.json` in the Python edition.
