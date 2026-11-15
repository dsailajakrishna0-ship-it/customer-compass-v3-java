# Stage J8: the same module, built several ways

The plan is in [`docs/roadmap.md`](../roadmap.md) ("Stage J8"); decisions: [ADR 0029](../adr/0029-comparison-backends-behind-one-http-face.md). The code is in [`comparisons/`](../../comparisons/README.md), separate from the application. Slices, one PR each: **J8.1a** the shared parts, the `handWritten` tool-calling backend and the web app's Approach buttons, **J8.1b** the `withSpringAi` backend (both on this page), then J8.1c `withLangchain4j` and J8.1d the write-up that compares them.

## In one minute

A model cannot look at your CRM. **Tool calling** is the trick that lets it: you tell the model "these functions exist"; when it needs one, it answers "call this function with these arguments" instead of text; your code runs it, hands the result back, and the model writes the answer. The same trick can be coded by hand, or with a framework doing the loop for you. This stage builds the same small flow each way so you can read them side by side.

| Shop idea | Project idea | Where |
| --- | --- | --- |
| A shop assistant who may phone the stockroom | **The model with tools**: it can ask for a company's facts | the two tools in `common/CrmTools.java` |
| The phone call, written down by hand | **handWritten**: our own loop, plain Java | `tool-calling/handWritten/HandWrittenToolChat.java` |
| The same call, through a switchboard that dials for you | **withSpringAi** (J8.1b), **withLangchain4j** (a later slice) | `tool-calling/withSpringAi`, `tool-calling/withLangchain4j` |
| A switch on the counter that picks which assistant serves you | **The Approach buttons** in the web app | `apps/web/src/api/approaches.ts` |

## J8.1a: what is built

```mermaid
flowchart LR
  web["web app<br/>Approach buttons"] -->|"POST /api/v1/chat/tools"| face["common: HTTP face<br/>checks the request"]
  face --> hw["handWritten<br/>the loop"]
  hw -->|"tools + messages"| llm["model API<br/>(OpenRouter)"]
  hw -->|"get_company_summary<br/>get_open_deals"| tools["common: CrmTools"]
  tools -->|"GET /api/v1/companies/{id}"| crm["the application's CRM routes"]
```

**The loop in `handWritten`** (read `HandWrittenToolChat.answer`): send the conversation and the tools' schemas; if the model asks for tools, run each (at most 4 per reply), wrap each result as `<untrusted_context>` data, put them in the conversation and ask again, at most 4 rounds; after that, one last request with tools switched off forces a plain answer. A call with unreadable or wrong arguments, an unknown tool, or a tool that fails is answered with an error text the model can read, never an exception.

**What it leaves out compared with the application** (so you do not mistake it for the same thing): no prompt guard (no `guardrail` in the answer), no fallback across models, no tracing, no audit log, two read-only tools instead of four.

**The Approach buttons** appear in the web app's tool-calling mode. "The application" is the default and changes nothing. A comparison backend that is not running is greyed out with "(not running)"; choosing one sends only the tool-calling question to its port.

**Measured here:** the behaviour of the loop and the tools against scripted stand-ins (tests below), and that the tools read the real application correctly (`just comparisons-test-live`). **Not measured:** how any real model behaves with these tools, lines of code, speed or cost; those come with the write-up (J8.1d) once all three approaches exist.

**Rule to remember.** *A framework's convenience is a loop someone else wrote. Write it once by hand to know what is inside it.*

## J8.1b: tool calling with Spring AI

The same backend, the same HTTP face, the same two tools, with Spring AI doing the loop: a `ChatClient` over an `OpenAiChatModel`, the tools as `@Tool` methods, and Spring AI 2.0's `ToolCallingAdvisor` that sends the tools, runs what the model asks for, adds the results and asks again. Port 18102. Decisions and every measurement: [ADR 0030](../adr/0030-tool-calling-with-spring-ai.md).

```mermaid
flowchart LR
  face["common: HTTP face"] --> chat["SpringAiToolChat"]
  chat --> client["ChatClient<br/>+ RoundLimitedToolCallingAdvisor"]
  client -->|"tools + messages"| llm["model API (OpenRouter)"]
  client -->|"@Tool methods"| methods["CrmToolMethods"] --> tools["common: CrmTools"]
  client -.->|"errors, unknown tools"| errors["ErrorsAsData"]
```

