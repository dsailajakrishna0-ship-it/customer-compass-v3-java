package com.customercompass.comparisons.common;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place that knows OpenTelemetry: turns the events an approach reports through {@link
 * ChatObserver} into spans and metrics (ADR 0039), and can show the most recent ones as JSON.
 *
 * <p>Spans, as in the application's tracing (ADR 0027): {@code chat.request} (the root, one per
 * question), {@code llm.generate} (one per model call, with the GenAI attributes) and {@code
 * tool.<name>} (one per tool the model asked for). Every attribute is a name, a count, an outcome
 * or a duration. Nothing a user or a model wrote is ever recorded: the only strings that go into
 * attributes are the approach, model names the caller or the backend chose, tool names the backend
 * offers (or {@code unknown}), and fixed outcome words.
 */
public final class Telemetry implements ChatObserver {

  private static final AttributeKey<String> APPROACH = AttributeKey.stringKey("cc.approach");
  private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("cc.outcome");
  private static final AttributeKey<String> STATUS = AttributeKey.stringKey("cc.status");
  private static final AttributeKey<String> FAILURE = AttributeKey.stringKey("cc.failure");
  private static final AttributeKey<Long> MODEL_CALLS = AttributeKey.longKey("cc.model_calls");
  private static final AttributeKey<Long> TOOL_CALLS = AttributeKey.longKey("cc.tool_calls");
  private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("gen_ai.operation.name");
  private static final AttributeKey<String> MODEL = AttributeKey.stringKey("gen_ai.request.model");
  private static final AttributeKey<Long> TOKENS_IN = AttributeKey.longKey("gen_ai.usage.input_tokens");
  private static final AttributeKey<Long> TOKENS_OUT = AttributeKey.longKey("gen_ai.usage.output_tokens");
  private static final AttributeKey<String> TOOL = AttributeKey.stringKey("gen_ai.tool.name");
  private static final AttributeKey<String> TOKEN_TYPE = AttributeKey.stringKey("gen_ai.token.type");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final SdkTracerProvider tracerProvider;
  private final SdkMeterProvider meterProvider;
  private final InMemoryMetricReader metricReader;
  private final RecentSpans recent;
  private final Tracer tracer;
  private final LongCounter requests;
  private final LongCounter modelCalls;
  private final LongCounter toolCalls;
  private final LongCounter tokens;
  private final LongHistogram requestMillis;

  private Telemetry(SpanExporter exporter, RecentSpans recent) {
    this.recent = recent;
    this.tracerProvider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    this.metricReader = InMemoryMetricReader.create();
    this.meterProvider = SdkMeterProvider.builder().registerMetricReader(metricReader).build();
    this.tracer = tracerProvider.get("comparisons");
    Meter meter = meterProvider.get("comparisons");
    this.requests = meter.counterBuilder("cc.chat.requests").setDescription("Questions answered").build();
    this.modelCalls = meter.counterBuilder("cc.chat.model_calls").setDescription("Calls to the model").build();
    this.toolCalls = meter.counterBuilder("cc.chat.tool_calls").setDescription("Tools the model asked for").build();
    this.tokens = meter.counterBuilder("gen_ai.client.token.usage").setUnit("{token}").build();
    this.requestMillis =
        meter.histogramBuilder("cc.chat.request.duration").setUnit("ms").ofLongs().build();
  }

  /** Records into a bounded in-memory buffer that {@link #recentJson()} shows. */
  public static Telemetry create() {
    var recent = new RecentSpans();
    return new Telemetry(recent, recent);
  }

  /** Records into the given exporter (tests pass the SDK's in-memory exporter). */
  public static Telemetry create(SpanExporter exporter) {
    return new Telemetry(exporter, null);
  }

  /** The metrics recorded so far, as the SDK reports them. */
  public List<MetricData> metrics() {
    return new ArrayList<>(metricReader.collectAllMetrics());
  }

  @Override
  public ChatObserver.Request begin(String approach, String requestedModel) {
    Span root =
        tracer
            .spanBuilder("chat.request")
            .setSpanKind(SpanKind.SERVER)
            .setAttribute(APPROACH, approach)
            .setAttribute(MODEL, requestedModel == null ? "default" : requestedModel)
            .startSpan();
    return new Handle(approach, root);
  }

  private final class Handle implements ChatObserver.Request {

    private final String approach;
    private final Span root;
    private final long startedNanos = System.nanoTime();
    private long models;
    private long tools;
    private String failure;
    private boolean closed;

    Handle(String approach, Span root) {
      this.approach = approach;
      this.root = root;
    }

