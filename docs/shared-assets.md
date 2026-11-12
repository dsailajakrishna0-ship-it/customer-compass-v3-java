# Shared assets copied from the Python edition

Copied (not linked) from `customer-compass-v3` at commit `139c7f8` (Stage 3.2b merged), 2026-11-05. The Python edition stays the source of truth: change them there first, then re-copy and record the new commit here.

| Path | What it is | Used by |
| --- | --- | --- |
| `apps/web` and root `package.json`, `package-lock.json` | The React web app | Both backends. It calls `http://localhost:8001` (`apps/web/src/api/client.ts`), so the Java API will listen on 8001 when the Python one is stopped; making the address a setting is a J1 task |
| `sample-data/documents` | Synthetic documents to ingest | J3 |
| `contracts/` | `mcp-tools.json` and two small checkers | J4 |
| `evals/golden` | The golden question set | J7 |

Update 2026-11-12: `sample-data/documents` re-copied from `customer-compass-v3` at commit `1750505` (Stage 4.3 merged): the PDF, Excel and Word files and their `.meta.yaml` sidecars were added.

Not copied: the Python API, the Python tool server, the Python-specific eval runners and the docs of the Python edition.
