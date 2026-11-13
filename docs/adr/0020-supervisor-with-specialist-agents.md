# ADR 0020: a supervisor with specialist agents (E3.1)

Status: accepted, 2026-11-13. A Java-edition extension: not part of the contract shared with the Python edition.

## Context

The account follow-up agent (J5, ADR 0011) is a fixed pipeline: classify, gather evidence, analyse, review, draft. Code decides the order and every model does one job. The owner asked for the next agent concept to be a **supervisor with specialist agents**: a coordinator that decides, turn by turn, which specialist works next, and takes control back after each one. The question to answer is what the pattern adds over a fixed pipeline, and what it costs.

## Decision

`POST /api/v1/agent/supervise` takes the request of `/agent/recommend` and returns its shape plus a `supervisor` object (every decision: turn, chosen specialist, who decided, the reason, what was allowed, and what the model proposed when code overrode it). A completed run waits at the **same approval gate**: `POST /agent/confirm` resumes it, and nothing is written before the user says yes.

- **A LangGraph4j graph** (the engine of ADR 0011): `START -> supervisor`, a conditional edge from the supervisor to each specialist or END, and an edge from every specialist back to the supervisor. This is the standard LangGraph supervisor topology.
- **Five specialists**, four of them the existing agent's nodes unchanged (same prompts, parsers, the reviewer's two code rules, the guard, the audit log): `crm_facts` (identify the customer, load facts, deals and interactions), `analyst`, `reviewer`, `drafter`; and a new `researcher` that searches every knowledge base (E1) for background and adds it to the evidence.
- **The supervisor is a model, so its choices are enforced in code.** Each turn code computes the list of **allowed** specialists from preconditions: nothing before `crm_facts`; the analyst needs evidence; the reviewer needs a recommendation that is not yet reviewed; the drafter needs a reviewed recommendation that was approved or is out of revisions; FINISH needs the note (or no customer found). The first allowed entry is the next step the fixed pipeline would take. The model's answer is accepted only if it is in the allowed list; an unreadable answer or a disallowed one is replaced by the pipeline's step and the turn is recorded as made by `code` with the reason.
- **No model call when there is no choice.** If exactly one specialist is allowed (most turns) the supervisor is not asked. The genuine decisions are narrow: whether to call the researcher (at most twice) before the analyst, and, after a "revise", whether to research again before the analyst's second try.
- **Limits live in code:** `SUPERVISOR_MAX_TURNS` (default 10, how often the supervisor may decide), the existing time limit, the existing revision limit (1), a research cap of two. A run that hits a limit ends with `budget_exceeded` or `timed_out` and its trace.
- **No customer found ends the run at once**, with no model call at all.
- **The supervisor's prompt is data in, JSON out:** the request, a short state summary (customer, evidence count, researcher runs, recommendation, review, note) and the allowed list; it answers `{"next": "<name>", "reason": "<one sentence>"}`.

## Alternatives considered

- **Specialists as tools of one tool-calling model** ("agents as tools"): fewer moving parts and one model loop, but the supervisor's decisions disappear into tool calls, enforcement of preconditions moves into every tool, and small models call tools unreliably (see ADR 0019's agent results).
- **Handoffs between peers** (each agent decides who goes next): no central place for rules; harder to bound.
- **Plan then execute** (the model writes a whole plan first): a different pattern, worth its own comparison; a plan has to be validated as a whole.
- **Letting the model's choice stand without allowed lists:** simplest to build, and a small model skips the reviewer or finishes early. The rules are the safety.

## Consequences

- With the allowed lists, the supervisor can only reorder two optional steps. That is deliberate for a first slice: autonomy within guardrails. E3.2 widens it (an answer team and the supervisor writing its own search words; ADR 0021); parallel specialists are still left out.
- A run costs the pipeline's model calls plus one supervisor call per genuine choice (usually one or two); `supervisor.modelDecisions` and `codeDecisions` count them.
- Every decision is in the response, so a reader can see why the researcher ran, why the supervisor's answer was overridden, or why a run stopped.
- Counting steps: the step budget covers the specialists' steps (four per allowed turn), the turn limit covers the supervisor's decisions.
