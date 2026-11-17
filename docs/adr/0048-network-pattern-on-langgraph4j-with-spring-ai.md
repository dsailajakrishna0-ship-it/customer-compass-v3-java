# ADR 0048: the network pattern on LangGraph4j with Spring AI (J8.7d)

Status: accepted, 2026-11-17. Third of the four patterns of J8.7 (ADR 0045), built on the shared contract and scenario of the supervisor (ADR 0046); the hierarchical and handoff patterns are built next to it.

## Context

In a **network** nobody is in charge. The specialists are peers: after a peer has worked, *it* names who works next, and it may name any other peer. There is no supervisor and no allowed list built from the global state. The pattern is the least controlled of the four, which is the reason to build it: it shows how little protection the library gives and how much the code has to add to keep such a team from wandering.

## Decision

- **Folder:** `comparisons/multi-agent/network/withLangGraph4jSpringAi` (port 18153): the concept (multi-agent), the pattern (network) and the framework (LangGraph4j for the graph, Spring AI for the model calls).
- **The same scenario and the same four specialists as the supervisor** (`crm_facts`, `deals`, `analyst`, `reviewer`; the same prompts, the same length-checked recommendation, the same untrusted-data wrapping), **copied on purpose** so that only the organisation differs and the folder reads on its own. The shared contract in `comparisons/common` is unchanged.
- **A fully connected graph:** four peer nodes, an edge from the start to the entry peer (`crm_facts`, which nobody names), and **from every peer a conditional edge to every peer (itself included) and to the end.**
- **Each peer names the next one** with a model call of its own ("who works next?"), given only flags about the board (never CRM text) and the list of names it may name. The choice is **forced** (no model call, `decidedBy: code`) in only four cases: the company was not found, the turn limit was reached, every peer is at its cap, and a peer that was called without its inputs. A peer's choice is `decidedBy: peer:<name>`.
- **The protections, all of them:**
  | Protection | Where | What it does |
  | --- | --- | --- |
  | A peer's own inputs (local) | `Peers.blockedBy` | A peer called without its inputs (the analyst or deals without facts; the reviewer without a recommendation) does **no work**, the turn is wasted, and it names the peer that produces them (recorded as `code`, a `blocked` step) |
  | A cap of turns (global) | `NetworkTeam.choose` | At most 10 turns (configurable), then `stopped` |
  | A cap of calls per peer (global) | `Board.MAX_CALLS_PER_PEER` | A peer may be called at most 3 times; a name that is at its cap is not allowed, so it is replaced by the naming peer's default successor, or by FINISH (`stopped`) |
  | A loop guard (global) | `NetworkTeam.settle` | The same peer named again **at the same board version** as the last time it was named (the board is the same, nothing new was found) ends the run `stopped`; this catches a peer naming itself and two peers naming each other |
- **Nothing checks that the job is done.** FINISH is honoured whenever a peer names it. The run is `completed` only if a reviewed recommendation exists; FINISH before that is `stopped` ("a peer named FINISH before a reviewed recommendation existed"); a review that asked for a revision does not stop a peer from naming FINISH (the message says so). The supervisor pattern cannot end like this, because FINISH is allowed only after approval or the last revision.
- **An unreadable answer, or a name that is not a peer, or a peer at its cap,** is replaced by the naming peer's **default successor** from a fixed table (`crm_facts` to `deals`, `deals` to `analyst`, `analyst` to `reviewer`, `reviewer` to FINISH if the review approved, else `analyst`) and recorded with what was proposed (a plain word, or `unreadable`: model text is never echoed).
- **The contract's `Decision` record is unchanged:** `decidedBy` is `peer:<name>` or `code` here (`supervisor` or `code` in the supervisor pattern), `allowed` is every peer below its cap plus FINISH, and `turns` is the number of decisions (every peer turn ends with one).
- **State:** a plain `Board` object in a closure, built per run (the blackboard every peer reads and writes plus the bookkeeping of the guards); the graph's own state carries only the name of the peer named next, so nothing the graph holds needs to be serializable.
- **Observation (the J8.4 sink, off by default):** `modelCall` for every model call, `toolCall` for the two CRM tools, a `step` per peer turn (`ok`, `blocked` or `error`) and a `step` named `peer_choice` per decision (`model`, `code` or `override`); never content.

## What LangGraph4j and Spring AI do on their own (measured: `NetworkBehaviourTests`)

