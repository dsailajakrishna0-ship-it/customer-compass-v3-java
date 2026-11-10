package com.customercompass.api.llm;

/** The answer plus the telemetry behind the usage footer (field names match the API's "usage"). */
public record GenerationResult(
    String content,
    String model,
    Integer tokensIn,
    Integer tokensOut,
    long latencyMs,
    Double estimatedCost,
    boolean fallbackUsed,
    String fallbackFromModel) {}
