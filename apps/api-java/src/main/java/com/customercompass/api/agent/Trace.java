package com.customercompass.api.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An inspectable record of every step an agent run takes: which code step, tool, agent or gate ran,
 * what went in, what came out, and how long it took. It is returned to the UI so a human can see
 * why the system recommended what it did (the Python edition's trace.py).
 */
public final class Trace {

  static final int SUMMARY_MAX_CHARS = 300;

  private final List<Map<String, Object>> steps = new ArrayList<>();

  /** {@code startedNanos} is a System.nanoTime() value taken before the step ran. */
  public synchronized void record(
      String kind, String name, String input, String output, long startedNanos, String status) {
    var step = new LinkedHashMap<String, Object>();
    step.put("index", steps.size() + 1);
    step.put("kind", kind);
    step.put("name", name);
    step.put("input_summary", shorten(input));
    step.put("output_summary", shorten(output));
    step.put("status", status);
    step.put("duration_ms", (int) ((System.nanoTime() - startedNanos) / 1_000_000));
    steps.add(step);
  }

  public void record(String kind, String name, String input, String output, long startedNanos) {
    record(kind, name, input, output, startedNanos, "ok");
  }

  public synchronized List<Map<String, Object>> toList() {
    return List.copyOf(steps);
  }

  /** One line of at most 300 characters: whitespace collapsed, a long text cut with an ellipsis. */
  static String shorten(String text) {
    String trimmed = text == null ? "" : text.strip();
    String collapsed = trimmed.isEmpty() ? "" : String.join(" ", trimmed.split("\\s+"));
    int length = collapsed.codePointCount(0, collapsed.length());
    return length <= SUMMARY_MAX_CHARS
        ? collapsed
        : collapsed.substring(0, collapsed.offsetByCodePoints(0, SUMMARY_MAX_CHARS - 1)) + "…";
  }
}
