package com.customercompass.api.chat;

import com.customercompass.api.llm.ChatMessage;
import com.customercompass.api.web.RequestValidationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
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
   * Turns the raw body into a JSON tree, or says why not in FastAPI's words: no body at all is a
   * missing field, a content type that is not JSON hands the raw text to the model as a string, and
   * text that is not valid JSON is a decode error.
   */
  static JsonNode parse(String raw, String contentType, ObjectMapper json) {
    if (raw == null || raw.isEmpty()) {
      throw new RequestValidationException(
          List.of(problem("missing", List.of("body"), "Field required", null, null, json)));
    }
    boolean jsonType =
        contentType != null
            && (contentType.toLowerCase().startsWith("application/json")
                || contentType.toLowerCase().contains("+json"));
    if (!jsonType) {
      var input = json.getNodeFactory().stringNode(raw);
      throw new RequestValidationException(List.of(notAnObject(input, json)));
    }
    try {
      return json.readTree(raw);
    } catch (JacksonException e) {
      var detail = new LinkedHashMap<String, Object>();
      detail.put("type", "json_invalid");
      detail.put("loc", List.of("body"));
      detail.put("msg", "JSON decode error");
      detail.put("input", Map.of());
      throw new RequestValidationException(List.of(detail));
    }
  }

  private static Map<String, Object> notAnObject(JsonNode input, ObjectMapper json) {
    return problem(
        "model_attributes_type",
        List.of("body"),
        "Input should be a valid dictionary or object to extract fields from",
        input,
        null,
        json);
  }

  static ChatRequest validate(JsonNode body, ObjectMapper json) {
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
    if (!problems.isEmpty()) {
      throw new RequestValidationException(problems);
    }
    return new ChatRequest(List.copyOf(messages), model);
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
    var map = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], pairs[i + 1]);
    }
    return map;
  }

  private static Map<String, Object> problem(
      String type,
      List<Object> loc,
      String msg,
      JsonNode input,
      Map<String, Object> ctx,
      ObjectMapper json) {
    var map = new LinkedHashMap<String, Object>();
    map.put("type", type);
    map.put("loc", loc);
    map.put("msg", msg);
    map.put("input", input == null ? null : json.convertValue(input, Object.class));
    if (ctx != null) {
      map.put("ctx", ctx);
    }
    return map;
  }
}
