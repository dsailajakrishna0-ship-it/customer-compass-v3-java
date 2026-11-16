package com.customercompass.comparisons.ragretrieval.withspringai;

import com.customercompass.comparisons.common.Retrieve;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.document.Document;

/**
 * RAG retrieval with Spring AI: {@code QdrantVectorStore.similaritySearch} embeds the question with
 * the Gemini embedding model and asks Qdrant for the {@code topK} nearest chunks of this approach's
 * ingestion collection ({@code compare_withspringai}); the floor is applied here. The library can
 * apply a similarity threshold itself, but it passes it straight to Qdrant, so the chunks it drops
 * are not counted; searching with no threshold and applying the floor in our code keeps
 * {@code considered} honest (ADR 0037). Hybrid keyword search and a reranker are left out (ADR 0036).
 */
public final class SpringAiRetrieve implements Retrieve {

  static final String COLLECTION = "compare_withspringai";

  /** What one search needs: the store behind its seam, and how many embedding calls it has made. */
  record Target(ChunkSearch search, java.util.function.IntSupplier embeddingRequests) {}

  private final Supplier<Target> target;
  private final String embeddingModel;
  private final boolean hasKey;

  /** The target is built only when a search needs it, so the backend starts without a key. */
  SpringAiRetrieve(boolean hasKey, Supplier<Target> target, String embeddingModel) {
    this.hasKey = hasKey;
    this.target = target;
    this.embeddingModel = embeddingModel;
  }

  @Override
  public String approach() {
    return "withSpringAi";
  }

  @Override
  public Result search(String query, int topK, double minSimilarity) {
    long started = System.nanoTime();
    if (!hasKey) {
      throw new UnavailableException("GOOGLE_API_KEY is not set. Put it in the environment.");
    }
    Target built = target.get();
    List<Document> found = built.search().search(query, topK);

    var hits = new ArrayList<Hit>();
    for (Document document : found) {
      double score = document.getScore() == null ? 0.0 : document.getScore();
      if (score >= minSimilarity) {
        hits.add(hit(score, document));
      }
    }
    hits.sort((a, b) -> Double.compare(b.score(), a.score()));
    long ms = (System.nanoTime() - started) / 1_000_000;
    return new Result(
        COLLECTION, minSimilarity, found.size(), hits, built.embeddingRequests().getAsInt(), embeddingModel, ms);
  }

  private static Hit hit(double score, Document document) {
    Map<String, Object> metadata = document.getMetadata();
    return new Hit(
        score,
        text(metadata, "title"),
        text(metadata, "source"),
        text(metadata, "company"),
        chunkIndex(metadata.get("chunkIndex")),
        document.getText() == null ? "" : document.getText());
  }

  /** The store gives an integer back as a Long, but a value stored from a Java long comes back as text. */
  private static int chunkIndex(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text) {
      try {
        return Integer.parseInt(text.strip());
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    return 0;
  }

  private static String text(Map<String, Object> metadata, String key) {
    return metadata.get(key) instanceof String value ? value : "";
  }
}
