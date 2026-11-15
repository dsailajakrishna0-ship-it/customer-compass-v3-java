package com.customercompass.comparisons.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ToolBackendServerTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();
  private ToolBackendServer server;
  private volatile RuntimeException failure;
  private volatile List<Chat.Message> seen;
  private volatile String seenModel;

  @BeforeEach
  void start() throws Exception {
    failure = null;
    server =
        new ToolBackendServer(
            0,
            new Chat.ToolChat() {
              @Override
              public String approach() {
                return "scripted";
              }

              @Override
              public Chat.Result answer(List<Chat.Message> messages, String model) {
                if (failure != null) {
                  throw failure;
                }
                seen = messages;
                seenModel = model;
                return new Chat.Result(
                    "Two open deals.",
                    List.of(new Chat.ToolUse("get_open_deals", Map.of("company_name", "Acme"))),
                    new Chat.Usage("test-model", 10, 5, 12));
              }
            });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  private HttpResponse<String> post(String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/v1/chat/tools"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void answersInTheShapeOfTheApplicationsToolChatRoute() throws Exception {
    HttpResponse<String> response =
        post("{\"messages\":[{\"role\":\"user\",\"content\":\"Deals for Acme?\"}],\"model\":\"m/x\"}");
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("message").asString()).isEqualTo("Two open deals.");
    assertThat(body.get("toolsUsed").get(0).get("tool").asString()).isEqualTo("get_open_deals");
    assertThat(body.get("toolsUsed").get(0).get("arguments").get("company_name").asString()).isEqualTo("Acme");
    assertThat(body.get("mcpServer").asString()).isEqualTo("scripted");
    assertThat(body.get("usage").get("model").asString()).isEqualTo("test-model");
    assertThat(body.get("usage").get("tokensIn").asInt()).isEqualTo(10);
    assertThat(body.get("usage").get("fallbackUsed").asBoolean()).isFalse();
    assertThat(body.has("guardrail")).as("these backends run no prompt guard").isFalse();
    assertThat(seenModel).isEqualTo("m/x");
    assertThat(seen).containsExactly(new Chat.Message("user", "Deals for Acme?"));
  }

  @Test
  void aMissingModelMeansTheBackendsOwnDefault() throws Exception {
    post("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
    assertThat(seenModel).isNull();
  }

  @Test
  void refusesABadRequestWithA422AndAMessage() throws Exception {
    for (String bad :
        List.of(
            "not json",
            "[]",
            "{}",
            "{\"messages\":[]}",
            "{\"messages\":[{\"role\":\"system\",\"content\":\"x\"}]}",
            "{\"messages\":[{\"role\":\"user\",\"content\":\"  \"}]}",
            "{\"messages\":[{\"role\":\"assistant\",\"content\":\"x\"}]}",
            "{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],\"model\":3}")) {
      HttpResponse<String> response = post(bad);
      assertThat(response.statusCode()).as(bad).isEqualTo(422);
      assertThat(JSON.readTree(response.body()).get("detail").asString()).as(bad).isNotBlank();
    }
    String many = "{\"role\":\"user\",\"content\":\"x\"},".repeat(12) + "{\"role\":\"user\",\"content\":\"x\"}";
    assertThat(post("{\"messages\":[" + many + "]}").statusCode()).isEqualTo(422);
    String longText = "x".repeat(4_001);
    assertThat(post("{\"messages\":[{\"role\":\"user\",\"content\":\"" + longText + "\"}]}").statusCode())
        .isEqualTo(422);
  }

  @Test
  void anUnavailableModelIsA503WithTheMessage() throws Exception {
    failure = new Chat.UnavailableException("OPENROUTER_API_KEY is not set.");
    HttpResponse<String> response = post("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(JSON.readTree(response.body()).get("error").asString()).contains("OPENROUTER_API_KEY");
  }

  @Test
  void anUnexpectedFailureIsA503WithoutLeakingItsMessage() throws Exception {
    failure = new IllegalStateException("secret internal detail");
    HttpResponse<String> response = post("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).doesNotContain("secret internal detail");
  }

  @Test
  void healthAnswersAndPreflightAllowsTheBrowserApp() throws Exception {
    HttpResponse<String> health =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health")).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(health.headers().firstValue("Access-Control-Allow-Origin")).contains("*");
    HttpResponse<String> preflight =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/v1/chat/tools"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(preflight.statusCode()).isEqualTo(204);
    assertThat(preflight.headers().firstValue("Access-Control-Allow-Methods").orElse("")).contains("POST");
  }

  @Test
  void aGetOnTheChatRouteIsRefused() throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/v1/chat/tools")).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(405);
  }
}
