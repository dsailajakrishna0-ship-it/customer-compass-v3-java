package com.customercompass.comparisons.ragingestion.handwritten;

import com.customercompass.comparisons.common.Ingest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The few calls of Qdrant's REST API that ingestion needs, by hand: delete a collection, create it
 * with a vector size and the cosine distance, and add points (id, vector, payload) in batches.
 */
final class QdrantRest {

  /** Points sent per request. */
  static final int BATCH = 64;

  /** One stored chunk: a number, its vector and the payload that makes it citable. */
  record Point(long id, float[] vector, Map<String, Object> payload) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String baseUrl;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  QdrantRest(String baseUrl) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  /** Replaces the collection: removes it if it exists, then creates it empty. */
  void recreate(String collection, int dimensions) {
    int deleted = call("DELETE", "/collections/" + collection, null);
    if (deleted != 200 && deleted != 404) {
      throw new Ingest.UnavailableException("The vector store answered HTTP " + deleted + ".");
    }
    int created =
        call(
            "PUT",
            "/collections/" + collection,
            Map.of("vectors", Map.of("size", dimensions, "distance", "Cosine")));
    if (created != 200) {
      throw new Ingest.UnavailableException("The vector store answered HTTP " + created + ".");
    }
  }

  void add(String collection, List<Point> points) {
    for (int from = 0; from < points.size(); from += BATCH) {
      var batch = points.subList(from, Math.min(from + BATCH, points.size()));
      var items =
          batch.stream()
              .map(p -> Map.of("id", p.id(), "vector", p.vector(), "payload", p.payload()))
              .toList();
      int status =
          call("PUT", "/collections/" + collection + "/points?wait=true", Map.of("points", items));
      if (status != 200) {
        throw new Ingest.UnavailableException("The vector store answered HTTP " + status + ".");
      }
    }
  }

  private int call(String method, String path, Object body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json");
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    try {
      return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    } catch (IOException e) {
      throw new Ingest.UnavailableException("The vector store is not reachable.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Ingest.UnavailableException("The vector store call was interrupted.", e);
    }
  }
}
