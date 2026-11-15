# ADR 0033: RAG ingestion, built several ways (J8.2)

Status: accepted, 2026-11-15. J8.2a is the shared contract and the `handWritten` approach; `withSpringAi` (J8.2b) and `withLangchain4j` (J8.2c) follow.

## Context

Ingestion turns files into searchable vectors: read, split into chunks, embed, store. The application does it in `rag/ingestion` and `rag/reader` with Spring AI's embedding model and Qdrant store. J8.2 builds the same pipeline in separate small backends, as J8.1 did for tool calling (ADR 0029), to see what each approach does itself.

## Decision

- **One HTTP face for every approach** (`comparisons/common`: `Ingest`, `IngestBackendServer`): `POST /api/v1/ingest` with an optional `{"limit": n}` and `GET /health`. The answer is the counts of the run: documents, chunks, embedding requests, model, dimensions and time. Ports: 18111 `handWritten`, 18112 `withSpringAi`, 18113 `withLangchain4j`.
- **The caller cannot choose the folder or the collection.** The source folder (`SOURCE_DIR`, default `sample-data/documents`) is the backend's setting, and each approach writes only to its own Qdrant collection (`compare_handwritten`, ...). A request that names a directory or collection is ignored (a test shows it). Reasons: a request-supplied path would let a caller read any folder, and a separate collection means a run can never touch what the application searches.
- **Scope: markdown files.** PDF, Word and Excel readers are libraries (PDFBox, POI) that every approach would use the same way, so they would add size without comparing approaches. The application's readers stay as they are. Front matter gives the title and the company.
- **The same chunking policy in every approach** where the framework allows: 600 characters, 100 overlap, counted in code points. Where a framework's splitter works differently (for example in tokens), the write-up says so and the counts will differ.
- **`handWritten` calls Gemini and Qdrant over REST** with the JDK HTTP client: `batchEmbedContents` for up to 100 texts per request, and Qdrant's REST calls (delete, create with the cosine distance, add points in batches of 64).
- **Order of steps: embed first, then replace the collection.** The step that costs quota and can fail runs before the old collection is removed, so a failed run leaves the previous collection as it was. The application's own order is not what this decision copies.
- **The API key goes in a header** (`x-goog-api-key`), never in the URL, and an error reports the HTTP status only, never the service's reply (it can echo the request).
- **Collection settings:** vector size from `EMBEDDING_DIMENSIONS` (default 768), cosine distance, integer point ids in order, and a payload with title, source file, company, chunk number, character offsets and the chunk text (enough to cite).
- **Embedding task type `RETRIEVAL_DOCUMENT`.** The application's task type is not read here; vectors in `compare_*` collections are for the comparison, not for the application's search.

## Consequences

- Tests use fake Gemini and Qdrant servers, so no embedding quota is spent. A live run (needs `GOOGLE_API_KEY` and the throw-away Qdrant from `just qdrant`) uses roughly one embedding request per 100 chunks; `limit` keeps a first run small.
- Comparing with the application's collection (same chunks, same scores) is not part of this slice.
