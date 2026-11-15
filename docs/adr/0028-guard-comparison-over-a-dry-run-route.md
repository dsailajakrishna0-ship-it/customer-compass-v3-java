# ADR 0028: the guard comparison measures the app's own guard through a dry-run route (J7.4)

Status: accepted, 2026-11-14. Java twin of the Python edition's Stage 5.3 comparison, limited to the strategies the Java edition has (rules, the LLM classifier, both).

## Context

The Python edition compares guard strategies on 74 prompts (the 30 adversarial prompts plus 44 added in Stage 5.3) and keeps a baseline: rules 11 of 44 attacks with 4 of 30 benign flagged, classifier 25 of 44 with 3, rules plus classifier 29 of 44 with 5 (`evals/baseline/guards-v1.json` there). The Java edition has the same rules and the same classifier prompt (J6), but the rules run in the JVM and the classifier goes through `ModelGateway`, so they can only be measured by running the Java code.

## Decision

1. **A dry-run route, off by default:** `POST /api/v1/guard/check` with `{"text": ...}` returns the rules' verdict and the classifier's verdict for one text, with no rewriting, no audit-log row and no text echoed back. It needs `GUARD_CHECK_ENABLED=true` (404 otherwise), because each call can cost a model call. It is Java-only and not part of the shared contract. It always asks the classifier, whatever `GUARD_MODE` is, so one call gives both verdicts and three strategies (rules, classifier, both) are scored from 74 calls, not 222.
2. **An unreachable model is not SAFE.** The in-app classifier fails open (the request goes on with the rules alone); that is right for a chat and wrong for a measurement. The route reports `unavailable` with the failure's class name; the harness retries such prompts, counts them separately, and refuses to write a baseline while any remain.
3. **Which model answered is recorded.** The gateway falls back to other models when the first fails; a verdict from a fallback model is a different experiment. The route returns the answering model and whether it was a fallback; a baseline needs one answering model and no fallbacks.
4. **An unclear reply is SAFE, as in the app** (`parseVerdict`), but the reply's first word is recorded so the table can show how often it happened.
5. **The same replay pattern as J7.2:** `--record` is resumable, `recorded.json` is the evidence, replay and `--check` need no keys, and `--write-baseline` is deliberate.
6. **The 74 prompts are the Python edition's files:** `evals/adversarial-prompts.json` and the `prompts` of `evals/golden/guards-extra.json` (copied byte for byte). Its 32 PII texts are not used: the Java edition has no PII guard, so there is nothing to measure.

## Measured now (no key)

`GuardCheckTests.theJavaRulesMatchThePythonBaselineOnAll74Prompts`: the Java rules detect 11 of the 44 attacks and flag benign-08, benign-09, benign-18 and benign-21, the same counts and ids as the Python baseline. The classifier numbers need a recording (see the J7 guide); until one is committed no Java classifier figure is claimed. The Python edition's classifier figures (25 of 44, 3 false positives, on `meta-llama/llama-3.1-8b-instruct`) are a reference only: same prompt and parser, but a different code path and a single run.

## Consequences

- A rate-limited free model gives many `unavailable` rows; the harness tells you to rerun. The Python edition's notes say the free models allow about 50 requests a day, so a paid small model (the Python pin is `meta-llama/llama-3.1-8b-instruct`) is the practical choice.
- The route is one more thing that can spend a key's money if switched on and exposed; that is why it is off and documented as a measurement tool.
