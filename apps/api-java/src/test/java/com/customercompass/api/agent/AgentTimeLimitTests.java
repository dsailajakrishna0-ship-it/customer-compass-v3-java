package com.customercompass.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * AGENT_TIMEOUT_SECONDS=1: a hung model is stopped by the time limit with a status, not left
 * hanging.
 */
@TestPropertySource(properties = "agent.timeout-seconds=1")
class AgentTimeLimitTests extends AgentLimitsTestBase {

  @Test
  void aHungModelIsStoppedByTheTimeLimitWithAStatus() {
    model.delayMillis = 4000;
    long before = tasks();
    long started = System.nanoTime();

    Map<String, Object> body = recommend(ACME);

    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    assertThat(body)
        .containsEntry("route", "agent")
        .containsEntry("status", "timed_out")
        .containsEntry("message", "Stopped after 1s.");
    assertThat(body.get("trace")).as("the steps taken so far are reported").isNotNull();
    assertThat(elapsedMillis)
        .as("answered at the limit, not after the model finished")
        .isLessThan(3500);
    assertThat(tasks()).isEqualTo(before);
  }
}
