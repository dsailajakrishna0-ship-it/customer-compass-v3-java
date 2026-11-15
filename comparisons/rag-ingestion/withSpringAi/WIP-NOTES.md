# WIP notes: J8.2b withSpringAi ingestion (NOT FOR MERGE)

Branch `j8.2b-ingestion-with-spring-ai`, started from main at 092d11e. No PR. Delete this file before the real PR.

## State of the code
- `pom.xml` for `comparisons/rag-ingestion/withSpringAi` (module added to `comparisons/pom.xml`). It compiles and the two scratch tests ran green.
- **No production classes yet.** `src/main` holds nothing (the placeholder `Probe` was removed). Planned: `SpringAiIngest`, `WithSpringAiIngestMain` (port 18112, collection `compare_withspringai`), a small `ChunkStore` interface with a Qdrant implementation, a precomputed-embeddings wrapper.
- Tests present: `FakeHttp` (copied from handWritten), `ScratchTests` and `Scratch2Tests` (throw-away measurement code that prints `MEASURE` lines; turn the findings into assertions in a `SpringAiIngestionBehaviourTests` class and delete the scratch classes). `Scratch2Tests.qdrant()` needs a Qdrant gRPC on localhost:36334, so it FAILS without one: do not leave it in the build.

## Artifacts chosen (all Spring AI 2.0.2, the application's line; versions confirmed on Maven Central)
- `spring-ai-markdown-document-reader` (MarkdownDocumentReader), `spring-ai-google-genai-embedding` (GoogleGenAiTextEmbeddingModel), `spring-ai-qdrant-store` (QdrantVectorStore, gRPC). `TokenTextSplitter` comes from `spring-ai-commons`. Libraries only, no starters (same reason as the application and J8.1b).
- The Gemini model takes a pre-built `com.google.genai.Client`, so a fake server can stand in: `Client.builder().apiKey(k).httpOptions(HttpOptions.builder().baseUrl(url).retryOptions(...))`, then `GoogleGenAiEmbeddingConnectionDetails.builder().genAiClient(client)`. No need for the OpenAI-compatible endpoint.
- QdrantVectorStore is gRPC, so unit tests need a `ChunkStore` seam (fake in tests); real-store checks are live-gated (skipped unless a Qdrant env var is set).

