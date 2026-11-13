package com.customercompass.api.llm;

import java.util.Map;

/**
 * One tool the model asked to call. {@code error} is set (and {@code arguments} empty) when the
 * model's arguments were not a valid JSON object, so the caller can tell the model instead of
 * crashing.
 */
public record ToolCall(String id, String name, Map<String, Object> arguments, String error) {

  public ToolCall(String id, String name, Map<String, Object> arguments) {
    this(id, name, arguments, null);
  }
}
