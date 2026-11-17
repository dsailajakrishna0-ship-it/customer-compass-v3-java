package com.customercompass.comparisons.common;

/**
 * Where an approach reports what it does while it answers one question, so the same signals (a trace
 * with a span per model call and per tool call, and metrics) can be recorded whichever approach is
 * behind the route. How the events are captured differs per approach: the hand-written loop calls
 * this directly, Spring AI and LangChain4j feed it from their own observation and listener hooks.
 *
 * <p>The rule that holds for every implementation: <b>names, counts, ids and durations only, never
 * content</b> (no question, prompt, answer, document text or tool argument value).
 */
public interface ChatObserver {

  /** Does nothing: what an approach uses when observation is off. */
  ChatObserver NONE =
      (approach, model) ->
          new Request() {
            @Override
            public void modelCall(String model, Integer tokensIn, Integer tokensOut, long millis, String outcome) {}

            @Override
            public void toolCall(String tool, String outcome, long millis) {}

            @Override
            public void fail(String kind) {}

            @Override
            public void close() {}
          };

  /** A question starts. Close the returned request when the answer is done (or failed). */
  Request begin(String approach, String requestedModel);

  /** One question being answered. Safe to call from other threads. */
  interface Request extends AutoCloseable {

    /** One call to the model. {@code outcome} is {@code ok} or {@code error}; tokens may be null. */
    void modelCall(String model, Integer tokensIn, Integer tokensOut, long millis, String outcome);

    /**
     * One tool the model asked for. {@code tool} is a name the backend offers, or {@code unknown};
     * {@code outcome} is {@code ran}, {@code invalid_call}, {@code invalid_arguments}, {@code
     * tool_error} or {@code skipped}.
     */
    void toolCall(String tool, String outcome, long millis);

    /** The question could not be answered; {@code kind} is a short word such as {@code model_unavailable}. */
    void fail(String kind);

    /**
     * One step of a multi-agent run: a specialist that ran, or the supervisor's decision. {@code agent}
     * is a fixed name the backend chose (lower-case letters and underscores) and {@code outcome} a
     * fixed word such as {@code ok}, {@code model}, {@code code} or {@code override}. Does nothing by
     * default, so approaches that have no steps need not implement it.
     */
    default void step(String agent, String outcome, long millis) {}

    @Override
    void close();
  }
}
