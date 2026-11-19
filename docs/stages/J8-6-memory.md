# Stage J8.6: conversation memory, built several ways

Plan: [`docs/roadmap.md`](../roadmap.md) ("Stage J8"). Decisions: [ADR 0050](../adr/0050-conversation-memory-comparison.md) (the contract and the hand-written path), [ADR 0051](../adr/0051-conversation-memory-with-spring-ai.md) (Spring AI), [ADR 0052](../adr/0052-conversation-memory-with-langchain4j.md) (LangChain4j). Code: [`comparisons/memory/`](../../comparisons/README.md). One section per approach (J8.6a `handWritten`, J8.6b `withSpringAi`, J8.6c `withLangchain4j`) and, last, the three side by side.

## In one minute

A model has **no memory**: each request is a new meeting. To hold a conversation, someone must hand it the minutes of the last meeting every time. Think of a receptionist who keeps a folder per visitor: before each visit they take the last few pages out and put them in front of the doctor, and after the visit they file the new pages. The visitor only says "I'm back, folder 7".

| Shop idea | Project idea | Where |
| --- | --- | --- |
| A folder per visitor, with a number on it | a **conversation** with a random **id** | `HandWrittenMemoryChat.create` |
| The last few pages go to the doctor | the **window**: last 20 messages, at most 16,000 characters | `HandWrittenMemoryChat.window` |
| New pages are filed after the visit | the **turn** is stored only after the model answered | `HandWrittenMemoryChat.turn` |
| Folders not touched for a day are shredded | the **time limit**: 24 hours after the last use | `expired` |
| The cabinet holds 1,000 folders | the **cap**: the least recently used goes | `create` |
| The receptionist does not wait with the folder locked while the doctor thinks | **no lock across the model call** | `turn` |

## J8.6a: memory by hand

No AI framework: a map, two kinds of lock, a clock and a hand-written model call. Port 18161.

```mermaid
flowchart LR
  turn["POST /chat/memory<br/>{id, new messages}"] --> find{"found and<br/>not expired?"}
  find -->|no| nf(["404, model not asked"])
  find -->|yes| win["copy the window<br/>(lock, then release)"] --> model["one model call<br/>(no lock held)"] --> store["append the pair<br/>(lock again)"] --> answer(["answer + counts"])
  model -->|fails| nothing(["503, nothing stored"])
```

**The rules the code enforces** (each has a test):
- A turn stores the user message then the answer, and answers with `messagesUsed` (what the model saw from the past) and `messagesStored`.
- The history goes **before** the new messages, after the system message.
- A **failed** model call stores nothing; **no key** means no call and nothing stored.
- **Only the last 20** stored messages are sent; the oldest are dropped until the characters are at most 16,000 (exactly 16,000 is kept). All stay stored.
- A conversation expires **24 hours after its last use**: a turn extends it, a read does not; expired ones are removed when another is created.
- Creating beyond **1,000** removes the **least recently used**; using one protects it, reading does not.
- Delete is immediate; an unknown id is 404 and the model is not asked; two conversations never see each other.
- Two turns on one conversation at once each store their pair whole; **a turn blocked inside the model call does not block** reads, creates or deletes; a conversation deleted mid-turn stays deleted.
- Bad bodies are 422 before the model; errors never repeat an exception's own text; observation records counts, never text.

