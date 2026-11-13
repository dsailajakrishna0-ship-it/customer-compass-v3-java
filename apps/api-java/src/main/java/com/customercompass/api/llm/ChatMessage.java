package com.customercompass.api.llm;

import java.util.List;

/**
 * One chat message. The role is "user" or "assistant" for ordinary text. For tool use there are two
 * more shapes: an "assistant" message that asked for tools (its {@code toolCalls}), and a "tool"
 * message that carries a tool's answer back ({@code toolCallId} says which call it answers).
 */
public record ChatMessage(
    String role, String content, List<ToolCall> toolCalls, String toolCallId, String toolName) {

  public ChatMessage {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  public ChatMessage(String role, String content) {
    this(role, content, List.of(), null, null);
  }

  /** The assistant's turn that asked for tools (its text is often empty). */
  public static ChatMessage assistantToolCalls(String content, List<ToolCall> calls) {
    return new ChatMessage("assistant", content == null ? "" : content, calls, null, null);
  }

  /** A tool's answer, tied to the call that asked for it. */
  public static ChatMessage toolResult(String toolCallId, String toolName, String content) {
    return new ChatMessage("tool", content, List.of(), toolCallId, toolName);
  }
}
