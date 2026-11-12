# ADR 0006: retrieval and `POST /chat/rag` (J3.3)

Status: accepted, 2026-11-12.

## Context

The Python edition answers document questions in `POST /chat/rag`: embed the question, find the closest chunks, show them to the model as numbered sources, and answer only from them with citations. It has three retrieval modes behind settings: dense (the default), hybrid (a second, keyword vector merged by reciprocal rank fusion) and an optional hosted reranker. The Java edition must give the same answers in the same response shape.

## Decision

- **Port the default mode only: dense retrieval.** Embed the question, ask Qdrant for the closest `top_k = 4` chunks, keep those with a cosine similarity of at least **0.45** (the Python edition's measured floor for `gemini-embedding-001`: relevant chunks about 0.50 to 0.80, unrelated questions 0.42 to 0.44).
- **Hybrid search and the reranker are not in J3.3.** Python keeps both off by default, so the default behaviour is already equal. Spring AI's Qdrant store holds one vector per chunk, so hybrid needs a design choice (a second collection, direct Qdrant calls, or staying dense); it gets its own slice and ADR once the Stage 5 evaluation numbers show whether it earns its cost.
- **Spring AI does the search** (`VectorStore.similaritySearch` with a similarity threshold), so the question is embedded and the vector search runs through the framework we chose for this stage.
- **The index model check is kept.** The model that built the index is the `embedding_model` of the latest completed ingestion run (Spring AI's store cannot write collection metadata the way Python does). If it differs from the configured model, the search is refused with Python's own message, which says to re-ingest.
- **Nothing found means no model call.** The answer is the fixed sentence, `citations` is empty, and `usage` is absent (as in Python).
- **The prompt is the Python edition's, word for word:** the grounded system prompt, and for the last user message `Context:` plus the sources inside `<untrusted_context>` tags, then `Question:` plus the question. Earlier messages are sent unchanged.
- **Not yet:** the prompt-injection scan of the question and of each retrieved chunk (J6). The retrieved text is already wrapped as untrusted data. `guardrail` stays unflagged.

## Consequences

- Same question, same documents, same embedding model: the same chunks and scores as the Python edition's dense mode (checked live: the six runbook questions cite the same sources, with scores 0.58 to 0.78).
- A question that names an exact term (a clause number, an invoice id) relies on meaning alone. In the sample data the "Clause 7.3" question still ranks the right PDF page first (0.78), but this is the case hybrid search exists for.
- Left out for now and listed in `docs/port-status.md`: `GET /documents/vector-search-info` (the exact versus approximate search choice by collection size; Spring AI's store does not expose search parameters), hybrid retrieval, the reranker, and the company filter that `/chat/crm` (J3.4) needs.
