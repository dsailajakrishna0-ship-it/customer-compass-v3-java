package com.customercompass.comparisons.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RetrieveBackendServerTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();
  private final List<String> calls = new ArrayList<>();
  private RetrieveBackendServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop();
    }
  }

  private void start(Supplier<Retrieve.Result> result) throws Exception {
    server =
        new RetrieveBackendServer(
            0,
            new Retrieve() {
              @Override
              public String approach() {
                return "fake";
              }

              @Override
              public Result search(String query, int topK, double minSimilarity) {
                calls.add(query + "|" + topK + "|" + minSimilarity);
                return result.get();
              }
            });
    server.start();
  }

  private static Retrieve.Result result() {
    return new Retrieve.Result(
        "compare_fake",
        0.6,
        3,
        List.of(new Retrieve.Hit(0.8, "T", "t.md", "Acme", 2, "some text")),
        1,
        "model-x",
        12);
  }

  private HttpResponse<String> post(String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/v1/retrieve"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void defaultsAreFourChunksAndTheCalibratedFloor() throws Exception {
    start(RetrieveBackendServerTests::result);
    var response = post("{\"query\": \"renewal terms\"}");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(calls).containsExactly("renewal terms|4|0.6");
  }

  @Test
  void theAnswerHasTheCountsAndTheHits() throws Exception {
    start(RetrieveBackendServerTests::result);
    JsonNode body = JSON.readTree(post("{\"query\": \"q\"}").body());
    assertThat(body.get("approach").asString()).isEqualTo("fake");
    assertThat(body.get("collection").asString()).isEqualTo("compare_fake");
    assertThat(body.get("considered").asInt()).isEqualTo(3);
    assertThat(body.get("kept").asInt()).isEqualTo(1);
    assertThat(body.get("minSimilarity").asDouble()).isEqualTo(0.6);
    JsonNode hit = body.get("hits").get(0);
    assertThat(hit.get("score").asDouble()).isEqualTo(0.8);
    assertThat(hit.get("title").asString()).isEqualTo("T");
    assertThat(hit.get("source").asString()).isEqualTo("t.md");
    assertThat(hit.get("company").asString()).isEqualTo("Acme");
    assertThat(hit.get("chunkIndex").asInt()).isEqualTo(2);
    assertThat(hit.get("text").asString()).isEqualTo("some text");
    assertThat(body.get("embeddingRequests").asInt()).isEqualTo(1);
    assertThat(body.get("embeddingModel").asString()).isEqualTo("model-x");
  }

  @Test
  void topKAndTheFloorCanBeSet() throws Exception {
    start(RetrieveBackendServerTests::result);
    assertThat(post("{\"query\": \"q\", \"topK\": 10, \"minSimilarity\": 0.45}").statusCode()).isEqualTo(200);
    assertThat(calls).containsExactly("q|10|0.45");
  }

  @Test
  void aBadRequestIsRefusedAndNothingRuns() throws Exception {
    start(RetrieveBackendServerTests::result);
    for (String body :
        List.of(
            "",
            "not json",
            "[1]",
            "{}",
            "{\"query\": \"\"}",
            "{\"query\": \"  \"}",
            "{\"query\": 5}",
            "{\"query\": \"" + "x".repeat(1001) + "\"}",
            "{\"query\": \"q\", \"topK\": 0}",
            "{\"query\": \"q\", \"topK\": 21}",
            "{\"query\": \"q\", \"topK\": \"3\"}",
            "{\"query\": \"q\", \"minSimilarity\": 1.5}",
            "{\"query\": \"q\", \"minSimilarity\": -0.1}",
            "{\"query\": \"q\", \"minSimilarity\": \"0.5\"}")) {
      assertThat(post(body).statusCode()).as(body).isEqualTo(422);
    }
    assertThat(calls).isEmpty();
  }

  @Test
  void theCollectionCannotBeChosenByTheCaller() throws Exception {
    start(RetrieveBackendServerTests::result);
    JsonNode body = JSON.readTree(post("{\"query\": \"q\", \"collection\": \"other\"}").body());
    assertThat(body.get("collection").asString()).isEqualTo("compare_fake");
  }

  @Test
  void anUnavailableServiceIsAnswered503WithItsMessage() throws Exception {
    start(
        () -> {
          throw new Retrieve.UnavailableException("The vector store is not reachable.");
        });
    var response = post("{\"query\": \"q\"}");
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(JSON.readTree(response.body()).get("detail").asString()).isEqualTo("The vector store is not reachable.");
  }

  @Test
  void anyOtherFailureIsAnswered503WithoutItsText() throws Exception {
    start(
        () -> {
          throw new IllegalStateException("http://internal-host/secret path");
        });
    var response = post("{\"query\": \"q\"}");
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).doesNotContain("secret").contains("Could not search.");
  }

  @Test
  void healthNamesTheApproachAndOtherMethodsAreRefused() throws Exception {
    start(RetrieveBackendServerTests::result);
    var health =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health")).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(JSON.readTree(health.body()).get("approach").asString()).isEqualTo("fake");
    var get =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/v1/retrieve")).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(get.statusCode()).isEqualTo(405);
  }

  @Test
  void parseTrimsTheQuery() {
    assertThat(RetrieveBackendServer.parse("{\"query\": \"  hello  \"}").query()).isEqualTo("hello");
    assertThatThrownBy(() -> RetrieveBackendServer.parse("{\"query\": \"q\", \"topK\": 2.5}"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
