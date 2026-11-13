package com.customercompass.api.agent;

/**
 * Which agent is asking. It travels with every model call so logs, traces and test fakes can tell
 * the agents apart.
 */
public enum AgentRole {
  ANALYST("analyst"),
  REVIEWER("reviewer"),
  TASK_DRAFTER("task_drafter"),
  /** The supervisor of the specialist team (the E3 extension). */
  SUPERVISOR("supervisor");

  private final String wireName;

  AgentRole(String wireName) {
    this.wireName = wireName;
  }

  /** The name used in traces and in the API (analyst, reviewer, task_drafter). */
  public String wireName() {
    return wireName;
  }
}