| Question | What they do |
| --- | --- |
| How is a fully connected graph expressed? | **One conditional edge from every node to every node and to the end**; a peer may name any peer and the route is followed (a test walks a to c to b to b to a to end). It needs nothing but the routing map |
| Is the library's own limit the only brake? | **It is the only brake of the library:** a bare network of two peers that name each other is stopped **only** at the library's **25 iterations** (`IllegalStateException`, "Maximum number of iterations (25) reached!"), after **24 node runs**: with a model naming the next peer after every turn, 24 model calls. Nothing in the library notices a loop |
| A node's exception | Comes out wrapped in **three layers** (`CompletionException`, `GraphRunnerException`, `ExecutionException`); our `unwrap` finds the original |
| Which thread, two runs at once | As in the supervisor (ADR 0046): the library's own platform thread; independent runs |
| Spring AI | As in the supervisor (ADR 0046): the response carries the tokens and the answering model, and **its HTTP error message holds the service's own reply**, so `SpringAiLlm.translate` reduces it to the status |

## What was measured about the pattern itself

All numbers are asserted by `NetworkTeamTests` (a scripted model, a stub CRM).

| Question | Result |
| --- | --- |
| **Model calls on the happy path** (crm_facts, deals, analyst, reviewer, FINISH) | **6**: four namings (every peer decides) + the analyst + the reviewer. **The supervisor pattern needs 4** on the same path (ADR 0046): its two code decisions (the analyst to the reviewer, the reviewer to FINISH) cost nothing, because code knows the order. **A network spends 50% more calls to reach the same answer** |
| Where is the decision log? | **Scattered:** `peer:crm_facts`, `peer:deals`, `peer:analyst`, `peer:reviewer`: four peers, four choices, no one place that decides or can be audited as a whole |
| What does a peer called out of order cost? | crm_facts names the reviewer: the reviewer does no work and names the analyst: **one wasted turn, no wasted model call** (the blocked turn is decided by code), 5 model calls and 4 turns for the three-peer path |
| A peer that names itself (a runaway) | The loop guard ends it after **3 turns and 3 naming calls** (`crm_facts`, `deals`, `deals`) |
| Two peers that name each other with nothing new | Ended after **4 turns and 4 naming calls** |
| Two peers that keep changing the board (the analyst and the reviewer, the reviewer always asking for a revision) | **The loop guard sees nothing**, because the board changes each time; only the cap of 3 calls per peer stops it, after **7 turns and 13 model calls** (7 namings + 3 analyst + 3 reviewer). **This is the expensive failure:** a loop that makes progress by the board's own measure |
| FINISH too early | The run ends `stopped` with no recommendation after 1 turn and 1 model call: no one checks the job |

## What was added, and how

| Rule | How |
| --- | --- |
| A peer's own inputs | `Peers.blockedBy` (code); a blocked peer names its producer |
| The caps: 10 turns, 3 calls per peer | `NetworkTeam.choose` and `settle`, `Board` |
| The loop guard | `settle` compares the board's version (counted from a fingerprint of what the peers found) with the version at the last naming of that peer |
| Default successors for a bad answer | `Peers.defaultNext`, recorded as an override with what was proposed |
| 404 for a company that is not found | The `crm_facts` peer sets a flag; the next choice is forced to FINISH and `run()` throws `CompanyNotFoundException` |
| The library's exception layers | `unwrap` |
| Errors by status only, no key means no call | `SpringAiLlm` (copied from the supervisor) |
| A peer's naming prompt holds no CRM text | `NetworkTeam.question` builds it from flags only (a test checks the requests) |
| Never content in the signals | Only fixed names, counts and outcome words reach the observer; a test checks every span name, attribute and metric attribute |

## When a network is the right shape

A network fits **a few symmetric peers doing exploratory work**, where no fixed order exists and the cost of a wandering run is low (for example, brainstorming agents). It is **rare in production** for the reasons measured above: **no central control or audit** (the decision log is four peers' guesses), **it costs more model calls** (every peer decides), **loops** (the library only stops one at 25 iterations; our guards had to be written, and a loop that changes the board slips past the loop guard and is stopped only by a cap), and **nothing checks the job is done**. When the order is known, a pipeline is cheaper and a supervisor with allowed lists is safer.

## Consequences

- The network is the control group of J8.7: it keeps the same specialists and the same signals, and shows what the supervisor's allowed list and the hierarchical pattern's boundaries buy.
- The guards here are deliberately blunt; a real network would need a **budget in tokens or money per run**, which this lab does not measure.
- Not measured: a live model (a real model may name peers more or less sensibly than the scripts), real latencies, and cost in money.
