package com.customercompass.api.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for documents and search (the Python edition's QDRANT_* and GOOGLE_* variables).
 *
 * @param qdrantHost where Qdrant runs; the Java client talks gRPC
 * @param qdrantGrpcPort Qdrant's gRPC port (6334; 6333 is the REST port)
 * @param collection the Qdrant collection that holds the chunks
 * @param googleApiKey empty means "no key": ingestion says what to set
 * @param embeddingModel the Gemini embedding model
 * @param embeddingDimensions the vector length (Matryoshka: 768 instead of the full 3072)
 * @param sampleDataDir empty means "find sample-data/documents above the working directory"
 */
@ConfigurationProperties("rag")
public record RagProperties(
    @DefaultValue("localhost") String qdrantHost,
    @DefaultValue("6334") int qdrantGrpcPort,
    @DefaultValue("document_chunks") String collection,
    @DefaultValue("") String googleApiKey,
    @DefaultValue("gemini-embedding-001") String embeddingModel,
    @DefaultValue("768") int embeddingDimensions,
    @DefaultValue("") String sampleDataDir) {

  public boolean hasGoogleKey() {
    return googleApiKey != null && !googleApiKey.isBlank();
  }

  /**
   * Names the model behind the vectors, e.g. google:gemini-embedding-001 (stored with each run).
   */
  public String embeddingModelId() {
    return "google:" + embeddingModel;
  }
}
