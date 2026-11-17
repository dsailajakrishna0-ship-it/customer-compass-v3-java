# ADR 0047: the hierarchical pattern on LangGraph4j with Spring AI (J8.7c)

Status: accepted, 2026-11-17. Second of the four patterns of J8.7 (ADR 0045), after the supervisor (ADR 0046). The scenario, the four specialists, the Spring AI model layer and the shared contract are the supervisor's; only the **organisation** differs.

## Context

A supervisor decides who works next. A **hierarchy** puts supervisors under a supervisor: a top supervisor chooses between **teams**, and each team has a sub-supervisor of its own that chooses among the team's specialists. The reasons to do it are scale (a coordinator that sees every specialist gets a long prompt) and boundaries (a team can be built, tested and replaced on its own). This slice builds the smallest honest hierarchy of the account review to see what the extra level costs and what LangGraph4j gives for it.

## Decision

- **Folder:** `comparisons/multi-agent/hierarchical/withLangGraph4jSpringAi` (port 18152), the same contract as the other patterns (`POST /api/v1/multi-agent/run`); `pattern()` answers `hierarchical`. The code is copied from the supervisor module on purpose (the model layer and the four specialists), so each pattern folder reads on its own and the specialists cannot drift: the tests repeat the supervisor's expectations for everything that does not depend on the pattern.
- **Two teams, each a LangGraph4j child graph with its own sub-supervisor.** `research_team`: the specialists `crm_facts` and `deals`; its sub-supervisor offers `FINISH` (hand control back to the top) or `deals` (at most 2 lookups in all). `answer_team`: the specialists `analyst` and `reviewer`; its sub-supervisor runs the analyst, then the reviewer, and allows one revision, so it **never has a choice and never calls the model**.
- **The top supervisor** chooses among `research_team`, `answer_team` and `FINISH`. Code builds the allowed list: `research_team` first; after the research team, `answer_team` (the fallback) and, if fewer than 2 lookups were made and fewer than 2 research rounds ran, `research_team` once more (the top may send the work back); after the answer team, `FINISH`; company not found, `FINISH`. This gives the top level one real choice in the happy path.
- **The same rules at every level:** the model chooses only from the allowed list; **no model call when exactly one entry is allowed** (`decidedBy: code`); an unreadable or disallowed answer is replaced by the first allowed entry and recorded with what the model proposed (a plain word or `unreadable`: model text is never echoed); **every supervisor prompt carries flags and the allowed list, never CRM text**, at all three levels.
- **Limits at every level:** each team has a turn limit of its own (5; a team that reaches it is ended by code and the run is marked `stopped`), the top has one (6), and a global limit of 12 specialist runs is the last net. The library's own limit of 25 iterations per graph stays as an outer safety net.
- **What crosses a team boundary is the graph's state, a typed bundle:** `facts`, `deals`, `company_name`, `deals_lookups`, `action`, `rationale`, `reviewed`, `approved`, `review_reason`, `revisions` and the level flags, all plain strings, numbers and booleans (`Rails`). The record of decisions and the observer live in a per-run closure. The child graph is added with `addNode(id, CompiledGraph)`, built per run so it can use the closure. The other two forms measured the same way (`addNode(id, StateGraph)` compiles with the parent).
- **One "who is next" key per level** (`top_next`, `research_next`, `answer_next`): a child's final state is merged into the parent, and on a collision the child's value wins, so a shared key would let the child overwrite the parent's choice.
- **Decisions are data, with the level in `chosen`:** the contract's `Decision` is unchanged, a top-level decision reads `answer_team`, a nested one `research_team>deals`. `turns` counts supervisor turns at all levels. Telemetry: a step per decision at every level (`agent.supervisor`, `agent.research_supervisor`, `agent.answer_supervisor`), per specialist, `llm.generate` per model call, `tool.*` per CRM tool; never content.

## What LangGraph4j and Spring AI do on their own (measured: `HierarchicalBehaviourTests`)

| Question | What they do |
| --- | --- |
| What does a child graph receive? | **The parent's whole state** (all its keys) as the child's input. |
| What comes back? | **The child's final state is merged into the parent's**: its new keys appear, and on a collision **the child's value wins** (the next parent node already sees it). |
| Compiled child or state graph? | `addNode(id, CompiledGraph)` (used here, built per run) and `addNode(id, StateGraph)` (compiled with the parent) both share the parent's state type and merge the same way. The compiled form lets a test run a team on its own. |
| The library's iteration limit | **Per graph, per invoke.** A runaway child is stopped at **25** (24 child nodes ran) with "Maximum number of iterations (25) reached!" and the parent had run **one** node. |
| Does the parent count a child's nodes? | **No: a child team is one node of the parent.** A parent that loops p, team, p, team ... was stopped at 25 after 12 runs of each (24 parent-level runs), although the teams had run 24 nodes of their own. |
| A node's exception inside a child | **The same layers as a direct one** (`CompletionException`, `GraphRunnerException`, `ExecutionException`) around the original; our unwrap finds it. (A runaway child's limit error arrives with more layers.) |
| Which thread | **The library's own platform threads** (`CancellableDispatcher`), for parent and child nodes alike: not the caller's, not virtual. |
| Two nested runs at once | Independent: each run builds its own graphs and context. |
| Spring AI | As measured in ADR 0046: the response carries the tokens and the answering model, and an HTTP error message holds the service's own reply, so `SpringAiLlm` reduces it to a status. |

## What was added by us

| Rule | Where |
| --- | --- |
| The allowed list at each of the three levels | `Rails` (the library has no notion of an allowed choice) |
| No model call when there is one option; override and record an unreadable or disallowed answer | `HierarchicalTeam.supervise`, one method for all three levels |
| No CRM text in any supervisor prompt | `question(...)`: flags and the allowed list only |
| The turn limits of each team, the top and the run | `limitReached`; the library only has its own 25 per graph |
| One "who is next" key per level | The key names in `Rails` (the merge rule of child states, above) |
| The level in the decision log | `label(level, chosen)` |
| 404 for an unknown company, status-only errors, no key means no call | `unwrap` and `SpringAiLlm`, as in the supervisor |
| Never-content telemetry | The same `ChatObserver` calls |

## What the hierarchy costs (measured against the supervisor, the same scripted happy path)

| | Supervisor (ADR 0046) | Hierarchical |
| --- | --- | --- |
| Decisions (`turns`) | 5 | **9** (top 3, research team 3, answer team 3) |
| Model calls in all | 4 | **5** |
| Of which for routing | 2 | **3** (research team 2, top 1, answer team 0) |
| Specialists that ran, and the recommendation | the same four, in the same order | the same four, in the same order |
| Graphs | 1 | **3** (the top and two child graphs) |

One more level means **four more decisions and one more model call** for the same review. Three of the four extra decisions are made by code, because the second level of the hierarchy is a fixed order here (the answer team never has a choice). **The hierarchy pays off when each level has real choices or when teams are built and owned separately; for a fixed phase order it is a pipeline with more bookkeeping.**

## Consequences

- The top supervisor's prompt does not grow with the number of specialists: it chooses between two teams. The cost is the extra level of decisions and one more place for a bad answer to be overridden.
- Information crosses a boundary only as the state's bundle. Nothing is lost in this scenario because the bundle carries the facts, the deals and the review whole; a real system that summarised at the boundary would lose information there, which this comparison does not model.
- A team limit ends the team, not the run: the run continues to the next level and is marked `stopped`.
- Not measured: a live model, real latencies and the cost in money.
