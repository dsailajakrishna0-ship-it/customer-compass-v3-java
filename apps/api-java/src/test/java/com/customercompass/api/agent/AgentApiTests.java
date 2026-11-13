package com.customercompass.api.agent;

import static com.customercompass.api.agent.AgentTestSupport.APPROVE;
import static com.customercompass.api.agent.AgentTestSupport.REVISE;
import static com.customercompass.api.agent.AgentTestSupport.recommendation;
import static org.assertj.core.api.Assertions.assertThat;

import com.customercompass.api.agent.AgentTestSupport.FakeRetriever;
import com.customercompass.api.agent.AgentTestSupport.RoleModel;
import com.customercompass.api.rag.Retriever;
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
 * POST /agent/recommend and /agent/confirm over real HTTP with a real Postgres: the documented
 * shapes, the trace, the reviewer loop, the 404 and the 422s, and that recommending never writes.
 * Only the model (which answers by agent role) and document search (no embeddings) are faked.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentApiTests {

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

  private static final String ACME = "Analyze Acme Fabrication and recommend a next action";
  private static final String NOTE = "TEST: send the pricing breakdown";

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired RoleModel model;
  @Autowired Retriever retriever;

  @BeforeEach
  void fresh() {
    model.reset();
    ((FakeRetriever) retriever).noEmbeddings = false;
  }

  @AfterEach
  void cleanUp() {
    jdbc.sql("DELETE FROM tasks WHERE note LIKE 'TEST:%'").update();
    jdbc.sql("DELETE FROM tool_audit_log").update();
  }

  private RestClient http() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
        .build();
  }

  private org.springframework.http.ResponseEntity<Map> postRaw(String path, String body) {
    return http()
        .post()
        .uri(path)
        .header("Content-Type", "application/json")
        .body(body)
        .retrieve()
        .toEntity(Map.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> recommend(String message) {
    return postRaw("/api/v1/agent/recommend", "{\"message\":\"" + message + "\"}").getBody();
  }

  private long tasks() {
    return jdbc.sql("SELECT count(*) FROM tasks").query(Long.class).single();
  }

  // ------------------------------------------------------------------ recommend

  @Test
  @SuppressWarnings("unchecked")
  void aCompletedRunHasExactlyTheDocumentedShapeInTheDocumentedOrder() {
    Map<String, Object> body = recommend(ACME);

    assertThat(body.keySet())
        .containsExactly(
            "route",
            "status",
            "trace",
            "company",
            "evidence",
            "recommendation",
            "review",
            "proposedTask",
            "guardrail",
            "llmCalls");
    assertThat(body).containsEntry("route", "agent").containsEntry("status", "completed");
    var company = (Map<String, Object>) body.get("company");
    assertThat(company.keySet()).containsExactly("id", "name");
    assertThat(company).containsEntry("name", "Acme Fabrication");
    var rec = (Map<String, Object>) body.get("recommendation");
    assertThat(rec.keySet()).containsExactly("action", "rationale", "evidence");
    assertThat(rec).containsEntry("action", "Send the pricing breakdown");
    var review = (Map<String, Object>) body.get("review");
    assertThat(review.keySet()).containsExactly("approved", "verdict", "reasons", "revisions");
    assertThat(review).containsEntry("approved", true).containsEntry("revisions", 0);
    assertThat(body.get("proposedTask"))
        .isEqualTo(
            Map.of(
                "companyName", "Acme Fabrication", "note", "Send the pricing breakdown to Acme"));
    assertThat(body.get("guardrail")).isEqualTo(Map.of("flagged", false, "reasons", List.of()));
    var evidence = (List<Map<String, Object>>) body.get("evidence");
    assertThat(evidence)
        .isNotEmpty()
        .allSatisfy(e -> assertThat(e.keySet()).containsExactly("label", "source", "text"));
    assertThat(evidence)
        .extracting(e -> e.get("label"))
        .containsExactlyElementsOf(
            java.util.stream.IntStream.rangeClosed(1, evidence.size())
                .mapToObj(i -> "E" + i)
                .toList());
    assertThat(evidence.get(0).get("text").toString())
        .startsWith(
            "Acme Fabrication: industry Manufacturing, status customer, 1 won / 0 lost deals.");
    assertThat(evidence.get(1).get("text").toString())
        .isEqualTo("Premium Operations Plan renewal, 48000.00, expected close 2026-10-15.");
  }

  @Test
  @SuppressWarnings("unchecked")
  void theTraceIsAnOrderedRecordOfEveryStep() {
    var trace = (List<Map<String, Object>>) recommend(ACME).get("trace");

    assertThat(trace)
        .allSatisfy(
            step ->
                assertThat(step.keySet())
                    .containsExactly(
                        "index",
                        "kind",
                        "name",
                        "input_summary",
                        "output_summary",
                        "status",
                        "duration_ms"));
    assertThat(trace).extracting(s -> s.get("index")).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    assertThat(trace)
        .extracting(s -> s.get("name"))
        .containsExactly(
            "identify_company",
            "get_company_summary",
            "get_recent_interactions",
            "search_documents",
            "Analyst agent",
            "Reviewer agent",
            "Task Drafter agent",
            "awaiting_user_confirmation");
    assertThat(trace)
        .extracting(s -> s.get("kind"))
        .containsExactly("code", "tool", "code", "tool", "agent", "agent", "agent", "gate");
    assertThat(trace.get(7))
        .containsEntry("input_summary", "task not created")
        .containsEntry("output_summary", "proposed: Send the pricing breakdown to Acme");
    assertThat(trace).allSatisfy(s -> assertThat((Integer) s.get("duration_ms")).isNotNegative());
  }

  @Test
  @SuppressWarnings("unchecked")
  void theAgentsAreAskedInOrderAndEachCallIsReported() {
    Map<String, Object> body = recommend(ACME);

    assertThat(model.calls).containsExactly("analyst", "reviewer", "task_drafter");
    var calls = (List<Map<String, Object>>) body.get("llmCalls");
    assertThat(calls).extracting(c -> c.get("agent")).containsExactlyElementsOf(model.calls);
    assertThat(calls)
        .allSatisfy(
            c ->
                assertThat(c.keySet())
                    .containsExactly("agent", "model", "latencyMs", "fallbackUsed"));
  }

  @Test
  void recommendingNeverWritesATask() {
    long before = tasks();

    recommend(ACME);

    assertThat(tasks()).isEqualTo(before);
  }

  @Test
  void aSimpleQuestionIsSentBackToTheDirectModesAndNoAgentIsAsked() {
    Map<String, Object> body = recommend("What is Acme Fabrication's industry?");

    assertThat(body.keySet()).containsExactly("route", "message");
    assertThat(body)
        .containsEntry("route", "direct")
        .containsEntry(
            "message",
            "This looks like a simple question. Use the CRM copilot or document chat mode instead of the agent.");
    assertThat(model.calls).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void anUnknownCompanyStopsBeforeAnyAgent() {
    Map<String, Object> body = recommend("Analyze Globex and recommend a next action");

    assertThat(body)
        .containsEntry("route", "agent")
        .containsEntry("status", "company_not_found")
        .containsEntry("message", "Name a company from the CRM to analyze.");
    var trace = (List<Map<String, Object>>) body.get("trace");
    assertThat(trace).extracting(s -> s.get("name")).containsExactly("identify_company");
    assertThat(trace.get(0))
        .containsEntry("status", "error")
        .containsEntry("output_summary", "no company found");
    assertThat(model.calls).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void theReviewerCanSendTheAnalystBackOnce() {
    model.reviewer(REVISE, APPROVE);

    Map<String, Object> body = recommend(ACME);

    assertThat(model.calls)
        .containsExactly("analyst", "reviewer", "analyst", "reviewer", "task_drafter");
    var review = (Map<String, Object>) body.get("review");
    assertThat(review).containsEntry("revisions", 1).containsEntry("approved", true);
    assertThat((List<Map<String, Object>>) body.get("trace"))
        .extracting(s -> s.get("name"))
        .contains("Analyst agent (revision)");
  }

  @Test
  @SuppressWarnings("unchecked")
  void revisionsAreBoundedAndAnUnapprovedResultIsFlaggedNotHidden() {
    model.reviewer(REVISE);

    Map<String, Object> body = recommend(ACME);

    assertThat(body).containsEntry("status", "completed");
    var review = (Map<String, Object>) body.get("review");
    assertThat(review).containsEntry("approved", false).containsEntry("revisions", 1);
    assertThat(model.calls.stream().filter("analyst"::equals).count()).isEqualTo(2);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aRecommendationThatCitesNothingCannotBeApprovedEvenIfTheReviewerApproves() {
    model.analyst(recommendation("Call", "r"));
    model.reviewer(APPROVE, APPROVE);

    var review = (Map<String, Object>) recommend(ACME).get("review");

    assertThat(review).containsEntry("revisions", 1).containsEntry("approved", false);
    assertThat((List<String>) review.get("reasons")).contains("No evidence was cited.");
  }

  @Test
  @SuppressWarnings("unchecked")
  void citingEvidenceThatDoesNotExistCannotBeApproved() {
    model.analyst(recommendation("Call", "r", "E99"));

    var review = (Map<String, Object>) recommend(ACME).get("review");

    assertThat(review).containsEntry("approved", false);
    assertThat((List<String>) review.get("reasons")).anyMatch(reason -> reason.contains("E99"));
  }

  @Test
  void withNoModelTheApiSaysWhatToFixWithA503AndWritesNothing() {
    model.down = true;
    long before = tasks();

    var reply = postRaw("/api/v1/agent/recommend", "{\"message\":\"" + ACME + "\"}");

    assertThat(reply.getStatusCode().value()).isEqualTo(503);
    assertThat(String.valueOf(reply.getBody().get("error")))
        .contains("model chain is currently unavailable");
    assertThat(tasks()).isEqualTo(before);
  }

  @Test
  void withNoEmbeddingsKeyTheApiSaysWhatToFixWithA503() {
    ((FakeRetriever) retriever).noEmbeddings = true;

    var reply = postRaw("/api/v1/agent/recommend", "{\"message\":\"" + ACME + "\"}");

    assertThat(reply.getStatusCode().value()).isEqualTo(503);
    assertThat(String.valueOf(reply.getBody().get("error"))).contains("GOOGLE_API_KEY is not set");
  }

  @Test
  @SuppressWarnings("unchecked")
  void aBadRecommendRequestIs422WithFastApisProblems() {
    var empty = postRaw("/api/v1/agent/recommend", "{}");
    var blank = postRaw("/api/v1/agent/recommend", "{\"message\":\"\"}");
    var tooLong = postRaw("/api/v1/agent/recommend", "{\"message\":\"" + "x".repeat(1001) + "\"}");
    var badModel = postRaw("/api/v1/agent/recommend", "{\"message\":\"Analyze Acme\",\"model\":5}");

    assertThat(empty.getStatusCode().value()).isEqualTo(422);
    assertThat(((List<Map<String, Object>>) empty.getBody().get("detail")).get(0))
        .isEqualTo(
            Map.of(
                "type",
                "missing",
                "loc",
                List.of("body", "message"),
                "msg",
                "Field required",
                "input",
                Map.of()));
    assertThat(((List<Map<String, Object>>) blank.getBody().get("detail")).get(0))
        .containsEntry("type", "string_too_short")
        .containsEntry("msg", "String should have at least 1 character")
        .containsEntry("ctx", Map.of("min_length", 1));
    assertThat(((List<Map<String, Object>>) tooLong.getBody().get("detail")).get(0))
        .containsEntry("type", "string_too_long")
        .containsEntry("ctx", Map.of("max_length", 1000));
    assertThat(((List<Map<String, Object>>) badModel.getBody().get("detail")).get(0))
        .containsEntry("type", "string_type")
        .containsEntry("loc", List.of("body", "model"));
    assertThat(model.calls).isEmpty();
  }

  // ------------------------------------------------------------------ confirm

  @Test
  void decliningWritesNothing() {
    long before = tasks();

    var reply =
        postRaw(
            "/api/v1/agent/confirm",
            "{\"companyName\":\"Acme Fabrication\",\"note\":\"" + NOTE + "\",\"confirm\":false}");

    assertThat(reply.getStatusCode().value()).isEqualTo(200);
    assertThat(reply.getBody())
        .isEqualTo(Map.of("status", "declined", "message", "No task was created."));
    assertThat(tasks()).isEqualTo(before);
  }

  @Test
  @SuppressWarnings("unchecked")
  void confirmingCreatesExactlyOneTaskWithTheDocumentedShapeAndAuditsIt() {
    long before = tasks();

    var reply =
        postRaw(
            "/api/v1/agent/confirm",
            "{\"companyName\":\"Acme\",\"note\":\"" + NOTE + "\",\"confirm\":true}");

    assertThat(reply.getStatusCode().value()).isEqualTo(200);
    Map<String, Object> body = reply.getBody();
    assertThat(body).containsEntry("status", "confirmed");
    var task = (Map<String, Object>) body.get("task");
    assertThat(task.keySet())
        .containsExactlyInAnyOrder("id", "companyName", "note", "status", "createdAt");
    assertThat(task)
        .containsEntry("companyName", "Acme Fabrication")
        .containsEntry("status", "open");
    assertThat(tasks()).isEqualTo(before + 1);
    var listed = http().get().uri("/api/v1/tasks").retrieve().body(List.class);
    assertThat(((List<Map<String, Object>>) listed))
        .anyMatch(t -> t.get("id").equals(task.get("id")));
    assertThat(
            jdbc.sql("SELECT result_summary FROM tool_audit_log WHERE status = 'confirmed'")
                .query(String.class)
                .list())
        .anyMatch(summary -> summary.contains("#" + task.get("id")));
  }

  @Test
  void anUnknownCompanyIsA404WithAMessageAndWritesNothing() {
    long before = tasks();

    var reply =
        postRaw(
            "/api/v1/agent/confirm",
            "{\"companyName\":\"Globex\",\"note\":\"" + NOTE + "\",\"confirm\":true}");

    assertThat(reply.getStatusCode().value()).isEqualTo(404);
    assertThat(String.valueOf(reply.getBody().get("error"))).contains("Globex");
    assertThat(tasks()).isEqualTo(before);
  }

  @Test
  void aBadConfirmRequestIs422AndWritesNothing() {
    long before = tasks();

    for (String bad :
        List.of(
            "{\"companyName\":\"Acme\",\"note\":\"x\"}", // confirm is required: no silent default
            // to "write"
            "{\"companyName\":\"\",\"note\":\"x\",\"confirm\":true}",
            "{\"companyName\":\"Acme\",\"note\":\"\",\"confirm\":true}",
            "{\"companyName\":\"Acme\",\"note\":\"" + "x".repeat(301) + "\",\"confirm\":true}",
            "{\"companyName\":\"Acme\",\"note\":\"x\",\"confirm\":\"maybe\"}")) {
      assertThat(postRaw("/api/v1/agent/confirm", bad).getStatusCode().value())
          .as(bad)
          .isEqualTo(422);
    }
    assertThat(tasks()).isEqualTo(before);
  }
}
