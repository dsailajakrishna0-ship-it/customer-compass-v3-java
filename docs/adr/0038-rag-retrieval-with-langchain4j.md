# ADR 0038: RAG retrieval with LangChain4j (J8.3c)

Status: accepted, 2026-11-16. Third approach of the J8.3 comparison (ADR 0036): the same backend as `handWritten`, built with LangChain4j's Gemini embedding model and Qdrant embedding store.

## Context

`handWritten` embeds the question with Gemini's REST API, asks Qdrant for the nearest chunks and applies the floor itself. LangChain4j has a model class for the first step and a store class (`QdrantEmbeddingStore`) for the second. This slice builds the same backend with them to see, with tests, what each does on its own and what had to be added to keep the rules `handWritten` enforces.

## Decision

- **A separate module** `comparisons/rag-retrieval/withLangchain4j` (port 18123), the same HTTP face as `handWritten` (`comparisons/common`). It searches `compare_withlangchain4j`, the collection the J8.2c ingestion built, whose chunks carry their text, `title`, `source`, `company` and an `index`.
- **LangChain4j 1.23.0**, the artifacts of the J8.2c module: `langchain4j`, `langchain4j-google-ai-gemini` (`GoogleAiEmbeddingModel`) and `langchain4j-qdrant` 1.23.0-beta33 (a beta, gRPC).
- **The floor is applied here, on the cosine similarity, not by the library.** Measured: the library's score is not the cosine (see the table). The store is searched with `minScore` 0, each score is converted back with `cosine = 2 * score - 1`, and the floor (0.60 by default, as in `handWritten`) is applied to that. So the floor means the same thing in every approach, and `considered` (how many the store returned) is known.
- **Task type `RETRIEVAL_QUERY`** for the question. The J8.2c ingestion used `RETRIEVAL_DOCUMENT`. The library sends no task type unless one is set.
- **Zero retries** (`maxRetries(0)`): a refused call is reported, not repeated.
- **The Qdrant store is behind a small seam** (`Target`: the store and how to close it), because gRPC cannot be faked with a plain HTTP server. The default build tests the pipeline with a recording fake store; the tests that need a real Qdrant are **skipped unless `QDRANT_GRPC_PORT` is set**, so the default build needs no keys, network or Docker. They use collections of their own and never touch `compare_withlangchain4j`.

## What LangChain4j does on its own (measured: `LangChain4jRetrievalBehaviourTests`, the store rows against a real Qdrant)

| Question | What LangChain4j 1.23.0 does |
| --- | --- |
| **The score of a match** | **Not the cosine: a relevance score `(cosine + 1) / 2`.** Against the query (1, 0): the same vector scores **1.0**, an orthogonal one **0.5**, an opposite one **0.0**, one at 45 degrees (cosine 0.7071) **0.8536**. |
| **`minScore`** | Compared with that relevance score. **A floor of 0.60 handed straight to `minScore` is a cosine of 0.20**: with `minScore` 0.6 the 45-degree chunk and anything with a cosine of 0.2 or more get through; only the orthogonal and opposite ones are dropped. This is the main trap of this approach. |
| No `minScore` | Everything up to `maxResults` comes back. |
| Order | **Best first**; `maxResults` limits the number. |
| Chunk metadata | Comes back with the chunk text (title, source, company, `index` were put there by the J8.2c ingestion). |
| Gemini: how many requests | A question is **one `embedContent` request**, key in the `x-goog-api-key` header, not in the URL. |
| Gemini: task type | **Not sent unless set** on the builder. |
| Gemini: a vector of the wrong length | **Not detected**: a reply with 3 numbers for a configured size of 4 comes back as a 3-number vector. |
| Gemini: a refusal | The exception (`RateLimitException` for 429) has **the service's own reply as its message** (a test's echoed key came through); one request with `maxRetries(0)`. |
| Gemini: a blank key | **Does not stop** the library from calling the service. |
| Store: a query of the wrong length | Refused by Qdrant with `INVALID_ARGUMENT` (inside a `RuntimeException`). |
| Store: a missing collection | Fails with `NOT_FOUND` (inside a `RuntimeException`, as the J8.2c ingestion also found). |

## What was added, and how

| Rule of `handWritten` | In `withLangchain4j` |
| --- | --- |
| The floor is a cosine similarity of 0.60 | **Added:** the score is converted back (`2 * score - 1`) and the floor applied to the cosine, with `minScore` left at 0. A tolerance of 0.000001 keeps a chunk exactly at the floor when the 32-bit score lands a hair below. |
| `considered` and `kept` are both reported | **Added:** by searching with `minScore` 0 and filtering here; with the library's own `minScore` the number considered would be unknown. |
| Hits best first | **Library already does this**; the hits are sorted again after the conversion. |
| No key means nothing is called | **Added:** checked first (the library would call anyway). |
| A vector of the wrong length is an error | **Added:** checked before the store is asked. |
| Errors say the status only, never the service's reply | **Added:** the embedding exception is reduced to "answered HTTP n" or "not reachable"; the store's gRPC exception to its status name (the "does not exist, run the ingestion first" sentence for `NOT_FOUND`, "not reachable" for `UNAVAILABLE`), or a fixed sentence. The server's text never comes out. |
| The key never in a URL | **Library already does this** (header). |
| One attempt per call | **Configured:** `maxRetries(0)`. |
| Task type for a question | **Configured:** `RETRIEVAL_QUERY`. |
| The caller cannot choose the collection | The shared server already enforces it. |

## Consequences

- LangChain4j gives a one-line embedding of the question and a one-call search, but its score scale differs from the cosine that the application's floor (ADR 0024) is calibrated on. An approach that passed 0.60 to `minScore` would have looked right, returned results and been wrong by a wide margin, and no error would have said so. The live test with known vectors is what shows it.
- The same applies to any approach that uses a library's score: check what scale it is on before using a calibrated number.
- Hybrid search and a reranker are not part of the comparison (ADR 0036).
- Library upgrades can change a measured behaviour: the behaviour tests fail when they do.
- **Not measured:** a live Gemini call, search quality on the real documents, whether the task type changes the scores, and speed.
