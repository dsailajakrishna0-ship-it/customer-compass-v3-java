# ADR 0053: the application's ingestion embeds before it replaces, in batches of at most 100

Status: accepted, 2026-11-28. The Java twin of the Python edition's ADR 0044. Measured with Testcontainers (a real Postgres and Qdrant) and a fake embedding model; **not against the live Google service**.

## Context

The Python edition found three weaknesses in its own ingestion (key in the URL, one request per text, the index emptied before the embedding calls) and fixed them. This ADR is the check of the same three points in the **Java application** (`DocumentIngestionService`, `KnowledgeBaseIngestion`), written from the code and the comparison lab's measurements (ADR 0034), not from memory.

| Point | In the Java application | Action |
| --- | --- | --- |
| **The key in the URL** | **No.** Spring AI's Gemini client sends the key in the `x-goog-api-key` header (measured in ADR 0034). | None |
| **One request per text** | **No, the other way round:** `store.add(points)` embeds all the chunks of **one document** in a single request, with **no cap at Gemini's limit of 100 texts per batch** (ADR 0034 measured "250 texts, one request"). The sample documents have at most a few chunks each, so the problem was hidden; a document of more than 100 chunks (a long PDF or spreadsheet) would be a refused request. | **Fixed:** at most 100 per request |
| **The index emptied before embedding** | **Yes.** `TRUNCATE documents` and `index.recreate()` ran **before** the embedding of the first document. A refused or failed embedding call half-way left a partial index (the first documents only) and the previous one gone. The same in the knowledge-base ingestion. | **Fixed:** embed first, then replace |

## Decision

1. **Two phases.** Read-only first: cut every document into chunks and embed **all chunks of the run in one batched call** (`VectorIndex.embedAll`: at most 100 texts per request, input order kept). Only then replace: truncate the rows, recreate the collections, insert the rows and add the chunks. An embedding failure leaves the **previous index and the document rows exactly as they were**, and the run is recorded as failed as before. The same for `KnowledgeBaseIngestion`.
2. **The store is given the finished vectors.** Spring AI's vector store always embeds the documents it is given (a `Document` has no field for a vector), so `VectorIndex.addEmbedded` hands it a model that makes no call and answers from the vectors computed in phase 1 (`PrecomputedEmbeddings`). Both small classes are the ones the J8.2b comparison module already proved, now in the application.
3. **Two checks per answer** (`BatchedEmbeddings`): the number of vectors must equal the number of texts, and each vector must have the model's declared length; otherwise the run fails with a plain message (`EmbeddingsUnavailableException`, which the API already shows as is). The expected length is the model's own `dimensions()`, which in production is the configured 768 (the model is wrapped by `FixedDimensionsEmbeddingModel`, ADR 0004).
4. **Not changed:** the embedding model and its options (no task type is sent, as before, so **the vectors are the ones the existing indexes hold** and nothing needs re-embedding), the chunker, the payload, the routes and the shared contract.

## Consequences

- **Memory:** every vector of a run is held until the replace (768 floats per chunk, about 3 KB each as `float[]`): fine for this corpus; a much larger one would need a staging collection that is swapped in (not built).
- **Quota:** a request of 100 is one HTTP request; whether the free tier counts it as one request or 100 embeddings is **not verified** (no live run).
- **Not covered by a test:** the failure path of `KnowledgeBaseIngestion` has no test of its own (it shares `embedAll` and `addEmbedded` with the CRM ingestion, whose failure path is tested); its happy path is covered by the existing `KnowledgeBasesTests` with unchanged counts.

## How this was checked

`BatchedEmbeddingsTests` (5): 250 texts become requests of 100, 100 and 50 with the vectors back in input order; 100 texts are one request and 101 are two; no text means no request; fewer vectors than texts and a vector of the wrong length each fail. `IngestionKeepsTheIndexTests` (3, Testcontainers): the 33 chunks of the 19 sample documents are embedded in **one** call (not one per document); a failed embedding call leaves the 33 stored chunks and the 19 document rows untouched and records `failed`; a first run that fails leaves nothing half built and the next run works. The existing `DocumentIngestionTests` and `KnowledgeBasesTests` pass with the same counts (19 documents, 33 chunks).
