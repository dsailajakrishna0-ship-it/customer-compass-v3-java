# ADR 0049: the handoff pattern on LangGraph4j with Spring AI (J8.7e)

Status: accepted, 2026-11-17. The handoff (swarm) pattern of ADR 0045, built next to the supervisor of ADR 0046 on the same scenario, the same four pieces of work and the same limits, so that only the **organisation** differs.

## Context

In a supervisor team one coordinator decides who works next. In a **handoff** team there is no coordinator: control passes from agent to agent by an explicit, named handoff, and the agent that has just worked decides who takes over, only from the list of agents it is allowed to hand off to (the "handoffs array" of the OpenAI Agents SDK). It is the usual shape for triage and routing to specialists, and for customer-facing flows. The question of this slice: what do LangGraph4j and Spring AI give for it, what must be added, and what does it cost against the supervisor?

## Decision

- **Module** `comparisons/multi-agent/handoff/withLangGraph4jSpringAi` (port 18154, `pattern()` is `handoff`), serving the shared `MultiAgent` contract (`POST /api/v1/multi-agent/run`). The pieces that do not depend on the pattern (the Spring AI model layer, the four pieces of work and their prompts) are **copied on purpose** from the supervisor module, so each pattern folder reads on its own; the tests repeat the supervisor module's expectations for everything that is not the pattern.
- **Four agents and an explicit handoff list for each, all in code** (`HandoffRules`): `triage` hands to `researcher`; `researcher` (which loads the facts and does one deals lookup per visit) hands to `analyst`, or back to itself for a second lookup (at most two); `analyst` hands to `reviewer`; `reviewer` hands to `analyst` once for a revision, otherwise to `FINISH`. The graph has **no supervisor node**: after each agent a conditional edge covers that agent's own list.
- **Who decides.** One entry on the list: nobody is asked, code hands off. Several entries (only the researcher has two): the agent decides with a model call in which each allowed target is a **tool**, `transfer_to_analyst` and so on. The reviewer's handoff follows from its own verdict, with no further call. A handoff that is not on the list is replaced by the first entry and recorded.
- **Reading a tool call without running it** (measured below): the handoff call goes to the **ChatModel directly**, not through a ChatClient.
- **Guards:** at most 10 handoffs for the run, at most 3 visits to one agent, and a pair of agents that repeats more than 3 times is a loop; each ends the run with status `stopped` and a recorded reason. Every agent's edge map includes `FINISH`, because a limit can end the run after any agent.
- **Recorded as data** (the contract's `Decision` is unchanged): `decidedBy` is `code` or `agent:<name>`, `allowed` is that agent's list, `chosen` is the next agent, `proposed` is what the model asked for when it was overridden. `specialists` lists the agents in the order they ran, including `triage`.
- **Telemetry** as in the other patterns: a span per agent (`agent.triage`, `agent.researcher`, ...) and per handoff (`agent.handoff`, outcome `code`, `agent` or `override`), per model call and per tool, never content.
- **The routing prompts carry no CRM text**: only flags and the allowed list, so text in the data cannot steer a handoff.
- **The LangGraph4j handoff modules are not used** (see the table).

## What LangGraph4j and Spring AI give for free (measured: `HandoffBehaviourTests`)

| Question | What it does |
| --- | --- |
| A tool call through a **ChatClient** | **Spring AI runs the tool** and **asks the model again**: two requests, and the answer is the model's text. A handoff tool would cost an extra model call. |
| A tool call on the **ChatModel directly** | **One request, nothing executed.** The reply carries the tool call (name, arguments with the reason), finish reason `TOOL_CALLS` and the token usage (15 in and 6 out in the test). |
| A switch to turn Spring AI's own tool execution off | **None in 2.0.2:** `OpenAiChatOptions` has no `internalToolExecutionEnabled`; the tool loop lives in the ChatClient, which is why the ChatModel is called directly. |
| An answer in text although handoff tools were offered | **No tool call at all**: the text comes back. A handoff must be checked for, not assumed. |
| Several tool calls in one reply | **All are returned**, in order. We read the first. |
| A tool name that was never offered | **Returned as it is**: the library does not validate it against what was offered. |
| An edge key that is not in the mapping | The run **fails** ("cannot find edge mapping"); our unwrap turns it into a generic failure. A limit that ends the run after an agent whose map lacks `FINISH` hits this (found when two limit tests failed). |
| A graph in which agents hand off in a circle | Stopped by the **library's own limit of 25 iterations** (24 node runs ran), per invoke. Our limits (10 handoffs, 3 visits, a repeated pair) come first. |
| Threads, the wrapping of a node's exception, token usage and the answering model | As measured for the supervisor (ADR 0046): the nodes run on the library's own platform thread; an exception comes out in three layers; the tokens and the answering model are in Spring AI's response. |

## The LangGraph4j handoff modules, read on 2026-11-17

`langgraph4j-springai-agent-handoff` and `langgraph4j-lc4j-agent-handoff` exist on Maven Central only as **1.5.9 to 1.5.12**, last published **2025-05-15**, while the core is 1.9.9 and the agent executor 1.9.9. Their poms import Spring Boot **3.3.4** and depend on the agent executor **at 1.5.12**. The Spring AI one holds `AgentHandoff`, `AbstractAgent` and `AbstractAgentExecutor`. **By name**, all 11 Spring AI classes it references still exist in Spring AI 2.0.2; **that is all that was checked: it was not run** with the 1.9.9 core or Spring AI 2.0.2, and a behaviour change would not show in a name. **Not used**: the handoff here is the graph's own conditional edges plus tool calls read from the ChatModel, in 516 lines of main code for the whole module (the model layer, the four pieces of work and the main class included).

## What was added, and how

| Rule | In the handoff team |
| --- | --- |
| A handoff list per agent | `HandoffRules.targets(agent, state)`: code, never the model |
| The model may only choose from the list | The offered tools are exactly the list; the answer is checked against it; an answer not on it (text, another agent, a strange name) is replaced by the first entry and recorded with a safe word (`no_handoff`, `unreadable` or the plain name) |
| No model call when there is no choice | One entry means code hands off |
| Reading a handoff without running it | The ChatModel is called directly (above) |
| Limits | 10 handoffs, 3 visits per agent, a repeated pair is a loop; `FINISH` in every edge map |
| The routing prompts carry no CRM text | Only flags and the allowed list |
| 404, status-only errors, no key means no call, never-content telemetry | Added by us, as in the supervisor |

## Cost against the supervisor (measured: model calls for a scripted run)

| Scripted run | Supervisor | Handoff |
| --- | --- | --- |
| A second deals lookup, then the analyst | **4** model calls (two routing, the analyst, the reviewer), 5 decisions | **3** model calls (one handoff, the analyst, the reviewer), 5 decisions |
| The analyst straight after the first lookup | 3 model calls | 3 model calls |

The handoff team asks **fewer routing questions** because one agent, the researcher, does both the facts and the lookup in a visit and the other agents have only one handoff; the supervisor asks at each turn in which it has a choice. This is the counts of one scenario, not a general result, and **nothing was measured against a live model**.

## When handoff is the right shape, and when it is not

- **Right:** triage and routing to specialists, customer-facing flows in which the next owner of the conversation is a natural decision, and teams in which each agent knows its neighbours. The routing is **spread over the agents but auditable**: every agent's list is code, and every handoff is recorded.
- **Not right:** work that needs a **single place to enforce a rule across the whole run** (a global precondition, a budget, a rule such as "never draft before a review"): in a supervisor that is one allowed-list function, here it is the union of the agents' lists and the limits. The reviewer's one-time revision and the researcher's two lookups are rules about the run that no single agent owns, which is why the counters live in the shared state.
- Compared with the **network** pattern (any agent may call any other): handoff **restricts** each agent to a list, so a run can be audited and a loop is bounded by construction.

## Consequences

- LangGraph4j gives the graph, the conditional edges and an outer iteration limit; Spring AI gives the model calls and, on the ChatModel, tool calls that can be read without being run. The lists, the checks, the limits and the records are ours.
- Not measured: a live model (whether a model reliably chooses a handoff by tool call, how often it answers in text), real latencies and cost in money.
