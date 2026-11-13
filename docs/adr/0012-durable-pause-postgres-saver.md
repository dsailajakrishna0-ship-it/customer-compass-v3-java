# ADR 0012: the approval pause is saved in Postgres with the LangGraph4j saver (J5.2)

Status: accepted, 2026-11-13.

## Context

After a completed run the agent stops and waits for a human to say yes or no. The Python edition keeps that waiting run in a Postgres checkpointer, so an API restart does not lose it (its ADR 0018). The Java edition must match, without changing the API contract: `/agent/recommend` and `/agent/confirm` have no run id.

## Decision

- **A second, small graph owns the pause** (`ApprovalGate`): `human_approval` then `write`. It is compiled with `interruptBefore("human_approval")` and the `PostgresSaver` from `langgraph4j-postgres-saver`. The main graph (ADR 0011) stays free of storage: it finishes, and its final state is handed to the gate, which saves it and stops.
- **Why two graphs:** the id a run is saved under comes from the drafted note, which does not exist until the first graph has run.
- **The id is derived, not issued.** `threadId = followup- + the first 32 hex characters of SHA-256(lower-case company | note)`, with surrounding spaces ignored. `/recommend` and `/confirm` compute the same id from what the user sees, so the response has no new field.
- **Confirm resumes, otherwise writes directly.** `/confirm` looks for a paused run under that id. Found: it records the answer with `updateState` and resumes with the returned config; the `write` node runs only when the answer is yes. Not found (API restarted without the saved run, the note was edited, another company): it writes directly, as J5.1 did. Declining writes nothing in both paths.
- **A finished run is released.** The graph is compiled with `releaseThread(true)`, so its checkpoints are removed once it reaches the end and a repeated confirm finds nothing to resume: a task is never written twice from one run. Pausing the same run twice reuses the same id, so it stays one run.
- **The write is a graph node that calls the same `CrmTools` method** as before, so the audit row is identical.

## Findings worth keeping

- The saver reports a run as waiting while its next node is `human_approval`. `updateState` returns a new config pointing at the new checkpoint; resume with that config, not the old one.
- Our first attempt looked broken ("a finished run still looks paused") only because the route was never calling the gate. A test that drives the real route found it; a test of the gate alone would not have.

## Consequences

- One more dependency (`langgraph4j-postgres-saver`); the saver creates its own tables (`createTables(true)`).
- The pause survives an API restart: a new `ApprovalGate` over the same database finds the run (`AgentPauseTests`).
- Python and Java both have: pause, restart survival, single write, direct-write fallback.
