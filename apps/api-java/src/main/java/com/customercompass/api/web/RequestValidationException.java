package com.customercompass.api.web;

import java.util.List;
import java.util.Map;

/**
 * A request body that fails validation; leaves as FastAPI's 422 body {"detail": [problem, ...]}.
 */
public class RequestValidationException extends RuntimeException {

  private final transient List<Map<String, Object>> problems;

  public RequestValidationException(List<Map<String, Object>> problems) {
    super("request validation failed");
    this.problems = problems;
  }

  public List<Map<String, Object>> problems() {
    return problems;
  }
}
