# ADR 0044: the agent with LangChain4j's agentic module (J8.5c)

Status: accepted, 2026-11-17. Third path of the J8.5 comparison (ADR 0042): the same four-step agent with an approval pause and the same HTTP contract, built with LangChain4j's **agentic** module. Port 18143, folder `agent-graph/withLangchain4jAgentic`.

## Context

`handWritten` (J8.5a) writes the steps as plain methods and keeps the pause as a run in a map. LangChain4j has an agentic module (`langchain4j-agentic`) with `@Agent` interfaces, workflow builders (sequence, loop, conditional, parallel, supervisor) and a **human-in-the-loop** agent. The question this slice answers: what does the module give for free, and above all **can its human-in-the-loop pause across two HTTP requests without holding a thread?**

## Decision

- **A beta, added through the BOM.** `langchain4j-agentic` is published only as `1.23.0-beta33`; the stable `langchain4j-bom` 1.23.0 maps it, so the module depends on it with no explicit version. Its only dependency is `langchain4j` (which this approach already needs); the jar is about 356 KB.
- **One sequence of five agents over one shared scope:** `gather` (plain code), `recommend` (the AI agent: an `@Agent` interface with the shared system prompt and the CRM facts wrapped as untrusted data), `validate` (plain code: the shared length-checked parse), `approval` (the human-in-the-loop agent) and `act` (plain code, a dry run, with an **activation condition** so a decline skips it).
- **The pause is a suspension, not a parked thread.** The approval agent's response provider returns a `SuspendedResponse` ("approval"): the library stops the sequence, **releases the calling thread** and returns a suspended `ResultWithAgenticScope` that carries a resume callback. `resume` calls `completePendingResponse(approve)` on it; the library re-invokes the sequence from where it stopped. Measured: nothing runs on any thread between the two HTTP calls.
- **The run store is ours** (the contract keeps the most recent 100 runs): the suspended result is kept in a bounded map, and a run that is finished, evicted or failed has its scope evicted from the library's registry.
- **Not durable:** the resume callback lives in this process. The module has an `AgenticScopeStore` for persisting scopes across restarts (`ResultWithAgenticScope` says that after a restart one completes the pending response on the scope and re-invokes the agent method with the same memory id); that is not used here.
- **One model per run:** the agentic system is built per run with the model the client picked.

## What the agentic module gives for free (measured: `LangChain4jAgenticBehaviourTests` and `LangChain4jAgenticAgentTests`)

| Question | What LangChain4j agentic 1.23.0-beta33 does |
| --- | --- |
| Steps and order | A sequence of agents runs in order over one shared **agentic scope** (named state: `facts`, `draft`, `recommendation`, ...). |
| The pause: a `SuspendedResponse` from the human-in-the-loop provider | **Releases the thread** and returns a result with `suspended() == true` and the pending response id (`approval`). The steps before it ran; the one after did not. |
| Resuming | `completePendingResponse(value)` on that result resumes **from where it stopped**: earlier steps do not run again, the provider's answer comes from the scope. The resume runs on the thread that calls it. |
| A second completion of the same suspended result | Throws an `IllegalStateException`. |
| The blocking alternative | A provider that waits on a future (`future.join()`) **parks the calling thread** until the future completes (measured: the virtual thread stays in a waiting state). |
| Skipping a step | An **activation condition** on a plain-code agent skips it (a declined run does not run `act`); the run still completes. |
| An exception in a plain-code agent | Comes out wrapped: `AgentInvocationException` > `InvocationTargetException` > `RuntimeException` > the original. The original is found by walking the causes. |
| Two runs of one system | Independent scopes, by memory id. |
| What the library keeps | **Every scope stays in an in-memory registry**, a finished run too, until it is evicted by memory id. |
| Agent interfaces | **Must be public:** the library calls the method by reflection and fails with `IllegalAccessException` on a package-private interface. |
| The model's request | Sent pretty-printed JSON; the model call is the library's (a chat model), not the shared recommender. |

## What was added

| Contract rule | How |
| --- | --- |
| Four fixed steps in the answer, with their statuses | `outcome(run)` builds them from the run's status; the library has no notion of "the four steps we report". |
| A bounded run store (the most recent 100) | A map that evicts the oldest and **evicts its scope from the library's registry** (otherwise the registry grows with every run). |
| 404 for an unknown run, 409 for a run already answered | Checked before `completePendingResponse`: the library's own answer to a second completion is an `IllegalStateException`. |
| No key means the model is never called | **Checked first**, as a precaution: LangChain4j's Gemini embedding model was measured to call the service even with a blank key (J8.2c, J8.3c); that was **not measured for this chat model**, so the check does not depend on the answer. |
| Errors by status only, never the service's reply | `translate`: finds our own exceptions in the library's wrapping, reduces model failures to "answered HTTP n" or "not reachable", and anything else to a fixed sentence. |
| The model's text confined to a message, length-checked | The shared `OpenRouterRecommender.fromContent` in a plain-code `validate` agent; **two members of that shared class were made public** for this. |
| The company is not found / the CRM is down before the model is asked | The `gather` agent throws our exceptions; the sequence stops before `recommend`. |

## Size and shape

About 130 lines of main code for the hand-written agent against about 260 here (the agent class and its main). The sequence reads well, five agents named in order, but the code around it (the run store, the eviction, the error translation, the checks) is the same code the hand-written path needs, and the agent definitions add their own.

## Consequences

- **The human-in-the-loop can pause across requests without holding a thread,** which is what the contract needs. The pause is a suspended result kept in a map, as in the hand-written agent; the library adds the checkpoint, the resume callback and the skip condition.
- It is a **beta**, and some of what it needs (public interfaces, the registry that only grows) was found by running it.
- Not measured: a live model, a durable pause across a restart (the scope store), and the supervisor and loop workflows.
