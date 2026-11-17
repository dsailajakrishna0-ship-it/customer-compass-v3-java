package com.customercompass.comparisons.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenRouterChatTests {

  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private final List<String> auth = new CopyOnWriteArrayList<>();
  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String serve(int status, String reply) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          auth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
          byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static final String OK = "{\"choices\":[{\"message\":{\"content\":\"hi there\"}}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3}}";
  private static final List<Chat.Message> ASK = List.of(new Chat.Message("user", "hello"));

  @Test
  void sendsOneRequestWithTheKeyInTheHeaderAndTheMessagesInTheBody() throws Exception {
    var chat = new OpenRouterChat(serve(200, OK), "sk-secret", "default/model");
    var reply = chat.complete(ASK, null);
    assertThat(reply.content()).isEqualTo("hi there");
    assertThat(reply.usage().model()).isEqualTo("default/model");
    assertThat(reply.usage().tokensIn()).isEqualTo(11);
    assertThat(reply.usage().tokensOut()).isEqualTo(3);
    assertThat(bodies).hasSize(1);
    assertThat(auth).containsExactly("Bearer sk-secret");
    assertThat(bodies.get(0)).contains("\"model\":\"default/model\"").contains("\"content\":\"hello\"").doesNotContain("sk-secret");
  }

  @Test
  void aChosenModelWins() throws Exception {
    var chat = new OpenRouterChat(serve(200, OK), "k", "default/model");
    assertThat(chat.complete(ASK, "other/model").usage().model()).isEqualTo("other/model");
    assertThat(bodies.get(0)).contains("\"model\":\"other/model\"");
  }

  @Test
  void noKeyMeansNoCall() throws Exception {
    var chat = new OpenRouterChat(serve(200, OK), "  ", "m");
    assertThatThrownBy(() -> chat.complete(ASK, null)).isInstanceOf(MemoryChat.UnavailableException.class).hasMessage("OPENROUTER_API_KEY is not set. Put it in the environment.");
    assertThat(bodies).isEmpty();
  }

  @Test
  void aFailureStatusIsReportedWithoutTheReply() throws Exception {
    var chat = new OpenRouterChat(serve(429, "{\"error\":\"secret-detail\"}"), "k", "m");
    assertThatThrownBy(() -> chat.complete(ASK, null)).isInstanceOf(MemoryChat.UnavailableException.class).hasMessage("The model m answered HTTP 429.");
  }

  @Test
  void anUnreachableModelIsReportedWithoutTheAddress() {
    var chat = new OpenRouterChat("http://127.0.0.1:1", "k", "m");
    assertThatThrownBy(() -> chat.complete(ASK, null)).isInstanceOf(MemoryChat.UnavailableException.class).hasMessage("The model is not reachable.");
  }

  @Test
  void aReplyThatIsNotWhatWasExpectedIsAnError() throws Exception {
    var chat = new OpenRouterChat(serve(200, "<html>nope</html>"), "k", "m");
    assertThatThrownBy(() -> chat.complete(ASK, null)).isInstanceOf(MemoryChat.UnavailableException.class);
  }
}
