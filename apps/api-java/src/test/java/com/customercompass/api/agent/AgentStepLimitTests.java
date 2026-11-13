package com.customercompass.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * AGENT_MAX_STEPS=4: the run is stopped by its step limit with a status, and says what it had done.
 */
@TestPropertySource(properties = "agent.max-steps=4")
class AgentStepLimitTests extends AgentLimitsTestBase {

  @Test
  @SuppressWarnings("unchecked")
  void theStepLimitStopsTheRunWithAStatusAndStillReportsTheStepsTaken() {
    long before = tasks();

    Map<String, Object> body = recommend(ACME);

    assertThat(body.keySet()).containsExactly("route", "status", "trace", "message");
    assertThat(body)
        .containsEntry("status", "budget_exceeded")
        .containsEntry("message", "step limit reached (4)");
    var trace = (List<Map<String, Object>>) body.get("trace");
    assertThat(trace)
        .extracting(s -> s.get("name"))
        .containsExactly(
            "identify_company",
            "get_company_summary",
            "get_recent_interactions",
            "search_documents",
            "Analyst agent");
    assertThat(model.calls).as("the reviewer is never asked").containsExactly("analyst");
    assertThat(tasks()).isEqualTo(before);
  }
}
