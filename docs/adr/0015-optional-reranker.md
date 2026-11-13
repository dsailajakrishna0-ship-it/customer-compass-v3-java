# ADR 0015: an optional hosted reranker, off by default (J3.5)

Status: accepted, 2026-11-13 (scope chosen with the owner: port the reranker, then hybrid search).

## Context

Dense search is fast and approximate. A cross-encoder reranker reads the question and each candidate together and re-orders a short list more accurately, at the price of one extra network call. The Python edition has it as an optional step (its ADR 0019, Stage 4.1b): `RERANK_PROVIDER` off, `voyage` or `cohere`, default off. The Java edition must give the same behaviour behind the same settings.

## Decision

- **Same providers, same defaults.** Voyage `rerank-2.5-lite` (the first 200 million tokens are free per account) and Cohere `rerank-v5.0-fast` (a Trial key allows 10 requests a minute and 1,000 calls a month, which an evaluation run can use up). `RERANK_PROVIDER=off` is the default; `RERANK_MODEL` overrides the model; `RERANK_TIMEOUT_SECONDS` (default 5) bounds the call; `RETRIEVAL_CANDIDATES` (default 20) is the length of the shortlist.
- **Plain HTTP, no SDK.** One `POST` with a bearer key through the JDK `HttpClient`; the request shape differs only in the limit field (`top_k` for Voyage, `top_n` for Cohere) and the reply field (`data` or `results`).
- **Reranking never breaks retrieval.** Off, no key, a timeout, a 4xx or 5xx, a reply that is not a ranking: the result is empty and the search order stays. The reply is sorted by `relevance_score` rather than trusted for order, and duplicate or out-of-range indexes are ignored. A failure is logged with the error type and status only, never the key or the reply.
- **Where it sits.** With a reranker the search asks for `RETRIEVAL_CANDIDATES` chunks (after the same 0.45 similarity floor); the reranker picks and orders the final top k. Without one the search asks for top k, exactly as before. Chunk text sent is `title: content`, as in Python. A shortlist of one or none is not sent.
- **No new dependency**, no change to the API response shape, no re-ingest.

## Alternatives considered

- **A local cross-encoder** (an ONNX model in the JVM): no key, but a model download and CPU inference in the image; Python rejected it for the same reason.
- **A Spring AI reranker abstraction:** none in the version we use; the call is small enough to read.

## Consequences

- A new outbound call and a latency cost per question only when the setting is on; the default configuration is unchanged.
- The agent's `search_documents` and the tools chat go through the same retriever, so they are reranked too.
- Whether the reranker earns its cost is for the evals to say (Python's `just eval-rerank`); this slice makes the behaviour available and safe.
