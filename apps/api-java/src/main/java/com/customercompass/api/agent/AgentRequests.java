package com.customercompass.api.agent;

import com.customercompass.api.web.RequestValidationException;
import com.customercompass.api.web.ValidationProblems;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates the two agent request bodies by hand, so every problem leaves in FastAPI's shape
 * exactly as the Python edition's Pydantic models report it: a message of 1 to 1,000 characters; a
 * company name of 1 to 200, a note of 1 to 300 and a required boolean confirm (no silent default to
 * "write").
 */
public final class AgentRequests {

  /** A validated recommend request. */
  public record Recommend(String message, String model) {}

  /** A validated confirm request: the user's answer to the proposed task. */
  public record Confirm(String companyName, String note, boolean confirm) {}

  private static final Set<String> TRUE_WORDS = Set.of("1", "on", "t", "true", "y", "yes");
  private static final Set<String> FALSE_WORDS = Set.of("0", "off", "f", "false", "n", "no");

  private AgentRequests() {}

  public static Recommend recommend(JsonNode body, ObjectMapper json) {
    var problems = new ArrayList<Map<String, Object>>();
    requireObject(body, problems, json);
    String message = string(body, "message", "message", 1, 1000, problems, json);
    String model = optionalString(body, "model", problems, json);
    throwIfAny(problems);
    return new Recommend(message, model);
  }

  public static Confirm confirm(JsonNode body, ObjectMapper json) {
    var problems = new ArrayList<Map<String, Object>>();
    requireObject(body, problems, json);
    // the field is companyName; populate_by_name also allows company_name
    String key =
        body.has("companyName") || !body.has("company_name") ? "companyName" : "company_name";
    String company = string(body, key, "companyName", 1, 200, problems, json);
    String note = string(body, "note", "note", 1, 300, problems, json);
    Boolean confirm = bool(body, problems, json);
    throwIfAny(problems);
    return new Confirm(company, note, confirm);
  }

  private static void requireObject(
      JsonNode body, List<Map<String, Object>> problems, ObjectMapper json) {
    if (body == null || !body.isObject()) {
      problems.add(ValidationProblems.notAnObject(body, json));
      throwIfAny(problems);
    }
  }

  private static String string(
      JsonNode body,
      String key,
      String loc,
      int min,
      int max,
      List<Map<String, Object>> problems,
      ObjectMapper json) {
    JsonNode value = body.get(key);
    List<Object> at = List.of("body", loc);
    if (value == null) {
      problems.add(ValidationProblems.problem("missing", at, "Field required", body, null, json));
      return null;
    }
    if (!value.isTextual()) {
      problems.add(
          ValidationProblems.problem(
              "string_type", at, "Input should be a valid string", value, null, json));
      return null;
    }
    String text = value.asText();
    int length = text.codePointCount(0, text.length());
    if (length < min) {
      problems.add(
          ValidationProblems.problem(
              "string_too_short",
              at,
              "String should have at least " + min + " character" + (min == 1 ? "" : "s"),
              value,
              ValidationProblems.ctx("min_length", min),
              json));
      return null;
    }
    if (length > max) {
      problems.add(
          ValidationProblems.problem(
              "string_too_long",
              at,
              "String should have at most " + max + " characters",
              value,
              ValidationProblems.ctx("max_length", max),
              json));
      return null;
    }
    return text;
  }

  private static String optionalString(
      JsonNode body, String key, List<Map<String, Object>> problems, ObjectMapper json) {
    JsonNode value = body.get(key);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      problems.add(
          ValidationProblems.problem(
              "string_type",
              List.of("body", key),
              "Input should be a valid string",
              value,
              null,
              json));
      return null;
    }
    return value.asText();
  }

  /**
   * A required boolean the way Pydantic reads one from JSON: true or false, 0 or 1, or a yes/no
   * word.
   */
  private static Boolean bool(
      JsonNode body, List<Map<String, Object>> problems, ObjectMapper json) {
    List<Object> at = List.of("body", "confirm");
    JsonNode value = body.get("confirm");
    if (value == null) {
      problems.add(ValidationProblems.problem("missing", at, "Field required", body, null, json));
      return null;
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isNumber()) {
      double number = value.asDouble();
      if (number == 0.0 || number == 1.0) {
        return number == 1.0;
      }
      problems.add(parsing(at, value, json));
      return null;
    }
    if (value.isTextual()) {
      String word = value.asText().strip().toLowerCase(Locale.ROOT);
      if (TRUE_WORDS.contains(word)) {
        return true;
      }
      if (FALSE_WORDS.contains(word)) {
        return false;
      }
      problems.add(parsing(at, value, json));
      return null;
    }
    problems.add(
        ValidationProblems.problem(
            "bool_type", at, "Input should be a valid boolean", value, null, json));
    return null;
  }

  private static Map<String, Object> parsing(List<Object> at, JsonNode value, ObjectMapper json) {
    return ValidationProblems.problem(
        "bool_parsing",
        at,
        "Input should be a valid boolean, unable to interpret input",
        value,
        null,
        json);
  }

  private static void throwIfAny(List<Map<String, Object>> problems) {
    if (!problems.isEmpty()) {
      throw new RequestValidationException(List.copyOf(problems));
    }
  }
}
