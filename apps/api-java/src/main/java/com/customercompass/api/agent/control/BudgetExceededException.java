package com.customercompass.api.agent.control;

/** A run would exceed its step or time limit; the run stops with a status instead of going on. */
public class BudgetExceededException extends RuntimeException {

  public BudgetExceededException(String message) {
    super(message);
  }
}
