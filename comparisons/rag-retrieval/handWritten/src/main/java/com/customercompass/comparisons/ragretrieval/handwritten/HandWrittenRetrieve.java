package com.customercompass.comparisons.ragretrieval.handwritten;

import com.customercompass.comparisons.common.Retrieve;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * RAG retrieval with no AI framework: embed the question, ask Qdrant for the {@code topK} closest
 * chunks of this approach's ingestion collection, keep the ones whose cosine similarity is at or
 * above the floor, best first. The collection is the one {@code handWritten} ingestion built
 * ({@code compare_handwritten}), so its payload keys are the ones that ingestion wrote.
 *
 * <p>Left out on purpose, as in ADR 0036: hybrid keyword search (the ingestion collections hold no
 * keyword vectors) and a reranker (an external service in the application).
 */
public final class HandWrittenRetrieve implements Retrieve {

  static final String COLLECTION = "compare_handwritten";

  private final String embeddingUrl;
  private final String apiKey;
  private final String embeddingModel;
  private final int dimensions;
  private final String qdrantUrl;

  public HandWrittenRetrieve(
      String embeddingUrl, String apiKey, String embeddingModel, int dimensions, String qdrantUrl) {
    this.embeddingUrl = embeddingUrl;
    this.apiKey = apiKey;
    this.embeddingModel = embeddingModel;
    this.dimensions = dimensions;
    this.qdrantUrl = qdrantUrl;
  }

  @Override
  public String approach() {
    return "handWritten";
  }

  @Override
  public Result search(String query, int topK, double minSimilarity) {
    long started = System.nanoTime();
    var embedder = new GeminiQueryEmbedder(embeddingUrl, apiKey, embeddingModel, dimensions);
    float[] vector = embedder.embed(query);
    List<QdrantSearch.Point> points = new QdrantSearch(qdrantUrl).search(COLLECTION, vector, topK);

    var hits = new ArrayList<Hit>();
    for (QdrantSearch.Point point : points) {
      if (point.score() >= minSimilarity) {
        hits.add(hit(point));
      }
    }
    hits.sort((a, b) -> Double.compare(b.score(), a.score()));
    long ms = (System.nanoTime() - started) / 1_000_000;
    return new Result(COLLECTION, minSimilarity, points.size(), hits, embedder.requests(), embeddingModel, ms);
  }

  private static Hit hit(QdrantSearch.Point point) {
    JsonNode payload = point.payload();
    return new Hit(
        point.score(),
        text(payload, "title"),
        text(payload, "source"),
        text(payload, "company"),
        payload != null && payload.has("chunkIndex") ? payload.get("chunkIndex").asInt() : 0,
        text(payload, "text"));
  }

  private static String text(JsonNode payload, String key) {
    return payload != null && payload.has(key) && payload.get(key).isString() ? payload.get(key).asString() : "";
  }
}
