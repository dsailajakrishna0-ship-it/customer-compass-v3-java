package com.customercompass.api.llm;

import java.util.List;

/**
 * The answer plus the telemetry behind the usage footer (field names match the API's "usage"), and
 * the tools the model asked to call instead of, or besides, answering (empty for a plain answer).
 */
public record GenerationResult(
    String content,
    String model,
    Integer tokensIn,
    Integer tokensOut,
    long latencyMs,
    Double estimatedCost,
    boolean fallbackUsed,
    String fallbackFromModel,
    List<ToolCall> toolCalls) {

  public GenerationResult {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  public GenerationResult(
      String content,
      String model,
      Integer tokensIn,
      Integer tokensOut,
      long latencyMs,
      Double estimatedCost,
      boolean fallbackUsed,
      String fallbackFromModel) {
    this(
        content,
        model,
        tokensIn,
        tokensOut,
        latencyMs,
        estimatedCost,
        fallbackUsed,
        fallbackFromModel,
        List.of());
  }
}
