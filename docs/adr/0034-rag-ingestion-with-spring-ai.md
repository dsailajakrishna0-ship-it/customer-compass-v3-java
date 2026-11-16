# ADR 0034: RAG ingestion with Spring AI (J8.2b)

Status: accepted, 2026-11-16. Second approach of the J8.2 comparison (ADR 0033): the same backend as `handWritten`, built with Spring AI's reader, splitter, embedding model and vector store.

## Context

`handWritten` reads, chunks, embeds and stores with the JDK and Jackson only. Spring AI has a class for each of the four steps. This slice builds the same backend with them to see, with tests, what each class does on its own and what had to be added to keep the rules `handWritten` enforces.

## Decision

- **A separate module** `comparisons/rag-ingestion/withSpringAi` (port 18112, collection `compare_withspringai`), the same HTTP face as `handWritten` (`comparisons/common`). Not a Spring Boot application: library classes only, as in the application and in J8.1b.
- **Spring AI 2.0.2**, the application's line: `spring-ai-markdown-document-reader` (`MarkdownDocumentReader`), `TokenTextSplitter` (in `spring-ai-commons`), `spring-ai-google-genai-embedding` (`GoogleGenAiTextEmbeddingModel`, over the Google GenAI SDK 1.65.0) and `spring-ai-qdrant-store` (`QdrantVectorStore`, gRPC).
- **The embedding model is pointed at a fake server in tests** through the SDK: `Client.builder().httpOptions(HttpOptions.builder().baseUrl(...))` passed as `genAiClient` to the connection details. No OpenAI-compatible endpoint is needed.
- **The Qdrant store is behind a small interface** (`ChunkStore`), because its gRPC protocol cannot be faked with a plain HTTP server. The default build tests the pipeline against a recording fake; `SpringAiQdrantBehaviourTests` runs the real store and is **skipped unless `QDRANT_GRPC_PORT` is set**, so the default build needs no keys, network or Docker.
- **One attempt per Gemini call** (`HttpRetryOptions.attempts(1)`). Chosen because the SDK's default made 5 requests and took about 30 to 37 seconds to give up on a 429 or a 500 (measured once, not repeated in the build because it is slow); a run that has been refused should stop, not spend more quota.
- **Chunk size 150 tokens.** See "How the chunks differ" below.

## What Spring AI does on its own (measured; `SpringAiIngestionBehaviourTests`, and `SpringAiQdrantBehaviourTests` for the store)

| Question | What Spring AI 2.0.2 does |
| --- | --- |
| Reader: front matter? | **Misread, not dropped.** The closing `---` under the key lines is a markdown heading underline, so the key lines become a heading: the section's `title` metadata is the **last** key line (for a real sample file, `date: 2026-08-28`; for a two-key block, `company: Acme Fabrication`), `category` is `header_2`, and the other key lines (`title: A call`...) are nowhere. The body text itself is clean. (My first measurement, on a file that continued with a `# Heading`, wrongly looked like "dropped".) |
| Reader: how is a file cut? | **One document per heading section**; the heading leaves the text and goes to the metadata (`title` = the heading, `category` = `header_1`...). A file with no heading is one document with empty metadata; a file with only front matter gives none. |
| Reader: paragraphs | **Joined with no separator**: "Hello there." and "More text." come out as `Hello there.More text.`. |
| Splitter: unit of size | **Tokens**, default **800**: a 2,430-character text stays one chunk. 150 tokens gave chunks of about 647 characters on that text (the characters per chunk depend on the text). |
| Splitter: overlap? | **No option for it**, and none measured: the chunk lengths of a numbered text add up to within 100 characters of the original. |
| Splitter: where does it cut? | **At the token count, often inside a sentence**: of 14 chunks of numbered sentences, 2 ended at a sentence end. |
| Splitter: metadata | Copies the parent's metadata, adds `chunk_index`, `total_chunks`, `parent_document_id`; **no character offsets**. |
| Splitter: very short text | A text of 3 characters gives **no chunk** (default minimum length to embed is 5); "Short note." is kept. handWritten keeps both. |
| Embedding: how many texts per request? | **All of them in one request** (250 texts, one request). Gemini accepts at most 100 per batch (its documented limit; not tried against the real service). |
| Embedding: key | In the `x-goog-api-key` header, **not in the URL**. |
| Embedding: task type | **Never sent**, even when `TaskType.RETRIEVAL_DOCUMENT` is set in the options, in the default options or per request (the request body has the model, the text and `outputDimensionality`). handWritten sends `RETRIEVAL_DOCUMENT`. |
| Embedding: a refusal | An exception **whose message contains the service's reply** (a test's echoed key came back in it), after **5 requests and about 35 seconds** by default; with `attempts(1)`, one request. |
| Embedding: a vector of the wrong length, or fewer vectors than texts | **Both accepted**, without a message. |
| Store: collection | `initializeSchema(true)` creates it with the model's size and the **cosine** distance. It has **no "recreate"**: the collection is deleted through the Qdrant client. |
| Store: same ids added twice | **One point each** (an upsert), but the texts are **embedded again** each time. |
| Store: documents without ids | Get random ids, so every add **piles up**. |
| Store: a failed embedding | `add` throws and **writes nothing**. |
| Store: embedding calls | 250 documents are **one** call with 250 texts (no batches of 100). |
| Store: payload | Every metadata key (strings and integers kept as such) plus the text under `doc_content`. Ids are UUIDs. |
| Store: precomputed vectors | A `Document` has **no field for a vector**: the store always embeds the documents itself. |

