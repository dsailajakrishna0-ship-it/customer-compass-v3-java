package com.customercompass.comparisons.common;

/**
 * The one model call of the agent: given the verified CRM facts of one company, recommend one next
 * action. Shared by the approaches that orchestrate the agent themselves, so what differs between
 * them is the orchestration (the steps, the pause, the resume), not the prompt or the model call.
 */
public interface Recommender {

  /** One recommended next action and why, both short and already length-checked. */
  record Recommendation(String action, String rationale) {}

  /**
   * {@code factsJson} is the CRM tool's answer: untrusted data. {@code model} is the one the client
   * picked, or null for the backend's own default.
   *
   * @throws Agent.UnavailableException when the model cannot be reached, refuses, or answers with
   *     something that is not a usable recommendation
   */
  Recommendation recommend(String factsJson, String model);
}
