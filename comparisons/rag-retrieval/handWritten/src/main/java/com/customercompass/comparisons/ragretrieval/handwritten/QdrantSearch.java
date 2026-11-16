package com.customercompass.comparisons.ragretrieval.handwritten;

import com.customercompass.comparisons.common.Retrieve;
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
 * The one call of Qdrant's REST API that retrieval needs, by hand: {@code points/query} with a
 * vector, a limit and the payload. For a collection built with the cosine distance the score of a
 * point is its cosine similarity, which is what the floor is compared with.
 */
final class QdrantSearch {

  /** One point that came back: its score and its payload. */
  record Point(double score, JsonNode payload) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String baseUrl;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  QdrantSearch(String baseUrl) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  List<Point> search(String collection, float[] vector, int limit) {
    String body = JSON.writeValueAsString(Map.of("query", vector, "limit", limit, "with_payload", true));
    HttpResponse<String> response;
    try {
      response =
          http.send(
              HttpRequest.newBuilder(URI.create(baseUrl + "/collections/" + collection + "/points/query"))
                  .timeout(Duration.ofSeconds(30))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new Retrieve.UnavailableException("The vector store is not reachable.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Retrieve.UnavailableException("The vector store call was interrupted.", e);
    }
    if (response.statusCode() == 404) {
      throw new Retrieve.UnavailableException(
          "The collection " + collection + " does not exist. Run the ingestion for this approach first.");
    }
    if (response.statusCode() / 100 != 2) {
      throw new Retrieve.UnavailableException("The vector store answered HTTP " + response.statusCode() + ".");
    }
    JsonNode points;
    try {
      JsonNode result = JSON.readTree(response.body()).get("result");
      points = result == null ? null : result.get("points");
    } catch (JacksonException e) {
      throw new Retrieve.UnavailableException("The vector store sent a reply that is not JSON.");
    }
    if (points == null || !points.isArray()) {
      throw new Retrieve.UnavailableException("The vector store sent a reply without points.");
    }
    var found = new ArrayList<Point>();
    for (JsonNode point : points) {
      found.add(new Point(point.get("score").asDouble(), point.get("payload")));
    }
    return found;
  }
}
