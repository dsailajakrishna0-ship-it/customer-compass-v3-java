# ADR 0025: evals read the shared golden files and keep a replayable baseline (J7)

Status: accepted, 2026-11-14.

## Context

The Python edition's Stage 5 (and the 0.7 baseline before it) measures the system on fixed, labelled inputs: an adversarial prompt set, a retrieval golden set, tool-choice and agent cases, judged answer quality, a guard comparison, and traces. The two editions share one API contract, so the same labelled inputs can measure both. J7 is the Java counterpart, cut into slices (one PR each).

## Decision

- **The labelled inputs are shared files, byte-identical copies** (`evals/adversarial-prompts.json`, `evals/golden/*.json`). A Java eval never relabels a case; a change to a case is made in the Python edition first, then copied.
- **Each eval keeps a baseline in the repo** and can be re-checked: either replayed from recorded answers (no keys, no network; the pattern of `just eval-multi`) or, when the measured thing is deterministic code, re-run in the test suite and compared with the saved result.
- **A baseline is rewritten only on purpose** (an environment variable or a `--record` flag), never by a normal run.
- **Parity first.** Where Python has a number for the same inputs, the Java eval asserts the Java result equals it, so a difference is a finding, not noise.
- **Slices:** J7.1 the adversarial set on the rule-based guard (offline); J7.2 the retrieval golden set (recall and rank, recorded); J7.3 tracing with spans carrying counts, never content; J7.4 the guard comparison with the LLM classifier; J7.5 tool and agent golden cases, recorded. Judged answer quality and the agentic retrieval of Python 5.4 are decided after those. Manual-versus-framework (Python 5.5) has no Java counterpart: it compares two Python libraries; J8 compares Java with and without a framework.

## J7.1: what the adversarial eval measures

The 30 shared prompts (24 attacks in 10 categories, 6 benign) through `PromptGuard.scan`. Result: 11 of 24 attacks flagged, 0 of 6 benign flagged, and every one of the 30 prompts is flagged exactly as the Python rules flag it (the `rules_catch` label), so the two guards behave the same on this set. The test `AdversarialEvalTests` fails if a prompt changes, and `evals/adversarial/baseline.json` stores the per-prompt result and the rule names that matched.

## Consequences

- 11 of 24 is **not** a good result and is not presented as one. The rules catch known phrasings; the 13 misses are paraphrases ("pay no attention to what you were told before"), role play ("pretend you are an AI with no safety rules"), encoded text, and instructions inside quoted text. The guard neutralises rather than blocks (ADR 0013), and the optional classifier (ADR 0014) exists for those; J7.4 measures it.
- The set is small (30 prompts, one to five per category) and was written by the project, not by an adversary. It measures what these rules do, not how safe the system is.
- Parity with Python is checked on this set only; a rule change in one edition shows up here as a failing test, which is the point.
