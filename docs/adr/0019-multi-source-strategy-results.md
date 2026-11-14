# ADR 0019: which way to decide where to search (E1.3 results)

Status: accepted, 2026-11-13; re-recorded on the default embedding model 2026-11-14. A Java-edition extension. Measures the three strategies of ADR 0018; changes no behaviour.

## Context

`POST /chat/multi` can decide where to search three ways (`route`, `all`, `agent`). The owner's question is which is worth using. E1.3 asks all three the same 24 labelled questions and counts what happened: 8 that belong to the CRM corpus, 8 to the farm-supply corpus, 4 that need both, and 4 that belong to neither. The "renewal", "pricing", "support" and "delivery" vocabulary appears in both corpora on purpose, so a router cannot decide on vocabulary alone.

## Result

Chat model for the router, the answers and the agent: `meta-llama/llama-3.1-8b-instruct` (paid, US$0.02 per million tokens in, $0.04 out; 72 requests, a fraction of a cent). Embeddings: `gemini-embedding-001`, the default. `just eval-multi` replays the table from `evals/knowledge-bases/recorded.json` with no keys; `just eval-multi-check` compares it with `baseline.json`.

| | route | all | agent |
| --- | --- | --- | --- |
| answerable questions: right sources found | 20/20 | 20/20 | 19/20 |
| of them, CRM-only questions | 8/8 | 8/8 | 8/8 |
| of them, farm-supply-only questions | 8/8 | 8/8 | 7/8 |
| of them, questions that need both | 4/4 | 4/4 | 4/4 |
| searched exactly the right knowledge bases (all questions) | 10/24 | 4/24 | 19/24 |
| searched at least the right ones | 24/24 | 24/24 | 23/24 |
| citations from the wrong knowledge base | 31/91 | 43/91 | 22/126 |
| no-corpus questions with no sources cited | 0/4 | 0/4 | 0/4 |
| model calls per question (mean) | 2 | 1 | 2.29 |
| median ms per question | 4228 | 3014 | 1388 |
| p95 ms per question | 7756 | 6865 | 3087 |
| failed requests | 0 | 0 | 0 |

How to read the rows: "right sources found" needs a relevant document from every knowledge base the question belongs to; "searched exactly the right knowledge bases" compares the set of corpora searched with the labelled set (so `all` can be exact only on the 4 questions that need both); "citations from the wrong knowledge base" counts sources shown to the model that came from a corpus the question does not belong to.

**History.** The first recording (2026-11-13) used `gemini-embedding-2`, because the free quota of the default model was spent that day. The strategies ranked the same way there: right sources found 19, 19 and 19 of 20; searched exactly the right knowledge bases 8, 4 and 20 of 24; citations from the wrong knowledge base 40 of 96, 48 of 96 and 28 of 136; no sources cited for questions that belong to neither 0, 0 and 1 of 4. The default model is slightly better for the router (it found the right sources on one more "needs both" question and narrowed on two more) and changes nothing else that matters.

## What it shows

1. **All three find the right sources about equally:** 20 of 20 for `route` and `all`, 19 of 20 for `agent`. The strategies differ in noise and cost, not in recall.
2. **The small router rarely narrows.** It chose both knowledge bases on 18 of 24 questions: on 6 of the 8 CRM-only ones, 4 of the 8 farm-supply-only ones, all 4 that need both (correct), and all 4 that belong to neither (it never answered "nowhere"). It never chose a single wrong corpus, it just did not narrow: exact on 10 of 24 questions, for one model call more than `all`.
3. **The agent is the most precise:** wrong-corpus citations 22 of 126 (17%) against 31 of 91 (34%) for `route` and 43 of 91 (47%) for `all`, at 2.29 model calls on average (2 to 5). It chooses a corpus by trying one, reading, and trying another, which a one-shot classification cannot do.
4. **The agent's one failure is its own:** on mq-16 (an early-payment discount at Stonebridge Orchards, a farm-supply document) it searched the CRM twice with the same query, never tried the other corpus, and found nothing.
5. **No strategy handled the questions that belong to neither** (0 of 4 each, with the default model too). The cause is the similarity floor, and it is a finding: against the farm-supply corpus the unrelated questions scored 0.46 ("capital of France"), 0.48 ("chocolate cake"), 0.47 ("weather in Chicago") and 0.45 ("football World Cup"), and two of them also cleared the floor against the CRM corpus (0.46 to 0.47). The floor of 0.45 was tuned on the CRM documents, where unrelated questions scored 0.42 to 0.44; **a similarity threshold belongs to a corpus (and an embedding model), and does not transfer to a second corpus.** The agent made it worse in a different way: it searched the CRM for every off-topic question, once to four times, and the answerer then saw weak sources.
6. **Latency is not a ranking.** The agent's median was lowest (1.4 s against 3.0 s for `all` and 4.2 s for `route`) because a short tool exchange with a small model can finish faster than a retrieval and an answer from four sources. One run per question on one provider's capacity: not a benchmark.

## Decision

- **No default changes in this PR.** The default stays `route` (a documented teaching default); the extension keeps all three.
- **Guidance recorded for the notes:** prefer `agent` when the model supports tool calling and the corpora are many or overlapping; prefer `all` when the model cannot call tools, corpora are few and retrieval is cheap (it never misses a corpus and needs one model call); use `route` only with a router model strong enough to narrow (it was the weakest here) or when retrieval is expensive enough that skipping a corpus pays.
- **A follow-up worth doing:** a similarity floor per knowledge base, calibrated on that corpus (relevant against unrelated questions), instead of one global 0.45. The off-topic row cannot improve without it.
- **What would change the guidance:** a stronger router model, descriptions rewritten for the router, per-corpus floors, or a router that checks the retrieved chunks before committing.

## Caveats

- **One 8B model, one run per question, 24 questions I labelled myself.** One question is 4 points of a row of 24, 12.5 points of the "both" row. No variance estimate. A stronger model would very likely route better.
- Sources shown to the model are counted as "from the wrong knowledge base" even when the answerer ignored them; this measures noise in the prompt, not wrong answers. Answer quality was not judged.
- The similarity scores in finding 5 are one live query each against the running index, not a calibration set.

## Consequences

- The comparison is reproducible without keys and can be re-recorded with another model, another embedding model or more questions.
- The default `route` strategy is knowingly the weakest with a small router; a later slice can change the default or improve the router.
- The question of a per-corpus similarity floor is now an open item with evidence.
