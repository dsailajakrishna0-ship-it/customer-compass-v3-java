# ADR 0027: tracing with OpenTelemetry, off by default, never content (J7.3)

Status: accepted, 2026-11-14. The Java twin of the Python edition's ADR 0022.

## Context

Until now the only record of a request is a log line and the audit tables. They say what was done and whether it was allowed; they do not say why a run was slow, which model really answered after a fallback, how many tokens a call used or which agent step failed. The Python edition added tracing in its Stage 5.1. The Java edition has the same questions and the same constraints: free tiers, one laptop, optional features are off by default and may never break a request (the reranker's rule).

## Decision

1. **OpenTelemetry directly, not Micrometer Tracing.** The code depends on `opentelemetry-api` and `opentelemetry-sdk` and sends spans over OTLP/HTTP (`opentelemetry-exporter-otlp`, with the JDK's own HTTP sender instead of OkHttp). Langfuse accepts that format at `/api/public/otel`, so nothing names Langfuse in the code, and the receiver is a setting. Micrometer Tracing is a facade over the same SDK; Spring Boot 4.1.2's `spring-boot-starter-opentelemetry` (checked in its published pom) brings the Micrometer metrics starter, an OTLP metrics registry and the tracing bridge, and Boot's auto-configured observations, none of which this slice needs, and it would make "off" depend on framework switches rather than on this code. I did not build it to compare. Versions come from Spring Boot 4.1.2's own BOM: OpenTelemetry **1.62.0** (the current release on Maven Central is 1.68.0, 2026-11-06; the BOM's version is the one tested with this Boot).
2. **One class knows OpenTelemetry: `observability/Tracing`.** Everything else calls `Tracing.span(...)`. It is a static facade on purpose: the gateway, retriever, tool runner and guards are built by hand in many tests, and a global switch keeps their constructors unchanged. With tracing off `span` returns a handle that does nothing.
3. **What becomes a span** (all attributes are counts, names, ids, sizes, durations):
   | Span | Langfuse type | Where | Attributes |
   | --- | --- | --- | --- |
   | `POST /api/v1/...` (the request) | span (root) | `RequestIdFilter` | method, path, status, request id |
   | `llm.generate` | generation | `ModelGateway` | requested and answering model, tokens in and out, latency, fallback used and from, messages, tools offered, tool calls |
   | `retrieval` | retriever | `Retriever` | mode, top k, floor, scoped to a company, reranker, question length, results, best and weakest similarity |
   | `tool.<name>` | tool | `ToolCallRunner` | tool, argument **names**, outcome (`ran`, `invalid_call`, `invalid_arguments`, `tool_error`) |
   | `guard.input`, `guard.output` | guardrail | `InputGuard`, `ChatGuard` | mode or endpoint, text length, whether anything fired, how many rules (never which text, never which rule) |
   | `agent.run` and one span per step | agent, tool or span | `AgentWorkflow`, `SupervisorWorkflow`, `Trace.record` | status, steps, kind; per step: index, kind, name, status, input and output **lengths** |
   | `agent.resume` | agent | `ApprovalGate` | confirm, whether a paused run was found |
   The agent's steps reuse the `Trace` the screen already shows: `Trace.record` also emits a span back-dated by the step's duration, so the screen and the trace cannot disagree and no node changed.
4. **The request id is the trace id.** `RequestIdFilter` already gives every request an id and the `X-Request-ID` header. When it is 32 hex characters (the generated ones are) the trace uses it, so one id finds the log lines, the header and the trace. A caller's own token stays an attribute and the trace gets a fresh id.
5. **Spans never carry content, and there is no switch for it.** The Python edition has `TRACING_CAPTURE_CONTENT`; this edition deliberately does not: no question, prompt, answer, document text, tool argument value or rule name goes into a span, whatever the setting. Attribute names that look like secrets (`api_key`, `secret`, `password`, `authorization`, `bearer`, `credential`) are dropped and text is cut to 200 characters. An exception is recorded by its type only, because a message can hold a key or a customer's text.
6. **Off by default (`TRACING_ENABLED=false`) and never breaks a request.** Off: no exporter, no thread, no network, and the exporter code is never called. On: the OTLP exporter runs behind a batch processor on its own thread with a 5-second timeout; a dead receiver is that thread's problem. A failure to set tracing up at start-up is logged by exception type and the API starts without it. Settings, with the Python edition's names: `TRACING_ENABLED`, `TRACING_ENDPOINT`, `TRACING_SERVICE_NAME`, `TRACING_EXPORT_TIMEOUT_SECONDS`, `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY` (never printed; no `Authorization` header is sent when they are empty).
7. **The graph's threads carry the context explicitly.** LangGraph4j runs the agent on virtual threads, and OpenTelemetry's context is thread-local, so the run's context is captured where the run starts and made current around the run and around each node. A test breaks the wrapper on purpose and fails.
8. **The audit tables stay as they are.** A trace is for understanding and may be sampled or deleted; an audit row is a record.
9. **Langfuse is run from the Python edition's compose profile** (`docker compose --profile observability up -d`, port 3000) or Langfuse Cloud; the Java edition has no compose file of its own and does not start it.

## Alternatives considered

- **Micrometer Tracing with the OTel bridge** (`spring-boot-starter-opentelemetry`): see decision 1.
- **The OpenTelemetry Java agent** (`-javaagent`): zero code, but it traces every library call and cannot promise "never content".
- **The Langfuse SDK**: bakes the receiver into the code.
- **A switch to record content**, as in Python: rejected here; a second place customer text can leak from is not worth a debugging convenience.

## Consequences

- A few small dependencies enter the application (OpenTelemetry API, SDK, the OTLP exporter and its JDK sender; the test scope adds the SDK's testing module).
- Spans cover the request, model calls, searches, tool calls, guard scans and agent steps. **Not spanned:** embedding calls on their own (they run inside the `retrieval` span), ingestion, SQL, and the MCP server's own work (a tool span shows the round trip).
- The span name carries the request path, which can hold ids.
- Not measured: the export to a real Langfuse (the wire format is tested against a stand-in receiver on localhost, not against Langfuse), request latency with tracing on end to end, and memory.
