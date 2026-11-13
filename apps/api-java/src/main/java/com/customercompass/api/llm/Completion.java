package com.customercompass.api.llm;

import java.util.List;

/**
 * What one model call returned: text and/or tool calls. Tokens are null when the provider did not
 * say.
 */
public record Completion(
    String content, Integer tokensIn, Integer tokensOut, List<ToolCall> toolCalls) {

  public Completion {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  public Completion(String content, Integer tokensIn, Integer tokensOut) {
    this(content, tokensIn, tokensOut, List.of());
  }
}
