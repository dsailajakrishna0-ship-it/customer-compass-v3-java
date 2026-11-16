package com.customercompass.comparisons.ragretrieval.handwritten;

import com.customercompass.comparisons.common.Retrieve;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the question into one vector with Gemini's REST API, by hand: {@code embedContent} with the
 * task type {@code RETRIEVAL_QUERY}. The key travels in a header, never in the URL; a failed call
 * is reported by status only, never by the service's own reply (it can echo the request).
 */
final class GeminiQueryEmbedder {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String baseUrl;
  private final String apiKey;
  private final String model;
  private final int dimensions;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private int requests;

  GeminiQueryEmbedder(String baseUrl, String apiKey, String model, int dimensions) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.apiKey = apiKey;
    this.model = model;
    this.dimensions = dimensions;
  }

  int requests() {
    return requests;
  }

  float[] embed(String query) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new Retrieve.UnavailableException("GOOGLE_API_KEY is not set. Put it in the environment.");
    }
    var body =
        Map.of(
            "model", "models/" + model,
            "content", Map.of("parts", java.util.List.of(Map.of("text", query))),
            "taskType", "RETRIEVAL_QUERY",
            "outputDimensionality", dimensions);
    HttpResponse<String> response;
    try {
      requests++;
      response =
          http.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/v1beta/models/" + model + ":embedContent"))
                  .timeout(Duration.ofSeconds(60))
                  .header("Content-Type", "application/json")
                  .header("x-goog-api-key", apiKey)
                  .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new Retrieve.UnavailableException("The embedding service is not reachable.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Retrieve.UnavailableException("The embedding call was interrupted.", e);
    }
    if (response.statusCode() / 100 != 2) {
      throw new Retrieve.UnavailableException(
          "The embedding service answered HTTP " + response.statusCode() + ".");
    }
    JsonNode values;
    try {
      JsonNode embedding = JSON.readTree(response.body()).get("embedding");
      values = embedding == null ? null : embedding.get("values");
    } catch (JacksonException e) {
      throw new Retrieve.UnavailableException("The embedding service sent a reply that is not JSON.");
    }
    if (values == null || !values.isArray() || values.size() != dimensions) {
      throw new Retrieve.UnavailableException(
          "The embedding service returned a vector that is not " + dimensions + " numbers long.");
    }
    float[] vector = new float[dimensions];
    for (int i = 0; i < dimensions; i++) {
      vector[i] = values.get(i).floatValue();
    }
    return vector;
  }
}