**Measured vs not measured.** Measured: every rule above, against a scripted model and a movable clock, plus a real start of the backend. **Not measured:** a live model, answer quality, token cost (they need the owner's go).

**Rule to remember:** **the model remembers nothing; whoever keeps the history decides what it sees, so the window, the time limit and the lock are your code's job.**

## J8.6b: memory with Spring AI

Spring AI ships memory classes, so the question is how much of the contract they cover. Port 18162, the same HTTP face. Think of the folder cabinet again: the library gives you folders that keep their newest pages and a clerk (the **advisor**) who clips the pages in front of the doctor. It does not give you the label with the date, the 1,000-folder rule, a lock on the folder, or a promise that a visit that failed leaves no notes.

| Rule | Library | Us |
| --- | --- | --- |
| Newest 20 and at most 16,000 characters | count only, trims on write not read | `TurnMemory.get` |
| At most 200 stored | **free** (`MessageWindowChatMemory(200)`) | pass the number |
| 24 hours and 1,000 cap | nothing | registry, clock, eviction |
| Concurrency, no lock in the call | no lock; pairs get split under a race, and a message can be lost or repeated | one lock for the store only |
| Delete during a turn | the advisor writes the answer back | `removed` flag |
| Failed call stores nothing; all new messages stored | user message stored first; only the last one | the advisor only reads; we store the turn after success |
| Unknown id is 404 | an empty conversation, model called | registry check first |
| Observation, counts only | not part of it | `ChatObserver` |
| History before new messages | **free** | nothing |

The surprise: **the Spring AI class is longer (301 lines) than the hand-written one (237)**, because the registry and the rules are the same code and the library adds translation. `SpringAiLibraryBehaviourTests` (12 tests) shows each library behaviour alone; `SpringAiMemoryChatTests` (25) and `ObservedAndServedTests` (5) show the contract met. Full table and reasoning: [ADR 0051](../adr/0051-conversation-memory-with-spring-ai.md). The cross-approach write-up is J8.6d.

**Rule to remember:** **a library memory stores messages; it does not own a conversation's life, so read what the advisor writes, and when, before trusting it.**

Code: `comparisons/memory/withSpringAi/.../SpringAiMemoryChat.java` and `WithSpringAiMemoryMain.java`.

## J8.6c: memory with LangChain4j

Decisions: [ADR 0052](../adr/0052-conversation-memory-with-langchain4j.md). Port 18163, `just comparisons-memory-langchain4j`. Same HTTP face, same limits, same tests as the hand-written path; the question is what the **library's memory classes** do for free.

In shop terms: LangChain4j sells a **folder cabinet** (`InMemoryChatMemoryStore`) and a **page counter** (`MessageWindowChatMemory`) that throws away the oldest pages when the folder is too thick. It does not shred old folders, does not limit the cabinet, counts pages not letters, and its receptionist (`AiServices`) files the visitor's question **before** the doctor has answered. So we hand the doctor a photocopy for each visit and file the real pages afterwards.

| Rule | LangChain4j gives | We still write |
| --- | --- | --- |
| Window of 20 messages **and** 16,000 characters | A window by **message count** (`MessageWindowChatMemory`) or by **tokens** (`TokenWindowChatMemory`); the window also **is** the storage | The character window |
| 200 stored messages | `MessageWindowChatMemory(maxMessages = 200)` over the store | One line |
| 24 h time limit, 1,000-conversation cap | Nothing | All of it, plus an index of ids |
| Concurrent turns, no lock across the model call | Nothing (no lock, memory not thread-safe) | A per-turn memory and a lock around the commit |
| Delete, also during a turn | `deleteMessages` (a finished turn writes it back) | The `removed` flag under the lock |
| A failed call stores nothing | The question is stored **before** the answer | A throw-away memory per turn, committed after the answer |
| Unknown id is 404 | Any id gets a fresh memory | The index lookup first |

`LangChain4jMemoryChat` is 330 lines, against 237 by hand. **Rule to remember:** **a library memory is a storage and a count, not a conversation policy; time limits, caps, atomic turns and locks stay your code.**

Measured by `LibraryMeasurementsTests` (the library alone, the "textbook wiring") and `LangChain4jMemoryChatTests` / `ObservedAndServedTests` (the contract, the same tests as the hand-written path). Not measured: a live model, answer quality, token cost.

## Code tour

- Shared: `comparisons/common` has `MemoryChat` (the contract), `MemoryLimits`, `OpenRouterChat` and `MemoryChatBackendServer`.
- By hand: `comparisons/memory/handWritten/.../HandWrittenMemoryChat.java` (the store and the turn) and `HandWrittenMemoryMain.java` (start-up).
- LangChain4j: `comparisons/memory/withLangchain4j/.../LangChain4jMemoryChat.java` and `WithLangchain4jMemoryMain.java`; tests `LangChain4jMemoryChatTests`, `LibraryMeasurementsTests`, `ObservedAndServedTests`.
- Tests: `HandWrittenMemoryChatTests` (the rules), `ObservedAndServedTests` (HTTP and signals), with `FakeModel` and `MutableClock`.

## Questions to try

1. Tell it a name, ask for the name later: where does the second answer get it from? (look at `messagesUsed`)
2. Send 13 turns: how many past messages does the 13th use? Does `GET` still show them all?
3. Why does a read not extend the 24 hours, but a turn does?
4. What would break if the lock were held during the model call?