    @Override
    public synchronized void modelCall(String model, Integer tokensIn, Integer tokensOut, long millis, String outcome) {
      models++;
      var builder =
          child("llm.generate", millis)
              .setAttribute(OPERATION, "chat")
              .setAttribute(MODEL, model == null ? "unknown" : model)
              .setAttribute(OUTCOME, outcome);
      if (tokensIn != null) {
        builder.setAttribute(TOKENS_IN, tokensIn.longValue());
      }
      if (tokensOut != null) {
        builder.setAttribute(TOKENS_OUT, tokensOut.longValue());
      }
      Span span = builder.startSpan();
      if (outcome.equals("error")) {
        span.setStatus(StatusCode.ERROR);
      }
      span.end();
      modelCalls.add(1, Attributes.of(APPROACH, approach, MODEL, model == null ? "unknown" : model, OUTCOME, outcome));
      if (tokensIn != null) {
        tokens.add(tokensIn, Attributes.of(APPROACH, approach, TOKEN_TYPE, "input"));
      }
      if (tokensOut != null) {
        tokens.add(tokensOut, Attributes.of(APPROACH, approach, TOKEN_TYPE, "output"));
      }
    }

    @Override
    public synchronized void toolCall(String tool, String outcome, long millis) {
      tools++;
      Span span =
          child("tool." + tool, millis).setAttribute(TOOL, tool).setAttribute(OUTCOME, outcome).startSpan();
      if (!outcome.equals("ran")) {
        span.setStatus(StatusCode.ERROR);
      }
      span.end();
      toolCalls.add(1, Attributes.of(APPROACH, approach, TOOL, tool, OUTCOME, outcome));
    }

    @Override
    public synchronized void fail(String kind) {
      failure = kind;
    }

    @Override
    public synchronized void close() {
      if (closed) {
        return;
      }
      closed = true;
      String status = failure == null ? "ok" : "error";
      root.setAttribute(STATUS, status);
      root.setAttribute(MODEL_CALLS, models);
      root.setAttribute(TOOL_CALLS, tools);
      if (failure != null) {
        root.setAttribute(FAILURE, failure);
        root.setStatus(StatusCode.ERROR);
      }
      root.end();
      requests.add(1, Attributes.of(APPROACH, approach, STATUS, status));
      requestMillis.record((System.nanoTime() - startedNanos) / 1_000_000, Attributes.of(APPROACH, approach));
    }

    /** A child span of the request, back-dated by the duration (the time was spent before the report). */
    private io.opentelemetry.api.trace.SpanBuilder child(String name, long millis) {
      Instant end = Instant.now();
      return tracer
          .spanBuilder(name)
          .setParent(Context.root().with(root))
          .setStartTimestamp(end.minusMillis(Math.max(0, millis)).toEpochMilli(), TimeUnit.MILLISECONDS);
    }
  }

  /**
   * The most recent traces and the metrics, as JSON: {@code {"enabled": true, "traces": [{"traceId",
   * "spans": [{"name", "durationMs", "attributes"}]}], "metrics": [{"name", "points": [...]}]}}.
   */
  public String recentJson() {
    var out = new LinkedHashMap<String, Object>();
    out.put("enabled", true);
    var traces = new LinkedHashMap<String, List<Object>>();
    if (recent != null) {
      for (SpanData span : recent.snapshot()) {
        var one = new LinkedHashMap<String, Object>();
        one.put("name", span.getName());
        one.put("durationMs", (span.getEndEpochNanos() - span.getStartEpochNanos()) / 1_000_000);
        var attributes = new LinkedHashMap<String, Object>();
        span.getAttributes().forEach((key, value) -> attributes.put(key.getKey(), value));
        one.put("attributes", attributes);
        traces.computeIfAbsent(span.getTraceId(), k -> new ArrayList<>()).add(one);
      }
    }
    var list = new ArrayList<Object>();
    traces.forEach((traceId, spans) -> list.add(Map.of("traceId", traceId, "spans", spans)));
    out.put("traces", list);
    var metrics = new ArrayList<Object>();
    for (MetricData metric : metrics()) {
      var points = new ArrayList<Object>();
      metric.getData().getPoints().forEach(point -> {
        var map = new LinkedHashMap<String, Object>();
        var attributes = new LinkedHashMap<String, Object>();
        point.getAttributes().forEach((key, value) -> attributes.put(key.getKey(), value));
        map.put("attributes", attributes);
        if (point instanceof io.opentelemetry.sdk.metrics.data.LongPointData longPoint) {
          map.put("value", longPoint.getValue());
        } else if (point instanceof io.opentelemetry.sdk.metrics.data.HistogramPointData histogram) {
          map.put("count", histogram.getCount());
          map.put("sum", histogram.getSum());
        }
        points.add(map);
      });
      metrics.add(Map.of("name", metric.getName(), "points", points));
    }
    out.put("metrics", metrics);
    return JSON.writeValueAsString(out);
  }

  /** Flushes and releases the SDK. */
  public void close() {
    tracerProvider.close();
    meterProvider.close();
  }
}
