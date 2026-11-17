package com.customercompass.comparisons.multiagent.network.withlanggraph4jspringai;

import static com.customercompass.comparisons.multiagent.network.withlanggraph4jspringai.Fixtures.choose;
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

/** The same signals as the supervisor: a span per peer turn and per choice, the model and tool calls, and never any content. */
class NetworkTelemetryTests {

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
    return new NetworkTeam(crm.tools(), llm.url(), "key", "test/model", 10, telemetry).run("Acme", null);
  }

  private static String attr(SpanData span, String key) {
    return span.getAttributes().get(AttributeKey.stringKey(key));
  }

  private long count(String name) {
    return spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name)).count();
  }

  private List<String> outcomesOf(String spanName) {
    var outcomes = new ArrayList<String>();
    for (SpanData span : spans.getFinishedSpanItems()) {
      if (span.getName().equals(spanName)) {
        outcomes.add(attr(span, "cc.outcome"));
      }
    }
    return outcomes;
  }

  @Test
  void aRunIsOneTraceWithASpanPerPeerPerChoicePerModelCallAndPerTool() {
    observedRun();
    assertThat(spans.getFinishedSpanItems().stream().map(SpanData::getTraceId).distinct()).hasSize(1);
    assertThat(count("chat.request")).isEqualTo(1);
    assertThat(count("agent.peer_choice")).isEqualTo(4);
    assertThat(count("agent.crm_facts")).isEqualTo(1);
    assertThat(count("agent.deals")).isEqualTo(1);
    assertThat(count("agent.analyst")).isEqualTo(1);
    assertThat(count("agent.reviewer")).isEqualTo(1);
    assertThat(count("llm.generate")).isEqualTo(6);
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
  void everyPeerChoiceInTheHappyPathWasMadeByAModel() {
    observedRun();
    assertThat(outcomesOf("agent.peer_choice")).containsExactly("model", "model", "model", "model");
  }

  @Test
  void aPeerCalledWithoutItsInputsIsABlockedStepAndItsChoiceIsMadeByCode() {
    llm.script(choose("reviewer"), choose("reviewer"), choose("FINISH"));
    observedRun();
    assertThat(outcomesOf("agent.reviewer")).containsExactlyInAnyOrder("blocked", "ok");
    assertThat(outcomesOf("agent.peer_choice")).containsExactlyInAnyOrder("model", "code", "model", "model");
  }

  @Test
  void aChoiceThatWasReplacedIsAnOverrideStep() {
    llm.script("not a name");
    observedRun();
    assertThat(outcomesOf("agent.peer_choice")).contains("override");
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
    assertThat(input).isEqualTo(120);
  }

  @Test
  void aFailedRunIsAFailedRequestAndAnErroredModelCall() {
    llm.failStatus = 500;
    var team = new NetworkTeam(crm.tools(), llm.url(), "key", "test/model", 10, telemetry);
    assertThatThrownBy(() -> team.run("Acme", null)).isInstanceOf(MultiAgent.UnavailableException.class);
    SpanData root = spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals("chat.request")).findFirst().orElseThrow();
    assertThat(attr(root, "cc.status")).isEqualTo("error");
    assertThat(attr(root, "cc.failure")).isEqualTo("unavailable");
    assertThat(outcomesOf("llm.generate")).contains("error");
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
    assertThat(everything).contains("agent.analyst", "agent.peer_choice", "withLangGraph4jSpringAi");
    for (String content : List.of("my-secret-word", "Call Maya Chen", "Acme", "Premium Operations Plan", "because", "fits the facts", "48000")) {
      assertThat(everything).as(content).doesNotContain(content);
    }
    assertThat(telemetry.recentJson()).doesNotContain("my-secret-word").doesNotContain("Call Maya Chen");
  }

  @Test
  void withObservationOffNothingIsRecordedAndTheAnswerIsTheSame() {
    MultiAgent.Outcome outcome = new NetworkTeam(crm.tools(), llm.url(), "key", "test/model", 10, null).run("Acme", null);
    assertThat(outcome.status()).isEqualTo("completed");
    assertThat(spans.getFinishedSpanItems()).isEmpty();
  }
}
