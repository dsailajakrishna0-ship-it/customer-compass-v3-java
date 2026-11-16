package com.customercompass.comparisons.ragretrieval.handwritten;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A throw-away local server that records every request and answers with a scripted reply. */
final class FakeHttp implements AutoCloseable {

  record Request(String method, String path, Map<String, String> headers, String body) {}

  record Reply(int status, String body) {}

  final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
  private final HttpServer server;

  FakeHttp(Function<Request, Reply> responder) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          var headers = new java.util.HashMap<String, String>();
          exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
          var request =
              new Request(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().toString(),
                  headers,
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          requests.add(request);
          Reply reply = responder.apply(request);
          byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
          if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
          }
          exchange.close();
        });
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  /** A Gemini that answers an embedContent call with one vector of {@code dimensions} numbers. */
  static FakeHttp gemini(int dimensions) throws IOException {
    return new FakeHttp(
        request -> {
          var vector = new StringBuilder("[");
          for (int i = 0; i < dimensions; i++) {
            vector.append(i == 0 ? "" : ",").append("0.5");
          }
          return new Reply(200, "{\"embedding\":{\"values\":" + vector.append("]}}"));
        });
  }

  /** A Qdrant whose query answers with the given points as (score, payload-json) pairs. */
  static FakeHttp qdrant(String... pointsJson) throws IOException {
    return new FakeHttp(
        request ->
            new Reply(200, "{\"result\":{\"points\":[" + String.join(",", pointsJson) + "]}}"));
  }

  static String point(double score, String title, String source, String company, int chunk, String text) {
    return "{\"id\":1,\"score\":"
        + score
        + ",\"payload\":{\"title\":\""
        + title
        + "\",\"source\":\""
        + source
        + "\",\"company\":\""
        + company
        + "\",\"chunkIndex\":"
        + chunk
        + ",\"text\":\""
        + text
        + "\"}}";
  }
}
