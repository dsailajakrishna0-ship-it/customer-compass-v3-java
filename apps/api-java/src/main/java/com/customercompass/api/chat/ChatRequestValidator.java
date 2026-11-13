package com.customercompass.api.chat;

import com.customercompass.api.llm.ChatMessage;
import com.customercompass.api.web.RequestValidationException;
import com.customercompass.api.web.ValidationProblems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates a chat body by hand so every problem leaves in FastAPI's shape (type, loc, msg, input,
 * ctx), exactly as the Python edition's Pydantic model reports it: 1 to 12 messages, a role of user
 * or assistant, content that is not blank and at most 4,000 characters, and a string model.
 */
final class ChatRequestValidator {

  static final int MAX_MESSAGES = 12;
  static final int MAX_CONTENT_CHARACTERS = 4_000;

  private ChatRequestValidator() {}

  /**
   * The raw body as JSON, or a FastAPI-style validation problem (see {@link
   * ValidationProblems#parseBody}).
   */
  static JsonNode parse(String raw, String contentType, ObjectMapper json) {
    return ValidationProblems.parseBody(raw, contentType, json);
  }

  static ChatRequest validate(JsonNode body, ObjectMapper json) {
    return validate(body, json, false);
  }

  /** With {@code withMcpServer} the optional string field mcpServer is checked and returned too. */
  static ChatRequest validate(JsonNode body, ObjectMapper json, boolean withMcpServer) {
    var problems = new ArrayList<Map<String, Object>>();
    if (body == null || !body.isObject()) {
      problems.add(
          problem(
              "model_attributes_type",
              List.of("body"),
              "Input should be a valid dictionary or object to extract fields from",
              body,
              null,
              json));
      throw new RequestValidationException(problems);
    }
    var messages = new ArrayList<ChatMessage>();
    JsonNode list = body.get("messages");
    if (list == null) {
      problems.add(
          problem("missing", List.of("body", "messages"), "Field required", body, null, json));
    } else if (!list.isArray()) {
      problems.add(
          problem(
              "list_type",
              List.of("body", "messages"),
              "Input should be a valid list",
              list,
              null,
              json));
    } else {
      for (int i = 0; i < list.size(); i++) {
        message(list.get(i), i, messages, problems, json);
      }
      if (problems.isEmpty() && list.isEmpty()) {
        problems.add(
            problem(
                "too_short",
                List.of("body", "messages"),
                "List should have at least 1 item after validation, not 0",
                list,
                ctx("field_type", "List", "min_length", 1, "actual_length", 0),
                json));
      }
      if (problems.isEmpty() && list.size() > MAX_MESSAGES) {
        problems.add(
            problem(
                "too_long",
                List.of("body", "messages"),
                "List should have at most 12 items after validation, not " + list.size(),
                list,
                ctx("field_type", "List", "max_length", MAX_MESSAGES, "actual_length", list.size()),
                json));
      }
    }
    String model = null;
    JsonNode modelNode = body.get("model");
    if (modelNode != null && !modelNode.isNull()) {
      if (modelNode.isTextual()) {
        model = modelNode.asText();
      } else {
        problems.add(
            problem(
                "string_type",
                List.of("body", "model"),
                "Input should be a valid string",
                modelNode,
                null,
                json));
      }
    }
    String mcpServer = null;
    JsonNode serverNode = withMcpServer ? body.get("mcpServer") : null;
    if (serverNode != null && !serverNode.isNull()) {
      if (serverNode.isTextual()) {
        mcpServer = serverNode.asText();
      } else {
        problems.add(
            problem(
                "string_type",
                List.of("body", "mcpServer"),
                "Input should be a valid string",
                serverNode,
                null,
                json));
      }
    }
    if (!problems.isEmpty()) {
      throw new RequestValidationException(problems);
    }
    return new ChatRequest(List.copyOf(messages), model, mcpServer);
  }

  private static void message(
      JsonNode node,
      int index,
      List<ChatMessage> out,
      List<Map<String, Object>> problems,
      ObjectMapper json) {
    List<Object> at = List.of("body", "messages", index);
    if (!node.isObject()) {
      problems.add(
          problem(
              "model_attributes_type",
              at,
              "Input should be a valid dictionary or object to extract fields from",
              node,
              null,
              json));
      return;
    }
    boolean ok = true;
    JsonNode role = node.get("role");
    if (role == null) {
      problems.add(problem("missing", append(at, "role"), "Field required", node, null, json));
      ok = false;
    } else if (!role.isTextual()
        || !(role.asText().equals("user") || role.asText().equals("assistant"))) {
      problems.add(
          problem(
              "literal_error",
              append(at, "role"),
              "Input should be 'user' or 'assistant'",
              role,
              ctx("expected", "'user' or 'assistant'"),
              json));
      ok = false;
    }
    JsonNode content = node.get("content");
    if (content == null) {
      problems.add(problem("missing", append(at, "content"), "Field required", node, null, json));
      ok = false;
    } else if (!content.isTextual()) {
      problems.add(
          problem(
              "string_type",
              append(at, "content"),
              "Input should be a valid string",
              content,
              null,
              json));
      ok = false;
    } else if (content.asText().codePointCount(0, content.asText().length())
        > MAX_CONTENT_CHARACTERS) {
      problems.add(
          problem(
              "string_too_long",
              append(at, "content"),
              "String should have at most 4000 characters",
              content,
              ctx("max_length", MAX_CONTENT_CHARACTERS),
              json));
      ok = false;
    } else if (content.asText().isBlank()) {
      var empty = new LinkedHashMap<String, Object>();
      empty.put("error", Map.of());
      problems.add(
          problem(
              "value_error",
              append(at, "content"),
              "Value error, content must not be blank",
              content,
              empty,
              json));
      ok = false;
    }
    if (ok) {
      out.add(new ChatMessage(role.asText(), content.asText()));
    }
  }

  private static List<Object> append(List<Object> at, Object next) {
    var list = new ArrayList<>(at);
    list.add(next);
    return list;
  }

  private static Map<String, Object> ctx(Object... pairs) {
    return ValidationProblems.ctx(pairs);
  }

  private static Map<String, Object> problem(
      String type,
      List<Object> loc,
      String msg,
      JsonNode input,
      Map<String, Object> ctx,
      ObjectMapper json) {
    return ValidationProblems.problem(type, loc, msg, input, ctx, json);
  }
}
