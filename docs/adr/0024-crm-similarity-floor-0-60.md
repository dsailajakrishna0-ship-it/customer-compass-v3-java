# ADR 0024: the CRM similarity floor is 0.60 (E1.4 follow-up)

Status: accepted, 2026-11-14. The owner chose it for both editions; the Python edition made the same change in its ADR 0031.

## Context

ADR 0023 calibrated a floor per knowledge base and left the CRM at 0.45 (parity) with 0.60 as the better-supported value. The owner decided on 0.60 in both editions.

## Decision

- The global default `retrieval.min-similarity` (`RETRIEVAL_MIN_SIMILARITY`) and `Retriever.MIN_SIMILARITY` are **0.60**. It is the CRM documents' floor and the default for any knowledge base that sets none. `RETRIEVAL_MIN_SIMILARITY=0.45` restores the old behaviour.
- The farm-supply floor stays 0.64.

## What the numbers say (replayed from the recorded scores, no live calls)

Counting the chunks of the right documents for the 12 CRM questions (calibration set):

| Floor | Chunks kept in the top 10 | Top-four chunks kept | Questions that lose a top-four chunk | Unrelated questions let through |
| --- | --- | --- | --- | --- |
| 0.45 | 28/28 | 24/24 | 0/12 | 10/12 |
| 0.55 | 27/28 | 23/24 | 1/12 | 3/12 |
| 0.60 | 23/28 | 22/24 | 2/12 | 0/12 |
| 0.64 | 16/28 | 16/24 | 5/12 | 0/12 |

On the 4 held-out CRM questions 0.60 keeps 6 of 7 chunks. Every question keeps its best chunk.

## Costs and limits

- The margin is 0.008 (0.605 against 0.597) on 12 questions per corpus; another sample could land either side.
- About 8% of the weaker supporting chunks (2 of 24 in the top four) are lost, so some answers cite one source fewer.
- `gemini-embedding-001` only; another embedding model needs its own calibration.
- The strategy comparison with these floors already exists (`baseline-calibrated-floors.json`, ADR 0023); `baseline.json` and `recorded.json` keep the old global 0.45 as history.
- Not measured: whether a refund-policy-type question (plausible but uncovered) is now filtered.
