package com.customercompass.comparisons.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class IngestBackendServerTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();
  private final List<Integer> limits = new ArrayList<>();
  private IngestBackendServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop();
    }
  }

  private void start(Function<Integer, Ingest.Result> run) throws Exception {
    server =
        new IngestBackendServer(
            0,
            new Ingest() {
              @Override
              public String approach() {
                return "fake";
              }

              @Override
              public Result run(Integer limit) {
                limits.add(limit);
                return run.apply(limit);
              }
            });
    server.start();
  }

  private static Ingest.Result result() {
    return new Ingest.Result("compare_fake", 2, 5, 1, "model-x", 768, 12);
  }

  private HttpResponse<String> post(String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/v1/ingest"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void anEmptyBodyRunsWithNoLimitAndAnswersWithTheCounts() throws Exception {
    start(limit -> result());
    var response = post("");
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("approach").asString()).isEqualTo("fake");
    assertThat(body.get("status").asString()).isEqualTo("completed");
    assertThat(body.get("collection").asString()).isEqualTo("compare_fake");
    assertThat(body.get("documents").asInt()).isEqualTo(2);
    assertThat(body.get("chunks").asInt()).isEqualTo(5);
    assertThat(body.get("embeddingRequests").asInt()).isEqualTo(1);
    assertThat(body.get("embeddingModel").asString()).isEqualTo("model-x");
    assertThat(body.get("dimensions").asInt()).isEqualTo(768);
    assertThat(limits).containsExactly((Integer) null);
  }

  @Test
  void aLimitIsPassedOn() throws Exception {
    start(limit -> result());
    assertThat(post("{\"limit\": 3}").statusCode()).isEqualTo(200);
    assertThat(limits).containsExactly(3);
  }

  @Test
  void aBadLimitIsRefusedAndNothingRuns() throws Exception {
    start(limit -> result());
    for (String body : List.of("{\"limit\": 0}", "{\"limit\": 1001}", "{\"limit\": \"3\"}", "{\"limit\": 2.5}")) {
      assertThat(post(body).statusCode()).as(body).isEqualTo(422);
    }
    assertThat(limits).isEmpty();
  }

  @Test
  void aBodyThatIsNotJsonOrNotAnObjectIsRefused() throws Exception {
    start(limit -> result());
    assertThat(post("not json").statusCode()).isEqualTo(422);
    assertThat(post("[1]").statusCode()).isEqualTo(422);
  }

  @Test
  void theFolderAndTheCollectionCannotBeChosenByTheCaller() throws Exception {
    start(limit -> result());
    var response = post("{\"directory\": \"/etc\", \"collection\": \"other\"}");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).get("collection").asString()).isEqualTo("compare_fake");
  }

  @Test
  void anUnavailableServiceIsAnswered503WithItsMessage() throws Exception {
    start(
        limit -> {
          throw new Ingest.UnavailableException("The vector store is not reachable.");
        });
    var response = post("");
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(JSON.readTree(response.body()).get("detail").asString()).isEqualTo("The vector store is not reachable.");
  }

  @Test
  void anyOtherFailureIsAnswered503WithoutItsText() throws Exception {
    start(
        limit -> {
          throw new IllegalStateException("http://internal-host/secret path");
        });
    var response = post("");
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).doesNotContain("secret").contains("Could not ingest.");
  }

  @Test
  void healthNamesTheApproachAndOtherMethodsAreRefused() throws Exception {
    start(limit -> result());
    var health =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health")).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(JSON.readTree(health.body()).get("approach").asString()).isEqualTo("fake");
    var get =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/v1/ingest")).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(get.statusCode()).isEqualTo(405);
  }
}
