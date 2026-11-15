# ADR 0030: tool calling with Spring AI (J8.1b)

Status: accepted, 2026-11-15. Second approach of the J8.1 comparison (ADR 0029): the same backend as `handWritten`, built with Spring AI.

## Context

`handWritten` writes the tool loop by hand. Spring AI 2.0 has the loop built in: `ChatClient` sends the tools, its `ToolCallingAdvisor` executes what the model asks for, adds the results and asks again until the model answers in text. This slice builds the same backend with that machinery to see, with tests, what the library does on its own and what had to be added to keep the rules `handWritten` enforces.

## Decision

- **A separate module** `comparisons/tool-calling/withSpringAi` (port 18102), same HTTP face and the same two CRM tools as `handWritten` (`comparisons/common`). It is not a Spring Boot application: it uses the library classes (`OpenAiChatModel`, `ChatClient`, `@Tool`) directly, like the application's own `SpringAiModelClient` does, and serves the route with the JDK server so the comparison is about tool calling and not about a web framework.
- **Versions:** Spring AI **2.0.2**, the line the application uses and, on Maven Central today, the newest release that is not a release candidate (2.1.0-RC2 is the latest build). Spring Boot 4.1.2 is only the parent POM for dependency versions, as in the other modules. The reference documentation for 2.0.2 was read for this slice (Tool Calling, ToolCallingAdvisor), and the library's behaviour was then measured, not assumed.
- **The tools are `@Tool` methods** (`CrmToolMethods`) that adapt the shared `CrmTools`. A test checks that the schema Spring AI generates from the annotations matches the shared specs (names, descriptions, the required `company_name`).
- **One `ChatClient` call per question,** with state per question (`TurnState`): the rounds so far, the calls of the current reply and the tools used.

## What Spring AI does on its own (measured by `SpringAiLibraryBehaviourTests`)

A plain `ChatClient` with one `@Tool` method and the default loop, against a scripted model:

| Question | What Spring AI 2.0.2 does |
| --- | --- |
| Is there a limit on rounds? | **No.** A model that always asks for the tool is stopped only by the library's tool-call cap: 40 calls per tool by default (150 in total), after 41 model requests. The answer is then the library's text "Tool call limit (40) exceeded for tool ..." with finish reason `toolCallLimitExceeded`. |
| How many calls of one reply run? | **All of them** (6 of 6). |
| How is a tool result sent? | As JSON text of the return value (a string comes out as `"result for Acme"`, with quotes), with no wrapper. |
| Arguments that are not JSON? | The tool is not run and the model gets **Jackson's own message** ("Unrecognized token 'not' ..."), unwrapped. |
| A tool that does not exist? | **`IllegalStateException` out of the whole call** ("No ToolCallback found for tool name: drop_tables"): the question fails. |
| A missing argument? | **Not refused:** the method runs with `null`. |
| An extra argument, or `5` for a text argument? | **Ignored,** and **converted** to "5". |
| A tool that throws? | The exception's message is sent to the model as the result, unwrapped, and the loop goes on. |
| Usage? | The answer's token counts are the **sum over every model call of the loop** (15 + 20 prompt tokens in the test), not the last call's. |
| A model reply without the OpenAI envelope (`id`, `object`, `created`, `model`, ...)? | **Refused by the OpenAI SDK** (`OpenAIInvalidDataException: id is not set`). The hand-written approach reads only `choices[0].message` and accepts it. |

## What was added, and how (the library's own extension points)

| Rule of `handWritten` | In `withSpringAi` |
| --- | --- |
| At most 4 rounds, then one plain answer with tools off | **Added:** `RoundLimitedToolCallingAdvisor` extends `ToolCallingAdvisor` (the documented extension point; `doBeforeCall` runs before every model call) and, after 4 rounds, adds the "answer now" message and sets `tool_choice` to `none`; the loop stops whatever the model then says. |
| A cap on tool calls | **Configured:** `maxTotalToolCalls(16)` (4 rounds of 4) with `RETURN_ERROR_RESPONSE`, as a second safety net; the round limit stops the loop first. |
| At most 4 calls per reply, the surplus dropped | **Added in the `@Tool` methods** (`TurnState`): the calls after the fourth get an error text as their result. **Differs:** `handWritten` drops the surplus calls silently, Spring AI answers every call it was asked for. |
| Every result wrapped as untrusted data | **Added:** `@Tool(resultConverter = UntrustedResultConverter.class)`. The converter does not cover the error paths, so `ErrorsAsData` wraps those too. **Not wrapped:** the library's own limit text (`RETURN_ERROR_RESPONSE`), which comes from the library, not from a tool. |
| Bad JSON or a tool failure becomes an error text | **Added:** `ErrorsAsData` is the `ToolExecutionExceptionProcessor`: the model gets "the arguments are not valid JSON" (not Jackson's message) or the exception's message, wrapped. |
| An unknown tool becomes an error text | **Added:** `ErrorsAsData` is also the `ToolCallbackResolver`, with `resolutionFallbackEnabled(true)`. It never returns a real tool, only a callback that answers "Unknown tool", so the model still cannot run anything that was not offered. |
| A missing `company_name` is refused | **Added inside the method** (the shared `CrmTools.validate` runs on the parameter). |
| An extra argument or a wrong type is refused | **Dropped:** the `@Tool` method only receives its parameters, so an extra argument is never seen and `5` arrives as "5". `handWritten` refuses both. A difference, stated. |
| Usage of the last call | **Differs:** the library sums the loop. This is arguably the more honest cost; the HTTP face reports whatever the approach gives. |

What both leave out compared with the application: no fallback chain across models, no prompt guard, no tracing, no audit log, two tools instead of four.

## Consequences

- Spring AI removes the loop, the argument parsing, the schema writing and the result plumbing, about as much code as `handWritten`'s loop and parsing. What it does not give is the rules around the loop: rounds, per-reply caps, untrusted wrapping of error paths, unknown-tool handling. Those were found by measuring, and each needed a hook.
- The OpenAI SDK under Spring AI is strict about the reply envelope, so a provider that omits `id` breaks `withSpringAi` and not `handWritten`.
- The measurements are against a scripted model. How a real model behaves with these tools is not measured here (no live call, no recording); the numbers for lines of code, speed and cost belong to the write-up slice (J8.1d).
- Library upgrades can change a measured behaviour: `SpringAiLibraryBehaviourTests` fails when they do.
