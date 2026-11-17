# ADR 0045: multi-agent patterns on LangGraph4j, frameworks by survey (J8.7)

Status: accepted, 2026-11-17.

## Context

J8.7 was planned as "a multi-agent supervisor in three paths" (hand-written, LangGraph4j, LangChain4j agentic). Two things changed the plan. First, the hand-written path has taught the same lesson in five stages (J8.1 to J8.5: the rules around the library are ours), and the application's own supervisor on LangGraph4j (ADR 0020, stage E3) already exists as the framework reference. Second, a supervisor is only one of the **four architectural patterns** in common use (supervisor, hierarchical, network, handoff or swarm), and the owner wants to explore the patterns and the production-ready frameworks, not spend the slice on another hand-written loop.

## Decision

- **No hand-written path in J8.7.**
- **The four patterns are built on one framework, LangGraph4j (1.9.9, the version in use)**: a graph engine that has conditional edges, child graphs, parallel branches and checkpoint savers, so the comparison is about the architecture and not about the library. **Spring AI is the model and tool layer inside the nodes** (LangGraph4j publishes a Spring AI integration, `langgraph4j-spring-ai` 1.9.9). Spring AI is not used as the orchestrator: its own documentation presents agent patterns as code workflows, and it documents no hierarchical, network or handoff runtime.
- **Other frameworks (LangChain4j agentic, Embabel, Google ADK for Java, Koog) are covered by a verified survey** (`docs/stages/J8-7-multi-agent-patterns.md`), read from Maven Central, GitHub and each project's own documentation on 2026-11-17, with every unconfirmed claim marked "not verified". They are not built unless the owner asks.
- **The same scenario, limits and telemetry in all four patterns**, so they can be compared on the same signals: the account review (facts, an analysis, a review, a recommendation), a turn cap, caps per specialist, decisions recorded as data, and the J8.4 sink.
- **Slices:** J8.7b supervisor, J8.7c hierarchical, J8.7d network, J8.7e handoff (c to e in parallel), J8.7f the write-up. Folder: `comparisons/multi-agent/<pattern>` (the roadmap's folder name for this stage is updated).

## Consequences

- The comparison is by pattern, which is new in J8: where J8.1 to J8.5 compared libraries for one task, J8.7 compares architectures on one library. The write-up says so.
- LangGraph4j's handoff modules are at 1.5.12 while the core is 1.9.9, so the handoff pattern is built from the graph's own conditional edges, and whether the modules work with 1.9.9 is left open.
- The survey records what could not be confirmed. A framework claim that matters for a later decision must be confirmed from the project's own page first.
- Not measured: anything against a live model, and the cost of any pattern.
