package com.customercompass.api.mcp.client;

import com.customercompass.api.llm.ToolCall;
import com.customercompass.api.mcp.server.ToolSchemas;
import com.customercompass.api.observability.Tracing;
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
    // a span per tool call: the tool and the NAMES of its arguments, never their values or the
    // result
    try (var span =
        Tracing.span(
            "tool." + call.name(),
            "tool",
            Tracing.attrs(
                "tool.name",
                call.name(),
                "tool.argument_names",
                call.arguments() == null ? List.of() : List.copyOf(call.arguments().keySet())))) {
      return execute(session, call, userConfirmed, toolsUsed, span);
    }
  }

  private String execute(
      McpToolClient.Session session,
      ToolCall call,
      boolean userConfirmed,
      List<Map<String, Object>> toolsUsed,
      Tracing.SpanScope span) {
    if (call.error() != null) {
      span.set("tool.outcome", "invalid_call");
      return error("Invalid tool call: " + call.error() + ".");
    }
    Map<String, Object> arguments;
    try {
      arguments = new LinkedHashMap<>(ToolSchemas.validate(call.name(), call.arguments()));
    } catch (IllegalArgumentException e) {
      span.set("tool.outcome", "invalid_arguments");
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
      String result = session.call(call.name(), arguments);
      span.set("tool.outcome", "ran");
      return result;
    } catch (RuntimeException e) {
      span.set("tool.outcome", "tool_error");
      return error(String.valueOf(e.getMessage()));
    }
  }

  private static String error(String message) {
    return JSON.writeValueAsString(Map.of("status", "error", "message", message));
  }
}
