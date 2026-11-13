# ADR 0011: the agent graph runs on LangGraph4j (J5)

Status: accepted, 2026-11-13 (decided with the owner).

## Context

The account follow-up agent is a small workflow of three model-backed agents with code in charge of the order, the limits and the data: classify the request, gather evidence, analyse, review (with one bounded loop back to the analyst), draft a task, and then wait for a human to approve the write. J5.2 adds a durable pause at that last step. The Python edition builds this as a LangGraph graph with a Postgres checkpointer (its ADR 0018). The roadmap left the Java choice open: Spring AI's workflow patterns, LangGraph4j, or hand-written code.

## Decision

- **LangGraph4j 1.9.9** (`langgraph4j-core`; the Postgres saver follows in J5.2). It is the Java twin of the library the Python edition uses, so the two editions' stage guides compare concept by concept: nodes, edges, conditional edges, state, checkpoints, interrupts.
- **The nodes are plain methods; the graph only wires them.** Each node reads the run's state and returns the keys it changed, exactly as the Python nodes do (`classify`, `evidence`, `documents`, `analyze`, `review`, `prepare_revision`, `draft`). The shape of the agent, including the single loop, is visible in one method (`AgentWorkflow.build`).
- **State is plain data** (maps, lists, strings, numbers), so a checkpointer can save it. Anything that cannot be saved, or must survive an exception (the model call, the limits, the step budget, the trace), lives in a per-run context the nodes close over. A run stopped by a limit still reports every step it took.
- **The graph is compiled per run** because the nodes close over that run's context. Compiling is cheap.
- **Limits stay in code:** `RunBudget` counts steps and time before every step (defaults 8 steps, 180 seconds, 1 revision, the Python edition's `AGENT_*` settings). The time limit also cuts a run off from outside: the run executes on its own thread and the request waits at most the limit, then answers `timed_out`; the abandoned run stops at its next step. A model call in flight cannot be interrupted, which is why every model attempt has its own timeout too.
- **The agents are ordinary code** (`Agents`): one model call, one JSON answer, a parser with a safe fallback, the Python edition's prompts word for word. The model call is a function the route binds (role, system prompt, messages), so tests answer by role.
- **Tools are called in process**, as the Python edition does: the workflow calls the same `CrmTools` methods the MCP server serves, so the audit rows are the same.
- **Not in J5.1:** the guardrails (J6): the request and the stored text are not scanned, the output scan is absent, and `guardrail` is always unflagged. The durable pause and the resume (J5.2).

## Alternatives considered

- **Hand-written state machine in plain Java.** No dependency and the easiest code to read, but checkpointing, the pause and resume would be ours to write and test. A plain-Java version of the core flow is planned anyway (J8) as the comparison, so building it twice is redundant.
- **Spring AI's agent workflow patterns.** Useful ideas (chain, routing, orchestrator-workers) but, in the version we use, no graph engine with persisted state and resumable pauses, which is what J5.2 needs.

## Consequences

- A new dependency (small: the core library, `async-generator`, `gson`, `slf4j`). J5.2 adds the Postgres saver.
- LangGraph4j's API is its own, not a copy of Python's: its conditional edges return a key that is mapped to a node name, and its run call returns an `Optional` of the final state. The guide shows the two side by side.
- The agent's behaviour is specified by the Python edition's contract tests, ported as `AgentApiTests` (shapes, trace order, reviewer loop, limits, 404, 422, 503) against a real Postgres.
