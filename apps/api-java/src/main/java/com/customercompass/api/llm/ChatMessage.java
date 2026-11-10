package com.customercompass.api.llm;

/** One chat message; role is "user" or "assistant". */
public record ChatMessage(String role, String content) {}
