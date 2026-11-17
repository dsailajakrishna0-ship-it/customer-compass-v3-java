# ADR 0040: observability with Spring AI (J8.4b)

Status: accepted, 2026-11-17. Second path of the J8.4 comparison (ADR 0039): the same signals as the hand-written path, captured through Spring AI's own observations instead of reports written into the loop.

## Context

The hand-written loop reports a model call and a tool call where it already holds the facts (J8.4a). Spring AI 2.0 observes its own work with Micrometer Observation: a registry given to the chat model and to the tool-calling manager receives an observation for every model call and every tool call, and a handler on that registry sees them. This slice writes such a handler that feeds the shared sink (`ChatObserver`, `Telemetry`) and measures what Spring AI gives for free and what had to be added.

## Decision

- **A separate module** `comparisons/observability/withSpringAi` (port 18132). It builds the existing `SpringAiToolChat` (J8.1b) with an `ObservationRegistry`, adds one handler to the registry (`SpringAiObservationHandler`) and wraps each question (`ObservedSpringAiToolChat`). The same HTTP face, `TELEMETRY_ENABLED` (default false) and `GET /api/v1/observability/recent` as the hand-written path; with observation off the backend is the plain tool-calling chat.
- **Spring AI 2.0.2**, the line the tool-calling module uses. Its observation classes are in `spring-ai-model` (`chat/observation`, `tool/observation`); the handler reads `ChatModelObservationContext` and `ToolCallingObservationContext` and ignores every other context.
- **The tool-calling module gets a small optional extension point** (`SpringAiToolChat.withObservationRegistry(...)`; the default registry observes nothing, behaviour and its tests are unchanged) and two places that add our outcome words to the tool observation (`CrmToolMethods`, `ErrorsAsData`).
- **Never content.** The handler reads the model name and the token counts from a model call, and the tool's name and our outcome word from a tool call, and nothing else (see "What Spring AI does on its own"). A tool name the model made up is reported as `unknown`.
- **The question is found from the registry's scope chain,** not from a parent and not from the current observation. `ObservedSpringAiToolChat` keeps an observation open on the asking thread whose context carries the question's `ChatObserver.Request`; Spring AI runs its handlers on that thread, and the stack opens a few unnamed no-op scopes above the question's, so the handler walks the whole chain until one carries a request. A test runs two questions at once and checks that each model call lands in its own trace.
- **The model name reported is the one asked for** (from the request's options), as in the other paths; the model's reply names another (`fake/model` in the tests), which is on the observation as `gen_ai.response.model`.

## What Spring AI does on its own (measured: `SpringAiObservabilityBehaviourTests`)

| Question | What Spring AI 2.0.2 does |
| --- | --- |
| What is observed for one question with one tool? | **Two `gen_ai.client.operation` observations** (a `ChatModelObservationContext`, one per model call), **one `spring.ai.tool`** (a `ToolCallingObservationContext`) and, from the HTTP client under the model, **two `okhttp.requests`** (children of the model observation). A handler must ignore the HTTP ones. |
| What does a model observation hold? | Low-cardinality: `gen_ai.operation.name` (chat), `gen_ai.request.model`, `gen_ai.response.model`, `gen_ai.system`. High-cardinality: **`gen_ai.usage.input_tokens`, `output_tokens`, `total_tokens`**, `gen_ai.response.finish_reasons`, `gen_ai.response.id`, `spring.ai.model.request.tool.names`. The tokens are there for free. |
| What does a tool observation hold? | Contextual name `execute_tool <name>`; low-cardinality `spring.ai.tool.definition.name`, `spring.ai.tool.type` (function), `gen_ai.operation.name` (execute_tool); high-cardinality `spring.ai.tool.call.id`, the tool's description and its JSON Schema (a definition, not content). **No arguments and no result among the key values.** |
| Does anything carry content by default? | **No key value and no event** carries the question, the answer, a tool argument or a tool result (checked for every key and event of one question). But the contexts hold them in memory: the model context's request is the whole prompt and its response the answer; the tool context has `getToolCallArguments()` and `getToolCallResult()`. Spring AI's content handlers (`ChatModelPromptContentObservationHandler`, `ChatModelCompletionObservationHandler`, `ToolCallingContentObservationFilter`) are separate classes that do nothing unless added. Our handler never reads those getters. |
| Which thread? | **Every handler runs on the thread that asked the question.** |
| Is the observation a child of the observation open on the thread? | **No.** With an observation open on the thread, the model and tool observations still have **no parent**; only the HTTP observation is chained (to the model call). A handler cannot find the question from the parent. The open observation is **not the top scope either**: the registry's scope chain when a handler runs is a few unnamed no-op scopes and, last, the question's observation. The handler walks the chain. |
| A model that fails? | The model observation gets an **error** (`onError` with `InternalServerException`), `gen_ai.response.model` is `none` and there are no token values; `onStop` still fires. |
| A tool that throws, or arguments that are not JSON? | **No error on the tool observation:** the manager hands the exception to the processor inside the observation, so it looks like a success. |
| A call that was refused by our own checks (bad arguments, a fifth call in one reply)? | Looks like a **success** too: our tool method returns an error text. |
| A tool the model made up? | Observed **under its own name** (`spring.ai.tool.definition.name=drop_all_tables`, `execute_tool drop_all_tables`). Anything built on that value (a span name, a metric label) carries a string the model chose. |
| A chat with no registry? | The default registry observes **nothing**: no handler is called. |

## What was added, and how

| Signal | From Spring AI, free | Added by us |
| --- | --- | --- |
| A model call: model, tokens, time, outcome | The observation, its usage and its error (the handler reads them) | The handler maps them; the **model name reported is the requested one** (read from the request's options, not from the prompt) |
| A tool call: name, time | The tool observation | The handler; a name the backend does not offer is reported as **`unknown`** (the library reports it as the model wrote it) |
| Tool outcome `ran` | The observation without an error and without our word means `ran` | nothing |
| Tool outcome `invalid_arguments`, `skipped` | **Nothing:** a refused call looks like a success | `CrmToolMethods` adds `cc.outcome` to the current tool observation (the tool method runs inside it) |
| Tool outcome `invalid_call`, `tool_error` | **Nothing:** no error on the observation | `ErrorsAsData.process` adds `cc.outcome` to the current tool observation (it runs inside it) |
| Which question a call belongs to | **Nothing usable:** no parent, and the question is not the top scope | `ObservedSpringAiToolChat` keeps an observation per question open on the thread; the handler walks the registry's scope chain (tested with two questions at once) |
| The request: opened, closed, failed | Not an observation | `ObservedSpringAiToolChat` (`begin`, `fail("unavailable")`, `close`) |
| Never content | Key values are free of it; the contexts are not | The handler reads only the fields listed above, and a test checks every span name, attribute and metric attribute |

**A difference from the hand-written path, stated:** a fifth tool call in one reply is a `tool.<name>` span with the outcome `skipped` here, because Spring AI asks for all the calls and our method answers the surplus ones with an error text; the hand-written loop drops them silently and records nothing.

## Consequences

- Spring AI hands over what a model call cost (model, tokens, error) with no code of ours, which is the part the hand-written loop had to parse out of the reply itself. What it does not hand over is what our own rules decided (refused calls, thrown tools), the question a call belongs to, and a name that is safe to record: those are our code, in three small places.
- The outcome words are written into the tool observation by the code that decides them. Anyone who adds a new place that refuses or fails a tool call must add the word there, as in the hand-written loop each new place must report.
- The handler relies on Spring AI running its handlers on the asking thread, which holds for the blocking call used here (measured) and is not claimed for streaming.
- Not measured: a live model, real latencies, Spring AI's own meter handlers (`ChatModelMeterObservationHandler`) and the ChatClient and advisor observations (the classes exist; they were not enabled), and export to a real receiver.
