package com.customercompass.comparisons.common;

import java.util.List;

/**
 * What every RAG-retrieval approach implements: embed the question, ask the vector store for the
 * closest chunks of the approach's own ingestion collection, and keep the ones that are similar
 * enough to cite. The HTTP face ({@link RetrieveBackendServer}) is the same for all of them, so
 * the approaches differ only in what is behind this interface.
 */
public interface Retrieve {

  /** The default similarity floor: the application's calibrated value for gemini-embedding-001 (ADR 0024). */
  double DEFAULT_MIN_SIMILARITY = 0.60;

  /** The default number of chunks asked for. */
  int DEFAULT_TOP_K = 4;

  /** The folder name of the approach, for example {@code handWritten}. */
  String approach();

  /** Searches the approach's collection. {@code topK} and {@code minSimilarity} are already validated. */
  Result search(String query, int topK, double minSimilarity);

  /** One chunk that was kept: its cosine similarity and where it came from. */
  record Hit(double score, String title, String source, String company, int chunkIndex, String text) {}

  /**
   * What one search did. {@code considered} is how many chunks the store returned (at most topK),
   * {@code hits} the ones at or above the floor.
   */
  record Result(
      String collection,
      double minSimilarity,
      int considered,
      List<Hit> hits,
      int embeddingRequests,
      String embeddingModel,
      long latencyMs) {}

  /** The embedding service or the vector store is missing, refused or could not be reached. */
  class UnavailableException extends RuntimeException {
    public UnavailableException(String message) {
      super(message);
    }

    public UnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
