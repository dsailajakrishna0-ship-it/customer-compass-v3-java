package com.customercompass.comparisons.multiagent.supervisor.withlanggraph4jspringai;

import static com.customercompass.comparisons.multiagent.supervisor.withlanggraph4jspringai.Fixtures.choose;
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

/** The same signals as the other J8.4 paths, plus a span per specialist and per decision, and never any content. */
class SupervisorTelemetryTests {

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
    llm.supervisor(choose("deals"), choose("analyst"));
    return new SupervisorTeam(crm.tools(), llm.url(), "key", "test/model", 10, telemetry).run("Acme", null);
  }

  private static String attr(SpanData span, String key) {
    return span.getAttributes().get(AttributeKey.stringKey(key));
  }

  private long count(String name) {
    return spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name)).count();
  }

  @Test
  void aRunIsOneTraceWithASpanPerDecisionPerSpecialistPerModelCallAndPerTool() {
    observedRun();
    assertThat(spans.getFinishedSpanItems().stream().map(SpanData::getTraceId).distinct()).hasSize(1);
    assertThat(count("chat.request")).isEqualTo(1);
    assertThat(count("agent.supervisor")).isEqualTo(5); // five decisions
    assertThat(count("agent.crm_facts")).isEqualTo(1);
    assertThat(count("agent.deals")).isEqualTo(1);
    assertThat(count("agent.analyst")).isEqualTo(1);
    assertThat(count("agent.reviewer")).isEqualTo(1);
    assertThat(count("llm.generate")).isEqualTo(4);
    assertThat(count("tool.get_company_summary")).isEqualTo(1);
    assertThat(count("tool.get_open_deals")).isEqualTo(1);
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
  void theSupervisorStepsSayWhoDecided() {
    observedRun();
    List<String> outcomes = new ArrayList<>();
    for (SpanData span : spans.getFinishedSpanItems()) {
      if (span.getName().equals("agent.supervisor")) {
        outcomes.add(attr(span, "cc.outcome"));
      }
    }
    assertThat(outcomes).containsExactlyInAnyOrder("code", "model", "model", "code", "code");
  }

  @Test
  void modelCallSpansCarryTheTokensFromSpringAisUsage() {
    observedRun();
    for (SpanData span : spans.getFinishedSpanItems()) {
      if (span.getName().equals("llm.generate")) {
        assertThat(attr(span, "gen_ai.request.model")).isEqualTo("test/model");
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(20);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(8);
      }
    }
    MetricData tokens = telemetry.metrics().stream().filter(m -> m.getName().equals("gen_ai.client.token.usage")).findFirst().orElseThrow();
    long input = tokens.getLongSumData().getPoints().stream().filter(p -> "input".equals(p.getAttributes().get(AttributeKey.stringKey("gen_ai.token.type")))).mapToLong(p -> p.getValue()).sum();
    assertThat(input).isEqualTo(80);
  }

  @Test
  void aFailedRunIsAFailedRequestAndAnErroredModelCall() {
    llm.failStatus = 500;
    var team = new SupervisorTeam(crm.tools(), llm.url(), "key", "test/model", 10, telemetry);
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
    for (String content : List.of("my-secret-word", "Call Maya Chen", "Acme", "Premium Operations Plan", "because", "enough facts", "fits the facts", "48000")) {
      assertThat(everything).as(content).doesNotContain(content);
    }
    assertThat(telemetry.recentJson()).doesNotContain("my-secret-word").doesNotContain("Call Maya Chen");
  }

  @Test
  void withObservationOffNothingIsRecordedAndTheAnswerIsTheSame() {
    llm.supervisor(choose("analyst"));
    MultiAgent.Outcome outcome = new SupervisorTeam(crm.tools(), llm.url(), "key", "test/model", 10, null).run("Acme", null);
    assertThat(outcome.status()).isEqualTo("completed");
    assertThat(spans.getFinishedSpanItems()).isEmpty();
  }
}
