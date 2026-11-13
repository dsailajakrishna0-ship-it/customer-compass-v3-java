package com.customercompass.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.customercompass.api.agent.AgentTestSupport.FakeRetriever;
import com.customercompass.api.agent.AgentTestSupport.RoleModel;
import com.customercompass.api.mcp.CrmTools;
import com.customercompass.api.rag.Retriever;
import java.util.Map;
import javax.sql.DataSource;
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
 * The durable pause over real HTTP and a real Postgres: /agent/recommend leaves the run saved at
 * the gate, /agent/confirm resumes that run (and only that run), and a paused run survives an API
 * restart. The Python edition's resume tests, ported.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentPauseTests {

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
  private static final String DRAFTED = "TEST: send pricing";

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired RoleModel model;
  @Autowired ApprovalGate gate;
  @Autowired DataSource dataSource;
  @Autowired CrmTools tools;

  @BeforeEach
  void fresh() {
    model.reset();
    // the drafter writes a note the tests can recognise and clean up
    model.drafter("{\"task_note\": \"" + DRAFTED + "\"}");
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

  @SuppressWarnings("unchecked")
  private Map<String, Object> post(String path, String body) {
    return http()
        .post()
        .uri(path)
        .header("Content-Type", "application/json")
        .body(body)
        .retrieve()
        .body(Map.class);
  }

  /**
   * Runs the agent and returns the proposed task (company name and note) the user would confirm.
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> recommend() {
    Map<String, Object> body = post("/api/v1/agent/recommend", "{\"message\":\"" + ACME + "\"}");
    return (Map<String, Object>) body.get("proposedTask");
  }

  private String confirmBody(Map<String, Object> proposed, boolean confirm) {
    return "{\"companyName\":\"%s\",\"note\":\"%s\",\"confirm\":%s}"
        .formatted(proposed.get("companyName"), proposed.get("note"), confirm);
  }

  private long tasks() {
    return jdbc.sql("SELECT count(*) FROM tasks").query(Long.class).single();
  }

  @Test
  void recommendLeavesTheRunPausedInPostgresAndWritesNothing() {
    long before = tasks();

    Map<String, Object> proposed = recommend();

    assertThat(proposed).containsEntry("note", DRAFTED);
    assertThat(gate.isPaused((String) proposed.get("companyName"), (String) proposed.get("note")))
        .as("the run is saved at the gate")
        .isTrue();
    assertThat(tasks()).isEqualTo(before);
  }

  @Test
  @SuppressWarnings("unchecked")
  void confirmResumesThePausedRunAndWritesExactlyOnce() {
    Map<String, Object> proposed = recommend();
    long before = tasks();

    Map<String, Object> reply = post("/api/v1/agent/confirm", confirmBody(proposed, true));

    assertThat(reply).containsEntry("status", "confirmed");
    assertThat(((Map<String, Object>) reply.get("task"))).containsEntry("note", DRAFTED);
    assertThat(tasks()).isEqualTo(before + 1);
    assertThat(gate.isPaused((String) proposed.get("companyName"), (String) proposed.get("note")))
        .as("the finished run is no longer waiting")
        .isFalse();
    assertThat(
            jdbc.sql("SELECT status FROM tool_audit_log WHERE tool_name = 'create_followup_task'")
                .query(String.class)
                .list())
        .containsExactly("confirmed");
  }

  @Test
  void decliningResumesTheRunWithNoAndWritesNothing() {
    Map<String, Object> proposed = recommend();
    long before = tasks();

    Map<String, Object> reply = post("/api/v1/agent/confirm", confirmBody(proposed, false));

    assertThat(reply).isEqualTo(Map.of("status", "declined", "message", "No task was created."));
    assertThat(tasks()).isEqualTo(before);
    assertThat(gate.isPaused((String) proposed.get("companyName"), (String) proposed.get("note")))
        .isFalse();
  }

  @Test
  void anEditedNoteOrAnotherCompanyFindsNoPausedRunAndWritesDirectly() {
    Map<String, Object> proposed = recommend();
    long before = tasks();

    Map<String, Object> edited =
        post(
            "/api/v1/agent/confirm",
            "{\"companyName\":\"Acme Fabrication\",\"note\":\"TEST: my own wording\",\"confirm\":true}");

    assertThat(edited).containsEntry("status", "confirmed");
    assertThat(tasks()).isEqualTo(before + 1);
    assertThat(gate.isPaused((String) proposed.get("companyName"), (String) proposed.get("note")))
        .as("the paused run is untouched")
        .isTrue();
    assertThat(gate.isPaused("Northstar Logistics", DRAFTED)).isFalse();
  }

  @Test
  void aPausedRunSurvivesAnApiRestart() {
    Map<String, Object> proposed = recommend();
    long before = tasks();

    // A new gate over the same database is what a restarted API starts with: nothing in memory.
    var restarted = new ApprovalGate(dataSource, tools);

    assertThat(
            restarted.isPaused((String) proposed.get("companyName"), (String) proposed.get("note")))
        .isTrue();
    Map<String, Object> result =
        restarted.resume((String) proposed.get("companyName"), (String) proposed.get("note"), true);
    assertThat(result).containsEntry("status", "confirmed");
    assertThat(tasks()).isEqualTo(before + 1);
  }

  @Test
  void noPausedRunMeansTheGateAnswersNull() {
    assertThat(gate.resume("Acme Fabrication", "TEST: never proposed", true)).isNull();
    assertThat(gate.isPaused("Acme Fabrication", "TEST: never proposed")).isFalse();
  }

  @Test
  void theRunIdIsDerivedFromTheCompanyAndTheNoteIgnoringCaseAndSurroundingSpaces() {
    String id = ApprovalGate.threadId("Acme Fabrication", "Send pricing");

    assertThat(id).matches("followup-[0-9a-f]{32}");
    assertThat(ApprovalGate.threadId("  acme FABRICATION ", "  Send pricing  ")).isEqualTo(id);
    assertThat(ApprovalGate.threadId("Acme Fabrication", "Send pricing now")).isNotEqualTo(id);
    assertThat(ApprovalGate.threadId("Northstar Logistics", "Send pricing")).isNotEqualTo(id);
  }

  @Test
  void theSamePauseRunTwiceStillGivesOnePausedRunAndOneTask() {
    Map<String, Object> first = recommend();
    Map<String, Object> second = recommend();
    long before = tasks();

    post("/api/v1/agent/confirm", confirmBody(second, true));

    assertThat(first).isEqualTo(second);
    assertThat(tasks()).isEqualTo(before + 1);
  }
}
