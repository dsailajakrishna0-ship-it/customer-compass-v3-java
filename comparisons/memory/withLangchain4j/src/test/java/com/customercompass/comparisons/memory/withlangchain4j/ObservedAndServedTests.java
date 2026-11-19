package com.customercompass.comparisons.memory.withlangchain4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.customercompass.comparisons.common.MemoryChatBackendServer;
import com.customercompass.comparisons.common.MemoryLimits;
import com.customercompass.comparisons.common.Telemetry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The store behind the real HTTP face, and what the observation records (never the text). */
class ObservedAndServedTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SECRET = "my-very-secret-question";
  private final HttpClient http = HttpClient.newHttpClient();
  private FakeModel model;
  private InMemorySpanExporter spans;
  private Telemetry telemetry;
  private MemoryChatBackendServer server;

  @BeforeEach
  void start() throws Exception {
    model = new FakeModel();
    spans = InMemorySpanExporter.create();
    telemetry = Telemetry.create(spans);
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop();
    }
    model.close();
    telemetry.close();
  }

  private void serve(String key, Telemetry observer) throws Exception {
    var chat = new LangChain4jMemoryChat(model.url(), key, "test/model", MemoryLimits.DEFAULTS, Clock.systemUTC(), observer);
    server = new MemoryChatBackendServer(0, chat, observer);
    server.start();
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
    builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String create() throws Exception {
    return JSON.readTree(send("POST", "/api/v1/conversations", "").body()).get("conversationId").asString();
  }

  private HttpResponse<String> say(String id, String text) throws Exception {
    return send("POST", "/api/v1/chat/memory", "{\"conversationId\":\"" + id + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + text + "\"}]}");
  }

  @Test
  void aWholeConversationOverHttp() throws Exception {
    serve("key", null);
    String id = create();
    var first = JSON.readTree(say(id, "q1").body());
    assertThat(first.get("memory").get("messagesUsed").asInt()).isZero();
    var second = JSON.readTree(say(id, "q2").body());
    assertThat(second.get("message").asString()).isEqualTo("answer to q2");
    assertThat(second.get("memory").get("messagesUsed").asInt()).isEqualTo(2);
    assertThat(second.get("memory").get("messagesStored").asInt()).isEqualTo(4);
    assertThat(JSON.readTree(send("GET", "/api/v1/conversations/" + id, null).body()).get("messages")).hasSize(4);
    assertThat(send("DELETE", "/api/v1/conversations/" + id, null).statusCode()).isEqualTo(204);
    assertThat(send("GET", "/api/v1/conversations/" + id, null).statusCode()).isEqualTo(404);
    assertThat(say(id, "q3").statusCode()).isEqualTo(404);
  }

  @Test
  void aTurnWithoutAKeyIs503WithAHintAndTheModelIsNotCalled() throws Exception {
    serve("", null);
    String id = create();
    var response = say(id, "q1");
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).contains("OPENROUTER_API_KEY");
    assertThat(model.requests).isEmpty();
  }

  @Test
  void theObservationNeverHoldsTheTextOfTheConversation() throws Exception {
    serve("key", telemetry);
    String id = create();
    say(id, SECRET);
    say(id, SECRET + "-again");
    assertThat(spans.getFinishedSpanItems()).extracting(SpanData::getName).contains("chat.request", "llm.generate");
    for (SpanData span : spans.getFinishedSpanItems()) {
      assertThat(span.getName()).doesNotContain(SECRET);
      span.getAttributes().forEach((key, value) -> {
        assertThat(key.getKey()).doesNotContain(SECRET);
        assertThat(String.valueOf(value)).doesNotContain(SECRET).doesNotContain("answer to");
      });
    }
    telemetry.metrics().forEach(metric -> {
      metric.getData().getPoints().forEach(point -> point.getAttributes().forEach((key, value) -> assertThat(String.valueOf(value)).doesNotContain(SECRET)));
    });
    assertThat(send("GET", "/api/v1/observability/recent", null).body()).doesNotContain(SECRET).doesNotContain("answer to");
  }

  @Test
  void aFailedTurnIsRecordedAsAFailureWithoutText() throws Exception {
    serve("key", telemetry);
    String id = create();
    model.status = 500;
    assertThat(say(id, SECRET).statusCode()).isEqualTo(503);
    var request = spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals("chat.request")).findFirst().orElseThrow();
    assertThat(request.getStatus().getStatusCode().name()).isEqualTo("ERROR");
    assertThat(request.getAttributes().toString()).doesNotContain(SECRET);
  }

  @Test
  void withObservationOffNothingIsRecordedAndTheRouteSaysSo() throws Exception {
    serve("key", null);
    say(create(), SECRET);
    assertThat(spans.getFinishedSpanItems()).isEmpty();
    assertThat(send("GET", "/api/v1/observability/recent", null).statusCode()).isEqualTo(404);
  }
}
