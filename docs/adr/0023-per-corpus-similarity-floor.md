# ADR 0023: a similarity floor for each knowledge base (E1.4)

Status: accepted, 2026-11-14. A Java-edition extension; follows from the E1.3 finding (ADR 0019).

## Context

A chunk is cited only if its cosine similarity to the question reaches a floor. The Python edition's floor is 0.45, tuned on the CRM documents (relevant chunks 0.50 to 0.80, unrelated questions 0.42 to 0.44). E1.3 showed it does not transfer: against the farm-supply handbook unrelated questions scored 0.45 to 0.48, so every strategy cited sources for all four off-topic questions. A floor belongs to a corpus. This slice gives each knowledge base its own.

## Decision

- **Each knowledge base can set `min-similarity`** (`knowledge-bases.extra[].min-similarity`); one that sets none uses the global `retrieval.min-similarity` (`RETRIEVAL_MIN_SIMILARITY`, default 0.45). A value outside 0 to 1 is refused at startup, naming the knowledge base. Hybrid search applies the same floor, and the reranker works after it, as before.
- **The CRM floor stays 0.45** (parity: both editions answer the same question over the same documents the same way). It is configurable, and the calibration below says 0.60 would separate relevant from unrelated questions better; changing it should be done in both editions together, so it is left as a decision for the owner.
- **The farm-supply floor is 0.64**, from calibration (below).
- **Calibration is a tool, not a guess:** `just kb-calibrate` replays recorded scores; `just kb-calibrate-record` asks the running API for them with the floor switched off. The search route takes an optional `minSimilarity` in the body (clamped to 0 to 1, a non-number is a 422) so calibration can see every candidate's score; the list shows each knowledge base's `similarityFloor`. The calibration labels are the same 24 questions the strategy comparison uses: for a knowledge base, "relevant" is its own questions plus the ones that need both, scored by the best chunk from a relevant document; "unrelated" is the other corpus's questions plus the four that belong to neither, scored by their best chunk.

## Calibration

| | relevant best-hit (min / median / max) | unrelated top-hit (min / median / max) | floor 0.45 lets through | calibrated floor |
| --- | --- | --- | --- | --- |
| crm | 0.61 / 0.71 / 0.77 | 0.44 / 0.52 / 0.60 | 10 of 12 unrelated | 0.60: keeps 12 of 12, lets 0 through |
| agri-supply | 0.65 / 0.74 / 0.78 | 0.45 / 0.55 / 0.62 | 12 of 12 unrelated | 0.64: keeps 12 of 12, lets 0 through |

**Held-out check.** The floors were chosen on the same 24 questions they are scored on, so twelve new questions were written afterwards (four relevant to each corpus's documents and four unrelated ones) and scored the same way (`evals/knowledge-bases/heldout.json`, `calibration-heldout.json`). CRM 0.60: 4 of 4 relevant kept, 0 of 8 unrelated through. Farm-supply 0.64: 3 of 4 relevant kept, 0 of 8 through; the one dropped relevant question scored 0.62.

**Pooled (36 questions per knowledge base):** CRM at 0.60 keeps 16 of 16 and lets 0 of 20 through, but the margin is 0.605 against 0.597. Farm-supply at 0.64 keeps 15 of 16 and lets 0 of 20 through; the scores genuinely overlap at about 0.62 (one relevant question at 0.618, one unrelated one at 0.619), so no floor separates them. 0.64 loses nothing more than 0.62 on these questions and leaves a wider margin from the unrelated side, which is why it is the shipped value.

## Effect on the strategy comparison

The E1.3 comparison re-run with floors crm 0.60 and agri-supply 0.64 (`recorded-calibrated-floors.json`, `baseline-calibrated-floors.json`; the same 24 questions, model and embeddings), against the single global 0.45 (ADR 0019):

| | route | all | agent |
| --- | --- | --- | --- |
| answerable questions: right sources found | 19/20 | 20/20 | 19/20 |
| of them, CRM-only questions | 7/8 | 8/8 | 8/8 |
| of them, farm-supply-only questions | 8/8 | 8/8 | 8/8 |
| of them, questions that need both | 4/4 | 4/4 | 3/4 |
| searched exactly the right knowledge bases (all questions) | 10/24 | 4/24 | 18/24 |
| searched at least the right ones | 23/24 | 24/24 | 23/24 |
| citations from the wrong knowledge base | 0/50 | 0/54 | 0/62 |
| no-corpus questions with no sources cited | 4/4 | 4/4 | 4/4 |
| model calls per question (mean) | 1.79 | 0.83 | 2.42 |
| median ms per question | 4514 | 2861 | 1717 |
| p95 ms per question | 7341 | 6039 | 2631 |
| failed requests | 0 | 0 | 0 |

| | route | all | agent |
| --- | --- | --- | --- |
| citations from the wrong knowledge base, global 0.45 | 31/91 | 43/91 | 22/126 |
| no-corpus questions with no sources cited, global 0.45 | 0/4 | 0/4 | 0/4 |
| right sources found, global 0.45 | 20/20 | 20/20 | 19/20 |
| model calls per question, global 0.45 | 2 | 1 | 2.29 |

What it shows:

1. **The off-topic problem is gone:** every strategy now cites nothing for the four off-topic questions, and no citation comes from a corpus the question does not belong to. With nothing retrieved, the answering model is not called, so `route` and `all` also use fewer model calls (1.79 and 0.83).
2. **Recall cost: one question each for two strategies** (`route` 19 of 20, `agent` 19 of 20, `all` 20 of 20). Both misses were routing choices, not the floor: the router sent the CRM question about delivery visibility only to the farm-supply corpus, and the agent searched only the CRM for a question about discounts at both. With one run per question this may be run-to-run variation. A floor cannot repair a routing mistake: a router or an agent that picks the wrong single corpus loses the question whatever the floor is (the router narrowed to a single corpus on the same number of questions, 10 of 24, with the global floor).
3. **Caution, a circular result:** the floors were calibrated on the same 24 questions this table scores, so "0 wrong citations" and "4 of 4 off-topic" are what the calibration was designed to give. The held-out check above is the honest evidence of how far they hold: CRM 0.60 did (4/4, 0/8); farm-supply 0.64 dropped one of four relevant questions.

## Consequences

- Off-topic questions get the fixed "could not find" answer instead of a confident answer from weak sources.
- A floor is a trade: stricter loses a relevant but weakly matched chunk (about one in sixteen here for farm-supply), looser lets noise through. Each corpus now has its own dial and a way to read it.
- Twelve held-out questions are still small. A larger labelled set, and recalibrating whenever a corpus or the embedding model changes, is the real practice.
- The CRM floor is the open decision: 0.60 is better supported by the calibration, and 0.45 matches Python; apply it to both editions or neither.
