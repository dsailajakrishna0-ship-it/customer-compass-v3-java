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
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP face every conversation-memory approach shares (ADR 0036, the lab contract):
 *
 * <ul>
 *   <li>{@code POST /api/v1/conversations} creates a conversation: 201 with its id and expiry;
 *   <li>{@code POST /api/v1/chat/memory} with {@code {"conversationId", "messages", "model"}} answers one turn;
 *   <li>{@code GET} and {@code DELETE /api/v1/conversations/{id}} read and remove one (204 on delete);
 *   <li>{@code GET /health} and, with a {@link Telemetry}, {@code GET /api/v1/observability/recent}.
 * </ul>
 *
 * Built on the JDK's own server so no approach drags a web framework in. The lab, unlike the
 * application's chat routes, requires the conversation id on every turn.
 */
public final class MemoryChatBackendServer {

  static final int MAX_MESSAGES = 12;
  static final int MAX_CONTENT = 4_000;
  static final int MAX_MODEL = 100;

  private static final String CONVERSATIONS = "/api/v1/conversations";
  private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  private final MemoryChat memory;
  private final Telemetry telemetry;

  public MemoryChatBackendServer(int port, MemoryChat memory) throws IOException {
    this(port, memory, null);
  }

  public MemoryChatBackendServer(int port, MemoryChat memory, Telemetry telemetry) throws IOException {
    this.memory = memory;
    this.telemetry = telemetry;
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    server.setExecutor(BackendThreads.forApproach(memory));
    server.createContext("/health", this::health);
    server.createContext(CONVERSATIONS, this::conversations);
    server.createContext("/api/v1/chat/memory", this::chat);
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
    send(exchange, 200, Map.of("status", "ok", "approach", memory.approach()));
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

  /** {@code /api/v1/conversations} (create) and {@code /api/v1/conversations/{id}} (read, delete). */
  private void conversations(HttpExchange exchange) throws IOException {
    if (cors(exchange)) {
      return;
    }
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();
    try {
      if (path.equals(CONVERSATIONS) || path.equals(CONVERSATIONS + "/")) {
        if (!method.equals("POST")) {
          send(exchange, 405, Map.of("detail", "Method Not Allowed"));
          return;
        }
        MemoryChat.Created created = memory.create();
        var body = new LinkedHashMap<String, Object>();
        body.put("conversationId", created.conversationId());
        body.put("expiresAt", created.expiresAt().toString());
        send(exchange, 201, body);
        return;
      }
      String id = path.substring(CONVERSATIONS.length() + 1);
      if (!ID.matcher(id).matches()) {
        // an id that cannot be one of ours is simply not a conversation
        throw new MemoryChat.ConversationNotFoundException();
      }
      if (method.equals("GET")) {
        MemoryChat.Conversation conversation = memory.get(id);
        var body = new LinkedHashMap<String, Object>();
        body.put("conversationId", conversation.conversationId());
        body.put("messages", conversation.messages().stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList());
        body.put("expiresAt", conversation.expiresAt().toString());
        send(exchange, 200, body);
      } else if (method.equals("DELETE")) {
        memory.delete(id);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
      } else {
        send(exchange, 405, Map.of("detail", "Method Not Allowed"));
      }
    } catch (MemoryChat.ConversationNotFoundException e) {
      send(exchange, 404, Map.of("detail", e.getMessage()));
    } catch (RuntimeException e) {
      send(exchange, 503, Map.of("error", "The conversation could not be read.", "detail", "The conversation could not be read."));
    }
  }

  private void chat(HttpExchange exchange) throws IOException {
    if (cors(exchange)) {
      return;
    }
    if (!exchange.getRequestMethod().equals("POST")) {
      send(exchange, 405, Map.of("detail", "Method Not Allowed"));
      return;
    }
    JsonNode body;
    List<Chat.Message> messages;
    try {
      String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      body = raw.isBlank() ? null : JSON.readTree(raw);
      if (body == null || !body.isObject()) {
        throw new IllegalArgumentException("The body must be a JSON object.");
      }
      messages = validate(body);
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
      MemoryChat.Answer answer = memory.chat(body.get("conversationId").asString(), messages, useModel);
      var out = new LinkedHashMap<String, Object>();
      out.put("approach", memory.approach());
      out.put("message", answer.message());
      out.put("conversationId", answer.conversationId());
      out.put("memory", Map.of("messagesUsed", answer.messagesUsed(), "messagesStored", answer.messagesStored()));
      if (answer.usage() != null) {
        var usage = new LinkedHashMap<String, Object>();
        usage.put("model", answer.usage().model());
        usage.put("tokensIn", answer.usage().tokensIn());
        usage.put("tokensOut", answer.usage().tokensOut());
        usage.put("latencyMs", answer.usage().latencyMs());
        out.put("usage", usage);
      }
      send(exchange, 200, out);
    } catch (MemoryChat.ConversationNotFoundException e) {
      send(exchange, 404, Map.of("detail", e.getMessage()));
    } catch (MemoryChat.UnavailableException e) {
      send(exchange, 503, Map.of("error", e.getMessage(), "detail", e.getMessage()));
    } catch (RuntimeException e) {
      // never the exception's own text: it can carry a URL or a library's message
      send(exchange, 503, Map.of("error", "The conversation could not be answered.", "detail", "The conversation could not be answered."));
    }
  }

  /** Checks the body and returns the new messages. */
  static List<Chat.Message> validate(JsonNode body) {
    JsonNode id = body.get("conversationId");
    if (id == null || !id.isString() || !ID.matcher(id.asString()).matches()) {
      throw new IllegalArgumentException("conversationId is required and must be a conversation id.");
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
      if (content.asString().length() > MAX_CONTENT) {
        throw new IllegalArgumentException("A message content is limited to " + MAX_CONTENT + " characters.");
      }
      messages.add(new Chat.Message(role.asString(), content.asString()));
    }
    if (!messages.get(messages.size() - 1).role().equals("user")) {
      throw new IllegalArgumentException("The last message must be from the user.");
    }
    JsonNode model = body.get("model");
    if (model != null && !model.isNull() && (!model.isString() || model.asString().length() > MAX_MODEL)) {
      throw new IllegalArgumentException("model must be a string of at most " + MAX_MODEL + " characters.");
    }
    return messages;
  }

  private static boolean cors(HttpExchange exchange) throws IOException {
    var headers = exchange.getResponseHeaders();
    headers.set("Access-Control-Allow-Origin", "*");
    headers.set("Access-Control-Allow-Headers", "Content-Type");
    headers.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
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
