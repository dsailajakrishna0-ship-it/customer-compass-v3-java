# ADR 0019: which way to decide where to search (E1.3 results)

Status: accepted, 2026-11-13. A Java-edition extension. Measures the three strategies of ADR 0018; changes no behaviour.

## Context

`POST /chat/multi` can decide where to search three ways (`route`, `all`, `agent`). The owner's question is which is worth using. E1.3 asks all three the same 24 labelled questions and counts what happened: 8 that belong to the CRM corpus, 8 to the farm-supply corpus, 4 that need both, and 4 that belong to neither. The "renewal", "pricing", "support" and "delivery" vocabulary appears in both corpora on purpose, so a router cannot decide on vocabulary alone.

## Result

Chat model for the router, the answers and the agent: `meta-llama/llama-3.1-8b-instruct` (paid, US$0.02 per million tokens in, $0.04 out; the whole recording of 72 requests cost a fraction of a cent). Embeddings: `gemini-embedding-2` (see the caveats). `just eval-multi` replays the table from `evals/knowledge-bases/recorded.json` with no keys; `just eval-multi-check` compares it with `baseline.json`.

| | route | all | agent |
| --- | --- | --- | --- |
| answerable questions: right sources found | 19/20 | 19/20 | 19/20 |
| of them, CRM-only questions | 8/8 | 8/8 | 8/8 |
| of them, farm-supply-only questions | 8/8 | 8/8 | 7/8 |
| of them, questions that need both | 3/4 | 3/4 | 4/4 |
| searched exactly the right knowledge bases (all questions) | 8/24 | 4/24 | 20/24 |
| searched at least the right ones | 24/24 | 24/24 | 23/24 |
| citations from the wrong knowledge base | 40/96 | 48/96 | 28/136 |
| no-corpus questions with no sources cited | 0/4 | 0/4 | 1/4 |
| model calls per question (mean) | 2 | 1 | 2.17 |
| median ms per question | 5057 | 2899 | 1346 |
| p95 ms per question | 10002 | 9116 | 2254 |
| failed requests | 0 | 0 | 0 |

How to read the rows: "right sources found" needs a relevant document from every knowledge base the question belongs to; "searched exactly the right knowledge bases" compares the set of corpora searched with the labelled set (so `all` can be exact only on the 4 questions that need both); "citations from the wrong knowledge base" counts sources shown to the model that came from a corpus the question does not belong to.

## What it shows

1. **All three find the right sources about equally** (19 of 20 answerable questions each). The strategies differ in noise and cost, not in recall. `route` and `all` miss the same one (mq-20, the Fleet Visibility against delivery windows comparison); `agent` misses a different one (mq-16, below).
2. **The small router is the weak spot.** On 20 of 24 questions it chose both knowledge bases, including 7 of the 8 CRM-only questions and 5 of the 8 farm-supply-only ones, and on all four of the "neither" questions it wrote a reason such as "neither knowledge base is about geography" and then still listed both ids. It never chose a single wrong corpus; it just did not narrow. `route` was therefore exact on only 8 of 24 questions, and one model call more expensive than `all` without being more precise.
3. **The agent is the most precise** (exact on 20 of 24, wrong-corpus citations 28 of 136, about 21%, against 42% for `route` and 50% for `all`) at 2.17 model calls on average. It chooses the corpus by trying one, reading, and trying another, which a one-shot classification cannot do.
4. **The agent's failure is its own:** on mq-16 (an early-payment discount at Stonebridge Orchards, a farm-supply document) it searched the CRM twice with the same query, never tried the other corpus, and found nothing. On the three "neither" questions it still searched the CRM once or twice before answering.
5. **No strategy handled the questions that belong to neither** (0 of 4 for `route` and `all`, 1 of 4 for `agent` with no sources cited). That is the similarity floor, not the strategies: see the caveats.
6. **Latency is not a ranking.** The agent's median was lowest here (1.3 s against 2.9 s for `all` and 5.1 s for `route`) because a short tool exchange with a small model can finish faster than a retrieval, an answer from four sources and (for `route`) a router call. One run per question on one provider's capacity: not a benchmark.

## Decision

- **No default changes in this PR.** The default stays `route` (a documented teaching default); the extension keeps all three.
- **Guidance recorded for the notes:** prefer `agent` when the model supports tool calling and the corpora are many or overlapping; prefer `all` when the model cannot call tools, corpora are few and retrieval is cheap (it never misses a corpus, and needs one model call); use `route` only with a router model strong enough to narrow (it was the worst of the three with this one) or when retrieval is expensive enough that skipping a corpus pays.
- **What would change the guidance:** a stronger router model, descriptions rewritten for the router, a calibrated similarity floor per corpus, or a router that gets the answer first (a cheap "which corpus" check on the retrieved chunks).

## Caveats

- **One 8B model, one run per question, 24 questions I labelled myself.** One question is 4 points of a row of 24, 12.5 points of the "both" row. No variance estimate. A stronger model would very likely route better.
- **The embedding model is not the default.** Google's free tier allows 1,000 embedding requests a day per model and `gemini-embedding-001` was used up by other work, so this recording used `gemini-embedding-2`. The similarity floor of 0.45 was tuned for `gemini-embedding-001` (relevant 0.50 to 0.80, unrelated 0.42 to 0.44); `gemini-embedding-2` scores unrelated text around 0.6, so the floor stops almost nothing and the "neither" row is much worse than it would be on the default model. Rows about which corpus was searched and which sources were found do not depend on the floor as much; the "no sources cited" row does. **Re-record on `gemini-embedding-001`** after the quota resets (`just eval-multi-record` with `EMBEDDING_MODEL` set as a label), and compare.
- Sources shown to the model are counted as "from the wrong knowledge base" even when the answerer ignored them; this measures noise in the prompt, not wrong answers. Answer quality was not judged.

## Consequences

- The comparison is reproducible without keys and can be re-recorded with another model, another embedding model or more questions.
- The default `route` strategy is knowingly the weakest with a small router; a later slice can change the default or improve the router once re-recorded on the default embedding model.
