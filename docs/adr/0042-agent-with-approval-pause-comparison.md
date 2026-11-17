# ADR 0042: a simple agent with an approval pause, built several ways (J8.5)

Status: accepted, 2026-11-17. J8.5a is the shared contract and the `handWritten` path; `withLangGraph4j` (J8.5b) and `withLangchain4jAgentic` (J8.5c) follow. Spring AI has no graph or pause feature (its documentation describes agent workflows as patterns you write with `ChatClient`), so it has no folder here; the write-up says so.

## Context

An **agent** is a program in which a model decides, or helps decide, what happens next, in steps. What makes an agent safe to use is the rules around the steps: a fixed set of steps, a limit on what the model's text can do, and a **pause for a person's approval** before anything with side effects. The application has one (J5: a LangGraph4j graph with a durable approval pause). J8.5 asks the same question as the other J8 stages: what does each approach give for free, and what has to be added?

## Decision

- **The agent is deliberately small:** four steps. `gather` (the CRM tool `get_company_summary`, plain code), `recommend` (one model call: a JSON recommendation of one next action and why), `approval` (the pause: nothing runs until a person answers), `act` (a **dry run**: it says what it would have done; nothing is written anywhere). The CRM backends of this comparison are read-only, so the act step cannot write, and a test checks the CRM saw only reads.
- **One HTTP face for every approach** (`comparisons/common`: `Agent`, `AgentBackendServer`): `POST /api/v1/agent/start` with `{"company": "Acme", "model": "optional"}` runs the agent to the pause and answers with the run (`runId`, `status`, the four `steps`, the `recommendation`, a `message`); `POST /api/v1/agent/resume` with `{"runId": "...", "approve": true}` continues it; `GET /health`. Statuses: `awaiting_approval`, `completed`, `declined`. Step statuses: `done`, `waiting`, `pending`, `skipped`, `declined`. Errors: 422 invalid body, 404 no such company or no such run, 409 the run is not waiting (already answered), 503 the model or CRM is unavailable. Ports: 18141 `handWritten`, 18142 `withLangGraph4j`, 18143 `withLangchain4jAgentic`.
- **The model call is shared** by the approaches that orchestrate the agent themselves (`OpenRouterRecommender` in `common`: one chat-completions request, no tools), so what differs between them is the orchestration: the steps, the pause and the resume. The CRM facts are handed to the model as **untrusted data** (wrapped, with the system prompt saying so), the model's JSON is parsed and **length-checked** (action at most 200 characters, rationale at most 400) before anything uses it, and a failure is reported by status only, never by the service's own reply.
- **The model's text can only ever become a message.** The act step is a dry run that echoes the validated action; there is no tool the model's output can reach.
- **A pause is a stored run.** What an approach must keep between `start` and `resume` is the run: its id, the company and the recommendation. Only the most recent 100 runs are kept, so a stream of starts cannot fill memory; an older run is simply unknown on resume. **Not durable:** a restart forgets every run (the application's agent keeps its pause in the database, J5.2); a durable pause is not part of this comparison.
- **Left out on purpose:** several models or agents (the supervisor is J8.7), loops (revise after review), the application's guard and audit, and a real write.

## What the hand-written approach does (J8.5a)

`HandWrittenAgent`: four steps as plain methods; the pause is a run object put in a bounded map with the status `awaiting_approval`; `resume` finds it, checks that it is still waiting, and runs `act` or skips it. About 150 lines, no library besides Jackson.

| Concern | How the hand-written agent handles it |
| --- | --- |
| The steps and their order | Straight-line code in `start` and `resume` |
| The pause | `start` returns after storing the run; there is no waiting thread |
| Resuming | Look the run up by id; refuse an unknown id (404) and a run that is not waiting (409) |
| Two answers to one run | The run is locked while its status is checked and changed, so the second answer is refused |
| Memory | A `LinkedHashMap` that evicts the oldest run beyond 100 |
| A bad model answer | Parsed and length-checked before use; otherwise 503 "did not return a usable recommendation" |

## Consequences

- The hand-written agent is very small because there is no framework to configure: the pause is a status and a map. The costs: the state machine, the run store and the resume rules are ours to get right (a test covers each).
- The comparison is only meaningful if J8.5b and J8.5c answer the same requests the same way, so they run against the same expectations (the same steps, statuses and errors).
- Not measured: a live model and a durable pause.
