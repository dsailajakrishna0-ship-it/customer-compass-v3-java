package com.customercompass.api.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds FastAPI's validation problems (type, loc, msg, input, ctx) and turns a raw request body
 * into JSON the way FastAPI does, so every route that validates by hand gives the same 422 bodies
 * the Python edition gives.
 */
public final class ValidationProblems {

  private ValidationProblems() {}

  /** One problem, in FastAPI's key order; {@code ctx} may be null. */
  public static Map<String, Object> problem(
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

  /** A ctx map from alternating keys and values. */
  public static Map<String, Object> ctx(Object... pairs) {
    var map = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], pairs[i + 1]);
    }
    return map;
  }

  /**
   * The raw body as JSON, or why not in FastAPI's words: no body is a missing field, a content type
   * that is not JSON hands the raw text back as the wrong type, and text that is not valid JSON is
   * a decode error.
   */
  public static JsonNode parseBody(String raw, String contentType, ObjectMapper json) {
    if (raw == null || raw.isEmpty()) {
      throw new RequestValidationException(
          List.of(problem("missing", List.of("body"), "Field required", null, null, json)));
    }
    String type = contentType == null ? "" : contentType.toLowerCase();
    boolean jsonType = type.startsWith("application/json") || type.contains("+json");
    if (!jsonType) {
      throw new RequestValidationException(
          List.of(notAnObject(json.getNodeFactory().stringNode(raw), json)));
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

  public static Map<String, Object> notAnObject(JsonNode input, ObjectMapper json) {
    return problem(
        "model_attributes_type",
        List.of("body"),
        "Input should be a valid dictionary or object to extract fields from",
        input,
        null,
        json);
  }
}
