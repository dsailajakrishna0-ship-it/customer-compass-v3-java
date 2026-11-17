# Stage J8.5: a simple agent with an approval pause, built several ways

Plan: [`docs/roadmap.md`](../roadmap.md) ("Stage J8"). Decisions: [ADR 0042](../adr/0042-agent-with-approval-pause-comparison.md) (the contract). Code: [`comparisons/agent-graph/`](../../comparisons/README.md). One section per approach, added as each is built: **J8.5a `handWritten`** (here), J8.5b `withLangGraph4j`, J8.5c `withLangchain4jAgentic`, and J8.5d the three side by side. Spring AI has no graph or pause feature, so it has no folder here.

## In one minute

A chatbot answers. An **agent** *does* things in steps, and what makes it safe is a **pause**: before anything with side effects it stops and asks a person. Think of a junior clerk who prepares a refund, puts it in your tray and waits for your signature, instead of paying it out.

| Shop idea | Project idea | Where |
| --- | --- | --- |
| The clerk looks up the customer's file | **gather**: read the company's facts from the CRM (plain code) | `HandWrittenAgent.start` |
| The clerk writes a suggestion | **recommend**: one call to the model, which answers in a fixed JSON shape | `OpenRouterRecommender` |
| The suggestion goes in your tray | **approval**: the run is stored and the call returns; nothing happens until you answer | `HandWrittenAgent.start` ends here |
| You sign, or you don't | **resume** with approve or decline | `HandWrittenAgent.resume` |
| The clerk does the job | **act**: a **dry run** here: it says what it would do and writes nothing | `HandWrittenAgent.resume` |

## J8.5a: the agent by hand

No AI framework: four plain steps, a stored run for the pause, and a hand-written model call. Port 18141.

```mermaid
flowchart LR
  start["POST /agent/start"] --> gather["gather<br/>CRM facts"] --> recommend["recommend<br/>one model call"] --> approval{{"approval<br/>(run stored, call returns)"}}
  approval -->|"POST /agent/resume approve"| act["act<br/>dry run"] --> completed(["completed"])
  approval -->|"POST /agent/resume decline"| declined(["declined"])
```

**The rules the code enforces** (each has a test):
- `start` runs `gather` and `recommend` and stops at the approval: the answer has status `awaiting_approval`, the four steps (`done`, `done`, `waiting`, `pending`) and the recommendation.
- Approving runs `act` as a **dry run** (the message says "Dry run ... Nothing was written") and **the CRM saw only reads**; declining skips it.
- A run can be answered **once**: a second answer is refused (409); an unknown run is refused (404).
- A company that is not in the CRM is a 404 and **the model is never asked**; a CRM that is down is a 503 and the model is never asked.
- The CRM facts reach the model **wrapped as untrusted data**; the model's answer must be JSON with an `action` of at most 200 characters, or the run fails (503); a code fence around the JSON is accepted.
- Errors say the HTTP status only, never what the service replied; no key means the model is never called.
- Only the most recent 100 runs are kept; two runs are independent.

**Measured here:** the behaviour of every step against a scripted model and a stub CRM. **Not measured:** a live model, and a durable pause (a restart forgets the runs).

**Rule to remember.** *An agent is safe because of what surrounds the model: fixed steps, the model's text confined to a message, and a pause before anything that matters.*

## J8.5b: the agent with LangGraph4j

The same four steps and the same HTTP contract, as a **LangGraph4j 1.9.9 graph**: `gather` (plain code), `recommend` (the same model call as the hand-written agent), `approval`, `act`. The pause is the library's own: the graph is compiled with a **checkpoint saver** and an **interrupt before `approval`**; a run is a graph *thread* whose id is the run id. Port 18142. Decisions and the measured tables: [ADR 0043](../adr/0043-agent-with-langgraph4j.md).

```mermaid
flowchart LR
  start["start: invoke(input, thread)"] --> gather --> recommend --> stop{{"interrupt before approval<br/>(state saved under the thread id)"}}
  stop -->|"updateState(approved) + invoke(resume)"| approval --> edge{approved?}
  edge -->|yes| act --> done(["end"])
  edge -->|no| done
```

**What LangGraph4j gave, without a line of ours** (measured in `LangGraph4jBehaviourTests`): the stop and the saved state, the continue from the checkpoint with the answer put into the state, a conditional edge that reads that answer, independent threads, and a refusal to resume a finished thread.

**What it did not give, and what that took:**

