package com.customercompass.api.agent;

import com.customercompass.api.observability.Tracing;
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

  /** The span this run's steps hang under (null with tracing off), taken where the run starts. */
  private final Object parentSpan = Tracing.current();

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
    emitSpan(kind, name, input, output, status, (int) step.get("duration_ms"), steps.size());
  }

  /**
   * The same step as a span, so the screen and the trace cannot disagree. Counts and sizes only:
   * the summaries the screen shows are never sent.
   */
  private void emitSpan(
      String kind, String name, String input, String output, String status, int ms, int index) {
    var attributes = new LinkedHashMap<String, Object>();
    attributes.put("step.index", index);
    attributes.put("step.kind", kind);
    attributes.put("step.name", name);
    attributes.put("step.status", status);
    attributes.put("step.input_chars", input == null ? 0 : input.length());
    attributes.put("step.output_chars", output == null ? 0 : output.length());
    attributes.put("ok", "ok".equals(status));
    String type = kind.equals("tool") ? "tool" : kind.equals("agent") ? "agent" : "span";
    Tracing.finishedSpan(parentSpan, kind + "." + name, type, ms, attributes);
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
