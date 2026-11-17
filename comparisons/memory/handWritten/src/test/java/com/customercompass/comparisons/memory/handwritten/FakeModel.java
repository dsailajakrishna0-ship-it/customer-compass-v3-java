package com.customercompass.comparisons.memory.handwritten;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A scripted stand-in for the model's HTTP API. Every request is kept; the reply text is computed
 * from the request ({@link #responder}), so a test can make the model echo what it was asked. A
 * {@link #gate} makes the model wait, which is how the tests hold a turn "inside the model call".
 */
final class FakeModel implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  final List<String> requests = new CopyOnWriteArrayList<>();
  volatile Function<String, String> responder = body -> "answer to " + lastUser(body);
  volatile int status = 200;
  volatile CountDownLatch gate;
  private final HttpServer server;

  FakeModel() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    server.createContext(
        "/chat/completions",
        exchange -> {
          String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          requests.add(body);
          CountDownLatch held = gate;
          if (held != null) {
            try {
              held.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          String reply =
              status == 200
                  ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + JSON.writeValueAsString(responder.apply(body)) + "}}],"
                      + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":8}}"
                  : "{\"error\":\"nope\"}";
          byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** The messages of one request, as {@code role: content}, in order. */
  static List<String> messages(String body) {
    var list = new ArrayList<String>();
    for (JsonNode message : JSON.readTree(body).get("messages")) {
      list.add(message.get("role").asString() + ": " + message.get("content").asString());
    }
    return list;
  }

  static String lastUser(String body) {
    String last = "";
    for (JsonNode message : JSON.readTree(body).get("messages")) {
      if (message.get("role").asString().equals("user")) {
        last = message.get("content").asString();
      }
    }
    return last;
  }

  @Override
  public void close() {
    CountDownLatch held = gate;
    if (held != null) {
      held.countDown();
    }
    server.stop(0);
  }
}
