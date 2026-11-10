# ADR 0004: documents, embeddings and the vector store (J3.1)

Status: accepted, 2026-11-10.

## Context

The Python edition ingests documents (parse, chunk, embed, store) into Qdrant with two vectors per chunk (meaning and keywords) and searches them with hybrid search and an optional reranker. The Java edition must expose the same routes and, later, answer from the same documents. J3.1 is only the first slice: Markdown files in, chunks with embeddings out, stored and countable.

## Decision

- **Spring AI for the two framework parts:** `QdrantVectorStore` (module `spring-ai-qdrant-store`) and the Gemini embedding model (`spring-ai-google-genai-embedding`), as in the roadmap. Only the modules, not the starters, so nothing is auto-configured: the application starts without a key and without Qdrant, and a missing `GOOGLE_API_KEY` is reported when ingestion is used.
- **Our own code for the parts that must equal Python:** the chunker (`Chunker`, windows of 600 characters with 100 overlap, counted in code points), the Markdown front matter rules (`MarkdownParser`), and the ingestion order (record the run, clear, store, record the result). Test: the 16 sample Markdown files give exactly the chunk counts the Python edition reports (28 chunks in total).
- **Dense vectors only in J3.1.** Python also stores a keyword vector for hybrid search. Spring AI's store holds one vector per point, so hybrid search is a J3.3 decision (an ADR of its own: a second collection, direct Qdrant calls, or dense only).
- **Own collection name,** `java_document_chunks`, so the Java edition never replaces the Python edition's `document_chunks` when both run against one Qdrant.
- **The index records its embedding model in Postgres** (`ingestion_runs.embedding_model`), because Spring AI's Qdrant store cannot write collection metadata the way the Python edition does. The mismatch check of Python Stage 4.3 will read the latest completed run (J3.3).

## What the first real run found

1. **Dimensions.** Spring AI's Gemini model sends the requested size (768) to Google, but reports its own default (3072) when asked how long its vectors are, so the collection was created at 3072 and every insert was refused. `FixedDimensionsEmbeddingModel` reports the configured length (a test shows the difference). A fake model in a unit test would never have shown this: it needed the real service.
2. **A `Long` payload value is stored as text.** Spring AI's Qdrant store writes `Long` metadata as a string, so filtering by `document_id` found nothing. `document_id` is an `int` in the payload, as in the Python edition, and an exact-match filter works.

## Consequences

- Ingestion skips PDF, Word and Excel files (logged); they arrive in J3.2 with Apache PDFBox and POI or the choice recorded there. Until then the Java edition ingests 16 documents and 28 chunks where Python has 19 and 33.
- A failed run returns the summary with HTTP 502, like the Python edition. Unlike Python, an error message that did not come from our own code is replaced by the exception type (the full error is in the API log), because a Qdrant or Google error could carry request details.
