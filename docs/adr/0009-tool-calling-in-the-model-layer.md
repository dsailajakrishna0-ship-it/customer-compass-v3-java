# ADR 0009: tool calling in the model layer (J4.2)

Status: accepted, 2026-11-13.

## Context

`POST /chat/tools` (J4.3) needs a model that can ask for tools: the chat loop offers the four MCP tools, the model answers with "call `get_open_deals` with `company_name` = Acme" instead of text, the loop runs the tool and sends the result back, and the model writes the final answer. The model layer so far handled text only. The Python edition's gateway already supports this (a `tools` list and a `tool_choice` on every call, parsed tool calls on the result).

## Decision

- **Our own small types carry tools through the gateway:** `ToolDefinition` (name, description, the JSON Schema of the arguments), `ToolCall` (id, name, arguments, and an `error` when the arguments are not a JSON object), and two more message shapes in `ChatMessage` (an assistant turn that asked for tools, and a tool result tied to its call). `Completion` and `GenerationResult` gain a `toolCalls` list. The gateway and its tests stay free of Spring AI types.
- **Spring AI only carries the request and the reply.** In Spring AI 2.0.2 the OpenAI chat model sends the offered tool definitions and returns the model's tool calls; it never runs a tool itself (execution lives in a different layer that we do not use). So the client offers each tool as a callback that is never called, and the chat loop runs the tools through MCP. This also keeps the confirm rule where it belongs: in our code, decided from the user's words.
- **Two ways to call the client:** the plain call is unchanged (existing fakes and tests keep working), and a second call takes the tools and a tool choice, `auto` (the model decides) or `none` (answer in text, used to force the final answer after the tool limit). A client that cannot offer tools refuses instead of silently ignoring them.
- **A tool call is a valid answer, even with no text.** The gateway's rule for a failed attempt is now: no text *and* no tool calls, or raw tool-call markup. Failed attempts move to the next model without benching it, as before.
- **Raw tool-call markup is a failed attempt** (the Python edition's rule from its PR #67, ported here): a small model that prints `<|tool_call_start|>...` as text instead of making a structured call gets skipped, and if every model does it the error says "The model returned an unusable reply". A real tool call wins even when the text looks like markup; text that merely mentions the token is kept.
- **Bad arguments do not crash the loop.** If the model's arguments are not valid JSON, or not an object, the call comes back with an `error` and empty arguments, so the loop can tell the model what was wrong.

## What the first real runs showed

Against live OpenRouter models, a two-turn exchange worked end to end: the model made a structured call (`get_open_deals`, `company_name` = Acme Fabrication), received the tool result as a `tool` message and, with tool choice `none`, wrote the final answer from it. Nemotron did both turns; the 2.6B default model made the call too (its second turn hit the free tier's rate limit); Gemma was rate-limited. Tool-call quality varies by model, which is why the chain and the fallback rules matter more here than for plain chat.

## Consequences

- J4.3 can build the loop on one gateway method: `generate(system, messages, model, tools, toolChoice)`.
- The tool schemas the model sees come from the MCP tool list (J4.3), minus `confirm`, which the model must not set.
- Streaming is still not supported, as before.
