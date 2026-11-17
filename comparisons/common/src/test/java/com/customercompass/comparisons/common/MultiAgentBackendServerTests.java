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

class MultiAgentBackendServerTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();
  private final List<String> calls = new ArrayList<>();
  private MultiAgentBackendServer server;
  private RuntimeException failure;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop();
    }
  }

  private void start(Telemetry telemetry) throws Exception {
    server =
        new MultiAgentBackendServer(
            0,
            new MultiAgent() {
              @Override
              public String approach() {
                return "fake";
              }

              @Override
              public String pattern() {
                return "supervisor";
              }

              @Override
              public Outcome run(String company, String model) {
                calls.add(company + "|" + model);
                if (failure != null) {
                  throw failure;
                }
                return new Outcome(
                    "completed",
                    List.of("crm_facts", "analyst"),
                    List.of(
                        new Decision(1, "code", List.of("crm_facts"), "crm_facts", "only one allowed", null),
                        new Decision(2, "supervisor", List.of("analyst", "deals"), "analyst", "enough facts", "billing")),
                    2,
                    new Recommender.Recommendation("Call them.", "Because."),
                    "done");
              }
            },
            telemetry);
    server.start();
  }

  private HttpResponse<String> post(String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/v1/multi-agent/run")).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void runPassesTheCompanyAndTheModelAndAnswersWithTheDecisions() throws Exception {
    start(null);
    var response = post("{\"company\": \"  Acme  \", \"model\": \"m/x\"}");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(calls).containsExactly("Acme|m/x");
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("approach").asString()).isEqualTo("fake");
    assertThat(body.get("pattern").asString()).isEqualTo("supervisor");
    assertThat(body.get("status").asString()).isEqualTo("completed");
    assertThat(body.get("turns").asInt()).isEqualTo(2);
    assertThat(body.get("specialists")).hasSize(2);
    assertThat(body.get("decisions").get(0).get("decidedBy").asString()).isEqualTo("code");
    assertThat(body.get("decisions").get(0).get("proposed").isNull()).isTrue();
    assertThat(body.get("decisions").get(1).get("proposed").asString()).isEqualTo("billing");
    assertThat(body.get("decisions").get(1).get("allowed")).hasSize(2);
    assertThat(body.get("recommendation").get("action").asString()).isEqualTo("Call them.");
  }

  @Test
  void aMissingModelIsPassedAsNull() throws Exception {
    start(null);
    post("{\"company\": \"Acme\"}");
    assertThat(calls).containsExactly("Acme|null");
  }

  @Test
  void aBadBodyIsRefusedAndNothingRuns() throws Exception {
    start(null);
    for (String body : List.of("", "not json", "[1]", "{}", "{\"company\": \"\"}", "{\"company\": 5}", "{\"company\": \"" + "x".repeat(201) + "\"}", "{\"company\": \"A\", \"model\": 5}", "{\"company\": \"A\", \"model\": \"" + "m".repeat(101) + "\"}")) {
      assertThat(post(body).statusCode()).as(body).isEqualTo(422);
    }
    assertThat(calls).isEmpty();
  }

  @Test
  void theErrorsMapToTheirStatusesWithoutALibrarysText() throws Exception {
    start(null);
    failure = new MultiAgent.CompanyNotFoundException("no company");
    assertThat(post("{\"company\": \"A\"}").statusCode()).isEqualTo(404);
    failure = new MultiAgent.UnavailableException("The model is not reachable.");
    var unavailable = post("{\"company\": \"A\"}");
    assertThat(unavailable.statusCode()).isEqualTo(503);
    assertThat(JSON.readTree(unavailable.body()).get("detail").asString()).isEqualTo("The model is not reachable.");
    failure = new IllegalStateException("http://internal-host/secret path");
    var other = post("{\"company\": \"A\"}");
    assertThat(other.statusCode()).isEqualTo(503);
    assertThat(other.body()).doesNotContain("secret").contains("The agents could not run.");
  }

  @Test
  void healthNamesTheApproachAndThePatternAndOtherMethodsAreRefused() throws Exception {
    start(null);
    var health = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health")).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(JSON.readTree(health.body()).get("approach").asString()).isEqualTo("fake");
    assertThat(JSON.readTree(health.body()).get("pattern").asString()).isEqualTo("supervisor");
    var get = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/v1/multi-agent/run")).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(get.statusCode()).isEqualTo(405);
  }

  @Test
  void theRecentRouteIsOffWithoutTelemetryAndShowsTheTraceWithIt() throws Exception {
    start(null);
    var off = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/v1/observability/recent")).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(off.statusCode()).isEqualTo(404);
    assertThat(off.body()).contains("TELEMETRY_ENABLED=true");
    server.stop();
    var telemetry = Telemetry.create();
    try {
      try (var request = telemetry.begin("fake", "m")) {
        request.step("analyst", "ok", 5);
      }
      start(telemetry);
      var on = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/v1/observability/recent")).build(), HttpResponse.BodyHandlers.ofString());
      assertThat(on.statusCode()).isEqualTo(200);
      assertThat(on.body()).contains("agent.analyst").contains("chat.request");
    } finally {
      telemetry.close();
    }
  }
}
