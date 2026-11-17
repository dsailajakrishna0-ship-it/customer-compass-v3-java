package com.customercompass.comparisons.multiagent.handoff.withlanggraph4jspringai;

import static com.customercompass.comparisons.multiagent.handoff.withlanggraph4jspringai.Fixtures.to;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.comparisons.common.MultiAgent;
import com.customercompass.comparisons.common.Telemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The same signals as the other patterns (a span per agent, per handoff, per model call and per tool), and never any content. */
class HandoffTelemetryTests {

  private Fixtures.Crm crm;
  private Fixtures.Llm llm;
  private InMemorySpanExporter spans;
  private Telemetry telemetry;

  @BeforeEach
  void start() throws Exception {
    crm = new Fixtures.Crm();
    llm = new Fixtures.Llm();
    spans = InMemorySpanExporter.create();
    telemetry = Telemetry.create(spans);
  }

  @AfterEach
  void stop() {
    crm.close();
    llm.close();
    telemetry.close();
  }

  private MultiAgent.Outcome observedRun() {
    llm.handoffs(to("researcher"));
    return new HandoffTeam(crm.tools(), llm.url(), "key", "test/model", 10, telemetry).run("Acme", null);
  }

  private static String attr(SpanData span, String key) {
    return span.getAttributes().get(AttributeKey.stringKey(key));
  }

  private long count(String name) {
    return spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name)).count();
  }

  @Test
  void aRunIsOneTraceWithASpanPerHandoffPerAgentPerModelCallAndPerTool() {
    observedRun();
    assertThat(spans.getFinishedSpanItems().stream().map(SpanData::getTraceId).distinct()).hasSize(1);
    assertThat(count("chat.request")).isEqualTo(1);
    assertThat(count("agent.handoff")).isEqualTo(5); // five handoffs
    assertThat(count("agent.triage")).isEqualTo(1);
    assertThat(count("agent.researcher")).isEqualTo(2);
    assertThat(count("agent.analyst")).isEqualTo(1);
    assertThat(count("agent.reviewer")).isEqualTo(1);
    assertThat(count("llm.generate")).isEqualTo(3);
    assertThat(count("tool.get_company_summary")).isEqualTo(1);
    assertThat(count("tool.get_open_deals")).isEqualTo(2);
    SpanData root = spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals("chat.request")).findFirst().orElseThrow();
    assertThat(attr(root, "cc.approach")).isEqualTo("withLangGraph4jSpringAi");
    assertThat(attr(root, "cc.status")).isEqualTo("ok");
    for (SpanData child : spans.getFinishedSpanItems()) {
      if (!child.getName().equals("chat.request")) {
        assertThat(child.getParentSpanId()).isEqualTo(root.getSpanId());
      }
    }
  }

  @Test
  void theHandoffStepsSayWhoDecided() {
    observedRun();
    List<String> outcomes = new ArrayList<>();
    for (SpanData span : spans.getFinishedSpanItems()) {
      if (span.getName().equals("agent.handoff")) {
        outcomes.add(attr(span, "cc.outcome"));
      }
    }
    assertThat(outcomes).containsExactlyInAnyOrder("code", "agent", "code", "code", "agent");
  }

  @Test
  void aFailedRunIsAFailedRequestAndAnErroredModelCall() {
    llm.failStatus = 500;
    var team = new HandoffTeam(crm.tools(), llm.url(), "key", "test/model", 10, telemetry);
    assertThatThrownBy(() -> team.run("Acme", null)).isInstanceOf(MultiAgent.UnavailableException.class);
    SpanData root = spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals("chat.request")).findFirst().orElseThrow();
    assertThat(attr(root, "cc.status")).isEqualTo("error");
    assertThat(attr(root, "cc.failure")).isEqualTo("unavailable");
    assertThat(spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals("llm.generate")).map(s -> attr(s, "cc.outcome"))).contains("error");
  }

  @Test
  void noContentIsEverRecordedNotTheCompanyNotTheCrmTextNotTheAnswerNotTheReason() {
    crm.industry = "my-secret-word";
    observedRun();
    var texts = new ArrayList<String>();
    for (SpanData span : spans.getFinishedSpanItems()) {
      texts.add(span.getName());
      span.getAttributes().forEach((key, value) -> texts.add(key.getKey() + "=" + value));
    }
    for (MetricData metric : telemetry.metrics()) {
      texts.add(metric.getName());
      metric.getData().getPoints().forEach(point -> point.getAttributes().forEach((key, value) -> texts.add(key.getKey() + "=" + value)));
    }
    String everything = String.join("\n", texts);
    assertThat(everything).contains("agent.analyst", "withLangGraph4jSpringAi");
    for (String content : List.of("my-secret-word", "Call Maya Chen", "Acme", "Premium Operations Plan", "because", "fits the facts", "48000")) {
      assertThat(everything).as(content).doesNotContain(content);
    }
    assertThat(telemetry.recentJson()).doesNotContain("my-secret-word").doesNotContain("Call Maya Chen");
  }

  @Test
  void withObservationOffNothingIsRecordedAndTheAnswerIsTheSame() {
    llm.handoffs(to("analyst"));
    MultiAgent.Outcome outcome = new HandoffTeam(crm.tools(), llm.url(), "key", "test/model", 10, null).run("Acme", null);
    assertThat(outcome.status()).isEqualTo("completed");
    assertThat(spans.getFinishedSpanItems()).isEmpty();
  }
}
