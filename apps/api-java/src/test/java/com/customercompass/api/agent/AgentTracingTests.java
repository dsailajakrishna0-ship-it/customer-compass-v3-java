package com.customercompass.api.agent;

import static com.customercompass.api.agent.AgentTestSupport.recommendation;
import static org.assertj.core.api.Assertions.assertThat;

import com.customercompass.api.agent.AgentTestSupport.FakeRetriever;
import com.customercompass.api.agent.AgentTestSupport.RoleModel;
import com.customercompass.api.observability.Tracing;
import com.customercompass.api.rag.retrieval.Retriever;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Tracing end to end (J7.3, ADR 0027): one real HTTP request through the real application with an
 * in-memory exporter. The request id is the trace id; the agent run, its steps, the model calls and
 * the guard nest under the request, including on the threads the graph runs on; and no span holds
 * what the user or the model wrote. Real Postgres, scripted agents, no key, no network.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentTracingTests {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry registry) {
    registry.add("llm.openrouter-api-key", () -> "test-key-not-real");
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    RoleModel roleModel() {
      return new RoleModel();
    }

    @Bean
    @Primary
    Retriever fakeRetriever() {
      return new FakeRetriever();
    }
  }

  private static final String REQUEST_ID = "0123456789abcdef0123456789abcdef";
  private static final String ASKED =
      "Analyze Acme Fabrication and recommend a next action zebra-7731";
  private static final String ANSWERED = "quokka-4421";

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired RoleModel model;

  InMemorySpanExporter exporter;

  @BeforeEach
  void on() {
    model.reset();
    model.analyst(
        recommendation("Send the pricing breakdown " + ANSWERED, "Acme asked " + ANSWERED, "E1"));
    exporter = InMemorySpanExporter.create();
    Tracing.install(exporter, "test", false);
  }

  @AfterEach
  void off() {
    Tracing.shutdown();
    jdbc.sql("DELETE FROM tool_audit_log").update();
  }

  private RestClient http() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
        .build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> post(String path, String body) {
    return http()
        .post()
        .uri(path)
        .header("Content-Type", "application/json")
        .header("X-Request-ID", REQUEST_ID)
        .body(body)
        .retrieve()
        .body(Map.class);
  }

  private SpanData named(String name) {
    return exporter.getFinishedSpanItems().stream()
        .filter(s -> s.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no span named " + name));
  }

  private List<SpanData> allNamed(String prefix) {
    return exporter.getFinishedSpanItems().stream()
        .filter(s -> s.getName().startsWith(prefix))
        .toList();
  }

  @Test
  void theRequestIsTheRootAndItsIdIsTheTraceId() {
    post("/api/v1/agent/recommend", "{\"message\":\"" + ASKED + "\"}");

    SpanData root = named("POST /api/v1/agent/recommend");
    assertThat(root.getTraceId()).isEqualTo(REQUEST_ID);
    assertThat(root.getParentSpanId()).isEqualTo("0000000000000000");
    assertThat(root.getAttributes().get(AttributeKey.longKey("http.status"))).isEqualTo(200L);
    assertThat(exporter.getFinishedSpanItems()).allMatch(s -> s.getTraceId().equals(REQUEST_ID));
  }

  @Test
  void theAgentRunItsStepsAndItsModelCallsNestUnderTheRequest() {
    post("/api/v1/agent/recommend", "{\"message\":\"" + ASKED + "\"}");

    SpanData root = named("POST /api/v1/agent/recommend");
    SpanData run = named("agent.run");
    assertThat(run.getParentSpanId()).isEqualTo(root.getSpanId());
    assertThat(run.getAttributes().get(AttributeKey.stringKey("agent.status")))
        .isEqualTo("completed");
    assertThat(run.getAttributes().get(AttributeKey.longKey("agent.steps"))).isGreaterThan(4L);

    List<SpanData> steps =
        allNamed("agent.").stream().filter(s -> !s.getName().equals("agent.run")).toList();
    assertThat(steps).isNotEmpty().allMatch(s -> s.getParentSpanId().equals(run.getSpanId()));

    // the model calls ran on other threads (the graph's), and still hang under the run
    List<SpanData> generations = allNamed("llm.generate");
    assertThat(generations).hasSizeGreaterThanOrEqualTo(3);
    assertThat(generations).allMatch(s -> s.getParentSpanId().equals(run.getSpanId()));
    assertThat(generations.get(0).getAttributes().get(AttributeKey.longKey("llm.tokens_in")))
        .isEqualTo(30L);
  }

  @Test
  void theGuardLeavesASpanForTheQuestionAndForTheAnswer() {
    post("/api/v1/agent/recommend", "{\"message\":\"" + ASKED + "\"}");

    assertThat(named("guard.input").getAttributes().get(AttributeKey.booleanKey("guard.flagged")))
        .isFalse();
    assertThat(allNamed("guard.")).hasSizeGreaterThanOrEqualTo(1);
  }

  @Test
  void noSpanHoldsWhatTheUserAskedOrWhatTheModelAnswered() {
    post("/api/v1/agent/recommend", "{\"message\":\"" + ASKED + "\"}");

    var everything = new ArrayList<String>();
    exporter
        .getFinishedSpanItems()
        .forEach(
            s -> {
              everything.add(s.getName());
              s.getAttributes().forEach((k, v) -> everything.add(k.getKey() + "=" + v));
              everything.add(String.valueOf(s.getStatus().getDescription()));
              s.getEvents().forEach(e -> everything.add(e.getName() + e.getAttributes()));
            });
    String all = String.join("\n", everything);
    assertThat(exporter.getFinishedSpanItems()).hasSizeGreaterThan(8);
    assertThat(all).doesNotContain("zebra-7731", ANSWERED, "Send the pricing breakdown");
  }

  @Test
  void aSupervisorRunIsTracedToo() {
    model.supervisor(
        "{\"next\": \"crm_facts\"}",
        "{\"next\": \"analyst\"}",
        "{\"next\": \"reviewer\"}",
        "{\"next\": \"drafter\"}");

    post("/api/v1/agent/supervise", "{\"message\":\"" + ASKED + "\"}");

    SpanData run = named("agent.run");
    assertThat(run.getAttributes().get(AttributeKey.stringKey("agent.kind")))
        .isEqualTo("supervisor");
    assertThat(allNamed("llm.generate")).isNotEmpty();
  }

  @Test
  void aDeclinedConfirmIsTraced() {
    post("/api/v1/agent/recommend", "{\"message\":\"" + ASKED + "\"}");
    exporter.reset();

    post(
        "/api/v1/agent/confirm",
        "{\"companyName\":\"Acme Fabrication\",\"note\":\"Send the pricing breakdown to Acme\",\"confirm\":false}");

    SpanData resume = named("agent.resume");
    assertThat(resume.getAttributes().get(AttributeKey.booleanKey("confirm"))).isFalse();
    assertThat(resume.getAttributes().get(AttributeKey.booleanKey("paused_run_found"))).isNotNull();
  }

  @Test
  void aRunWithTracingOffIsTheSameRunWithNoSpans() {
    Tracing.shutdown();

    Map<String, Object> body = post("/api/v1/agent/recommend", "{\"message\":\"" + ASKED + "\"}");

    assertThat(body).containsEntry("status", "completed");
    assertThat(exporter.getFinishedSpanItems()).isEmpty();
  }
}