## What was added, and how

| Rule of `handWritten` | In `withSpringAi` |
| --- | --- |
| Embed everything **before** replacing the collection | **Added:** the texts are embedded first (`BatchedEmbeddings`); then `QdrantChunkStore` deletes the collection and creates it, and adds the chunks through the store with `PrecomputedEmbeddings`, an embedding model that returns the vectors already computed (found by the chunk's text). A failed embedding never reaches the store (test). |
| At most 100 texts per request | **Added:** `BatchedEmbeddings` sends 100 at a time (250 chunks: 3 requests; test). |
| The vector count equals the text count, each vector has the configured length | **Added:** both checked, the run stops before the store (tests). |
| Errors say the HTTP status only, never the service's reply | **Added:** the library's exceptions are caught and reduced to "answered HTTP n" (or "not reachable"); a test checks that an echoed key and reply text do not come out. The Qdrant side likewise: a fixed sentence, never the gRPC text. |
| The key never in a URL | **Library already does this** (header). |
| One attempt per call | **Configured** (see Decision). |
| Task type `RETRIEVAL_DOCUMENT` | **Cannot be done** with this model: it is never sent. A difference, stated. |
| Title and company from the front matter | **Added by hand:** the block is read and **cut off before the reader sees the text** (otherwise its key lines become a bogus heading), the same few lines as handWritten. The reader's own `title` is then always a real heading and is kept as `heading`. The paragraph joining is not repaired: it is the reader's behaviour, stated. |
| Stable ids, so a re-run replaces and does not duplicate | **Added:** ids from the file name and the chunk number, as UUIDs (`UUID.nameUUIDFromBytes`). |
| `charStart`/`charEnd` in the payload | **Not available:** the splitter gives no offsets, so the payload has none (title, source, company, heading, chunkIndex and the text). |
| Caller cannot choose the folder or the collection | The shared server already enforces it. |

## How the chunks differ from handWritten's 600 characters with 100 overlap

Measured over the 16 markdown files of `sample-data/documents` (11,545 characters of body text; the reader gives one section per file, as none has a heading):

| | Chunks | Average length |
| --- | --- | --- |
| handWritten: 600 characters, 100 overlap | about 28 (from the window formula, not a run) | up to 600 |
| Spring AI, 100 tokens | 31 | 367 characters |
| 130 tokens | 26 | 438 |
| 140 tokens | 23 | 495 |
| **150 tokens (chosen)** | **20** | **570** |
| 200 tokens | 16 | 713 |

150 tokens is the measured size closest to 600 characters on average, but it is **not the same policy**: chunks are sized by tokens so their lengths vary with the text; they do not overlap, so a sentence that straddles a cut is not whole in any chunk; they are cut inside sentences more often than handWritten's windows (which also cut by characters, but overlap); and a short text may be dropped. The number of chunks therefore differs, and search results will too. Whether the search is better or worse was not measured.

## Consequences

- The four steps shrink to a few library calls, but the rules around them (order, batching, checks, error text, ids) are code of ours, as in J8.1b. Each needed a measurement to find out.
- This approach cannot ask Gemini for document-type vectors, and its chunks have no offsets; handWritten does and has. Comparing search quality between the two collections is not part of this slice.
- The Qdrant store is exercised for real only when a Qdrant is available (`SpringAiQdrantBehaviourTests`); the default build tests our store class only for the "not reachable" path.
- Library upgrades can change a measured behaviour: the behaviour tests fail when they do.
