package com.customercompass.comparisons.common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP face every agent approach shares: {@code POST /api/v1/agent/start} with {@code
 * {"company": "Acme", "model": "optional"}}, {@code POST /api/v1/agent/resume} with {@code
 * {"runId": "...", "approve": true}}, and {@code GET /health}. Built on the JDK's own server so no
 * approach drags a web framework in.
 */
public final class AgentBackendServer {

  static final int MAX_COMPANY = 200;
  static final int MAX_MODEL = 100;
  private static final Pattern RUN_ID = Pattern.compile("[0-9a-fA-F-]{8,64}");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  private final Agent agent;

  public AgentBackendServer(int port, Agent agent) throws IOException {
    this.agent = agent;
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    server.setExecutor(BackendThreads.forApproach(agent));
    server.createContext("/health", this::health);
    server.createContext("/api/v1/agent/start", exchange -> post(exchange, true));
    server.createContext("/api/v1/agent/resume", exchange -> post(exchange, false));
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
    send(exchange, 200, Map.of("status", "ok", "approach", agent.approach()));
  }

  private void post(HttpExchange exchange, boolean start) throws IOException {
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
      if (start) {
        validateStart(body);
      } else {
        validateResume(body);
      }
    } catch (JacksonException e) {
      send(exchange, 422, Map.of("detail", "The body is not valid JSON."));
      return;
    } catch (IllegalArgumentException e) {
      send(exchange, 422, Map.of("detail", e.getMessage()));
      return;
    }
    try {
      Agent.Outcome outcome =
          start
              ? agent.start(body.get("company").asString().strip(), optionalModel(body))
              : agent.resume(body.get("runId").asString(), body.get("approve").asBoolean());
      send(exchange, 200, view(outcome));
    } catch (Agent.CompanyNotFoundException | Agent.UnknownRunException e) {
      send(exchange, 404, Map.of("detail", e.getMessage()));
    } catch (Agent.NotAwaitingApprovalException e) {
      send(exchange, 409, Map.of("detail", e.getMessage()));
    } catch (Agent.UnavailableException e) {
      send(exchange, 503, Map.of("error", e.getMessage(), "detail", e.getMessage()));
    } catch (RuntimeException e) {
      // never the exception's own text: it can carry a URL or a library's message
      send(exchange, 503, Map.of("error", "The agent could not run.", "detail", "The agent could not run."));
    }
  }

  private static String optionalModel(JsonNode body) {
    JsonNode model = body.get("model");
    return model == null || model.isNull() || model.asString().isBlank() ? null : model.asString();
  }

  static void validateStart(JsonNode body) {
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

  static void validateResume(JsonNode body) {
    JsonNode runId = body.get("runId");
    if (runId == null || !runId.isString() || !RUN_ID.matcher(runId.asString()).matches()) {
      throw new IllegalArgumentException("runId is required and must be a run id.");
    }
    JsonNode approve = body.get("approve");
    if (approve == null || !approve.isBoolean()) {
      throw new IllegalArgumentException("approve is required and must be true or false.");
    }
  }

  private static Map<String, Object> view(Agent.Outcome outcome) {
    var map = new LinkedHashMap<String, Object>();
    map.put("runId", outcome.runId());
    map.put("status", outcome.status());
    map.put("steps", outcome.steps().stream().map(s -> Map.of("name", s.name(), "status", s.status())).toList());
    if (outcome.recommendation() != null) {
      map.put(
          "recommendation",
          Map.of("action", outcome.recommendation().action(), "rationale", outcome.recommendation().rationale()));
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
