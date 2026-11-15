package com.customercompass.comparisons.ragingestion.handwritten;

import com.customercompass.comparisons.common.Ingest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns texts into vectors with Gemini's REST API, by hand: one {@code batchEmbedContents} request
 * for up to 100 texts. The key travels in a header, never in the URL, so it cannot end up in a log
 * line or an error message. A failed call is reported by status only; the service's own reply is
 * never repeated (it can echo the request).
 */
final class GeminiEmbedder {

  /** Gemini accepts at most 100 texts in one batch request. */
  static final int MAX_BATCH = 100;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String baseUrl;
  private final String apiKey;
  private final String model;
  private final int dimensions;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private int requests;

  GeminiEmbedder(String baseUrl, String apiKey, String model, int dimensions) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.apiKey = apiKey;
    this.model = model;
    this.dimensions = dimensions;
  }

  int requests() {
    return requests;
  }

  /** One vector per text, in order. */
  List<float[]> embed(List<String> texts) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new Ingest.UnavailableException("GOOGLE_API_KEY is not set. Put it in the environment.");
    }
    var vectors = new ArrayList<float[]>();
    for (int from = 0; from < texts.size(); from += MAX_BATCH) {
      List<String> batch = texts.subList(from, Math.min(from + MAX_BATCH, texts.size()));
      vectors.addAll(embedBatch(batch));
    }
    return vectors;
  }

  private List<float[]> embedBatch(List<String> batch) {
    var items = new ArrayList<Map<String, Object>>();
    for (String text : batch) {
      items.add(
          Map.of(
              "model", "models/" + model,
              "content", Map.of("parts", List.of(Map.of("text", text))),
              "taskType", "RETRIEVAL_DOCUMENT",
              "outputDimensionality", dimensions));
    }
    HttpResponse<String> response;
    try {
      requests++;
      response =
          http.send(
              HttpRequest.newBuilder(
                      URI.create(baseUrl + "/v1beta/models/" + model + ":batchEmbedContents"))
                  .timeout(Duration.ofSeconds(60))
                  .header("Content-Type", "application/json")
                  .header("x-goog-api-key", apiKey)
                  .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("requests", items))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new Ingest.UnavailableException("The embedding service is not reachable.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Ingest.UnavailableException("The embedding call was interrupted.", e);
    }
    if (response.statusCode() / 100 != 2) {
      throw new Ingest.UnavailableException(
          "The embedding service answered HTTP " + response.statusCode() + ".");
    }
    JsonNode embeddings;
    try {
      embeddings = JSON.readTree(response.body()).get("embeddings");
    } catch (JacksonException e) {
      throw new Ingest.UnavailableException("The embedding service sent a reply that is not JSON.");
    }
    if (embeddings == null || !embeddings.isArray() || embeddings.size() != batch.size()) {
      throw new Ingest.UnavailableException(
          "The embedding service returned a different number of vectors than texts.");
    }
    var vectors = new ArrayList<float[]>();
    for (JsonNode embedding : embeddings) {
      JsonNode values = embedding.get("values");
      if (values == null || !values.isArray() || values.size() != dimensions) {
        throw new Ingest.UnavailableException(
            "The embedding service returned a vector that is not " + dimensions + " numbers long.");
      }
      float[] vector = new float[dimensions];
      for (int i = 0; i < dimensions; i++) {
        vector[i] = values.get(i).floatValue();
      }
      vectors.add(vector);
    }
    return vectors;
  }
}
