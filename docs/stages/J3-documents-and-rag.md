# Stage J3: documents and RAG

Ports Python Stage 4 (ingestion, retrieval, `/chat/rag`, `/chat/crm`). Decisions: [ADR 0004](../adr/0004-documents-and-vector-store.md). Cut into slices: **J3.1** ingestion of Markdown (this page), J3.2 PDF, Word and Excel with page and sheet in citations, J3.3 retrieval and `POST /chat/rag`, J3.4 `POST /chat/crm`, **J3.5** the optional reranker (below), **J3.6** hybrid search (below).

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

## J3.2: PDF, Word and Excel, with the page or sheet in the citation

```mermaid
flowchart LR
  file["file"] --> kind{"format"}
  kind -->|".md"| md["MarkdownParser<br/>front matter"]
  kind -->|".pdf"| pdf["PdfReader (PDFBox)<br/>one segment per page"]
  kind -->|".docx"| word["WordReader (POI)<br/>one segment, headings as ##"]
  kind -->|".xlsx"| xl["ExcelReader (POI)<br/>one segment per sheet"]
  pdf --> side["sidecar<br/>report.meta.yaml"]
  word --> side
  xl --> side
  md --> chunk["Chunker.chunkSegments<br/>chunk keeps page or sheet"]
  side --> chunk
```

| Piece | File | Job | Python twin |
| --- | --- | --- | --- |
| Dispatch | `rag/DocumentReader.java` | picks the reader, skips `.meta.yaml` sidecars | `parse_file`, `is_document` |
| Sidecar | `rag/DocumentMetadata.java` | title, type, company for the non-Markdown formats | `read_sidecar`, `_check` |
| PDF | `rag/PdfReader.java` | one segment per page with text | `parse_pdf_pages` |
| Word | `rag/WordReader.java` | one segment of Markdown-like text | `parse_word` |
| Excel | `rag/ExcelReader.java`, `rag/MarkdownTable.java` | one segment per sheet, the table laid out as Python lays it out | `parse_sheets` |

### Concepts

1. **A citation says where.** "Clause 7.3, page 2" or "Fleet Plus, sheet Rate card" is worth more than a file name. So the reader keeps a location with each run of text and every chunk inherits it.
2. **Equal text, not just equal meaning.** Both editions embed the text of the same files; if the Java reader wrote the table differently, the vectors would differ and the same question could cite a different chunk. The tests compare the text character for character.
3. **No library to copy, so copy the output.** Docling has no Java twin; the Java readers reproduce its output shape (headings, table layout) and the test says whether they got it right.
4. **Metadata beside the file.** Only Markdown can carry its own metadata. A sidecar keeps the rule simple and the error message exact.

### Questions

1. Why is a PDF split into one segment per page instead of one segment for the whole file?
2. A chunk from page 2 starts at character 0 of its chunk. Relative to what are `char_start` and `char_end`?
3. The Excel table has a column of prices. Which alignment does it get, and why does the width not equal the longest number?
4. A PDF is added with no `.meta.yaml`. What does the caller see?
5. What can a spreadsheet hold that this reader does not tell apart?

### Code tour

