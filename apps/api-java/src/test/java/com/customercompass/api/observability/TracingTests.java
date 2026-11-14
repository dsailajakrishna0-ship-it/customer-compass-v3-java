package com.customercompass.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tracing module on its own (ADR 0027): an in-memory exporter, no key, no network, no Spring.
 * The names mirror the Python edition's test_tracing.py.
 */
class TracingTests {

  InMemorySpanExporter exporter;

  @BeforeEach
  void on() {
    exporter = InMemorySpanExporter.create();
    Tracing.install(exporter, "test-service", false);
  }

  @AfterEach
  void off() {
    Tracing.shutdown();
  }

  private SpanData only(String name) {
    return exporter.getFinishedSpanItems().stream()
        .filter(s -> s.getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void withTracingOffEverythingIsANoOp() {
    Tracing.shutdown();

    try (var span = Tracing.span("x", "span", Map.of("a", 1))) {
      span.set("b", 2).fail("nothing happens");
    }
    Tracing.finishedSpan(null, "y", "span", 5, Map.of());

    assertThat(Tracing.active()).isFalse();
    assertThat(Tracing.current()).isNull();
    assertThat(exporter.getFinishedSpanItems()).isEmpty();
  }

  @Test
  void aSpanInsideASpanIsItsChildInTheSameTrace() {
    try (var outer = Tracing.span("outer", "agent")) {
      try (var inner = Tracing.span("inner", "generation", Map.of("llm.model", "m"))) {
        inner.set("llm.tokens_in", 5);
      }
    }

    SpanData outer = only("outer");
    SpanData inner = only("inner");
    assertThat(inner.getParentSpanId()).isEqualTo(outer.getSpanId());
    assertThat(inner.getTraceId()).isEqualTo(outer.getTraceId());
    assertThat(inner.getAttributes().get(AttributeKey.stringKey("langfuse.observation.type")))
        .isEqualTo("generation");
    assertThat(inner.getAttributes().get(AttributeKey.longKey("llm.tokens_in"))).isEqualTo(5L);
    assertThat(outer.getResource().getAttribute(AttributeKey.stringKey("service.name")))
        .isEqualTo("test-service");
  }

  @Test
  void aRequestIdOf32HexCharactersIsTheTraceId() {
    String id = "0123456789abcdef0123456789abcdef";

    try (var request = Tracing.requestSpan("GET /x", id, Map.of())) {
      try (var child = Tracing.span("child", "span")) {
        child.set("n", 1);
      }
    }

    assertThat(only("GET /x").getTraceId()).isEqualTo(id);
    assertThat(only("child").getTraceId()).isEqualTo(id);
  }

  @Test
  void aCallersOwnTokenStaysAnAttributeAndTheTraceGetsAFreshId() {
    try (var request = Tracing.requestSpan("GET /x", "my-own-token-1", Map.of())) {
      request.set("n", 1);
    }

    SpanData root = only("GET /x");
    assertThat(root.getTraceId()).hasSize(32).isNotEqualTo("my-own-token-1");
    assertThat(root.getAttributes().get(AttributeKey.stringKey("request.id")))
        .isEqualTo("my-own-token-1");
  }

  @Test
  void aRequestSpanIsARootEvenInsideAnotherSpan() {
    try (var outer = Tracing.span("outer", "span")) {
      try (var request = Tracing.requestSpan("GET /x", null, Map.of())) {
        request.set("n", 1);
      }
    }

    assertThat(only("GET /x").getParentSpanId()).isEqualTo("0000000000000000");
  }

  @Test
  void attributesThatLookLikeSecretsAreDroppedAndTextIsCut() {
    try (var span =
        Tracing.span(
            "s",
            "span",
            Tracing.attrs(
                "api_key",
                "sk-secret",
                "Authorization",
                "Bearer abc",
                "db_password",
                "p",
                "note",
                "x".repeat(500),
                "nothing",
                null,
                "ok",
                true))) {
      span.set("tool.argument_names", List.of("a", "b"));
    }

    var attributes = only("s").getAttributes();
    assertThat(attributes.asMap().keySet().stream().map(AttributeKey::getKey))
        .doesNotContain("api_key", "Authorization", "db_password", "nothing");
    assertThat(attributes.get(AttributeKey.stringKey("note"))).hasSize(Tracing.MAX_TEXT);
    assertThat(attributes.get(AttributeKey.booleanKey("ok"))).isTrue();
    assertThat(attributes.get(AttributeKey.stringArrayKey("tool.argument_names")))
        .containsExactly("a", "b");
  }

  @Test
  void aFailureRecordsTheExceptionTypeNotItsMessageAndPassesThrough() {
    var thrown = new IllegalStateException("customer Acme owes 4000 and the key is sk-123");

    try (var span = Tracing.span("s", "span")) {
      span.fail(thrown);
    }

    SpanData data = only("s");
    assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(data.getStatus().getDescription()).isEqualTo("IllegalStateException");
  }

  @Test
  void aFinishedStepBecomesASpanBackdatedByItsDuration() {
    Object parent;
    try (var run = Tracing.span("agent.run", "agent")) {
      parent = Tracing.current();
      Tracing.finishedSpan(parent, "agent.Analyst", "agent", 250, Map.of("ok", false));
    }

    SpanData run = only("agent.run");
    SpanData step = only("agent.Analyst");
    assertThat(step.getParentSpanId()).isEqualTo(run.getSpanId());
    assertThat(step.getEndEpochNanos() - step.getStartEpochNanos())
        .isBetween(249_000_000L, 400_000_000L);
    assertThat(step.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
  }

  @Test
  void theContextCrossesToAnotherThreadWhenHandedOver() throws Exception {
    try (var run = Tracing.span("agent.run", "agent")) {
      Object context = Tracing.current();
      CompletableFuture.supplyAsync(
              () ->
                  Tracing.within(
                      context,
                      () -> {
                        try (var inner = Tracing.span("on.other.thread", "span")) {
                          inner.set("n", 1);
                        }
                        return 1;
                      }))
          .get();
    }

    assertThat(only("on.other.thread").getParentSpanId()).isEqualTo(only("agent.run").getSpanId());
  }

  @Test
  void aBrokenExporterNeverReachesTheTracedCode() {
    Tracing.install(
        new io.opentelemetry.sdk.trace.export.SpanExporter() {
          @Override
          public io.opentelemetry.sdk.common.CompletableResultCode export(
              java.util.Collection<SpanData> spans) {
            throw new IllegalStateException("receiver is down");
          }

          @Override
          public io.opentelemetry.sdk.common.CompletableResultCode flush() {
            return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
          }

          @Override
          public io.opentelemetry.sdk.common.CompletableResultCode shutdown() {
            return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
          }
        },
        "svc",
        false);

    int result;
    try (var span = Tracing.span("s", "span")) {
      result = 42;
    }

    assertThat(result).isEqualTo(42);
  }

  @Test
  void spansEndedAfterShutdownAreIgnored() {
    var span = Tracing.span("s", "span");
    Tracing.shutdown();

    span.set("n", 1);
    span.close();

    assertThat(Tracing.active()).isFalse();
  }
}
