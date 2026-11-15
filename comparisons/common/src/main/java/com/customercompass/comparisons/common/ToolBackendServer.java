package com.customercompass.comparisons.common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP face every approach shares: {@code POST /api/v1/chat/tools} with the same body and the
 * same answer shape as the application's tool-calling route, plus {@code GET /health}. Built on
 * the JDK's own server so that no approach drags a web framework in: what differs between the
 * approaches is only the {@link Chat.ToolChat} behind it.
 *
 * <p>Differences from the application's route, on purpose: it takes only the fields messages and
 * model (no mcpServer), it does not run the prompt guard, so the answer has no {@code guardrail},
 * and a bad request gets a short {@code detail} message and not FastAPI's full validation list.
 */
public final class ToolBackendServer {

  static final int MAX_MESSAGES = 12;
  static final int MAX_CONTENT_CHARACTERS = 4_000;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  private final Chat.ToolChat chat;

  public ToolBackendServer(int port, Chat.ToolChat chat) throws IOException {
    this.chat = chat;
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/health", this::health);
    server.createContext("/api/v1/chat/tools", this::tools);
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
    send(exchange, 200, Map.of("status", "ok", "approach", chat.approach()));
  }

  private void tools(HttpExchange exchange) throws IOException {
    if (cors(exchange)) {
      return;
    }
    if (!exchange.getRequestMethod().equals("POST")) {
      send(exchange, 405, Map.of("detail", "Method Not Allowed"));
      return;
    }
    List<Chat.Message> messages;
    String model;
    try {
      JsonNode body = JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      messages = messages(body);
      model = model(body);
    } catch (JacksonException e) {
      send(exchange, 422, Map.of("detail", "The body is not valid JSON."));
      return;
    } catch (IllegalArgumentException e) {
      send(exchange, 422, Map.of("detail", e.getMessage()));
      return;
    }
    try {
      Chat.Result result = chat.answer(messages, model);
      var response = new LinkedHashMap<String, Object>();
      response.put("message", result.message());
      response.put("toolsUsed", result.toolsUsed().stream().map(use -> Map.of("tool", use.tool(), "arguments", use.arguments())).toList());
      response.put("mcpServer", chat.approach());
      var usage = new LinkedHashMap<String, Object>();
      usage.put("model", result.usage().model());
      usage.put("tokensIn", result.usage().tokensIn());
      usage.put("tokensOut", result.usage().tokensOut());
      usage.put("latencyMs", result.usage().latencyMs());
      usage.put("estimatedCost", null);
      usage.put("fallbackUsed", false);
      usage.put("fallbackFromModel", null);
      response.put("usage", usage);
      send(exchange, 200, response);
    } catch (Chat.UnavailableException e) {
      send(exchange, 503, errorBody(e.getMessage()));
    } catch (RuntimeException e) {
      send(exchange, 503, errorBody("Could not answer using tools."));
    }
  }

  private static Map<String, Object> errorBody(String message) {
    return Map.of("error", message, "detail", message);
  }

  /** 1 to 12 messages, a role of user or assistant, content that is not blank and at most 4,000 characters. */
  static List<Chat.Message> messages(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new IllegalArgumentException("The body must be a JSON object.");
    }
    JsonNode list = body.get("messages");
    if (list == null || !list.isArray()) {
      throw new IllegalArgumentException("messages is required and must be a list.");
    }
    if (list.isEmpty() || list.size() > MAX_MESSAGES) {
      throw new IllegalArgumentException("messages must hold 1 to " + MAX_MESSAGES + " items.");
    }
    var messages = new ArrayList<Chat.Message>();
    for (JsonNode item : list) {
      JsonNode role = item.get("role");
      JsonNode content = item.get("content");
      if (role == null || !role.isString() || !(role.asString().equals("user") || role.asString().equals("assistant"))) {
        throw new IllegalArgumentException("A message role must be user or assistant.");
      }
      if (content == null || !content.isString() || content.asString().isBlank()) {
        throw new IllegalArgumentException("A message content must be a non-blank string.");
      }
      if (content.asString().length() > MAX_CONTENT_CHARACTERS) {
        throw new IllegalArgumentException("A message content is limited to " + MAX_CONTENT_CHARACTERS + " characters.");
      }
      messages.add(new Chat.Message(role.asString(), content.asString()));
    }
    if (!messages.get(messages.size() - 1).role().equals("user")) {
      throw new IllegalArgumentException("The last message must be from the user.");
    }
    return messages;
  }

  static String model(JsonNode body) {
    JsonNode model = body.get("model");
    if (model == null || model.isNull()) {
      return null;
    }
    if (!model.isString()) {
      throw new IllegalArgumentException("model must be a string.");
    }
    return model.asString().isBlank() ? null : model.asString();
  }

  /** The browser app runs on another port, so every answer says it may be read; true when preflight was answered. */
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
