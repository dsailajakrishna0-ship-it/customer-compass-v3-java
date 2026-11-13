# Java extensions

From the point where the Java edition matched the Python edition (every feature merged in Python Stages 0 to 4, 2026-11-13) the two repositories grow **separately**, so the notes show what each stack teaches. Everything on this page exists only in the Java edition.

**The shared API contract does not change.** Extensions are new routes, new settings and new code, never a change to a route or tool the Python edition has. The shared contract checks (`just contract`, `just mcp-contract`) keep passing unchanged, and the extra routes are documented here and in their stage guides, not in the shared API reference.

## E1: multi-source RAG with routing

Several knowledge bases (for example the CRM documents and a farm-supply handbook), and a model that searches across them. Different from multimodal RAG (one index holding many kinds of content).

| Slice | What | Status |
| --- | --- | --- |
| E1.1 | A knowledge-base registry, a second corpus with its own collection, `GET /knowledge-bases`, `POST /knowledge-bases/{id}/ingest`, `POST /knowledge-bases/{id}/search`. [ADR 0017](adr/0017-knowledge-bases.md), [guide](stages/E1-multi-source-rag.md) | In review |
| E1.2 | Three ways to decide where to search, behind `POST /chat/multi`: a router picks, search everything and merge (reciprocal rank fusion, then the optional reranker), or the model picks through a tool argument. [ADR 0018](adr/0018-multi-source-routing-strategies.md) | In review |
| E1.3 | A comparison of the three on 24 labelled questions (one corpus, both, neither), replayable without keys; `just eval-multi`. [ADR 0019](adr/0019-multi-source-strategy-results.md) | In review |

## New routes and settings (E1.1)

| Route or setting | What it does |
| --- | --- |
| `GET /api/v1/knowledge-bases` | The knowledge bases (`crm` and the extra ones) with their description, collection and chunk count |
| `POST /api/v1/knowledge-bases/{id}/ingest` | Rebuilds an extra knowledge base from its folder; never touches the CRM documents |
| `POST /api/v1/knowledge-bases/{id}/search` | `{"query": "...", "topK": 4}`: the chunks of that knowledge base only, with citations |
| `POST /api/v1/chat/multi` | A chat question answered from several knowledge bases; `"strategy"`: `route` (default), `all` or `agent`; the response adds `routing` and a `knowledgeBase` on each citation |
| `knowledge-bases.extra[]` (`application.yml`) | `id`, `label`, `description`, `source-dir` of each extra knowledge base |
| `KB_AGRI_SUPPLY_DIR` | Folder of the sample farm-supply handbook (default: `sample-data/knowledge-bases/agri-supply`) |
| `modelCalls` in `routing` | How many model calls the answer cost (router, tool rounds, answer) |
