package com.customercompass.api.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every error leaves as {"error": message, "detail": message}, the shape the Python edition's
 * global handler produces (docs/api-reference.md, "Error shape"). The web app reads "error".
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, String>> api(ApiException e) {
    return body(e.status(), e.getMessage());
  }

  /*
   * The next three match the Python edition as it behaves today, not as its reference describes it:
   * an unknown route and a wrong method carry only "detail" (they come from Starlette, below the
   * handler that adds "error"), and a value of the wrong type gets FastAPI's validation list. A fix
   * belongs in the Python edition first (docs/port-status.md, "Contract gaps"); then this follows.
   */
  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<Map<String, String>> notFound(NoResourceFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "Not Found"));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<Map<String, String>> wrongMethod(HttpRequestMethodNotSupportedException e) {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(Map.of("detail", "Method Not Allowed"));
  }

  /** A path or query value of the wrong type, such as /companies/abc: FastAPI's 422 body. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<Map<String, Object>> badValue(MethodArgumentTypeMismatchException e) {
    String where = e.getParameter().hasParameterAnnotation(PathVariable.class) ? "path" : "query";
    var problem = new LinkedHashMap<String, Object>();
    problem.put("type", "int_parsing");
    problem.put("loc", List.of(where, e.getName()));
    problem.put("msg", "Input should be a valid integer, unable to parse string as an integer");
    problem.put("input", String.valueOf(e.getValue()));
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .body(Map.of("detail", List.of(problem)));
  }

  /** A request body that fails validation: FastAPI's 422 body, built by the route's validator. */
  @ExceptionHandler(RequestValidationException.class)
  ResponseEntity<Map<String, Object>> invalidBody(RequestValidationException e) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .body(Map.of("detail", e.problems()));
  }

  /** A body that is not JSON at all (the byte position FastAPI reports is not reproduced). */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> unreadableBody(HttpMessageNotReadableException e) {
    var problem = new LinkedHashMap<String, Object>();
    problem.put("type", "json_invalid");
    problem.put("loc", List.of("body"));
    problem.put("msg", "JSON decode error");
    problem.put("input", Map.of());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .body(Map.of("detail", List.of(problem)));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, String>> unexpected(Exception e) {
    log.error("unhandled_error", e);
    return body(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
  }

  private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("error", message, "detail", message));
  }
}
