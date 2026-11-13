package com.customercompass.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** SUPERVISOR_MAX_TURNS=3: the run is stopped by its turn limit with a status. */
@TestPropertySource(properties = "supervisor.max-turns=3")
class SupervisorTurnLimitTests extends AgentLimitsTestBase {

  @SuppressWarnings("unchecked")
  private Map<String, Object> supervise(String message) {
    return org.springframework.web.client.RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, (r, s) -> {})
        .build()
        .post()
        .uri("/api/v1/agent/supervise")
        .header("Content-Type", "application/json")
        .body("{\"message\":\"" + message + "\"}")
        .retrieve()
        .body(Map.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void theTurnLimitStopsTheRunWithAStatusAndStillReportsTheStepsTaken() {
    long before = tasks();

    Map<String, Object> body = supervise(ACME);

    assertThat(body)
        .containsEntry("status", "budget_exceeded")
        .containsEntry("message", "turn limit reached (3)");
    var trace = (List<Map<String, Object>>) body.get("trace");
    assertThat(trace.stream().filter(s -> "supervisor".equals(s.get("kind"))).count()).isEqualTo(3);
    assertThat(tasks()).isEqualTo(before);
  }
}
