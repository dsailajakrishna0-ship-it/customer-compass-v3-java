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
 * The HTTP face every multi-agent pattern shares: {@code POST /api/v1/multi-agent/run} with {@code
 * {"company": "Acme", "model": "optional"}}, {@code GET /health} and, when a {@link Telemetry} is
 * given, {@code GET /api/v1/observability/recent} (404 with a hint when observation is off). Built
 * on the JDK's own server so no approach drags a web framework in.
 */
public final class MultiAgentBackendServer {

  static final int MAX_COMPANY = 200;
  static final int MAX_MODEL = 100;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  private final MultiAgent team;
  private final Telemetry telemetry;

  public MultiAgentBackendServer(int port, MultiAgent team) throws IOException {
    this(port, team, null);
  }

  public MultiAgentBackendServer(int port, MultiAgent team, Telemetry telemetry) throws IOException {
    this.team = team;
    this.telemetry = telemetry;
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    server.setExecutor(BackendThreads.forApproach(team));
    server.createContext("/health", this::health);
    server.createContext("/api/v1/multi-agent/run", this::run);
    server.createContext("/api/v1/observability/recent", this::recent);
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
    send(exchange, 200, Map.of("status", "ok", "approach", team.approach(), "pattern", team.pattern()));
  }

  private void recent(HttpExchange exchange) throws IOException {
    if (cors(exchange)) {
      return;
    }
    if (!exchange.getRequestMethod().equals("GET")) {
      send(exchange, 405, Map.of("detail", "Method Not Allowed"));
      return;
    }
    if (telemetry == null) {
      send(exchange, 404, Map.of("enabled", false, "detail", "Observation is off. Start the backend with TELEMETRY_ENABLED=true."));
      return;
    }
    byte[] bytes = telemetry.recentJson().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private void run(HttpExchange exchange) throws IOException {
    if (cors(exchange)) {
      return;
    }
    if (!exchange.getRequestMethod().equals("POST")) {
      send(exchange, 405, Map.of("detail", "Method Not Allowed"));
      return;
    }
    JsonNode body;
    try {
      String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      body = raw.isBlank() ? null : JSON.readTree(raw);
      if (body == null || !body.isObject()) {
        throw new IllegalArgumentException("The body must be a JSON object.");
      }
      validate(body);
    } catch (JacksonException e) {
      send(exchange, 422, Map.of("detail", "The body is not valid JSON."));
      return;
    } catch (IllegalArgumentException e) {
      send(exchange, 422, Map.of("detail", e.getMessage()));
      return;
    }
    try {
      JsonNode model = body.get("model");
      String useModel = model == null || model.isNull() || model.asString().isBlank() ? null : model.asString();
      send(exchange, 200, view(team.run(body.get("company").asString().strip(), useModel)));
    } catch (MultiAgent.CompanyNotFoundException e) {
      send(exchange, 404, Map.of("detail", e.getMessage()));
    } catch (MultiAgent.UnavailableException e) {
      send(exchange, 503, Map.of("error", e.getMessage(), "detail", e.getMessage()));
    } catch (RuntimeException e) {
      // never the exception's own text: it can carry a URL or a library's message
      send(exchange, 503, Map.of("error", "The agents could not run.", "detail", "The agents could not run."));
    }
  }

  static void validate(JsonNode body) {
    JsonNode company = body.get("company");
    if (company == null || !company.isString() || company.asString().isBlank()) {
      throw new IllegalArgumentException("company is required and must be a non-blank string.");
    }
    if (company.asString().length() > MAX_COMPANY) {
      throw new IllegalArgumentException("company is limited to " + MAX_COMPANY + " characters.");
    }
    JsonNode model = body.get("model");
    if (model != null && !model.isNull() && (!model.isString() || model.asString().length() > MAX_MODEL)) {
      throw new IllegalArgumentException("model must be a string of at most " + MAX_MODEL + " characters.");
    }
  }

  private Map<String, Object> view(MultiAgent.Outcome outcome) {
    var map = new LinkedHashMap<String, Object>();
    map.put("approach", team.approach());
    map.put("pattern", team.pattern());
    map.put("status", outcome.status());
    map.put("turns", outcome.turns());
    map.put("specialists", outcome.specialists());
    map.put(
        "decisions",
        outcome.decisions().stream()
            .map(
                d -> {
                  var decision = new LinkedHashMap<String, Object>();
                  decision.put("turn", d.turn());
                  decision.put("decidedBy", d.decidedBy());
                  decision.put("allowed", d.allowed());
                  decision.put("chosen", d.chosen());
                  decision.put("reason", d.reason());
                  decision.put("proposed", d.proposed());
                  return decision;
                })
            .toList());
    if (outcome.recommendation() != null) {
      map.put("recommendation", Map.of("action", outcome.recommendation().action(), "rationale", outcome.recommendation().rationale()));
    }
    map.put("message", outcome.message());
    return map;
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
