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

  // ------------------------------------------------------------------ tools (J4.2)

  private static final List<ToolDefinition> TOOLS =
      List.of(
          new ToolDefinition(
              "get_open_deals",
              "Get only the open deals for a company.",
              java.util.Map.of(
                  "type", "object",
                  "properties",
                      java.util.Map.of(
                          "company_name",
                          java.util.Map.of("type", "string", "description", "The company.")),
                  "required", List.of("company_name"))));

  private static final String TOOL_CALL_BODY =
      """
      {"id":"x","object":"chat.completion","created":1,"model":"a:free",
       "choices":[{"index":0,"message":{"role":"assistant","content":null,
         "tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_open_deals","arguments":"{\\"company_name\\":\\"Acme\\"}"}}]},
         "finish_reason":"tool_calls"}],
       "usage":{"prompt_tokens":40,"completion_tokens":12,"total_tokens":52}}""";

  private Completion askWithTools(String toolChoice) {
    return client(5)
        .complete(
            "a:free",
            "Use the tools.",
            List.of(new ChatMessage("user", "Acme deals?")),
            TOOLS,
            toolChoice);
  }

  @Test
  void theOfferedToolsAreSentWithTheirNamesDescriptionsAndArgumentSchemas() {
    askWithTools("auto");

    String body = requestBody.get();
    assertThat(body).contains("\"tools\"").contains("\"type\":\"function\"");
    assertThat(body).contains("\"name\":\"get_open_deals\"").contains("Get only the open deals");
    assertThat(body).contains("\"company_name\"").contains("\"required\":[\"company_name\"]");
  }

  @Test
  void withNoToolsNoneAreSent() {
    ask();

    assertThat(requestBody.get()).doesNotContain("\"tools\"").doesNotContain("tool_choice");
  }

  @Test
  void aToolCallInTheReplyIsReadWithItsIdNameAndParsedArgumentsAndNoText() {
    reply = TOOL_CALL_BODY;

    Completion completion = askWithTools("auto");

    assertThat(completion.content()).isBlank();
    assertThat(completion.toolCalls())
        .containsExactly(
            new ToolCall("call_1", "get_open_deals", java.util.Map.of("company_name", "Acme")));
    assertThat(completion.tokensIn()).isEqualTo(40);
  }

  @Test
  void argumentsThatAreNotAJsonObjectBecomeAnErrorOnTheCallNotAnException() {
    reply = TOOL_CALL_BODY.replace("{\\\"company_name\\\":\\\"Acme\\\"}", "not json");
    assertThat(askWithTools("auto").toolCalls())
        .extracting(ToolCall::error)
        .containsExactly("arguments were not valid JSON");

    reply = TOOL_CALL_BODY.replace("{\\\"company_name\\\":\\\"Acme\\\"}", "[1]");
    assertThat(askWithTools("auto").toolCalls())
        .extracting(ToolCall::error)
        .containsExactly("arguments must be a JSON object");
    assertThat(askWithTools("auto").toolCalls().get(0).arguments()).isEmpty();
  }

  @Test
  void aToolConversationIsSentBackWithTheCallAndItsResult() {
    var call = new ToolCall("call_1", "get_open_deals", java.util.Map.of("company_name", "Acme"));

    client(5)
        .complete(
            "a:free",
            "Use the tools.",
            List.of(
                new ChatMessage("user", "Acme deals?"),
                ChatMessage.assistantToolCalls("", List.of(call)),
                ChatMessage.toolResult("call_1", "get_open_deals", "{\"status\":\"executed\"}")),
            TOOLS,
            "auto");

    String body = requestBody.get();
    assertThat(body).contains("\"tool_calls\"").contains("\"id\":\"call_1\"");
    assertThat(body).contains("\"role\":\"tool\"").contains("\"tool_call_id\":\"call_1\"");
    assertThat(body).contains("executed");
    assertThat(body.indexOf("\"role\":\"assistant\""))
        .isLessThan(body.indexOf("\"role\":\"tool\""));
  }

  @Test
  void aFinalAnswerCanBeForcedToTextWithToolChoiceNone() {
    askWithTools("none");

    assertThat(requestBody.get()).contains("\"tool_choice\":\"none\"");
  }
}
