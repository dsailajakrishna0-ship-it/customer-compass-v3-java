# ADR 0039: observability, built several ways (J8.4)

Status: accepted, 2026-11-17. J8.4a is the shared sink and the `handWritten` path; `withSpringAi` (J8.4b) and `withLangchain4j` (J8.4c) follow.

## Context

Until now the only record of what a backend did is its answer. The application records traces with OpenTelemetry (ADR 0027): a span per request, model call, tool call, retrieval and agent step, **never content**, off by default. J8.4 asks the same question the other J8 stages did: what does each approach give for free, and what has to be added?

## Decision

- **The observed flow is tool calling.** It has both signals the roadmap names, model calls and tool calls, and all three approaches already exist (J8.1). The observation modules are `comparisons/observability/<approach>`, ports 18131 `handWritten`, 18132 `withSpringAi`, 18133 `withLangchain4j`; each builds the existing tool-calling chat with observation on.
- **One shared sink, three ways to feed it.** `comparisons/common` has the contract (`ChatObserver`: `begin`, then `modelCall`, `toolCall`, `fail`, `close`) and the one class that knows OpenTelemetry (`Telemetry`). What differs per approach is **how the events are captured**: the hand-written loop reports them by hand (J8.4a); Spring AI's own observations (J8.4b) and LangChain4j's listeners (J8.4c) feed the same sink. So the comparison is of the capture, not of the exporter.
- **The signals** (the application's, ADR 0027):
  | Span | Attributes |
  | --- | --- |
  | `chat.request` (root, one per question) | `cc.approach`, `gen_ai.request.model`, `cc.status` (`ok` or `error`), `cc.model_calls`, `cc.tool_calls`, `cc.failure` |
  | `llm.generate` (one per model call) | `gen_ai.operation.name`, `gen_ai.request.model`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `cc.outcome` (`ok` or `error`) |
  | `tool.<name>` (one per tool the model asked for) | `gen_ai.tool.name`, `cc.outcome` (`ran`, `invalid_call`, `invalid_arguments`, `tool_error`) |
  Metrics: `cc.chat.requests` (by approach and status), `cc.chat.model_calls` (by model and outcome), `cc.chat.tool_calls` (by tool and outcome), `gen_ai.client.token.usage` (input and output), and a histogram `cc.chat.request.duration`. Attribute names follow OpenTelemetry's GenAI conventions where one exists.
- **Never content, and no switch for it** (as in ADR 0027). The only strings that go into a span or a metric are the approach, a model name the backend or the caller chose, a tool name the backend offers, and fixed outcome words. **A tool name the model made up is recorded as `unknown`**, because it is model output; the span is `tool.unknown`. A test checks that the question, the answer, a tool result and the arguments appear nowhere in any span, metric or the JSON view.
- **Off by default** (`TELEMETRY_ENABLED=false`): the chat gets no observer, nothing is recorded, and the signals route answers 404 with a hint. On: spans go to a bounded in-memory buffer (the last 200) and metrics to an in-memory reader.
- **A way to look without a trace backend:** `GET /api/v1/observability/recent` on each observation backend shows the recent traces (spans with durations and attributes) and the metrics as JSON. Exporting to Langfuse or any OTLP receiver (the application's exporter, ADR 0027) is not part of this slice.
- **Child spans are back-dated by the duration** the approach reports, as the application's agent steps are: the time was spent before the report, so the span starts that long before it ends.
- **The SDK's in-memory metric reader** (`opentelemetry-sdk-testing`) is on the compile scope of `common` on purpose: it is what the recent route reads. The SDK's in-memory span exporter keeps every span for ever, so a bounded exporter of our own is used instead.

## What the hand-written approach does (J8.4a)

`HandWrittenToolChat.withObserver(...)` (the default does nothing): the loop calls the observer where it already holds the facts. A request is opened in `answer`; `complete` reports every model call with the model, the tokens read from the reply and the time, an error outcome when the call failed; `runTool` reports every tool with its outcome and time; an unavailable model or a missing key marks the request failed.

| Signal | Where the hand-written loop gets it |
| --- | --- |
| Model call, tokens, time | It parsed the reply itself, so it has `usage.prompt_tokens` and `completion_tokens` and measured the time |
| Tool name and outcome | It runs the tool, so it knows if the arguments were valid JSON, valid for the schema, and whether the call threw |
| Request status | It knows if the model answered or the call failed |
| An invented tool name | It checks the name against the tools it offers before recording it |

## Consequences

- The hand-written loop needs a handful of lines at the points where it already has the data, and nothing else; the cost is that each new place that calls a model or a tool must remember to report.
- The comparison is only meaningful if all three approaches produce the same signals, so J8.4b and J8.4c test against the same expectations (the same spans, attributes and metrics for the same scripted question).
- **A build detail found by running it:** an observation backend depends on the tool-calling module, and `-am` pulls that module's own `exec:java` into the build, which starts the plain tool-calling backend first and never returns. The tool-calling poms therefore read `toolcalling.exec.skip` (default false, so their own recipes are unchanged) and the observation recipes pass `-Dtoolcalling.exec.skip=true`. The unit tests cannot show this; only starting the backend through `just` did.
- `Telemetry` is tested through the observation modules (the signals each approach produces), not on its own.
- Not measured: a live model, real latencies, and export to a real receiver.
