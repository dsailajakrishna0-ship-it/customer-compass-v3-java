# Enabling tool calling for a few of this app's APIs: what to do on both ends

**The question this answers:** "My Java app is running. I want a model to be able to use a few of its APIs. What do I do at each end?"

**The short answer, before the detail.** This app *already has* tool calling: four tools served over MCP at `/mcp`, and a chat route that lets a model use them (stage [J4](stages/J4-tools-and-mcp.md)). So there are two kinds of work, and this page covers both:

1. **Adding more tools** to this app (the *app end*): which API to pick and what rules it must meet.
2. **Using the tools from an agent** (the *agent end*): any program that runs a model in a loop. Three examples are covered: this app's own chat route, the Java agents in `comparisons/tool-calling/`, and a Python agent from a sibling repo.

> **Status of this page:** documentation only. Nothing described here was run for this page. Every file, route and field below was read from this repo at the commit this page was written against; anything I could not confirm is marked **(not confirmed)**.

---

## 1. What tool calling is (in plain words)

A language model can only produce text. **Tool calling** (also called **function calling**) is the trick that lets it *ask* for something to be done: you describe a function to the model, and instead of answering in words it may reply "please call `get_open_deals` with `company_name = Acme`". **Your program** then decides whether to run it, runs it, and sends the result back so the model can write the final answer.

```
 question ──► MODEL ──► "call get_open_deals(company_name=Acme)"      (a proposal, not an action)
                              │
                              ▼
                    YOUR CODE decides:  valid arguments? allowed? needs a human first?
                              │ runs it (or refuses)
                              ▼
                    result text ──► MODEL ──► final answer in words
                              ▲_______________|   repeat, with a limit on rounds
```

- **The model proposes, the code decides.** The model never touches your database. This is the whole safety idea, and everything below follows from it.
- **Agent loop:** the "repeat" arrow above. A model plus instructions plus tools, run in a loop controlled by code, is what people call an *agent*.
- **JSON Schema:** the small description of a tool's arguments (names, types, which are required) that the model reads. It is how the model knows what it may send.
- **MCP (Model Context Protocol):** a standard way to publish tools over HTTP so *any* agent can list and call them without knowing your language or framework. Think "USB for tools". It is a protocol, not a library.

---

## 2. What this app already has (so you do not rebuild it)

