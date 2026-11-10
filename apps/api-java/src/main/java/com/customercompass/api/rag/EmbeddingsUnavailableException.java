package com.customercompass.api.rag;

/**
 * The embedding service cannot be used (no key, Google refused, an unusable reply). The message is
 * safe to show to an API caller: it never holds the key or Google's reply body.
 */
public class EmbeddingsUnavailableException extends RuntimeException {

  public EmbeddingsUnavailableException(String message) {
    super(message);
  }

  public EmbeddingsUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
