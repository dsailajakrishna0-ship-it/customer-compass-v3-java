package com.customercompass.comparisons.common;

/**
 * What every RAG-ingestion approach implements: read the documents of a folder, split them into
 * chunks, embed the chunks and store them in a vector collection of its own. The HTTP face
 * ({@link IngestBackendServer}) is the same for all of them, so the approaches differ only in what
 * is behind this interface.
 */
public interface Ingest {

  /** The folder name of the approach, for example {@code handWritten}. */
  String approach();

  /**
   * Runs the whole pipeline over the source folder. The collection is replaced, so running twice
   * never duplicates. {@code limit} (null for no limit) keeps only the first documents, in file
   * name order: a way to try a run without spending much embedding quota.
   */
  Result run(Integer limit);

  /** What one run did. {@code embeddingRequests} counts calls to the embedding service. */
  record Result(
      String collection,
      int documents,
      int chunks,
      int embeddingRequests,
      String embeddingModel,
      int dimensions,
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
