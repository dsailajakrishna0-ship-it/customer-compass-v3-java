# ADR 0036: RAG retrieval, built several ways (J8.3)

Status: accepted, 2026-11-16. J8.3a is the shared contract and the `handWritten` approach; `withSpringAi` (J8.3b) and `withLangchain4j` (J8.3c) follow.

## Context

Retrieval is what happens at question time: embed the question, ask the vector store for the closest chunks, and keep the ones that are similar enough to cite. The application does it in `rag/retrieval` (dense search, the similarity floor, optional hybrid keyword search and an optional reranker). J8.3 builds the same step in separate small backends, as J8.1 and J8.2 did, to see what each approach does itself.

## Decision

- **One HTTP face for every approach** (`comparisons/common`: `Retrieve`, `RetrieveBackendServer`): `POST /api/v1/retrieve` with `{"query": "...", "topK": 4, "minSimilarity": 0.6}` (only the query is required) and `GET /health`. The answer has the approach, the collection, the floor used, how many chunks the store returned (`considered`), how many passed the floor (`kept`), the hits (score, title, source, company, chunk number, text) and the number of embedding requests. Ports: 18121 `handWritten`, 18122 `withSpringAi`, 18123 `withLangchain4j`.
- **Each approach searches the collection its own ingestion built** (`compare_handwritten`, ...), so ingestion (J8.2) must have run for that approach first; a missing collection says so (503, "Run the ingestion for this approach first"). The collection is never taken from the request.
- **Scope: dense search, `topK` and the similarity floor.** Left out on purpose: **hybrid keyword search**, because the ingestion collections hold no keyword vectors (the application builds sparse vectors at ingestion, ADR 0016, and the J8.2 comparison does not), and the **reranker**, because the application's reranker is an external service (ADR 0015) and needs a provider and a key. Either could be a later slice.
- **Defaults:** `topK` 4 (1 to 20 allowed) and a floor of **0.60**, the application's calibrated value for `gemini-embedding-001` (ADR 0024). The caller may lower or raise the floor per request, which is how the cost of the floor can be seen live (0.45 against 0.60).
- **A chunk exactly at the floor is kept** (at or above). Hits are best first.
- **`handWritten` calls Gemini and Qdrant over REST**: `embedContent` with task type `RETRIEVAL_QUERY` and Qdrant's `points/query` with the vector, the limit and the payload. The key goes in a header, never in the URL; errors report the HTTP status only, never the service's reply; no key means nothing is called.
- **The payload keys are the ones the matching ingestion wrote** (`handWritten`: `text`; Spring AI's store and LangChain4j's store use their own keys, which J8.3b and J8.3c read).

## Consequences

- Tests use fake Gemini and Qdrant servers, so no embedding quota is spent; a live run needs `GOOGLE_API_KEY`, Qdrant (`just qdrant`) and a prior ingestion, and costs one embedding request per question.
- The three approaches embed the question with different task types where the library allows it (`handWritten` sends `RETRIEVAL_QUERY`); whether that changes the scores is not measured here.
- Comparing the retrieved chunks and scores of the three approaches for the same question is the write-up slice (J8.3d).
