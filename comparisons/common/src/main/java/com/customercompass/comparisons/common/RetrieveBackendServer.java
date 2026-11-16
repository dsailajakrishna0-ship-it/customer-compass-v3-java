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
 * The HTTP face every retrieval approach shares: {@code POST /api/v1/retrieve} with {@code
 * {"query": "...", "topK": 4, "minSimilarity": 0.6}} (only the query is required) and {@code GET
 * /health}. Built on the JDK's own server so no approach drags a web framework in. The collection
 * is the approach's own and is never taken from the request.
 */
public final class RetrieveBackendServer {

  static final int MAX_QUERY_CHARACTERS = 1_000;
  static final int MAX_TOP_K = 20;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  private final Retrieve retrieve;

  public RetrieveBackendServer(int port, Retrieve retrieve) throws IOException {
    this.retrieve = retrieve;
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    server.setExecutor(BackendThreads.forApproach(retrieve));
    server.createContext("/health", this::health);
    server.createContext("/api/v1/retrieve", this::search);
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

  /** A validated request. */
  record Request(String query, int topK, double minSimilarity) {}

  private void health(HttpExchange exchange) throws IOException {
    if (cors(exchange)) {
      return;
    }
    send(exchange, 200, Map.of("status", "ok", "approach", retrieve.approach()));
  }

  private void search(HttpExchange exchange) throws IOException {
    if (cors(exchange)) {
      return;
    }
    if (!exchange.getRequestMethod().equals("POST")) {
      send(exchange, 405, Map.of("detail", "Method Not Allowed"));
      return;
    }
    Request request;
    try {
      request = parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    } catch (JacksonException e) {
      send(exchange, 422, Map.of("detail", "The body is not valid JSON."));
      return;
    } catch (IllegalArgumentException e) {
      send(exchange, 422, Map.of("detail", e.getMessage()));
      return;
    }
    try {
      Retrieve.Result result = retrieve.search(request.query(), request.topK(), request.minSimilarity());
      var response = new LinkedHashMap<String, Object>();
      response.put("approach", retrieve.approach());
      response.put("collection", result.collection());
      response.put("minSimilarity", result.minSimilarity());
      response.put("considered", result.considered());
      response.put("kept", result.hits().size());
      response.put("hits", result.hits().stream().map(RetrieveBackendServer::hit).toList());
      response.put("embeddingRequests", result.embeddingRequests());
      response.put("embeddingModel", result.embeddingModel());
      response.put("latencyMs", result.latencyMs());
      send(exchange, 200, response);
    } catch (Retrieve.UnavailableException e) {
      send(exchange, 503, Map.of("error", e.getMessage(), "detail", e.getMessage()));
    } catch (RuntimeException e) {
      // never the exception's own text: it can carry a URL or a library's message
      send(exchange, 503, Map.of("error", "Could not search.", "detail", "Could not search."));
    }
  }

  private static Map<String, Object> hit(Retrieve.Hit hit) {
    var map = new LinkedHashMap<String, Object>();
    map.put("score", hit.score());
    map.put("title", hit.title());
    map.put("source", hit.source());
    map.put("company", hit.company());
    map.put("chunkIndex", hit.chunkIndex());
    map.put("text", hit.text());
    return map;
  }

  /**
   * {@code query}: a non-blank string of at most 1,000 characters. {@code topK}: an integer from 1 to
   * 20 (default 4). {@code minSimilarity}: a number from 0 to 1 (default 0.60).
   */
  static Request parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("The body must be a JSON object with a query.");
    }
    JsonNode body = JSON.readTree(raw);
    if (body == null || !body.isObject()) {
      throw new IllegalArgumentException("The body must be a JSON object.");
    }
    JsonNode query = body.get("query");
    if (query == null || !query.isString() || query.asString().isBlank()) {
      throw new IllegalArgumentException("query is required and must be a non-blank string.");
    }
    if (query.asString().length() > MAX_QUERY_CHARACTERS) {
      throw new IllegalArgumentException("query is limited to " + MAX_QUERY_CHARACTERS + " characters.");
    }
    int topK = Retrieve.DEFAULT_TOP_K;
    JsonNode k = body.get("topK");
    if (k != null && !k.isNull()) {
      if (!k.isInt() || k.asInt() < 1 || k.asInt() > MAX_TOP_K) {
        throw new IllegalArgumentException("topK must be an integer from 1 to " + MAX_TOP_K + ".");
      }
      topK = k.asInt();
    }
    double floor = Retrieve.DEFAULT_MIN_SIMILARITY;
    JsonNode m = body.get("minSimilarity");
    if (m != null && !m.isNull()) {
      if (!m.isNumber() || m.asDouble() < 0 || m.asDouble() > 1) {
        throw new IllegalArgumentException("minSimilarity must be a number from 0 to 1.");
      }
      floor = m.asDouble();
    }
    return new Request(query.asString().strip(), topK, floor);
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
