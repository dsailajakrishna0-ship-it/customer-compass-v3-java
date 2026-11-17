package com.customercompass.comparisons.common;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

/**
 * A span exporter that keeps only the most recent finished spans, so a long-running backend cannot
 * fill its memory (the SDK's in-memory exporter keeps everything).
 */
final class RecentSpans implements SpanExporter {

  static final int CAPACITY = 200;

  private final Deque<SpanData> spans = new ArrayDeque<>();

  @Override
  public synchronized CompletableResultCode export(Collection<SpanData> batch) {
    for (SpanData span : batch) {
      if (spans.size() == CAPACITY) {
        spans.removeFirst();
      }
      spans.addLast(span);
    }
    return CompletableResultCode.ofSuccess();
  }

  synchronized List<SpanData> snapshot() {
    return new ArrayList<>(spans);
  }

  @Override
  public CompletableResultCode flush() {
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    return CompletableResultCode.ofSuccess();
  }
}
