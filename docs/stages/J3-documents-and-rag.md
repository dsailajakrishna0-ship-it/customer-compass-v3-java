# Stage J3: documents and RAG

Ports Python Stage 4 (ingestion, retrieval, `/chat/rag`, `/chat/crm`). Decisions: [ADR 0004](../adr/0004-documents-and-vector-store.md). Cut into slices: **J3.1** ingestion of Markdown (this page), J3.2 PDF, Word and Excel with page and sheet in citations, J3.3 retrieval and `POST /chat/rag`, J3.4 `POST /chat/crm`.

## J3.1: ingest Markdown documents

```mermaid
flowchart LR
  files["sample-data/documents/*.md"] --> parse["MarkdownParser<br/>front matter + body"]
  parse --> chunk["Chunker<br/>600 characters, 100 overlap"]
  chunk --> embed["Spring AI Gemini embeddings<br/>768 numbers per chunk"]
  embed --> qdrant["Spring AI QdrantVectorStore<br/>java_document_chunks"]
  parse --> pg["Postgres<br/>documents, ingestion_runs"]
```

| Piece | File | Job | Python twin |
| --- | --- | --- | --- |
| Chunking | `rag/Chunker.java` | overlapping windows of characters | `rag/chunking.py` |
| Reading | `rag/MarkdownParser.java` | front matter (title, type, company) and body | `rag/parse.py` |
| Index | `rag/VectorIndex.java` | Gemini embeddings plus the Qdrant collection, built on first use | `rag/embeddings.py`, `rag/qdrant_store.py` |
| Pipeline | `rag/DocumentIngestionService.java` | read, chunk, embed, store, record the run | `rag/ingest.py` |
| Routes | `rag/DocumentController.java` | `POST /documents/ingest`, `GET /documents`, `GET /documents/ingestion-runs` | `routes/documents.py` |

### Concepts

1. **Chunking is a policy, not a detail.** Too big and the embedding blurs several topics; too small and the answer is cut in half. The overlap keeps a sentence that straddles a boundary whole in one chunk.
2. **Ingestion is a separate, re-runnable step.** It clears and rebuilds, so running it twice never duplicates; chat only reads.
3. **A payload that can be cited without a join.** Every chunk carries its document id, title, path, type and company, so a result can be shown with its source straight from Qdrant.
4. **Postgres is the record, Qdrant is the index.** Documents and run history live in Postgres; vectors and the citable payload live in Qdrant.
5. **Use the framework, then test where it lies.** Two surprises (the reported vector length and `Long` stored as text) only appeared against the real services (ADR 0004).

### Questions

1. Why is the overlap 100 and not 0? What would a question about a sentence at a boundary do without it?
2. Re-running ingestion: what is cleared, and in what order? What happens to the history of runs?
3. Why is `document_id` an `int` in the payload and not a `long`?
4. The Gemini model is asked for 768 numbers. Why did the collection first come out as 3072, and where is that fixed?
5. A Markdown file has no `type`. What does the caller see, and which file is named?

### Code tour

`Chunker.java`, then `ChunkerTests.java` (the last test compares with the Python edition's chunk counts), then `DocumentIngestionService.java` and `DocumentIngestionTests.java` (a real Postgres and a real Qdrant in containers, with a fake embedding model).
