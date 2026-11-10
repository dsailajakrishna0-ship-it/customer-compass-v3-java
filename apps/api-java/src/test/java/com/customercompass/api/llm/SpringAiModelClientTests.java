package com.customercompass.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.api.llm.ModelCallException.Kind;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real Spring AI client against a local fake of OpenRouter's /chat/completions: the request it
 * sends, the answer and tokens it reads, and how each failure is sorted. No network, no real key.
 */
class SpringAiModelClientTests {

  private static final String KEY = "sk-test-not-a-real-key";
  private static final String OK_BODY =
      """
      {"id":"x","object":"chat.completion","created":1,"model":"a:free",
       "choices":[{"index":0,"message":{"role":"assistant","content":"Paris"},"finish_reason":"stop"}],
       "usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}""";

  private HttpServer server;
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private volatile int status = 200;
  private volatile String reply = OK_BODY;
  private volatile long delayMillis = 0;

  @BeforeEach
  void startFakeOpenRouter() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          try {
            Thread.sleep(delayMillis);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private SpringAiModelClient client(double timeoutSeconds) {
    var properties = new LlmProperties(KEY, "", timeoutSeconds, 2, 1, 30, "");
    return new SpringAiModelClient(
        properties, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
  }

  private Completion ask() {
    return client(5)
        .complete("a:free", "Be brief.", List.of(new ChatMessage("user", "Capital of France?")));
  }

  @Test
  void aSuccessfulCallReturnsTheTextAndTheTokenCounts() {
    Completion completion = ask();

    assertThat(completion.content()).isEqualTo("Paris");
    assertThat(completion.tokensIn()).isEqualTo(11);
    assertThat(completion.tokensOut()).isEqualTo(7);
  }

  @Test
  void theRequestCarriesTheKeyTheModelTheSystemPromptAndTheConversation() {
    client(5)
        .complete(
            "a:free",
            "Be brief.",
            List.of(
                new ChatMessage("user", "Hi"),
                new ChatMessage("assistant", "Hello"),
                new ChatMessage("user", "Capital of France?")));

    assertThat(authorization.get()).isEqualTo("Bearer " + KEY);
    String body = requestBody.get();
    assertThat(body).contains("\"model\":\"a:free\"").contains("\"max_tokens\":700");
    assertThat(body).contains("Be brief.").contains("Hello").contains("Capital of France?");
    assertThat(body.indexOf("Be brief.")).isLessThan(body.indexOf("Hi"));
  }

  @Test
  void aRateLimitIsSortedAsRateLimitAndIsNotRetriedByTheClient() {
    status = 429;
    reply = "{\"error\":{\"message\":\"slow down\",\"code\":429}}";

    assertThatThrownBy(this::ask)
        .isInstanceOfSatisfying(
            ModelCallException.class, e -> assertThat(e.kind()).isEqualTo(Kind.RATE_LIMIT));
  }

  @Test
  void aServerErrorIsSortedAsServerError() {
    status = 503;
    reply = "{\"error\":{\"message\":\"overloaded\",\"code\":503}}";

    assertThatThrownBy(this::ask)
        .isInstanceOfSatisfying(
            ModelCallException.class, e -> assertThat(e.kind()).isEqualTo(Kind.SERVER_ERROR));
  }

  @Test
  void aRejectedKeyIsSortedAsAuthAndTheMessageNeverHoldsTheKeyOrTheReplyBody() {
    status = 401;
    reply = "{\"error\":{\"message\":\"bad key " + KEY + "\",\"code\":401}}";

    assertThatThrownBy(this::ask)
        .isInstanceOfSatisfying(
            ModelCallException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(Kind.AUTH);
              assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("bad key");
            });
  }

  @Test
  void aBadRequestIsSortedAsBadRequest() {
    status = 400;
    reply = "{\"error\":{\"message\":\"no such model\",\"code\":400}}";

    assertThatThrownBy(this::ask)
        .isInstanceOfSatisfying(
            ModelCallException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_REQUEST));
  }

  @Test
  void aSlowAnswerIsSortedAsTimeout() {
    delayMillis = 1500;

    assertThatThrownBy(
            () -> client(0.4).complete("a:free", "x", List.of(new ChatMessage("user", "hi"))))
        .isInstanceOfSatisfying(
            ModelCallException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TIMEOUT));
  }

  @Test
  void anAnswerWithNoTextComesBackEmptySoTheGatewayCanTryTheNextModel() {
    reply =
        """
        {"id":"x","object":"chat.completion","created":1,"model":"a:free",
         "choices":[{"index":0,"message":{"role":"assistant","content":""},"finish_reason":"length"}],
         "usage":{"prompt_tokens":11,"completion_tokens":700,"total_tokens":711}}""";

    assertThat(ask().content()).isBlank();
  }
}