`DocumentReader.java`, then `ExcelReader.java` and `MarkdownTable.java`, then `DocumentReaderTests.java` (the expected text in the tests is the Python edition's own output for the same files).

## J3.3: retrieval and `POST /api/v1/chat/rag`

```mermaid
flowchart LR
  q["question"] --> check["Retriever<br/>index built with this model?"]
  check --> embed["embed the question<br/>(Spring AI, Gemini)"]
  embed --> search["Qdrant: closest 4 chunks<br/>similarity at least 0.45"]
  search --> none{"any chunks?"}
  none -->|"no"| said["fixed sentence,<br/>no model call"]
  none -->|"yes"| ctx["RagContext<br/>[1] Source: title (type, page 2)"]
  ctx --> gw["ModelGateway<br/>grounded prompt"]
  gw --> out["message + citations + usage"]
```

| Piece | File | Job | Python twin |
| --- | --- | --- | --- |
| Retrieval | `rag/Retriever.java` | search, the 0.45 floor, the index model check | `rag/retrieve.py` (dense mode) |
| Context | `rag/RagContext.java` | numbered sources, the untrusted wrapper, the citation shape | `build_context`, `_citation` |
| Route | `chat/RagChatController.java` | `POST /api/v1/chat/rag` | `rag_chat` in `routes/chat.py` |

### Concepts

1. **Retrieval before generation.** The model is only shown what retrieval found; it is told to answer from those sources and cite them as `[1]`, `[2]`.
2. **A floor that says "I do not know".** A chunk below 0.45 similarity is not cited, so an unrelated question finds nothing and the model is never called. It cannot catch a plausible but uncovered question; the model's "the sources do not say" answer covers that.
3. **Retrieved text is data.** The sources sit inside `<untrusted_context>` tags and the prompt says to treat them as claims to cite, never as instructions. The scan that looks for injected commands comes in J6.
4. **A citation says where.** Each citation carries the document, its type and company, the similarity, a 200-character snippet and, for a PDF or a spreadsheet, the page or the sheet.
5. **Refuse a mismatched index.** Vectors from two embedding models cannot be compared, so a changed model is caught and the fix (ingest again) is named.

### Questions

1. Why does a question about the capital of France not call the model at all?
2. A document was ingested with another embedding model. What does the user see, and what do they do?
3. Which message of the conversation gets the context, and why only that one?
4. The 0.45 floor lets a question about a refund policy through. What stops the model from inventing a policy?
5. Why can `Clause 7.3` rank well with meaning search alone, and what kind of question would not?

### Code tour

`Retriever.java`, then `RagContext.java` and `RagContextTests.java`, then `RagChatController.java` and `RagChatTests.java` (the test records what the model is shown).

## J3.4: the CRM copilot, `POST /api/v1/chat/crm`

```mermaid
flowchart LR
  q["question"] --> router["QueryRouter<br/>keywords + which company"]
  router -->|"deal words + a company"| sql["CrmFacts<br/>SQL summary"]
  router -->|"said / email words,<br/>or no deal words"| ret["Retriever<br/>scoped to the company"]
  sql --> ctx["facts block (do not cite)<br/>+ numbered sources"]
  ret --> ctx
  ctx --> gw["ModelGateway<br/>CRM prompt"]
  gw --> out["message + facts + citations<br/>+ scopedCompany + usage"]
  router -->|"nothing found"| none["fixed sentence,<br/>no model call"]
```

| Piece | File | Job | Python twin |
| --- | --- | --- | --- |
| Router | `chat/QueryRouter.java` | keywords and the named company | `chat/router.py` |
| Facts | `crm/CrmFacts.java` | company list, summary with open deals and counts | `crm/queries.py` |
| Scope | `rag/Retriever.java` | `company_id = X or 0` filter | `_company_filter` |
| Route | `chat/CrmChatController.java` | combine, prompt, respond | `crm_chat` in `routes/chat.py` |

### Concepts

1. **Two kinds of truth.** A number from the database is correct by construction and is stated plainly. A claim from a document is only as good as its source and gets a citation. Keeping the two apart in the prompt and in the response (`facts` and `citations`) is the point of this route.
2. **A router you can read.** The decision is a keyword match, so any answer can be explained by pointing at a word. The price is that it does not understand meaning ("pineapple" names Harbor & Pine).
3. **Scope is enforced where the data is.** The company filter runs inside the vector search, before ranking, so another customer's chunk cannot appear in the results at all. A test asks about three companies and checks every citation.
4. **A framework limit shapes the data.** Spring AI's Qdrant filter cannot test for a missing value, so company-agnostic documents are stored with `company_id` 0 (ADR 0007).
5. **Nothing found means no model.** The route says so itself instead of letting a model improvise.

### Questions

1. "What open deals does Acme Fabrication have?" does not search documents. Which words decide that?
2. A question names no company and mentions "deals". What does the user get, and is the model called?
3. Why are company-agnostic documents stored with `company_id` 0 and not left without one?
4. After upgrading to J3.4 the scoped search misses the product overviews. Why, and what is the fix?
5. Why is a fact never given a citation number?

### Code tour

`QueryRouter.java` and `QueryRouterTests.java`, then `CrmFacts.java`, then `CrmChatController.java`, then `CrmChatTests.java` (the test `aQuestionAboutOneCompanyNeverRetrievesAnotherCompanysDocuments` is the safety property).

## J3.5: the optional reranker

Decisions: [ADR 0015](../adr/0015-optional-reranker.md).

```mermaid
flowchart LR
  q["question"] --> search["dense search<br/>RETRIEVAL_CANDIDATES chunks, floor 0.45"]
  search -->|"RERANK_PROVIDER=off"| top["first top k"]
  search -->|"voyage / cohere"| rr["Reranker<br/>one HTTP call, 5 s limit"]
  rr -->|"ranking"| ordered["its order decides the top k"]
  rr -.->|"any failure"| top
```

| Piece | File | Job | Python twin |
| --- | --- | --- | --- |
| The call | `rag/Reranker.java` | request per provider, parse and sort the reply, never throw | `rag/rerank.py` |
| Settings | `rag/RerankProperties.java` | provider, candidates, model, timeout, keys | `config.py` |
| Where it plugs in | `rag/Retriever.java` | longer shortlist, then rerank | `rag/retrieve.py` (`_rerank`) |

### Concepts

1. **Two-stage retrieval.** A fast, approximate first stage finds candidates; a slow, accurate second stage orders a short list.
2. **Optional enrichment never breaks the product.** Every failure falls back to the first stage's order.
3. **Do not trust the reply's order.** Sort by score, drop duplicates and out-of-range indexes.
4. **Keys stay out of logs.** Only the error type and status are logged.

## J3.6: hybrid search

Decisions: [ADR 0016](../adr/0016-hybrid-search-keyword-collection.md).

```mermaid
flowchart TD
  ingest["ingestion"] --> chunks[("chunks collection<br/>dense vector + payload")]
  ingest --> kw[("chunks_bm25 collection<br/>BM25 sparse vector + company_id")]
  q["question"] --> d["dense search<br/>top N, no floor"]
  q --> k["keyword search<br/>top N, same filter"]
  chunks --> d
  kw --> k
  d --> rrf["reciprocal rank fusion<br/>k = 60"]
  k --> rrf
  rrf --> floor["similarity floor 0.45<br/>(cosine; computed from the stored vector for keyword-only hits)"]
  floor --> rr["optional reranker (J3.5)"]
  rr --> out["top k chunks"]
```

| Piece | File | Job | Python twin |
| --- | --- | --- | --- |
| Keyword vectors | `rag/Sparse.java` | tokenize, hash, BM25 weights | `rag/sparse.py` |
| Keyword collection | `rag/KeywordIndex.java`, `VectorIndex.recreate` | create with the IDF modifier, store, search, fetch | `rag/qdrant_store.py` |
| Fusion and mode | `rag/Retriever.java` (`hybrid`, `rrfFuse`, `cosine`) | the two searches, merge, floor | `rag/retrieve.py` |
| Settings | `rag/RetrievalProperties.java` | `RETRIEVAL_MODE`, `RRF_K` | `config.py` |

### Concepts

1. **Meaning and exact words fail differently.** Embeddings blur a clause number; keywords miss a paraphrase. Using both covers each other's blind spot.
2. **BM25 in two halves.** Term frequency is computed here per chunk; rarity (IDF) is applied by Qdrant across the collection.
3. **Fuse ranks, not scores.** Cosine and BM25 are on different scales; reciprocal rank fusion only uses positions, so there is nothing to tune.
4. **One floor for both modes.** A keyword-only hit is scored by meaning too, so "no relevant source" still means no sources.
5. **Measure before switching the default.** In V3's own evals hybrid was slightly worse than dense on our golden set; that is why the default is `dense`.