| The rule | What LangGraph4j does | In `withLangGraph4j` |
| --- | --- | --- |
| Know that the run stopped | `invoke` returns the state so far, **no marker** | Ask the saver: `getState(...).nextNodeId()` |
| 404 unknown run, 409 answered run | The **same two exceptions** for both | Our map of runs and a status |
| Keep only the last 100 runs | The saver has **no bound and no way to forget a thread** | One graph and one saver **per run**, in a bounded map |
| The original error | Wrapped in **three layers** | `unwrap` |
| A failed run must not linger | It **stays saved at the failed node** | A run is kept only after it reached the pause |
| State | Must be **serializable** | Strings only |

**Two things you would not guess.** The nodes run on **the library's own platform thread**, not the caller's, so anything that depends on the calling thread (a request context) does not follow. And a **finished thread's checkpoints are kept** (moved into a tag) whatever the release setting, so a saver that serves many runs grows for ever.

**Cost.** One direct dependency (`langgraph4j-core`, about 320 KB, bringing `async-generator`, `gson`, `slf4j-api`, `jspecify` and Jackson 2). The agent class is **178 lines of code against 112** for the hand-written one: the library replaced the state machine with two calls, but the contract rules it does not give were written out.

**Measured here:** the library's behaviour and the agent's, against a scripted model and a stub CRM. **Not measured:** a live model, and a durable saver (the application's `langgraph4j-postgres-saver` is not used here).

**Rule to remember.** *A checkpoint is not a record of runs. The library keeps the state; knowing which runs exist, which are answered and how many to keep is still yours.*
## J8.5c: the agent with LangChain4j's agentic module

The same four-step agent with LangChain4j's **agentic** module (`langchain4j-agentic`, a **beta**, 1.23.0-beta33, mapped by the stable BOM): one **sequence** of five agents over a shared scope. Port 18143. Decisions and the measured tables: [ADR 0044](../adr/0044-agent-with-langchain4j-agentic.md).

```mermaid
flowchart LR
  gather["gather<br/>plain code"] --> recommend["recommend<br/>AI agent"] --> validate["validate<br/>plain code"] --> approval{{"approval<br/>human-in-the-loop<br/>(suspends)"}}
  approval -->|"resume: approved"| act["act<br/>dry run"]
  approval -->|"resume: declined"| skip["act skipped<br/>(activation condition)"]
```

**The answer to the key question: yes, its pause does not hold a thread.** The approval agent's response provider returns a `SuspendedResponse`: the library stops the sequence, **releases the calling thread** and returns a *suspended result* with a resume callback. `resume` completes the pending response and the library carries on from where it stopped (earlier steps do not run again). The other kind of response, a provider that waits on a future, **does** park the thread (measured), so the choice of response type decides.

**What the module gave for free:** the sequence and the shared scope, the suspension and the resume, the skip of a step by an activation condition, independent runs by memory id. **What had to be added:** the bounded run store with eviction (**the library keeps every scope in memory, finished runs too**), the 404 and 409 answers, status-only error text (the library wraps exceptions three deep), the key check, and the answers in the contract's fixed shape.

**Found by running it:** the agent interfaces must be **public** (the library calls them by reflection, and a package-private interface fails with `IllegalAccessException`).

**Measured here:** the library's behaviour and ours against a scripted model and a stub CRM. **Not measured:** a live model, a durable pause across a restart (the module has a scope store that was not used), the loop and supervisor workflows.

**Rule to remember.** *Ask a framework's pause two questions: does it hold a thread, and does it survive a restart? Here the answers are no, and not unless you add a store.*

## Code tour

| | Where | What |
| --- | --- | --- |
| new | `comparisons/common/.../Agent.java`, `AgentBackendServer.java`, `Recommender.java`, `OpenRouterRecommender.java` | The contract, the shared HTTP face and the shared model call |
| new | `comparisons/agent-graph/handWritten/` | `HandWrittenAgent` (the steps, the run store, the resume), `HandWrittenAgentMain` (port 18141) |
| new | `justfile` | `comparisons-agent-hand-written` |
| new (J8.5b) | `comparisons/agent-graph/withLangGraph4j/` | `LangGraph4jAgent` (the graph, the run map), `LangGraph4jAgentMain` (port 18142); `justfile` `comparisons-agent-with-langgraph4j` |
| new (J8.5c) | `comparisons/agent-graph/withLangchain4jAgentic/` | `LangChain4jAgenticAgent` (the sequence, the run store, the resume), `LangChain4jAgenticMain` (port 18143); two members of `OpenRouterRecommender` made public; justfile `comparisons-agent-with-langchain4j-agentic` |

## Questions to try

1. Start a run and look at the steps. Where exactly is the run while it waits, and what happens to it if the backend restarts?
2. Why does the dry-run message echo the model's action instead of running it?
3. What would you have to add to make the pause survive a restart?
