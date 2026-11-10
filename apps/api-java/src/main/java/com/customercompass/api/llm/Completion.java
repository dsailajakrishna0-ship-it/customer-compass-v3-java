package com.customercompass.api.llm;

/** What one model call returned. Tokens are null when the provider did not say. */
public record Completion(String content, Integer tokensIn, Integer tokensOut) {}
