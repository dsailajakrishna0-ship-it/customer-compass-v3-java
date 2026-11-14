# ADR 0022: the supervisor against the fixed pipeline (E3.3 results)

Status: accepted, 2026-11-14. A Java-edition extension. Measures ADR 0020 and 0021 against the follow-up agent of ADR 0011; changes no behaviour.

## Context

`POST /agent/recommend` runs a fixed pipeline (classify, evidence, documents, analyse, review, draft). `POST /agent/supervise` runs the same specialists under a supervisor that decides, turn by turn, what happens next, with its choices enforced in code (E3.1), and a researcher that searches every knowledge base. The owner's question is what the extra flexibility buys and what it costs. E3.3 runs both on the same cases.

## Result

Cases: V3's ten golden agent cases (seven accounts to analyse, one plain question, two with no customer), each run twice per arm, so 20 runs per arm. Chat model for every agent and the supervisor: `meta-llama/llama-3.1-8b-instruct` (paid). Embeddings: `gemini-embedding-001` (the default). `just eval-supervisor` replays the table from `evals/supervisor/recorded.json` with no keys; `just eval-supervisor-check` compares it with `baseline.json`.

| | pipeline | supervisor |
| --- | --- | --- |
| runs as expected (status and company) | 20/20 | 20/20 |
| analyses completed | 14 | 14 |
| completed with valid citations | 14/14 | 14/14 |
| completed and approved by the reviewer | 11/14 | 13/14 |
| evidence items per completed run | 7.3 | 7.4 |
| farm-supply items shown to the analyst (completed) | 0 | 24 |
| model calls per completed run | 3.57 | 5.14 |
| model calls in total | 50 | 76 |
| tasks written before a confirm (must be 0) | 0 | 0 |
| runs flagged by the guard | 0 | 0 |
| median ms per run | 6725 | 9057 |
| p95 ms per run | 14812 | 18727 |
| failed requests | 0 | 0 |

Supervisor: 5.5 turns per run, 26 model decisions in total, 0 overridden or unreadable, 11 runs used the researcher, 17 own search queries

How to read it: the pipeline answers the plain question with its "direct" redirect and the supervisor with its answer team, so "as expected" compares each arm with its own expected status. "Farm-supply items" counts knowledge-base chunks from the second corpus that reached the analyst. "Tasks written" is checked against the task table around every run.

## What it shows

1. **On these cases the supervisor matched the pipeline on quality and cost more.** Every run ended as expected in both arms (20 of 20), all 14 analyses cited only evidence that exists (14 of 14 in both), nothing was written before a confirm, and the guard flagged nothing. The reviewer approved 11 of 14 pipeline analyses and 13 of 14 supervisor analyses; with 14 runs and a model that is not deterministic that two-run gap is within noise, not a finding.
2. **The price of flexibility:** 5.14 model calls per completed run against 3.57 (about 44% more) and a median of 9.1 s against 6.7 s (about 35% more; p95 18.7 s against 14.8 s). The extra calls are the supervisor's decisions.
3. **The supervisor behaved:** 26 model decisions, every one readable and allowed (no override, no fallback), even from an 8B model, where E1.3's router often failed to narrow. The difference is the question: here the model picks from one or two allowed names, and code makes every other decision.
4. **It used its freedom:** the researcher ran in 11 of the 20 runs (twice in the runs that ran it more than once), and each time the supervisor wrote its own search words (17 queries). On these CRM-only tasks that put **24 farm-supply chunks** in front of the analyst across the completed runs. They did not break the citation check, but they are noise: whether the analyst cited any of them was not measured.
5. **The rules never had to act**, which proves nothing about the day they must; the unit tests cover the overrides.

## Decision

- **No default changes.** `/agent/recommend` stays the fixed pipeline; the supervisor stays an extension.
- **Guidance for the notes:** use the fixed pipeline when the workflow has one shape (it is cheaper and faster for the same result here); reach for a supervisor when the shape varies, when the right evidence may live in several corpora, or when the set of specialists will grow, and accept about a third more latency and 40% more model calls for it. Put rails around it either way.
- **What would make it earn its cost:** cases the pipeline cannot do, such as follow-ups whose evidence spans both corpora, or requests that need a different specialist order. The ten golden cases are all ones the pipeline already handles, so this comparison measures the cost of flexibility, not its value.

## Caveats

- **One 8B model, 20 runs per arm, ten cases written for the pipeline.** No variance estimate; the repeat of each case only shows that it is not deterministic.
- **The cases do not exercise the supervisor's strengths.** All seven analysis cases are CRM-only; a cross-corpus set would be the fair test of the researcher.
- **Latency is one machine and one provider's capacity**, recorded once; not a benchmark. Token counts and cost were not recorded; the calls are short and the model is priced at a few cents per million tokens, so the cost is a fraction of a cent, which is an estimate, not a measurement.
- **Answer quality was not judged:** the table counts citation validity, the reviewer's verdict, calls and time.

## Consequences

- The comparison is replayable without keys and can be re-recorded with another model, more cases or cross-corpus cases.
- ADR 0020 and 0021 stand: the supervisor's value is in flexibility, which this case set does not test, and its cost is measured.
