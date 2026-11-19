# ADR 0051: conversation memory with Spring AI (J8.6b)

Status: accepted, 2026-11-19. Same contract as [ADR 0050](0050-conversation-memory-comparison.md); this records what Spring AI 2.0.2's memory classes give for free and what had to be written around them. Every row below is backed by a test: `SpringAiLibraryBehaviourTests` measures the library alone, `SpringAiMemoryChatTests` and `ObservedAndServedTests` prove the contract on our class.

## Context

Spring AI has memory classes: `ChatMemory` (add, get, clear), `MessageWindowChatMemory` (keeps the newest `maxMessages`), `ChatMemoryRepository` with `InMemoryChatMemoryRepository` (a map of id to messages), and `MessageChatMemoryAdvisor`, which a `ChatClient` runs around the model call to put the history in front of the new messages (the conversation id is the advisor parameter `ChatMemory.CONVERSATION_ID`). The class and method names were read from the 2.0.2 jars (`spring-ai-model`, `spring-ai-client-chat`), not from memory.

## Decision

`SpringAiMemoryChat` (port 18162, the same `MemoryChat` and `MemoryChatBackendServer` as handWritten; **common is unchanged**) is a `ChatClient` over `OpenAiChatModel` with `MessageChatMemoryAdvisor`. Two things in it are not the textbook setup:

1. The advisor is given a **per-turn read-only memory** (`TurnMemory`): `get` returns the stored messages cut to the newest 20 and then to 16,000 characters; `add` is ignored. We store the turn ourselves, after the model answered (reason: findings 1, 5 and 6 below).
2. The real memory is `MessageWindowChatMemory(maxMessages = 200)` over `InMemoryChatMemoryRepository`: the **storage bound is the library's own trimming**.

## Findings: what the library gives, what we wrote

| # | Contract rule | What Spring AI does (test) | What we wrote |
| --- | --- | --- | --- |
| 1 | Window: newest 20 messages **and** at most 16,000 characters | `MessageWindowChatMemory` windows by **message count only** (`theWindowIsByMessageCountOnlyAndKnowsNothingOfCharacters`: 40,000 characters came back). It also trims **when it writes, not when it reads** (`theWindowTrimsWhenItWritesNotWhenItReads`: `get` returned 10 of a window of 4) | `TurnMemory.get`: take the last 20, drop the oldest until the characters fit (about 8 lines) |
| 2 | At most 200 stored messages | **Free**: the same class trimmed on `add` keeps the newest N (`theSameClassBoundsWhatIsStored...`); the bound is `maxMessages = 200` | Nothing but passing `MemoryLimits.maxStoredMessages()` |
| 3 | 24-hour limit after last use, 1,000-conversation LRU cap | **Nothing**: the repository has four methods (ids, find, saveAll, delete), no time, no count, no eviction (`nothingExpiresAndNothing...`: 1,500 conversations stayed; `theRepositoryOffersNoTime...`) | A registry (`Entry` with last use and a use counter, the injected `Clock`), expiry on access and on create, eviction of the smallest counter: the same logic as handWritten |
| 4 | Per-conversation concurrency, no lock across the model call | No lock in the advisor or the memory: two turns are in the model call at once (good), but each stores its **user message before** its answer, so the stored order is `A, B, answer A, answer B`, **the pairs are split** (`twoTurnsOnOneId...`). `add` is also a read, modify, write of a whole list with no lock, so the race can **lose a user message or an answer, or repeat one** (seen in the build: 3 stored messages, `user: B` twice, a list starting with an answer), and the test only checks what always holds | One lock per conversation around the store only; the advisor never writes, so the model call holds no lock and each pair is stored whole, after success |
| 5 | Delete, and delete during a turn | `clear(id)` works, but a turn that is in the model call **writes the conversation back** when the advisor stores the answer (`aConversationClearedDuringATurn...`) | A `removed` flag on the registry entry, checked under the lock before the store; the turn still answers, stores nothing (`messagesStored` 0) |
| 6 | A failed model call stores nothing; all 1 to 12 new messages are stored | The advisor stores the user message **before** the call, so a failed call leaves it (`aFailedModelCallStillLeavesTheUserMessageStored`). It stores only the **last** user message of the request (`theAdvisorStoresOnlyTheLastUserMessage...`: of three new messages one was kept) | `TurnMemory.add` is a no-op; after success `storage.add(id, new messages + answer)` in one call |
| 7 | Content retention: nothing logs or keeps text beyond the store | The repository keeps the message objects in a map (that is the store). The advisor and memory classes write no log lines of their own; the lab has no SLF4J provider (the start-up prints the usual "no providers, NOP logger" notice, as the other lab backends do), so nothing is logged at all | Nothing; the observation rule is finding 9 |
| 8 | Unknown id is 404 before any model call | **Not an error**: `get` of an unknown id is an empty list and the advisor calls the model and creates the conversation (`anUnknownConversationIdIsNotAnError...`) | `use(id)` in the registry throws `ConversationNotFoundException` before the key check and before the model |
| 9 | Observation by counts only, never content | Not part of the memory classes; the model call has Spring AI's own observation (J8.4b) | `ChatObserver` called directly around the turn (`begin`, `modelCall`, `fail`), the same five tests as handWritten pass, including "the text never appears" |
| 10 | History before the new messages, system message first | **Free** (`theAdvisorPutsTheHistoryBefore...`): `system, history, new` | Nothing |

**Size.** `SpringAiMemoryChat` is **301 lines** (251 without blanks and comments), `handWritten`'s class is 237 (184). The library saves the storage bound, the prompt assembly and the message objects, and costs more than that back: the registry, the expiry and cap logic are the same as by hand, plus `TurnMemory`, the translation between our messages and Spring AI's, and the OpenAI client set-up. **For this contract Spring AI's memory is not shorter code; it is a smaller part of the problem than it looks.** The advisor is the part that fits least: it writes at the wrong time (before the answer, last user message only, no lock), so the safest use is to let it *read* only.

## Consequences

- If the application used the stock `MessageChatMemoryAdvisor` it would get windowing and storage but not the contract's character rule, atomic turns, time limit, cap, 404 or delete safety. Each of those is code, and findings 4 to 6 are subtle (they pass a single-user demo).
- `MemoryChat` and `MemoryChatBackendServer` needed no change. The test helpers `FakeModel` (with the full OpenAI reply envelope, which the OpenAI SDK requires) and `MutableClock` are copies of the handWritten ones: a shared test-jar would add a module for two small files.
- Not measured: a live model, answer quality, token cost, a durable repository (JDBC and others exist in Spring AI; they would change findings 2 and 3 only if they expire or evict, which the interface does not offer).
