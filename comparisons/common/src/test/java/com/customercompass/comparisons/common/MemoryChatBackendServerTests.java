package com.customercompass.comparisons.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MemoryChatBackendServerTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ID = "11111111-2222-4333-8444-555555555555";
  private final HttpClient http = HttpClient.newHttpClient();
  private final List<String> calls = new ArrayList<>();
  private MemoryChatBackendServer server;
  private RuntimeException failure;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop();
    }
  }

  private void start(Telemetry telemetry) throws Exception {
    server =
        new MemoryChatBackendServer(
            0,
            new MemoryChat() {
              @Override
              public String approach() {
                return "fake";
              }

              @Override
              public Created create() {
                return new Created(ID, Instant.parse("2026-11-19T10:00:00Z"));
              }

              @Override
              public Answer chat(String conversationId, List<Chat.Message> messages, String model) {
                calls.add(conversationId + "|" + messages.size() + "|" + model);
                if (failure != null) {
                  throw failure;
                }
                return new Answer("hello", conversationId, 2, 4, new Chat.Usage("m", 10, 5, 7));
              }

              @Override
              public Conversation get(String conversationId) {
                if (!conversationId.equals(ID)) {
                  throw new ConversationNotFoundException();
                }
                return new Conversation(ID, List.of(new Chat.Message("user", "q"), new Chat.Message("assistant", "a")), Instant.parse("2026-11-19T10:00:00Z"));
              }

              @Override
              public void delete(String conversationId) {
                if (!conversationId.equals(ID)) {
                  throw new ConversationNotFoundException();
                }
              }
            },
            telemetry);
    server.start();
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
    builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> chat(String body) throws Exception {
    return send("POST", "/api/v1/chat/memory", body);
  }

  private static String body(String conversationId, String messages) {
    return "{\"conversationId\":\"" + conversationId + "\",\"messages\":" + messages + "}";
  }

  private static final String ONE = "[{\"role\":\"user\",\"content\":\"hi\"}]";

  @Test
  void createsAConversationWith201() throws Exception {
    start(null);
    var response = send("POST", "/api/v1/conversations", "");
    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode json = JSON.readTree(response.body());
    assertThat(json.get("conversationId").asString()).isEqualTo(ID);
    assertThat(json.get("expiresAt").asString()).isEqualTo("2026-11-19T10:00:00Z");
  }

  @Test
  void aTurnAnswersWithTheCountsAndTheUsage() throws Exception {
    start(null);
    var response = chat("{\"conversationId\":\"" + ID + "\",\"messages\":" + ONE + ",\"model\":\"x/y\"}");
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode json = JSON.readTree(response.body());
    assertThat(json.get("approach").asString()).isEqualTo("fake");
    assertThat(json.get("message").asString()).isEqualTo("hello");
    assertThat(json.get("conversationId").asString()).isEqualTo(ID);
    assertThat(json.get("memory").get("messagesUsed").asInt()).isEqualTo(2);
    assertThat(json.get("memory").get("messagesStored").asInt()).isEqualTo(4);
    assertThat(json.get("usage").get("tokensIn").asInt()).isEqualTo(10);
    assertThat(calls).containsExactly(ID + "|1|x/y");
  }

  @Test
  void aBlankModelMeansNoModelChosen() throws Exception {
    start(null);
    chat("{\"conversationId\":\"" + ID + "\",\"messages\":" + ONE + ",\"model\":\"  \"}");
    assertThat(calls).containsExactly(ID + "|1|null");
  }

  @Test
  void readsAConversation() throws Exception {
    start(null);
    var response = send("GET", "/api/v1/conversations/" + ID, null);
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode json = JSON.readTree(response.body());
    assertThat(json.get("messages")).hasSize(2);
    assertThat(json.get("messages").get(1).get("role").asString()).isEqualTo("assistant");
  }

  @Test
  void deletesAConversationWith204AndNoBody() throws Exception {
    start(null);
    var response = send("DELETE", "/api/v1/conversations/" + ID, null);
    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(response.body()).isEmpty();
  }

  @Test
  void anUnknownOrBadlyShapedIdIsNotFound() throws Exception {
    start(null);
    for (String id : List.of("99999999-2222-4333-8444-555555555555", "not-an-id", "..%2F..")) {
      var get = send("GET", "/api/v1/conversations/" + id, null);
      assertThat(get.statusCode()).as(id).isEqualTo(404);
      assertThat(JSON.readTree(get.body()).get("detail").asString()).isEqualTo("Conversation not found or expired.");
      assertThat(send("DELETE", "/api/v1/conversations/" + id, null).statusCode()).as(id).isEqualTo(404);
    }
  }

  @Test
  void aTurnOnAnUnknownConversationIs404() throws Exception {
    start(null);
    failure = new MemoryChat.ConversationNotFoundException();
    var response = chat(body(ID, ONE));
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(JSON.readTree(response.body()).get("detail").asString()).isEqualTo("Conversation not found or expired.");
  }

  @Test
  void aBodyThatBreaksTheRulesIs422AndTheMemoryIsNeverAsked() throws Exception {
    start(null);
    String long4001 = "x".repeat(4_001);
    String thirteen = "[" + "{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"assistant\",\"content\":\"b\"},".repeat(6) + "{\"role\":\"user\",\"content\":\"c\"}]";
    List<String> bad =
        List.of(
            "not json",
            "[]",
            "{}",
            "{\"messages\":" + ONE + "}",
            body("not-a-uuid", ONE),
            body(ID, "[]"),
            body(ID, "\"text\""),
            body(ID, "[{\"role\":\"system\",\"content\":\"hi\"}]"),
            body(ID, "[{\"role\":\"user\",\"content\":\"   \"}]"),
            body(ID, "[{\"role\":\"user\",\"content\":\"" + long4001 + "\"}]"),
            body(ID, "[{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"assistant\",\"content\":\"b\"}]"),
            body(ID, thirteen),
            "{\"conversationId\":\"" + ID + "\",\"messages\":" + ONE + ",\"model\":\"" + "m".repeat(101) + "\"}");
    for (String b : bad) {
      var response = chat(b);
      assertThat(response.statusCode()).as(b.length() > 80 ? b.substring(0, 80) : b).isEqualTo(422);
    }
    assertThat(calls).isEmpty();
  }

  @Test
  void aContentOfExactlyFourThousandCharactersIsAccepted() throws Exception {
    start(null);
    var response = chat(body(ID, "[{\"role\":\"user\",\"content\":\"" + "x".repeat(4_000) + "\"}]"));
    assertThat(response.statusCode()).isEqualTo(200);
  }

  @Test
  void twelveMessagesEndingWithTheUsersAreAccepted() throws Exception {
    start(null);
    String twelve = "[" + "{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"assistant\",\"content\":\"b\"},".repeat(5) + "{\"role\":\"user\",\"content\":\"c\"},{\"role\":\"user\",\"content\":\"d\"}]";
    assertThat(chat(body(ID, twelve)).statusCode()).isEqualTo(200);
  }

  @Test
  void unavailableIs503WithItsOwnMessage() throws Exception {
    start(null);
    failure = new MemoryChat.UnavailableException("The model is not reachable.");
    var response = chat(body(ID, ONE));
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(JSON.readTree(response.body()).get("error").asString()).isEqualTo("The model is not reachable.");
  }

  @Test
  void anUnexpectedFailureNeverLeaksItsText() throws Exception {
    start(null);
    failure = new IllegalStateException("jdbc:secret://host?key=SECRET-KEY");
    var response = chat(body(ID, ONE));
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).doesNotContain("SECRET-KEY").doesNotContain("jdbc");
  }

  @Test
  void healthNamesTheApproach() throws Exception {
    start(null);
    var response = send("GET", "/health", null);
    assertThat(JSON.readTree(response.body()).get("approach").asString()).isEqualTo("fake");
  }

  @Test
  void wrongMethodsAre405() throws Exception {
    start(null);
    assertThat(send("GET", "/api/v1/chat/memory", null).statusCode()).isEqualTo(405);
    assertThat(send("GET", "/api/v1/conversations", null).statusCode()).isEqualTo(405);
    assertThat(send("PUT", "/api/v1/conversations/" + ID, "").statusCode()).isEqualTo(405);
  }

  @Test
  void corsPreflightAllowsDelete() throws Exception {
    start(null);
    var response = send("OPTIONS", "/api/v1/conversations/" + ID, null);
    assertThat(response.statusCode()).isIn(200, 204);
    assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElse("")).contains("DELETE");
  }

  @Test
  void theObservationRouteSaysItIsOffWhenThereIsNoTelemetry() throws Exception {
    start(null);
    var response = send("GET", "/api/v1/observability/recent", null);
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("TELEMETRY_ENABLED");
  }

  @Test
  void theObservationRouteAnswersWithTelemetry() throws Exception {
    start(Telemetry.create());
    var response = send("GET", "/api/v1/observability/recent", null);
    assertThat(response.statusCode()).isEqualTo(200);
  }
}
