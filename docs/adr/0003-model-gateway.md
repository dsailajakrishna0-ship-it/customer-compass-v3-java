# ADR 0003: The model gateway is our own small loop

Status: Accepted (J2.1, 2026-11-07)

## Context

The Python edition calls models through a LiteLLM Router (ADR 0009 there): an ordered fallback chain, no retry on a 429, retries with growing waits on timeouts and server errors, a cool-down for a model that just failed, and an empty answer treated as a failure. The Java edition must behave the same.

## Decision

`ModelGateway` is about 120 lines of plain Java: a loop over the chain, with the retry and cool-down rules written out. It talks to a one-method interface, `ModelClient`, that makes one call to one model and reports a failure as a `ModelCallException` of a kind (rate limit, timeout, server error, auth, bad request, other). The real client (Spring AI, J2.2) only has to map its errors to those kinds.

Alternatives considered:
- **Spring AI alone:** it has retry (Spring Retry) for one model but no ordered fallback across models and no cool-down.
- **Resilience4j** (circuit breaker, retry): the right tool for a service with many callers; for a chain of three free models its configuration is longer than the loop and hides the policy that this project exists to teach.
- **A routing proxy** (LiteLLM proxy, OpenRouter's own routing): another service to run, and it moves the policy out of the code a learner reads.

The gateway is a pure policy: no HTTP, no Spring AI, a `Clock` and a `Sleeper` as seams. That is why its 18 tests need no network, key or model, and read like the Python edition's `test_gateway.py`.

## Behaviour (the same as Python)

| Event | What happens |
| --- | --- |
| 429 | No retry; the model is benched; the next model is tried at once |
| Timeout or 5xx | Retried on the same model, waits 1 s then 2 s (`LLM_RETRY_MIN_SECONDS` doubling), up to `LLM_MAX_RETRIES` (2); then benched and the next model is tried |
| Auth, bad request, other | No retry; benched; next model |
| Empty answer | Counts as a failure of that model without benching it; the next model gets the question; all empty ends in one clear error |
| Benched model | Skipped for `LLM_COOLDOWN_SECONDS` (30), then tried again |
| No key | Empty chain; the message names `OPENROUTER_API_KEY` |
| Everything failed | One error naming the models tried and the last error kind, never a key or a provider message |

One difference from LiteLLM: the doubling of the waits (1 s, 2 s) is explicit here; LiteLLM's exact backoff curve is internal. The Python test only requires "one wait per retry, each longer than the last", which this satisfies.

## Consequences

The cool-down is per process, as in Python. If a second replica ever runs, each keeps its own bench list. Streaming and tool calling are not in the gateway yet; tool calling arrives in J4 with the MCP client.

## Addendum (J2.2): the Spring AI client

`SpringAiModelClient` is the one implementation of `ModelClient`. It uses Spring AI 2.0.2's OpenAI chat model (which in 2.0 sits on the official OpenAI Java SDK) pointed at OpenRouter's OpenAI-compatible endpoint, `https://openrouter.ai/api/v1`.

- **Only the client module (`spring-ai-openai`), not the starter.** The starter's auto-configuration builds the chat model at start-up and demands a key; this application must start without one and say what to set when chat is used. The model is built on the first call.
- **The SDK's own retries are off (`maxRetries(0)`).** Retries and fallback are the gateway's job, and the 429 rule (never retry the same model) would be broken by a hidden retry.
- **One timeout per attempt** from `LLM_TIMEOUT_SECONDS`; the temperature (0.3) and the answer limit (700 tokens) equal the Python edition's.
- **Failures are sorted by what happened, not by message text:** `RateLimitException` or HTTP 429 is a rate limit; 5xx is a server error; 401 and 403 are auth; other 4xx are bad requests; an I/O or timeout error is a timeout. The message that leaves the client names only the failure type, never the key and never the provider's reply body (a test checks both).
- **Tested against a local fake of `/chat/completions`**, so the request that is really sent (key header, model, system prompt first, history in order, `max_tokens`) and every failure kind are checked with no network and no real key.

