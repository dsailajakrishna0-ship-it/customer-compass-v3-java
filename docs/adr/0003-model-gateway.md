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
