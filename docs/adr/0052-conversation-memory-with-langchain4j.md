# ADR 0052: conversation memory with LangChain4j (J8.6c)

Status: accepted, 2026-11-19. The LangChain4j path of the conversation-memory comparison. The contract is [ADR 0050](0050-conversation-memory-comparison.md) and is **not changed**: the same `MemoryChat` interface and `MemoryChatBackendServer` (nothing in `comparisons/common` was touched), the same limits, the same tests of behaviour. Code: `comparisons/memory/withLangchain4j` (port 18163), LangChain4j **1.23.0** (the version the other lab modules pin; core library only, no Spring Boot starter).

## Decision

Use what LangChain4j offers for memory, and **measure** (with tests, no live model) what it does and does not do against the contract:

- `ChatMemory` / `MessageWindowChatMemory` over an `InMemoryChatMemoryStore` (a `ChatMemoryStore`) hold the committed conversations; the memory is created with `maxMessages(maxStoredMessages)`, so it bounds what is stored.
- `AiServices` with `@MemoryId` and a `ChatMemoryProvider` puts a memory in front of the model call.
- `TokenWindowChatMemory` is measured (not used): it windows by tokens.

**The shape that was needed.** The textbook wiring (one `AiServices`, one memory per id, all over one store) breaks three rules of the contract (see the table), so the approach gives the model a **throw-away memory per turn**: a new `InMemoryChatMemoryStore` seeded with the system message, the window and the new messages but the last. `AiServices` adds the last user message and the answer to it, and **only after the answer** does the turn append the new messages and the answer to the real store, under the conversation's lock. `@MemoryId` and the provider are therefore used, but they point at a memory that lives for one turn.

## What the library gives, what we wrote (measured by `LibraryMeasurementsTests` and `LangChain4jMemoryChatTests`)

| # | Contract rule | What LangChain4j does | What we wrote |
| --- | --- | --- | --- |
| 1 | Window: last **20 messages AND 16,000 characters** | `MessageWindowChatMemory` windows by **message count**, keeps the system message first, and **evicts for good**: the window **is** the storage (30 added to a window of 20 leaves 20 in the store). It knows no characters (20 messages of 4,000 characters, 80,000 characters, all kept). A narrower window over a wider store trims only the view until it writes, then destroys the rest. `TokenWindowChatMemory` windows by **tokens** (needs a `TokenCountEstimator`: the bundled one is OpenAI's, an estimate for an OpenRouter model): short messages give far more than 20, long ones fewer, and it cannot express "16,000 characters". | `window()`: last 20 of the stored messages, then drop the oldest until the characters fit (same code as handWritten). Neither memory class is used to window what is **sent**. |
| 2 | Stored bound: **200** messages per conversation | `MessageWindowChatMemory(maxMessages = 200)` over the store does it: the oldest are dropped on every `add`. The store alone has **no bound** (500 messages kept as given). | One line: `maxMessages(limits.maxStoredMessages())` on the committing memory. **Free.** |
| 3 | **24 h** time limit after last use; **1,000** conversations, least recently used out | **Nothing.** `InMemoryChatMemoryStore` is a bare concurrent map: no expiry, no size, no list of ids (1,500 ids all stay), an unknown id is an empty list, not "not found". | An index of conversations (last use, a use counter, a `removed` flag), expiry on access and on create, the LRU loop, the injected `Clock`. As in handWritten. |
| 4 | Per-conversation concurrency, **no lock across the model call** | `AiServices` takes no per-id lock: two turns on one id run their model calls **at the same time** (measured). The memory classes are **not synchronised** (no `synchronized` method), so the order and wholeness of the messages two turns add is not guaranteed. `AiServices` also keeps a memory object per id until `evictChatMemory(id)`. | The per-turn memory (nothing shared during the call) and a lock on the conversation **only** around the commit and the read; no lock is held while the model is called. |
| 5 | Delete; delete **during** a turn | `ChatMemoryStore.deleteMessages(id)` / `memory.clear()` delete. `evictChatMemory` only forgets the memory **object**. With the textbook wiring a turn that ends after a delete **writes the conversation back** (measured). | `drop()`: set `removed` and delete the messages **under the conversation's lock**; a turn that finishes later sees `removed` and stores nothing. The answer is still returned. |
| 6 | A failed model call **stores nothing** | `AiServices` adds the **user message to the memory before** the model answers, and nothing takes it back: after a 500 the store holds the unanswered question (measured). | The per-turn memory (the failure is thrown away with it); commit after the answer only. |
| 7 | Content retention | The memory classes and the store keep the messages and nothing else. The chat model logs nothing unless `logRequests` / `logResponses` is turned on (set to false explicitly). A turn writes no text to the console (measured). `AiServices` keeps a memory per id in its own map. | Nothing to write; the per-turn memory is garbage after the turn; `delete` empties the store. |
| 8 | Unknown id: **404 before any model call** | The service does not know about ids that exist: any id gets a fresh, empty memory. A made-up id would simply start a conversation. | The index lookup first (`use`), so an unknown or expired id throws `ConversationNotFoundException` before the model is built. |
| 9 | Observation by `ChatObserver`, counts only | `Result` carries the token usage; no hook of the memory is needed. (`ChatModelListener` exists, see J8.4c.) | `begin`, `modelCall` with the `Result` token counts, `fail("not_found" or "unavailable" or "error")`: the same four lines as handWritten. |

Two more things the tests found that the contract cares about:

- **The system message lands in the wrong place.** With a memory that already holds the history, `AiServices` does not put its `@SystemMessage` first: the prompt came out as history, then system, then question. The per-turn memory is seeded **with the system message first**; `AiServices` does not add a second one (tested).
- **Only the last message goes through the service.** `AiServices.chat` takes one user message, but the contract allows up to 12 new messages (a client may send assistant ones too): the others are seeded into the per-turn memory.

## Size

`LangChain4jMemoryChat` is **330 lines (272 without comments and blank lines)** against handWritten's **237 (184)**. The library saved **one line** (the stored bound) and the time limit, the cap, the character window, the locks and the failure handling all stayed; the AI service and the per-turn memory added more than the library took away. What it does give is the model call, the OpenAI-compatible client, the error classes and the token usage (handWritten writes its own `OpenRouterChat`, which is in `common`, so is not in the 237).

## Consequences and gaps

- The three rules the textbook wiring breaks (a failed call stores the question, no atomic turn, delete is undone) are **why** the memory is per turn. A reader who wires `AiServices` straight onto a shared `ChatMemoryProvider` gets a working chat, and these three bugs.
- The model is built per turn (the model name can change per request); a client-chosen name is therefore not a cache key.
- Not measured: a live model, answer quality, token cost; Spring AI's `ChatMemory` (J8.6b); the three side by side (J8.6d).
- The `SLF4J: No providers` warning at start-up is LangChain4j's own, with no logging binding on the class path (a no-op logger); it is harmless and prints no text.
