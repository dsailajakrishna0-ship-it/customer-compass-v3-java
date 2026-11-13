# ADR 0018: three ways to decide where to search (E1.2)

Status: accepted, 2026-11-13. A Java-edition extension: not part of the contract shared with the Python edition.

## Context

With more than one knowledge base (ADR 0017), a question needs a decision: which corpus can answer it? There is no single right answer, so the extension builds the three common patterns behind one route and lets E1.3 compare them on the same questions.

## Decision

`POST /api/v1/chat/multi` takes the usual chat body plus `"strategy"`: `route` (default), `all` or `agent`. An unknown value is a FastAPI-style 422 (`literal_error`). The answer, the guard, the usage footer and the audit log are the RAG route's; `citations` also say which knowledge base each source came from, and `routing` says what was searched.

- **`route`: a router decides first.** One small model call reads each knowledge base's description and answers JSON (`{"knowledge_bases": [...], "reason": "..."}`). Only the chosen ones are searched. Cheap at answer time (one retrieval, one prompt), but a wrong choice hides a corpus, so the parse is safe: invented ids are dropped, and a reply that cannot be read (or names only unknown ids) falls back to searching **everything**, with the reason saying so. An explicit empty list is respected: nothing is searched, the fixed "could not find" answer is returned and the answering model is not called.
- **`all`: search everything and merge.** No decision, no router call. Each knowledge base is searched with its own retriever (so each applies its own similarity floor, and hybrid search if switched on). Scores from different corpora are not comparable, so the lists are merged by **reciprocal rank fusion** (positions only), and, when a reranker is configured (J3.5), the merged candidates are re-ordered by it. Without a reranker, RRF over disjoint lists is a round-robin by rank, which is fair but not smart; the reranker is what makes this strategy good.
- **`agent`: the model decides while it works.** The model is given a `search_knowledge_base(knowledge_base, query)` tool (the knowledge base is an enum of the known ids) and the descriptions in its prompt, and may search several times, in several corpora, with its own queries. Sources are numbered across all calls in the order returned, so `[3]` in the answer matches `citations[2]`. Limits as in the tools chat: four rounds, four calls per reply, then one forced plain-text answer. Arguments are untrusted: an unknown knowledge base or missing argument comes back to the model as a readable tool result and is not recorded as used.
- **The guard applies to all three.** The question is guarded first; every chunk and every tool result is scanned and redacted before a model reads it and wrapped as untrusted; the answer is scanned last; detections go to the audit log with endpoint `chat/multi`. Retrieval uses the original question.
- **The tool is in the route, not in the MCP server.** The shared MCP tool list (`contracts/mcp-tools.json`) is a contract with the Python edition; this tool is an in-process extension and stays out of it.

## Alternatives considered

- **A keyword router** (no model call): free and deterministic, and it would have been enough for two very different corpora, but the point of the extension is the descriptions-as-data pattern and what happens when the vocabulary overlaps (renewal, pricing, support appear in both corpora).
- **Always `all`:** the simplest and the safest against a wrong route, but it costs a retrieval per corpus and fills the prompt with weak sources as corpora multiply.
- **Embedding-similarity routing** (compare the question with an embedding of each description): no model call and no JSON to parse, but it needs a calibrated threshold; a possible later variant.

## Consequences

- `route` costs one extra model call per question; `agent` costs one call per round; `all` costs no extra model call but one retrieval per corpus.
- The router sees only the (cleaned) question and the descriptions, never document text.
- E1.3 measures which strategy gets the right sources for questions that belong to one corpus, to both, and to neither.
