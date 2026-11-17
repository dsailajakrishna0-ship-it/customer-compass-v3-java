# ADR 0041: observation with LangChain4j's own hooks (J8.4c)

Status: accepted, 2026-11-17. Third path of the J8.4 comparison (ADR 0039): the same tool-calling backend and the same shared telemetry sink, with the events taken from LangChain4j's listeners and events instead of calls written in the loop.

## Context

J8.4a (the hand-written path) reports each model call and each tool by hand, at the points where the loop already holds the facts. LangChain4j 1.23.0 has hooks of its own: a `ChatModelListener` on the chat model and, in `langchain4j-core`, an `observability` API whose AI-service events (`AiServiceStartedEvent`, `AiServiceRequestIssuedEvent`, `AiServiceResponseReceivedEvent`, `ToolExecutedEvent`, `AiServiceCompletedEvent`, `AiServiceErrorEvent`, ...) can be registered on `AiServices`. This slice feeds the shared `Telemetry` (ADR 0039) from them and records what they gave for free and what had to be added.

## Decision

- **A separate module** `comparisons/observability/withLangchain4j` (port 18133). It starts the unchanged tool-calling chat (`LangChain4jToolChat`, ADR 0031) with observation on; `TELEMETRY_ENABLED` is off by default and `GET /api/v1/observability/recent` shows the signals, as in J8.4a.
- **Model calls come from the chat model's `ChatModelListener`**, not from the AI-service events, because only the model hook also sees the **forced final answer** this approach makes when the model keeps asking for tools after the last round: it calls the model directly, outside the AI service, so no service event exists for it (measured). The model name asked for, the tokens and the time come from the hook's contexts.
- **Tools come from the AI service's `ToolExecutedEvent`,** the only hook that sees them.
- **The request is opened and closed by the chat, not by `AiServiceStartedEvent` / `AiServiceCompletedEvent`.** The request span must also cover the missing-key check and the forced final answer, neither of which the AI service sees, and the chat is where a model error is translated into "unavailable".
- **An extension point in the tool-calling module:** `LangChain4jToolChat.withObservation(ChatObserver, TurnInstrumentation)` (default does nothing, behaviour unchanged, its tests stay green). `TurnInstrumentation` returns the listeners to register for one question; `ToolOutcomes` is where our own code records why a tool ended as it did (below).
- **Correlation: the listeners are built per question.** The chat already builds one assistant and one model per question (the token counting needs it), so each listener holds its own question's request; two questions at once on the same chat get separate traces (a test runs two). The alternative, measured below, is the invocation id every AI-service event carries; the model hook has no such id, so the per-question listener is the one design that covers the model hook too.
- **Not used: `langchain4j-micrometer-metrics` and `langchain4j-observation`.** Both exist on Maven Central only as betas (1.23.0-beta33 and earlier betas, no stable release), and they feed Micrometer while the shared sink is OpenTelemetry. They were looked up, not evaluated.
- **Never content, as in ADR 0039.** These contexts and events hold the whole conversation, the tool arguments and the tool results (measured); the listeners copy only names, numbers and the error flag, and a test checks that the question, the answer, a tool result and the arguments appear in no span or metric.

## What LangChain4j gives for free (measured: `LangChain4jObservabilityBehaviourTests`, with no instrumentation of ours)

| Question | What LangChain4j 1.23.0 does |
| --- | --- |
| What fires for one question with one tool? | In order: `AiServiceStartedEvent`; then per round `AiServiceRequestIssuedEvent`, the model hook's request and response, `AiServiceResponseReceivedEvent`; the `ToolExecutedEvent` between the rounds; `AiServiceCompletedEvent` at the end. The model hook runs inside each round. |
| On which thread? | **All of them on the thread that asked the question.** |
| What does the model hook give? | The model asked for (`chatRequest().modelName()`), **the model that answered** (`chatResponse().metadata().modelName()`, which can differ), input and output tokens, the finish reason (`TOOL_EXECUTION` or `STOP`), and an `attributes()` map shared between the request and the response hook, so a start time can travel in it. |
| A model error | The model hook's `onError` fires, then `AiServiceErrorEvent`; the call throws. No token usage. |
| What does `ToolExecutedEvent` give? | The tool name and call id, **the arguments and the result text (content)**, and `isError()`. **Nothing else:** no duration, no reason for an error. |
| What does `isError()` say? | `false` when the tool ran; **`true` when the tool threw** (the error handler's text went to the model) and **when the arguments were not JSON**; **`false` for a tool nobody offered**, where the event still fires with the model's own invented name. A tool method that returns an error text of its own is also `false`. |
| Several tools in one reply | One event each, in the order of the calls (they run one after another by default). |
| Which question does an event belong to? | Every service event carries `invocationContext().invocationId()`: **two questions at once on one shared assistant got two different ids**, and all events of a question share its id. The model hook's contexts carry no such id. |
| Does a model call outside the AI service show up? | **In the model hook, yes; as a service event, no.** |
| Do they carry content? | **Yes, all of it:** the model hook's request holds the messages, the tool event the arguments and the result, the started event the user message. |

## What was added, and how

| Signal | How it is obtained |
| --- | --- |
| The request span: open, mark failed, close | **By hand, in the chat** (`withObservation`): `begin` before the key check, `fail("unavailable")` or `fail("error")` on the way out. |
| A model call: model, tokens, time | **From the model hook:** the requested model name, the tokens from the response, the time from a start mark put in `attributes()` in the request hook. A failed call is reported from `onError` with no tokens. |
| A tool call: name | **From the event,** but a name the event carries can be one the model made up, so it is checked against `CrmTools.knows` and recorded as `unknown` otherwise. |
| A tool call: the outcome word (`ran`, `invalid_call`, `invalid_arguments`, `tool_error`) | **Added by hand:** the places that know why are ours, the tool method (arguments refused) and the error handlers (not JSON, the tool threw, an unknown tool). They record the word in `ToolOutcomes` before the event fires; the listener takes it, and falls back to the event's `isError()` (`tool_error` or `ran`) when none was recorded. This relies on the tools of one reply running one after another, which is the default (measured). |
| A call the round limit refuses | **Added by hand:** the library **still reports it as an executed tool** (measured: a model that keeps asking for tools produces a fifth tool event), so the tool method records the word `skipped` before it throws. The hand-written path never sees such a call (it forces a plain answer instead), so this path has one more tool span in that case. |
| A tool call: the time | **Derived:** the event has no duration, so the time is measured from the previous event (the reply arriving, or the previous tool) to this one. It includes the library's argument parsing. |
| The rule that no content is recorded | **Ours:** the listeners read only the names, the numbers and the flag. |

## Consequences

- LangChain4j gives the model call (name, tokens, finish, error) and the existence of each tool for free, on the calling thread and with a correlation id on the service events. It does not give a tool's duration or the reason it failed, and **its contexts and events are full of content**, so the privacy rule is a discipline in our listener, not a property of the hook.
- Compared with the hand-written path, the loop contains no reporting calls; the cost moved to a small extension point, an outcome holder and a listener class, and to the knowledge that the forced final answer is only visible to the model hook.
- The forced final answer is the reason model calls cannot come from the AI-service events alone.
- Not measured: a live model, real latencies, concurrent tool execution (`executeToolsConcurrently()`, which this approach does not use and which would break the outcome holder), and export to a real receiver.
