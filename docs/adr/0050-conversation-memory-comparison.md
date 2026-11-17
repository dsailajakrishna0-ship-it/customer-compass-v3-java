# ADR 0050: conversation memory in the lab, the shared contract and the hand-written path (J8.6a)

Status: accepted, 2026-11-18. The lab version of the conversation-memory contract of the Python edition (ADR 0036 in `customer-compass-v3`). The three approaches (**handWritten** here, **withSpringAi** and **withLangchain4j** next) serve the same HTTP face and obey the same rules, so that only **who writes the memory** differs.

## Context

A chat model remembers nothing: every request must carry the conversation so far. Either the client keeps the history and resends it (simple, but the client decides what the model sees) or the **server keeps it** and the client sends only the new message. The Python edition chose the server, keyed by a `conversationId`. The question of this slice: what does it take to do that by hand in Java, so that the two library versions can be judged against it?

## Decision: the shared contract (`comparisons/common`)

- **Routes** (JDK server, `MemoryChatBackendServer`): `POST /api/v1/conversations` (201, random UUID v4 and `expiresAt`); `POST /api/v1/chat/memory` with `{conversationId, messages, model}`; `GET` and `DELETE /api/v1/conversations/{id}` (204 on delete); `GET /health`; `GET /api/v1/observability/recent` when observation is on. Unlike the application, the lab **requires** the id on every turn and serves plain chat only (no RAG, no tools).
- **With an id, `messages` are the NEW messages**, 1 to 12, roles `user` and `assistant`, content 1 to 4,000 characters, the last from the user. Anything else is 422 before any model call.
- **The window** (what the model sees): the **last 20 stored messages**, then the **oldest dropped until the history is at most 16,000 characters**. **Storage is bounded too:** a conversation keeps at most **200 messages** (`MEMORY_MAX_STORED_MESSAGES`); when a turn is appended the oldest are dropped. The window only limits what is sent.
- **A turn** is stored only after the model answered: the new messages and the answer, in that order, together. A failed call stores nothing.
- **Unknown or expired id**: 404 "Conversation not found or expired." and the model is not asked. An id that cannot be one of ours is also a 404.
- **Time limit**: 24 hours after the last **use**; a turn extends it, a read does not. **Cap**: 1,000 conversations; creating one beyond the cap removes the **least recently used**.
- **Never content in observation**: spans and metrics hold counts and outcomes only.
- **Concurrency**: no lock is held across the model call (see below).
- All limits are `MemoryLimits` (environment: `MEMORY_MAX_MESSAGES`, `MEMORY_MAX_CHARS`, `MEMORY_TTL_HOURS`, `MEMORY_MAX_CONVERSATIONS`). The model client is `OpenRouterChat` (one POST to `/chat/completions`, key in a header, status-only errors, no key means no call).

## Decision: the hand-written path (`comparisons/memory/handWritten`, port 18161)

`HandWrittenMemoryChat` is 232 lines. What had to be written by hand:

| Need | How it is done by hand |
| --- | --- |
| The store | A `HashMap` of conversation id to `Stored` (messages, last use, a use counter), behind one lock for the map and one lock per conversation |
| The window | `window(...)`: take the last 20, then drop from the oldest until the characters fit |
| The time limit | `expired` compares `lastUsed + ttl` with the injected `Clock`; expired entries are removed when a conversation is created, and checked on every access |
| The cap and LRU | A use counter (not the clock, which can tie); on create, evict the smallest counter while the store is full |
| The turn | Copy the window under the conversation lock, **release it**, call the model, take the lock again and append |
| Delete during a turn | A `removed` flag: a turn that finishes after the delete answers but stores nothing, and the conversation stays gone |
| Observation | `ChatObserver` around the turn: `begin`, `modelCall`, `fail("not_found" or "unavailable" or "error")`, `close` |

**No lock across the model call**: a slow model must not block reads, creates or other conversations. The price: two turns on one conversation at the same moment both read the history as it was, and each stores its own pair whole (the pairs are never split, but their order is the order they finish). That matches ADR 0036.

## Known gaps (stated, not hidden)

- **A conversation's stored messages are bounded** (200 by default, the oldest dropped on append), because the time limit alone does not bound them: every use extends it, so a client could otherwise grow one conversation without limit. This was found in review of the first version of this PR and the same bound was added to the Python contract (ADR 0036).
- **There is no prompt guard in the lab**, so the text the model sees is exactly what was stored; the application's neutralising step is not part of this comparison.
- **Not durable**: a restart forgets every conversation. The application keeps them in Postgres; the lab keeps them in memory on purpose, to compare the memory mechanisms, not the storage.
- **Not measured here**: a live model, answer quality, token cost. The tests use a scripted model.

## Consequences

The two library approaches have to meet the same tests of behaviour (window, limits, atomic turn) through their own memory classes (Spring AI `ChatMemory`, LangChain4j `ChatMemory`), and each rule they do not give for free (time limit, cap, a window by characters, a per-conversation lock) shows up as code written around the library. That comparison is J8.6d.
