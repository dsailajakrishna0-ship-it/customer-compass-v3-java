package com.customercompass.api.rag;

/** The documents were embedded with a different model from the one now configured. */
public class EmbeddingModelMismatchException extends EmbeddingsUnavailableException {

  public EmbeddingModelMismatchException(String indexed, String current) {
    super(
        "The documents were embedded with "
            + indexed
            + ", but the embedding model is now "
            + current
            + ". Click Ingest documents (or POST /api/v1/documents/ingest) to re-embed them.");
  }
}