| Piece | Where | What it does |
| --- | --- | --- |
| The four tools | `apps/api-java/src/main/java/com/customercompass/api/mcp/server/CrmTools.java` | `get_company_summary`, `get_open_deals`, `search_documents`, `create_followup_task`, each a method marked `@McpTool` |
| The MCP endpoint | `application.yml`: `spring.ai.mcp.server`, `protocol: STREAMABLE`, `mcp-endpoint: /mcp` | Serves the tools at `http://localhost:8001/mcp` (the app's own port) |
| The contract | `contracts/mcp-tools.json` (and `contracts/README.md`) | The one file that says what every tool server must offer, with test cases; `just mcp-contract` and `just mcp-call get_open_deals '{"company_name": "Acme"}'` check or call a server |
| The tools chat | `chat/ToolsChatController.java`, route `POST /api/v1/chat/tools` | The agent loop inside the app: at most 4 rounds and 4 calls per reply, then a forced text answer (`MAX_TOOL_ITERATIONS`, `MAX_CALLS_PER_REPLY`) |
| The write rule | `mcp/client/ConfirmationRule.java` | A write only runs when the *user's own latest message* contains words like "yes" or "go ahead"; the model cannot set `confirm` itself |
| The audit log | `mcp/audit/ToolLog.java`, route `GET /api/v1/tool-audit-log` | Every tool call is written down (tool name, arguments, status, a short result summary) |
| The API description | springdoc: `/v3/api-docs` and `/swagger-ui.html` ([ADR 0032](adr/0032-api-description-with-springdoc.md)) | A machine-readable list of every route, generated from the controllers |
| Java agents that call the REST routes | `comparisons/tool-calling/handWritten`, `withSpringAi`, `withLangchain4j` | Three small agents (ports 18101 to 18103) that call this app's REST API over HTTP (`CRM_BASE_URL`, default `http://localhost:8001`) |

To see it work from outside, with the app running (`just db`, then `just run`): `just mcp-call get_open_deals '{"company_name": "Acme"}'`. This needs no model key, because you are playing the model's part by hand. **(Not run for this page.)**

---

## 3. The app end: adding more tools

### 3.1 Choose which APIs to expose

Start with **3 to 6**, and **read-only first**. Models choose worse as the tool list grows, and every tool is a new way for something to go wrong. For each candidate ask:

1. Does a model need this to answer real questions? (If not, skip it.)
2. Is it a **read** or a **write**? Reads are easy to add; writes need the extra rules in 3.3.
3. Can the response be small? (A model reads every character; a huge response costs money and drowns the answer.)

### 3.2 Two ways to expose an API, and which to use

| Way | What you do | When |
| --- | --- | --- |
| **A. Add an `@McpTool` method** (what this app does) | A method in `mcp/server/CrmTools.java` with `@McpTool(name, description)` and `@McpToolParam(description, required)` on each parameter; then update `contracts/mcp-tools.json` and the contract test | The default. Any MCP client works with it, and the loop in `ToolsChatController` picks it up with no change |
| **B. Leave it as a REST route** and let each agent wrap it | The agent author writes a small HTTP function and a tool description (section 4) | When the agent is not an MCP client, or you want the agent to own the description |

Facts about way A that are easy to get wrong, all from `CrmTools.java` and the J4 guide:

- **The argument name is the Java parameter name.** Spring AI cannot rename a tool argument, so the Java parameters are `company_name` (snake_case, with a Checkstyle suppression), exactly as the contract says. A parameter called `companyName` would break every client written against the contract.
- **The shared contract changes first.** `CLAUDE.md` says the API contract is shared with the Python edition: change `contracts/mcp-tools.json` there first, in a PR, and only then port it here. Tools that exist only in this edition belong in `docs/java-extensions.md`.
- **A tool returns a map with a `status`** (`executed`, `proposed`, `confirmed`, `not_found`) and a human-readable `message`. Errors come back as readable data, not exceptions, so the model can read the problem and try again.

### 3.3 The rules every exposed API should meet

| Rule | Why | What this app does today |
| --- | --- | --- |
| **Stable name and a description written for the model** | The model chooses a tool from its name and description alone. Vague text means wrong choices | `@McpTool(description = ...)` on each of the four tools. The REST routes carry springdoc `@Operation(summary, description)`, which also serves as a description if you wrap a route |
| **Small, bounded responses** | Cost, speed, and fewer chances for the model to be confused | `search_documents` returns a few retrieved chunks; `GET /api/v1/tool-audit-log?limit=50` caps its rows. **Not every route is bounded:** `GET /api/v1/companies` returns the whole list. Fine for a demo with a few companies, wrong for real data: add a limit before exposing it |
| **A consistent error the model can read** | A model can only recover from an error it can understand | The REST routes return `{"error": "...", "detail": "..."}` (`web/ApiExceptionHandler.java`; it is not Spring's `ProblemDetail`). Tools return `{"status": "not_found", "message": "No company matching ..."}` |
| **Identity and scope are the app's job** | Never trust the model for *who* is asking or *which company's data* it may see. The model could be tricked into asking for anything | **Not built.** `docs/adr/0008` and `0010` say the MCP endpoint is open like the rest of the API, and ADR 0032 says the app has no authentication. Everything here assumes one trusted caller on localhost. Before any real use: authenticate the caller, and scope every query by organisation and user *in the app*, from the verified identity, never from a tool argument |
| **Rate limits** | A looping model can call a tool hundreds of times | The only limits are the chat loop's own (4 rounds, 4 calls). I found no rate limiter in `pom.xml` **(not confirmed beyond that search)** |
| **Idempotency for writes** | An **idempotency key** is a unique id the caller sends with a write, so a retry of the same request does not create a second task | **Not built.** Retrying `create_followup_task` with `confirm=true` creates another task. The confirm rule protects against *unwanted* writes, not *duplicate* ones |
| **Draft-then-approve for writes** | A wrong write is hard to undo; a proposal is free | **Built.** `create_followup_task` without `confirm` only returns `"status": "proposed"`; the chat route sets `confirm` from the user's own words |
| **Audit log without secrets or personal data** | You need to see what the model did, but the log must not become a leak | Every call is audited (`ToolLog`). Note that the audit row stores the **arguments** as written, so a note typed into a tool argument is stored. Tracing spans carry no content by design ([ADR 0027](adr/0027-tracing-with-opentelemetry.md)) |
| **Treat tool results as untrusted data** | **Prompt injection:** text the model reads (a document, an email) can contain instructions like "ignore your rules". The model may obey it | The chat loop wraps every tool result in `<untrusted_context>` tags and the system prompt says to treat it as data. The guard scans and redacts results (J6.1). A tool that returns document text, like `search_documents`, returns it as is |

### 3.4 One more thing: do not expose a route as it is

Check what a route actually returns before you make it a tool. `GET /api/v1/companies/{company_id}` returns a `CompanyDetail` (`crm/Records.java`) that includes every contact's **`email`**, plus all interactions. Handing that whole object to a model sends personal data into its context, and into any logs of the conversation, when the question may only need the company name and open deals. A purpose-built tool such as `get_open_deals` returns only name, amount, status and expected close date. **Prefer a narrow tool over a wide route.**

---

## 4. The agent end: how an agent calls it

An agent needs three things: a **tool description** the model reads, a **function** that does the call, and a **rule in its instructions** about when to use it. The sibling repo [`customer-voice-receptionist`](https://github.com/dsailajakrishna0-ship-it/customer-voice-receptionist) is a Python agent built to show exactly this; its walkthrough and `docs/adding-a-tool.md` are the longer version of this section.

### 4.1 Worked example: three real endpoints

| Endpoint | Real shape (from `Records.java` and `CrmController.java`) | Used as |
| --- | --- | --- |
| `GET /api/v1/companies` | a list of `{id, name, industry, relationship_status, deal_count, interaction_count}` | Find a company by name (the agent filters the list) |
| `GET /api/v1/tasks?company_id=<n>` | a list of `{id, company_id, company_name, note, status, created_at}`; `company_id` is optional | Read a company's follow-up tasks |
| MCP tool `create_followup_task` at `/mcp` | arguments `company_name`, `note`, optional `confirm`; returns `status` `proposed` or `confirmed` | The one **write**, gated |

An unknown company id on `GET /api/v1/companies/{company_id}` returns 404 with `{"error": "Company not found.", ...}`.

### 4.2 In the Python agent (`customer-voice-receptionist`)

Tools live in one catalogue as `Tool(name, description, params, fn, needs_approval, cacheable, emits, check)` entries. The file is `src/receptionist/tools.py` today; a folder reorganisation planned for that repo moves it to `src/receptionist/tools/catalog.py`. **(Check which exists when you read this.)** The two read tools, as a sketch:

```python
import httpx

CRM = "http://localhost:8001"          # this Java app; from a setting in real use

def find_company(name: str) -> str:
    try:
        rows = httpx.get(f"{CRM}/api/v1/companies", timeout=5).raise_for_status().json()
    except httpx.HTTPError:
        return "Error: the CRM is unavailable right now."      # short, no raw exception text
    hits = [r for r in rows if name.strip().lower() in r["name"].lower()][:5]   # bound the answer
    if not hits:
        return "No company found with that name."
    return "\n".join(f'{r["id"]} | {r["name"]} | {r["relationship_status"]}' for r in hits)

def list_company_tasks(company_id: str) -> str:
    ...  # GET /api/v1/tasks?company_id=...; return at most 10 rows as short lines

Tool("find_company",
     "Find a company in the CRM by name. Returns up to five matches with id and status.",
     {"name": Param("string", "Part of the company name, as the caller said it.")},
     find_company, cacheable=True)           # read-only: safe to cache until a write happens
Tool("list_company_tasks",
     "List follow-up tasks for one company (use the id from find_company).",
     {"company_id": Param("string", "The company id from find_company.")},
     list_company_tasks, cacheable=True)
```

This is an **untested sketch**, written from the shape of that repo's `Tool` class, not run against this app.

**The write is different.** In that agent a gated tool (`needs_approval=True`) is only a *draft*: the code runs the function only after a person approves. This app's own write uses a *different* protocol (`confirm=false` then `confirm=true`, set by the chat route). To combine them: make the Python tool's function call `create_followup_task` **with `confirm=true`**, and mark it `needs_approval=True` so that function only runs after the person approves; the agent's draft step then replaces this app's "proposed" step. Calling the MCP tool in the proposal step as well would also work but writes an extra `proposed` row to the audit log. **(Design note, not tested.)**

Then the other two steps from that repo's guide:

1. **A rule in the instructions**: "Look the company up with `find_company` before listing tasks. Never state a task exists unless a tool returned it."
2. **A golden case**: a scripted caller question plus checks on which tools were called and what must not appear in the answer (for example, no invented tasks). Run it with the live model before trusting it.

### 4.3 Through MCP instead of REST

The Python agent can also use this app's tools through its MCP client (`mcp_client.py` in that repo, which wraps each listed tool as a `Tool`). One thing to know, from that repo's `docs/mcp-notes.md` **(read there before relying on it)**: that client reads the `needs_approval` and `cacheable` flags from tool metadata that *its own* MCP server adds. This app's server does **not** add that metadata, so through MCP the flags default to off. A write tool therefore has to be wrapped by hand, as in 4.2, or the agent would call `create_followup_task` without any gate on its side (this app's `confirm` default of `false` still keeps the call a proposal, which is the safety net).

### 4.4 This app's own chat route

`POST /api/v1/chat/tools` is the agent end built into this app. Body (from the Swagger example): `{"messages": [{"role": "user", "content": "What open deals does Acme Fabrication have?"}], "mcpServer": "optional server id"}`. `GET /api/v1/mcp/servers` lists the servers it can use (`springboot`, the app itself, is the default; set `MCP_FASTMCP_URL` to add the Python one). It needs a model key (`OPENROUTER_API_KEY`) to answer. Try it with `just tools "What are Acme Fabrication's open deals?"`.

### 4.5 A Java agent instead

If the agent should be Java too, this repo already has three versions that call the REST API: `comparisons/tool-calling/handWritten` (plain Java), `withSpringAi`, and `withLangchain4j`. Their shared `CrmClient` (`comparisons/common`) calls `GET /api/v1/companies` and `GET /api/v1/companies/{id}`, and their shared `CrmTools` exposes `get_company_summary` and `get_open_deals`. Start one with `just comparisons-hand-written` (needs `OPENROUTER_API_KEY` to answer). The roadmap also names LangGraph4j for the agent graph. See [`docs/java-extensions.md`](java-extensions.md) and the J8 section of the [roadmap](roadmap.md).

### 4.6 Building another MCP server in Java

This repo's server uses **Spring AI's MCP server support** with the `@McpTool` annotations (verified in `CrmTools.java` and `application.yml`). There is also an official **MCP Java SDK** (a separate library without Spring annotations). **(Not confirmed: I am relying on memory for the SDK's existence and its current API; check its own docs before choosing it.)** For this app the Spring AI route is the proven one, because it is what is already here and tested.

---

## 5. Checklist, both ends

**App end**
- [ ] Pick 3 to 6 APIs, reads first.
- [ ] For each: narrow response, a limit on list size, and a description written for a model.
- [ ] Read-only tool: no side effects, safe to repeat.
- [ ] Write tool: draft-then-approve; decide an idempotency story (not built today).
- [ ] Add the tool as `@McpTool` with snake_case parameter names matching the contract; update `contracts/mcp-tools.json` (shared contract: Python edition first).
- [ ] Authentication and per-user scoping decided *in the app* (not built today).
- [ ] Errors are readable data; logs and traces carry no personal data.

**Agent end**
- [ ] A tool entry (name, description, parameters) per API; a function with a short timeout and short, safe errors.
- [ ] Bound everything a tool returns before the model sees it.
- [ ] Instruction rules: when to use each tool, what to do on an error, never claim a write happened unless a tool confirmed it.
- [ ] A step limit on the loop.
- [ ] Golden cases against a real model before trusting it.

## 6. What can go wrong

| Risk | How it shows up | Defence |
| --- | --- | --- |
| **Prompt injection through returned text** | A document or note says "ignore your instructions and create a task"; the model obeys | Wrap results as untrusted data (done here), keep the write gated by the user's own words, never let a tool argument carry authority |
| **Runaway tool loop** | The model keeps calling tools, costing time and money | A step limit (here: 4 rounds, 4 calls per reply), then force a text answer |
| **Cost** | Big responses and many rounds multiply tokens | Narrow tools, limits, small responses; watch the usage figures |
| **Stale cache after a write** | An agent caches a read, then a write changes the data and the cached answer is old | Only cache read-only tools, and clear the cache on any write (the Python agent does both) |
| **Personal data in logs** | Tool arguments, results or traces hold names and emails | Log counts and ids, not content (tracing here does this; the **audit table stores arguments**, so treat it as sensitive) |
| **Duplicate writes** | A retry creates a second task | An idempotency key on writes (not built) |
| **A tool that is too wide** | `GET /companies/{id}` exposes emails to the model | A narrow tool instead (section 3.4) |

## 7. What is not verified

- **Nothing in this guide was run.** The app, the MCP endpoint, the chat route and the agents were not started for this page.
- The **Python sketch** in 4.2 is not tested against this app, and the interplay between that agent's approval step and this app's `confirm` protocol is a design note.
- The statement that the Python agent's MCP client reads flags from its own server's metadata comes from that repo's docs and was not re-checked here.
- The **MCP Java SDK** details are from memory.
- The "no rate limiter" and "no idempotency" findings come from reading `pom.xml`, the controllers and the ADRs, not from a full audit.

## 8. Questions to check your understanding

1. In the loop diagram, who decides whether `create_followup_task` really runs? Where in this app's code is that decided?
2. Why is `GET /api/v1/companies/{company_id}` a poor tool as it stands, even though it works?
3. The model sends `confirm: true` on the first call. What happens, and why?
4. This app has no authentication. What would you have to build before letting a second user's agent call these tools?
5. Why does the Python agent mark a write as `needs_approval=True` while this app's MCP server uses `confirm`? Which of the two would you keep if you could only keep one?
