package com.customercompass.api.rag;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A local stand-in for Voyage or Cohere: records each request and answers what the test says. */
final class FakeRerankProvider implements AutoCloseable {

  record Seen(String path, String authorization, String body) {}

  final List<Seen> seen = new CopyOnWriteArrayList<>();
  volatile int status = 200;
  volatile String reply = "{}";
  volatile long delayMillis = 0;
  private final HttpServer server;

  FakeRerankProvider() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.createContext(
        "/",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          seen.add(
              new Seen(
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestHeaders().getFirst("Authorization"),
                  body));
          if (delayMillis > 0) {
            try {
              Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  void reset() {
    seen.clear();
    status = 200;
    reply = "{}";
    delayMillis = 0;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
