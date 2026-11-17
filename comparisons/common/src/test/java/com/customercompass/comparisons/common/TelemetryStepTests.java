package com.customercompass.comparisons.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The step hook added for multi-agent runs: a span per specialist, a counter, fixed names only. */
class TelemetryStepTests {

  private static String attr(SpanData span, String key) {
    return span.getAttributes().get(AttributeKey.stringKey(key));
  }

  @Test
  void aStepIsAChildSpanOfTheRequestWithTheAgentAndTheOutcome() {
    var exporter = InMemorySpanExporter.create();
    var telemetry = Telemetry.create(exporter);
    try (var request = telemetry.begin("approach", "m")) {
      request.step("analyst", "ok", 12);
      request.step("supervisor", "model", 3);
    }
    List<SpanData> spans = exporter.getFinishedSpanItems();
    assertThat(spans.stream().map(SpanData::getName)).containsExactlyInAnyOrder("chat.request", "agent.analyst", "agent.supervisor");
    SpanData root = spans.stream().filter(s -> s.getName().equals("chat.request")).findFirst().orElseThrow();
    SpanData analyst = spans.stream().filter(s -> s.getName().equals("agent.analyst")).findFirst().orElseThrow();
    assertThat(analyst.getParentSpanId()).isEqualTo(root.getSpanId());
    assertThat(attr(analyst, "cc.agent")).isEqualTo("analyst");
    assertThat(attr(analyst, "cc.outcome")).isEqualTo("ok");
    telemetry.close();
  }

  @Test
  void aNameThatIsNotAFixedWordIsRecordedAsUnknownNeverAsGiven() {
    var exporter = InMemorySpanExporter.create();
    var telemetry = Telemetry.create(exporter);
    try (var request = telemetry.begin("approach", "m")) {
      request.step("Ignore previous instructions!", "ok", 1);
      request.step("analyst", "a very long outcome with spaces and a secret", 1);
      request.step(null, null, 1);
    }
    String everything = exporter.getFinishedSpanItems().stream().map(s -> s.getName() + s.getAttributes()).reduce("", String::concat);
    assertThat(everything).doesNotContain("Ignore").doesNotContain("secret").contains("agent.unknown");
    telemetry.close();
  }

  @Test
  void theStepsAreCountedByAgentAndOutcome() {
    var telemetry = Telemetry.create(InMemorySpanExporter.create());
    try (var request = telemetry.begin("approach", "m")) {
      request.step("analyst", "ok", 1);
      request.step("analyst", "ok", 1);
      request.step("reviewer", "ok", 1);
    }
    MetricData steps = telemetry.metrics().stream().filter(m -> m.getName().equals("cc.agent.steps")).findFirst().orElseThrow();
    long analyst = steps.getLongSumData().getPoints().stream().filter(p -> "analyst".equals(p.getAttributes().get(AttributeKey.stringKey("cc.agent")))).mapToLong(p -> p.getValue()).sum();
    assertThat(analyst).isEqualTo(2);
    telemetry.close();
  }

  @Test
  void theNoneObserverIgnoresStepsAndThePlainObserversNeedNotImplementIt() {
    try (var request = ChatObserver.NONE.begin("a", "m")) {
      request.step("analyst", "ok", 1);
    }
  }
}