**What Spring AI gave, without a line of ours** (measured in `SpringAiLibraryBehaviourTests`): the loop itself; the JSON Schema of each tool, written from the method and its annotations; parsing the model's arguments into the method's parameters; running the method and sending the result back; a cap on tool calls (40 per tool and 150 in total by default); a tool that throws becomes a message to the model.

**What it did not give, and what that took** (the table is in the ADR):

| The rule | What Spring AI does | In `withSpringAi` |
| --- | --- | --- |
| At most 4 rounds, then a plain answer | No limit on rounds | A subclass of `ToolCallingAdvisor` (`RoundLimitedToolCallingAdvisor`) |
| Results are data, not instructions | No wrapper; error messages go through unwrapped | A result converter, plus `ErrorsAsData` for the error paths |
| A tool that does not exist | Throws out of the whole question | `ErrorsAsData` as the resolver |
| Bad arguments | A missing one runs the method with `null`; an extra one is ignored; `5` becomes "5"; broken JSON sends Jackson's message | The missing one is refused inside the method; the extra and the wrong type **cannot** be refused (a difference from `handWritten`) |
| One reply may call 4 tools at most | All run | The surplus gets an error as its result (`handWritten` drops it silently) |

**Two differences you would not guess.** Spring AI reports the **sum** of the tokens of every model call in the loop; `handWritten` reports the last call. And the OpenAI SDK under Spring AI **refuses a reply without the full OpenAI envelope** (`id`, `object`, ...), which `handWritten` accepts.

**Versions:** Spring AI 2.0.2, the line the application uses (newest non-RC release on Maven Central; 2.1.0-RC2 is the latest build). Spring Boot 4.1.2 only supplies dependency versions: no Spring application runs here.

**Measured here:** the library's behaviour and ours against scripted stand-ins; each rule has a test, and three of the added pieces were removed on purpose to see a test fail (the round limit, the result wrapper, the unknown-tool resolver). **Not measured:** how a real model behaves with these tools; lines of code, speed or cost (J8.1d); a run of this backend in the browser.

**Rule to remember.** *A framework gives you the loop and takes away the rules around it. Measure which rules you lost before you trust it with a customer's question.*

## Code tour

| | Where | What |
| --- | --- | --- |
| new | `comparisons/pom.xml` | The build of the comparison modules (Java 21, the application's dependency versions) |
| new | `comparisons/common/` | `Chat` (the shared types and the one interface an approach implements), `CrmClient` (the CRM over HTTP), `CrmTools` (the two tools, their schemas, validation), `ToolBackendServer` (the shared HTTP face) |
| new | `comparisons/tool-calling/handWritten/` | `HandWrittenToolChat` (the loop), `HandWrittenMain` (starts it on port 18101) |
| new (J8.1b) | `comparisons/tool-calling/withSpringAi/` | `SpringAiToolChat` (the `ChatClient` call and the manager's settings), `CrmToolMethods` (`@Tool` methods), `RoundLimitedToolCallingAdvisor`, `UntrustedResultConverter`, `ErrorsAsData`, `TurnState`, `WithSpringAiMain` (port 18102); tests `SpringAiLibraryBehaviourTests` (the library alone) and `SpringAiToolChatTests` |
| new | `apps/web/src/api/approaches.ts`, `components/chat/ApproachPicker.tsx`, `hooks/useApproachStatus.ts` | The list of approaches, the buttons, the health check |
| changed | `apps/web/src/api/client.ts`, `chat.ts`, `hooks/useChat.ts`, `context/ChatSessionContext.tsx`, `ChatModeSelector.tsx`, `ChatPanel.tsx` | The tool-calling request can go to another address; the choice is remembered |
| new | `justfile` | `comparisons-test`, `comparisons-test-live`, `comparisons-hand-written`, `comparisons-with-spring-ai` (J8.1b) |
| new | `.github/workflows/ci.yml` | A step that builds and tests the comparison modules |

## Questions to try

1. The model asks for two tools in one reply and the second has bad arguments. What does the user see, and what does the model see? (Read the test `anUnknownToolOrBadArgumentsAreAnsweredWithAnError`.)
2. Why is the "tool result" message wrapped in `<untrusted_context>` tags? What would a document with the text "ignore your instructions" do without them?
3. The application's version of this route has a fallback chain and a prompt guard; this one does not. Which would you miss first in production?
