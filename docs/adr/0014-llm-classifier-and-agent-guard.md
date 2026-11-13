# ADR 0014: the optional LLM classifier and the agent's guard (J6.2)

Status: accepted, 2026-11-13.

## Context

J6.1 guards the chat routes with rules. Two things remain from the Python edition's Stage 8: the optional LLM classifier (`GUARD_MODE`, `GUARD_MODEL`) and the guard inside the agent, which reads stored text (interactions, documents) and produces text the user will act on (a task note).

## Decision

- **`InputGuard` is the one place the user's own text is guarded.** The rules are the base. `GUARD_MODE=rules` (default) makes no model call; `llm` uses only the classifier; `both` runs both. The classifier answers SAFE or UNSAFE; anything unclear counts as SAFE; UNSAFE adds the finding `llm_classifier` with the first 120 characters as the excerpt.
- **The classifier flags and audits, it never rewrites.** It cannot say what to remove, and a model rewriting the user's message would be one more thing to trust.
- **It fails open.** If the classifier model is unreachable the request goes on with the rules alone (the rules are the floor, the classifier a bonus). The failure is logged.
- **The classifier goes through the same `ModelGateway`** as every other call (fallback chain, retries, cool-down) and is used only on the user's question, as in Python; chunks and tool results are scanned by rules only.
- **The agent guards three places.** The request (`classify`, then the company is found in the cleaned text); stored text, meaning interactions and document chunks, before any agent reads them (the CRM facts and deals come from the database through a tool and are not scanned); and the three texts the user will see (recommendation action, rationale, task note) with the output scan including the cross-customer name check.
- **The proposed note is the cleaned note.** `/recommend` returns it, the paused run is saved under it, and `/confirm` finds the run by that same text, so redaction does not break the resume.
- **A note arriving on `/confirm` is scanned again** because it comes from the browser. A detection is audited (`user_input: task note`); the direct-write fallback saves the cleaned text. A resumed run is found by the note as sent.
- **Detections are plain data in the run's state** (a list of maps), so the state can be saved; they are written to the audit log once, in `draft`, with endpoint `agent`.

## Consequences

- With `GUARD_MODE=both` every chat request and every agent run costs one more (short) model call.
- Interaction and document locations are audited with `event_type` `input` and `document` as in Python (the source is derived from the location prefix, so `interaction: ...` counts as input).
