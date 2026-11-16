# ADR 0035: RAG ingestion with LangChain4j (J8.2c)

Status: accepted, 2026-11-16. Third approach of the J8.2 comparison (ADR 0033): the same backend as `handWritten`, built with LangChain4j's loader, splitter, embedding model, ingestor and Qdrant store.

## Context

`handWritten` reads, chunks, embeds and stores with the JDK and Jackson only. LangChain4j has a class for each step and one class (`EmbeddingStoreIngestor`) that runs split, embed and store as one call. This slice builds the same backend with them to see, with tests, what each does on its own and what had to be added to keep the rules `handWritten` enforces.

## Decision

- **A separate module** `comparisons/rag-ingestion/withLangchain4j` (port 18113, collection `compare_withlangchain4j`), the same HTTP face as `handWritten` (`comparisons/common`). Not a Spring application.
- **LangChain4j 1.23.0** (stable, pinned by its BOM): `langchain4j` (`FileSystemDocumentLoader`, `DocumentSplitters.recursive`, `EmbeddingStoreIngestor`), `langchain4j-google-ai-gemini` (`GoogleAiEmbeddingModel`, whose builder takes a `baseUrl`, so a fake server can stand in) and **`langchain4j-qdrant` 1.23.0-beta33**, the only version published, a beta; it talks gRPC. The Tika parser (beta, heavy) is not used: the plain text parser reads markdown.
- **The ingestor is used.** Measured: it embeds every segment in one call and only then calls the store once, with everything. So a store that resets the collection inside that one call replaces it exactly when the new vectors exist (`ReplacingStore`): "embed first, then replace" holds, and a failed embedding never reaches the store.
- **The Qdrant store is behind a small seam** (a `Target` of reset action and store), because gRPC cannot be faked with a plain HTTP server. The default build tests the pipeline with a recording store; `LangChain4jIngestLiveTests` run the real store and are **skipped unless `QDRANT_REST_URL` is set** (with `QDRANT_GRPC_PORT`), so the default build needs no keys, network or Docker.
- **Zero retries** (`maxRetries(0)`): a rejected request is reported, not repeated; a retry would spend quota again.
- **Task type `RETRIEVAL_DOCUMENT` is set explicitly** (the library sends none by default) and the dimension is asked for.

## What LangChain4j does on its own (measured: `LangChain4jIngestionBehaviourTests` and, for the store, `LangChain4jIngestLiveTests`)

| Question | What LangChain4j 1.23.0 does |
| --- | --- |
| Loader: which files? | Only what the glob matches, **not sub-folders**. |
| Loader: blank files | A load of a folder **skips** files with no word; loading one such file throws. |
| Loader: front matter | **Kept in the text**; the document's metadata is only about the file. Title and company are not read. |
| Loader: order | Not sorted by the library; the approach sorts by file name. |
| Splitter: unit | **Characters** (`recursive(600, 100)`), cutting at paragraph, line, sentence and word boundaries first; every chunk stays within the size. |
| Splitter: front matter | **Cut into chunks of its own and repeated at the start of the next one** (the overlap), so it pollutes the chunks unless removed before splitting. |
| Splitter: metadata | Copies the document's metadata onto every chunk and adds **only a chunk number**; **no character offsets**. |
| Embedding: texts per request | The library batches itself: **250 texts made 3 requests** (100, 100, 50). |
| Embedding: key | In the `x-goog-api-key` header, **not in the URL**. |
| Embedding: task type | **Not sent unless set** on the builder; the dimension is sent when asked. |
| Embedding: a refusal | An exception whose **message is the service's reply verbatim** (a test's echoed key came through). |
| Embedding: too few vectors, or a vector of the wrong length | **Not detected.** |
| Embedding: a blank key | **Does not stop** the library from calling the service. |
| Ingestor: order of calls | Embeds all segments in **one call**, then calls the store **once**. |
| Store: the collection | **Not created**: with it missing, `addAll`, `removeAll` and `clearStore` all fail with NOT_FOUND. |
| Qdrant client: `recreateCollectionAsync` | **Fails with "could not be deleted" when the collection does not exist yet**, which is every first run. Found when two live tests failed. |
| Store: payload | The chunk text under **`text_segment`**, plus the chunk's metadata keys. |

## What was added, and how

| Rule of `handWritten` | In `withLangchain4j` |
| --- | --- |
| Embed everything **before** replacing the collection | **Added:** `ReplacingStore` resets the collection inside the ingestor's single store call (see Decision). Tests: a failed embedding leaves the previous collection untouched; the replace happens only after every chunk has its vector. |
| Create the collection with the vector size and cosine distance | **Added:** `QdrantCollection` checks if it exists, deletes it, then creates it with Qdrant's own client (not `recreateCollectionAsync`, see above). |
| At most 100 texts per request | **Library already does this**; the request count is computed from that size, not observed per run. |
| The vector count equals the text count, each vector has the configured length | **Added:** `CheckedEmbeddings`; the run stops before the store. |
| Errors say the status only, never the service's reply | **Added:** the library's exceptions are caught and reduced to "answered HTTP n" or "not reachable"; the store side gives the gRPC status name only, never the server's text. |
| The key never in a URL; no key means nothing is called | URL: **library already does this**. No key: **added** (checked before anything is called, because the library would call anyway). |
| Title and company from the front matter; the block never a chunk | **Added by hand** in `SourceDocuments`: the block is parsed and cut off before the splitter sees the text. |
| Files in name order, `limit`, no text skipped, only markdown | **Added:** sorting and `limit` in `SourceDocuments`; the glob and the loader give the rest. |
| `charStart`/`charEnd` in the payload | **Not available:** the splitter gives no offsets; the payload has title, source, company, chunk number and the text. |

## How the chunks differ from handWritten's

Both count characters (600, 100 overlap), but this splitter breaks at paragraph, line, sentence and word boundaries and `handWritten` cuts fixed windows, so the chunks are **not the same**: they are shorter on average and fewer cut a sentence. **The number of chunks was not compared with handWritten's in this slice.**

## Consequences

- LangChain4j's ingestor gives the whole pipeline in one call and, unlike Spring AI's store, lets a wrapper put the replace between embedding and storing. The rules around the steps (checks, error text, no key, the collection) are still code of ours.
- The Qdrant store is a beta and does not create its collection; the Qdrant client's own recreate has a first-run failure. Both are found only by running them (the live tests).
- Task type is sent here; it is not with Spring AI's embedding model.
- Library upgrades can change a measured behaviour: the behaviour tests fail when they do.
