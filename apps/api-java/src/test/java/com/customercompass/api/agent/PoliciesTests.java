package com.customercompass.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The routing rule and the run budget (steps and time). */
class PoliciesTests {

  /** A clock the test can move forward. */
  static class MovingClock extends Clock {
    long millis = 1_000_000;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }
  }

  @Test
  void onlyAnalysisStyleRequestsNeedTheAgent() {
    for (String yes :
        java.util.List.of(
            "Analyze Acme Fabrication and recommend a next action",
            "analyse Globex",
            "What is the next step for Vertex?",
            "Any follow-up needed for Summit?",
            "What should we do about Riverside?",
            "Do an account review for Acme",
            "Review the account of Northstar",
            "Give me a recommendation")) {
      assertThat(AgentIntent.needsAgent(yes)).as(yes).isTrue();
    }
    for (String no :
        java.util.List.of(
            "What is Acme Fabrication's industry?",
            "What open deals does Acme have?",
            "What is the capital of France?",
            "reanalyzed",
            "")) {
      assertThat(AgentIntent.needsAgent(no)).as(no).isFalse();
    }
  }

  @Test
  void aRunMayTakeItsStepsAndNoMoreAndTheErrorNamesTheLimit() {
    var budget = new RunBudget(new AgentProperties(3, 180, 1), Clock.systemUTC());

    budget.spend();
    budget.spend();
    budget.spend();

    assertThat(budget.stepsUsed()).isEqualTo(3);
    assertThatThrownBy(budget::spend)
        .isInstanceOf(BudgetExceededException.class)
        .hasMessage("step limit reached (3)");
  }

  @Test
  void aRunThatTakesTooLongIsStoppedWithTheTimeLimitInTheMessage() {
    var clock = new MovingClock();
    var budget = new RunBudget(new AgentProperties(8, 2, 1), clock);

    budget.spend();
    clock.millis += 1999;
    budget.spend();
    clock.millis += 2;

    assertThatThrownBy(budget::spend).hasMessage("time limit reached (2s)");
    assertThat(budget.stepsUsed()).as("a refused step is not counted").isEqualTo(2);
  }

  @Test
  void theDefaultsAreThePythonEditionsLimits() {
    var defaults = new AgentProperties(8, 180, 1);

    assertThat(defaults.maxSteps()).isEqualTo(8);
    assertThat(defaults.timeoutSeconds()).isEqualTo(180.0);
    assertThat(defaults.maxRevisions()).isEqualTo(1);
  }
}
