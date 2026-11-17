package com.customercompass.comparisons.common;

import java.util.List;

/**
 * What every multi-agent pattern implements (J8.7): a team of specialists reviews one company and
 * produces one recommended next action. The pattern (supervisor, hierarchical, network, handoff)
 * decides who works next; the HTTP face ({@link MultiAgentBackendServer}) and the scenario are the
 * same for all of them, so the patterns can be compared on the same signals.
 */
public interface MultiAgent {

  /** The framework folder name, for example {@code withLangGraph4jSpringAi}. */
  String approach();

  /** The pattern: {@code supervisor}, {@code hierarchical}, {@code network} or {@code handoff}. */
  String pattern();

  /** Runs the team on one company. */
  Outcome run(String company, String model);

  /**
   * One routing decision. {@code decidedBy} is {@code supervisor} (a model chose) or {@code code} (only
   * one option was allowed, so no model was asked). {@code allowed} is what the code allowed this
   * turn; {@code proposed} is what the model proposed when its answer was unreadable or not allowed
   * and was replaced (otherwise null).
   */
  record Decision(int turn, String decidedBy, List<String> allowed, String chosen, String reason, String proposed) {}

  /**
   * How a run ended: {@code completed}, or {@code stopped} (the turn limit was reached). {@code
   * specialists} are the ones that ran, in order; {@code recommendation} may be null when the run
   * stopped early.
   */
  record Outcome(
      String status,
      List<String> specialists,
      List<Decision> decisions,
      int turns,
      Recommender.Recommendation recommendation,
      String message) {}

  /** No company matches the name. */
  class CompanyNotFoundException extends RuntimeException {
    public CompanyNotFoundException(String message) {
      super(message);
    }
  }

  /** The model or the CRM could not be reached or answered badly: the route answers 503. */
  class UnavailableException extends RuntimeException {
    public UnavailableException(String message) {
      super(message);
    }

    public UnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
