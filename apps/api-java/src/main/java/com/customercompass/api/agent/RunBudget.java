package com.customercompass.api.agent;

import java.time.Clock;

/**
 * Counts the steps and the elapsed time of one run. {@link #spend()} is called before every step
 * and throws instead of letting a run go on past its limits.
 */
public final class RunBudget {

  private final AgentProperties limits;
  private final Clock clock;
  private final long startedMillis;
  private int stepsUsed;

  public RunBudget(AgentProperties limits, Clock clock) {
    this.limits = limits;
    this.clock = clock;
    this.startedMillis = clock.millis();
  }

  public void spend() {
    if (stepsUsed >= limits.maxSteps()) {
      throw new BudgetExceededException("step limit reached (" + limits.maxSteps() + ")");
    }
    if (clock.millis() - startedMillis > (long) (limits.timeoutSeconds() * 1000)) {
      throw new BudgetExceededException(
          "time limit reached (" + Math.round(limits.timeoutSeconds()) + "s)");
    }
    stepsUsed++;
  }

  public int stepsUsed() {
    return stepsUsed;
  }
}
