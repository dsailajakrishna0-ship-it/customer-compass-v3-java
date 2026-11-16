# ADR 0037: RAG retrieval with Spring AI (J8.3b)

Status: accepted, 2026-11-16. Second approach of the J8.3 comparison (ADR 0036): the same backend as `handWritten`, built with Spring AI's Gemini embedding model and Qdrant vector store.

## Context

`handWritten` embeds the question by REST, asks Qdrant for the nearest chunks and applies the floor in its own code. Spring AI's `QdrantVectorStore.similaritySearch(SearchRequest)` does the embedding and the search in one call and can apply a similarity threshold itself. This slice builds the same backend with it to see, with tests, what the library does on its own and what had to be added to keep the rules `handWritten` enforces.

## Decision

- **A separate module** `comparisons/rag-retrieval/withSpringAi` (port 18122), the same HTTP face as `handWritten` (`comparisons/common`). It searches `compare_withspringai`, the collection the J8.2b ingestion built, and reads the payload that ingestion wrote (`doc_content` for the text, plus title, source, company, chunkIndex). Library classes only, as in the application and in J8.2b.
- **Spring AI 2.0.2:** `QdrantVectorStore` and `GoogleGenAiTextEmbeddingModel`, over the Google GenAI SDK, the same artifacts as the ingestion module. The embedding model is pointed at a fake server in tests through the SDK's base URL.
- **The store is behind a small interface** (`ChunkSearch`), because its gRPC protocol cannot be faked with a plain HTTP server. The default build tests the pipeline against a recording fake and the real classes against a fake Gemini and a Qdrant address that is not there; `SpringAiQdrantSearchTests` runs the real store and is **skipped unless `QDRANT_GRPC_PORT` is set**, so the default build needs no keys, network or Docker.
- **The floor is applied in our code, not by the library.** The library can apply a threshold, but it hands it straight to Qdrant, and measured, that threshold is strict (a chunk whose score equals it is dropped) and, even with the "accept all" value, drops chunks of cosine 0 or below. A chunk exactly at the floor must be kept and `considered` must count what the store returned, so the search is made with no threshold and the floor is checked in `SpringAiRetrieve`.
- **One attempt per Gemini call** (`HttpRetryOptions.attempts(1)`, as in J8.2b): a refused search should stop, not spend quota five times over about 35 seconds.
- **No key, nothing built:** the model and the store are created only when a search needs them; with no key the search fails before either exists.

## What Spring AI does on its own (measured: `SpringAiRetrievalBehaviourTests` without Docker, `SpringAiQdrantSearchTests` against a real Qdrant)

| Question | What Spring AI 2.0.2 does |
| --- | --- |
| Who embeds the question? | **The store itself:** one search is one `embed` call and, with this model, **one `batchEmbedContents` request** holding the one question. |
| Embedding task type | **Never sent** (the request body has the model, the text and `outputDimensionality`). `handWritten` sends `RETRIEVAL_QUERY`. |
| API key | In the `x-goog-api-key` header, **not in the URL**. |
| What is `Document.getScore()`? | **Qdrant's cosine similarity** as a float widened to a double: 1.0 for an identical vector, 0.7071067690849304 for 45 degrees, and **0.6000000238418579** for a chunk stored as 0.6. The metadata also holds `distance` = 1 minus the score, as a `Float`. |
| Order | **Best first.** |
| Is the threshold applied inside the store? | **Yes, by Qdrant** (`score_threshold`), so the chunks it drops are not counted. |
| Is the threshold `>` or `>=`? | **Strict:** with the threshold set to a chunk's own score (0.6000000238418579) that chunk was **not returned**. |
| Threshold range | **0 to 1 only:** a negative value is refused (`IllegalArgumentException: Similarity threshold must be in [0,1] range.`). |
| "Accept all" (0.0) | **Does not return everything:** chunks of cosine 0 and -1 were missing from a search with topK 10. So `considered` under-counts them. |
| `topK` | Honoured: topK 2 returned the two closest. |
| Chunk text and metadata | The text is `Document.getText()` (the `doc_content` payload key is taken out of the metadata); the other payload keys come back as metadata. **A Java `int` is stored as an integer and comes back as a `Long`; a Java `long` is stored as a string and comes back as one** (found when a test stored a `long`; the J8.2b ingestion stores an `int`, so its chunk numbers are numbers, and `SpringAiRetrieve` reads a text number too). |
| A refusal from Gemini (429) | **`ClientException` whose message contains the service's reply** (a test's echoed key came through it). |
| A Gemini that is not there | `GenAiIOException` wrapping a `ConnectException`. |
| A vector of the wrong length from Gemini | **Returned as it is** (3 numbers for a size of 4); an empty reply is a raw `NoSuchElementException`. |
| A query vector of the wrong size at the store | **Qdrant refuses it** (`INVALID_ARGUMENT ... expected dim: 4, got 3`); the library does not check. |
| A missing collection | A `RuntimeException` over an `ExecutionException` over **gRPC `NOT_FOUND`**, whose text names the collection. |

## What was added, and how

| Rule of `handWritten` | In `withSpringAi` |
| --- | --- |
| The floor is at or above, applied to cosine similarity | **Added:** the floor is applied in `SpringAiRetrieve` on the score (the library's threshold is strict and drops chunks of cosine 0 or below, see above). A chunk exactly at the floor is kept (test). |
| `considered` counts what the store returned | **Added by searching with no threshold:** it is then the number of chunks returned (at most `topK`). Chunks of cosine 0 or below are never returned, so with a floor of 0 this approach cannot show them; `handWritten` can. |
| Hits best first | **Library already does this;** `SpringAiRetrieve` sorts again so the order does not depend on it. |
| Errors say the status only, never the service's reply | **Added:** `CheckedEmbeddings` reduces the embedding errors to "answered HTTP n" or "not reachable"; `QdrantChunkSearch` reduces the store's gRPC errors to a fixed sentence (a test shows the key and the server's text do not come out). |
| A vector of the wrong length is an error | **Added:** `CheckedEmbeddings` checks the count and the length before the store is asked (the library accepts a wrong-length vector). |
| One attempt per Gemini call | **Configured** (see Decision). |
| No key means nothing is called | **Added:** checked before the model or the store is built. |
| A missing collection says to run the ingestion first | **Added:** `QdrantChunkSearch` maps gRPC `NOT_FOUND` to that sentence (a real-store test). |
| The key never in a URL | **Library already does this** (header). |
| Task type `RETRIEVAL_QUERY` | **Cannot be done** with this model: it is never sent. A difference, stated; it may change the scores compared with `handWritten` and was not measured. |
| The caller cannot choose the collection | The shared server already enforces it. |

## Consequences

- One library call replaces the embedding request and the search request, but the rules around it (floor, counts, errors, checks) are code of ours, as in J8.1b and J8.2b.
- The library's own threshold is not usable here: it is strict, counts nothing it drops and cannot see cosine 0 or below.
- This approach embeds the question with no task type while the ingestion it reads also sent none (J8.2b), so the two sides match; whether that scores better or worse than document and query task types was not measured.
- The real store is exercised only when a Qdrant is available (`SpringAiQdrantSearchTests`).
- Library upgrades can change a measured behaviour: the behaviour tests fail when they do.
