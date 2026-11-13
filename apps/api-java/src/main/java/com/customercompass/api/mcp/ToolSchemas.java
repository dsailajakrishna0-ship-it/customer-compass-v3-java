package com.customercompass.api.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the model is allowed to pass to each tool. The same description does two jobs: its JSON
 * Schema is what the model is shown as the tool's parameters, and it validates whatever the model
 * actually sends before any tool runs. A tool without an entry here is never offered, and {@code
 * confirm} is deliberately not an argument of {@code create_followup_task}: whether a task is
 * confirmed is decided by the chat route from the user's own message, never by the model (the
 * Python edition's app/mcp/schemas.py).
 */
final class ToolSchemas {

  /** One argument: always a string; {@code minLength} 1 means "not empty". */
  private record Arg(String name, String description, boolean required, Integer maxLength) {}

  private static final Map<String, List<Arg>> TOOLS = new LinkedHashMap<>();

  static {
    var company = new Arg("company_name", "The company's name, as the user wrote it.", true, null);
    TOOLS.put("get_company_summary", List.of(company));
    TOOLS.put("get_open_deals", List.of(company));
    TOOLS.put(
        "search_documents",
        List.of(
            new Arg("query", "What to look for in the interaction documents.", true, null),
            new Arg("company_name", "Only search this company's documents.", false, null)));
    TOOLS.put(
        "create_followup_task",
        List.of(
            new Arg("company_name", "The company the follow-up is for.", true, null),
            new Arg("note", "What the follow-up task is.", true, 500)));
  }

  private ToolSchemas() {}

  static boolean knows(String tool) {
    return TOOLS.containsKey(tool);
  }

  /** The JSON Schema of a tool's arguments, as the model is shown it. */
  static Map<String, Object> parameters(String tool) {
    var properties = new LinkedHashMap<String, Object>();
    var required = new ArrayList<String>();
    for (Arg arg : TOOLS.get(tool)) {
      var property = new LinkedHashMap<String, Object>();
      property.put("type", "string");
      property.put("description", arg.description());
      if (arg.required()) {
        property.put("minLength", 1);
        required.add(arg.name());
      }
      if (arg.maxLength() != null) {
        property.put("maxLength", arg.maxLength());
      }
      properties.put(arg.name(), property);
    }
    var schema = new LinkedHashMap<String, Object>();
    schema.put("type", "object");
    schema.put("properties", properties);
    schema.put("required", required);
    schema.put("additionalProperties", false);
    return schema;
  }

  /**
   * The cleaned arguments (only the named ones that are present and not null), or an
   * IllegalArgumentException whose message is safe to show the model. The messages are Pydantic's,
   * as in the Python edition.
   */
  static Map<String, Object> validate(String tool, Map<String, Object> arguments) {
    List<Arg> args = TOOLS.get(tool);
    if (args == null) {
      throw new IllegalArgumentException("There is no tool called \"" + tool + "\".");
    }
    var problems = new ArrayList<String>();
    var cleaned = new LinkedHashMap<String, Object>();
    Set<String> declared = new LinkedHashSet<>();
    for (Arg arg : args) {
      declared.add(arg.name());
      Object value = arguments.get(arg.name());
      if (!arguments.containsKey(arg.name())) {
        if (arg.required()) {
          problems.add(arg.name() + ": Field required");
        }
      } else if (value == null) {
        if (arg.required()) {
          problems.add(arg.name() + ": Input should be a valid string");
        }
      } else if (!(value instanceof String text)) {
        problems.add(arg.name() + ": Input should be a valid string");
      } else if (arg.required() && text.isEmpty()) {
        problems.add(arg.name() + ": String should have at least 1 character");
      } else if (arg.maxLength() != null
          && text.codePointCount(0, text.length()) > arg.maxLength()) {
        problems.add(
            arg.name() + ": String should have at most " + arg.maxLength() + " characters");
      } else {
        cleaned.put(arg.name(), text);
      }
    }
    for (String key : arguments.keySet()) {
      if (!declared.contains(key)) {
        problems.add(key + ": Extra inputs are not permitted");
      }
    }
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(
          "Invalid arguments for " + tool + ": " + String.join("; ", problems));
    }
    return cleaned;
  }
}
