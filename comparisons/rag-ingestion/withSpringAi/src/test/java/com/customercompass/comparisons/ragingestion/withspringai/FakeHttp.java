package com.customercompass.comparisons.ragingestion.withspringai;

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

  /** A Gemini that answers every batch with one vector of {@code dimensions} numbers per text (counted by the "parts" entries). */
  static FakeHttp gemini(int dimensions) throws IOException {
    return new FakeHttp(
        request -> {
          int texts = request.body().split("\"parts\"", -1).length - 1;
          var vector = new StringBuilder("[");
          for (int i = 0; i < dimensions; i++) {
            vector.append(i == 0 ? "" : ",").append("0.5");
          }
          vector.append("]");
          var body = new StringBuilder("{\"embeddings\":[");
          for (int i = 0; i < texts; i++) {
            body.append(i == 0 ? "" : ",").append("{\"values\":").append(vector).append("}");
          }
          return new Reply(200, body.append("]}").toString());
        });
  }

  /** A Qdrant that says yes to everything (and 404 to deleting what is not there). */
  static FakeHttp qdrant() throws IOException {
    return new FakeHttp(
        request -> new Reply(request.method().equals("DELETE") ? 404 : 200, "{\"result\":true}"));
  }
}
