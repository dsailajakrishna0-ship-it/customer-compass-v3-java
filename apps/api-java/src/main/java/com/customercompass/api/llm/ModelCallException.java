package com.customercompass.api.llm;

/** A failed model call, sorted into the kinds the gateway treats differently. */
public class ModelCallException extends RuntimeException {

  public enum Kind {
    /** 429: do not retry the same model, go to the next one at once. */
    RATE_LIMIT("RateLimitError"),
    /** Retry the same model with growing waits, then fall back. */
    TIMEOUT("Timeout"),
    /** 5xx: retried like a timeout. */
    SERVER_ERROR("InternalServerError"),
    AUTH("AuthenticationError"),
    BAD_REQUEST("BadRequestError"),
    OTHER("Error");

    private final String label;

    Kind(String label) {
      this.label = label;
    }

    /** The name shown in the user-facing message (kept equal to the Python edition's). */
    public String label() {
      return label;
    }

    boolean retryable() {
      return this == TIMEOUT || this == SERVER_ERROR;
    }
  }

  private final Kind kind;

  public ModelCallException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
