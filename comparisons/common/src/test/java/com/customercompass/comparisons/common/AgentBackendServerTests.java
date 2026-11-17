package com.customercompass.comparisons.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AgentBackendServerTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ID = "11111111-2222-3333-4444-555555555555";
  private final HttpClient http = HttpClient.newHttpClient();
  private final List<String> calls = new ArrayList<>();
  private AgentBackendServer server;
  private RuntimeException failure;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop();
    }
  }

  private void start() throws Exception {
    server =
        new AgentBackendServer(
            0,
            new Agent() {
              @Override
              public String approach() {
                return "fake";
              }

              @Override
              public Outcome start(String company, String model) {
                calls.add("start|" + company + "|" + model);
                if (failure != null) {
                  throw failure;
                }
                return outcome("awaiting_approval");
              }

              @Override
              public Outcome resume(String runId, boolean approve) {
                calls.add("resume|" + runId + "|" + approve);
                if (failure != null) {
                  throw failure;
                }
                return outcome(approve ? "completed" : "declined");
              }
            });
    server.start();
  }

  private static Agent.Outcome outcome(String status) {
    return new Agent.Outcome(
        ID,
        status,
        List.of(new Agent.Step("gather", "done"), new Agent.Step("recommend", "done"), new Agent.Step("approval", "waiting"), new Agent.Step("act", "pending")),
        new Recommender.Recommendation("Call them.", "Because."),
        "a message");
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void startPassesTheCompanyAndTheModelAndAnswersWithTheRun() throws Exception {
    start();
    var response = post("/api/v1/agent/start", "{\"company\": \"  Acme  \", \"model\": \"m/x\"}");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(calls).containsExactly("start|Acme|m/x");
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("runId").asString()).isEqualTo(ID);
    assertThat(body.get("status").asString()).isEqualTo("awaiting_approval");
    assertThat(body.get("steps")).hasSize(4);
    assertThat(body.get("steps").get(2).get("status").asString()).isEqualTo("waiting");
    assertThat(body.get("recommendation").get("action").asString()).isEqualTo("Call them.");
    assertThat(body.get("message").asString()).isEqualTo("a message");
  }

  @Test
  void startWithoutAModelPassesNull() throws Exception {
    start();
    post("/api/v1/agent/start", "{\"company\": \"Acme\"}");
    assertThat(calls).containsExactly("start|Acme|null");
  }

  @Test
  void resumePassesTheRunAndTheAnswer() throws Exception {
    start();
    assertThat(JSON.readTree(post("/api/v1/agent/resume", "{\"runId\": \"" + ID + "\", \"approve\": true}").body()).get("status").asString()).isEqualTo("completed");
    assertThat(JSON.readTree(post("/api/v1/agent/resume", "{\"runId\": \"" + ID + "\", \"approve\": false}").body()).get("status").asString()).isEqualTo("declined");
    assertThat(calls).containsExactly("resume|" + ID + "|true", "resume|" + ID + "|false");
  }

  @Test
  void aBadStartIsRefusedAndNothingRuns() throws Exception {
    start();
    for (String body : List.of("", "not json", "[1]", "{}", "{\"company\": \"\"}", "{\"company\": 5}", "{\"company\": \"" + "x".repeat(201) + "\"}", "{\"company\": \"A\", \"model\": 5}", "{\"company\": \"A\", \"model\": \"" + "m".repeat(101) + "\"}")) {
      assertThat(post("/api/v1/agent/start", body).statusCode()).as(body).isEqualTo(422);
    }
    assertThat(calls).isEmpty();
  }

  @Test
  void aBadResumeIsRefusedAndNothingRuns() throws Exception {
    start();
    for (String body : List.of("{}", "{\"runId\": \"" + ID + "\"}", "{\"runId\": \"" + ID + "\", \"approve\": \"yes\"}", "{\"runId\": \"../etc\", \"approve\": true}", "{\"runId\": 5, \"approve\": true}", "{\"runId\": \"abc\", \"approve\": true}")) {
      assertThat(post("/api/v1/agent/resume", body).statusCode()).as(body).isEqualTo(422);
    }
    assertThat(calls).isEmpty();
  }

  @Test
  void theErrorsMapToTheirStatuses() throws Exception {
    start();
    failure = new Agent.CompanyNotFoundException("no company");
    assertThat(post("/api/v1/agent/start", "{\"company\": \"A\"}").statusCode()).isEqualTo(404);
    failure = new Agent.UnknownRunException("no run");
    assertThat(post("/api/v1/agent/resume", "{\"runId\": \"" + ID + "\", \"approve\": true}").statusCode()).isEqualTo(404);
    failure = new Agent.NotAwaitingApprovalException("already answered");
    assertThat(post("/api/v1/agent/resume", "{\"runId\": \"" + ID + "\", \"approve\": true}").statusCode()).isEqualTo(409);
    failure = new Agent.UnavailableException("The model is not reachable.");
    var response = post("/api/v1/agent/start", "{\"company\": \"A\"}");
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(JSON.readTree(response.body()).get("detail").asString()).isEqualTo("The model is not reachable.");
  }

  @Test
  void anyOtherFailureIsAnswered503WithoutItsText() throws Exception {
    start();
    failure = new IllegalStateException("http://internal-host/secret path");
    var response = post("/api/v1/agent/start", "{\"company\": \"A\"}");
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).doesNotContain("secret").contains("The agent could not run.");
  }

  @Test
  void healthNamesTheApproachAndOtherMethodsAreRefused() throws Exception {
    start();
    var health = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health")).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(JSON.readTree(health.body()).get("approach").asString()).isEqualTo("fake");
    var get = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/v1/agent/start")).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(get.statusCode()).isEqualTo(405);
  }
}