## Measured so far (exact)
Reader (`MarkdownDocumentReader`, defaults and with horizontal-rule documents, same result):
- Front matter is dropped: the `title` and `company` lines appear nowhere in text or metadata. A file is split per heading: each section becomes a Document, the heading text leaves the text and goes to metadata (`title` = the heading, `category` = `header_1`...). A file with no heading gives one Document with empty metadata. A file with only front matter and blanks gives 0 Documents. So title and company must still be parsed by hand (what handWritten does), and the reader's `title` metadata means something else (a heading).
Splitter (`TokenTextSplitter`, counts tokens):
- Default chunk size 800 tokens: a 2,429-character text stays ONE chunk. Chunk size 150 tokens gave chunks of 647, 647, 647, 485 characters; 100 tokens gave 404 characters each (about 4.3 characters per token on that text; the chars per chunk depend on the text). handWritten cuts 600 characters with 100 overlap.
- `minChunkSizeChars` made no difference at 1 versus default on that text. A text of 3 characters ("abc") gives 0 chunks (`minChunkLengthToEmbed`, default 5), "Short note." gives 1.
- Chunk metadata: `chunk_index`, `total_chunks`, `parent_document_id`; the parent Document's metadata is copied to every chunk; each chunk has a new id. No character offsets.
- No overlap option exists in the builder (methods: chunk size, min chunk chars, min length to embed, max chunks, keep separator, punctuation marks, encoding). The numbered-sentence test (chunks of about 250 characters at 60 tokens) shows chunk 0 ending "...Sentence number 9" and chunk 1 starting "ends here. Sentence number 1" and chunk 1 ending "...Sentence" and chunk 2 starting "number 18": so chunks do not overlap, and they can cut mid-sentence and mid-number (chunk 0 and chunk 1 together read "Sentence number 9" + "ends here. Sentence number 1" which are the pieces of the cut "number 9" / "number 18"?? re-check this by hand; I did not confirm the exact cut points).
Gemini model (`GoogleGenAiTextEmbeddingModel` over the Google GenAI SDK 1.65.0) against a recording fake server:
- Request: `POST /v1beta/models/gemini-embedding-001:batchEmbedContents`, key in header `x-goog-api-key` (not in the URL).
- The body has `outputDimensionality` and `model` but NO `taskType`, although the options were built with `TaskType.RETRIEVAL_DOCUMENT`. So this model, as called, did not send the task type (handWritten sends RETRIEVAL_DOCUMENT). Re-check once with the options passed per request before stating it as a rule.
- 250 texts went out as ONE request (no batching at 100); 3 texts also one request. Gemini's documented limit of 100 per batch was not tested live, so a 100-text batcher still has to be added and tested.
- On HTTP 429 and 500 the library made 5 requests and took about 37 s and 30 s (default). A 400 made 1 request. With the SDK's `retryOptions(attempts=1)` a 429 made 1 request (134 ms). The exception message includes the service's reply (my fake echoed a key; the message printed it): `ClientException: 429 . quota for key secret-key-123`. So errors must be caught and reduced to the status.
- A vector of the wrong length (3, expected 4) was ACCEPTED, and a reply with fewer vectors than texts (1 for 2) was ACCEPTED silently: validation has to be added (the application wraps the model in `FixedDimensionsEmbeddingModel` for the length).
QdrantVectorStore against a real Qdrant v1.20.0 container (ports 36333/36334, started and now stopped by name `cc-j82b-qdrant`), with a fake EmbeddingModel of 4 dimensions:
- `initializeSchema(true)` plus `afterPropertiesSet()` created the collection with size 4, distance Cosine.
- Adding the same two Documents (same ids) twice left 2 points (upsert) but embedded again each time (2 calls, 4 texts). Documents with random ids add up (count went 2 to 4 after two more).
- A failed embedding made `add` throw (IllegalStateException from my fake) and the count stayed 4: nothing was written.
- 250 Documents: one embedding call with 250 texts (no batching at 100 by the store), 254 points total.
- Point ids are UUIDs. A scroll of one point showed payload keys `[doc_content, title]`; I scrolled an arbitrary point (probably one of the random-id ones that only had `title`), so whether other metadata keys (`source`, `chunkIndex`) are kept in the payload is NOT confirmed yet. `QdrantVectorStore` has no "recreate"; the collection must be deleted through `QdrantClient`.
- `Document` has no embedding field, so the store always embeds the Documents itself. To embed everything BEFORE replacing the collection, plan: embed all texts first (batched at 100, validated), then delete and recreate the collection, then `store.add` with a small `EmbeddingModel` that returns the precomputed vectors.

## Still to do
1. Production code (above) and the seam, using SDK `retryOptions(attempts=1)` or a small attempt count, a 100-text batcher, vector validation, error text reduced to the HTTP status, deterministic UUIDs from `source` plus chunk index, limit, files sorted, same shared `IngestBackendServer`.
2. Decide chunk settings: pick a token size near 140 to approximate 600 characters, say it is token-based with no overlap, and check payload offsets (not available: say so).
3. `SpringAiIngestionBehaviourTests` (turn the measurements above into assertions; the Qdrant ones live-gated by an env var such as `QDRANT_GRPC_HOST`) and `SpringAiIngestTests` (scripted fakes), delete `ScratchTests`, `Scratch2Tests`.
4. ADR `docs/adr/0034-rag-ingestion-with-spring-ai.md`; "J8.2b" section in `docs/stages/J8-2-rag-ingestion.md` (after J8.2a, additive); "J8.2b" section in `docs/testing-guide.md` (above J8.2a); a row in `comparisons/README.md`; justfile recipe `comparisons-ingest-with-spring-ai`.
5. Full `./apps/api-java/mvnw -B -ntp -f comparisons/pom.xml verify` (run in the background, result file), then push and open ONE PR titled `J8.2b: RAG ingestion with Spring AI (comparisons/rag-ingestion/withSpringAi)` with the sections What, Rules table, Tests, Not measured, How to try. Expect small merge conflicts with J8.2c in the shared docs files.

## Blockers
None. Open points are the two re-checks marked above (task type, payload keys) and the mid-sentence cut points of the splitter.
