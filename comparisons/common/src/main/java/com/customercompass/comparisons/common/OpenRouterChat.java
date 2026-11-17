package com.customercompass.comparisons.common;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plain chat by hand: one chat-completions request to an OpenAI-compatible API (OpenRouter) with a
 * list of messages and no tools. The key goes in the Authorization header, there is one attempt, a
 * failure is reported by status only (never by the service's own reply) and no key means no call.
 * Used by the approaches that call the model themselves.
 */
public final class OpenRouterChat {

  /** What the model said and what the call cost. */
  public record Reply(String content, Chat.Usage usage) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String baseUrl;
  private final String apiKey;
  private final String defaultModel;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public OpenRouterChat(String baseUrl, String apiKey, String defaultModel) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.apiKey = apiKey;
    this.defaultModel = defaultModel;
  }

  /** The model used when the client does not pick one. */
  public String defaultModel() {
    return defaultModel;
  }

  /** Sends the messages as they are (a system message first, if any) and returns the model's text. */
  public Reply complete(List<Chat.Message> messages, String model) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new MemoryChat.UnavailableException("OPENROUTER_API_KEY is not set. Put it in the environment.");
    }
    String useModel = model == null ? defaultModel : model;
    var list = new ArrayList<Map<String, Object>>();
    for (Chat.Message message : messages) {
      var item = new LinkedHashMap<String, Object>();
      item.put("role", message.role());
      item.put("content", message.content());
      list.add(item);
    }
    long started = System.nanoTime();
    HttpResponse<String> response;
    try {
      response =
          http.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                  .timeout(Duration.ofSeconds(120))
                  .header("Authorization", "Bearer " + apiKey)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("model", useModel, "messages", list))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new MemoryChat.UnavailableException("The model is not reachable.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MemoryChat.UnavailableException("Interrupted while waiting for the model.", e);
    }
    if (response.statusCode() != 200) {
      throw new MemoryChat.UnavailableException("The model " + useModel + " answered HTTP " + response.statusCode() + ".");
    }
    JsonNode body;
    try {
      body = JSON.readTree(response.body());
    } catch (JacksonException e) {
      throw new MemoryChat.UnavailableException("The model's answer was not valid JSON.");
    }
    JsonNode content = body.path("choices").path(0).path("message").path("content");
    if (!content.isString()) {
      throw new MemoryChat.UnavailableException("The model's answer had no text.");
    }
    JsonNode usage = body.path("usage");
    Integer in = usage.path("prompt_tokens").isNumber() ? usage.path("prompt_tokens").asInt() : null;
    Integer out = usage.path("completion_tokens").isNumber() ? usage.path("completion_tokens").asInt() : null;
    long ms = (System.nanoTime() - started) / 1_000_000;
    return new Reply(content.asString(), new Chat.Usage(useModel, in, out, ms));
  }
}
