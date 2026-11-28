# ADR 0054: the tool and agent golden cases are scored over HTTP from a recording (J7.5)

Status: accepted, 2026-11-28. Follows ADR 0025 and ADR 0026.

## Context

The Python edition keeps ten tool-call cases (`evals/golden/tool_calls.json`: a message, the tool the model should call, words the arguments should contain) and ten agent cases (`evals/golden/agent.json`: a message, the route, the status and the company expected). Both files are copied into this repository, but nothing here scored them. The question they answer is different from retrieval: not "was the right document found" but "did the model pick the right function, and did the agent take the right path".

## Decision

- **Same pattern as J7.2.** `evals/tools-agent/tool_agent_eval.py` replays `recorded.json` (no keys, no network), checks it against `baseline.json`, and with `--record` asks the running API: `POST /api/v1/chat/tools` for each tool case (a one-message conversation; the answer's `toolsUsed` is kept) and `POST /api/v1/agent/recommend` for each agent case (route, status and the company's name are kept). `--write-baseline` saves the replayed metrics on purpose.
- **Scoring.** A tool case passes the tool check when the expected tool was called at least once (a case that expects no tool passes when none was called), and the argument check when one call to it has every expected word inside the argument, ignoring case. An agent case checks the route, the status when one is expected and the company when one is expected.
- **A safety counter.** `write_without_confirm` counts `create_followup_task` calls recorded with `confirm` true in these one-message conversations. It must be 0: only the user's own later "yes" may set `confirm`. The recording writes nothing, so running it leaves the database as it was.
- **Tolerance 0.05.** With ten cases per suite one flipped case moves a rate by 0.1, so any single flip fails the check. A replay of the same recording matches exactly; the tolerance matters when a new recording (another model) is compared with the saved baseline.
- **No recording and no baseline ship in this slice.** The recording needs keys, ten tool chats and ten agent runs (15 to 60 seconds each on a free model) and the owner's approval. Until then `just eval-tools-agent-check` fails with a plain message and CI runs only the self-test, as for J7.2.

## Limits

- Twenty cases written by the project: a coarse thermometer, not proof. One model answers each case once, so a result is one sample.
- The agent cases check the path (route, status, company), not whether the recommendation is good.
- `agent-08` expects the `direct` route, which needs no model; the other agent cases need keys and ingested documents.
- The golden files are the Python edition's. Whether the Java edition's tool descriptions steer a model the same way is exactly what the recording will show.
