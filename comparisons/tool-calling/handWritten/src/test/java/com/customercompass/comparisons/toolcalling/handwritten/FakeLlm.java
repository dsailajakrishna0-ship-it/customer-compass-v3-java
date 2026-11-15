package com.customercompass.comparisons.toolcalling.handwritten;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A scripted stand-in for the model's HTTP API: each request to /chat/completions gets the next
 * scripted reply (the last one repeats), and every request body is kept for the test to read.
 */
final class FakeLlm implements AutoCloseable {

  final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<String> replies = new ArrayList<>();
  private final List<Integer> statuses = new ArrayList<>();
  private final HttpServer server;
  private int next;

  FakeLlm() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          int index;
          synchronized (this) {
            index = Math.min(next++, replies.size() - 1);
          }
          byte[] bytes = replies.get(index).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(statuses.get(index), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  FakeLlm thenReply(String json) {
    replies.add(json);
    statuses.add(200);
    return this;
  }

  FakeLlm thenFailWith(int status) {
    replies.add("{\"error\":\"nope\"}");
    statuses.add(status);
    return this;
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** A model reply that answers in plain text. */
  static String text(String content) {
    return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
        + quote(content)
        + "}}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":8}}";
  }

  /** A model reply that asks for tools: each entry is {id, name, arguments-as-json-text}. */
  static String calls(String[]... calls) {
    var list = new StringBuilder();
    for (String[] call : calls) {
      if (list.length() > 0) {
        list.append(',');
      }
      list.append("{\"id\":\"")
          .append(call[0])
          .append("\",\"type\":\"function\",\"function\":{\"name\":\"")
          .append(call[1])
          .append("\",\"arguments\":")
          .append(quote(call[2]))
          .append("}}");
    }
    return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
        + list
        + "]}}],\"usage\":{\"prompt_tokens\":15,\"completion_tokens\":6}}";
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
