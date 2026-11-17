package com.customercompass.comparisons.toolcalling.withlangchain4j;

/**
 * The outcome word of the tool that just ran, for one question. LangChain4j's tool event says only
 * whether a tool ended in an error (a boolean), not why; the places that know why are ours: the
 * tool method (arguments refused, calls over the limit) and the error handlers (arguments that are
 * not JSON, a tool that threw, a tool the model made up). They record the word here before the
 * event fires, and the observation listener takes it when the event arrives (J8.4c, ADR 0041).
 *
 * <p>This relies on the tools of one reply running one after another, which is LangChain4j's
 * default (measured: the events of a two-tool reply come in order); it would not hold with {@code
 * executeToolsConcurrently()}, which this approach does not use.
 */
public final class ToolOutcomes {

  private volatile String next;

  /** Called by our own code, just before the library reports the tool as executed. */
  void record(String outcome) {
    next = outcome;
  }

  /** The recorded word, or null when none was recorded (the tool ran without trouble); clears it. */
  public String take() {
    String outcome = next;
    next = null;
    return outcome;
  }
}
