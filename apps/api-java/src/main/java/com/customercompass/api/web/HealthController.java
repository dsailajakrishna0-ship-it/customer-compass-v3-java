package com.customercompass.api.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Up and able to reach Postgres, not just alive (same meaning as the Python edition's /health). */
@RestController
public class HealthController {

  private final JdbcClient jdbc;

  public HealthController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/health")
  Map<String, String> health() {
    try {
      jdbc.sql("SELECT 1").query(Integer.class).single();
    } catch (RuntimeException e) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE, "Database unreachable: " + e.getMessage());
    }
    return Map.of("status", "ok");
  }
}
