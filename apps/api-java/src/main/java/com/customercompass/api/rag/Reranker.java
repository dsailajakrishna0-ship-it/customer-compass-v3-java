package com.customercompass.api.rag;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An optional second, more careful model that re-orders the first search results (the Python
 * edition's {@code rerank.py}). Search is fast but approximate; a cross-encoder reads the question
 * and each candidate together, which is slower and more accurate, so it only sees a short list. It
 * is a hosted API: Voyage by default, Cohere as the alternative, chosen with {@code
 * RERANK_PROVIDER} (default {@code off}).
 *
 * <p>The one rule: <b>reranking must never break retrieval.</b> Off, no key, slow, rate-limited or
 * answering nonsense all give an empty result and the caller keeps the search order. Failures are
 * logged with the error type and status only, never the key or the provider's reply.
 */
@Component
public class Reranker {

  private static final Logger log = LoggerFactory.getLogger(Reranker.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final RerankProperties properties;
  private final HttpClient client;

  public Reranker(RerankProperties properties) {
    this.properties = properties;
    this.client = HttpClient.newBuilder().connectTimeout(timeout(properties)).build();
  }

  private static Duration timeout(RerankProperties p) {
    return Duration.ofMillis((long) (p.timeoutSeconds() * 1000));
  }

  public boolean enabled() {
    return properties.enabled();
  }

  /**
   * Positions of {@code texts}, best first, at most {@code topN}; empty when reranking is off or
   * did not work (the caller then keeps its own order).
   */
  public Optional<List<Integer>> order(String question, List<String> texts, int topN) {
    if (!properties.enabled() || texts.isEmpty()) {
      return Optional.empty();
    }
    boolean voyage = properties.provider() == RerankProperties.Provider.VOYAGE;
    String name = voyage ? "voyage" : "cohere";
    String key = voyage ? properties.voyageApiKey() : properties.cohereApiKey();
    if (key == null || key.isBlank()) {
      log.warn("rerank_skipped provider={} reason=no_api_key", name);
      return Optional.empty();
    }
    var payload = new LinkedHashMap<String, Object>();
    payload.put(
        "model",
        properties.model().isBlank()
            ? (voyage ? "rerank-2.5-lite" : "rerank-v5.0-fast")
            : properties.model());
    payload.put("query", question);
    payload.put("documents", texts);
    payload.put(voyage ? "top_k" : "top_n", topN);
    Integer status = null;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(voyage ? properties.voyageUrl() : properties.cohereUrl()))
              .timeout(timeout(properties))
              .header("Authorization", "Bearer " + key)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      status = response.statusCode();
      if (status >= 400) {
        throw new java.io.IOException("HTTP " + status);
      }
      Optional<List<Integer>> order =
          parse(JSON.readTree(response.body()), voyage ? "data" : "results", texts.size(), topN);
      if (order.isEmpty()) {
        log.warn("rerank_failed provider={} error=unusable_reply", name);
      }
      return order;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("rerank_failed provider={} error=InterruptedException status={}", name, status);
      return Optional.empty();
    } catch (Exception e) {
      // the error type and status only: the message could echo the request or the key
      log.warn(
          "rerank_failed provider={} error={} status={}",
          name,
          e.getClass().getSimpleName(),
          status);
      return Optional.empty();
    }
  }

  /** The indexes of the candidates, best first, from a provider reply; empty when unusable. */
  static Optional<List<Integer>> parse(JsonNode body, String resultsField, int count, int topN) {
    JsonNode results = body == null ? null : body.get(resultsField);
    if (results == null || !results.isArray()) {
      return Optional.empty();
    }
    var ranked = new ArrayList<JsonNode>();
    results.forEach(
        r -> {
          if (r.isObject()) {
            ranked.add(r);
          }
        });
    ranked.sort((a, b) -> Double.compare(score(b), score(a)));
    var order = new ArrayList<Integer>();
    for (JsonNode item : ranked) {
      JsonNode index = item.get("index");
      if (index != null && index.isInt()) {
        int i = index.asInt();
        if (i >= 0 && i < count && !order.contains(i)) {
          order.add(i);
        }
      }
    }
    if (order.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(List.copyOf(order.subList(0, Math.min(topN, order.size()))));
  }

  private static double score(JsonNode item) {
    JsonNode s = item.get("relevance_score");
    return s != null && s.isNumber() ? s.asDouble() : 0.0;
  }
}
