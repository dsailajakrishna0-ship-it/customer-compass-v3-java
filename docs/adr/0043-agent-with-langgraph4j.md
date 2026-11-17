# ADR 0043: a simple agent with an approval pause, with LangGraph4j (J8.5b)

Status: accepted, 2026-11-17. Second path of the J8.5 comparison (ADR 0042): the same four-step agent and the same HTTP contract as `handWritten`, with the steps as a LangGraph4j graph and the pause taken from the library.

## Context

`handWritten` keeps the pause as a stored run in a map. LangGraph4j, which the application already uses for its agent (J5, ADR 0012), has a pause of its own: a **checkpoint saver** that stores the graph's state under a thread id, and **`interruptBefore`**, which stops a run before a named node. This slice builds the same agent with them to see, with tests, what the library does on its own and what has to be added to meet the contract.

## Decision

- **A separate module** `comparisons/agent-graph/withLangGraph4j` (port 18142), the same HTTP face and the same model call as `handWritten` (`comparisons/common`: `AgentBackendServer`, `OpenRouterRecommender`), so only the orchestration differs. Not part of the application: the module depends on `langgraph4j-core` 1.9.9 (the application's version) and does not touch `apps/api-java`.
- **The graph:** `gather` (plain code, the CRM tool), `recommend` (the shared model call), `approval` (an empty node), `act` (the dry run), with a conditional edge after `approval` that reads the answer from the state (`approved`): `act` when true, the end otherwise. **The graph is compiled with a `MemorySaver` and `interruptBefore("approval")`**, as the application's approval gate is.
- **Start:** `invoke(input, config)` with the run id as the thread id runs `gather` and `recommend` and returns at the interrupt. **Resume:** `updateState(config, {approved})` puts the answer into the saved state, then `invoke(GraphInput.resume(), config)` runs `approval` and `act`, or ends.
- **One graph and one saver per run**, held in a map that keeps the most recent 100 runs. This is the bound the contract needs; see the table below for why the library cannot give it.
- **State values are strings** (the library requires them to be serializable).

## What LangGraph4j does on its own (measured: `LangGraph4jBehaviourTests`, a graph of the same shape built with the library alone)

| Question | What LangGraph4j 1.9.9 does |
| --- | --- |
| How does it stop? | `invoke` returns the state so far (company, facts, action) with **no marker that it stopped**. The only way to know, and where, is `getState(config).nextNodeId()`, which is `approval`. |
| How does a run continue? | **The answer goes into the saved state** (`updateState`), and `invoke(GraphInput.resume(), config)` continues from the checkpoint. A resume with **no answer in the state** takes the end edge and runs nothing, without any error. |
| On which thread do the nodes run? | **On the library's own platform thread** (named `CancellableDispatcher[0]`), not the caller's and not a virtual thread. |
| Can a finished thread be resumed again? | **No, but only as an `IllegalStateException`** ("Resume request without a valid checkpoint!"); `getState` of a finished thread throws "Missing Checkpoint!". |
| What does an unknown thread id do? | **The same two exceptions as a finished one**: the library cannot say which is which. |
| What does it keep for a finished thread? | The thread's checkpoints are **moved into a tag that the saver keeps**, with `releaseThread` on or off. Nothing is freed. |
| Does the saver have a bound? | **No.** 150 paused threads are 150 entries, and there is **no call that forgets one** (a finished thread leaves a tag). A stream of starts grows it for ever. |
| What does an exception in a node do? | It comes out of `invoke` **wrapped in three layers** (`CompletionException`, `GraphRunnerException`, `ExecutionException`) around the original; the thread **stays saved at the failed node** (`nextNodeId` is the node that failed). |
| What must the state hold? | **Serializable values**: a plain `Object` in the input fails the run with `NotSerializableException`. |
| Are two threads independent? | **Yes**: on one graph, answering thread A leaves thread B waiting with its own state. |

## What was added, and how

| Rule of the contract | In `withLangGraph4j` |
| --- | --- |
| Tell that the run stopped at the approval | `getState(config).nextNodeId()` is compared with `approval` after `start` |
| 404 for an unknown run, 409 for an answered one | **Added:** a map of runs and a status. The library cannot tell the two apart (same exceptions), so the map gives the 404 and the status gives the 409 with its "already completed" or "already declined" |
| Keep only the most recent 100 runs | **Added:** one compiled graph and one `MemorySaver` per run, in a map that evicts the oldest. Dropping the run drops its saver, so memory is freed; a shared saver could not forget a thread |
| A failed run must not linger | **Added:** a run is put in the map only after it reached the approval, so a run that failed at `gather` or `recommend` (which the library leaves saved at the failed node) is never kept |
| The original exception (the 404 and the 503 mapping) | **Added:** `unwrap` walks the three layers to the contract's own exceptions; anything else is "The agent could not run." |
| Serializable state | Only strings are put in the state |
| An explicit answer | The server validates `approve` as a boolean; a missing answer would silently take the end edge in the library |
| The length-checked recommendation, untrusted facts, status-only errors | **Shared:** the same `OpenRouterRecommender` as `handWritten` |

## Cost of the library

- **Dependency:** one direct artifact, `langgraph4j-core` (about 320 KB), which brings `async-generator`, `jspecify`, `slf4j-api`, `gson` and **Jackson 2** (the module's own code uses Jackson 3).
- **Size:** the agent class is **178 lines of code** against 112 for the hand-written one (same counting rule), because the graph and the contract rules the library does not give (the map, the unwrapping, the status) are written out. What the library replaced is the state machine itself: the pause and the resume are two calls.
- **Not durable here:** the saver is in memory. The application uses `langgraph4j-postgres-saver` for a pause that survives a restart; that is a library feature this slice does not use.

## Consequences

- Where a graph is the right shape (many steps, conditions, a pause in the middle) LangGraph4j gives the pause and the resume as two calls on a saved state. For four fixed steps it is more code than a state machine and one more dependency.
- The pause is only as safe as the code around it: the contract's 404, 409 and bound all came from our own map, because the library's checkpoint is not a record of runs.
- Library upgrades can change a measured behaviour: `LangGraph4jBehaviourTests` fails when they do.
- Not measured: a live model, and a durable saver.
