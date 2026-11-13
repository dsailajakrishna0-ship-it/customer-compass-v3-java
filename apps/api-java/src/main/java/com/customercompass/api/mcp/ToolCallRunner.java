package com.customercompass.api.mcp;

import com.customercompass.api.llm.ToolCall;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates and runs one tool call from the model. Every failure becomes a tool result the model
 * can read and correct, never an exception; only calls that really ran are recorded in {@code
 * toolsUsed}.
 */
@Component
public class ToolCallRunner {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public String run(
      McpToolClient.Session session,
      ToolCall call,
      boolean userConfirmed,
      List<Map<String, Object>> toolsUsed) {
    if (call.error() != null) {
      return error("Invalid tool call: " + call.error() + ".");
    }
    Map<String, Object> arguments;
    try {
      arguments = new LinkedHashMap<>(ToolSchemas.validate(call.name(), call.arguments()));
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }
    if ("create_followup_task".equals(call.name())) {
      // The model never sets confirm; only the user's own latest message can.
      arguments.put("confirm", userConfirmed);
    }
    var used = new LinkedHashMap<String, Object>();
    used.put("tool", call.name());
    used.put("arguments", arguments);
    toolsUsed.add(used);
    try {
      return session.call(call.name(), arguments);
    } catch (RuntimeException e) {
      return error(String.valueOf(e.getMessage()));
    }
  }

  private static String error(String message) {
    return JSON.writeValueAsString(Map.of("status", "error", "message", message));
  }
}
