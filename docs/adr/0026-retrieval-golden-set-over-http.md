# ADR 0026: the retrieval golden set is scored over HTTP from a recording (J7.2)

Status: accepted, 2026-11-14. Follows ADR 0025.

## Context

The Python edition scores retrieval on `evals/golden/retrieval.json` (42 questions: 36 answerable with their relevant documents, 6 the documents do not answer) with recall@1/3/5, MRR and a decline rate, calling its retrieval function directly. Retrieval needs real embeddings (one `GOOGLE_API_KEY` call per question), so unlike the guard (J7.1) it cannot be re-run in a keyless test. It needs the pattern of `just eval-multi`: record once, replay for free.

## Decision

- **A script scores a recording; the recording is made through the running API.** `evals/retrieval/retrieval_eval.py` replays `recorded.json` (no keys, no network), checks it against `baseline.json`, and with `--record` asks `POST /api/v1/knowledge-bases/crm/search` for the first 10 chunks of each question. Documents are the file names of the cited sources, deduplicated at their best rank, exactly as the Python run does. The scoring functions are the Python edition's, line for line, and `selftest.py` works them by hand on a made-up recording.
- **One small API addition, Java-only:** the knowledge-base search body takes an optional `company` (a company name). It scopes the search the way company questions are scoped elsewhere (that company's documents and the company-agnostic ones). Three golden questions have a company, and without it they would be scored against everyone's documents. An unknown name is a 404, a non-string is a 422. The shared contract does not include this route, so nothing in it changes.
- **The check allows a 0.02 move** per metric (the Python retrieval tolerance), requires the same question counts, and fails when no baseline exists. A replay of the same recording matches exactly; the tolerance is for comparing a new recording (another model, floor or mode) with the saved baseline through `RECORDED_PATH`.
- **The floor can be chosen for a run** (`MIN_SIMILARITY`), recorded in `recorded.json`. The Python baseline was measured at 0.45 and both editions now default to 0.60 (ADR 0024), so a like-for-like comparison with that baseline records at `MIN_SIMILARITY=0.45`; a recording at the default floor measures what runs today.
- **No baseline ships in this slice.** `baseline.json` is written from a real recording, which needs the owner's keys and about 42 embedding calls. Until then `just eval-retrieval-check` fails with a plain message, so CI runs only the self-test.

## Limits

- The set is small and written by the project, and the relevant-document labels are the Python edition's. Every labelled file exists in the Java edition's documents (checked by the self-test), but whether the Java chunking ranks them the same way is exactly what the recording measures.
- Dense mode only by default; hybrid and the reranker change the numbers and are recorded as labels (`RETRIEVAL_MODE`), not compared here.
- Recall and MRR are on documents, not chunks or answers: they say whether the right document was among the first few, not whether the model's answer was right.
