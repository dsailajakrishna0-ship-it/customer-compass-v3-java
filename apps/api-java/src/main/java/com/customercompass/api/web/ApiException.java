package com.customercompass.api.web;

import org.springframework.http.HttpStatus;

/** An error the API reports with the shared body {"error": message, "detail": message}. */
public class ApiException extends RuntimeException {

  private final HttpStatus status;

  public ApiException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }
}
