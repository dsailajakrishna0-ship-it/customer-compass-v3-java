package com.customercompass.api.llm;

/** Every model in the chain failed. The message is safe to show to the user. */
public class LlmUnavailableException extends RuntimeException {

  public LlmUnavailableException(String message) {
    super(message);
  }
}
