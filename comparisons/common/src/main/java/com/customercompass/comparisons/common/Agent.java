package com.customercompass.comparisons.common;

import java.util.List;

/**
 * What every agent approach implements: a short run that looks up a company, asks the model for a
 * recommended next action, <b>pauses for a person's approval</b>, and then (approved) performs the
 * action as a dry run: nothing is written anywhere. The HTTP face ({@link AgentBackendServer}) is
 * the same for all of them, so the approaches differ only in how they keep the steps, the pause and
 * the resume.
 */
public interface Agent {

  /** Step statuses: {@code done}, {@code waiting} (for the person), {@code pending}, {@code skipped}, {@code declined}. */
  record Step(String name, String status) {}

  /**
   * Where a run is. {@code status} is {@code awaiting_approval}, {@code completed} or {@code
   * declined}; {@code steps} are always {@code gather}, {@code recommend}, {@code approval}, {@code
   * act}, in that order. {@code recommendation} is set once the model has answered.
   */
  record Outcome(String runId, String status, List<Step> steps, Recommender.Recommendation recommendation, String message) {}

  /** The folder name of the approach, for example {@code handWritten}. */
  String approach();

  /** Runs the agent up to the approval pause and returns where it stopped. */
  Outcome start(String company, String model);

  /** Continues a paused run with the person's answer. */
  Outcome resume(String runId, boolean approve);

  /** No company matches the name. */
  class CompanyNotFoundException extends RuntimeException {
    public CompanyNotFoundException(String message) {
      super(message);
    }
  }

  /** No run with that id (never started, or evicted: only the most recent runs are kept). */
  class UnknownRunException extends RuntimeException {
    public UnknownRunException(String message) {
      super(message);
    }
  }

  /** The run is not waiting for an answer (already answered, or never reached the pause). */
  class NotAwaitingApprovalException extends RuntimeException {
    public NotAwaitingApprovalException(String message) {
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
