package com.customercompass.api.guard;

import com.customercompass.api.web.ApiException;
import com.customercompass.api.web.RequestValidationException;
import com.customercompass.api.web.ValidationProblems;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * POST /api/v1/guard/check (Java only, J7.4): a dry run of the guard on one text, for the guard
 * comparison. It returns what the rules say and what the classifier says, writes nothing to the
 * audit log and returns no text. It is OFF by default ({@code GUARD_CHECK_ENABLED=true} turns it
 * on) because every call can cost a model call.
 */
@RestController
@RequestMapping("/api/v1/guard")
class GuardCheckController {

  static final int MAX_CHARS = 4000;

  private final InputGuard guard;
  private final ObjectMapper json;
  private final boolean enabled;

  GuardCheckController(
      InputGuard guard, ObjectMapper json, @Value("${guard.check-enabled:false}") boolean enabled) {
    this.guard = guard;
    this.json = json;
    this.enabled = enabled;
  }

  @PostMapping(path = "/check", consumes = MediaType.ALL_VALUE)
  Map<String, Object> check(
      @RequestBody(required = false) String raw,
      @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
    if (!enabled) {
      throw new ApiException(
          HttpStatus.NOT_FOUND,
          "The guard check is off. Set GUARD_CHECK_ENABLED=true to measure the guard.");
    }
    JsonNode body = ValidationProblems.parseBody(raw, contentType, json);
    JsonNode text = body.get("text");
    if (text == null || !text.isString() || text.asString().isBlank()) {
      throw new RequestValidationException(
          List.of(
              ValidationProblems.problem(
                  text == null ? "missing" : "string_type",
                  List.of("body", "text"),
                  text == null ? "Field required" : "Input should be a valid string",
                  text,
                  null,
                  json)));
    }
    if (text.asString().length() > MAX_CHARS) {
      throw new RequestValidationException(
          List.of(
              ValidationProblems.problem(
                  "string_too_long",
                  List.of("body", "text"),
                  "String should have at most " + MAX_CHARS + " characters",
                  text,
                  ValidationProblems.ctx("max_length", MAX_CHARS),
                  json)));
    }
    InputGuard.Check c = guard.check(text.asString());
    Map<String, Object> rules = new LinkedHashMap<>();
    rules.put("flagged", c.rulesFlagged());
    rules.put("patterns", c.rulePatterns());
    Map<String, Object> classifier = new LinkedHashMap<>();
    classifier.put("outcome", c.classifier().name().toLowerCase(java.util.Locale.ROOT));
    classifier.put("word", c.word());
    classifier.put("model", c.model());
    classifier.put("fallbackUsed", c.fallbackUsed());
    classifier.put("failure", c.failure());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("rules", rules);
    out.put("classifier", classifier);
    out.put("configuredModel", guard.configuredModel());
    return out;
  }
}
