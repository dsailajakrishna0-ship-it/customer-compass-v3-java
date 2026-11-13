package com.customercompass.api.guard;

import com.customercompass.api.web.RequestValidationException;
import com.customercompass.api.web.ValidationProblems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * GET /api/v1/security-audit-log: the guard's detections, newest first (the Python edition's
 * route), with its query rules: {@code limit} 1 to 500 (default 50), {@code event_type} one of
 * input, document, tool, output, and an exact {@code pattern} name. Bad values are FastAPI's 422.
 */
@RestController
@RequestMapping("/api/v1")
class SecurityAuditController {

  private static final String EVENT_TYPE_PATTERN = "^(input|document|tool|output)$";
  private static final Pattern EVENT_TYPE = Pattern.compile(EVENT_TYPE_PATTERN);

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  SecurityAuditController(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @GetMapping("/security-audit-log")
  List<Map<String, Object>> list(
      @RequestParam(name = "limit", required = false) String limitText,
      @RequestParam(name = "event_type", required = false) String eventType,
      @RequestParam(name = "pattern", required = false) String pattern) {
    var problems = new ArrayList<Map<String, Object>>();
    int limit = parseLimit(limitText, problems);
    if (eventType != null && !EVENT_TYPE.matcher(eventType).find()) {
      problems.add(
          ValidationProblems.problem(
              "string_pattern_mismatch",
              List.of("query", "event_type"),
              "String should match pattern '" + EVENT_TYPE_PATTERN + "'",
              json.valueToTree(eventType),
              ValidationProblems.ctx("pattern", EVENT_TYPE_PATTERN),
              json));
    }
    if (!problems.isEmpty()) {
      throw new RequestValidationException(problems);
    }
    return jdbc.sql(
            """
            SELECT id, event_type, endpoint, pattern, location, excerpt, created_at::text AS created_at
            FROM security_audit_log
            WHERE (CAST(:type AS text) IS NULL OR event_type = :type)
              AND (CAST(:pattern AS text) IS NULL OR pattern = :pattern)
            ORDER BY created_at DESC, id DESC LIMIT :limit""")
        .param("type", eventType)
        .param("pattern", pattern)
        .param("limit", limit)
        .query(
            (rs, n) -> {
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("id", rs.getInt("id"));
              row.put("event_type", rs.getString("event_type"));
              row.put("endpoint", rs.getString("endpoint"));
              row.put("pattern", rs.getString("pattern"));
              row.put("location", rs.getString("location"));
              row.put("excerpt", rs.getString("excerpt"));
              row.put("created_at", rs.getString("created_at"));
              return row;
            })
        .list();
  }

  private int parseLimit(String text, List<Map<String, Object>> problems) {
    if (text == null) {
      return 50;
    }
    int value;
    try {
      value = Integer.parseInt(text.strip());
    } catch (NumberFormatException e) {
      problems.add(
          ValidationProblems.problem(
              "int_parsing",
              List.of("query", "limit"),
              "Input should be a valid integer, unable to parse string as an integer",
              json.valueToTree(text),
              null,
              json));
      return 50;
    }
    if (value < 1) {
      problems.add(
          ValidationProblems.problem(
              "greater_than_equal",
              List.of("query", "limit"),
              "Input should be greater than or equal to 1",
              json.valueToTree(text),
              ValidationProblems.ctx("ge", 1),
              json));
    } else if (value > 500) {
      problems.add(
          ValidationProblems.problem(
              "less_than_equal",
              List.of("query", "limit"),
              "Input should be less than or equal to 500",
              json.valueToTree(text),
              ValidationProblems.ctx("le", 500),
              json));
    }
    return value;
  }
}
