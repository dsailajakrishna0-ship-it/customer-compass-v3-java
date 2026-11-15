# ADR 0031: tool calling with LangChain4j (J8.1c)

Status: accepted, 2026-11-15. Third approach of the J8.1 comparison (ADR 0029): the same backend as `handWritten` and `withSpringAi` (ADR 0030), built with LangChain4j.

## Context

LangChain4j runs the tool loop for you: `AiServices` sends the tools, executes what the model asks for, adds the results and asks again until the model answers in text. This slice builds the same backend with it to see, with tests, what the library does on its own and what had to be added to keep the rules `handWritten` enforces.

## Decision

- **A separate module** `comparisons/tool-calling/withLangchain4j` (port 18103), same HTTP face and the same two CRM tools (`comparisons/common`). Not a Spring application: it uses `AiServices.builder(...)` and `OpenAiChatModel` directly.
- **Versions:** `langchain4j` and `langchain4j-open-ai` **1.23.0** (the stable line). The Spring Boot starter with the `@AiService` annotation is published only as a beta (1.23.0-beta33) and needs a Spring application, so it is **not used**; `AiServices.builder` is the framework-independent way to declare the same service.
- **The tools are `@Tool` methods** (`CrmToolMethods`) over the shared `CrmTools`; a test checks that the generated schema matches the shared specs.
- **One assistant per question,** built per question because the model carries a listener (`TurnListener`) that counts tokens and keeps the last request; state per question is in `TurnState`. The earlier turns of the conversation are put into a `MessageWindowChatMemory`.

## What LangChain4j does on its own (measured by `LangChain4jLibraryBehaviourTests`)

| Question | What LangChain4j 1.23.0 does |
| --- | --- |
| Is there a limit on rounds? | **Yes: `maxToolCallingRoundTrips`, 100 by default** (the old name `maxSequentialToolsInvocations` is a deprecated alias). A model that always asks for the tool runs it 100 times, after 101 model requests, and then the invocation **throws** ("exceeded 100 tool calling round trips"). With a limit of 4, a model that asks a fourth time gets 4 runs and an exception, not a plain answer. |
| How many calls of one reply run? | **All of them** (6 of 6). |
| How is a tool result sent? | As the returned text, **no wrapper**. |
| Arguments that are not JSON? | **The whole invocation throws** by default; the tool is not run. |
| A tool that does not exist? | **The whole invocation throws** by default. |
| A missing argument? | **Not refused:** the method runs with `null`. |
| An extra argument, or `5` for a text argument? | **Ignored,** and **converted** to "5". |
| A tool that throws? | The exception's message is sent to the model unwrapped and the loop goes on. |
| Usage? | The result's token counts are the **sum of every model call of the loop** (35 in and 14 out in the test), not the last call's. |
| A reply without the OpenAI envelope `id`? | **Accepted.** (Spring AI's OpenAI SDK refuses it; `handWritten` accepts it.) |
| A system message plus earlier turns in memory? | The model sees **user, assistant, system, user**: the system message comes **after** the history, not before. `handWritten` and `withSpringAi` send it first. A difference, stated. |
| A model that answers with an error status? | An exception that carries the status (`HttpException`, `RateLimitException`, ...), which the approach turns into the route's 503 message. |

## What was added, and how (the library's own extension points)

| Rule of `handWritten` | In `withLangchain4j` |
| --- | --- |
| At most 4 rounds, then one plain answer with tools off | **Added:** the tool method throws `RoundLimitReached` when the model asks for tools after the fourth round; the error handler rethrows it on purpose (documented as ending the invocation), and the chat then makes one more request with the conversation so far, an "answer now" message and `tool_choice` none. The library's own limit of 100 never comes into play. |
| At most 4 calls per reply, the surplus dropped | **Added in the `@Tool` methods** (`TurnState`): calls after the fourth get an error text. **Differs:** `handWritten` drops the surplus silently. |
| Every result wrapped as untrusted data | **Added:** the method returns `Untrusted.wrap(...)`, and `ErrorsAsData` wraps the error paths. |
| Bad JSON becomes an error text | **Added:** `ErrorsAsData.arguments()` answers with one fixed sentence ("the arguments are not valid JSON"), not the parser's message (the ready-made `sendExceptionMessageToLlm()` would send the parser's own message, which a test shows). |
| A tool failure becomes an error text | **Added:** `ErrorsAsData` is the `toolExecutionErrorHandler`. |
| An unknown tool becomes an error text | **Added:** `hallucinatedToolNameStrategy`. |
| A missing `company_name` is refused | **Added inside the method** (`CrmTools.validate`). |
| An extra argument or a wrong type is refused | **Dropped,** as in Spring AI: the method only receives its parameters. `handWritten` refuses both. |
| Usage of the last call | **Differs:** the library sums the loop. |

What this approach leaves out compared with the application: no fallback chain across models, no prompt guard, no tracing, no audit log, two tools instead of four.

## Consequences

- LangChain4j has a round limit out of the box, which Spring AI lacks, but it **throws** when the limit is reached, so a graceful "answer now" needed a hook plus one extra request.
- Like Spring AI, it removes the loop, the argument parsing, the schema writing and the result plumbing, and leaves the rules around the loop to us.
- The history-before-system ordering is the one behaviour that changes what the model reads; whether it matters for answers is not measured here (no live call).
- The measurements are against a scripted model; lines of code, speed and cost belong to the write-up slice (J8.1d).
- A library upgrade can change a measured behaviour: `LangChain4jLibraryBehaviourTests` fails when it does.
