# ADR 0016: hybrid search with a second Qdrant collection of BM25 vectors (J3.6)

Status: accepted, 2026-11-13 (scope chosen with the owner: port the reranker, then hybrid search).

## Context

Dense search finds text that means the same thing but can blur exact terms: a clause number, a deal name, an abbreviation. The Python edition adds keyword matching as a mode, `RETRIEVAL_MODE=hybrid` (its ADR 0019): each chunk gets a second, sparse vector built by our own BM25 code, the two searches run separately and are merged by reciprocal rank fusion. It is off by default; on its golden set it was slightly worse than dense (recall@1 0.787 against 0.843, MRR 0.958 against 1.000, recall@5 1.000 against 0.986), so the default stays `dense` in both editions. ADR 0006 left open how Java would do it, because Spring AI's Qdrant store keeps one unnamed vector per chunk.

## Decision

- **A second collection holds the keyword vectors:** `<collection>_bm25`, one sparse vector named `bm25` (with Qdrant's IDF modifier) per chunk, under the same point id as the chunk, plus a `company_id` payload for the scoping filter. The chunk collection and everything Spring AI does with it are untouched.
- **The maths is the Python edition's, word for word** (`Sparse`): lower-case words and numbers, a CRC32 hash as the word's index (tests check the same indexes and weights Python computes), BM25's term-frequency weight (k1 1.2, b 0.75, 100 words average) per chunk, and weight 1 per distinct word for the question. Qdrant's IDF supplies the rarity part; TF times IDF is BM25.
- **Filled at ingestion, rebuilt with the chunks.** `VectorIndex.recreate()` drops and recreates both collections, ingestion adds each chunk's keyword vector after the chunk itself. Hybrid mode needs no extra step and an index built before this slice only needs one re-ingest. Until then a hybrid search finds no keyword matches and falls back to dense (logged as `keyword_index_missing`).
- **Two searches, merged in Java by reciprocal rank fusion** (`k = 60`, `RRF_K`), exactly as Python: the dense search asks for `RETRIEVAL_CANDIDATES` chunks without the similarity floor, the keyword search for the same number. Only positions are fused, so cosine and BM25 scores need no tuning.
- **The 0.45 floor applies after the merge, to every candidate.** A chunk found only by keywords gets its meaning-similarity as the cosine between the question's embedding and the chunk's stored dense vector (fetched from the chunk collection with its payload). The same floor therefore holds in both modes, and an unrelated question still finds nothing to cite.
- **The reranker composes.** The merged, floored shortlist (up to `RETRIEVAL_CANDIDATES` with a reranker, else top k) goes to the reranker exactly as in dense mode.
- **A setting chooses the mode:** `RETRIEVAL_MODE=dense|hybrid`, default `dense`. The response shape does not change, so the contract and the web app are unaffected.

## Alternatives considered

- **Qdrant's server-side fusion** (a prefetch of both searches, then a fusion query): less code, but the fused score is rank-based and replaces the cosine the floor and the UI use.
- **Replace Spring AI's store with direct Qdrant calls and named vectors:** one collection and one write, but it throws away the framework the stage is meant to teach and changes the stored layout for everything.
- **FastEmbed or SPLADE sparse models:** better keyword handling, but a new dependency or a local neural model.

## Consequences

- Hybrid mode costs one extra embedding call per question that has keyword-only hits (the dense search is Spring AI's, which embeds internally, and the question's vector is needed again for their cosine), plus one keyword query and one fetch.
- The same trade-offs as Python: a keyword-only chunk is dropped when its meaning is far from the question (by design); the tokenizer has no stemming or stop words (IDF down-weights common words); two words can hash to one index.
- Two collections to keep in step: both are recreated together and filled in one ingestion; deleting only one leaves hybrid on dense (safe) or fails the ingestion's next upsert (visible).
- `GET /documents/vector-search-info` (exact against approximate search) is still not built; Spring AI does not expose search parameters.
