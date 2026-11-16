package com.customercompass.comparisons.common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP face every ingestion approach shares: {@code POST /api/v1/ingest} (body optional, or
 * {@code {"limit": 3}}) and {@code GET /health}. Built on the JDK's own server so no approach
 * drags a web framework in. The source folder and the collection are fixed by the backend, never
 * taken from the request, so a caller cannot make it read another folder or write another
 * collection.
 */
public final class IngestBackendServer {

  static final int MAX_LIMIT = 1_000;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  private final Ingest ingest;

  public IngestBackendServer(int port, Ingest ingest) throws IOException {
    this.ingest = ingest;
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    server.setExecutor(BackendThreads.forApproach(ingest));
    server.createContext("/health", this::health);
    server.createContext("/api/v1/ingest", this::run);
  }

  public void start() {
    server.start();
  }

  public void stop() {
    server.stop(0);
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private void health(HttpExchange exchange) throws IOException {
    if (cors(exchange)) {
      return;
    }
    send(exchange, 200, Map.of("status", "ok", "approach", ingest.approach()));
  }

  private void run(HttpExchange exchange) throws IOException {
    if (cors(exchange)) {
      return;
    }
    if (!exchange.getRequestMethod().equals("POST")) {
      send(exchange, 405, Map.of("detail", "Method Not Allowed"));
      return;
    }
    Integer limit;
    try {
      limit = limit(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    } catch (JacksonException e) {
      send(exchange, 422, Map.of("detail", "The body is not valid JSON."));
      return;
    } catch (IllegalArgumentException e) {
      send(exchange, 422, Map.of("detail", e.getMessage()));
      return;
    }
    try {
      Ingest.Result result = ingest.run(limit);
      var response = new LinkedHashMap<String, Object>();
      response.put("approach", ingest.approach());
      response.put("status", "completed");
      response.put("collection", result.collection());
      response.put("documents", result.documents());
      response.put("chunks", result.chunks());
      response.put("embeddingRequests", result.embeddingRequests());
      response.put("embeddingModel", result.embeddingModel());
      response.put("dimensions", result.dimensions());
      response.put("latencyMs", result.latencyMs());
      send(exchange, 200, response);
    } catch (Ingest.UnavailableException e) {
      send(exchange, 503, Map.of("error", e.getMessage(), "detail", e.getMessage()));
    } catch (RuntimeException e) {
      // never the exception's own text: it can carry a URL or a library's message
      send(exchange, 503, Map.of("error", "Could not ingest.", "detail", "Could not ingest."));
    }
  }

  /** An empty body or {@code {}} means no limit; otherwise {@code limit} is an integer from 1 to 1,000. */
  static Integer limit(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    JsonNode body = JSON.readTree(raw);
    if (body == null || !body.isObject()) {
      throw new IllegalArgumentException("The body must be a JSON object.");
    }
    JsonNode limit = body.get("limit");
    if (limit == null || limit.isNull()) {
      return null;
    }
    if (!limit.isInt() || limit.asInt() < 1 || limit.asInt() > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be an integer from 1 to " + MAX_LIMIT + ".");
    }
    return limit.asInt();
  }

  private static boolean cors(HttpExchange exchange) throws IOException {
    var headers = exchange.getResponseHeaders();
    headers.set("Access-Control-Allow-Origin", "*");
    headers.set("Access-Control-Allow-Headers", "Content-Type");
    headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    if (exchange.getRequestMethod().equals("OPTIONS")) {
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return true;
    }
    return false;
  }

  private static void send(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
