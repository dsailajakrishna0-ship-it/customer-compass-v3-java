# WIP notes: J8.2c withLangchain4j ingestion (not for merge)

Stopped on request, 2026-11-15. Delete this file before the PR.

## State of the code
- `comparisons/pom.xml`: module line `rag-ingestion/withLangchain4j` added.
- `rag-ingestion/withLangchain4j/pom.xml`: written; resolves and builds. No main sources yet (`LangChain4jIngest`, `WithLangchain4jIngestMain` not written).
- Tests: `FakeHttp.java` (copy of handWritten's test helper, package changed) and `ProbeTests.java` (3 throw-away measurement tests that print `PROBE ...` lines and assert nothing). The module compiled and the 3 probe tests passed (`BUILD SUCCESS`). Turn them into assertions in `LangChain4jIngestionBehaviourTests`, then delete `ProbeTests`.
- Not started: ADR 0035, stage-page section, testing-guide section, `comparisons/README.md` row, justfile recipe `comparisons-ingest-with-langchain4j`, PR.

## Artifacts chosen (all pinned by `langchain4j-bom` 1.23.0)
- `langchain4j` 1.23.0 (stable): `FileSystemDocumentLoader`, `DocumentSplitters.recursive`, `EmbeddingStoreIngestor`.
- `langchain4j-google-ai-gemini` 1.23.0 (stable): `GoogleAiEmbeddingModel`; its builder has `baseUrl`, `apiKey`, `modelName`, `outputDimensionality`, `taskType`, `maxRetries`, `timeout`, so a fake server can stand in.
- `langchain4j-qdrant` 1.23.0-beta33 (beta only): `QdrantEmbeddingStore`, gRPC (so a fake REST server will not do). Builder: host, port, collectionName, useTls, payloadTextKey, apiKey, client. Methods include `removeAll()`, `clearStore()`, `addAll(ids, embeddings, segments)`.
- Not used: `langchain4j-document-parser-apache-tika` (beta, heavy). The plain `TextDocumentParser` is the candidate reader.

## Measured so far (fake Gemini server, `ProbeTests`)
- Splitter `DocumentSplitters.recursive(600, 100)` on a front-matter file with a 1,040-character paragraph: 4 segments of 16, 16, 588 and 545 characters. The front matter (`---\ntitle: x\n---`) was cut into **two separate 16-character segments**, and segment 2 starts with the front matter again (overlap), so front matter pollutes the chunks unless it is removed before splitting. Segment metadata is only `{index=N}`: **no character offsets**, no file name. A short document: 1 segment. 2,000 words of "word ": 17 segments, longest 599 characters.
- Gemini batching: `embedAll` of 250 segments made **3 requests** (request sizes 16,511 / 16,621 / 8,321 bytes, about 100 / 100 / 50 texts). The library batches by itself. I did not count texts per request exactly (my fake counted `"taskType"` and found 0, see next line).
- Request shape: path was `/models/gemini-embedding-001:batchEmbedContents` after the base URL I gave (so the default base URL must include `/v1beta`; **not checked**). The key is sent in the **`x-goog-api-key` header, not in the URL**. `user-agent: LangChain4j`. The body contains **no `taskType`** unless `taskType(...)` is set on the builder (my fake counted 0 occurrences, so it returned 0 vectors; the 3 requests still happened).
- Errors: an HTTP 429 gives `RateLimitException` whose **message is the service's reply body verbatim** (my fake echoed the key in it, and it came through). So the library alone breaks the "status only, never the reply" rule: the approach must catch and rewrite it.
- A reply with 1 vector for 2 texts: **not detected** (returned 1). A vector of 3 numbers for a configured size of 4: **not detected** (dimension 3). So count and length checks must be added by us.
- Unreachable host: `RuntimeException` wrapping `ConnectException`, message `java.net.ConnectException`.
- Blank key: the probe printed nothing, meaning **no exception** was thrown at embed time; I did not print whether a request was sent. Re-measure before claiming anything.

## Still to measure (no Docker/Qdrant started yet)
- `FileSystemDocumentLoader.loadDocuments(folder, PathMatcher, TextDocumentParser)`: order of files (probably not sorted), whether blank files are skipped or throw `BlankDocumentException`, metadata keys (file name, directory), whether non-markdown files are excluded by the matcher.
- `EmbeddingStoreIngestor.ingest(docs)`: does it call `embedAll` once for all segments before the first store call? If yes, a decorating store that clears the collection inside its first `addAll` gives "embed first, then replace". Check with a recording `EmbeddingStore`.
- `QdrantEmbeddingStore`: does the constructor create a missing collection, with what vector size and distance; point id format (UUID strings); payload layout; what `removeAll()` / `clearStore()` do (points only, or the collection). Plan: measure once against a throw-away local Qdrant container (exact name, stop it by name, never `pkill -f`; no embedding quota needed, use fixed vectors), then keep a live test that is skipped unless `QDRANT_HOST` is set. Default tests use a recording `EmbeddingStore<TextSegment>`, no Docker.

## Plan for the rules (from the brief)
Embed everything before replacing the collection; wrap the embedding model to enforce at most 100 per request if needed (the library already batches), vector count equals text count, vector length equals the configured size, rewrite errors to status only (HTTP 429 etc.), skip empty files, ignore non-markdown, sort by file name and apply `limit`, keep title/source/company/chunkIndex/text in the payload (offsets are not available, say so; strip front matter before splitting). Collection `compare_withlangchain4j`, port 18113, task type `RETRIEVAL_DOCUMENT` set explicitly (the library sends none by default).

## Blockers
None. Nothing is running. Docker not used yet.
