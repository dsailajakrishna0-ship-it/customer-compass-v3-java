# ADR 0046: the supervisor pattern on LangGraph4j with Spring AI (J8.7b)

Status: accepted, 2026-11-17. First of the four patterns of J8.7 (ADR 0045); the hierarchical, network and handoff patterns reuse the shared contract and scenario built here.

## Context

A supervisor is a model that decides which specialist works next, inside rules that code enforces. The application already has one on LangGraph4j (ADR 0020, stage E3). This slice rebuilds the idea in the comparison lab, small and measurable, as the baseline the other patterns are compared with, and builds the shared scenario and contract that all four patterns use.

## Decision

- **Folder:** `comparisons/multi-agent/supervisor/withLangGraph4jSpringAi` (port 18151). The name says the concept (multi-agent), the pattern (supervisor) and the framework (LangGraph4j for the graph, Spring AI for the model calls).
- **The shared contract** lives in `comparisons/common` for all four patterns: `MultiAgent` (`run(company, model)` returns an outcome with the specialists that ran, **every routing decision as data** and the recommendation), `MultiAgentBackendServer` (`POST /api/v1/multi-agent/run`, `GET /health`, `GET /api/v1/observability/recent` when observation is on), and a `step` hook on `ChatObserver` so a span per specialist and per decision can be recorded (`agent.<name>`, fixed names only, never content).
- **The scenario** (the same in every pattern): the account review. Specialists: `crm_facts` and `deals` (plain code, the two CRM tools), `analyst` (a model: one recommended action, the shared prompt and length-checked parse of `OpenRouterRecommender`) and `reviewer` (a model: approve or revise, with a reason).
- **The rails are code** (`Rails`): each turn the allowed list is built from the state, and the model may only choose from it. Facts not loaded: `crm_facts`. Facts loaded, no recommendation: `deals` (at most 2 lookups) and `analyst`. Not reviewed: `reviewer`. Approved, or one revision used: `FINISH`. Revise with a revision left: `analyst` and `deals`. A company that is not found ends the run (404). **Hard limit: 10 turns** (configurable), then status `stopped`.
- **No model call when there is no choice:** with one allowed entry the code decides (`decidedBy: code`). An unreadable or disallowed answer is replaced by the first allowed entry and recorded with what the model proposed (a plain word, or `unreadable`: model text is never echoed).
- **The supervisor sees no CRM text:** its prompt holds only flags (facts loaded, deals lookups, has a recommendation, reviewed, verdict, revisions) and the allowed list, so text in the data cannot steer the routing. The analyst and the reviewer see the facts, wrapped as untrusted data.
- **An unreadable review counts as `revise`** (it fails closed) and the run goes on; one revision is allowed.
- **The run's state is a plain object kept in a closure, built per run**, and the graph's own state carries only the supervisor's choice, so nothing the graph holds needs to be serializable. A graph is built per run (cheap).
- **Spring AI is the model layer** (`ChatClient` over `OpenAiChatModel`, built on first use, one attempt, no key means no call). **`langgraph4j-spring-ai` is not used:** it adds streaming, tool-execution helpers and message serializers, none of which this scenario needs; Spring AI's own client is enough.
- **Observation** is the J8.4 sink: `modelCall` for every model call (tokens from Spring AI's usage), `toolCall` for the two CRM tools, `step` for every specialist and decision, `fail` on a failure. Off by default (`TELEMETRY_ENABLED`).

## What LangGraph4j and Spring AI do on their own (measured: `SupervisorBehaviourTests`)

| Question | What they do |
| --- | --- |
| LangGraph4j: a graph that loops for ever | **The library stops it at its own limit of 25 iterations** with an `IllegalStateException` ("Maximum number of iterations (25) reached!"); the limit is **per invoke** and counts node runs (24 ran before the 25th was refused). |
| Our turn limit against it | Ours is the rule (10 turns, at most 21 node runs); the library's is an outer safety net |
| LangGraph4j: a conditional edge whose key is not in the mapping | **A `GraphRunnerException`** ("cannot find edge mapping ..."), which fails the whole run |
| LangGraph4j: a node's exception | Comes out wrapped in **three layers** (`CompletionException`, `GraphRunnerException`, `ExecutionException`) around the original |
| LangGraph4j: which thread | **The library's own platform thread** (a `CancellableDispatcher`), not the caller's and not a virtual thread |
| LangGraph4j: two runs at once | Independent (each run has its own graph and closure) |
| Spring AI: a response | Carries **the token counts** and **the model that answered** (from the reply, not the one asked for) |
| Spring AI: an HTTP error | An exception **whose message holds the service's own reply** (the stub's body came through) |
| Spring AI: a model that is not there | An `OpenAIIoException` around a `ConnectException` |
| `langgraph4j-opentelemetry` 1.9.9 | Per its README it wraps node and edge execution in spans through hooks and ships a local OTLP collector; **not added** (our J8.4 sink records the agent-level signals; this module would give node and edge spans in addition) |

## What was added, and how

| Rule | How |
| --- | --- |
| The allowed list per turn, the first-allowed fallback | `Rails.allowed` (code); the supervisor node replaces an unreadable or disallowed answer and records `proposed` |
| No model call when only one is allowed | The supervisor node decides in code |
| The 10-turn limit and status `stopped` | The supervisor node, counting decisions |
| Deals capped at 2, one revision | `Rails` |
| The supervisor never sees CRM text | `supervisorQuestion` builds the prompt from flags only (a test checks the request) |
| 404 for a company that is not found | The `crm_facts` node sets a flag; the run ends and `run()` throws `CompanyNotFoundException` |
| The library's exception layers | `unwrap` finds our own exceptions; anything else becomes "The agents could not run." |
| Errors by status only, no key means no call | `SpringAiLlm.translate` and the key check |
| Never content in the signals | The observer is only given fixed names, counts and outcome words; a test checks every span name, attribute and metric attribute |

## Consequences

- The graph is short because the rules live in `Rails` and the supervisor node, which a hand-written loop would need as well: the library gives the routing mechanism (nodes, edges, an outer iteration limit) and **none of the rules**.
- The recorded decisions (who chose, what was allowed, what was overridden) are what makes a supervisor auditable; they are part of the contract so every pattern can be compared on them.
- Not measured: a live model, real latencies, and cost.
