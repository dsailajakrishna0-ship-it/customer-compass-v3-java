package com.customercompass.api.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place that knows OpenTelemetry (J7.3, ADR 0027; the Python edition's tracing.py). Every
 * other class calls {@link #span}. A trace is the story of one request, a span one timed step.
 *
 * <p>Three rules, the same as for the reranker:
 *
 * <ol>
 *   <li><b>Off by default.</b> With tracing off, {@link #span} returns a handle that does nothing
 *       and touches no OpenTelemetry class.
 *   <li><b>Tracing never breaks a request.</b> Every call into OpenTelemetry is wrapped; a dead
 *       receiver is the exporter thread's problem. An exception from the traced code passes through
 *       untouched and the span records only its type.
 *   <li><b>Spans carry counts, names, sizes and durations, never content.</b> Nothing a user typed,
 *       no prompt, no answer and no document text. Names that look like secrets are dropped and
 *       text is cut to {@value #MAX_TEXT} characters.
 * </ol>
 *
 * It is a static facade on purpose: the traced classes (gateway, retriever, tool runner, guards)
 * are built by hand in many tests, and a global switch keeps their constructors unchanged.
 */
public final class Tracing {

  private static final Logger log = LoggerFactory.getLogger(Tracing.class);

  /** How Langfuse labels a step: generation, tool, retriever, agent, span, guardrail. */
  public static final String OBSERVATION_TYPE = "langfuse.observation.type";

  static final int MAX_TEXT = 200;
  private static final Pattern SECRET_KEY =
      Pattern.compile(
          "api[_-]?key|secret|password|authorization|bearer|credential", Pattern.CASE_INSENSITIVE);
  private static final Pattern HEX_ID = Pattern.compile("[0-9a-f]{32}");

  /** The trace id the next root span must use: the request's id (see {@link #requestSpan}). */
  private static final ThreadLocal<String> FORCED_TRACE_ID = new ThreadLocal<>();

  private static volatile SdkTracerProvider provider;
  private static volatile Tracer tracer;

  private Tracing() {}

  public static boolean active() {
    return tracer != null;
  }

  // ------------------------------------------------------------------ set-up and tear-down

  /**
   * Makes {@code exporter} receive every span. {@code batch} sends in the background (production);
   * tests pass an in-memory exporter and read it straight away.
   */
  public static synchronized void install(
      SpanExporter exporter, String serviceName, boolean batch) {
    shutdown();
    SdkTracerProvider created =
        SdkTracerProvider.builder()
            .setResource(
                Resource.getDefault().toBuilder()
                    .put(AttributeKey.stringKey("service.name"), serviceName)
                    .build())
            .setIdGenerator(new RequestTraceIds())
            .addSpanProcessor(
                batch
                    ? BatchSpanProcessor.builder(exporter).build()
                    : SimpleSpanProcessor.create(exporter))
            .build();
    provider = created;
    tracer = created.get("customer-compass");
  }

  /** Flushes what is waiting and stops tracing (at shut-down, and between tests). */
  public static synchronized void shutdown() {
    SdkTracerProvider old = provider;
    provider = null;
    tracer = null;
    if (old != null) {
      try {
        old.shutdown().join(5, java.util.concurrent.TimeUnit.SECONDS);
      } catch (RuntimeException e) {
        log.warn("tracing_shutdown_failed {}", e.getClass().getSimpleName());
      }
    }
  }

  /** Random ids, except that a request's root span takes the request id as its trace id. */
  static final class RequestTraceIds implements IdGenerator {
    private final IdGenerator random = IdGenerator.random();

    @Override
    public String generateSpanId() {
      return random.generateSpanId();
    }

    @Override
    public String generateTraceId() {
      String forced = FORCED_TRACE_ID.get();
      return forced != null ? forced : random.generateTraceId();
    }
  }

  // ------------------------------------------------------------------ safe attributes

  static String shorten(String text, int limit) {
    String flat = String.join(" ", text.strip().split("\\s+"));
    return flat.length() <= limit ? flat : flat.substring(0, limit - 1) + "…";
  }

  /** Only values OpenTelemetry accepts, text shortened, nothing that looks like a secret. */
  static Attributes clean(Map<String, ?> attributes) {
    AttributesBuilder out = Attributes.builder();
    attributes.forEach(
        (key, value) -> {
          if (value == null || SECRET_KEY.matcher(key).find()) {
            return;
          }
          switch (value) {
            case Boolean b -> out.put(AttributeKey.booleanKey(key), b);
            case Integer i -> out.put(AttributeKey.longKey(key), i.longValue());
            case Long l -> out.put(AttributeKey.longKey(key), l);
            case Double d -> out.put(AttributeKey.doubleKey(key), d);
            case Float f -> out.put(AttributeKey.doubleKey(key), f.doubleValue());
            case Collection<?> c ->
                out.put(
                    AttributeKey.stringArrayKey(key),
                    c.stream().limit(20).map(item -> shorten(String.valueOf(item), 80)).toList());
            default ->
                out.put(AttributeKey.stringKey(key), shorten(String.valueOf(value), MAX_TEXT));
          }
        });
    return out.build();
  }

  // ------------------------------------------------------------------ spans

  /** What the code being traced sees: {@code set} adds attributes, {@code fail} marks an error. */
  public static final class SpanScope implements AutoCloseable {
    private final Span span;
    private final Scope scope;

    private SpanScope(Span span, Scope scope) {
      this.span = span;
      this.scope = scope;
    }

    public SpanScope set(String key, Object value) {
      return set(attrs(key, value));
    }

    public SpanScope set(Map<String, ?> attributes) {
      if (span != null) {
        try {
          span.setAllAttributes(clean(attributes));
        } catch (RuntimeException e) {
          log.debug("span_attribute_failed", e);
        }
      }
      return this;
    }

    /** The exception's type, not its message: a message can carry a key or a customer's text. */
    public void fail(Throwable error) {
      fail(error.getClass().getSimpleName());
    }

    public void fail(String what) {
      if (span != null) {
        try {
          span.setStatus(StatusCode.ERROR, shorten(what, 80));
        } catch (RuntimeException e) {
          log.debug("span_status_failed", e);
        }
      }
    }

    @Override
    public void close() {
      try {
        if (scope != null) {
          scope.close();
        }
        if (span != null) {
          span.end();
        }
      } catch (RuntimeException e) {
        log.debug("span_end_failed", e);
      }
    }
  }

  private static final SpanScope NO_SPAN = new SpanScope(null, null);

  /** Attributes from key, value pairs; a null value is allowed (it is left out), unlike Map.of. */
  public static Map<String, Object> attrs(Object... keysAndValues) {
    var map = new java.util.LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
      map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
    }
    return map;
  }

  /** A span that does nothing, for code that sometimes traces and sometimes does not. */
  public static SpanScope none() {
    return NO_SPAN;
  }

  /**
   * Starts a span that is the current one until it is closed, so spans started inside nest under
   * it. Use it in try-with-resources. {@code type} is the Langfuse observation type.
   */
  public static SpanScope span(String name, String type, Map<String, ?> attributes) {
    Tracer t = tracer;
    if (t == null) {
      return NO_SPAN;
    }
    try {
      Span span = t.spanBuilder(name).setSpanKind(SpanKind.INTERNAL).startSpan();
      span.setAttribute(OBSERVATION_TYPE, type);
      span.setAllAttributes(clean(attributes));
      return new SpanScope(span, Context.current().with(span).makeCurrent());
    } catch (RuntimeException e) {
      log.debug("span_start_failed", e);
      return NO_SPAN;
    }
  }

  public static SpanScope span(String name, String type) {
    return span(name, type, Map.of());
  }

  /**
   * The root span of an HTTP request. When {@code requestId} is a 32-character hex id, the trace
   * uses it as its trace id, so one id finds the log lines, the X-Request-ID header and the trace.
   * A caller's own token stays an attribute and the trace gets a fresh id.
   */
  public static SpanScope requestSpan(String name, String requestId, Map<String, ?> attributes) {
    Tracer t = tracer;
    if (t == null) {
      return NO_SPAN;
    }
    boolean forced = requestId != null && HEX_ID.matcher(requestId).matches();
    try {
      if (forced) {
        FORCED_TRACE_ID.set(requestId);
      }
      Span span = t.spanBuilder(name).setNoParent().setSpanKind(SpanKind.SERVER).startSpan();
      span.setAttribute(OBSERVATION_TYPE, "span");
      span.setAttribute("request.id", requestId == null ? "" : shorten(requestId, 64));
      span.setAllAttributes(clean(attributes));
      return new SpanScope(span, Context.current().with(span).makeCurrent());
    } catch (RuntimeException e) {
      log.debug("span_start_failed", e);
      return NO_SPAN;
    } finally {
      FORCED_TRACE_ID.remove();
    }
  }

  /**
   * A step that has already finished (the agent's own trace records steps after they ran): a span
   * under {@code parent}, started {@code durationMs} ago and ended now.
   */
  public static void finishedSpan(
      Object parent, String name, String type, long durationMs, Map<String, ?> attributes) {
    Tracer t = tracer;
    if (t == null) {
      return;
    }
    try {
      Context parentContext = parent instanceof Context c ? c : Context.current();
      SpanBuilder builder =
          t.spanBuilder(name)
              .setParent(parentContext)
              .setStartTimestamp(Instant.now().minus(Duration.ofMillis(Math.max(0, durationMs))));
      Span span = builder.startSpan();
      span.setAttribute(OBSERVATION_TYPE, type);
      span.setAllAttributes(clean(attributes));
      if (Boolean.FALSE.equals(attributes.get("ok"))) {
        span.setStatus(StatusCode.ERROR);
      }
      span.end();
    } catch (RuntimeException e) {
      log.debug("span_failed", e);
    }
  }

  // ------------------------------------------------------------------ crossing threads

  /** The current context, to hand to code that runs later or on another thread (null when off). */
  public static Object current() {
    return tracer == null ? null : Context.current();
  }

  /** Runs {@code work} with {@code context} (from {@link #current}) as the current one. */
  public static <T> T within(Object context, Supplier<T> work) {
    if (!(context instanceof Context c)) {
      return work.get();
    }
    try (Scope ignored = c.makeCurrent()) {
      return work.get();
    }
  }
}
