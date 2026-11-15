package com.customercompass.comparisons.toolcalling.withlangchain4j;

/**
 * Thrown by a tool method when the model asks for tools after the last allowed round. It is how
 * this approach stops LangChain4j's loop at exactly four rounds: {@link ErrorsAsData} rethrows it,
 * which the library documents as ending the invocation, and the chat then asks for one plain
 * answer.
 */
final class RoundLimitReached extends RuntimeException {

  RoundLimitReached() {
    super("The model asked for tools after the last allowed round.", null, false, false);
  }
}
